# Account policy and lifecycle

These optional server interfaces use the existing account identity and list
permissions. Local phone use does not depend on them. Apply the numbered server
migrations before starting the updated binary.

## Operator limits

`USUALS_ACCOUNT_LIMITS` is a JSON object mapping canonical account UUIDs to limit
objects. `USUALS_DEFAULT_ACCOUNT_LIMITS` optionally supplies the default object.
The fields are `maxOwnedLists`, `maxListMembers` and `monthlyAiRequests`. An object
must specify a list or member limit; omitted fields retain the server safety
limit, and an omitted monthly allowance adds no monthly cap. The existing daily
AI limit still applies. Invalid fields, ranges or account IDs fail startup.

Owned-list accounting includes archived lists and frees capacity on deletion.
Membership counts include the owner. Sharing uses the list owner's limit,
including when an editor adds a member. Lowering a limit preserves existing
lists and members, while refusing additions beyond the limit. Provider probes
consume the monthly allowance too. Monthly counters persist in PostgreSQL and
reset at the next UTC calendar month; they are not reset by policy changes.

To enable dynamic updates, set `USUALS_ACCOUNT_POLICY_KEY_FILE` to a protected
regular file containing exactly 32 random bytes. Symlinks and group/other file
permissions are refused. The service bearer is the unpadded base64url encoding
of those bytes. Keep this key separate from browser and phone credentials.

`PUT /api/service/accounts/{accountId}/limits` accepts one `Authorization: Bearer`
header and no cookie. Its JSON body has `limits`, `fallback` and `expiresAt`.
Both limit objects must explicitly supply `maxOwnedLists` and `maxListMembers`;
`monthlyAiRequests` is optional. `expiresAt` is Unix time in milliseconds, after
now and no more than five minutes ahead. Updates apply only to an existing,
enabled account. Once expired, the stored explicit fallback applies on the next
operation without a worker or policy-server call. With no key, this route is
absent. With no configured policy, existing server safety limits apply.

`GET /api/me` includes `limits` when a policy exists, with owned-list usage and
the dynamic expiry where applicable. The service credential cannot create
accounts, grant membership or read list contents.

## Export and closure

`GET /api/account/export` uses a browser session and returns account metadata
plus an ACL-filtered list page, without credentials. Save the page, then repeat
with `?cursor=` set to its `lists.cursor` while `lists.hasMore` is true. A reset
requires restarting the export. The feed can change during export; this is not a
transactional database backup.

A browser prepares closure with `POST /api/account/closure-requests`, using its
normal CSRF/origin protections. Supply a new UUID `id`, `confirm: "DELETE"`, and
fresh authentication. A local account supplies `password`; an OIDC account needs
a recent verified provider authentication time. If owned lists are shared,
transfer/delete them first or explicitly supply `deleteOwnedLists: true` and
`sharedConfirmation: "DELETE SHARED LISTS"`. Closure rechecks shared ownership,
so newly added sharing cannot bypass the choice. Retrying the same request ID is
idempotent; a different pending ID is refused.

Set `USUALS_ACCOUNT_LIFECYCLE_KEY_FILE` to another protected 32-byte raw key to
enable the operator execution routes. Its bearer uses the same base64url format
as the policy key. Each request requires exactly one authorization header and no
cookie:

| Method | Route | Result |
|---|---|---|
| GET | `/api/service/accounts/{accountId}/closure/{requestId}` | Exact prepared operation status |
| POST | `/api/service/accounts/{accountId}/closure/{requestId}` | Close the authorized account |
| POST | `/api/service/accounts/{accountId}/closure/{requestId}/purge` | Remove retained issuer/subject after closure |

Closure fences account operations, revokes browser/phone credentials, removes
owned list contents and account preferences, and removes the account's membership
from other lists. Lists owned by other accounts remain. Minimal disabled-account,
list tombstone and operation records remain for referential integrity and retry
safety. The purge step removes the retained external identity binding; it is not
a promise that historic backups or a separate identity provider are erased.

The browser preparation/export endpoints are available when account identity is
enabled. Operator execution routes are absent without their key. An integrating
operator must complete external identity and backup reconciliation separately.
Restores still require the independent fence procedure in the
[runbook](runbook.md); restoring an old database is not proof that current
account deletion decisions have been reconciled.
