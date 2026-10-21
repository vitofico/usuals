# Usuals browser client

Buildless native JavaScript modules served at the relay origin. The root server
build packages only `index.html`, `assets/`, `src/` and `locales/`. No npm install,
CDN, third-party script or browser provider token is involved.

The browser signs in through a local account or the configured OIDC provider,
then unlocks its separately encrypted local vault. Create a household here or
join one through an invite link from an Android or browser owner. The owner
approves each joining device after matching a 6-digit code. Account administrators
cannot grant list keys.
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
From Shared lists, invite a device and approve the request whose code matches the
one it shows. Keep the kit for Android recovery if this browser is lost.

## Server connection and AI

**Account > Connect a phone** shows a one-time QR for your own phone. The phone
scans it and taps **Continue**; approve it here when it shows the same 6-digit code.
The phone needs no sign-in of its own. When this browser is unlocked and owns a
household, the same approval also adds the phone to it.
**Account > AI assistance** shows the server-selected provider. Its optional
**Advanced** section contains model details and personal provider/key controls. Photo import and recipe-text extraction use this same-origin gateway when
available; ordinary users need no provider CORS or separate endpoint setup. Inputs
are submitted only on explicit action and remain drafts until reviewed. See the
[connection guide](../docs/server-connection.md) and [provider guide](../docs/providers.md).

## Connect a phone with QR

1. Sign in, unlock the web vault and choose **Connect a phone** on the household
   card. Create a household first if you do not have one yet.
2. On the phone, scan the code with the camera (the server's `/connect` page offers
   **Open in Usuals**) or in Usuals, and tap **Continue**.
3. The phone appears in the open dialog with a 6-digit code. Approve it when the
   phone shows the same code. The phone is then signed in and joins the household
   without another approval.

The code works for one phone and expires after 10 minutes; **Create a new code**
replaces it. For another person's phone or a browser, use **Invite someone**: that
invite works once and expires after 24 hours. QR generation runs on your device
without uploads or an external QR service.

Without a camera, expand **Share the link instead** and send the link privately.
Anyone who opens it can ask to join, so approve only the code you expect. A
request that used an invite from another owner device must be approved on that
device. Account sign-in alone does not grant household membership or list keys.

To share a list already on your Android phone:

1. In Android, open **Settings > Server and sharing** and choose **Start sharing**.
   Connect to the same server origin as the web app and sign in.
2. Save the recovery file, copy the recovery code and create the household.
3. Share a local list, then choose **Invite**. Open the invite link in the
   signed-in browser and choose **Join in this browser**.
4. Approve the browser in Android when it shows the same 6-digit code.

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
simultaneous unlocked tabs. Closing the page or 30 minutes of inactivity locks
it. On a trusted computer the person can keep the browser unlocked: the vault's
non-extractable AES key is stored in IndexedDB and opens the vault without the
passphrase while the account is signed in, with no idle lock. Signing out or
**Forget this browser** deletes that key. There is no device-key export/import or
server password recovery for this vault. A fresh invitation is needed if its passphrase is lost.

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
and paste its editor invite link. Write `approve.json` with the request ID and the
6-digit code the browser shows.
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

With server AI, **Read photo** performs the synthetic compatibility check and
then submits the prepared photo, without a separate model connection step.
Legacy direct browser models still require an explicit memory-only connection;
the operator must allow exact provider origins and the provider must support
browser CORS. No photo is sent by selection, preview or
capture alone. See [provider setup](../docs/providers.md). Camera permission is
requested only on **Take photo**; cancellation and failures stop the stream.

For isolated browser photo QA, pass `-PpreviewModelOrigin=http://localhost:18748`
to `server/scripts/preview-local` and run a synthetic provider at that loopback
origin. This fixture-only option rejects remote hosts and is absent from the
production startup path. Never use real photos, keys or households in the fixture.
