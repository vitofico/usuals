# Changelogs

One file per release, and **the filename is the integer `versionCode`**, not the
human-readable `versionName`. F-Droid maps a changelog to a build by version
code, so a file named after the version name is a file F-Droid never shows.

The CalVer scheme in `.github/workflows/android-ci.yaml` produces:

    versionName = YYYY.MM.DD.<CI run number>
    versionCode = yyMMdd * 100 + <nth release cut on that UTC day>

where `yyMMdd` counts the year from 2000, so 2026-09-10 is `260910`.

**The two tails are different numbers on purpose.** The name carries the CI run
number because a re-run has to land on the version its first attempt named, and
the run number is the only value that survives a re-run unchanged. The code
cannot use it: the run number counts pull-request runs too, so a multiple of 100
falling between two merges on the same day would send the code backwards, and
Android refuses to install a build whose code is lower than the installed one.
So the low two digits of the code count releases instead, carried forward from
the `VERSION_CODE` already in `gradle.properties`.

The first release cut on 2026-09-10 is therefore `2026.09.10.<run>` with version
code `26091001`, and its changelog is `26091001.txt`. The second that day is
`...02`, whatever the run number happens to be.

No release has been cut yet, which is why this directory holds only this file.
Add the first `<versionCode>.txt` when the first tag gets a signed APK attached
to it.

Keep entries terse. F-Droid truncates a long changelog in the listing, and the
CHANGELOG in the repository root is the full record.
