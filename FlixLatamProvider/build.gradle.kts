version = 2

android {
    namespace = "com.anonimo00.flixlatam"
    // Codigo comun a todos los proveedores (reproductor EMBED69, Anubis...).
    sourceSets["main"].java.srcDir("../shared/src/main/kotlin")
}

cloudstream {
    description = "Peliculas, series y animes en espanol latino desde flixlatam.com"
    authors = listOf("Anonimo-00")
    status = 1
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime",
    )
    language = "es"
    iconUrl = "https://flixlatam.com/themes/dooplay/assets/img/favicon.png"
}
