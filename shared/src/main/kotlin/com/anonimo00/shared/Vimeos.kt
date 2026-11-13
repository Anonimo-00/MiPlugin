package com.anonimo00.shared

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.getAndUnpack

/**
 * Reproductor vimeos.net (`https://vimeos.net/embed-<id>.html`).
 *
 * La pagina viene con el JavaScript empaquetado con P.A.C.K.E.R. Al
 * desempaquetarlo aparecen las fuentes:
 *
 * ```
 * sources:[{file:"https://.../master.m3u8?..."}]
 * tracks:[{file:"..._spa.vtt",label:"Spanish",kind:"captions"}, ...]
 * ```
 *
 * CloudStream ya trae el desempaquetador ([getAndUnpack]), asi que aqui solo
 * se extraen la playlist y los subtitulos.
 */
object Vimeos {
    private val FILE_REGEX = Regex("""file\s*:\s*"(https?://[^"]+)"""")
    private val TRACK_REGEX = Regex(
        """\{\s*file\s*:\s*"(https?://[^"]+\.(?:vtt|srt))"\s*,\s*label\s*:\s*"([^"]+)""""
    )

    /** Playlist HLS y subtitulos de un reproductor vimeos. */
    data class Media(
        val m3u8: String,
        /** Lista de subtitulos: idioma -> url. */
        val subtitles: List<Pair<String, String>>,
    )

    /**
     * Resuelve un reproductor vimeos.
     *
     * @param embedUrl url del reproductor (`https://vimeos.net/embed-<id>.html`).
     * @param referer pagina desde la que se abre el reproductor.
     */
    suspend fun resolve(embedUrl: String, referer: String?): Media? {
        val html = app.get(
            embedUrl,
            headers = referer?.let { mapOf("Referer" to it) } ?: emptyMap(),
        ).text
        val unpacked = getAndUnpack(html)

        val files = FILE_REGEX.findAll(unpacked).map { it.groupValues[1] }.toList()
        val m3u8 = files.firstOrNull { it.contains(".m3u8") } ?: return null

        val subtitles = TRACK_REGEX.findAll(unpacked)
            .map { it.groupValues[2] to it.groupValues[1] }
            .filter { (_, url) -> !url.contains("empty") }
            .toList()

        return Media(m3u8, subtitles)
    }
}
