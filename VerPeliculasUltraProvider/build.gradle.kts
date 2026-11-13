version = 1

android {
    namespace = "com.anonimo00.verpeliculasultra"
    // Codigo comun a todos los proveedores (reproductor EMBED69, vimeos, Anubis...).
    sourceSets["main"].java.srcDir("../shared/src/main/kotlin")
}

cloudstream {
    description = "Peliculas en espanol latino desde verpeliculasultra.com"
    authors = listOf("Anonimo-00")
    status = 1
    tvTypes = listOf(
        "Movie",
    )
    language = "es"
    iconUrl = "https://verpeliculasultra.com/favicon.ico"
}
