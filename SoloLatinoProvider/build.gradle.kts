version = 7

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
        "AsianDrama",
    )
    language = "es"
    iconUrl = "https://sololatino.net/images/logo.png"
}



