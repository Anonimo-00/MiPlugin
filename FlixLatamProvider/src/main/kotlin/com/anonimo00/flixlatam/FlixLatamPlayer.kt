package com.anonimo00.flixlatam

import com.anonimo00.shared.Embed69
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element

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
 * pagina de EMBED69 con la lista de servidores embebida en el HTML: los
 * enlaces van cifrados con AES-256-CBC y la clave se deriva de
 * `LINK_CHALLENGE` + `LINK_SALT`. Todo eso lo resuelve [Embed69], que ademas
 * sabe pasar el reto de Anubis por si el sitio lo activa.
 *
 * Los enlaces resultantes apuntan a reproductores externos (Voe, Streamwish,
 * Vidhide...) que CloudStream ya sabe resolver, asi que se les pasa al
 * extractor que corresponda.
 */
internal object FlixLatamPlayer {
    private const val BASE = "https://flixlatam.com"

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
        return Embed69.resolve(playerUrl, pageUrl).map {
            FlixLatamLink(it.name, it.url, it.referer)
        }
    }
}
