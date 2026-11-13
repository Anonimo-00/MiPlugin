version = 1

android {
    namespace = "com.anonimo00.gnula"
    // Codigo comun a todos los proveedores (reproductor EMBED69, vimeos, Anubis...).
    sourceSets["main"].java.srcDir("../shared/src/main/kotlin")
}

cloudstream {
    description = "Peliculas, series y animes en espanol latino desde gnula.la"
    authors = listOf("Anonimo-00")
    status = 1
    tvTypes = listOf(
        "Movie", "TvSeries", "Anime",
    )
    language = "es"
    iconUrl = "https://gnula.la/wp-content/uploads/2022/03/bkkS61w94ZVMNVd8KEyyJl2tnY5-193x288.jpg"
}
