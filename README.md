# Proveedor Latinos

Extensiones de [CloudStream](https://github.com/recloudstream/cloudstream) para
catalagos de streaming en español latino:

| Extensión | Sitio | Contenido | Idioma |
|---|---|---|---|
| `SoloLatinoProvider` | [sololatino.net](https://sololatino.net) | Películas, series, animes y doramas | es |
| `FlixLatamProvider` | [flixlatam.com](https://flixlatam.com) | Películas, series y animes | es |

La estructura sigue la [guía oficial de desarrollo de extensiones](https://recloudstream.github.io/csdocs/devs/gettingstarted/):
un módulo Gradle por proveedor, un `repo.json` con el manifiesto del
repositorio y un workflow que publica los `.cs3` en la rama `builds`.

## Estructura

```
.
├── build.gradle.kts              # Configuración raíz (plugin de CloudStream + repos)
├── settings.gradle.kts           # Incluye automáticamente cada módulo
├── gradle.properties
├── repo.json                     # Manifiesto del repositorio para la app
├── gradlew / gradlew.bat
├── gradle/
├── .github/workflows/build.yml   # CI: compila y publica en la rama `builds`
├── SoloLatinoProvider/
│   ├── build.gradle.kts          # Metadatos del proveedor (versión, status, tvTypes)
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── kotlin/com/anonimo00/sololatino/
│           ├── SoloLatinoPlugin.kt    # Registro del proveedor en CloudStream
│           ├── SoloLatinoProvider.kt  # Búsqueda, portada, ficha y enlaces
│           ├── SoloLatinoPlayer.kt    # Resolución de enlaces (API + reproductor)
│           └── SoloLatinoShield.kt    # SHA-256 del escudo y FNV-1a del sitio
└── FlixLatamProvider/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        └── kotlin/com/anonimo00/flixlatam/
            ├── FlixLatamPlugin.kt
            ├── FlixLatamProvider.kt
            └── FlixLatamPlayer.kt      # Descifrado AES del reproductor Embed69
```

## Cómo se extraen los enlaces

### SoloLatino

El sitio usa un backend Laravel/Sanctum, así que el catálogo se lee del HTML
público pero los enlaces de reproducción salen de su API:

1. `GET /sanctum/csrf-cookie` abre sesión y devuelve la cookie `XSRF-TOKEN`.
2. `POST /api/player-url` con el token del botón de servidor. La petición va en
   JSON y necesita la cabecera `X-SL-Render`, que el sitio calcula con
   `sha256(token + ":" + minuto + ":sololatino_render_shield_2026")` truncado a
   16 caracteres.
3. El reproductor real es `player.pelisserieshoy.com`. Su página lleva el token
   de sesión (`_t`), el identificador del contenido (`_qc`) y un token firmado
   (`rt`) repartido en cuatro trozos que se recomponen en otro orden. La firma
   `tk` es un FNV-1a de 32 bits.
4. `POST /s.php` con `a=1` devuelve los servidores, `a=click` valida la
   reproducción y `a=2` resuelve cada servidor a una URL HLS firmada (`sig`).
5. El HLS se reproduce a través del proxy del propio sitio
   (`/p.php?url=...&sig=...`): con un `Referer` de sololatino.net responde 403.

Los reproductores alternativos (`embed69.org`) están protegidos con Anubis
(proof of work) y se descartan; el reproductor principal ya da servidores
gratuitos.

### FlixLatam

El sitio usa el tema Dooplay, cuyo reproductor (`/vidurl/<id>/`) es una página
de EMBED69 con los servidores embebidos en el HTML:

1. `dataLink`: array JSON en claro con los servidores por idioma.
2. `LINK_CHALLENGE` + `LINK_SALT` → `SHA-256(challenge + salt)` = clave AES.
3. Cada enlace va cifrado en AES-256-CBC; los 16 primeros bytes del base64 son
   el IV.
4. Los enlaces resultantes (Voe, Streamwish, Vidhide…) se pasan al extractor
   que CloudStream ya trae, normalizando beforehand los dominios rotados.

## Importar en CloudStream

El workflow **Build** compila las extensiones y publica `plugins.json` y los
`.cs3` en la rama `builds`. Cuando termine, agrega este repositorio en la app:

`https://raw.githubusercontent.com/Anonimo-00/MiPlugin/builds/plugins.json`

## Compilar localmente

Requiere JDK 17 y el SDK de Android (compila con `compileSdk 35`).

En Windows:

```powershell
.\gradlew.bat make makePluginsJson
```

En Linux o macOS:

```sh
./gradlew make makePluginsJson
```

La salida queda en `SoloLatinoProvider/build/SoloLatinoProvider.cs3`,
`FlixLatamProvider/build/FlixLatamProvider.cs3` y `build/plugins.json`.

## Nota

Cada vez que cambies el código, sube `version = N` en el `build.gradle.kts` del
módulo para que la app detecte la actualización.
