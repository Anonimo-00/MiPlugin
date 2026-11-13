version = 1

android {
    namespace = "com.anonimo00.gnulaone"
    // Codigo comun a todos los proveedores (reproductor EMBED69, vimeos, Anubis...).
    sourceSets["main"].java.srcDir("../shared/src/main/kotlin")
}

cloudstream {
    description = "Peliculas en espanol latino desde ver.gnula.one"
    authors = listOf("Anonimo-00")
    status = 1
    tvTypes = listOf(
        "Movie",
    )
    language = "es"
    iconUrl = "https://ver.gnula.one/favicon.ico"
}
