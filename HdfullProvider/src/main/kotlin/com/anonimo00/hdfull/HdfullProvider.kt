package com.anonimo00.hdfull

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.util.Base64
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.anonimo00.shared.SearchHelper
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
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Proveedor de hdfull.org (peliculas y series en espanol latino).
 *
 * **El sitio es privado**: casi todas las rutas redirigen a `/login`, asi que
 * hace falta una cuenta. La extension pide usuario y contrasena en el panel de
 * ajustes de la propia app (el icono de "tune" junto a la extension) y guarda
 * la sesion (PHPSESSID) en las SharedPreferences.
 *
 * Como funciona el reproductor:
 *
 * - La pagina de la pelicula/episodio trae la variable `ad`, que es la lista
 *   de servidores **cifrada**: es base64 y luego cada caracter con codigo
 *   <= 126 se desplaza `(codigo + 112) % 126` (el mismo `obfs`/`substrings` que
 *   usa el JS del sitio). Al descifrarla sale un JSON con un elemento por
 *   servidor: `{id, provider, code, lang, quality}`.
 * - `provider` es el id del servidor dentro de la tabla `js/providers.js` del
 *   sitio (1=powvideo, 2=streamplay, 4=upstream, 5=cloudvideo, 6=streamtape,
 *   12=gamovideo, 15=mixdrop, 31=vidoza, 40=vidmoly, 45=waaw/hqq).
 * - La busqueda es un POST a `/buscar` con `__csrf_magic`, `menu=search` y
 *   `query`, y las temporadas/episodios seSacan de `/a/episodes`.
 */
