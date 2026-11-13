version = 1

android {
    namespace = "com.anonimo00.pelisplushd"
    // Codigo comun a todos los proveedores (reproductor EMBED69, Anubis...).
    sourceSets["main"].java.srcDir("../shared/src/main/kotlin")
}

cloudstream {
    description = "Peliculas, series y animes en espanol latino desde pelisplushd.la"
    authors = listOf("Anonimo-00")
    status = 1
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime",
    )
    language = "es"
    iconUrl = "https://pelisplushd.la/static/images/logo/apple-touch-icon.png"
}
