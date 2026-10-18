# ADR-0009: F-Droid and direct APK, without Play Services

- **Status:** Accepted (2026-09-10), retained after the smart-list pivot on 2026-10-15
- **Relates to:** ADR-0010, ADR-0012

## Context

Usuals should work on Android devices without Google services. The original
retailer-based rationale is historical; the distribution and dependency decision
still applies to the smart shopping list. No release or store listing exists yet.

## Decision

F-Droid and direct APK are the primary intended distribution channels. Do not
add Play Services, Firebase, FCM, ML Kit, analytics or crash-reporting services.
Barcode decoding uses CameraX and ZXing. Builds use the pinned Docker toolchain
under ADR-0010. Reproducibility must be checked before claiming an F-Droid release.

No push transport, polling job or foreground shopping service exists in this
build. A future opt-in local reminder may use WorkManager without Play Services.
Encrypted sync and its notification behavior need a separate approved design.
Play distribution remains a separate decision, rather than a current commitment.

## Consequences

The list and widget need no Google account or Play service. Distribution still
requires signing, update policy and store/reproducibility checks. Native library
alignment and physical-device performance remain release checks. The retired
cart and notification implementation plans are available in Git history.
