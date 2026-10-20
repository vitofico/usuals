# Changelogs

Each store locale needs a UTF-8 `template.txt` with 1 to 500 characters. After
main passes CI, the release job copies each template to its new integer Android
`versionCode` filename, for example `26102002.txt`, and commits these files with
the version properties. Published changelogs stay immutable.

Update templates for localized release highlights. `CHANGELOG.md` and GitHub's
generated release notes hold the full record. CI chooses the UTC `YYYY.MM.DD.N`
version name and a strictly increasing Android code. See the
[distribution guide](../../../../../docs/distribution.md) for numbering,
verification and recovery. These files do not submit anything to F-Droid.
