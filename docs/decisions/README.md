# Architecture decisions

[ADR-0012](ADR-0012-smart-shopping-list.md) records the implemented smart-list pivot.
[ADR-0013](ADR-0013-shared-list-protocol.md) accepts the reviewed S0 contract for
local shared-list implementation; the current app remains single-phone.

- ADR-0001, 0002, 0003, 0006, 0007 and 0008 are superseded historical decisions.
- ADR-0004 is amended for validated GTINs and confirmed named-item aliases.
- ADR-0005 is amended for optional model probing and its daily budget.
- ADR-0009 remains accepted: F-Droid/direct APK and no Play Services.
- ADR-0010 remains accepted: Docker-only Gradle builds.
- ADR-0011 is rewritten for opt-in scan naming and device-only third-party reads.

Superseded ADRs retain their original text as history. Amended current decisions
describe the implemented contracts; their older text is available in Git history.
New decisions must distinguish implemented behavior from planned work.
