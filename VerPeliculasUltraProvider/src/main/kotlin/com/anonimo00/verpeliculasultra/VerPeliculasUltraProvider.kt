package com.anonimo00.verpeliculasultra

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
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * Proveedor de verpeliculasultra.com (peliculas en espanol latino).
 *
 * CMS DataLife Engine (DLE). Solo tiene peliculas.
 *
 * Los reproductores vienen en el propio HTML (`div.play-btn-cont[data-src]`,
 * uno por pestana de idioma: Latino, Lat, Subtitulada y Sub). El de la familia
 * `hgcloud.to` es un rotador de dominio: su `main.js` salta a uno de los
 * dominios principales y ya en el dominio bueno la pagina del reproductor
 * lleva la playlist empaquetada con P.A.C.K.E.R (`links.hls4` / `hls3` / `hls2`).
 */
class VerPeliculasUltraProvider : MainAPI() {
    override var mainUrl = "https://verpeliculasultra.com"
    override var name = "VerPeliculasUltra"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "lastnews/" to "Peliculas",
        "accion/" to "Accion",
        "comedia/" to "Comedia",
        "drama/" to "Drama",
        "terror/" to "Terror",
    )

    // ---------- helpers ----------

    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst("div.shortf-img > a[href]")?.attr("href") ?: return null
        val title = this.selectFirst("h3.shortf-link a b")?.text()?.trim()
            ?: this.selectFirst("div.shortf-img > a[title]")?.attr("title")?.trim()
            ?: return null
        val poster = this.selectFirst("div.shortf-img img[src]")?.attr("src")
        val quality = this.selectFirst("div.shortf-quality")?.text()?.trim().orEmpty()
        val year = Regex("""\b(19|20)\d{2}\b""").find(quality)?.groupValues?.get(0)?.toIntOrNull()
        val score = Regex("""([\d.,]+)""").findAll(quality).lastOrNull()?.groupValues?.get(1)
            ?.replace(",", ".")?.toDoubleOrNull()?.let { Score.from(it, 10) }
        return newMovieSearchResponse(title, link, TvType.Movie) {
            this.posterUrl = fixUrlNull(poster)
            this.year = year
            this.score = score
        }
    }

    private fun cards(document: Element): List<SearchResponse> =
        document.select("#dle-content div.shortf").mapNotNull { it.toSearchResult() }

    // ---------- main page ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}").document
        val hasNext = document.selectFirst("div.pages a[href*=\"page=$((page + 1))\"]") != null
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
        val document = app.get("$mainUrl/index.php?do=search&subaction=search&story=$encoded").document
        return cards(document)
    }

    // ---------- load ----------

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("header.h-red h1 b")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: return null
        val poster = document.selectFirst("div.film-poster img[src]")?.attr("src")

        var year: Int? = null
        val tags = mutableListOf<String>()
        for (item in document.select("div.film-info li")) {
            val label = item.selectFirst("span.f-info-title")?.text()?.trim().orEmpty()
            val value = item.selectFirst("span.f-info-text")?.text()?.trim().orEmpty()
            when {
                label.startsWith("AÃ±o") -> year = value.toIntOrNull()
                label.startsWith("GÃ©neros") || label.startsWith("Generos") -> {
                    tags += item.select("span.f-info-text a").map { it.text().trim() }
                }
            }
        }
        val description = document.selectFirst("section#bar-content")?.text()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()
        val score = document.selectFirst("div.site-rating span.imdb b")?.text()?.trim()
            ?.replace(",", ".")?.toDoubleOrNull()?.let { Score.from(it, 10) }

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
        val document = app.get(data, headers = mapOf("Referer" to "$mainUrl/")).document
        val servers = document.select("div.play-btn-cont[data-src]")
        if (servers.isEmpty()) return false

        var found = false
        for (server in servers) {
            val url = server.attr("data-src")
            if (!url.startsWith("http")) continue
            found = true
            val name = server.closest("div.tabs-sidebar-block")?.id() ?: "Server"

            if (url.contains("hgcloud.to")) {
                val playlist = resolveHgCloud(url) ?: continue
                callback.invoke(
                    newExtractorLink(name, name, playlist, ExtractorLinkType.M3U8) {
                        this.referer = url
                    }
                )
            } else {
                loadExtractor(url, data, subtitleCallback) { extractorLink ->
                    CoroutineScope(Dispatchers.IO).launch {
                        callback.invoke(
                            newExtractorLink(name, name, extractorLink.url) {
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
        }
        return found
    }

    /**
     * El reproductor `hgcloud.to` redirige (en su `main.js`) a uno de los
     * dominios principales. Se prueban en orden hasta que uno responde con la
     * pagina del reproductor, de la que se desempaqueta la playlist.
     */
    private suspend fun resolveHgCloud(url: String): String? {
        val id = url.substringAfterLast("/e/").substringBefore("?")
        for (domain in HG_CLOUD_DOMAINS) {
            val html = app.get(
                "https://$domain/e/$id",
                headers = mapOf("Referer" to "$mainUrl/"),
            ).text
            val unpacked = getAndUnpack(html)
            val links = HLS_REGEX.findAll(unpacked).associate { it.groupValues[1] to it.groupValues[2] }
            if (links.isEmpty()) continue
            // `hls4` es relativa al dominio elegido, `hls2` suele ser absoluta.
            val playlist = links["hls4"] ?: links["hls3"] ?: links["hls2"] ?: continue
            return if (playlist.startsWith("http")) {
                playlist
            } else {
                "https://$domain$playlist"
            }
        }
        return null
    }

    companion object {
        /** Dominios principales a los que salta el rotador de hgcloud.to. */
        private val HG_CLOUD_DOMAINS = listOf("audinifer.com", "hanerix.com", "vibuxer.com")

        private val HLS_REGEX = Regex(""""(hls\d)"\s*:\s*"([^"]+)"""")
    }
}

