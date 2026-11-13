version = 1

android {
    namespace = "com.anonimo00.flixcorn"
    // Codigo comun a todos los proveedores (reproductor EMBED69, vimeos, Anubis...).
    sourceSets["main"].java.srcDir("../shared/src/main/kotlin")
}

cloudstream {
    description = "Series y animes en espanol latino desde flixcorn.net"
    authors = listOf("Anonimo-00")
    status = 1
    tvTypes = listOf(
        "TvSeries", "Anime",
    )
    language = "es"
    iconUrl = "https://www.flixcorn.net/favicon.ico"
}
