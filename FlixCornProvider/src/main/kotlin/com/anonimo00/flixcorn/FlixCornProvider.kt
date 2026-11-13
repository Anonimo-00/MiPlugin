package com.anonimo00.flixcorn

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
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.JsonAsString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * Proveedor de flixcorn.net (series y animes en espanol latino).
 *
 * Solo tiene series. Las temporadas son paginas aparte
 * (`/serie/<slug>/temporada-N.html`) y cada episodio tiene su propia pagina
 * (`/ver/<slug>/temporada-N/capitulo-M.html`).
 *
 * En la pagina del episodio cada servidor tiene un boton "LINK DIRECTO" que
 * apunta a `/external/<id>`; ese endpoint, llamado como AJAX, devuelve la url
 * real del reproductor. Para el servidor "Vidara" hay que pedir ademas el
 * `streaming_url` a su API, que devuelve el m3u8 en claro.
 */
class FlixCornProvider : MainAPI() {
    override var mainUrl = "https://www.flixcorn.net"
    override var name = "FlixCorn"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Anime)

    override val mainPage = mainPageOf(
        "estrenos-series-espanol-latino" to "Estrenos",
        "nuevos-episodios" to "Nuevos capitulos",
        "tendencias" to "Tendencias",
        "nueva-series-agregada" to "Novedades",
    )

    // ---------- helpers ----------

    private fun Element.toSearchResponse(): SearchResponse? {
        val link = this.selectFirst("a[href^=/serie/]")?.attr("href") ?: return null
        val title = this.selectFirst("p.media-title")?.text()?.trim()
            ?: this.selectFirst("img[alt]")?.attr("alt")?.trim()
            ?: return null
        val poster = this.selectFirst("div.media-cover img[src]")?.attr("src")
        val meta = this.selectFirst("div.media-meta span")?.text()?.trim()
        return newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
            this.posterUrl = fixUrlNull(poster)
            this.year = meta?.toIntOrNull()
        }
    }

    private fun cards(document: Element): List<SearchResponse> =
        document.select("a.media-card").mapNotNull { it.toSearchResponse() }

    // ---------- main page ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}").document
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = cards(document),
                isHorizontalImages = false,
            ),
            hasNext = false,
        )
    }

    // ---------- search ----------

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val document = app.get("$mainUrl/search?q=$encoded").document
        return cards(document)
    }

    // ---------- load ----------

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.fw-bold.lh-sm.mb-0")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: return null
        val poster = document.selectFirst("img.serie-poster[src]")?.attr("src")
        val description = document.selectFirst("p.glass.rounded-3.p-4.mb-0.lh-lg")?.text()?.trim()
        val tags = document.select("a.genre-link[href^=/genero/]").map { it.text().trim() }.distinct()

        var year: Int? = null
        var score: Score? = null
        for (item in document.select("div.serie-meta span.serie-meta-item")) {
            val text = item.text().trim()
            year = Regex("""\b(19|20)\d{2}\b""").find(text)?.groupValues?.get(0)?.toIntOrNull() ?: year
            score = Regex("""\d[.,]\d""").find(text)?.groupValues?.get(0)
                ?.replace(",", ".")?.toDoubleOrNull()?.let { Score.from(it, 10) } ?: score
        }

        val episodes = episodesOf(document, url)

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = description ?: ""
            this.tags = tags
            this.year = year
            this.score = score
        }
    }

    /** Episodios de todas las temporadas de la serie. */
    private suspend fun episodesOf(document: Element, url: String): List<com.lagradost.cloudstream3.Episode> {
        val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
        val seasons = document.select("a.ep-pill[href*=/temporada-]").map { it.attr("href") }.distinct()
        val pages = if (seasons.isEmpty()) listOf(url) else seasons
        for (page in pages) {
            val list = if (page == url) document else app.get("$mainUrl$page").document
            for (row in list.select("div.ep-list a.ep-row[href*=/ver/]")) {
                val href = row.attr("href")
                val episode = row.attr("data-cap").toIntOrNull()
                val badge = row.selectFirst("div.ep-row-badge")?.text()?.trim()
                val title = row.selectFirst("div.ep-row-title")?.text()?.trim()
                val season = Regex("""-(\d+)/""").find(href)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                episodes += newEpisode(href) {
                    this.name = title ?: badge
                    this.season = season
                    this.episode = episode
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
        val document = app.get(data).document
        val rows = document.select("div.cap-server-row")
        if (rows.isEmpty()) return false

        var found = false
        for (row in rows) {
            val name = row.selectFirst("span.cap-server-name")?.text()?.trim().orEmpty()
            val path = row.selectFirst("a.cap-btn--direct[href^=/external/]")?.attr("href")
                ?: row.selectFirst("a.cap-btn--online[href^=/player/]")?.attr("href")
                ?: continue
            found = true

            // El endpoint /external/ solo responde JSON si se pide como AJAX.
            val response = app.get(
                "$mainUrl$path",
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to data,
                ),
            )
            val embed = response.text.trim().let { text ->
                if (text.startsWith("{")) {
                    runCatching { org.json.JSONObject(text).optString("url") }.getOrNull()
                } else {
                    null
                }
            } ?: continue
            if (!embed.startsWith("http")) continue

            if (embed.contains("vidara")) {
                val fileCode = embed.substringAfterLast("/e/").substringBefore("?")
                val playlist = vidaraStream(fileCode, embed) ?: continue
                callback.invoke(
                    newExtractorLink(name, name, playlist, ExtractorLinkType.M3U8) {
                        this.referer = embed
                    }
                )
            } else {
                loadExtractor(embed, data, subtitleCallback) { extractorLink ->
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

    /** El servidor Vidara pide el m3u8 a su API, que lo devuelve en claro. */
    private suspend fun vidaraStream(fileCode: String, embedUrl: String): String? {
        val body = """{"filecode":"$fileCode","device":"web","codecs":[]}"""
        val response = app.post(
            "https://vidaraa.cc/api/stream",
            headers = mapOf(
                "Content-Type" to "application/json",
                "Referer" to embedUrl,
            ),
            data = null,
            json = JsonAsString(body),
        )
        val json = runCatching { org.json.JSONObject(response.text) }.getOrNull() ?: return null
        return json.optString("streaming_url").takeIf { it.isNotBlank() }
    }
}

