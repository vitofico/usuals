# Registros de cambios

Cada idioma de la tienda necesita un archivo UTF-8 `template.txt` de 1 a 500
caracteres. Cuando main supera CI, la publicación copia cada plantilla al nuevo
nombre entero `versionCode` de Android, por ejemplo `26102002.txt`, y confirma
estos archivos junto con las propiedades de versión. Los registros publicados
son inmutables.

Actualiza las plantillas con las novedades traducidas. `CHANGELOG.md` y las notas
de GitHub contienen el registro completo. CI elige el nombre `AAAA.MM.DD.N` en
UTC y un código Android estrictamente creciente. Consulta la
[guía de distribución](../../../../../docs/distribution.md) para numeración,
verificación y recuperación. Estos archivos no envían nada a F-Droid.
