package com.anonimo00.animeonline

import com.anonimo00.shared.SearchHelper
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
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import android.util.Base64
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Proveedor de animeonline.one (animes en espanol latino).
 *
 * El dominio `animeonline.ninja` esta detras del reto gestionado de
 * Cloudflare, asi que no se puede usar; `animeonline.one` sirve el mismo
 * sitio con el HTML generado en el servidor.
 *
 * Los reproductores vienen en `data-hash`, que es la URL del reproductor en
 * base64 (sin ningun cifrado). El servidor "Omega" (vidmoly) trae el m3u8
 * en claro dentro de su pagina.
 */
class AnimeOnlineProvider : MainAPI() {
    override var mainUrl = "https://ww4.animeonline.ninja"
    override var name = "AnimeOnline"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    /** La busqueda es una sola peticion: sirve para la busqueda rapida de la app. */
    override val hasQuickSearch = true

    /** La busqueda rapida reutiliza la busqueda normal. */
    override suspend fun quickSearch(query: String): List<SearchResponse> =
        SearchHelper.quickSearch(name, query) { siteSearch(it) }
    override val supportedTypes = setOf(TvType.Anime)

    override val mainPage = mainPageOf(
        "" to "Animes",
        "filter/?sort=recentlyUpdated" to "Actualizados",
        "filter/?sort=mostWatched" to "Mas vistos",
    )

    // ---------- helpers ----------

    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst("h3.film-name a")?.attr("href")?.takeIf { it.isNotBlank() }
            ?: this.selectFirst("a.film-poster-ahref")?.attr("href")?.takeIf { it.isNotBlank() }
            ?: return null
        val title = this.selectFirst("h3.film-name a")?.text()?.trim()
            ?: this.selectFirst("img.film-poster-img")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        val poster = this.selectFirst("img.film-poster-img")?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: this.selectFirst("img.film-poster-img")?.attr("src")
        val score = this.selectFirst(".tick-quality")?.text()?.trim()
        return newTvSeriesSearchResponse(title, link, TvType.Anime) {
            this.posterUrl = fixUrlNull(poster)
            this.score = score?.let { Score.from(it, 10) }
        }
    }

    private fun cards(document: Element): List<SearchResponse> =
        // En las paginas de busqueda conviven los resultados con los charts
        // de mas vistos, asi que se acota a la lista de peliculas.
        document.select("div.film_list .flw-item, section.block_area-anime .flw-item")
            .mapNotNull { it.toSearchResult() }

    // ---------- main page ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.isEmpty()) {
            "/page/$page/"
        } else {
            "/${request.data}&page=$page"
        }
        val document = fetch(url).document
        val hasNext = document.selectFirst(".pagination a[href*=\"page=$((page + 1))\"]") != null
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = cards(document),
                isHorizontalImages = false,
            ),
            hasNext = hasNext,
        )
    }

    /**
     * Descarga una pagina probando los dominios por orden de preferencia.
     *
     * `animeonline.one` sirve el sitio sin reto de Cloudflare, mientras que
     * `ww4.animeonline.ninja` esta detras del reto gestionado; se prueban
     * ambos por si el primero deja de responder.
     */
    private suspend fun fetch(path: String): com.lagradost.nicehttp.NiceResponse {
        for (domain in DOMAINS) {
            val response = runCatching { app.get("$domain$path") }.getOrNull() ?: continue
            if (response.isSuccessful && response.document.select("body").text().isNotBlank()) {
                return response
            }
        }
        return app.get("$mainUrl$path")
    }

    // ---------- search ----------

    override suspend fun search(query: String): List<SearchResponse> =
        SearchHelper.smartSearch(name, query) { siteSearch(it) }

    /** Busqueda en el sitio (la usa [search] con consultas alternativas). */
    private suspend fun siteSearch(query: String): List<SearchResponse> {
        val document = fetch("/?s=${query.trim().replace(" ", "+")}").document
        return cards(document)
    }

    // ---------- load ----------

    override suspend fun load(url: String): LoadResponse? {
        val document = fetch(urlPath(url)).document
        val title = document.selectFirst("h1.film-name.dynamic-name")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: return null
        val poster = document.selectFirst(".anime-poster img.film-poster-img")?.attr("src")
        val description = document.selectFirst("p.shorting")?.attr("data-full-html")
            ?.let { Jsoup.parse(it).text().trim() }
            ?: document.selectFirst("p.shorting")?.text()?.trim()
        val score = document.selectFirst(".anime_score_value")?.text()?.trim()?.toDoubleOrNull()
            ?.let { Score.from(it, 10) }
        val tags = document.select(".meta .item a[href^=\"/genre/\"]").map { it.text().trim() }.distinct()

        var year: Int? = null
        for (item in document.select(".meta .item")) {
            val label = item.selectFirst(".item-title")?.text()?.trim().orEmpty()
            if (label.startsWith("Premiered") || label.startsWith("Date aired")) {
                year = Regex("""\d{4}""").find(item.text())?.groupValues?.get(0)?.toIntOrNull()
                    ?: year
            }
        }

        val episodes = document.select(".episodes-ul a.ep-item").mapIndexed { index, item ->
            val href = item.attr("href")
            val number = item.attr("data-number").toIntOrNull()
                ?: item.selectFirst(".order")?.text()?.trim()?.toIntOrNull()
                ?: (index + 1)
            newEpisode(href) {
                this.name = "Episodio $number"
                this.season = 1
                this.episode = number
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
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
        val document = fetch(urlPath(data)).document
        val servers = document.select(".server-item a.btn[data-hash]")
        if (servers.isEmpty()) return false

        for (server in servers) {
            val encoded = server.attr("data-hash")
            if (encoded.isBlank()) continue
            // El hash es la URL del reproductor en base64, sin cifrar.
            val url = runCatching {
                String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
            }.getOrNull() ?: continue
            if (!url.startsWith("http")) continue

            val name = server.text().trim().ifEmpty { "Server" }
            loadExtractor(url, data, subtitleCallback, callback)
        }
        return true
    }

    companion object {
        /**
         * Dominios por orden de preferencia. `animeonline.one` es el que
         * responde sin reto de Cloudflare; el `.ninja` oficial esta bloqueado.
         */
        private val DOMAINS = listOf("https://ww4.animeonline.ninja", "https://animeonline.one")

        /** Convierte una url absoluta del sitio en un path relativo. */
        private fun urlPath(url: String): String {
            // Si la url ya es absoluta (el sitio puede redirigir a dominios externos
        // como 9anime.qpon), se usa tal cual. Si es relativa al sitio, se le
        // antepone el dominio correspondiente.
        val withoutDomain = DOMAINS.firstOrNull { url.startsWith(it) }
                ?.let { url.removePrefix(it) }
                ?: url
        return when {
            withoutDomain.startsWith("http") -> withoutDomain
            withoutDomain.startsWith("/") -> withoutDomain
            else -> "/$withoutDomain"
        }
        }
    }
}
