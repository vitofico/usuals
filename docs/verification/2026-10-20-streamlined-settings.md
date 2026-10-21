# Streamlined settings verification

Baseline `7e0c9be`; branch `feat/streamlined-settings`.

Android Settings now opens a focused server connection, with compact appearance,
privacy and help controls. Direct AI remains under Help and about > Advanced.
The server supplies provider configuration and request limits. Its AI consent
switch remains locally revocable while offline, changed configuration requires
renewed confirmation, and a connected server never falls back to direct AI.
Household membership and recovery remain in Shared lists.

Web account AI settings show the server default with optional Advanced controls.
Photo import performs its synthetic capability check as part of the explicit
read action. Selection and preview do not upload. Both clients retain processing
disclosures and review before adding items. All five locales are complete.

## Checks

- Full Docker Android gate passed: 931 test executions, 40 release-tool tests,
  lint, debug/release and instrumentation APK builds. Release APK native alignment
  passed for all 12 libraries. Command: `scripts/dgradle verifyReleaseTools test
  lint :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest --no-watch-fs`.
- Full PostgreSQL gate passed: 79 server tests, 144 browser tests and both
  directions of browser/WebCrypto and JVM/Tink interoperability. The affected
  server/browser gate was rerun after correcting the final photo instructions.
- Independent code/security review and subsequent delta reviews have no open
  finding. Regressions cover focused navigation, public-link preview without
  opening the private vault, offline consent revocation, captured routing and
  budget mode, and cancellation before photo upload after a connection change.
- Real emulator execution passed four isolated Keystore tests and the focused
  connection scenario: full-code approval, server text and photo reads, AI
  opt-out, account household creation and disconnect retaining household access.
- Browser fixture passed simple AI settings, cleared unsaved Advanced key entry,
  explicit text submission and automatic photo checking. Recorded provider calls
  confirm no upload on selection and a synthetic probe before the real photo.
  Text/photo results stayed in review; cancellation left the three items intact.
- Restored the original instrumentation APK and removed the newly installed test
  app. All 16 original legacy-app private files retained their exact SHA-256
  values. Stopped the emulator, browser tab, disposable database and mock model.

Native desktop UI automation was unavailable because Computer Use permission
was disabled. Manual native visual and optical-camera checks were not performed.
The emulator scenario and Robolectric screen tests provide the functional checks;
this is not an onboarding timing study or a model-accuracy benchmark.

Local evidence: `/private/tmp/usuals-settings-qa/`,
`/private/tmp/usuals-settings-android-tests.log`,
`/private/tmp/usuals-settings-server-tests.log`,
`/private/tmp/usuals-settings-server-final.log`, and
`/private/tmp/usuals-settings-secret-scan.log`.
