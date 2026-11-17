package com.anonimo00.animeonline

import com.anonimo00.shared.SearchHelper
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.Episode
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
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import android.util.Base64
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Proveedor de anime en espanol latino con dos plantillas detras:
 *
 *  - `veranime.ninja` (DooPlay): responde sin reto y trae el mismo catalogo
 *    que la red oficial. Listados en `article.item`/`.result-item`, episodios
 *    en `#seasons` y reproductores en `#playeroptionsul`, que devuelven por
 *    ajax una pagina "multiserver" (darkanimes) con los servidores cifrados
 *    con xor.
 *  - `ver2.animeonline.ninja` (plantilla film, detras de Cloudflare) y
 *    `animeonline.one` (redirige a 9anime.qpon, catalogo en ingles): tarjetas
 *    `.flw-item`, episodios `.episodes-ul` y reproductores en `data-hash`
 *    (url en base64) que llevan a un iframe de megaplay con el m3u8 en una
 *    api interna.
 *
 * Se detecta la plantilla por el propio HTML de cada pagina, asi que el
 * proveedor sirve las tres fuentes sin cambiar de logica.
 */
class AnimeOnlineProvider : MainAPI() {
    override var mainUrl = "https://veranime.ninja"
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

    /**
     * Resuelve el reto de Cloudflare del sitio oficial.
     *
     * `ver2.animeonline.ninja` devuelve 403 con `Server: cloudflare` y
     * `cf-mitigated: challenge`, asi que sin el WebView no se puede leer. La
     * instancia tiene que ser unica (`by lazy`): su constructor borra las
     * cookies del WebView y se perderia el `cf_clearance` ya ganado.
     */
    private val cloudflareKiller by lazy { CloudflareKiller() }

    override val mainPage = mainPageOf(
        "" to "Animes",
        "filter/?sort=recentlyUpdated" to "Actualizados",
        "filter/?sort=mostWatched" to "Mas vistos",
    )

    // ---------- helpers de tarjetas ----------

