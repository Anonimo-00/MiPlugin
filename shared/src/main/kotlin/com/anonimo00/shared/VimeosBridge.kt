package com.anonimo00.shared

import com.lagradost.cloudstream3.app
import org.json.JSONObject

/**
 * Reproductor vimeos con token JWT (la variante que usa serieslan.live).
 *
 * A diferencia del vimeos clasico (que empaqueta el JavaScript con
 * P.A.C.K.E.R), aqui la respuesta va en claro dentro de la pagina:
 *
 * ```
 * window.VIDAPI_CF_STREAM_RESPONSE = {"data":{"stream_urls":["https://.../master.m3u8?..."],
 *   "default_subs":[{"code":"en","lang":"English","url":"https://.../xxx_eng.vtt"}]}}
 * ```
 */
object VimeosBridge {
    private val RESPONSE_REGEX = Regex("""window\.VIDAPI_CF_STREAM_RESPONSE\s*=\s*(\{[\s\S]*?\});""")

    /** Playlist HLS y subtitulos de un reproductor vimeos con token. */
    data class Media(
        val m3u8: String,
        /** Lista de subtitulos: idioma -> url. */
        val subtitles: List<Pair<String, String>>,
    )

    /**
     * @param embedUrl url del reproductor (con el token `cf` incluido).
     * @param referer pagina desde la que se abre el reproductor.
     */
    suspend fun resolve(embedUrl: String, referer: String?): Media? {
        val html = app.get(
            embedUrl,
            headers = referer?.let { mapOf("Referer" to it) } ?: emptyMap(),
        ).text
        val json = RESPONSE_REGEX.find(html)?.groupValues?.get(1) ?: return null
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val data = root.optJSONObject("data") ?: return null

        val streams = data.optJSONArray("stream_urls")
        val m3u8 = streams?.optString(0)?.takeIf { it.isNotBlank() } ?: return null

        val subtitles = mutableListOf<Pair<String, String>>()
        val subs = data.optJSONArray("default_subs")
        if (subs != null) {
            for (index in 0 until subs.length()) {
                val sub = subs.optJSONObject(index) ?: continue
                val url = sub.optString("url")
                if (url.isBlank()) continue
                val label = sub.optString("lang").ifBlank { sub.optString("code") }
                subtitles += label to url
            }
        }
        return Media(m3u8, subtitles)
    }
}
