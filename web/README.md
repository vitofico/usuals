# Aurea browser client

Buildless native JavaScript modules served at the relay origin. The root server
build packages only `index.html`, `assets/`, `src/` and `locales/`. No npm install,
CDN, third-party script or browser provider token is involved.

The browser signs in through a local account or the configured OIDC provider,
then unlocks its separately encrypted local vault. A household owner approves a
fresh browser device using an Android invitation and full reverse fingerprint.
An account administrator cannot grant list keys. Owners continue to create
households, rotate membership and manage recovery in Android.

The editor supports real encrypted list reads, add, check, edit and delete,
including durable offline pending intents. It reduces every v1 operation from
Android. It retains exact ciphertext for retries, verifies signed membership and
receipt chains, and stops on unsupported versions, rollback or restored/frozen
households. See [the security boundary](PROTOCOL.md).

Private state remains encrypted in IndexedDB under a separate strong browser
passphrase, bound to immutable account identity and origin. Web Locks prevents
simultaneous unlocked tabs. Leaving the page or five minutes of inactivity
locks it. There is no device-key export/import or server password recovery for
this vault. A fresh invitation is needed if its passphrase is lost.

The active web origin supplies the executable code. A malicious operator who
replaces that code can observe an unlocked browser; use the independently
installed Android application for its stronger operator boundary.

## Languages

English is the default. Copy `locales/en.json` to another `locales/xx.json` and
translate every value, keeping `{placeholders}` unchanged. Catalog tests discover
all files and enforce complete parity. The language picker discovers the packaged
catalog inventory supplied by the server; labels belong in each catalog.

## Tests

Use `scripts/dgradle -p server verifyWebInterop` from the repository root. The
central task generates disposable Kotlin/Tink fixtures, runs Node built-in tests,
and verifies browser-created envelopes and HPKE packages in Kotlin. Tests cover
all 38 shared reducer scenarios, framing/RFC vectors, tampering and strict JSON,
account-bound vault storage, late asynchronous completion and held-tab locks.
No test fixture contains a real household or account key.

For a disposable local browser pass, run `server/scripts/preview-local`. It
publishes only a loopback port, starts isolated PostgreSQL and writes private
setup/command files under a fresh `server/build/browser-preview.*` directory. Activate the
synthetic administrator using `browser-setup.json`, then unlock a separate vault
and paste its editor invitation. Compare the full fingerprint shown by the
browser before writing `approve.json` with that fingerprint and request ID.
This fixture uses the production Kotlin household client as the native peer.
Create `stop` with `umask 077; touch <directory>/stop` to cleanly stop the preview;
all fixture input files must have mode0600.
PostgreSQL and its credentials are removed. Never use this fixture with real
accounts, household keys or production databases.
