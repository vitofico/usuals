# Privacy

Usuals works offline without an account. It contains no advertising, analytics,
crash reporting or Google Play Services. This policy covers the Android app,
optional self-hosted Usuals server and its browser client.

## On your Android device

Lists, purchase history, aliases and preferences are stored locally. Shopping
exports are readable files at the location you choose. Android cloud backup is
excluded; device transfer and exports exclude model credentials and the account
connection. Model credentials and the account connection use Android
Keystore-backed protection.

Camera access is requested for scanning and optional list-photo capture and microphone access for voice capture.
Barcode decoding runs on the device. Voice uses your selected Android speech
service, preferring on-device recognition when available. That service may send
audio to its provider; Usuals cannot attest its processing. Keyboard entry works
without microphone access. Optional shopping reminders use local notifications.

## Requests you choose

- Product lookup is off by default. Enabling Open Food Facts lookup sends the
  scanned barcode directly to Open Food Facts. When Open Food Facts answers that
  the barcode is not food, the same barcode is also sent to the sister database
  it names (Open Beauty Facts, Open Products Facts or Open Pet Food Facts, run by
  the same organisation) for a product name. Your IP address is visible to
  those services. Product data is cached locally and its source is credited.
- Sharing a recipe URL fetches that page directly from your device. The website
  sees the URL and connection metadata. Structured recipe parsing is local.
- Model assistance is optional and disabled by default. An explicit model action
  sends recipe text or a prepared photo and any configured API credential to your
  selected endpoint. Photo reading first sends a synthetic image compatibility
  check. Source photos are decoded locally, resized and re-encoded without source
  metadata. Selection and preview alone send no photo. Markdown parsing is local.
  Its operator's policies apply. A local endpoint label does not prove encryption
  or an absence of server-side storage.

Product lookups and recipe downloads go directly from the client to those sites.
With server AI enabled, explicit model inputs go through your selected Usuals
server to its displayed provider. That server can read these submissions and
handles provider credentials. Personal provider keys saved there are encrypted
with a separately managed server wrapping key. They do not enter shopping
exports or your lists. Provider changes require renewed confirmation.
The gateway does not persist inputs, photos or outputs. The server operator and
provider control their infrastructure and retention policies. In direct native
mode, the model key stays in Android Keystore and requests go directly to its
configured endpoint.

## Optional account and sharing

Lists on a phone that is not signed in never leave it. When you sign in to a
Usuals server, every list on the phone is saved to your account there, together
with its history, and stays in sync with the phone and the web app. The server
applies every change, so it can read your lists, and so can whoever runs it.
Choose a server you trust, run by you or by someone you know.

The server encrypts stored lists with a key kept outside the database. That
protects a copied database or backup; it does not protect against the running
server or its operator. Operators control their own retention, backups and
server access. The server also sees request timing, sizes and connection
metadata.

When you share a list, everyone you add can read and edit it, and add other
people. Choosing people shows the display names and email addresses of the other
enabled accounts on the same server, and they can see yours. Removing someone, or
leaving a list, stops that access and the app removes its copy at its next sync;
what a person already saw or copied cannot be taken back.

Signing out sends your pending changes, then removes your account's lists from the
phone; they stay in your account on the server. Deleting a list you own removes it
for everyone who has it.

Web accounts use an operator-configured SSO provider or standalone local
credentials. Account identity, sessions and connected phones are stored by that
operator. The browser keeps no copy of your lists: it shows them as the server
holds them. Browsers trust code delivered by their server, so a server that
changes that code can see whatever the page shows. The browser asks for the
camera only for **Use camera** in photo import and never uses the microphone.

## Control and reporting

Disable optional services in Settings. Export shopping data before deleting app
storage or changing devices. Disconnect a phone from **Account > Connected phones**
in the web app. Ask your chosen server operator about account removal, stored
lists and backup retention. This repository does not operate a paid hosting
service.

Report security issues using the [security policy](SECURITY.md).
See the [security model](docs/security-model.md) for the trust boundaries.

Browser photo import additionally requires an operator-approved model origin.
Model calls go directly from the browser, without account cookies or redirects.
The connection key and photo remain in memory only until the import dialog
closes; they are never stored on the server or in the browser.
The provider receives the photo pixels and network metadata when you explicitly
read it. A model may make mistakes, so every proposal needs review.
