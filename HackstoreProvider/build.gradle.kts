version = 1

android {
    namespace = "com.anonimo00.hackstore"
    // Codigo comun a todos los proveedores (reproductor EMBED69, vimeos, Anubis...).
    sourceSets["main"].java.srcDir("../shared/src/main/kotlin")
}

cloudstream {
    description = "Peliculas, series y animes en espanol latino desde hackstore.fo"
    authors = listOf("Anonimo-00")
    status = 1
    tvTypes = listOf(
        "Movie", "TvSeries", "Anime",
    )
    language = "es"
    iconUrl = "https://www.hackstore.fo/favicon.ico"
}
