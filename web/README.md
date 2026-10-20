# Usuals browser client

Buildless native JavaScript modules served at the relay origin. The root server
build packages only `index.html`, `assets/`, `src/` and `locales/`. No npm install,
CDN, third-party script or browser provider token is involved.

The browser signs in through a local account or the configured OIDC provider,
then unlocks its separately encrypted local vault. Create a household here or
join one through an invitation from an Android or browser owner. Joining devices
need full fingerprint approval. Account administrators cannot grant list keys.
Browser owners can invite, approve and remove devices; recovery and advanced
ownership actions remain available in Android.

## First household

A guided tour opens on the first visit to this browser. Use **Next** and **Back**
to explore household setup, sign-in, the browser passphrase and shared editing,
or **Skip tour** to go directly to the app. Completion and skipping are remembered
in this browser's local storage. If that storage is blocked or cleared, the tour
may appear again. **Guided tour** in the navigation reopens it at any time.
It never signs in, changes a permission or creates household membership for you.

Signing in with Authentik or a local account opens your account. Both starting
on the web and bringing a list from Android are supported.

For a new web household, unlock the browser vault and choose **Create household**.
Name the initial list, download the encrypted recovery file and save its key
separately. Select the saved file and enter the saved key to verify both before
creation. This uses your admitted account, without an operator setup code.
From Shared lists, invite a device and compare its complete fingerprint before
approval. Keep the kit for Android recovery if this browser is lost.

## Connect a phone with QR

1. Sign in, unlock the web vault and open **Shared lists > Connect a phone**.
   Create a household first if you do not have one yet.
2. On Android, open **Settings > Shared lists > Scan invitation QR**. Scan the
   web code, check the displayed server address and tap **Connect this phone**.
3. Back on the web, choose **Scan phone verification** and allow the computer
   camera. Point it at the verification QR now shown on Android. The browser
   approves that exact signed request and Android checks approval automatically.

The private invitation expires after ten minutes and works once. An expired
code is hidden; choose **Create a new code** to replace it. QR generation and
camera decoding run on your device without uploads or an external QR service.
Closing the dialog, locking the vault or hiding the page stops the camera.

Without a camera, expand **Use a link or manual verification** and copy the
invitation into Android. On the web, use **Review join requests** and enter the full
phone fingerprint obtained through a trusted channel. Account sign-in alone
does not grant household membership or list keys.

To share a list already on your Android phone:

1. Open **Settings > Shared lists > Create a household** in Android. Use the
   same server origin as the web app.
2. Obtain a one-time household setup code from the server administrator.
   Prepare, save and verify the recovery kit, then choose **Create household**.
3. Choose **Share a local list** to create a shared copy of your shopping list.
4. Choose **Invite someone**. Paste that invitation into the signed-in browser,
   then use **Review join requests** in Android to compare the full fingerprint and
   approve the browser.

The signed-in empty state offers web creation, the Android guide with the current
server address, and the invitation form. Joining an existing household always
requires its owner's approval, including for the server administrator.

Interrupted creation retains the exact owner identity and initial list in the
encrypted vault. Continue setup after reconnecting. A definitive creation-quota
rejection can be cancelled to join an existing household. Membership work with
an uncertain outcome is retained. **Discard obsolete change** verifies server
membership and checkpoints first, preserving shopping edits and history pins.

The editor supports real encrypted list reads, Markdown import, optional photo
import, add, check, edit and delete,
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

## Markdown and photo import

**Import ingredients** reads Markdown locally from text or a UTF-8 file, skips
completed checkboxes and recipe instructions, and reviews the selected rows.
Confirmation queues all new rows in one atomic vault save. Existing identities
and aliases are excluded rather than increasing their quantities.

Photos require an explicit memory-only model connection and a successful image
compatibility check. The operator must allow exact provider origins and the
provider must support browser CORS. No photo is sent by selection, preview or
capture alone. See [provider setup](../docs/providers.md). Camera permission is
requested only on **Take photo**; cancellation and failures stop the stream.

For isolated browser photo QA, pass `-PpreviewModelOrigin=http://localhost:18748`
to `server/scripts/preview-local` and run a synthetic provider at that loopback
origin. This fixture-only option rejects remote hosts and is absent from the
production startup path. Never use real photos, keys or households in the fixture.
