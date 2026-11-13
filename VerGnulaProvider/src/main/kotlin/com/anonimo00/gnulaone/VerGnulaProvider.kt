package com.anonimo00.gnulaone

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.jsoup.nodes.Element

/**
 * Proveedor de ver.gnula.one (peliculas en espanol latino).
 *
 * WordPress con tema propio. En este host solo hay peliculas (no series).
 *
 * Ojo: la busqueda `/?s=` esta bloqueada por Cloudflare, asi que se usa el
 * endpoint de sugerencias del propio tema
 * (`wp-json/gnula/v1/suggest?q=`), que devuelve JSON.
 *
 * El reproductor son dos pasos: cada servidor lleva a una pagina intermedia
 * (`?trembed=N&trid=<id>&trtype=1`) que a su vez trae el iframe con el
 * reproductor real (Voe, Streamwish, Vidhide...).
 */
class VerGnulaProvider : MainAPI() {
    override var mainUrl = "https://ver.gnula.one"
    override var name = "Gnula (.one)"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "peliculas/" to "Peliculas",
        "peliculas/latino/" to "Latino",
        "peliculas/castellano/" to "Castellano",
    )

    // ---------- helpers ----------

    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst("a.gn-card.gn-card--grid")?.attr("href") ?: return null
        val title = this.selectFirst(".gn-card__cap .gn-card__t")?.text()?.trim()
            ?: this.selectFirst(".gn-card__poster img")?.attr("alt")?.trim()
            ?: return null
        val poster = this.selectFirst(".gn-card__poster img")?.attr("src")
        val year = this.selectFirst(".gn-card__cap .gn-card__y")?.text()?.trim()?.toIntOrNull()
        val score = this.selectFirst(".gn-card__over .gn-card__rating")?.text()?.trim()
            ?.replace(",", ".")?.toDoubleOrNull()?.let { Score.from(it, 10) }
        return newMovieSearchResponse(title, link, TvType.Movie) {
            this.posterUrl = fixUrlNull(poster)
            this.year = year
            this.score = score
        }
    }

    private fun cards(document: Element): List<SearchResponse> =
        document.select("a.gn-card.gn-card--grid").mapNotNull { it.toSearchResult() }

    // ---------- main page ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}page/$page/").document
        val hasNext = document.selectFirst(".gn-pagination a[href*=\"page=$((page + 1))\"]") != null
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = cards(document),
                isHorizontalImages = false,
            ),
            hasNext = hasNext,
        )
    }

    // ---------- search ----------

    /**
     * La busqueda normal (`/?s=`) la bloquea Cloudflare, asi que se usa el
     * endpoint de sugerencias del tema, que ya devuelve los resultados en JSON.
     */
    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val response = app.get("$mainUrl/wp-json/gnula/v1/suggest?q=$encoded")
        val array = runCatching { JSONArray(response.text) }.getOrNull() ?: return emptyList()
        val results = mutableListOf<SearchResponse>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val url = item.optString("url")
            val title = item.optString("title")
            if (url.isBlank() || title.isBlank()) continue
            results += newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = fixUrlNull(item.optString("thumb"))
                this.year = item.optString("year").toIntOrNull()
            }
        }
        return results
    }

    // ---------- load ----------

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst(".gn-movie__title h1 a")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: return null
        val poster = document.selectFirst(".gn-info__poster img")?.attr("src")
        val description = document.selectFirst(".gn-synopsis__text p")?.text()?.trim()
        val score = document.selectFirst(".gn-facts__rating strong")?.text()?.trim()
            ?.replace(",", ".")?.toDoubleOrNull()?.let { Score.from(it, 10) }
        val tags = document.select(".gn-genres a").map { it.text().trim() }.distinct()

        var year: Int? = null
        val facts = document.select(".gn-info__facts li")
        year = facts.firstOrNull { it.text().contains("/release/") }
            ?.selectFirst("a")?.text()?.trim()?.toIntOrNull()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = description ?: ""
            this.tags = tags
            this.year = year
            this.score = score
        }
    }

    // ---------- links ----------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val document = app.get(data).document
        val servers = document.select("a.gn-srv[href]")
        if (servers.isEmpty()) return false

        var found = false
        for (server in servers) {
            val path = server.attr("href")
            if (path.isBlank()) continue
            found = true
            val name = server.text().replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
                .ifEmpty { "Server" }

            // Pagina intermedia: devuelve el iframe con el reproductor real.
            val response = app.get(
                if (path.startsWith("http")) path else "$mainUrl$path",
                headers = mapOf("Referer" to data),
            )
            val embed = response.document.selectFirst("div.Video iframe[src]")?.attr("src")
                ?: continue
            if (!embed.startsWith("http")) continue

            loadExtractor(embed, data, subtitleCallback) { extractorLink ->
                CoroutineScope(Dispatchers.IO).launch {
                    callback.invoke(
                        newExtractorLink(
                            name,
                            name,
                            extractorLink.url,
                        ) {
                            this.quality = extractorLink.quality
                            this.type = extractorLink.type
                            this.referer = extractorLink.referer
                            this.headers = extractorLink.headers
                            this.extractorData = extractorLink.extractorData
                        }
                    )
                }
            }
        }
        return found
    }
}

