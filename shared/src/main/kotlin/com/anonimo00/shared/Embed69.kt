package com.anonimo00.shared

import android.util.Base64
import com.lagradost.cloudstream3.app
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Enlace de un reproductor externo, ya descifrado.
 *
 * @param name nombre legible (idioma + servidor).
 * @param url url del reproductor (pagina de Voe, Streamwish, Vidhide...).
 * @param referer pagina que debe enviarse como referer al pedir el stream.
 */
data class EmbedLink(
    val name: String,
    val url: String,
    val referer: String,
)

/**
 * Reproductor EMBED69 (`embed69.org/f/<id>`).
 *
 * Es el reproductor que usan tanto sololatino.net como flixlatam.com:
 *
 *  1. La pagina lleva `dataLink`, un array JSON en claro con los servidores
 *     por idioma. Cada enlace va cifrado con AES-256-CBC.
 *  2. La clave se deriva de `LINK_CHALLENGE` + `LINK_SALT` con SHA-256.
 *  3. Los 16 primeros bytes del base64 son el IV y el resto el texto cifrado.
 *
 * Ademas, `embed69.org` esta protegido por Anubis (proof of work): hay que
 * resolver el reto antes de poder leer la pagina.
 */
object Embed69 {
    private val DATA_LINK_REGEX = Regex("""let dataLink\s*=\s*(\[[\s\S]*?\]);""")
    private val CHALLENGE_REGEX = Regex("""LINK_CHALLENGE\s*=\s*'([^']+)'""")
    private val SALT_REGEX = Regex("""LINK_SALT\s*=\s*'([^']+)'""")
    private val ANUBIS_REGEX = Regex("""<script id="anubis_challenge"[^>]*>([\s\S]*?)</script>""")

    /** Nombre legible de cada servidor. */
    private val SERVER_NAMES = mapOf(
        "voe" to "Voe",
        "filemoon" to "Filemoon",
        "doodstream" to "Doodstream",
        "streamwish" to "Streamwish",
        "vidhide" to "Vidhide",
        "rapidvideo" to "Rapidvideo",
    )

    /**
     * Lee los servidores de un reproductor EMBED69.
     *
     * @param url url del reproductor (por ejemplo `https://embed69.org/f/tt123`).
     * @param referer pagina desde la que se abre el reproductor.
     */
    suspend fun resolve(url: String, referer: String?): List<EmbedLink> {
        val page = fetch(url, referer)
        val html = page.text
        val rawData = DATA_LINK_REGEX.find(html)?.groupValues?.get(1) ?: return emptyList()
        val challenge = CHALLENGE_REGEX.find(html)?.groupValues?.get(1) ?: return emptyList()
        val salt = SALT_REGEX.find(html)?.groupValues?.get(1) ?: return emptyList()

        val key = MessageDigest.getInstance("SHA-256")
            .digest((challenge + salt).toByteArray(Charsets.UTF_8))
        val files = runCatching { JSONArray(rawData) }.getOrNull() ?: return emptyList()

        val links = LinkedHashMap<String, EmbedLink>()
        for (fileIndex in 0 until files.length()) {
            val file = files.optJSONObject(fileIndex) ?: continue
            val language = file.optString("video_language", "LAT")
            val servers = file.optJSONArray("sortedEmbeds") ?: continue
            for (serverIndex in 0 until servers.length()) {
                val server = servers.optJSONObject(serverIndex) ?: continue
                val encrypted = server.optString("link")
                if (encrypted.isBlank()) continue
                val plain = decrypt(encrypted, key) ?: continue
                val target = fixHost(plain)
                if (!target.startsWith("http")) continue
                val name = SERVER_NAMES[server.optString("servername")]
                    ?: server.optString("servername").ifBlank { "Server" }
                links.putIfAbsent(target, EmbedLink("$language [$name]", target, url))
            }
        }
        return links.values.toList()
    }

