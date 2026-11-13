package com.anonimo00.hackstore

import com.anonimo00.shared.Vimeos
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
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

/**
 * Proveedor de hackstore.fo (peliculas, series y animes).
 *
 * Tema WordPress MEGATheme. El reproductor se resuelve en dos pasos:
 *
 *  1. Cada servidor es un `<a class="playr" data-href="/play.php?u=..&b=..">`
 *     (los parametros son blobs opacos que se reenvian tal cual).
 *  2. `play.php` responde con un `window.location.href = '<url del reproductor>'`,
 *     que es una pagina de vimeos con la playlist cifrada con P.A.C.K.E.R.
 *
 * Ojo: en los episodios y en algunas series la lista de servidores online
 * sale vacia, asi que solo se pueden reproducir las peliculas.
 */
class HackstoreProvider : MainAPI() {
    override var mainUrl = "https://www.hackstore.fo"
    override var name = "Hackstore"
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

    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst(".movie-back a[href]")?.attr("href")
            ?: this.selectFirst("h3 a.movie-title")?.attr("href")
            ?: return null
        val title = this.selectFirst("h3 a.movie-title")?.text()?.trim()
            ?: this.selectFirst(".poster-pad img")?.attr("alt")?.trim()
            ?: return null
        val poster = this.selectFirst(".poster-pad img")?.attr("data-src")
        val year = this.selectFirst(".audios .audio")?.text()?.trim()?.toIntOrNull()
        val type = when {
            link.contains("/series/") -> TvType.TvSeries
            link.contains("/animes/") -> TvType.Anime
            else -> TvType.Movie
        }
        return when (type) {
            TvType.TvSeries -> newTvSeriesSearchResponse(title, link, type) {
                this.posterUrl = fixUrlNull(poster)
                this.year = year
            }

            else -> newMovieSearchResponse(title, link, type) {
                this.posterUrl = fixUrlNull(poster)
                this.year = year
            }
        }
    }

    private fun cards(document: Element): List<SearchResponse> =
        document.select("div.movie-thumbnail").mapNotNull { it.toSearchResult() }

    // ---------- main page ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}/page/$page/").document
        val hasNext = document.selectFirst("a[href*=\"page/$((page + 1))\"]") != null
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
        val document = app.get("$mainUrl/?s=${query.trim().replace(" ", "+")}").document
        return cards(document)
    }

    // ---------- load ----------

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst(".watch-info-header-tl h1 a")?.text()?.trim()
            ?: document.selectFirst("h1 a")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: return null
        val poster = document.selectFirst(".watch-content img.lazy")?.attr("data-src")
            ?: document.selectFirst(".watch-content img")?.attr("src")
        val description = document.selectFirst(".watch-text p.wp-block-paragraph")?.text()?.trim()
            ?: document.selectFirst(".watch-text p")?.text()?.trim()
        val tags = document.select(".watch-text a[rel=\"tag\"]").map { it.text().trim() }.distinct()

        var year: Int? = null
        var score: Score? = null
        for (paragraph in document.select(".watch-text p")) {
            val text = paragraph.text()
            year = Regex("""\b(19|20)\d{2}\b""").find(text)?.groupValues?.get(0)?.toIntOrNull() ?: year
            score = Regex("""\b(\d[.,]\d)\s*/\s*10\b""").find(text)?.groupValues?.get(1)
                ?.replace(",", ".")?.toDoubleOrNull()
                ?.let { Score.from(it, 10) } ?: score
        }

        val episodes = document.select("div.movie-thumbnail a[href*=\"/episodio/\"]").mapIndexed { index, item ->
            val href = item.attr("href")
            val title = item.selectFirst("h3 a.movie-title")?.text()?.trim()
            val season = Regex("""-(\d+)x(\d+)$""").find(href)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val episode = Regex("""-(\d+)x(\d+)$""").find(href)?.groupValues?.get(2)?.toIntOrNull()
                ?: (index + 1)
            newEpisode(href) {
                this.name = title
                this.season = season
                this.episode = episode
            }
        }

        return if (url.contains("/peliculas/")) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = description ?: ""
                this.tags = tags
                this.year = year
                this.score = score
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = description ?: ""
                this.tags = tags
                this.year = year
                this.score = score
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
        val players = document.select("a.playr[data-href]")
        if (players.isEmpty()) return false

        var found = false
        for (player in players) {
            val path = player.attr("data-href")
            if (path.isBlank()) continue
            found = true

            val name = listOfNotNull(
                player.attr("data-lang").takeIf { it.isNotBlank() },
                player.text().trim().takeIf { it.isNotBlank() },
            ).joinToString(" ").ifEmpty { "Server" }

            // play.php responde con la url real del reproductor.
            val response = app.get(
                if (path.startsWith("http")) path else "$mainUrl$path",
                headers = mapOf("Referer" to data),
            )
            val embed = REDIRECT_REGEX.find(response.text)?.groupValues?.get(1) ?: continue
            if (!embed.startsWith("http")) continue

            if (embed.contains("vimeos.net")) {
                val media = Vimeos.resolve(embed, data) ?: continue
                callback.invoke(
                    newExtractorLink(name, name, media.m3u8, ExtractorLinkType.M3U8) {
                        this.referer = "https://vimeos.net/"
                    }
                )
                for ((language, subtitle) in media.subtitles) {
                    subtitleCallback.invoke(SubtitleFile(language, subtitle))
                }
            } else {
                callback.invoke(newExtractorLink(name, name, embed))
            }
        }
        return found
    }

    companion object {
        private val REDIRECT_REGEX = Regex("""window\.location\.href\s*=\s*['"]([^'"]+)['"]""")
    }
}

