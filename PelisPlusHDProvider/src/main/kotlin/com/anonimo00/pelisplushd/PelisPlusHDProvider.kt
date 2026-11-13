package com.anonimo00.pelisplushd

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
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
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
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
 * Proveedor de pelisplushd.la (peliculas, series y animes en espanol latino).
 *
 * El sitio es muy simple: el catalogo, la ficha y los enlaces de los
 * reproductores estan todos en el HTML, sin cifrar ni API.
 *
 *  - Catalogo: `/peliculas`, `/series`, `/animes` (+ `/estrenos`, `/populares`).
 *  - Busqueda: `/search?s=`.
 *  - Ficha: `/pelicula/<slug>`, `/serie/<slug>`, `/anime/<slug>`.
 *  - Episodios: `/serie/<slug>/temporada/N/capitulo/M`.
 *  - Reproductor: los servidores estan en `ul.TbVideoNv li` y sus URLs en
 *    `li[data-url]` (fichas de pelicula) o en `#link_url span[url]`
 *    (fichas de episodio).
 */
class PelisPlusHDProvider : MainAPI() {
    override var mainUrl = "https://pelisplushd.la"
    override var name = "PelisPlusHD"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
    )

    override val mainPage = mainPageOf(
        "peliculas" to "Peliculas",
        "peliculas/estrenos" to "Peliculas estrenos",
        "series" to "Series",
        "animes" to "Animes",
    )

    // ---------- helpers ----------

    /** Traduce la url de la ficha al tipo de contenido de CloudStream. */
    private fun urlType(url: String): TvType = when {
        url.contains("/pelicula/") -> TvType.Movie
        url.contains("/anime/") -> TvType.Anime
        else -> TvType.TvSeries
    }

    /** La nota del sitio viene como "7.6/10". */
    private fun String.toScore(): Score? {
        val value = this.substringBefore("/").trim().toDoubleOrNull() ?: return null
        return Score.from(value, 10)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst("a.Posters-link")?.attr("href") ?: return null
        val title = this.selectFirst("div.listing-content > p")?.text()?.trim()
            ?: this.selectFirst("img.Posters-img")?.attr("alt")?.trim()
                ?.removePrefix("VER ")
                ?.substringBefore(" Online Gratis HD")
                ?.trim()
            ?: return null
        val poster = this.selectFirst("img.Posters-img")?.attr("src")
        val score = this.selectFirst("div.stars .rating span")?.text()?.trim()?.toScore()
        val type = urlType(link)
        return when (type) {
            TvType.TvSeries -> newTvSeriesSearchResponse(title, link, type) {
                this.posterUrl = fixUrlNull(poster)
                this.score = score
            }

            else -> newMovieSearchResponse(title, link, type) {
                this.posterUrl = fixUrlNull(poster)
                this.score = score
            }
        }
    }

    private fun cards(document: Element): List<SearchResponse> =
        document.select("a.Posters-link").mapNotNull { it.toSearchResult() }

    // ---------- main page ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val separator = if (request.data.contains("?")) "&" else "?"
        val document = app.get("$mainUrl/${request.data}${separator}page=$page").document
        val hasNext = document.selectFirst("a[href*=?page=${page + 1}]") != null
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
        val document = app.get("$mainUrl/search?s=$encoded").document
        return cards(document)
    }

    // ---------- load ----------

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = document.selectFirst("img[src^=/poster/]")?.attr("src")
        val description = document.selectFirst("p.text-dark.font-size-13 + div.text-large")
            ?.text()?.trim()
            ?: document.selectFirst("div.text-large")?.text()?.trim()
        val score = document.selectFirst("span.ion-md-star")?.text()?.trim()?.toScore()
        val tags = document.select("a[href^=/generos/][title]").map { it.text().trim() }.distinct()

        var year: Int? = null
        val actors = mutableListOf<String>()
        for (block in document.select("div.sectionDetail")) {
            val label = block.selectFirst("span")?.text()?.trim().orEmpty()
            when {
                label.contains("Fecha de estreno") -> {
                    year = Regex("\\d{4}").find(block.ownText())?.groupValues?.get(0)?.toIntOrNull()
                }

                label.contains("Actores") -> {
                    actors += block.select("a").map { it.text().trim() }.filter { it.isNotBlank() }
                }
            }
        }

        val episodes = document.select("a.btn[href*=/temporada/][href*=/capitulo/]").mapIndexed { index, link ->
            val href = link.attr("href")
            val season = Regex("/temporada/(\\d+)").find(href)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val episode = Regex("/capitulo/(\\d+)").find(href)?.groupValues?.get(1)?.toIntOrNull()
                ?: (index + 1)
            newEpisode(href) {
                this.name = link.text().trim()
                this.season = season
                this.episode = episode
            }
        }

        return if (url.contains("/pelicula/")) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = description ?: ""
                this.tags = tags
                this.year = year
                this.score = score
                this.actors = actors.map { ActorData(Actor(it)) }
            }
        } else {
            newTvSeriesLoadResponse(title, url, urlType(url), episodes) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = description ?: ""
                this.tags = tags
                this.year = year
                this.score = score
                this.actors = actors.map { ActorData(Actor(it)) }
            }
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
        val names = document.select("ul.TbVideoNv li").map { it.text().trim() }

        // En las peliculas las URLs van en el propio <li> y en los episodios
        // en un bloque aparte, asi que se recogen las dos formas.
        val links = LinkedHashMap<String, String>()
        document.select("ul.TbVideoNv li[data-url]").forEachIndexed { index, item ->
            val url = item.attr("data-url")
            if (url.isNotBlank()) links.putIfAbsent(url, names.getOrElse(index) { "Server" })
        }
        document.select("#link_url span[url]").forEachIndexed { index, item ->
            val url = item.attr("url")
            if (url.isNotBlank()) links.putIfAbsent(url, names.getOrElse(index) { "Server" })
        }
        if (links.isEmpty()) return false

        for ((url, name) in links) {
            // Los reproductores (Voe, Streamwish, Vidhide, Vidara...) los
            // resuelve el extractor que CloudStream ya trae.
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
