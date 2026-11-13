version = 1

android {
    namespace = "com.anonimo00.animeonline"
    // Codigo comun a todos los proveedores (reproductor EMBED69, vimeos, Anubis...).
    sourceSets["main"].java.srcDir("../shared/src/main/kotlin")
}

cloudstream {
    description = "Animes en espanol latino desde animeonline.one"
    authors = listOf("Anonimo-00")
    status = 1
    tvTypes = listOf(
        "Anime",
    )
    language = "es"
    iconUrl = "https://animeonline.one/wp-content/uploads/2026/10/psyren.jpg"
}
