# Shopping stats

Open **History > Shopping stats** on Android or in the browser. The section
starts collapsed and uses only the selected list's saved shopping history.
Searching History or filtering the live list by store does not filter its stats.
Switching lists starts with a collapsed section for the newly selected list.

The three totals describe all saved shops, recorded purchase entries and
different item identities. An entry is one saved shopping row; quantities such
as litres, packs and individual pieces are not added together. Confirmed aliases
count as one different item. Imported shops count, and undoing a finished shop
removes its contribution. Current checked or unchecked rows do not count until
the shop is finished.

**Your recent usuals** shows up to five items from the last eight saved shops,
ordered by the number of distinct shops containing them, then latest purchase
and item key. Two entries for the same item in one shop count as one occurrence.
The displayed denominator is the number of available saved shops, up to eight.
The last purchase date is a recorded fact, not a prediction. Future-dated shops,
future purchase rows and rows without a saved shop do not contribute.

The calculations add no storage, database migration, dependency, telemetry or
network request. Android still works offline; the browser uses the account list
projection it already receives. Stats are recomputed from source history, so
they need no migration or separate clearing operation.

## Manual review ordering

Review keeps distinct-shop frequency as its primary rank. Equal-frequency
items now favor coverage of more recent shops: the newest of the available
eight shops has weight eight, the next seven, down to one (a shorter history
uses its actual size). Duplicate rows in a shop receive that shop's weight only
once. Equal coverage still prefers the older last purchase, then item key.

This is a modest ordering change to the existing explicit Add/Skip review.
Current rows, purchases within 24 hours, mute and snooze choices, repeat rules,
the early-history fallback and minimum distinct-shop count keep their existing
eligibility rules. Review does not add inferred quantities or automatic items.
No claim of improved prediction accuracy is made without real-history replay.
Inferred intervals remain disabled under the existing real-history replay gate
in [Development](development.md#local-suggestion-replay).

## Verification

`ShoppingStatsTest`, `ReviewRankerTest`, `HistoryViewModelTest` and
`QuietHistoryScreenTest` cover grouping, duplicate rows, aliases, future and
orphan rows, stable ordering, empty history, the eight-shop window, selected-list
changes, search independence and the collapsed disclosure. Browser equivalents
are in `web/test/shopping-stats.test.mjs`, including preserving an open disclosure
on refresh and clearing unavailable-list content.

`ShoppingStatsDeviceTest` is an opt-in production-adapter/Room/Compose scenario
using synthetic in-memory history. After the standard Android checks and an
Android test APK build, run it on a selected disposable emulator:

```sh
adb -s emulator-5554 shell am instrument -w -r \
  -e class io.theficos.usuals.ShoppingStatsDeviceTest -e statsQa synthetic \
  io.theficos.usuals.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Repeat with actual narrow display and large system font settings, adding
`-e statsLargeText true` for the dark-theme screenshot. This document describes
the checks; their execution results belong in the task's final verification
record.
