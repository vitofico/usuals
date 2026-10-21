# Copied notes and Markdown import verification

Baseline `0000000`; branch `feat/markdown-notes`.

Android Import opens Text or Markdown first. Shared text and Markdown notes open
the same local review before any item is saved. Short shared capture lists keep
their existing comma/conjunction parsing, including when read again. Only a
standalone shared HTTP(S) URL opens the recipe reader.

Android and web accept one item per line, numbered lists, Unicode bullets and
unchecked boxes. Completed boxes are omitted. Recipe sections, quantity parsing,
duplicate exclusion, item selection and bounds remain enforced. Text import
does not request a model, render Markdown or fetch embedded links. Five UI
languages and store release-note templates are updated.

## Checks

- Full Docker Android gate passed: 882 test executions, five intentional skips,
  40 release-tool tests, lint and debug/release/instrumentation APK builds.
- The test-only Espresso upgrade was followed by instrumentation APK and lint
  rebuilds. Native alignment passed for all APKs.
- The opt-in emulator test passed with the real review sheet and an isolated
  in-memory repository: quantities, checked-row omission, cancel without writes,
  confirm and zero model use. The original instrumentation APK was restored and
  the temporary debug app removed.
- The browser fixture exercised the production dialog/parser: mixed note lines,
  quantities, existing-item exclusion, cancellation, deselection and one atomic
  add batch. Styled review screenshots were inspected on both clients.
- The full disposable PostgreSQL gate passed: 79 server tests, 145 browser
  tests and browser/WebCrypto to JVM/Tink interoperability.
- Independent code/security review and subsequent delta reviews have no open
  findings. The staged credential scan passed.

Local evidence: `/private/tmp/usuals-notes-android-complete.log`,
`/private/tmp/usuals-notes-instrument-final.log`,
`/private/tmp/usuals-notes-device/run-espresso37.log`,
`/private/tmp/usuals-notes-review.png`, and
`/private/tmp/usuals-notes-secret-final.log`.
