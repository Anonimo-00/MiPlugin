package com.anonimo00.cinecalidad

import com.anonimo00.shared.FastApiProvider
import com.lagradost.cloudstream3.TvType

/**
 * Proveedor de cinecalidad.org (peliculas, series y animes).
 *
 * El sitio es una SPA React: el HTML no lleva el catalogo, todo se pide a su
 * API de WordPress FastAPI (`/wp-api/v1`). Los reproductores son paginas de
 * vimeos con la playlist cifrada con P.A.C.K.E.R.
 */
class CinecalidadProvider : FastApiProvider() {
    override var name = "Cinecalidad"
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

    override val siteUrl = "https://cinecalidad.org"
    override val apiBase = "https://cinecalidad.org/wp-api/v1"
    override val imageBase = "https://cinecalidad.org/wp-content/uploads"

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
