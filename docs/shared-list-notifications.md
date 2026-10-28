# Quiet shared-list notifications

## Plan and design

Shared activity is optional. Android Settings and browser Preferences expose
Notifications with a master switch and separate choices for items added and
shops finished. The master starts off. Turning it off clears displayed shared
activity; the existing weekly reminder remains a separate opt-in.

A server-authored activity entry records an accepted explicit addition that
creates a live row, or a finished shop with purchases. Quantity merges, edits,
automatic repeats, history imports and rejected operations do not create alerts.
The server filters out the viewer's own account, including their other devices.
Activity is bounded, encrypted at rest and contains no item text. Clients work
with older servers that omit activity: list sync continues, but shared alerts
require the updated server as well as the client.

Android groups fresh activity into one silent notification per list at the next
existing background sync, normally around 15 minutes and subject to Android's
battery scheduling. No new polling, Firebase, sounds, vibration or permission
prompt on launch. Permission is requested only when enabling alerts. Notification
text contains no list, person or item names. Opening the app clears shared alerts.

The browser offers dismissible in-app activity for other lists while its tab is
open and visible. It keeps the existing connection policy. It does not promise
background browser push or ask for browser notification permission.

Both clients consume activity while disabled and skip initial/reset sync,
newly encountered lists, archived or single-member lists and activity older than
24 hours. Freshness checks tolerate a server clock up to five minutes ahead.
Enable-time checks remain conservative when clocks differ. Enabling a category starts from that moment. Android suppresses all
activity while the app is visible; the browser suppresses the list being viewed
and catch-up after returning to the tab. Delivery favours missing an alert over
duplicating one. Settings are local to the device/browser and account.

## Implementation and verification

1. Add bounded, authenticated activity to existing server list responses and test
   its event semantics, account filtering, retries and undo.
2. Add Android persistence, consumption, silent delivery and explicit controls.
3. Add browser controls and quiet inline activity without replacing Undo/errors.
4. Run deterministic regression tests, the repository gates, emulator/browser
   checks and an independent review. Record any unavailable checks honestly.

## Verification, 2026-10-28

- `scripts/check-ci android` passed, including debug and release builds, tests,
  lint and alignment checks for all 12 native libraries. App suites passed
  420 debug and 305 release tests.
- `scripts/check-ci server` passed with disposable PostgreSQL: 117 server,
  43 sync, 34 model and 124 browser tests, plus the distribution build.
- History and staged secret scans passed.
- Both notification emulator tests passed: explicit master/category controls,
  actual silent delivery and removal after disabling. Affected instrumentation
  lint was rerun after making the cancellation assertion asynchronous.
- Android Settings and the weekly-reminder entry were checked visually.
  Browser checks passed at phone and desktop widths in light and dark themes.
- Independent review has no remaining blocking findings.

These are local checks with synthetic data. No server deployment, signed release
or remote publication was performed.
