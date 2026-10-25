# Replay shortcut and guided tour verification, 2026-10-25

The Android list opens the usuals review through an icon beside History. Its
accessible name remains "Review your usuals". The shortcut appears only when
there are eligible purchases and stays out of typing and shopping mode. The
review sheet retains its title and existing controls.

The first-run tour has six steps: welcome, capture and repeats, imports,
shopping and history, usuals review, and account sharing. All five interface
languages have complete copy. Existing users can open it from Settings, Help,
Guided tour. New tours start at the welcome page, while an in-progress tour
keeps its page across activity recreation. Completing or skipping the tour
does not change privacy settings or require an account.

## Checks

- `scripts/dgradle :app:assembleDebug :app:assembleDebugAndroidTest test lintDebug --no-daemon --no-watch-fs --no-parallel --max-workers=2`
  passed. The active Android/JVM modules reported 1,337 tests, zero failures or
  errors, and six opt-in skips. This includes resource parity, tour navigation
  and saved progress, icon accessibility, and typing/shopping visibility.
- Native alignment passed for all 12 libraries in the debug APK.
- A disposable Android 16 / API 36 emulator ran offline at normal size in
  English and at 320 dp with 160% system text in dark mode. The latter tour used
  Italian. Screenshots were visually inspected.
- First-run QA traversed every page, finished, recreated the activity, reopened
  the tour through Settings, preserved a manually opened tour across recreation,
  and confirmed that another new tour starts at the welcome page.
- Replay QA used real Room and production adapters: icon entry, Add, Skip,
  safe Undo, hide, close/reopen, ten-item completion, more, concurrent addition,
  and switching lists. It passed with both display profiles.

The emulator checks are opt-in instrumentation tests. On a fresh disposable
install, select `TutorialDeviceTest` with `-e tourQa synthetic`. Select
`ReviewDeviceTest` with `-e replayQa synthetic`; its large-text profile also uses
`-e replayLargeText true -e replayDark true` and actual system font/density
settings. Both use the standard AndroidJUnitRunner.
