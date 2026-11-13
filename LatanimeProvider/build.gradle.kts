version = 1

android {
    namespace = "com.anonimo00.latanime"
    // Codigo comun a todos los proveedores (reproductor EMBED69, vimeos, Anubis...).
    sourceSets["main"].java.srcDir("../shared/src/main/kotlin")
}

cloudstream {
    description = "Animes en espanol latino desde latanime.org"
    authors = listOf("Anonimo-00")
    status = 1
    tvTypes = listOf(
        "Anime",
    )
    language = "es"
    iconUrl = "https://latanime.org/favicon.ico"
}
