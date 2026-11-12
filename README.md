# MiPlugin

Repositorio de plugins para Cloudstream. Incluye `ExampleProvider` como punto
de partida; es un ejemplo y no ofrece contenido real hasta que configures una
fuente en `ExampleProvider/src/main/kotlin/com/example/ExampleProvider.kt`.

## Importar en Cloudstream

1. Espera a que termine correctamente la acción **Build** en GitHub. Esta acción
   publica los archivos del plugin en la rama `builds`.
2. En Cloudstream, agrega este repositorio:

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
