# Proveedor Latinos

Extensiones de [CloudStream](https://github.com/recloudstream/cloudstream) para
catálogos de streaming en español latino:

| Extensión | Sitio | Contenido | Idioma |
|---|---|---|---|
| `SoloLatinoProvider` | [sololatino.net](https://sololatino.net) | Películas, series, animes y doramas | es |
| `FlixLatamProvider` | [flixlatam.com](https://flixlatam.com) | Películas, series y animes | es |

> **Nota:** este repositorio se ha desarrollado **con ayuda de IA** (asistente
> de programación). El código se ha ido verificando contra los sitios reales
> con `tools/verify.mjs`, pero los reproductores de streaming cambian con
> frecuencia, así que es posible que algún proveedor deje de funcionar sin
> aviso. Si notas que uno falla, actualiza el `version` del módulo y avisa.

La estructura sigue la [guía oficial de desarrollo de extensiones](https://recloudstream.github.io/csdocs/devs/gettingstarted/):
un módulo Gradle por proveedor, un `repo.json` con el manifiesto del
repositorio y un workflow que publica los `.cs3` en la rama `builds`.

## Estructura

```
.
├── build.gradle.kts              # Configuración raíz (plugin de CloudStream + repos)
├── settings.gradle.kts           # Incluye automáticamente cada módulo
├── gradle.properties
├── repo.json                     # Manifiesto del repositorio (URL para pegar en la app)
├── gradlew / gradlew.bat
├── gradle/
├── .github/workflows/build.yml   # CI: compila y publica en la rama `builds`
├── tools/verify.mjs              # Verificación en vivo contra los sitios reales
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

## Importar en CloudStream

> **Ojo:** en CloudStream → *Ajustes → Extensiones → Añadir repositorio* hay que
> pegar **la URL del manifiesto `repo.json`**, no la de `plugins.json`. La app
> interpreta lo que pegas como un `Repository` (con `name`, `manifestVersion`
> y `pluginLists`), así que si pegas el `plugins.json` da error de
> "repositorio no encontrado".

Después de que el workflow **Build** termine (publica los `.cs3` y el
`plugins.json` en la rama `builds`), pega esta URL:

```
https://raw.githubusercontent.com/Anonimo-00/MiPlugin/builds/repo.json
```

También funciona la de la rama principal:

```
https://raw.githubusercontent.com/Anonimo-00/MiPlugin/master/repo.json
```

Una vez añadido el repositorio, en la lista de extensiones aparecerán
`SoloLatinoProvider` y `FlixLatamProvider` para instalar.

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

## Verificar los enlaces

`tools/verify.mjs` reproduce la lógica de los dos proveedores contra los sitios
reales y comprueba que los enlaces resueltos devuelven una playlist HLS
válida:

```bash
node tools/verify.mjs            # ambos
node tools/verify.mjs soloLatino
node tools/verify.mjs flixlatam
```

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

## Sitios pendientes de añadir

Estos son los siguientes sitios que hay que revisar y añadir como proveedores.
Para cada uno el flujo es el mismo: primero comprobar **que se puede extraer
el enlace de vídeo** (es la parte protegida y la que hace útil al
proveedor), y después el catálogo, la búsqueda y la ficha.

| # | Sitio | Estado |
|---|---|---|
| 1 | <https://pelisplushd.mx/> | Pendiente de investigar |
| 2 | <https://verpeliculasultra.com/> | Pendiente de investigar |
| 3 | <https://www.flixcorn.net/> | Pendiente de investigar |
| 4 | <https://wnv5.gnula.cc/> | Pendiente de investigar |
| 5 | <https://ver.gnula.one/> | Pendiente de investigar |
| 6 | <https://www.hackstore.fo/> | Pendiente de investigar |
| 7 | <https://hackstore2.com/> | Pendiente de investigar |
| 8 | <https://cinecalidad.org/> | Pendiente de investigar |
| 9 | <https://ww4.animeonline.ninja/inicio/> | Pendiente de investigar |
| 10 | <https://www.cinemultimediaflix.com/> | Pendiente de investigar |
| 11 | <https://latanime.org/> | Pendiente de investigar |
| 12 | <https://animejara.com/catalogo> | Pendiente de investigar |
| 13 | <https://serieslan.live/> | Pendiente de investigar |

Lista plana para trabajar:

```
https://pelisplushd.mx/
https://verpeliculasultra.com/
https://www.flixcorn.net/
https://wnv5.gnula.cc/
https://ver.gnula.one/
https://www.hackstore.fo/
https://hackstore2.com/
https://cinecalidad.org/
https://ww4.animeonline.ninja/inicio/
https://www.cinemultimediaflix.com/
https://latanime.org/
https://animejara.com/catalogo
https://serieslan.live/
```

## Nota

Cada vez que cambies el código, sube `version = N` en el `build.gradle.kts` del
módulo para que la app detecte la actualización.
