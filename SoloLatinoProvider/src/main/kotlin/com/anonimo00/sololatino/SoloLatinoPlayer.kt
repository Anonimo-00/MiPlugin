package com.anonimo00.sololatino

import com.lagradost.cloudstream3.app
import com.lagradost.nicehttp.JsonAsString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder

/** Enlace de streaming ya resuelto y listo para reproducirse. */
internal data class SoloLatinoLink(
    val name: String,
    val url: String,
    val isM3u8: Boolean,
    val referer: String?,
)

/**
 * Resolutor de enlaces de sololatino.net.
 *
 * El sitio ya no usa el reproductor `embed69.org` con los enlaces cifrados
 * (POW + AES) que usaba el repositorio de referencia: ahora el flujo es
 *
 *  1. `POST /api/player-url` con el token del boton de servidor. Devuelve la
 *     url del reproductor y su tipo. La peticion va en JSON y necesita la
 *     cabecera `X-SL-Render`, que el sitio calcula con un SHA-256 truncado
 *     (ver [SoloLatinoShield]).
 *
 *  2. El reproductor real es `player.pelisserieshoy.com`. Su pagina incluye
 *     el token de sesion (`_t`), el identificador del contenido (`_qc`) y un
 *     token firmado (`rt`) repartido en un array de 4 trozos para dificultar
 *     la extraccion.
 *
 *  3. `POST /s.php` con `a=1` devuelve los servidores disponibles, `a=click`
 *     valida la reproduccion y `a=2` resuelve cada servidor a una url m3u8
 *     firmada (`sig`).
 *
 *  4. El m3u8 se reproduce a traves del proxy del propio sitio
 *     (`/p.php?url=...&sig=...`), que es el unico que acepta las peticiones:
 *     con un `Referer` de sololatino.net responde 403.
 */
internal object SoloLatinoPlayer {
    private const val BASE = "https://sololatino.net"
    private const val PLAYER = "https://player.pelisserieshoy.com"

