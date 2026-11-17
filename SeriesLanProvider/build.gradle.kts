version = 4

android {
    namespace = "com.anonimo00.serieslan"
    // Codigo comun a todos los proveedores (reproductor EMBED69, vimeos, Anubis...).
    sourceSets["main"].java.srcDir("../shared/src/main/kotlin")
}

cloudstream {
    description = "Series y animes en espanol latino desde serieslan.live"
    authors = listOf("Anonimo-00")
    status = 1
    tvTypes = listOf(
        "TvSeries", "Anime",
    )
    language = "es"
    iconUrl = "https://serieslan.live/favicon.ico"
}

