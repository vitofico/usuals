# Changelogs

Add one brief UTF-8 file per release in every store locale. Its filename is the
integer Android `versionCode`, for example `26102001.txt`, rather than the
human-readable version name.

Commit `VERSION_NAME` and a strictly increasing `VERSION_CODE` in
`gradle.properties` before tagging. The name uses `YYYY.MM.DD.N`, where N counts
releases that day. The code uses `yyMMdd * 100 + N`, with N from 1 to 99.
Use the matching immutable tag, such as `v2026.10.20.1`. CI reads these values;
it does not generate or increment them.

Keep each entry under 500 characters. The repository's `CHANGELOG.md` holds the
full record. Changelogs prepare store metadata; they do not submit to F-Droid.
