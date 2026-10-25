# Receipt photos and Wallet verification, 2026-10-25

History can attach one receipt photo to a finished shop, or import a receipt
photo as reviewed purchases. Reading uses the configured image-capable model
only after an explicit request. Manual entry remains available without a model.
The date and item lines are editable before saving. Adding a photo to an existing
shop does not duplicate purchases, and removing the photo preserves its history.

Prepared JPEG copies stay private to the phone, scoped to their list and trip.
They retain corrected orientation but discard source metadata. They are included
in shopping-data archive version 4 and direct device transfers, and can be
exported individually. List sync does not carry photos. Account archive photos
follow the restored independent copy. Earlier archives remain supported.

After a successful Finish shop started in shopping mode, the completion card
offers Open Wallet alongside Undo and Add receipt. Android's app picker remembers
the chosen launcher on this phone. Usuals opens it without passing shopping data
or initiating a payment. The six-step tour introduces both features in all five
interface languages; Replay keeps its accessible icon-only shortcut.

## Automated checks

- `scripts/dgradle :app:assembleDebug :app:assembleDebugAndroidTest test lintDebug --no-daemon --no-watch-fs --no-parallel --max-workers=2`
  passed. Active Android/JVM modules reported 1,368 tests, zero failures or errors,
  and six opt-in skips. Android test fixture changes were subsequently rebuilt
  with `:app:assembleDebugAndroidTest lintDebug`.
- Tests cover reviewed history-only imports, selected-list changes, attachment
  without duplicate purchases, manual entry, invalid dates, model connection and
  budget gates, archive round trips and validation, copied account lists, private
  file isolation, and recovery after an interrupted atomic photo replacement.
- A production list layout regression checks that Undo, Add receipt and Wallet
  remain reachable at 320 dp with 160% text. The shopping-mode header wraps its
  controls and the empty-list hero stays hidden while the completion card is open.
- All 12 native libraries passed the debug APK alignment check. No Room schema
  or dependency change was needed.

## Emulator checks

A disposable Android 16 / API 36 emulator ran offline in normal English and at
320 dp with actual 160% system font size in dark mode. Screenshots were inspected.

`ReceiptWalletDeviceTest`, enabled with `-e receiptWalletQa synthetic`, exercises
the production UI and adapters with real Room, private files, Android's launcher
picker and document picker. It finishes a shop, chooses and opens an external
test Wallet activity, returns to attach a photo, imports another receipt, edits
the date and extracted items, saves history, exports and restores its archive,
opens the full-size photo, then removes the original photo while retaining
purchases. The selected document is checked against the exact fixture bytes.

The native test waits for the document row's bounds to settle during directory
navigation. It does not retry a failed action or bypass the picker callback.
The receipt and model response are synthetic; this verifies the integration,
not real-world recognition accuracy, a provider's availability or payments.
Model HTTP contract tests use a local test server. Server AI requires updating
the Usuals server along with the app to receive the receipt prompt.

`TutorialDeviceTest`, enabled with `-e tourQa synthetic` on a fresh disposable
install, traverses all six pages, finishes and recreates the activity, reopens
through Settings, preserves an active page across recreation, and checks that
another requested tour starts fresh. The large-text tour uses Italian.

`ReviewDeviceTest`, enabled with `-e replayQa synthetic`, repeats the complete
Replay regression in both display profiles: icon entry, Add, Skip, Undo, hide,
close/reopen, ten-item completion, another round, concurrent additions and list
switching. The large-text profile also sets `-e replayLargeText true` and
`-e replayDark true`.
