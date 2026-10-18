# Registros de cambios

Un archivo por publicación, y **el nombre del archivo es el `versionCode`
entero**, no el `versionName` legible. F-Droid asocia cada registro de cambios a
una compilación por el código de versión, así que un archivo nombrado con el
nombre de versión es un archivo que F-Droid no muestra jamás.

El esquema CalVer de `.github/workflows/android-ci.yaml` produce:

    versionName = AAAA.MM.DD.<número de ejecución de CI>
    versionCode = aaMMDD * 100 + <publicación número N de ese día UTC>

donde `aaMMDD` cuenta el año desde 2000, de modo que el 10-09-2026 es `260910`.

**Las dos terminaciones son números distintos a propósito.** El nombre lleva el
número de ejecución de CI porque una reejecución tiene que caer en la misma
versión que nombró el primer intento, y ese número es el único valor que
sobrevive intacto a una reejecución. El código no puede usarlo: ese número
cuenta también las ejecuciones de las pull requests, así que un múltiplo de 100
que caiga entre dos fusiones del mismo día haría retroceder el código, y Android
se niega a instalar una compilación cuyo código sea menor que el ya instalado.
Por eso los dos últimos dígitos del código cuentan publicaciones, arrastrados
desde el `VERSION_CODE` que ya está en `gradle.properties`.

La primera publicación del 10-09-2026 es entonces `2026.09.10.<ejecución>`, su
código de versión es `26091001` y su registro de cambios es `26091001.txt`. La
segunda de ese día acaba en `...02`, sea cual sea el número de ejecución.

Todavía no se ha publicado ninguna versión, y por eso este directorio solo
contiene este archivo. El primer `<versionCode>.txt` se añade cuando la primera
etiqueta tenga adjunto un APK firmado.

Escribe entradas breves: F-Droid recorta los registros largos en el listado, y
el CHANGELOG de la raíz del repositorio es el registro completo.
