# Android distribution

The production package is `io.theficos.usuals`. Debug builds use
`io.theficos.usuals.debug` and have independent data. Keep existing installations
and their data when testing a release. To move from a private debug preview,
export/import shopping data through History and verify the imported facts before
removing the old app. Sync identities are not exported; enroll the production
installation through a fresh invitation.

## Install and update

Download `app-release.apk`, `app-release.apk.sha256` and `release-identity.json`
from the [GitHub releases](https://github.com/vitofico/usuals/releases). Verify the
checksum before installation. The identity record binds the binary, source
commit, version and signing certificate. The public certificate pin is
[release-certificate-sha256.txt](../scripts/release-certificate-sha256.txt).

[Obtainium](https://obtainium.imranr.dev/) follows this GitHub repository directly.
Add `https://github.com/vitofico/usuals` or use the
[import configuration](../distribution/obtainium.json) and
[app link](../distribution/obtainium-link.txt). Only stable `app-release.apk`
assets are selected. There is no separate Obtainium store submission.

F-Droid [closed the initial submission on 2026-10-19](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/53283),
citing the requirement for more than three months of maintenance, failure to
follow its merge request template, and insufficient unique features. The
maintainer has paused resubmission because Usuals does not meet the maintenance
requirement. Passing the build pipeline does not establish inclusion eligibility.
Any future submission must also address the template and distinctiveness feedback;
reaching the maintenance threshold alone does not guarantee acceptance.

Usuals is not listed on F-Droid. The prepared
[metadata](../distribution/fdroid/metadata/io.theficos.usuals.yml) is retained for
future consideration. It names the public source/tag, exact version, Java 21/SDK 34 build,
upstream APK and allowed signing certificate. The recipe requests signature
preservation through reproducible builds. Optional proprietary model services
are declared as `NonFreeNet`; offline lists and local models need none.

Store descriptions, screenshots and version-code changelogs live in
`fastlane/metadata/android/`. All five interface languages are represented.
New store locales add complete files there. Normal Android UI localization
continues to use only complete `values-xx/` resources.

## Prepare a release

1. Commit `VERSION_NAME` and a strictly increasing `VERSION_CODE` in
   `gradle.properties`, the five changelogs and the F-Droid recipe. Use
   `YYYY.MM.DD.N` and tag `vYYYY.MM.DD.N`. CI never rewrites the source version.
2. Run `scripts/dgradle verifyReleaseTools :app:assembleDebug :app:assembleRelease
   test lint --no-daemon --no-watch-fs --no-parallel --max-workers=2` and the
   affected server/browser gate. Verify on an emulator and scan all Git history
   with `scripts/secret-scan` before publication.
3. Keep the production key outside the checkout. Android assembly is unsigned.
   Start the candidate with `:app:clean :app:assembleRelease` so stale incremental
   compiler outputs from earlier development builds cannot enter a release.
   On Linux, restore ownership with `sudo chown -R "$(id -u):$(id -g)" app/build`
   after Docker builds and before signing or restoring a signed candidate.
   `scripts/sign-release /absolute/private/signing-directory` signs in an offline
   read-only container, with no repository or Gradle-cache mount. The signing
   directory contains `usuals-release.keystore`, `keystore-password.txt`,
   `key-password.txt`, `key-alias.txt` and `certificate-sha256.txt`. Its certificate
   must equal the committed Usuals pin. Never replace a published signing key.
4. Preserve the signed candidate outside `app/build`, then rebuild with
   `scripts/dgradle :app:clean :app:assembleRelease --no-daemon --no-build-cache`.
   Run `scripts/check-apk-reproducibility SIGNED_APK UNSIGNED_APK` to copy the
   existing signature, verify it and require byte-for-byte equality. The pinned
   verification packages run in Docker and never receive private keys.
5. Commit the verified source, create its immutable tag, restore the signed
   candidate to `app/build/outputs/apk/release/app-release.apk`, and run
   `scripts/verify-release TAG`. It rejects the wrong package, debug signing,
   a mismatched certificate/version, an uncommitted tree or a different tag.
6. Publish the tag and three verified assets. `scripts/publish-release.py`
   verifies existing asset bytes before repairing missing files and never
   overwrites an existing binary. Dispatch `release-apk` with that existing tag
   for a CI build/publish. Tags must be reachable from `main`.

CI requires all four `USUALS_RELEASE_*` secrets: `KEYSTORE_B64`,
`KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`. Missing credentials fail
publication. They are decoded only after compilation and removed immediately
after isolated signing, including on failure. Encrypted offline recovery of the
key is an operator responsibility; private material never belongs in Git.

Making a source/APK release does not deploy the server. Web household creation
requires the matching server source and migration 004. Existing operators must
follow the [upgrade runbook](../docs/sync/runbook.md), back up their identity and
database, and explicitly approve their own deployment.

Sources: [Obtainium app links](https://wiki.obtainium.imranr.dev/deep_links/),
[F-Droid inclusion](https://f-droid.org/en/docs/Submitting_to_F-Droid_Quick_Start_Guide/),
[F-Droid metadata](https://f-droid.org/en/docs/All_About_Descriptions_Graphics_and_Screenshots/).
