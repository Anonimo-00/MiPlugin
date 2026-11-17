package com.anonimo00.serieslan

import com.anonimo00.shared.SearchHelper
import com.anonimo00.shared.VimeosBridge
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject

/**
 * Proveedor de serieslan.live (series y animes en espanol latino).
 *
 * El sitio es una SPA React: todo el contenido sale de su API
 * (`/api/rest/v1/`), sin necesidad de Cloudflare ni de sesiÃ³n.
 *
 *  - CatÃ¡logo: `GET /listing?kind=serie&order=recent&page=N`
 *  - BÃºsqueda: `GET /search?q=`
 *  - Episodios: `GET /episode-seasons?item_id=..` y `GET /episodes?item_id=..&season=N`
 *  - Reproductor: el episodio trae la url del player
 *    (`player.serieslan.live/e/tv/<tmdb>/<temporada>/<episodio>`), que a su vez
 *    mete un iframe de vimeos con un token. El m3u8 va en claro dentro de la
 *    pagina del reproductor (`window.VIDAPI_CF_STREAM_RESPONSE`).
 */
class SeriesLanProvider : MainAPI() {
    override var mainUrl = "https://serieslan.live"
    override var name = "SeriesLan"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    /** La busqueda es una sola peticion: sirve para la busqueda rapida de la app. */
    override val hasQuickSearch = true

