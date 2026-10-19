# Privacy

Usuals works offline without an account. It contains no advertising, analytics,
crash reporting or Google Play Services. This policy covers the Android app,
optional self-hosted relay and browser client.

## On your Android device

Lists, purchase history, aliases and preferences are stored locally. Shopping
exports are readable files at the location you choose. Android cloud backup is
excluded; device transfer and exports exclude model credentials and sync keys.
Model credentials and device keys use Android Keystore-backed protection.

Camera access is requested for scanning and microphone access for voice capture.
Barcode decoding runs on the device. Voice uses your selected Android speech
service, preferring on-device recognition when available. That service may send
audio to its provider; Usuals cannot attest its processing. Keyboard entry works
without microphone access. Optional shopping reminders use local notifications.

## Requests you choose

- Product lookup is off by default. Enabling Open Food Facts lookup sends the
  scanned barcode directly to Open Food Facts. Your IP address is visible to
  that service. Product data is cached locally and its source is credited.
- Sharing a recipe URL fetches that page directly from your device. The website
  sees the URL and connection metadata. Structured recipe parsing is local.
- Model assistance is optional and disabled by default. An explicit model action
  sends recipe text and any configured API credential to your selected endpoint.
  Its operator's policies apply. A local endpoint label does not prove encryption
  or an absence of server-side storage.

No relay proxies product lookups, recipe downloads or model requests.

## Optional sharing

Only lists you choose to share are synchronized. Approved devices hold their
encryption keys; the relay stores encrypted shopping content and signed
membership/history records. It necessarily sees routing identifiers, membership,
request timing, sizes and connection metadata. Operators control their own
retention, backups and server access. Removing a device prevents future access
after key rotation; it cannot erase data the device has already received.

Web accounts use an operator-configured SSO provider or standalone local
credentials. Account identity and sessions are stored by that operator. Signing
in does not grant list keys. Household creation and invitations establish
membership separately. Save the encrypted recovery kit and its key separately.

The browser encrypts its local keys and saved shopping state under a separate
passphrase, and locks on leaving the page or after inactivity. Browsers trust
code delivered by their server; a malicious server can change that code. The
independently installed Android app has the stronger operator boundary.

## Control and reporting

Disable optional services in Settings. Export shopping data before deleting app
storage or changing devices. A browser passphrase cannot be recovered by the
operator. Ask your chosen server operator about account removal, stored metadata
and backup retention. This repository does not operate a paid hosting service.

Report security issues using the [security policy](SECURITY.md).
See the [security model](docs/security-model.md) for protocol limits.
