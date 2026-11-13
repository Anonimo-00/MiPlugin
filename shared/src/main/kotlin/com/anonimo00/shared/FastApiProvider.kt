package com.anonimo00.shared

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.type.TypeReference
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.URLEncoder

// ------------------------------------------------------------------- modelos

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class ApiImages(
    val poster: String? = null,
    val backdrop: String? = null,
    val logo: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class ApiPost(
    @JsonProperty("_id") val id: Int? = null,
    val title: String? = null,
    val overview: String? = null,
    val slug: String? = null,
    val images: ApiImages? = null,
    val rating: String? = null,
    val genres: List<Int> = emptyList(),
    val type: String? = null,
    val release_date: String? = null,
    val runtime: String? = null,
    val original_title: String? = null,
    val season_number: Int? = null,
    val episode_number: Int? = null,
    val show_id: String? = null,
    val vote_average: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class ApiPagination(
    val current_page: Int? = null,
    val last_page: Int? = null,
    val total: Int? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class ApiPostsData(
    val posts: List<ApiPost> = emptyList(),
    val pagination: ApiPagination? = null,
    val seasons: List<String> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class ApiPostsResponse(val data: ApiPostsData? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class ApiSingleResponse(val data: ApiPost? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class ApiEpisodeData(
    val episode: ApiPost? = null,
    val serie: ApiPost? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class ApiEpisodeResponse(val data: ApiEpisodeData? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class ApiEmbed(
    val url: String? = null,
    val server: String? = null,
    val lang: String? = null,
    val quality: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class ApiPlayerData(
    val embeds: List<ApiEmbed> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class ApiPlayerResponse(val data: ApiPlayerData? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class ApiTaxonomy(
    val name: String? = null,
    val slug: String? = null,
)

// ------------------------------------------------------------------ base

/**
 * Base para los sitios que usan la API FastAPI de WordPress (la misma en
 * gnula.la y cinecalidad.org):
 *
 *  - `GET {api}/listing/{postType}?page=N&postType={postType}&postsPerPage=24`
 *  - `GET {api}/search?q=...&postType=any&postsPerPage=24`
 *  - `GET {api}/single/{postType}?slug=...&postType={postType}`
 *  - `GET {api}/single/episodes/list?_id={showId}&season=N&page=1&postsPerPage=8`
 *  - `GET {api}/single/episodes?slug=...&postType=episodes`
 *  - `GET {api}/player?postId={id}&demo=0`
 *
 * Ojo: el tramo `/listing/{x}` no filtra nada, el filtro real es el parametro
 * `postType`, asi que hay que enviar los dos.
 */
abstract class FastApiProvider : MainAPI() {
    /** Url del sitio (sin barra final). */
    protected abstract val siteUrl: String

    /** Base de la API, por ejemplo `https://gnula.la/wp-api/v1`. */
    protected abstract val apiBase: String

    /** Base de las imagenes (el sitio las da relativas a esta). */
    protected abstract val imageBase: String

    /** Tipo de contenido de CloudStream al que corresponde cada `postType`. */
    protected abstract fun typeOf(postType: String): TvType

    /** Url publica de una entrada. */
    protected abstract fun urlOf(postType: String, slug: String): String

    /** `postType` a partir de la url publica. */
    protected abstract fun postTypeOf(url: String): String

    // ---------- helpers ----------

    private fun posterOf(post: ApiPost): String? {
        val path = post.images?.poster ?: return null
        if (path.startsWith("http")) return path
        return imageBase + path
    }

    private fun yearOf(post: ApiPost): Int? =
        post.release_date?.take(4)?.toIntOrNull()
            ?: Regex("""\((\d{4})\)$""").find(post.title ?: "")?.groupValues?.get(1)?.toIntOrNull()

    private fun scoreOf(post: ApiPost): Score? {
        val rating = post.rating?.toDoubleOrNull() ?: return null
        return Score.from(rating, 10)
    }

    /** Nombres de los generos, que la API devuelve como identificadores. */
    private suspend fun genreNames(): Map<Int, String> {
        val html = app.get(siteUrl).text
        val json = GENRES_REGEX.find(html)?.groupValues?.get(1) ?: return emptyMap()
        return runCatching {
            mapper.readValue(json, object : TypeReference<Map<Int, ApiTaxonomy>>() {})
        }.getOrNull()?.mapNotNull { (id, tax) ->
            tax.name?.let { id to it }
        }?.toMap() ?: emptyMap()
    }

    // ---------- busqueda ----------

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val response = app.get("$apiBase/search?q=$encoded&postType=any&postsPerPage=24")
        val posts = response.parsedSafe<ApiPostsResponse>()?.data?.posts ?: return emptyList()
        return posts.mapNotNull { it.toSearchResponse() }
    }

    // ---------- portada ----------

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): com.lagradost.cloudstream3.HomePageResponse {
        val postType = request.data
        val response = app.get(
            "$apiBase/listing/$postType?page=$page&orderBy=date&order=desc" +
                "&postType=$postType&postsPerPage=24"
        )
        val data = response.parsedSafe<ApiPostsResponse>()?.data
        val list = data?.posts?.mapNotNull { it.toSearchResponse() } ?: emptyList()
        val current = data?.pagination?.current_page ?: 1
        val last = data?.pagination?.last_page ?: 1
        return newHomePageResponse(
            request.name,
            list,
            current < last,
        )
    }

    // ---------- ficha ----------

    override suspend fun load(url: String): LoadResponse? {
        val postType = postTypeOf(url)
        val slug = url.trimEnd('/').substringAfterLast('/')
        val post = fetchSingle(postType, slug) ?: return null
        val title = post.title?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        return if (postType == "episodes") {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesOf(post)) {
                this.posterUrl = posterOf(post)
                this.plot = post.overview ?: ""
                this.year = yearOf(post)
                this.score = scoreOf(post)
            }
        } else if (postType == "movies") {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterOf(post)
                this.plot = post.overview ?: ""
                this.year = yearOf(post)
                this.score = scoreOf(post)
            }
        } else {
            newTvSeriesLoadResponse(title, url, typeOf(postType), episodesOf(post)) {
                this.posterUrl = posterOf(post)
                this.plot = post.overview ?: ""
                this.year = yearOf(post)
                this.score = scoreOf(post)
            }
        }
    }

    /** Episodios de una serie (o de un anime). */
    private suspend fun episodesOf(show: ApiPost): List<com.lagradost.cloudstream3.Episode> {
        val showId = show.id ?: return emptyList()
        val seasons = fetchSeasons(showId)
        val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
        for (season in seasons) {
            val response = app.get(
                "$apiBase/single/episodes/list?_id=$showId&season=$season&page=1&postsPerPage=100"
            )
            val posts = response.parsedSafe<ApiPostsResponse>()?.data?.posts ?: continue
            for (post in posts) {
                val slug = post.slug ?: continue
                episodes += newEpisode(urlOf("episodes", slug)) {
                    this.name = post.title
                    this.season = post.season_number ?: season
                    this.episode = post.episode_number
                    this.posterUrl = post.images?.poster?.let {
                        if (it.startsWith("http")) it else imageBase + it
                    }
                }
            }
        }
        return episodes
    }

    private suspend fun fetchSeasons(showId: Int): List<Int> {
        val response = app.get(
            "$apiBase/single/episodes/list?_id=$showId&season=1&page=1&postsPerPage=100"
        )
        val data = response.parsedSafe<ApiPostsResponse>()?.data ?: return listOf(1)
        val seasons = data.seasons.mapNotNull { it.toIntOrNull() }.distinct().sorted()
        return seasons.ifEmpty { listOf(1) }
    }

    private suspend fun fetchSingle(postType: String, slug: String): ApiPost? {
        val response = app.get("$apiBase/single/$postType?slug=$slug&postType=$postType")
        return response.parsedSafe<ApiSingleResponse>()?.data
    }

    // ---------- enlaces ----------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val postType = postTypeOf(data)
        val slug = data.trimEnd('/').substringAfterLast('/')

        val postId = if (postType == "episodes") {
            app.get("$apiBase/single/episodes?slug=$slug&postType=episodes")
                .parsedSafe<ApiEpisodeResponse>()?.data?.episode?.id
        } else {
            fetchSingle(postType, slug)?.id
        } ?: return false

        val player = app.get("$apiBase/player?postId=$postId&demo=0")
            .parsedSafe<ApiPlayerResponse>()?.data ?: return false

        var found = false
        for (embed in player.embeds) {
            val url = embed.url ?: continue
            // Hay reproductores whose el dominio esta caducado (la.movie), se
            // descartan para no ensuciar la lista.
            if (!url.startsWith("http")) continue
            found = true

            val name = listOfNotNull(
                embed.lang?.takeIf { it.isNotBlank() },
                embed.quality?.takeIf { it.isNotBlank() },
            ).joinToString(" ").ifEmpty { embed.server ?: "Server" }

            if (url.contains("vimeos.net")) {
                // vimeos lleva el m3u8 cifrado con P.A.C.K.E.R: hay que
                // desempaquetarlo para playable la playlist.
                val media = Vimeos.resolve(url, data)
                if (media == null) continue
                callback.invoke(
                    newExtractorLink(name, name, media.m3u8, ExtractorLinkType.M3U8) {
                        this.referer = "https://vimeos.net/"
                    }
                )
                for ((language, subtitle) in media.subtitles) {
                    subtitleCallback.invoke(SubtitleFile(language, subtitle))
                }
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

    // ---------- conversores ----------

    private fun ApiPost.toSearchResponse(): SearchResponse? {
        val slug = this.slug ?: return null
        val postType = this.type ?: "movies"
        val url = urlOf(postType, slug)
        val title = this.title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val tvType = typeOf(postType)
        return if (tvType == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, url, tvType) {
                this.posterUrl = posterOf(this@toSearchResponse)
                this.year = yearOf(this@toSearchResponse)
                this.score = scoreOf(this@toSearchResponse)
            }
        } else {
            newMovieSearchResponse(title, url, tvType) {
                this.posterUrl = posterOf(this@toSearchResponse)
                this.year = yearOf(this@toSearchResponse)
                this.score = scoreOf(this@toSearchResponse)
            }
        }
    }

    companion object {
        /** El mapa de generos va dentro del `window.siteConfig` de la home. */
        private val GENRES_REGEX = Regex(
            """genres\s*:\s*(\{.*?\})\s*,\s*years\s*:""",
            RegexOption.DOT_MATCHES_ALL,
        )
    }
}