    /** La busqueda rapida reutiliza la busqueda normal. */
    override suspend fun quickSearch(query: String): List<SearchResponse> =
        SearchHelper.quickSearch(name, query) { searchPage(it, 1) }
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Anime)

    override val mainPage = mainPageOf(
        "recent" to "Recientes",
        "views_today" to "Vistos hoy",
        "top_rated" to "Mejor valorados",
    )

    private val apiBase = "$mainUrl/api/rest/v1"

    // ---------- helpers ----------

    private fun posterOf(item: JSONObject): String? {
        val path = item.optString("poster_path")
        if (path.isBlank()) return null
        return "https://image.tmdb.org/t/p/w500$path"
    }

    private fun JSONObject.toSearchResponse(): SearchResponse? {
        val slug = optString("slug")
        val title = optString("title")
        if (slug.isBlank() || title.isBlank()) return null
        val url = optString("url").ifBlank { "/serie/$slug" }
        val year = optInt("year", 0).takeIf { it > 0 }
        val score = optDouble("vote_average", 0.0).takeIf { it > 0.0 }?.let { Score.from(it, 10) }
        return newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
            this.posterUrl = posterOf(this@toSearchResponse)
            this.year = year
            this.score = score
        }
    }

    private fun JSONObject.items(): List<JSONObject> {
        val array = optJSONArray("items") ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    // ---------- main page ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val response = app.get("$apiBase/listing?kind=serie&order=${request.data}&page=$page&limit=24")
        val root = runCatching { JSONObject(response.text) }.getOrNull()
        val list = root?.items()?.mapNotNull { it.toSearchResponse() } ?: emptyList()
        val pagination = root?.optJSONObject("pagination")
        val hasNext = pagination?.optBoolean("has_next") ?: false
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = list,
                isHorizontalImages = false,
            ),
            hasNext = hasNext,
        )
    }

    // ---------- search ----------

    override suspend fun search(query: String): List<SearchResponse> =
        SearchHelper.smartSearch(name, query) { searchPage(it, 1) }

    /** Busqueda paginada: la API devuelve la paginacion en `pagination`. */
    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val response = app.get("$apiBase/search?q=$encoded&page=$page&limit=24")
        val root = runCatching { JSONObject(response.text) }.getOrNull() ?: return null
        val list = root.items().mapNotNull { it.toSearchResponse() }
        val pagination = root.optJSONObject("pagination")
        val hasNext = pagination?.optBoolean("has_next") ?: false
        return newSearchResponseList(list, hasNext)
    }

    private suspend fun searchPage(query: String, page: Int): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val response = app.get("$apiBase/search?q=$encoded&page=$page&limit=24")
        val root = runCatching { JSONObject(response.text) }.getOrNull()
        return root?.items()?.mapNotNull { it.toSearchResponse() } ?: emptyList()
    }

    // ---------- load ----------

    override suspend fun load(url: String): LoadResponse? {
        val slug = url.trimEnd('/').substringAfterLast('/')
        val response = app.get("$apiBase/content/serie/$slug")
        val root = runCatching { JSONObject(response.text) }.getOrNull() ?: return null
        val item = root.optJSONObject("item") ?: return null

        val title = item.optString("title").ifBlank { slug }
        val poster = posterOf(item)
        val description = item.optString("overview").ifBlank { root.optString("overview") }
        val score = item.optDouble("vote_average", 0.0).takeIf { it > 0.0 }?.let { Score.from(it, 10) }
        val year = item.optInt("year", 0).takeIf { it > 0 }
        val genres = item.optJSONArray("genres")?.let { array ->
            (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("title") }
        } ?: emptyList()

        val itemId = item.optInt("catalog_item_id", 0)
        val episodes = episodesOf(itemId)

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
            this.tags = genres
            this.year = year
            this.score = score
        }
    }

    /** Episodios de todas las temporadas de la serie. */
    private suspend fun episodesOf(itemId: Int): List<com.lagradost.cloudstream3.Episode> {
        if (itemId <= 0) return emptyList()
        val seasonsResponse = app.get("$apiBase/episode-seasons?item_id=$itemId")
        val seasonsRoot = runCatching { JSONObject(seasonsResponse.text) }.getOrNull()
        val seasons = seasonsRoot?.optJSONArray("seasons")?.let { array ->
            (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optInt("season") }
        } ?: emptyList()

        val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
        for (season in seasons.ifEmpty { listOf(1) }) {
            val response = app.get("$apiBase/episodes?item_id=$itemId&season=$season&limit=100&page=1")
            val root = runCatching { JSONObject(response.text) }.getOrNull() ?: continue
            val array = root.optJSONArray("episodes") ?: continue
            for (index in 0 until array.length()) {
                val episode = array.optJSONObject(index) ?: continue
                val path = episode.optString("url")
                val player = episode.optString("player_url")
                if (path.isBlank() || player.isBlank()) continue
                // La API devuelve rutas relativas: se guardan absolutas porque
                // el par (url, player) no pasa por fixUrl().
                val absolute = if (path.startsWith("http")) path else "$mainUrl$path"
                episodes += newEpisode<Pair<String, String>>(Pair(absolute, player)) {
                    this.name = episode.optString("title").ifBlank { "Episodio ${episode.optInt("episode")}" }
                    this.season = episode.optInt("season", season)
                    this.episode = episode.optInt("episode")
                    this.posterUrl = episode.optString("still_path").takeIf { it.isNotBlank() }
                        ?.let { "https://image.tmdb.org/t/p/w300$it" }
                }
            }
        }
        return episodes
    }

    // ---------- links ----------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // El dato lleva la url del episodio y la del player.
        val parsed = runCatching { JSONObject(data) }.getOrNull() ?: return false
        val episodeUrl = parsed.optString("first")
        val playerUrl = parsed.optString("second")
        if (playerUrl.isBlank()) return false

        val playerPage = app.get(playerUrl, headers = mapOf("Referer" to episodeUrl)).text
        val embed = IFRAME_REGEX.find(playerPage)?.groupValues?.get(1) ?: return false
        if (!embed.startsWith("http")) return false

        val media = VimeosBridge.resolve(embed, playerUrl) ?: return false
        callback.invoke(
            newExtractorLink("Server", "Server", media.m3u8, ExtractorLinkType.M3U8) {
                this.referer = "https://vimeos.net/"
            }
        )
        for ((language, subtitle) in media.subtitles) {
            subtitleCallback.invoke(SubtitleFile(language, subtitle))
        }
        return true
    }

    companion object {
        private val IFRAME_REGEX = Regex("""<iframe[^>]*src="(https?://[^"]+)"""")
    }
}
