# Server connection and AI gateway verification

Baseline: `9577901`. Feature branch: `feat/server-connection-ai`.

The approved implementation covers Android and web. It adds a public server-link
and QR handoff, admitted-account household creation, full-code device comparison,
a retained source-list action, and the server AI default with optional personal
credentials for operator-approved profiles. Shopping encryption and Room's
released schema remain unchanged. No cluster deployment, publication or real
provider call was performed.

## Automated checks

- Final Android gate passed in 5m 49s: 914 JVM/Android test executions, no failures or lint errors, all 40 release-tool checks, debug/release/instrumentation APK builds. Command:
  `scripts/dgradle verifyReleaseTools test lint :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest --write-locks --no-watch-fs`.
- Complete PostgreSQL/server gate passed after the serialized-state fix:
  `server/scripts/test-local --write-locks`. It includes 77 PostgreSQL-backed
  server tests, the shared JVM suites, 144 browser tests, and both directions of
  Kotlin/Tink and browser/WebCrypto interoperability, including household,
  recovery-file, package and initial-snapshot codecs.
- Contract and security regressions cover verifier/key binding, replay, expiry,
  cancellation/replacement/concurrent completion, serialized private state,
  account admission and revocation races, browser CSRF, encrypted credentials,
  wrapping-key rotation, migration preservation, restore fencing, provider
  destination restrictions, consent revisions, image gating, durable limits and
  AI saturation without exhausting synchronization slots.

## Actual client checks

Only disposable localhost PostgreSQL, synthetic accounts and a scripted local
OpenAI-compatible provider were used. Provider records contain request kind,
model name and presence of authorization, with no submitted content or keys.

- Android public `usuals://connect` opens the connection screen with its server
  address filled in. The camera-free fallback retains that address and Connect.
- A fresh browser sign-in retains the pending native handoff. The complete code
  matches the phone. Browser approval is detected automatically by the app.
- The production Android transport and Keystore store passed text extraction,
  synthetic vision probing, photo extraction, verified-recovery household
  creation without an operator code, and disconnect preserving household access.
  Instrumentation: `ConnectionDeviceScenarioTest`, `OK (1 test)`, 22.09 seconds
  for this scripted scenario; this is not a timing study with unfamiliar people.
- Browser household approval survives locking and reauthentication. Existing
  encrypted fixture items load correctly. Recipe text and a prepared synthetic
  PNG both produce editable review results through the server default, without
  entering a provider endpoint or key in the browser. Cancelling leaves the
  original three items unchanged. Text review was inspected at 390 CSS pixels.
- Browser expired handoffs clear their URL/state, live handoffs can be cancelled,
  and Connected phones correctly reflects native logout and browser revocation.
- Updating the original debug APK (code 26101901) retained all three database
  files byte for byte. After QA, the original APK and all 12 saved private files
  were restored and their SHA-256 values matched exactly. The task's emulator,
  model process, port mappings and disposable database were stopped.

The first device run exposed reference equality in pending private-key state;
value-field comparison and serialization-backed tests fixed it. A separate
emulator graphics crash and HTTP/1.0 fixture framing issue were isolated and
corrected before the successful run. A stalled browser file chooser was repeated
with explicit deadlines; the bounded photo extraction and review passed.

## Review and limits

A separately authorized agent independently reviewed the full implementation
against the baseline, then reviewed the fix deltas. No material finding remains.
It performed source review independently of the author's test execution.

Automatic approval review rejected opening the camera permission prompt because
it interpreted that action as granting camera access. The pre-permission dialog
was dismissed and the paste-link fallback passed. Optical camera scanning was
not exercised. Real provider accuracy, Theficos network policy/model selection,
load sizing and onboarding timings with unfamiliar people remain pilot work.

The complete staged change also passed the repository credential scan.

Final unsigned release APK SHA-256:
`1ba92abb8547cd3069e3a0c728a0128dc8a01923851ce02e329468acf96bfb42`.

Local evidence: `/private/tmp/usuals-connection-qa/`,
`/private/tmp/usuals-connection-android-tests.log`, and
`/private/tmp/usuals-connection-server-tests.log`.
