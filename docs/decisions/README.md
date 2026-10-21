# Architecture decisions

[ADR-0017](ADR-0017-server-held-lists.md) is the current sharing model: a signed-in
account's lists live on its Usuals server, phone and browser show the same lists,
and accounts on that server share a list by adding each other. It supersedes the
end-to-end encrypted household design of [ADR-0013](ADR-0013-shared-list-protocol.md),
[ADR-0015](ADR-0015-pairing-v2.md) and [ADR-0016](ADR-0016-one-scan-phone-connection.md).
[ADR-0014](ADR-0014-server-connection-and-ai.md) still describes the account
connection and the optional server AI gateway; its household parts are superseded.

[ADR-0012](ADR-0012-smart-shopping-list.md) records the implemented smart-list pivot.

- ADR-0001, 0002, 0003, 0006, 0007 and 0008 are superseded historical decisions.
- ADR-0004 is amended for validated GTINs and confirmed named-item aliases.
- ADR-0005 is amended for optional model probing and its daily budget.
- ADR-0009 remains accepted: F-Droid/direct APK and no Play Services.
- ADR-0010 remains accepted: Docker-only Gradle builds.
- ADR-0011 is rewritten for opt-in scan naming and device-only third-party reads.
- ADR-0013, 0015 and 0016 are superseded by ADR-0017.

Superseded ADRs retain their original text as history. Amended current decisions
describe the implemented contracts; their older text is available in Git history.
New decisions must distinguish implemented behavior from planned work.