    /** Descifra un enlace AES-256-CBC (IV = primeros 16 bytes del base64). */
    private fun decrypt(encryptedBase64: String, key: ByteArray): String? = try {
        val raw = Base64.decode(encryptedBase64, Base64.DEFAULT)
        val iv = raw.copyOfRange(0, 16)
        val ciphertext = raw.copyOfRange(16, raw.size)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key.copyOfRange(0, 32), "AES"),
            IvParameterSpec(iv),
        )
        String(cipher.doFinal(ciphertext), Charsets.UTF_8).replace("`", "").trim()
    } catch (e: Exception) {
        null
    }

    /**
     * Normaliza los dominios rotados de los reproductores a los que los
     * extractores de CloudStream ya conocen.
     */
    fun fixHost(url: String): String = url
        .replaceFirst("https://morencius.com", "https://vidhidepro.com")
        .replaceFirst("https://minochinos.com", "https://vidhidepro.com")
        .replaceFirst("https://mivalyo.com", "https://vidhidepro.com")
        .replaceFirst("https://dinisglows.com", "https://vidhidepro.com")
        .replaceFirst("https://dhtpre.com", "https://vidhidepro.com")
        .replaceFirst("https://hglink.to", "https://streamwish.to")
        .replaceFirst("https://swdyu.com", "https://streamwish.to")
        .replaceFirst("https://cybervynx.com", "https://streamwish.to")
        .replaceFirst("https://dumbalag.com", "https://streamwish.to")
        .replaceFirst("https://filemoon.link", "https://filemoon.sx")
        .replaceFirst("https://sblona.com", "https://watchsb.com")
        .replaceFirst("https://lulu.st", "https://lulustream.com")
        .replaceFirst("https://uqload.io", "https://uqload.com")
        .replaceFirst("https://do7go.com", "https://dood.la")

    // ------------------------------------------------------------- Anubis

    /** Pagina descargada junto con las cookies de la respuesta. */
    private class Page(val text: String, val cookies: Map<String, String>)

    /**
     * Descarga la pagina del reproductor resolviendo antes el reto de Anubis
     * si el sitio lo tiene puesto.
     */
    private suspend fun fetch(url: String, referer: String?): Page {
        val headers = referer?.let { mapOf("Referer" to it) } ?: emptyMap()
        val response = app.get(url, headers = headers)
        val cookies = parseCookies(response.headers.values("Set-Cookie"))
        val text = response.text
        val challenge = ANUBIS_REGEX.find(text)?.groupValues?.get(1) ?: return Page(text, cookies)

        // El sitio esta protegido con Anubis: hay que resolver el proof of work.
        val passed = passAnubis(url, challenge, referer, cookies) ?: return Page(text, cookies)
        val after = app.get(
            url,
            headers = headers + mapOf("Cookie" to cookieHeader(passed.cookies)),
        )
        return Page(after.text, passed.cookies)
    }

    /** Cookies de una respuesta. */
    private fun parseCookies(setCookies: List<String>): Map<String, String> =
        setCookies.mapNotNull { cookie ->
            val pair = cookie.substringBefore(";").trim()
            val index = pair.indexOf("=")
            if (index <= 0) null else pair.substring(0, index) to pair.substring(index + 1)
        }.toMap()

    /** Cabecera `Cookie` a partir del mapa de cookies. */
    private fun cookieHeader(cookies: Map<String, String>): String =
        cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    /** Cookies resultantes de pasar el reto de Anubis. */
    private class Passed(val cookies: Map<String, String>)

    /**
     * Resuelve el proof of work de Anubis y devuelve las cookies que dan
     * acceso a la pagina.
     */
    private suspend fun passAnubis(
        url: String,
        challengeJson: String,
        referer: String?,
        cookies: Map<String, String>,
    ): Passed? {
        val challenge = runCatching {
            val root = JSONObject(challengeJson)
            val challenge = root.getJSONObject("challenge")
            Triple(
                challenge.getString("randomData"),
                challenge.getInt("difficulty"),
                challenge.getString("id"),
            )
        }.getOrNull() ?: return null

        val start = System.currentTimeMillis()
        val (hash, nonce) = solve(challenge.first, challenge.second)
        val elapsed = System.currentTimeMillis() - start

        val uri = runCatching { java.net.URI(url) }.getOrNull()
        val origin = if (uri == null) {
            url.substringBeforeLast("/")
        } else {
            "${uri.scheme}://${uri.authority}"
        }
        val redirect = uri?.let { it.rawPath + (it.rawQuery?.let { q -> "?$q" } ?: "") }
            ?: url.substringAfterLast("/")
        val passUrl = "$origin/.within.website/x/cmd/anubis/api/pass-challenge" +
            "?id=${challenge.third}" +
            "&response=$hash" +
            "&nonce=$nonce" +
            "&redir=${java.net.URLEncoder.encode(redirect, "UTF-8")}" +
            "&elapsedTime=$elapsed"

        val response = app.get(
            passUrl,
            headers = buildMap {
                referer?.let { put("Referer", it) }
                if (cookies.isNotEmpty()) {
                    put("Cookie", cookieHeader(cookies))
                }
            },
            allowRedirects = false,
        )
        val newCookies = parseCookies(response.headers.values("Set-Cookie"))
        if (newCookies.isEmpty()) return null
        return Passed(cookies + newCookies)
    }

    /**
     * Proof of work de Anubis: busca un nonce cuyo SHA-256 (datos + nonce)
     * tenga los primeros `difficulty` bits a cero.
     */
    private fun solve(randomData: String, difficulty: Int): Pair<String, Int> {
        val fullBytes = difficulty / 2
        val halfByte = difficulty % 2 != 0
        val digest = MessageDigest.getInstance("SHA-256")
        var nonce = 0
        while (true) {
            digest.reset()
            val bytes = digest.digest((randomData + nonce).toByteArray(Charsets.UTF_8))
            var valid = true
            for (i in 0 until fullBytes) {
                if (bytes[i].toInt() != 0) {
                    valid = false
                    break
                }
            }
            if (valid && halfByte && ((bytes[fullBytes].toInt() and 0xFF) shr 4) != 0) {
                valid = false
            }
            if (valid) {
                return Pair(bytes.joinToString("") { "%02x".format(it) }, nonce)
            }
            nonce++
        }
    }
}
