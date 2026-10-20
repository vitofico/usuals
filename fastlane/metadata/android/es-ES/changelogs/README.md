# Registros de cambios

Añade un archivo UTF-8 breve por publicación en cada idioma de la tienda. El
nombre es el `versionCode` entero de Android, por ejemplo `26102001.txt`, no el
nombre de versión legible.

Confirma `VERSION_NAME` y un `VERSION_CODE` estrictamente creciente en
`gradle.properties` antes de crear la etiqueta. El nombre usa `AAAA.MM.DD.N`,
donde N cuenta las publicaciones del día. El código usa `aaMMdd * 100 + N`, con
N de 1 a 99. Usa la etiqueta inmutable correspondiente, como `v2026.10.20.1`.
CI lee estos valores; no los genera ni los incrementa.

Mantén cada entrada por debajo de 500 caracteres. `CHANGELOG.md` contiene el
registro completo. Estos archivos preparan metadatos; no envían nada a F-Droid.