    /** Tarjeta de la plantilla film (.flw-item). */
    private fun Element.toFilmSearchResult(): SearchResponse? {
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

    /** Tarjeta de DooPlay: listados (`article.item`) y busquedas (`.result-item`). */
    private fun Element.toDooSearchResult(): SearchResponse? {
        val link = this.selectFirst(".details .title a[href]")?.attr("href")?.takeIf { it.startsWith("http") }
            ?: this.selectFirst(".image a[href]")?.attr("href")?.takeIf { it.startsWith("http") }
            ?: return null
        val title = this.selectFirst(".details .title")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: this.selectFirst("h3.title")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: this.selectFirst("img[alt]")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        val poster = this.selectFirst(".image img[data-src]")?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: this.selectFirst(".image img[src]")?.attr("src")
                ?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
        val score = this.selectFirst(".meta .rating")?.text()?.trim()
            ?.let { Regex("""\d+(?:\.\d+)?""").find(it)?.value?.toDoubleOrNull() }
        val year = this.selectFirst(".meta .year")?.text()?.trim()?.toIntOrNull()
            ?: this.selectFirst(".data > span")?.text()?.trim()?.toIntOrNull()
        return newTvSeriesSearchResponse(title, link, TvType.Anime) {
            this.posterUrl = fixUrlNull(poster)
            this.score = score?.let { Score.from(it, 10) }
            this.year = year
        }
    }

    /**
     * Tarjetas de cualquier pagina de listado o busqueda.
     *
     * Se prueba primero la plantilla film y, si no trae nada, la DooPlay: cada
     * sitio solo usa una de las dos, y los charts de mas vistos no llevan
     * `.flw-item` asi que no se cuelan en los resultados.
     */
    private fun cards(document: Element): List<SearchResponse> {
        val film = document.select(".flw-item").mapNotNull { it.toFilmSearchResult() }
        if (film.isNotEmpty()) return film
        return document.select("article.item, .result-item")
            .mapNotNull { it.toDooSearchResult() }
            .distinctBy { it.url }
    }

    // ---------- main page ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // `/anime/` vale en las dos plantillas; `/page/N/` es el antiguo de la
        // film y `/` la portada, por si alguna fuente no tiene listado por genero.
        val paths = when {
            request.data.isNotEmpty() -> listOf("/${request.data}&page=$page")
            page <= 1 -> listOf("/anime/", "/")
            else -> listOf("/anime/page/$page/", "/page/$page/")
        }
        var document: org.jsoup.nodes.Document? = null
        for (path in paths) {
            val doc = runCatching { fetch(path) { cards(it).isNotEmpty() } }
                .getOrNull()?.document ?: continue
            if (cards(doc).isNotEmpty()) {
                document = doc
                break
            }
        }
        val list = document?.let { cards(it) }.orEmpty()
        // DooPlay pagina con `/anime/page/N/`, la film con `?page=N` o `page/N`
        // (en 9anime solo lo anuncia <link rel="next"> en la cabecera).
        val hasNext = document != null && (
            document.selectFirst(
                ".pagination a[href*=\"page=${page + 1}\"], .pagination a[href*=\"page/${page + 1}\"]"
            ) != null || document.selectFirst("link[rel=next]") != null
        )
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = list,
                isHorizontalImages = false,
            ),
            hasNext = hasNext,
        )
    }

    /**
     * Descarga una pagina probando los dominios por orden de preferencia.
     *
     * `veranime.ninja` responde sin reto con el catalogo completo;
     * `ver2.animeonline.ninja` es el host oficial (detrás de Cloudflare, con
     * bypass) y `animeonline.one` cae en 9anime.qpon como ultimo recurso.
     * [accept] deja descartar respuestas sin contenido util (por ejemplo una
     * busqueda sin resultados) para probar el siguiente dominio.
     */
    private suspend fun fetch(
        path: String,
        accept: (org.jsoup.nodes.Document) -> Boolean = { true },
    ): com.lagradost.nicehttp.NiceResponse {
        // Si la url ya es absoluta (la busqueda devuelve enlaces a otros hosts
        // de la misma red) se pide tal cual: concatenandole el dominio saldria
        // "https://veranime...https://9anime.qpon/..." y la ficha no cargaria.
        if (path.startsWith("http")) {
            return getWithBypass(path)
        }
        for (domain in DOMAINS) {
            val response = runCatching { getWithBypass("$domain$path") }.getOrNull() ?: continue
            if (response.isSuccessful &&
                response.document.select("body").text().isNotBlank() &&
                accept(response.document)
            ) {
                return response
            }
        }
        return getWithBypass("$mainUrl$path")
    }

    /**
     * Pide una url resolviendo el reto de Cloudflare si lo hay.
     *
     * Primero se intenta directo con el interceptor (una sola peticion cuando
     * el sitio no protege esa ruta o cuando las cookies ya estan ganadas). Si
     * aun asi vuelve 403 se reintenta con la url definitiva: el sitio rota entre
     * `ww3`/`ww4`/`ver2` y `cf_clearance` es por host, asi que hay que pedir el
     * host al que acaba de redirigir.
     */
    private suspend fun getWithBypass(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): com.lagradost.nicehttp.NiceResponse {
        val first = runCatching { app.get(url, headers = headers, interceptor = cloudflareKiller) }.getOrNull()
            ?: return app.get(url, headers = headers, interceptor = cloudflareKiller)
        if (!isChallenge(first)) return first

        val finalUrl = first.url?.takeIf { it.startsWith("http") && it != url } ?: return first
        return runCatching { app.get(finalUrl, headers = headers, interceptor = cloudflareKiller) }.getOrNull() ?: first
    }

    /** true si la respuesta sigue siendo el reto de Cloudflare. */
    private fun isChallenge(response: com.lagradost.nicehttp.NiceResponse): Boolean =
        response.code in listOf(403, 503) &&
            (response.headers["server"].equals("cloudflare", true) ||
                response.headers["server"].equals("cloudflare-nginx", true) ||
                response.headers["cf-mitigated"] != null)

    // ---------- search ----------

    override suspend fun search(query: String): List<SearchResponse> =
        SearchHelper.smartSearch(name, query) { siteSearch(it) }

    /**
     * Busqueda en el sitio (la usa [search] con consultas alternativas).
     *
     * `/?s=` es la busqueda de WordPress y vale en las dos plantillas;
     * `/search/<q>/` es el alias de la plantilla film, por si la primera no
     * encuentra nada (por ejemplo un titulo en otro idioma).
     */
    private suspend fun siteSearch(query: String): List<SearchResponse> {
        val q = query.trim().replace(" ", "+")
        for (path in listOf("/?s=$q", "/search/$q/")) {
            val document = runCatching { fetch(path) { cards(it).isNotEmpty() } }
                .getOrNull()?.document ?: continue
            val found = cards(document)
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    // ---------- load ----------

    override suspend fun load(url: String): LoadResponse? {
        val response = fetch(urlPath(url))
        if (!response.isSuccessful) return null
        val document = response.document

        val title = document.selectFirst("h1.film-name.dynamic-name")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: return null
        val poster = document.selectFirst(".anime-poster img.film-poster-img")?.attr("src")
            ?: document.selectFirst(".poster img")?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: document.selectFirst(".poster img")?.attr("src")
                ?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
        val description = document.selectFirst("p.shorting")?.attr("data-full-html")
            ?.let { Jsoup.parse(it).text().trim() }
            ?: document.selectFirst("p.shorting")?.text()?.trim()
            ?: document.selectFirst("#info .wp-content p")?.text()?.trim()
        val score = document.selectFirst(".anime_score_value")?.text()?.trim()?.toDoubleOrNull()
            ?.let { Score.from(it, 10) }
            ?: document.selectFirst(".meta .rating")?.text()?.trim()
                ?.let { Regex("""\d+(?:\.\d+)?""").find(it)?.value?.toDoubleOrNull() }
                ?.let { Score.from(it, 10) }
        val tags = document.select(".meta a[href*=\"/genre/\"]").map { it.text().trim() }.distinct()
            .ifEmpty { document.select(".sgeneros a").map { it.text().trim() }.distinct() }

        var year: Int? = null
        for (item in document.select(".meta .item")) {
            val label = item.selectFirst(".item-title")?.text()?.trim().orEmpty()
            if (label.startsWith("Premiered") || label.startsWith("Date aired")) {
                year = Regex("""\d{4}""").find(item.text())?.groupValues?.get(0)?.toIntOrNull()
                    ?: year
            }
        }
        if (year == null) {
            val fecha = document.selectFirst(".meta .year")?.text()
                ?: document.selectFirst("span.date")?.text().orEmpty()
            year = Regex("""\d{4}""").find(fecha)?.value?.toIntOrNull()
        }

        val episodes = filmEpisodes(document).ifEmpty { dooEpisodes(document) }

        // Sin episodios puede ser una pelicula (DooPlay las trae con el
        // reproductor en la propia ficha): se devuelve como pelicula para que
        // loadLinks la resuelva en vez de dejar la ficha vacia.
        if (episodes.isEmpty()) {
            if (document.selectFirst("#playeroptionsul, .server-item") == null) return null
            return newMovieLoadResponse(title, url, TvType.Anime, url) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = description ?: ""
                this.tags = tags
                this.year = year
                this.score = score
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

    /** Episodios de la plantilla film: `.episodes-ul` con el numero en data-number. */
    private fun filmEpisodes(document: Element): List<Episode> =
        document.select(".episodes-ul a.ep-item").mapIndexed { index, item ->
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

    /** Episodios de DooPlay: `#seasons` con "Temporada - Episodio" en .numerando. */
    private fun dooEpisodes(document: Element): List<Episode> =
        document.select("#seasons ul.episodios li").mapIndexedNotNull { index, item ->
            val href = item.selectFirst(".episodiotitle a[href]")?.attr("href")
                ?.takeIf { it.startsWith("http") } ?: return@mapIndexedNotNull null
            val partes = item.selectFirst(".numerando")?.text().orEmpty()
                .split("-").map { it.trim().toIntOrNull() }
            val season = partes.getOrNull(0) ?: 1
            val number = partes.getOrNull(1) ?: partes.getOrNull(0) ?: (index + 1)
            newEpisode(href) {
                this.name = "Episodio $number"
                this.season = season
                this.episode = number
            }
        }

    // ---------- links ----------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val response = fetch(urlPath(data))
        val document = response.document
        val origin = originOf(response.url ?: mainUrl) ?: mainUrl

        // Plantilla film: reproductores en data-hash (la url del reproductor
        // esta en base64, sin cifrar).
        val servers = document.select(".server-item a.btn[data-hash]")
        if (servers.isNotEmpty()) {
            var ok = false
            for (server in servers) {
                val encoded = server.attr("data-hash")
                if (encoded.isBlank()) continue
                val url = runCatching {
                    String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
                }.getOrNull() ?: continue
                if (!url.startsWith("http")) continue
                ok = resolvePlayer(url, data, subtitleCallback, callback) || ok
            }
            return ok
        }

        // Plantilla DooPlay: opciones de `#playeroptionsul` que se resuelven
        // con un POST a admin-ajax (doo_player_ajax).
        val options = document.select("#playeroptionsul li[data-post][data-nume]")
        if (options.isNotEmpty()) {
            var ok = false
            for (option in options) {
                val post = option.attr("data-post")
                val nume = option.attr("data-nume")
                if (post.isBlank() || nume.isBlank()) continue
                val type = option.attr("data-type").ifBlank { "tv" }
                val ajax = runCatching {
                    app.post(
                        "$origin/wp-admin/admin-ajax.php",
                        headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Referer" to (response.url ?: origin),
                        ),
                        data = mapOf(
                            "action" to "doo_player_ajax",
                            "post" to post,
                            "nume" to nume,
                            "type" to type,
                        ),
                        interceptor = cloudflareKiller,
                    )
                }.getOrNull() ?: continue
                val embed = runCatching { JSONObject(ajax.text).optString("embed_url") }
                    .getOrNull()?.takeIf { it.startsWith("http") } ?: continue
                ok = resolvePlayer(embed, data, subtitleCallback, callback) || ok
            }
            return ok
        }
        return false
    }

    /**
     * Lleva una url de reproductor hasta el video.
     *
     * Primero se lo entrega a los extractores nativos de CloudStream (si el
     * host es conocido no hace falta leer la pagina). Si ninguno lo coge, la
     * url apunta a una pagina propia: ahi puede estar el reproductor
     * multiserver (darkanimes, con los servidores cifrados en `#msp-data`) o
     * un iframe de megaplay, que trae el m3u8 por su api interna.
     */
    private suspend fun resolvePlayer(
        url: String,
        source: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val native = runCatching { loadExtractor(url, source, subtitleCallback, callback) }
            .getOrDefault(false)
        if (native) return true

        val page = runCatching { getWithBypass(url, mapOf("Referer" to source)) }.getOrNull() ?: return false
        val document = page.document

        // Fuente directa (una playlist sin pagina detras).
        if (page.text.trimStart().startsWith("#EXTM3U")) {
            callback(
                newExtractorLink(name, name, url, ExtractorLinkType.M3U8) {
                    this.referer = source
                }
            )
            return true
        }

        // multiserver-pro: json en linea con la clave pk y los servidores.
        val msp = multiServerJson(document, page.url ?: url)
        if (msp != null && multiServerLinks(msp, url, subtitleCallback, callback)) return true

        // El reproductor real vive en un iframe (megaplay en la plantilla film).
        val frame = document.selectFirst("iframe[src^=\"http\"]")?.attr("src")
            ?.takeIf { it.startsWith("http") } ?: return false
        if (hostOf(frame).contains("megaplay")) {
            return megaPlay(frame, source, subtitleCallback, callback)
        }
        return runCatching { loadExtractor(frame, source, subtitleCallback, callback) }
            .getOrDefault(false)
    }

    /** JSON del reproductor multiserver: inline (`#msp-data`) o pedido a su api. */
    private suspend fun multiServerJson(document: Element, pageUrl: String): JSONObject? {
        document.selectFirst("script#msp-data")?.data()?.let { raw ->
            runCatching { JSONObject(raw) }.getOrNull()?.let { return it }
        }
        val api = document.selectFirst("#msp[data-api]")?.attr("data-api")
            ?.takeIf { it.startsWith("http") } ?: return null
        val text = runCatching { getWithBypass(api).text }.getOrNull() ?: return null
        return runCatching { JSONObject(text) }.getOrNull()
    }

    /**
     * Carga los servidores del multiserver.
     *
     * Cada servidor trae su url en base64 xor con la clave `pk` del propio
     * json (la misma rutina que usa su javascript); al descifrarla sale un
     * hoster normal (streamtape, mp4upload, mxdrop...) que resuelven los
     * extractores de CloudStream.
     */
    private suspend fun multiServerLinks(
        json: JSONObject,
        source: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val pk = json.optString("pk").takeIf { it.isNotBlank() } ?: return false
        val langs = json.optJSONArray("langs") ?: return false
        var ok = false
        for (i in 0 until langs.length()) {
            val items = langs.optJSONObject(i)?.optJSONArray("items") ?: continue
            for (j in 0 until items.length()) {
                val item = items.optJSONObject(j) ?: continue
                val encoded = item.optString("url").takeIf { it.isNotBlank() } ?: continue
                val real = xorDecode(encoded, pk)?.takeIf { it.startsWith("http") } ?: continue
                val loaded = runCatching {
                    loadExtractor(real, source, subtitleCallback, callback)
                }.getOrDefault(false)
                ok = loaded || ok
            }
        }
        return ok
    }

    /**
     * megaplay (iframe de la plantilla film): pide sus fuentes por api y
     * descifra el resultado con aes (misma clave estatica de su javascript).
     * Devuelve el m3u8 y las pistas de subtitulos.
     *
     * megaplay rechaza las peticiones sin `Referer` de la web que lo embebe
     * (devuelve una pagina "Error"), asi que se manda el origen del episodio.
     */
    private suspend fun megaPlay(
        url: String,
        source: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val referer = originOf(source)?.let { "$it/" } ?: "$mainUrl/"
        val page = runCatching { getWithBypass(url, mapOf("Referer" to referer)) }.getOrNull() ?: return false
        val origin = originOf(page.url ?: url) ?: return false
        val document = page.document
        val id = document.selectFirst("#megaplay-player")?.attr("data-id")?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("[data-realid][data-id]")?.attr("data-id")?.takeIf { it.isNotBlank() }
            ?: return false

        val api = "$origin/stream/getSources?id=$id&platform=OTHER&st=other&_=${System.currentTimeMillis()}"
        val text = runCatching {
            app.get(
                api,
                headers = mapOf(
                    "Referer" to (page.url ?: url),
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json",
                ),
                interceptor = cloudflareKiller,
            ).text
        }.getOrNull() ?: return false
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return false

        val tracks = json.optJSONArray("tracks")
        if (tracks != null) {
            for (i in 0 until tracks.length()) {
                val track = tracks.optJSONObject(i) ?: continue
                if (track.optString("kind") != "captions") continue
                val file = track.optString("file").takeIf { it.startsWith("http") } ?: continue
                val lang = track.optString("label").split(" ").firstOrNull()
                    ?.takeIf { it.isNotBlank() } ?: "Español"
                subtitleCallback(SubtitleFile(lang, file))
            }
        }

        // La fuente viene cifrada en "enc" (aes-cbc con clave fija).
        val enc = json.optString("enc").takeIf { it.isNotBlank() } ?: return false
        val source = aesDecrypt(enc)
            ?.let { runCatching { JSONObject(it).optString("file") }.getOrNull() }
            ?.takeIf { it.startsWith("http") } ?: return false

        callback(
            newExtractorLink(name, name, source, ExtractorLinkType.M3U8) {
                this.referer = "$origin/"
                this.headers = mapOf("Referer" to "$origin/")
            }
        )
        return true
    }

    /** base64 + xor con la clave del sitio (misma rutina que su javascript). */
    private fun xorDecode(encoded: String, key: String): String? = runCatching {
        val normalizado = encoded.replace('-', '+').replace('_', '/')
        val bin = Base64.decode(normalizado, Base64.DEFAULT)
        val out = ByteArray(bin.size)
        for (i in bin.indices) {
            out[i] = (bin[i].toInt() xor key[i % key.length].code).toByte()
        }
        String(out, Charsets.UTF_8)
    }.getOrNull()

    /** Descifra el payload "enc" de megaplay (aes-256-cbc, clave e iv fijos). */
    private fun aesDecrypt(payload: String): String? = runCatching {
        val data = Base64.decode(payload.replace('-', '+').replace('_', '/'), Base64.DEFAULT)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val key = MEGA_KEY.toByteArray(Charsets.UTF_8) + ByteArray(16)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(MEGA_IV.toByteArray(Charsets.UTF_8)),
        )
        String(cipher.doFinal(data), Charsets.UTF_8)
    }.getOrNull()

    // ---------- url helpers ----------

    /** "https://host" de una url, o null si no se puede leer. */
    private fun originOf(url: String): String? = runCatching {
        val uri = java.net.URI(url)
        val scheme = uri.scheme
        val host = uri.host
        if (scheme.isNullOrBlank() || host.isNullOrBlank()) null else "$scheme://$host"
    }.getOrNull()

    /** Host en minusculas de una url (vacio si no hay). */
    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host.orEmpty().lowercase() }.getOrDefault("")

    companion object {
        /**
         * Dominios por orden de preferencia. `veranime.ninja` responde sin reto
         * y trae el mismo catalogo que la red oficial; `ver2.animeonline.ninja`
         * es el host definitivo de la web (detrás de Cloudflare, con bypass);
         * `animeonline.one` redirige a 9anime.qpon (catalogo en ingles) como
         * ultimo recurso.
         */
        private val DOMAINS = listOf(
            "https://veranime.ninja",
            "https://ver2.animeonline.ninja",
            "https://animeonline.one",
        )

        /** Claves del reproductor megaplay (estan literalmente en su js). */
        private val MEGA_KEY = "i?LMTAx0Q6,:}50U"
        private val MEGA_IV = "W0;27ToaUpl_P%'c"

        /** Hosts que son el mismo sitio, para pedirlos desde el dominio activo. */
        private fun isSameSiteHost(host: String): Boolean =
            host == "animeonline.ninja" || host.endsWith(".animeonline.ninja") ||
                DOMAINS.any { it.removePrefix("https://") == host }

        /**
         * Convierte una url del sitio en un path relativo.
         *
         * La red rota entre `ww3`/`ww4`/`ver2`, asi que esos enlaces se piden
         * desde el dominio activo (con el `cf_clearance` ya ganado) en lugar de
         * repetir el reto por cada host. Las urls de otros sitios (por ejemplo
         * `9anime.qpon`, que es donde acaba redirigiendo `animeonline.one`) se
         * dejan absolutas y se piden tal cual.
         */
        private fun urlPath(url: String): String {
            if (!url.startsWith("http")) {
                return if (url.startsWith("/")) url else "/$url"
            }
            val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return url
            val host = uri.host.orEmpty()
            if (host.isBlank() || !isSameSiteHost(host)) return url
            val path = uri.path?.takeIf { it.isNotBlank() } ?: "/"
            return path + (uri.query?.let { "?$it" } ?: "")
        }
    }
}
