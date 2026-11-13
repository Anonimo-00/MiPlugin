version = 1

android {
    namespace = "com.anonimo00.cinecalidad"
    // Codigo comun a todos los proveedores (reproductor EMBED69, vimeos, Anubis...).
    sourceSets["main"].java.srcDir("../shared/src/main/kotlin")
}

cloudstream {
    description = "Peliculas, series y animes en espanol latino desde cinecalidad.org"
    authors = listOf("Anonimo-00")
    status = 1
    tvTypes = listOf(
        "Movie", "TvSeries", "Anime",
    )
    language = "es"
    iconUrl = "https://cinecalidad.org/favicon.ico"
}
