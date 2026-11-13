version = 2

android {
    namespace = "com.anonimo00.sololatino"
    // Codigo comun a todos los proveedores (reproductor EMBED69, Anubis...).
    sourceSets["main"].java.srcDir("../shared/src/main/kotlin")
}

cloudstream {
    description = "Peliculas, series, animes y doramas en espanol latino desde sololatino.net"
    authors = listOf("Anonimo-00")
    status = 1
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime",
        "Cartoon",
    )
    language = "es"
    iconUrl = "https://sololatino.net/wp-content/uploads/2020/10/cropped-logo-final-192x192.png"
}
