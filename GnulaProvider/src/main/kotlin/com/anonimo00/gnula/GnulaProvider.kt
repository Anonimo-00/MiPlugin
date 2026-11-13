package com.anonimo00.gnula

import com.anonimo00.shared.FastApiProvider
import com.lagradost.cloudstream3.TvType

/**
 * Proveedor de gnula.la (peliculas, series y animes en espanol latino).
 *
 * Las variantes `gnula.cc` y `wnv5.gnula.cc` estan detrás del reto gestionado
 * de Cloudflare, asi que no se pueden usar. `gnula.la` sirve el mismo sitio
 * (SPA React sobre WordPress) y su API responde sin reto.
 *
 * La API es la de WordPress FastAPI (`/wp-api/v1`), y los reproductores son
 * paginas de vimeos con la playlist cifrada con P.A.C.K.E.R.
 */
class GnulaProvider : FastApiProvider() {
    override var name = "Gnula"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
    )

    override val mainPage = com.lagradost.cloudstream3.mainPageOf(
        "movies" to "Peliculas",
        "tvshows" to "Series",
        "animes" to "Animes",
    )

    override val siteUrl = "https://gnula.la"
    override val apiBase = "https://gnula.la/wp-api/v1"
    override val imageBase = "https://gnula.la/wp-content/uploads"

    override fun typeOf(postType: String): TvType = when (postType) {
        "movies" -> TvType.Movie
        "animes" -> TvType.Anime
        else -> TvType.TvSeries
    }

    override fun urlOf(postType: String, slug: String): String = when (postType) {
        "movies" -> "$siteUrl/peliculas/$slug/"
        "tvshows" -> "$siteUrl/series/$slug/"
        "animes" -> "$siteUrl/animes/$slug/"
        "episodes" -> "$siteUrl/episodio/$slug/"
        else -> "$siteUrl/peliculas/$slug/"
    }

    override fun postTypeOf(url: String): String = when {
        url.contains("/series/") -> "tvshows"
        url.contains("/animes/") -> "animes"
        url.contains("/episodio/") -> "episodes"
        else -> "movies"
    }
}