    private val TOKEN_REGEX = Regex("""_t\s*=\s*'([^']+)'""")
    private val CONTENT_REGEX = Regex("""_qc\s*=\s*"([^"]*)"""")
    private val SHARDS_REGEX = Regex("""_qk\s*=\s*\[([^\]]*)]""")

    /** Sesion de Laravel: cookie de sesion y token XSRF. */
    private class Session(val cookie: String, val xsrf: String) {
        /** Cabeceras con la cookie de sesion (si se ha abierto sesion). */
        fun headers(): Map<String, String> =
            if (cookie.isBlank()) emptyMap() else mapOf("Cookie" to cookie)
    }

    /**
     * Resuelve todos los servidores de una pelicula o de un episodio.
     *
     * @param pageUrl url de la pelicula (`/pelicula/...`) o del episodio
     *                (`/serie/.../temporada-1/episodio-1`).
     */
    suspend fun resolve(pageUrl: String): List<SoloLatinoLink> {
        val session = openSession()
        val page = app.get(pageUrl, headers = session.headers()).document
        val tokens = page.select("button.server-btn[data-player-token]")
            .mapNotNull { it.attr("data-player-token").takeIf { token -> token.isNotBlank() } }
            .distinct()

        val links = LinkedHashMap<String, SoloLatinoLink>()
        for (token in tokens) {
            for (link in resolveToken(token, pageUrl, session)) {
                links.putIfAbsent(link.url, link)
            }
        }
        return links.values.toList()
    }

    /**
     * Abre sesion en el sitio. El backend es Laravel/Sanctum, asi que hace
     * falta la cookie `XSRF-TOKEN` (y su session) para poder llamar a la API.
     */
    private suspend fun openSession(): Session {
        val response = app.get("$BASE/sanctum/csrf-cookie")
        val jar = response.headers.values("Set-Cookie")
            .map { it.substringBefore(";").trim() }
            .filter { it.contains("=") }
        val xsrf = decodePercent(
            jar.firstOrNull { it.startsWith("XSRF-TOKEN=") }
                ?.substringAfter("XSRF-TOKEN=")
                .orEmpty()
        )
        return Session(jar.joinToString("; "), xsrf)
    }

    /** Resuelve un boton de servidor concreto. */
    private suspend fun resolveToken(
        token: String,
        pageUrl: String,
        session: Session,
    ): List<SoloLatinoLink> {
        val response = app.post(
            "$BASE/api/player-url",
            headers = mapOf(
                "Accept" to "application/json",
                "Content-Type" to "application/json",
                "X-XSRF-TOKEN" to session.xsrf,
                "X-SL-Render" to SoloLatinoShield.render(token),
                "Referer" to pageUrl,
                "Origin" to BASE,
            ) + session.headers(),
            data = null,
            json = JsonAsString("""{"t":"$token"}"""),
        )
        val json = runCatching { JSONObject(response.text) }.getOrNull() ?: return emptyList()
        val url = json.optString("url")
        val type = json.optString("type")
        return when {
            type == "mp4" && url.isNotBlank() -> listOf(
                SoloLatinoLink("Video", url, isM3u8 = false, referer = pageUrl)
            )

            url.contains("pelisserieshoy") -> resolvePlayer(url, pageUrl)

            // El resto de reproductores (embed69.org) estan protegidos con
            // Anubis (proof of work) y no se pueden resolver de forma fiable.
            else -> emptyList()
        }
    }

    /** Resuelve los servidores que expone el reproductor del sitio. */
    private suspend fun resolvePlayer(playerUrl: String, pageUrl: String): List<SoloLatinoLink> {
        val html = app.get(
            playerUrl,
            headers = mapOf(
                "Referer" to pageUrl,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            ),
        ).text

        val sessionToken = TOKEN_REGEX.find(html)?.groupValues?.get(1) ?: return emptyList()
        val contentId = CONTENT_REGEX.find(html)?.groupValues?.get(1) ?: return emptyList()
        val shards = SHARDS_REGEX.find(html)?.groupValues?.get(1)
            ?.split(",")
            ?.map { it.trim().trim('"', '\'') }
            ?: return emptyList()
        if (shards.size < 4) return emptyList()

        // El token firmado va repartido en 4 trozos que se recomponen en otro orden.
        val signedToken = shards[1] + shards[3] + shards[0] + shards[2]
        val proof = SoloLatinoShield.fnv1a("$signedToken|clk|$contentId")

        val common = mapOf(
            "tok" to sessionToken,
            "rt" to encode(signedToken),
            "tk" to proof,
            "ao" to encode(BASE),
        )

        val headers = mapOf(
            "Referer" to playerUrl,
            "Origin" to PLAYER,
            "Content-Type" to "application/x-www-form-urlencoded;charset=UTF-8",
        )

        val list = runCatching { JSONObject(playerPost(headers, mapOf("a" to "1") + common)) }
            .getOrNull()
            ?: return emptyList()
        val servers = list.optJSONArray("s") ?: return emptyList()

        // El sitio marca la reproduccion como iniciada antes de resolver el servidor.
        playerPost(headers, mapOf("a" to "click", "ts" to "1") + common)

        val links = LinkedHashMap<String, SoloLatinoLink>()
        for (index in 0 until servers.length()) {
            val server = servers.optJSONArray(index) ?: continue
            val label = server.optString(0)
            val id = server.optString(1)
            if (id.isBlank()) continue

            val resolved = runCatching {
                JSONObject(playerPost(headers, mapOf("a" to "2", "v" to id) + common))
            }.getOrNull() ?: continue

            val type = resolved.optString("type")
            val direct = resolved.optString("u")
            val signature = resolved.optString("sig")
            when {
                // Reproductor embebido: el propio sitio lo mete en un iframe.
                type == "iframe" && resolved.optString("url").isNotBlank() -> links.putIfAbsent(
                    resolved.optString("url"),
                    SoloLatinoLink(
                        label,
                        resolved.optString("url"),
                        isM3u8 = false,
                        referer = playerUrl,
                    ),
                )

                // Playlist HLS firmada: hay que pasar por el proxy del sitio.
                direct.startsWith("http") -> {
                    val proxy = "$PLAYER/p.php?url=${encode(direct)}&sig=${encode(signature)}"
                    links.putIfAbsent(
                        proxy,
                        SoloLatinoLink(label, proxy, isM3u8 = true, referer = playerUrl),
                    )
                }
            }
        }
        return links.values.toList()
    }

    /** POST del reproductor con cuerpo de formulario. */
    private suspend fun playerPost(
        headers: Map<String, String>,
        data: Map<String, String>,
    ): String = app.post(
        "$PLAYER/s.php",
        headers = headers,
        data = data,
    ).text

    /** Codifica un valor para un cuerpo `application/x-www-form-urlencoded`. */
    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /**
     * Decodifica percent-encoding sin tocar el `+`, que en una cookie significa
     * `+` y no un espacio (a diferencia de [URLEncoder]).
     */
    private fun decodePercent(value: String): String {
        if ('%' !in value) return value
        val out = ByteArrayOutputStream(value.length)
        var i = 0
        while (i < value.length) {
            val char = value[i]
            if (char == '%' && i + 2 < value.length) {
                val byte = value.substring(i + 1, i + 3).toIntOrNull(16)
                if (byte != null) {
                    out.write(byte)
                    i += 3
                    continue
                }
            }
            out.write(char.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}
