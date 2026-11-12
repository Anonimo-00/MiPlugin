package com.anonimo00.sololatino

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
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
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * Proveedor de sololatino.net (peliculas, series, animes y doramas en
 * espanol latino).
 *
 * El sitio se apoya en un backend Laravel/Sanctum que protege sus endpoints,
 * por lo que el catalogo se lee del HTML publico mientras que los enlaces de
 * reproduccion se resuelven a traves de la API (ver [SoloLatinoPlayer]).
 */
class SoloLatinoProvider : MainAPI() {
    override var mainUrl = "https://sololatino.net"
    override var name = "SoloLatino"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Cartoon,
    )

    override val mainPage = mainPageOf(
        "peliculas" to "Peliculas",
        "series" to "Series",
        "animes" to "Animes",
        "doramas" to "Doramas",
    )

    // ---------- helpers ----------

    /** Traduce la etiqueta de la tarjeta al tipo de contenido de CloudStream. */
    private fun cardType(badge: String?, url: String): TvType = when (badge?.trim()?.lowercase()) {
        "pelicula" -> TvType.Movie
        "anime" -> TvType.Anime
        "dorama" -> TvType.AsianDrama
        "dibujos" -> TvType.Cartoon
        "serie" -> TvType.TvSeries
        else -> if (url.contains("/pelicula/")) TvType.Movie else TvType.TvSeries
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst("a[href]")?.attr("href") ?: return null
        val title = this.selectFirst("p.card__title")?.text()?.trim()
            ?: this.selectFirst("span.card__title")?.text()?.trim()
            ?: this.selectFirst("img.card__poster")?.attr("alt")?.trim()
            ?: return null
        val poster = this.selectFirst("img.card__poster")?.attr("src")
        val year = this.selectFirst("span.card__year")?.text()?.trim()?.toIntOrNull()
        val type = cardType(this.selectFirst("span.card__badge")?.text(), link)
        return when (type) {
            TvType.TvSeries -> newTvSeriesSearchResponse(title, link, type) {
                this.posterUrl = poster
                this.year = year
            }

            TvType.Anime -> newAnimeSearchResponse(title, link, type) {
                this.posterUrl = poster
                this.year = year
            }

            else -> newMovieSearchResponse(title, link, type) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    private fun cards(document: Element): List<SearchResponse> =
        document.select("div.card").mapNotNull { it.toSearchResult() }

    // ---------- main page ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val separator = if (request.data.contains("?")) "&" else "?"
        val document = app.get("$mainUrl/${request.data}${separator}page=$page").document
        // El sitio marca la siguiente pagina con <link rel="next">.
        val hasNext = document.selectFirst("link[rel=next]") != null
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
        val meta = document.schemaData()
        val title = meta?.name ?: document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = meta?.image ?: document.selectFirst("img.w-44")?.attr("src")
        val background = document.selectFirst(".detail-hero__bg")?.attr("style")
            ?.substringAfter("url('")
            ?.substringBefore("');")
        val description = meta?.description
            ?: document.selectFirst("p.text-sm.leading-relaxed")?.text()?.trim()
        val year = meta?.datePublished?.take(4)?.toIntOrNull()
        val tags = meta?.genre ?: emptyList()
        val recommendations = document.select("div[id*=scroll-related] div.card")
            .mapNotNull { it.toSearchResult() }

        val episodes = document.select("div[data-season-panel]").flatMap { panel ->
            val season = panel.attr("data-season-panel").toIntOrNull() ?: 1
            panel.select("a.ep-item").mapIndexed { index, episode ->
                val episodeUrl = episode.attr("href")
                val name = episode.selectFirst("p.text-sm.font-semibold.text-white.leading-tight")
                    ?.text()?.trim()
                val thumb = episode.selectFirst("img.ep-thumb")?.attr("src")
                // El numero de episodio viene como "E12"; si no se puede leer se usa el orden.
                val number = episode.selectFirst("p.ep-num")?.text()?.trim()
                    ?.removePrefix("E")
                    ?.removePrefix("e")
                    ?.toIntOrNull()
                    ?: (index + 1)
                newEpisode(episodeUrl) {
                    this.name = name
                    this.season = season
                    this.episode = number
                    this.posterUrl = thumb
                }
            }
        }

        return if (url.contains("/pelicula/")) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background ?: poster
                this.plot = description ?: ""
                this.tags = tags
                this.year = year
                this.recommendations = recommendations
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background ?: poster
                this.plot = description ?: ""
                this.tags = tags
                this.year = year
                this.recommendations = recommendations
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
        val links = SoloLatinoPlayer.resolve(data)
        for (link in links) {
            callback.invoke(
                newExtractorLink(
                    link.name,
                    link.name,
                    link.url,
                    if (link.isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                ) {
                    this.referer = link.referer ?: ""
                }
            )
        }
        return links.isNotEmpty()
    }

    // ---------- schema.org ----------

    /**
     * El sitio incrusta los datos de la ficha en JSON-LD (schema.org), que es
     * mas fiable que los selectores CSS porque no cambia con el maquetado.
     */
    private fun Element.schemaData(): SchemaData? {
        for (block in this.select("script[type=application/ld+json]").map { it.data() }) {
            val tree = runCatching { mapper.readTree(block) }.getOrNull() ?: continue
            val graph = tree["@graph"] ?: continue
            val node = graph.find { it["@type"]?.asText() in SCHEMA_TYPES } ?: continue
            return SchemaData(
                name = node["name"]?.asText(),
                description = node["description"]?.asText(),
                image = node["image"]?.asText() ?: node["thumbnailUrl"]?.asText(),
                datePublished = node["datePublished"]?.asText(),
                genre = node["genre"]?.mapNotNull { it.asText() } ?: emptyList(),
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
)
