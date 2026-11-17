version = 7

android {
    namespace = "com.anonimo00.hdfull"
    // Codigo comun a todos los proveedores (reproductor EMBED69, vimeos, Anubis...).
    sourceSets["main"].java.srcDir("../shared/src/main/kotlin")
}

cloudstream {
    description = "Peliculas y series en espanol latino desde hdfull.org (necesita cuenta)"
    authors = listOf("Anonimo-00")
    status = 1
    tvTypes = listOf(
        "Movie",
        "TvSeries",
    )
    language = "es"
    iconUrl = "https://hdfullcdn.cc/favicon.ico"
}



