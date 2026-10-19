# Usuals browser client

Buildless native JavaScript modules served at the relay origin. The root server
build packages only `index.html`, `assets/`, `src/` and `locales/`. No npm install,
CDN, third-party script or browser provider token is involved.

The browser signs in through a local account or the configured OIDC provider,
then unlocks its separately encrypted local vault. A household owner approves a
fresh browser device using an Android invitation and full reverse fingerprint.
An account administrator cannot grant list keys. Owners continue to create
households, rotate membership and manage recovery in Android.

## First household

Signing in with Authentik or a local account opens your account. To use shared
lists, create the first household in Android and then invite the browser:

1. Open **Settings > Shared lists > Create a household** in Android. Use the
   same server origin as the web app.
2. Obtain a one-time household setup code from the server administrator.
   Prepare, save and verify the recovery kit, then choose **Create household**.
3. Choose **Share a local list** to create a shared copy of your shopping list.
4. Choose **Invite someone**. Paste that invitation into the signed-in browser,
   then use **Review requests** in Android to compare the full fingerprint and
   approve the browser.

The signed-in empty state displays this guide and the current server address
above the invitation form. The invitation is also required for the server
owner's browser. Account sign-in keeps its existing separate authority.

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

Use **Appearance** in the sidebar for Plum, Grove or Tide. The choice is local
to this browser, persists across reloads and follows system light/dark mode. It
does not affect account sessions or the encrypted vault. When browser storage is
unavailable, changing the palette still works for the current page.

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
