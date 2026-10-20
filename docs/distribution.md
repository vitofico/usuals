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

[Local feedback corrections and a template-based draft](../distribution/fdroid/README.md)
are prepared without posting to GitLab. The conservative earliest reconsideration
is **2027-01-20**, based on the public release on 2026-10-19, with continued
maintenance and a fresh policy/release review. Nothing submits automatically.
The overall policy and future pipeline checkboxes remain unchecked.

Usuals is not listed on F-Droid. The prepared
[metadata](../distribution/fdroid/metadata/io.theficos.usuals.yml) is retained for
future consideration. It pins the release’s full source hash, exact version, Java 21/SDK 34 build,
upstream APK and allowed signing certificate. The recipe requests signature
preservation through reproducible builds. Optional proprietary model services
are declared as `NonFreeNet`; offline lists and local models need none.

Store descriptions, screenshots and version-code changelogs live in
`fastlane/metadata/android/`. All five interface languages are represented.
New store locales add complete files there. Normal Android UI localization
continues to use only complete `values-xx/` resources.

## Automatic GitHub releases

Every push to `main` runs the Android build, tests, lint, native alignment,
release-tool tests and the server/browser gate. After both gates pass,
`android-ci` calls `release-apk` to publish a signed stable Android update.
Documentation-only changes run CI without creating another APK. Changes to
application, server, browser, distribution or build inputs qualify for a release.
No manual version edit, tag creation or APK upload is needed.

The release job:

1. Uses the exact `main` commit that passed CI. It prepares a local bot commit
   containing only `gradle.properties` and localized version-code changelogs.
   The version name is UTC `YYYY.MM.DD.N`; N advances beyond all existing releases
   that day. The Android code is the greater of every previous code plus one and
   `yyMMdd * 100 + 1`, so updates remain monotonic even after many daily releases.
   A backward calendar date or exhausted Android code fails closed.
2. Builds the unsigned publication candidate separately with
   `scripts/dgradle :app:clean :app:assembleRelease --no-daemon --no-build-cache`.
   It signs offline with the existing production identity, checks package,
   version, certificate, licenses and 16 KB alignment, then independently builds
   again and requires exact signature-copy reproduction. The combined CI gate's
   incremental APK is never the publication candidate.
3. Atomically pushes the verified bot commit and its immutable version tag.
   It uses a normal fast-forward push. If `main` advanced, it discards the
   candidate and dispatches fresh CI on current `main`; it never rebases an
   untested candidate or force-pushes. A source trailer binds the version commit
   to the checked parent.
4. Publishes `app-release.apk`, its SHA-256 file and `release-identity.json`.
   Obtainium discovers this stable release automatically. Retrying the original
   run reuses its version and any existing APK bytes; existing assets must match
   exactly before missing sidecars can be repaired. Published assets are never
   overwritten. Main runs and release publication are serialized. Bot pushes do
   not recursively trigger another release with GitHub's built-in token.

Each store locale supplies `changelogs/template.txt` with 1 to 500 characters.
CI copies it to the next version-code filename. Change these templates for
localized release highlights, and keep detailed changes in `CHANGELOG.md` and
commit descriptions. Adding a store locale means adding complete files, including
its template. Keep the paused F-Droid recipe pinned to its existing release;
Android publication does not submit or update an inclusion request.

### Recovery and local verification

Rerun a failed `android-ci` run to finish its same version. To repair a tagged
release independently, dispatch `release-apk` from `main` with its existing
`vYYYY.MM.DD.N` tag. The tag must be reachable from `main`. Manual recovery runs
its own Docker tests and lint before building a missing APK; an existing signed
APK is downloaded and verified rather than rebuilt or replaced. Dispatch
`android-ci` on `main` to retry the current source after a stale or cancelled run.
The workflow needs repository contents and Actions write permissions to publish
and dispatch that fresh check. Fork pull requests receive no signing secrets or
release permissions.

For local release verification, keep the production key outside the checkout.
`scripts/sign-release /absolute/private/signing-directory` uses an offline,
read-only container without repository or Gradle-cache mounts. Its private
inputs are `usuals-release.keystore`, `keystore-password.txt`, `key-password.txt`,
`key-alias.txt` and `certificate-sha256.txt`. The certificate must match the
committed Usuals pin. Never replace a published key.

Preserve a separately clean signed candidate outside `app/build`, then repeat
the clean release-only build with no build cache. Use
`scripts/check-apk-reproducibility SIGNED_APK UNSIGNED_APK` to require exact bytes.
Restore the candidate to `app/build/outputs/apk/release/app-release.apk` and run
`scripts/verify-release TAG` against its clean, committed tag. On Linux, restore
Docker output ownership with `sudo chown -R "$(id -u):$(id -g)" app/build` before
signing or restoring the candidate. The verified three assets can be published
with `scripts/publish-release.py`, which never replaces existing bytes.

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
