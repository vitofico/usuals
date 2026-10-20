# ADR 0014 Server connection and optional AI gateway

Status: accepted and verified locally, 2026-10-20. See the [verification record](../verification/2026-10-20-server-connection-ai.md).

Usuals asks once for an instance link or QR. An instance supplies optional account
services and its default model. Users can select an operator-approved provider
profile and save a personal key. Offline use and direct native providers remain
available. This supersedes only the device-origin model boundary in ADR-0012 and
the hosting plan; recipe-page and barcode lookups still originate on clients.

## Account connections

The installed app discovers the explicitly chosen HTTPS origin. HTTP is limited
to development loopback/emulator hosts. A public connection QR contains an origin,
never an administrator token. The custom app handoff also carries only that origin.

A five-minute browser approval ticket contains the new app public keys, a verifier
hash and a poll-secret hash. The app retains its random verifier and poll secret
in the Keystore vault. Both devices display the full 256-bit fingerprint. Browser
approval requires an admitted account, exact Origin and CSRF verification, plus an
explicit human comparison. The app polls with its retained verifier and poll
secret. The account token is derived with a dedicated connection domain from the
ticket ID and both secrets; the server retains only its hash. Successful retries
return the same account and expiry. The browser never receives the app verifier
or the resulting account token.

This is a polling handoff to the existing server account system, not a new OAuth
provider. OIDC still runs in the external browser through the existing server
flow. Approval requests survive the sign-in redirect in browser session storage;
only the public ticket ID is stored there. Native connections expire after 30 days
and can be disconnected locally. Account disabling, credential resets and admin
session revocation revoke native account services. Household membership remains
separate and requires the existing signed protocol and device-held keys.

Admitted native accounts create households under the existing account quota,
without the operator bootstrap capability. Native household principals remain
independent of browser-cookie sessions. No account login grants keys to an
existing household. The original bootstrap and invitation contracts remain valid.

## Full device verification

Household invitation approval displays the complete validated applicant
fingerprint and requires the owner to compare every group with the applicant's
screen. It does not truncate the code or trust the first request, an account name
or knowledge of the relay-visible invitation secret. This removes manual typing
and the second camera while preserving out-of-band full-fingerprint verification.
The exact signed reverse QR remains available as an alternative. No new pairing
cryptography or shorter authentication code is introduced.

## Optional model service

Purpose-specific versioned endpoints accept recipe text, prepared written-list
photos and synthetic capability probes. The server owns extraction prompts and
uses the existing OpenAI-compatible adapter. Endpoint destinations are fixed
operator profiles; callers cannot supply arbitrary destinations or model names
that were not configured for that profile. Provider redirects and automatic
transport retries remain disabled.

The gateway authenticates before reading a bounded request. It has two separate
admission slots, a separate coroutine execution allowance, a five-second upload
deadline and a 40-second total deadline. Per-account UTC-day attempts are committed
in PostgreSQL before inference. Probes and failed upstream attempts count. This
is an attempt limit, not a guaranteed monetary spending limit. AI cannot consume
the relay's separate synchronization admission slots.

Public profile revisions bind consent to destination, models, declared processing
location and personal credential configuration. Requests with an obsolete
revision fail before sending input upstream. Synthetic image verification is
bound to actual provider configuration and expires after ten minutes. Changing
profiles invalidates it. There is no automatic external-provider fallback.

Shopping ciphertext is unchanged. Explicit AI inputs are plaintext at the server
and selected provider. Keys use account-bound AES-256-GCM with a wrapping key
outside PostgreSQL. Request bodies, photos, credentials and model output are not
persisted by the gateway. Backups and wrapping-key rotation are operator duties
described in the provider guide. Browser code remains within the existing
operator trust boundary.

Migration 005 adds account connections, encrypted AI preferences, attempts and
vision checks. Existing Room and encrypted shopping schemas are unchanged.
Restore fencing deletes connection grants, native sessions and capability checks;
users must reconnect after a restore. Older server binaries reject the newer
migration ledger, so rollback requires a compatible binary or the fenced restore
procedure rather than a simple old-image restart.

The required verification covers handoff binding, replay, revocation, encrypted
credentials, migration preservation, stale provider consent, image gating,
durable budgets, denied arbitrary destinations, AI saturation versus sync,
browser/native contracts and actual client flows. Deployment and public release
require their separate operator action.
