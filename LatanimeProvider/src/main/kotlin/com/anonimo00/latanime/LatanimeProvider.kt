package com.anonimo00.latanime

import android.util.Base64
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * Proveedor de latanime.org (animes en espanol latino).
 *
 * Laravel con Bootstrap. Solo tiene animes y cada temporada es un anime
 * distinto (`/anime/<slug>`), por lo que no hay concepto de temporada: la
 * ficha trae la lista de episodios (`/ver/<slug>-episodio-N`).
 *
 * Los reproductores estan en `a.play-video[data-player]`, que es la URL del
 * reproductor en base64 (sin cifrar). El sitio los mete en un iframe a traves
 * de su propio proxy (`/reproductor?url=<base64>`), que solo redirige.
 */
class LatanimeProvider : MainAPI() {
    override var mainUrl = "https://latanime.org"
    override var name = "Latanime"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime)

    override val mainPage = mainPageOf(
        "animes" to "Animes",
        "emision" to "En emision",
    )

    // ---------- helpers ----------

    private fun Element.toSearchResponse(): SearchResponse? {
        val link = this.selectFirst("a[href*=/anime/]")?.attr("href") ?: return null
        val title = this.selectFirst("div.seriedetails h3.my-1")?.text()?.trim()
            ?: this.selectFirst("div.serieimg img")?.attr("alt")?.trim()
            ?: return null
        val poster = this.selectFirst("div.serieimg img")?.attr("data-src")
            ?: this.selectFirst("div.serieimg img")?.attr("src")
        val year = this.select("div.seriedetails span").lastOrNull()?.text()?.trim()?.toIntOrNull()
        return newTvSeriesSearchResponse(title, link, TvType.Anime) {
            this.posterUrl = fixUrlNull(poster)
            this.year = year
        }
    }

    private fun cards(document: Element): List<SearchResponse> =
        document.select("a[href*=/anime/]").mapNotNull { it.toSearchResponse() }

    // ---------- main page ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}").document
        val hasNext = document.selectFirst(".page-item a.page-link[href*=\"page=$((page + 1))\"]") != null
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

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val document = app.get("$mainUrl/buscar?q=$encoded").document
        return cards(document)
    }

    // ---------- load ----------

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h2.mojon3, .col-lg-9 h2")?.text()?.trim()
            ?: document.selectFirst("h2")?.text()?.trim()
            ?: return null
        val poster = document.selectFirst("img.img-fluid2")?.attr("src")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("p.my-2.opacity-75")?.text()?.trim()
        val tags = document.select("a[href*=/genero/] div.btn").map { it.text().trim() }.distinct()

        var year: Int? = null
        var episodes = 0
        for (paragraph in document.select("p")) {
            val text = paragraph.text()
            year = Regex("""\b(19|20)\d{2}\b""").find(text)?.groupValues?.get(0)?.toIntOrNull() ?: year
            episodes = Regex("""Episodios:\s*(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()
                ?: episodes
        }

        val episodeList = document.select("a[href*=/ver/]").mapIndexed { index, link ->
            val href = link.attr("href")
            val number = Regex("""-episodio-(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                ?: (index + 1)
            newEpisode(href) {
                this.name = link.text().trim().substringAfterLast("-episodio-").let { "Episodio $it" }
                this.season = 1
                this.episode = number
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodeList) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = description ?: ""
            this.tags = tags
            this.year = year
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
        val players = document.select("a.play-video[data-player]")
        if (players.isEmpty()) return false

        for (player in players) {
            val encoded = player.attr("data-player")
            if (encoded.isBlank()) continue
            // El atributo es la url del reproductor en base64, sin cifrar.
            val url = runCatching {
                String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
            }.getOrNull() ?: continue
            if (!url.startsWith("http")) continue

            val name = player.text().trim().ifEmpty { "Server" }
            loadExtractor(url, data, subtitleCallback) { extractorLink ->
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
        return true
    }
}

