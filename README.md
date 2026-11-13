# Proveedor Latinos

Extensiones de [CloudStream](https://github.com/recloudstream/cloudstream) para
catálogos de streaming en español latino:

| Extensión | Sitio | Contenido | Tipo |
|---|---|---|---|
| `SoloLatinoProvider` | [sololatino.net](https://sololatino.net) | Películas, series, animes y doramas | es |
| `FlixLatamProvider` | [flixlatam.com](https://flixlatam.com) | Películas, series y animes | es |
| `PelisPlusHDProvider` | [pelisplushd.la](https://pelisplushd.la) | Películas, series y animes | es |
| `GnulaProvider` | [gnula.la](https://gnula.la) | Películas, series y animes | es |
| `VerGnulaProvider` | [ver.gnula.one](https://ver.gnula.one) | Películas | es |
| `CinecalidadProvider` | [cinecalidad.org](https://cinecalidad.org) | Películas, series y animes | es |
| `VerPeliculasUltraProvider` | [verpeliculasultra.com](https://verpeliculasultra.com) | Películas | es |
| `FlixCornProvider` | [flixcorn.net](https://www.flixcorn.net) | Series y animes | es |
| `AnimeOnlineProvider` | [animeonline.one](https://animeonline.one) | Animes | es |
| `LatanimeProvider` | [latanime.org](https://latanime.org) | Animes | es |
| `HackstoreProvider` | [hackstore.fo](https://www.hackstore.fo) | Películas (las series no tienen servidores) | es |

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
├── shared/                       # Código común a todos los proveedores
│   └── src/main/kotlin/com/anonimo00/shared/
│       ├── Embed69.kt            # Reproductor EMBED69 + solver de Anubis
│       ├── Vimeos.kt             # Reproductor vimeos (P.A.C.K.E.R)
│       └── FastApiProvider.kt    # Base para las APIs de WordPress FastAPI
├── SoloLatinoProvider/           # sololatino.net
├── FlixLatamProvider/            # flixlatam.com
├── PelisPlusHDProvider/          # pelisplushd.la
├── GnulaProvider/                # gnula.la
├── VerGnulaProvider/             # ver.gnula.one
├── CinecalidadProvider/          # cinecalidad.org
├── VerPeliculasUltraProvider/    # verpeliculasultra.com
├── FlixCornProvider/             # flixcorn.net
├── AnimeOnlineProvider/          # animeonline.one
├── LatanimeProvider/             # latanime.org
└── HackstoreProvider/            # hackstore.fo
```

El código de `shared/` se incrusta en cada `.cs3` mediante
`sourceSets["main"].java.srcDir("../shared/src/main/kotlin")`, así que los
próximos proveedores reutilizarán los reproductores sin duplicar código.

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

Una vez añadido el repositorio, en la lista de extensiones aparecerán los once
proveedores para instalar.

## Cuántas fuentes da cada sitio

Los sitios ofrecen **varias fuentes por título**, no una sola:

| Sitio | Fuentes por título | De dónde salen |
|---|---|---|
| sololatino.net | 2 HLS + 6 embebidas | `player.pelisserieshoy.com` (LAT y SUB) + `embed69.org` (LAT/SUB × 3 servidores) |
| flixlatam.com | 6 embebidas | `dataLink` del reproductor EMBED69 (LAT/SUB × streamwish, voe, vidhide) |
| pelisplushd.la | 3 embebidas | `li[data-url]` / `#link_url span[url]` (streamwish, vidhide, vidara) |
| gnula.la | 1 por reproductor | `/wp-api/v1/player` → vimeos (P.A.C.K.E.R) |
| cinecalidad.org | 2 por reproductor | `/wp-api/v1/player` → voe + vimeos |
| ver.gnula.one | 3 embebidas | `a.gn-srv` → página intermedia → iframe (vidara, streamwish, vidhide) |
| verpeliculasultra.com | 4 embebidas | `div.play-btn-cont[data-src]` (4 idiomas) → hgcloud.to (rotador) |
| flixcorn.net | 1 por servidor | `/external/<id>` (JSON) → vidaraa.cc → `/api/stream` |
| animeonline.one | 3 embebidas | `a.btn[data-hash]` (base64, sin cifrar) |
| latanime.org | 7 embebidas | `a.play-video[data-player]` (base64, sin cifrar) |
| hackstore.fo | 1 por reproductor | `a.playr[data-href]` → `play.php` → vimeos |

## Protocolos que se resuelven

### API de WordPress FastAPI (`gnula.la` y `cinecalidad.org`)

`/wp-api/v1/listing/{tipo}`, `/search`, `/single/{tipo}`, `/single/episodes/list`
y `/player`. Ojo: el tramo `/listing/{x}` no filtra nada, el filtro real es el
parámetro `postType`, así que hay que enviar los dos.

### Reproductor EMBED69 (`embed69.org`)

`dataLink` en claro con los servidores por idioma; cada enlace va cifrado con
AES-256-CBC y la clave es `SHA-256(LINK_CHALLENGE + LINK_SALT)`. Además
`embed69.org` está protegido por **Anubis** (proof of work): `shared/Embed69.kt`
resuelve el reto (SHA-256 con los primeros bits a cero), saca las cookies que
dan acceso y descifra los enlaces.

### Reproductor vimeos (`vimeos.net`)

La playlist va empaquetada con el packer de Dean Edwards en base 36. Se
desempaqueta con el `getAndUnpack` de CloudStream y se extraen el m3u8 y los
subtítulos (`.vtt`).

### Rotador de dominio (`hgcloud.to`)

Su `main.js` salta a uno de los dominios principales (`audinifer.com`,
`hanerix.com`, `vibuxer.com`); en el dominio bueno ya viene la playlist
empaquetada (`links.hls4`).

## Verificar los enlaces

`tools/verify.mjs` reproduce la lógica de los proveedores contra los sitios
reales y comprueba que los enlaces resueltos devuelven una playlist HLS
válida:

```bash
node tools/verify.mjs            # todos
node tools/verify.mjs soloLatino
node tools/verify.mjs gnula
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

La salida queda en `<Módulo>/build/<Módulo>.cs3` y `build/plugins.json`.

## Sitios pendientes de añadir

Estos son los siguientes sitios que hay que revisar y añadir como proveedores.
Para cada uno el flujo es el mismo: primero comprobar **que se puede extraer
el enlace de vídeo** (es la parte protegida y la que hace útil al
proveedor), y después el catálogo, la búsqueda y la ficha.

| # | Sitio | Estado |
|---|---|---|
| 1 | <https://pelisplushd.mx/> | Hecho (el sitio real es `pelisplushd.la`) |
| 2 | <https://verpeliculasultra.com/> | Hecho |
| 3 | <https://www.flixcorn.net/> | Hecho |
| 4 | <https://wnv5.gnula.cc/> | Hecho (el sitio real es `gnula.la`) |
| 5 | <https://ver.gnula.one/> | Hecho |
| 6 | <https://www.hackstore.fo/> | Hecho |
| 7 | <https://hackstore2.com/> | Pendiente de investigar |
| 8 | <https://cinecalidad.org/> | Hecho |
| 9 | <https://ww4.animeonline.ninja/inicio/> | Hecho (el sitio real es `animeonline.one`) |
| 10 | <https://www.cinemultimediaflix.com/> | Pendiente de investigar |
| 11 | <https://latanime.org/> | Hecho |
| 12 | <https://animejara.com/catalogo> | Descartado: solo se abre con navegador (reto gestionado de Cloudflare) |
| 13 | <https://serieslan.live/> | Pendiente de investigar |
| 14 | <https://hdfull.to/> | Pendiente de investigar |

Lista plana para trabajar:

```
https://hackstore2.com/
https://www.cinemultimediaflix.com/
https://serieslan.live/
https://hdfull.to/
```

## Nota

Cada vez que cambies el código, sube `version = N` en el `build.gradle.kts` del
módulo para que la app detecte la actualización.
