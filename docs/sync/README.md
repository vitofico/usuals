# Lists on a Usuals server

When a phone or browser is signed in to a Usuals server, every list of the account
lives on that server and the clients show the same lists. The end-to-end encrypted
household protocol that came before was removed; its documents remain in Git history.

- [Decision](../decisions/ADR-0017-server-held-lists.md): why the server holds the lists and what that means for privacy.
- [Lists v2](lists-v2.md): the wire contract for lists, the change feed, sharing and connecting a phone.
- [Occasion packs](../occasion-packs.md): client-local templates, capability-gated additions and duplicate protection.
- [Shared-list notifications](../shared-list-notifications.md): transactional activity and optional client alerts.
- [Local foundation](local-foundation.md): how the phone keeps lists, history and pending changes in Room.
- [Server](../../server/README.md): build, configuration and limits.
- [Browser boundary](../../web/PROTOCOL.md): what the web app relies on.
- [Web identity](web-identity.md): local accounts, Authentik sign-in and sessions.
- [Operations](operations.md): service conventions and version pins.
- [Runbook](runbook.md): deployment, backup, restore and key rotation.
