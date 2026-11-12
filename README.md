# MiPlugin

Esqueleto de una extensión de Cloudstream en español. La búsqueda y la carga
de enlaces todavía no tienen lógica, así que el proveedor no mostrará contenido
hasta que se conecte una fuente autorizada.

## Archivos principales

- `MiPlugin/src/main/kotlin/com/anonimo00/miplugin/MiPlugin.kt`: registra el
  proveedor en Cloudstream.
- `MiPlugin/src/main/kotlin/com/anonimo00/miplugin/MiProvider.kt`: punto de
  entrada para implementar búsqueda, carga de fichas y resolución de enlaces.
- `MiPlugin/build.gradle.kts`: nombre, idioma, versión y estado del plugin.

## Importar en Cloudstream

El workflow **Build** compila el plugin y publica `plugins.json` y el archivo
`.cs3` en la rama `builds`. Cuando la compilación termine correctamente, agrega
este repositorio en Cloudstream:

`https://raw.githubusercontent.com/Anonimo-00/MiPlugin/builds/plugins.json`

## Compilar localmente

En Windows:

```powershell
.\gradlew.bat make makePluginsJson
```

En Linux o macOS:

```sh
./gradlew make makePluginsJson
```
