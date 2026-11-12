package com.anonimo00.flixlatam

import android.util.Base64
import com.lagradost.cloudstream3.app
import org.json.JSONArray
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Enlace de reproduccion de un servidor (ya descifrado). */
internal data class FlixLatamLink(
    val name: String,
    val url: String,
    /** Pagina que reproduce el enlace (los extractores de CloudStream la usan como referer). */
    val referer: String,
)

/**
 * Resolutor de enlaces de flixlatam.com.
 *
 * El sitio usa el tema Dooplay, cuyo reproductor (`/vidurl/<id>/`) es una
 * pagina de EMBED69 con la lista de servidores embebida en el HTML:
 *
 *  - `dataLink`: array JSON en claro con los servidores por idioma. Cada
 *    enlace va cifrado con AES-256-CBC.
 *  - `LINK_CHALLENGE` y `LINK_SALT`: con los dos se deriva la clave
 *    (`SHA-256(challenge + salt)`).
 *  - El primer bloque del base64 es el IV y el resto es el texto cifrado.
 *
 * Los enlaces resultantes apuntan a reproductores externos (Voe, Streamwish,
 * Vidhide...) que CloudStream ya sabe resolver, asi que se les pasa al
 * extractor que corresponda.
 */
internal object FlixLatamPlayer {
    private const val BASE = "https://flixlatam.com"

    private val DATA_LINK_REGEX = Regex("""let dataLink\s*=\s*(\[[\s\S]*?\]);""")
    private val CHALLENGE_REGEX = Regex("""LINK_CHALLENGE\s*=\s*'([^']+)'""")
    private val SALT_REGEX = Regex("""LINK_SALT\s*=\s*'([^']+)'""")

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
     * Resuelve los servidores de una pelicula o de un episodio.
     *
     * @param pageUrl url de la ficha (`/pelicula/...`, `/serie/...` o
     *                `/anime/...`), incluida la de un episodio.
     */
    suspend fun resolve(pageUrl: String): List<FlixLatamLink> {
        val page = app.get(pageUrl).document
        val playerPath = page.selectFirst("iframe[src*=/vidurl/]")?.attr("src")
            ?: return emptyList()
        val playerUrl = if (playerPath.startsWith("http")) playerPath else "$BASE$playerPath"

        val html = app.get(playerUrl, headers = mapOf("Referer" to pageUrl)).text
        val rawData = DATA_LINK_REGEX.find(html)?.groupValues?.get(1) ?: return emptyList()
        val challenge = CHALLENGE_REGEX.find(html)?.groupValues?.get(1) ?: return emptyList()
        val salt = SALT_REGEX.find(html)?.groupValues?.get(1) ?: return emptyList()

        val key = MessageDigest.getInstance("SHA-256")
            .digest((challenge + salt).toByteArray(Charsets.UTF_8))

        val files = runCatching { JSONArray(rawData) }.getOrNull() ?: return emptyList()

        val links = LinkedHashMap<String, FlixLatamLink>()
        for (fileIndex in 0 until files.length()) {
            val file = files.optJSONObject(fileIndex) ?: continue
            val language = file.optString("video_language", "LAT")
            val servers = file.optJSONArray("sortedEmbeds") ?: continue
            for (serverIndex in 0 until servers.length()) {
                val server = servers.optJSONObject(serverIndex) ?: continue
                val encrypted = server.optString("link")
                if (encrypted.isBlank()) continue
                val plain = decrypt(encrypted, key) ?: continue
                val url = fixHost(plain)
                if (!url.startsWith("http")) continue
                val name = SERVER_NAMES[server.optString("servername")]
                    ?: server.optString("servername").ifBlank { "Server" }
                links.putIfAbsent(url, FlixLatamLink("$language [$name]", url, playerUrl))
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
    private fun fixHost(url: String): String = url
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
}
