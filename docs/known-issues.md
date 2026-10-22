# Known issues

These limits describe the implemented smart-list build. Removed-product risks
are historical and remain in Git history and superseded ADRs.

| ID | Severity | Current limit and disposition |
|---|---|---|
| GAP-SMART-1 | Medium | Interval predictions remain off until real-history replay beats both baselines. Staples, explicit repeats and quantity hints work now. Recheck after 8 to 10 weeks of real use. |
| LIMIT-RECIPE-1 | Low | Recipe sites may block device HTTP clients. The app reports the block without bypassing it. Ambiguous measures stay in notes; ingredients require review. |
| LIMIT-SCAN-1 | Low | Open Food Facts coverage and names are crowd-sourced. Enable lookup in the scanner or Settings. Missing records and connection failures are explained; manual naming remains available. New names require confirmation, then work offline on later scans. |
| GAP-SYNC-1 | Informational | Server-held lists, sharing between accounts and phone connection are implemented and tested locally. A physical-phone pass with the web app, approved cluster deployment and a pilot with real users remain release gates. |
| LIMIT-PRIVACY-1 | Medium | The server reads the lists of its accounts to apply changes, so its operator can read them too. Encryption at rest covers a copied database or backup, not a running server. Every account on a server sees the display names and emails of the other enabled accounts when sharing. Lists on a signed-out phone stay on the phone. |
| LIMIT-CONNECT-1 | Low | Whoever scans a shown **Connect a phone** QR within its 10 minutes connects a phone to that account, with no code to compare. It works once; keep it private while it is on screen. |
| LIMIT-UPGRADE-1 | Low | Lists that existed only in a browser vault of an earlier version are lost. When two phones held the same old shared list, the second upload becomes a separate copy. Older app versions are told to update. |
| GAP-SYNC-OPS | Medium | Deployment bundles, manifests, metrics, backup and restore tools are prepared. A local authenticated near-limit smoke does not establish capacity. Live TLS/CNI/storage, encrypted off-cluster RPO/RTO, worst-case authenticated capacity, a chosen retention window (pruning is opt-in), list-key escrow and an elapsed pilot remain gates before wider use. The server runs as one replica. |
| RISK-DATA-1 | Medium | Unreleased schemas 1 to 3 are wiped on upgrade. Finalized schema 4 migrates through schemas 5 and 6 to schema 7 without deleting shopping facts; subsequent schema changes need migrations and migration tests. Keep a shopping-data export before resetting a development install. |
| RISK-VOICE-1 | Low | Voice prefers on-device recognition but can fall back to the selected Android speech service, which may send audio off the phone. The privacy screen discloses this; keyboard entry works offline. |
| RISK-PROVIDER-1 | Medium | A hosted model receives explicitly submitted recipe text. A local-provider label cannot prove the endpoint's storage or network behavior. No-model operation is fully supported. |
| RISK-ANDROID-1 | Medium | The API 37.1 emulator previously showed input ANRs under heavy graphics/interrupt load. Native 16 KB alignment is fixed. Current emulator and Motorola Android 15 smoke checks pass with no new crashes or ANRs. Repeat device checks for the eventual release APK. |

The native compatibility warning is resolved by CameraX 1.4.2. Artifact inspection
identified the old CameraX 1.3.4 image-processing library as the 4 KB binary;
the graphics library was already aligned. Both debug and release APKs pass ELF
and ZIP alignment checks for all four ABIs. See [release checks](release-check.md).

For the first captured ANR, the main thread was runnable in Compose drawing, with no
storage or network lock in the trace. Guest CPU was 90%, including substantial
graphics and interrupt work. Restarting only the debug app preserved data and
allowed the store/widget checks to pass. This is evidence of the observed
emulator condition, not a physical-device performance certification.

The final scanner confirmation rule has deterministic regression tests. The
simulator image camera rendered a known barcode, but neither the previous
analyzer nor the current one decoded it. A final physical optical check was
deferred when the phone was returned. Live voice was not exercised in this
follow-up. See the extended QA record in [release checks](release-check.md).

Signed APKs are distributed through GitHub Releases and Obtainium. F-Droid
closed the initial submission, including because Usuals has not been maintained
for more than three months. Resubmission is paused; see the
[distribution guide](distribution.md) for all closure feedback and the corrected
local draft. Earliest reconsideration is 2027-01-20 with evidence of continued
maintenance and a fresh review. No resubmission is scheduled.
Docker builds remain required under ADR-0010. See
[release checks](release-check.md) for the verified local scope.
