package com.anonimo00.flixlatam

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
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
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
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
 * Proveedor de flixlatam.com (peliculas, series y animes en espanol latino).
 *
 * El sitio usa el tema Dooplay: el catalogo esta en el HTML publico y el
 * reproductor (`/vidurl/...`) trae los enlaces de los servidores cifrados con
 * AES (ver [FlixLatamPlayer]).
 */
class FlixLatamProvider : MainAPI() {
    override var mainUrl = "https://flixlatam.com"
    override var name = "FlixLatam"
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

    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst("div.poster a[href]")?.attr("href")
            ?: this.selectFirst("div.data h2 a[href]")?.attr("href")
            ?: return null
        val title = this.selectFirst("div.data h2 a")?.text()?.trim()
            ?: this.selectFirst("div.poster img")?.attr("alt")?.trim()
            ?: return null
        val poster = this.selectFirst("div.poster img")?.attr("src")
        val year = this.selectFirst("div.data span")?.text()?.trim()?.toIntOrNull()
        return when (urlType(link)) {
            TvType.TvSeries -> newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }

            TvType.Anime -> newAnimeSearchResponse(title, link, TvType.Anime) {
                this.posterUrl = poster
                this.year = year
            }

            else -> newMovieSearchResponse(title, link, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    private fun cards(document: Element): List<SearchResponse> =
        document.select("article.item").mapNotNull { it.toSearchResult() }

    // ---------- main page ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}?page=$page").document
        // El tema Dooplay marca la paginacion con `?page=N`.
        val hasNext = document.selectFirst("a[href*=\"?page=${page + 1}\"]") != null
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
        val meta = document.schemaData()
        val title = meta?.name ?: document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = meta?.image ?: document.selectFirst("div.poster img")?.attr("src")
        val description = meta?.description
            ?: document.selectFirst(".wp-content p.no-synopsis, .wp-content p")?.text()?.trim()
        val year = meta?.datePublished?.take(4)?.toIntOrNull()
        val tags = meta?.genre ?: emptyList()
        val rating = meta?.rating

        val episodes = document.select("#seasons div.se-a[data-season]").flatMap { season ->
            val seasonNumber = season.attr("data-season").toIntOrNull() ?: 1
            season.select("ul.episodios li").mapIndexed { index, item ->
                val link = item.selectFirst("a.episode-link") ?: return@mapIndexed null
                val href = link.attr("href")
                val fragment = Regex("""#s(\d+)-e(\d+)""").find(href)
                val episodeNumber = fragment?.groupValues?.get(2)?.toIntOrNull()
                    ?: item.selectFirst("div.numerando")?.text()?.trim()
                        ?.substringAfter("-")
                        ?.trim()
                        ?.toIntOrNull()
                    ?: (index + 1)
                val episodeUrl = href.substringBefore("#").let { path ->
                    "$path/temporada/$seasonNumber/capitulo/$episodeNumber"
                }
                newEpisode(episodeUrl) {
                    this.name = link.text().trim()
                    this.season = seasonNumber
                    this.episode = episodeNumber
                    this.posterUrl = item.selectFirst("img")?.attr("src")
                }
            }
        }.filterNotNull()

        return if (url.contains("/pelicula/")) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description ?: ""
                this.tags = tags
                this.year = year
                this.score = Score.from(rating, 10)
            }
        } else {
            newTvSeriesLoadResponse(title, url, urlType(url), episodes) {
                this.posterUrl = poster
                this.plot = description ?: ""
                this.tags = tags
                this.year = year
                this.score = Score.from(rating, 10)
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
        val links = FlixLatamPlayer.resolve(data)
        var found = false
        for (link in links) {
            found = true
            // Los enlaces apuntan a reproductores externos (Voe, Streamwish,
            // Vidhide...) que CloudStream ya trae extractores. El referer es la
            // pagina del reproductor, igual que en el reproductor del sitio.
            loadExtractor(link.url, link.referer, subtitleCallback) { extractorLink ->
                // `newExtractorLink` es suspend y el callback no lo es, asi que
                // hace falta un scope para emitir el enlace al reproductor.
                CoroutineScope(Dispatchers.IO).launch {
                    callback.invoke(
                        newExtractorLink(
                            link.name,
                            link.name,
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

    // ---------- schema.org ----------

    /**
     * El tema Dooplay incrusta los datos de la ficha en JSON-LD (schema.org).
     */
    private fun Element.schemaData(): SchemaData? {
        for (block in this.select("script[type=application/ld+json]").map { it.data() }) {
            val node = runCatching { mapper.readTree(block) }.getOrNull() ?: continue
            if (node["@type"]?.asText() !in SCHEMA_TYPES) continue
            return SchemaData(
                name = node["name"]?.asText(),
                description = node["description"]?.asText(),
                image = node["image"]?.asText(),
                datePublished = node["datePublished"]?.asText(),
                genre = node["genre"]?.mapNotNull { it.asText() } ?: emptyList(),
                rating = node["aggregateRating"]?.get("ratingValue")?.asDouble(),
            )
        }
        return null
    }
}

/** Tipos de schema.org que contienen los datos de la ficha. */
private val SCHEMA_TYPES = setOf("Movie", "TVSeries", "TVEpisode", "CreativeWorkSeries")

/** Datos de la ficha extraidos del JSON-LD de schema.org. */
@JsonIgnoreProperties(ignoreUnknown = true)
internal data class SchemaData(
    val name: String? = null,
    val description: String? = null,
    val image: String? = null,
    val datePublished: String? = null,
    val genre: List<String> = emptyList(),
    val rating: Double? = null,
)