class HdfullProvider(private val context: Context) : MainAPI() {
    override var mainUrl = "https://hdfull.org"
    override var name = "HDFull"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "peliculas" to "Peliculas",
        "series" to "Series",
        "peliculas-actualizadas" to "Actualizadas",
    )

    private val prefs = context.getSharedPreferences("hdfull", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Evita abrir el dialogo de credenciales varias veces a la vez. */
    private val prompting = AtomicBoolean(false)

    // ---------- credenciales ----------

    private var user: String?
        get() = prefs.getString("user", null)
        set(value) = prefs.edit().putString("user", value).apply()

    private var password: String?
        get() = prefs.getString("password", null)
        set(value) = prefs.edit().putString("password", value).apply()

    private var session: String?
        get() = prefs.getString("session", null)
        set(value) = prefs.edit().putString("session", value).apply()

    // ---------- panel de ajustes ----------

    /**
     * Abre el panel de ajustes de la extension (el boton "tune" que CloudStream
     * muestra junto a la extension instalada). Permite escribir el usuario y la
     * contrasena de la cuenta de hdfull.
     */
    fun openSettings(ctx: Context) {
        scope.launch { showSettings(ctx, null) }
    }

    /**
     * Muestra el dialogo con los campos de usuario y contrasena.
     *
     * @param pending si no es null, se completa con la cookie de sesion en
     * cuanto el usuario guarda unos credenciales validos (o con null si
     * cancela). Sirve para que el proveedor pueda pedir las credenciales
     * cuando aun no hay ninguna guardada.
     */
    private suspend fun showSettings(ctx: Context, pending: CompletableDeferred<String?>?) {
        val userField = EditText(ctx).apply {
            hint = "Usuario"
            setSingleLine()
            setText(user ?: "")
        }
        val passField = EditText(ctx).apply {
            hint = "Contrasena"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
            addView(userField)
            addView(passField)
        }
        AlertDialog.Builder(ctx)
            .setTitle("HDFull: cuenta")
            .setMessage("hdfull necesita una cuenta para ver el catalogo. Puedes registrarte gratis en hdfull.org.")
            .setView(layout)
            .setPositiveButton("Guardar") { _, _ ->
                val typedUser = userField.text.toString().trim()
                val typedPassword = passField.text.toString()
                user = typedUser
                password = typedPassword
                scope.launch {
                    val cookie = if (typedUser.isNotBlank() && typedPassword.isNotBlank()) {
                        login(typedUser, typedPassword)
                    } else {
                        null
                    }
                    if (cookie == null) {
                        // Credenciales invalidas: las borramos para no insistir.
                        clearCredentials()
                        Toast.makeText(
                            ctx,
                            "Usuario o contraseña incorrectos",
                            Toast.LENGTH_LONG,
                        ).show()
                    } else {
                        Toast.makeText(ctx, "Sesión iniciada", Toast.LENGTH_SHORT).show()
                    }
                    pending?.complete(cookie)
                }
            }
            .setNegativeButton("Cancelar") { _, _ -> pending?.complete(null) }
            .setOnCancelListener { pending?.complete(null) }
            .show()
    }

    // ---------- sesion ----------

    /**
     * Devuelve una sesion valida. Si no hay ninguna guardada, se intenta iniciar
     * sesion con las credenciales guardadas y, si tampoco las hay, se pide al
     * usuario que las escriba en el panel de ajustes.
     *
     * Si el login con credenciales guardadas falla, se borran esas credenciales
     * para no pedirle al usuario una y otra vez algo que ya no sirve.
     */
    private suspend fun ensureSession(): String? {
        session?.let { return it }
        if (!prompting.compareAndSet(false, true)) return null
        return try {
            val storedUser = user
            val storedPassword = password
            if (!storedUser.isNullOrBlank() && !storedPassword.isNullOrBlank()) {
                login(storedUser, storedPassword)?.let { return it }
                // Login falló con credenciales guardadas: las borramos para no
                // insistir con algo que ya no sirve.
                clearCredentials()
            }
            val pending = CompletableDeferred<String?>()
            withContext(Dispatchers.Main) { showSettings(context, pending) }
            pending.await()
        } finally {
            prompting.set(false)
        }
    }

    /** Borra usuario, contraseña y sesión guardados. */
    private fun clearCredentials() {
        user = null
        password = null
        session = null
    }

    /** Inicia sesion y guarda la cookie de sesion. */
    private suspend fun login(user: String, password: String): String? = withContext(Dispatchers.IO) {
        val page = app.get("$mainUrl/login")
        val magic = csrfOf(page.text) ?: return@withContext null
        val sid = page.headers.values("Set-Cookie")
            .firstOrNull { it.startsWith("PHPSESSID=") }
            ?.substringBefore(";")
            ?.substringAfter("=")
            ?: return@withContext null
        val cookie = "PHPSESSID=$sid"
        val response = app.post(
            "$mainUrl/a/login",
            headers = mapOf(
                "Cookie" to cookie,
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to "$mainUrl/login",
            ),
            data = mapOf(
                "username" to encode(user),
                "password" to encode(password),
                "action" to "login",
                "__csrf_magic" to encode(magic),
            ),
        )
        val ok = runCatching { JSONObject(response.text).optString("status") == "OK" }
            .getOrDefault(false)
        if (!ok) return@withContext null
        session = cookie
        cookie
    }

    /**
     * Pide una pagina con la sesion. Si la sesion ha caducado (el sitio
     * redirige a `/login`) se vuelve a iniciar sesion con las credenciales
     * guardadas.
     */
    private suspend fun getPage(path: String): Page? = withContext(Dispatchers.IO) {
        var cookie = ensureSession() ?: return@withContext null
        // `load`/`loadLinks` reciben urls absolutas; si se antepusiera el
        // dominio saldria "https://hdfull.orghttps://..." (host invalido y
        // NXDOMAIN), asi que solo se anteponen las rutas relativas.
        val target = if (path.startsWith("http")) path else "$mainUrl$path"
        var response = app.get(target, headers = mapOf("Cookie" to cookie))
        if (response.url.contains("/login")) {
            session = null
            val storedUser = user
            val storedPassword = password
            if (storedUser.isNullOrBlank() || storedPassword.isNullOrBlank()) {
                return@withContext null
            }
            cookie = login(storedUser, storedPassword) ?: return@withContext null
            response = app.get(target, headers = mapOf("Cookie" to cookie))
        }
        Page(response.text, response.document)
    }

    /** Contenido de una pagina (html + documento parseado). */
    private data class Page(val html: String, val document: Element)

    // ---------- tarjetas ----------

    private fun Element.toCard(): SearchResponse? {
        val link = this.selectFirst("a.spec-border-ie")?.attr("href")?.takeIf { it.isNotBlank() }
            ?: this.selectFirst("a[href^='/pelicula/']")?.attr("href")?.takeIf { it.isNotBlank() }
            ?: this.selectFirst("a[href^='/serie/']")?.attr("href")?.takeIf { it.isNotBlank() }
            ?: return null
        val title = this.selectFirst("h5.left a.link")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: this.selectFirst("h3")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: this.selectFirst("h4")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: this.selectFirst("img.img-preview")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        val poster = this.selectFirst("img.img-preview")?.attr("src")?.takeIf { it.isNotBlank() }
            ?: this.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
        val rating = this.selectFirst(".rating-pod .rating")?.text()?.trim()
            ?.replace(",", ".")?.toDoubleOrNull()?.let { Score.from(it, 10) }
        val type = if (link.contains("/serie/")) TvType.TvSeries else TvType.Movie
        return when (type) {
            TvType.TvSeries -> newTvSeriesSearchResponse(title, link, type) {
                this.posterUrl = fixUrlNull(poster)
                this.score = rating
            }

            else -> newMovieSearchResponse(title, link, type) {
                this.posterUrl = fixUrlNull(poster)
                this.score = rating
            }
        }
    }

    private fun cards(document: Element): List<SearchResponse> {
        val detailed = document.select("div.span-6.inner-6.tt.view").mapNotNull { it.toCard() }
        if (detailed.isNotEmpty()) return detailed
        return document.select("div.span-6").mapNotNull { it.toCard() }
    }

    // ---------- pagina principal ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = if (page <= 1) "/${request.data}" else "/${request.data}/$page"
        val document = getPage(path)?.document ?: return newHomePageResponse(emptyList())
        val list = cards(document)
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = list,
                isHorizontalImages = false,
            ),
            hasNext = list.isNotEmpty() && hasNextPage(document, page),
        )
    }

    /** El sitio pagina las listas con enlaces `/<seccion>/<numero>`. */
    private fun hasNextPage(document: Element, page: Int): Boolean {
        val next = document.select("div.row-pages-wrapper a, .pagination a, a[href*=page=]")
        return next.any { link ->
            val href = link.attr("href")
            val number = href.substringAfterLast("/").substringBefore("?").toIntOrNull()
            number != null && number > page
        }
    }

    // ---------- busqueda ----------

    override suspend fun search(query: String): List<SearchResponse> =
        SearchHelper.smartSearch(name, query) { siteSearch(it) }

    /** Busqueda en el sitio (la usa [search] con consultas alternativas). */
    private suspend fun siteSearch(query: String): List<SearchResponse> {
        val cookie = ensureSession() ?: return emptyList()
        val magic = getPage("/")?.let { csrfOf(it.html) } ?: return emptyList()
        val response = app.post(
            "$mainUrl/buscar",
            headers = mapOf(
                "Cookie" to cookie,
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to "$mainUrl/",
            ),
            data = mapOf(
                "__csrf_magic" to encode(magic),
                "menu" to "search",
                "query" to encode(query),
            ),
        )
        return cards(Jsoup.parse(response.text))
    }

    // ---------- ficha ----------

    override suspend fun load(url: String): LoadResponse? {
        val page = getPage(url) ?: return null
        val document = page.document
        val title = document.selectFirst("#summary-title")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        val poster = document.selectFirst(".show-poster img.video-page-thumbnail")?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst(".show-poster img")?.attr("src")?.takeIf { it.isNotBlank() }
        val description = document.selectFirst(".show-overview-text")?.text()?.trim()
        val score = document.selectFirst("meta[itemprop=ratingValue]")?.attr("content")
            ?.toDoubleOrNull()?.let { Score.from(it, 10) }
        val tags = document.select(".show-details a[itemprop=genre]").map { it.text().trim() }.distinct()
        val year = document.selectFirst(".show-details a[href^='/buscar/year/']")?.text()?.trim()
            ?.toIntOrNull()

        return if (url.contains("/serie/")) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesOf(page)) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = description ?: ""
                this.tags = tags
                this.year = year
                this.score = score
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = description ?: ""
                this.tags = tags
                this.year = year
                this.score = score
            }
        }
    }

    /**
     * Episodios de una serie. El sitio los carga por ajax con `/a/episodes`,
     * pero tambien vienen en el HTML de la ficha, asi que se usa lo que
     * aparezca primero.
     */
    private suspend fun episodesOf(page: Page): List<Episode> {
        val episodes = episodeLinks(page.document).toMutableList()
        if (episodes.isEmpty()) {
            val slug = page.document.selectFirst("#season-list a")?.attr("href")
                ?.substringAfter("/serie/")
                ?.substringBefore("/")
                ?.takeIf { it.isNotBlank() }
            if (slug != null) {
                for (season in 1..MAX_SEASONS) {
                    val seasonPage = getPage("/serie/$slug/temporada-$season") ?: break
                    val found = episodeLinks(seasonPage.document)
                    if (found.isEmpty()) break
                    episodes += found
                }
            }
        }
        if (episodes.isEmpty()) episodes += episodesFromApi(page)
        return episodes
    }

    /** Episodios que aparecen en el HTML con la ruta `/temporada-N/episodio-M`. */
    private fun episodeLinks(document: Element): List<Episode> {
        val result = mutableListOf<Episode>()
        for (link in document.select("a[href*='/episodio-']")) {
            val href = link.attr("href")
            if (href.isBlank()) continue
            val url = if (href.startsWith("http")) href else "$mainUrl$href"
            val match = EPISODE_URL_REGEX.find(url) ?: continue
            val season = match.groupValues[1].toIntOrNull() ?: continue
            val number = match.groupValues[2].toIntOrNull() ?: continue
            val title = link.text().trim().takeIf { it.isNotBlank() } ?: "Episodio $number"
            result += newEpisode(url) {
                this.name = title
                this.season = season
                this.episode = number
            }
        }
        return result
    }

    /** Ultimo recurso: la API de episodios del propio sitio. */
    private suspend fun episodesFromApi(page: Page): List<Episode> {
        val cookie = session ?: return emptyList()
        val sid = SID_REGEX.find(page.html)?.groupValues?.get(1) ?: return emptyList()
        val slug = page.document.selectFirst("#season-list a")?.attr("href")
            ?.substringAfter("/serie/")?.substringBefore("/")?.takeIf { it.isNotBlank() }
            ?: return emptyList()
        val result = mutableListOf<Episode>()
        for (season in 1..MAX_SEASONS) {
            val response = app.post(
                "$mainUrl/a/episodes",
                headers = mapOf(
                    "Cookie" to cookie,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to "$mainUrl/serie/$slug",
                ),
                data = mapOf(
                    "action" to "season",
                    "start" to "0",
                    "limit" to "100",
                    "elang" to "ALL",
                    "show" to sid,
                    "season" to season.toString(),
                ),
            )
            val array = runCatching { JSONArray(response.text) }.getOrNull() ?: break
            if (array.length() == 0) break
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val number = item.optInt("episode", 0).takeIf { it > 0 } ?: continue
                val itemSeason = item.optInt("season", season).takeIf { it > 0 } ?: season
                val permalink = item.optString("permalink").takeIf { it.isNotBlank() }
                    ?: item.optString("perma").takeIf { it.isNotBlank() }
                    ?: slug
                val url = "$mainUrl/serie/$permalink/temporada-$itemSeason/episodio-$number"
                val title = item.optString("title").takeIf { it.isNotBlank() } ?: "Episodio $number"
                result += newEpisode(url) {
                    this.name = title
                    this.season = itemSeason
                    this.episode = number
                }
            }
        }
        return result
    }

    // ---------- enlaces ----------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val page = try {
            withTimeout(15_000) { getPage(data) }
        } catch (_: Exception) {
            return false
        } ?: return false
        val ad = AD_REGEX.find(page.html)?.groupValues?.get(1) ?: return false
        val array = runCatching { JSONArray(decodeAd(ad)) }.getOrNull() ?: return false

        var found = false
        for (index in 0 until array.length()) {
            val server = array.optJSONObject(index) ?: continue
            val code = server.optString("code")
            if (code.isBlank()) continue
            val providerId = server.optInt("provider", -1)
            val hoster = HOSTERS[providerId] ?: continue
            val url = hoster.url(code)
            val lang = server.optString("lang").takeIf { it.isNotBlank() }
            val quality = server.optString("quality").takeIf { it.isNotBlank() }
            val name = listOfNotNull(hoster.name, lang, quality)
                .joinToString(" ")
                .ifBlank { hoster.name }

            // Cada servidor se intenta de forma independiente con timeout corto:
            // si uno no responde (vidmoly/powvideo/etc. lentos o caídos), no
            // bloquea a los demás ni hace fallar toda la ficha.
            try {
                withTimeout(12_000) {
                    when (hoster.kind) {
                        // CloudStream ya tiene extractores para estos servidores.
                        HosterKind.EXTRACTOR -> loadExtractor(url, data, subtitleCallback, callback)
                        // Estos no: se extraen a mano.
                        else -> extractDirect(hoster, url, code, data, name, callback)
                    }
                }
            } catch (_: Exception) {
                // servidor lento o muerto: probamos con el siguiente
            }

            found = true
        }
        return found
    }

    /** Servidores que CloudStream no sabe extraer. */
    private suspend fun extractDirect(
        hoster: Hoster,
        url: String,
        code: String,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        when (hoster.kind) {
            HosterKind.POWVIDEO -> {
                // powvideo esconde el player en un iframe con el sources
                // empaquetado (P.A.C.K.E.R).
                val iframe = "https://powvideo.org/iframe-$code-640x360.html"
                val html = app.get(
                    iframe,
                    headers = mapOf(
                        "Referer" to url,
                        "User-Agent" to USER_AGENT,
                    ),
                ).text
                val unpacked = getAndUnpack(html)
                val source = SOURCES_REGEX.find(unpacked)?.groupValues?.get(1)
                    ?: M3U8_REGEX.find(unpacked)?.groupValues?.get(1)
                if (source != null) {
                    callback.invoke(
                        newExtractorLink(name, name, source, ExtractorLinkType.M3U8) {
                            this.referer = url
                        }
                    )
                }
            }

            HosterKind.HQQ -> {
                // hqq.tv redirige a otro dominio y pide la fuente por ajax.
                val page = app.get(url, headers = mapOf("Referer" to referer)).text
                val host = REDIRECT_REGEX.find(page)?.groupValues?.get(1)
                val query = WS_REGEX.find(page)?.groupValues?.get(1)
                if (host != null && query != null) {
                    val json = app.get(
                        "$host/player/load_vtt.php?v=23&url=$query",
                        headers = mapOf("Referer" to url),
                    ).text
                    val source = M3U8_REGEX.find(json)?.groupValues?.get(1)
                        ?: M3U8_REGEX.find(page)?.groupValues?.get(1)
                    if (source != null) {
                        callback.invoke(
                            newExtractorLink(name, name, source, ExtractorLinkType.M3U8) {
                                this.referer = url
                            }
                        )
                    }
                }
            }

            HosterKind.CLOUDVIDEO -> {
                // cloudvideo va con Cloudflare, pero a veces responde sin el.
                val html = app.get(url, headers = mapOf("Referer" to referer)).text
                val source = M3U8_REGEX.find(html)?.groupValues?.get(1)
                if (source != null) {
                    callback.invoke(
                        newExtractorLink(name, name, source, ExtractorLinkType.M3U8) {
                            this.referer = url
                        }
                    )
                }
            }

            HosterKind.EXTRACTOR -> Unit
        }
    }

    // ---------- cripto ----------

    /**
     * La variable `ad` es base64 y encima cada caracter con codigo <= 126 se
     * desplaza `(codigo + 112) % 126`. Es el `obfs`/`substrings` del sitio.
     */
    private fun decodeAd(ad: String): String {
        val bytes = Base64.decode(ad, Base64.DEFAULT)
        return buildString {
            for (byte in bytes) {
                val code = byte.toInt() and 0xFF
                append(if (code <= 126) (((code + 112) % 126).toChar()) else code.toChar())
            }
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** El token csrf va en un input oculto de todas las paginas con sesion. */
    private fun csrfOf(html: String): String? {
        val tag = CSRF_INPUT.find(html)?.value ?: return null
        return VALUE_REGEX.find(tag)?.groupValues?.get(1)
    }

    companion object {
        private const val MAX_SEASONS = 30

        private val CSRF_INPUT = Regex("""<input[^>]*__csrf_magic[^>]*>""")
        private val VALUE_REGEX = Regex("""value=['"]([^'"]+)['"]""")
        private val AD_REGEX = Regex("""var\s+ad\s*=\s*'([^']+)'""")
        private val SID_REGEX = Regex("""var\s+sid\s*=\s*['"]?(\d+)""")
        private val EPISODE_URL_REGEX = Regex("""/temporada-(\d+)/episodio-(\d+)""")
        private val M3U8_REGEX = Regex("""(https?://[^"'\s\\]+\.m3u8[^"'\s\\]*)""")
        private val SOURCES_REGEX = Regex("""src\s*:\s*['"]([^'"]+)['"]""")
        private val REDIRECT_REGEX = Regex("""hostRedirection\s*=\s*['"]([^'"]+)['"]""")
        private val WS_REGEX = Regex("""var\s+ws\s*=\s*['"]([^'"]+)['"]""")

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private enum class HosterKind { EXTRACTOR, POWVIDEO, CLOUDVIDEO, HQQ }

        private class Hoster(
            val name: String,
            val kind: HosterKind,
            val url: (String) -> String,
        )

        /**
         * Tabla de servidores del sitio (`js/providers.js`). Solo se listan los
         * que sirven para ver en linea (los de descarga se ignoran).
         */
        private val HOSTERS = mapOf(
            1 to Hoster("powvideo", HosterKind.POWVIDEO) { "https://powvideo.org/embed-$it-920x560.html" },
            2 to Hoster("streamplay", HosterKind.EXTRACTOR) { "https://streamplay.to/embed-$it-920x560.html" },
            4 to Hoster("upstream", HosterKind.EXTRACTOR) { "https://upstream.to/embed-$it-920x360.html" },
            5 to Hoster("cloudvideo", HosterKind.CLOUDVIDEO) { "https://cloudvideo.tv/embed-$it-920x360.html" },
            6 to Hoster("streamtape", HosterKind.EXTRACTOR) { "https://streamtape.com/e/$it" },
            12 to Hoster("gamovideo", HosterKind.EXTRACTOR) { "https://gamovideo.com/embed-$it-920x360.html" },
            15 to Hoster("mixdrop", HosterKind.EXTRACTOR) { "https://mixdrop.bz/e/$it" },
            31 to Hoster("vidoza", HosterKind.EXTRACTOR) { "https://vidoza.net/embed-$it-920x360.html" },
            40 to Hoster("vidmoly", HosterKind.EXTRACTOR) { "https://vidmoly.biz/embed-$it-920x360.html" },
            45 to Hoster("waaw", HosterKind.HQQ) { "https://hqq.tv/player/embed_player.php?vid=$it&autoplay=no" },
        )
    }
}
