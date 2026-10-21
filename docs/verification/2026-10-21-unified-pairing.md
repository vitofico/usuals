# One-scan pairing verification

Branch `feat/unified-pairing`, rebased on `78532ad`.

Android has one Sharing screen for server sign-in, household creation and
invitations. Invites use pairing v2: a link key only the two phones know, a
descriptor checksum in the link, a join MAC the owner verifies, matching
six-digit codes and one-tap approval. Invites work once and expire after 24
hours. Server migration 006 adds the descriptor and MAC columns.

## Checks

- Full Docker Android gate passed: 1,012 test executions with five
  intentional skips, release tools, lint and
  debug/release APK builds. Native 16 KB alignment passed.
- The disposable PostgreSQL gate passed: 89 server tests, 149 browser
  tests and browser/WebCrypto to JVM/Tink interoperability, including a browser
  pairing v2 join request, MAC and code verified by the native codecs.
- Independent review (Codex) found no critical issues and four important ones.
  Three are fixed test-first: an approval whose response was lost no longer
  leaves the invite able to approve a second request (Kotlin and web); both
  clients refuse a relay-reported expiry beyond 24 hours plus five minutes; a
  joining phone can stop waiting after a denial or expiry. The fourth, a
  browser sign-in from the join page dropping the in-memory invite, is answered
  in the copy ("open the invite link again"); a full resume is deferred.
- Two dedicated emulators ran against `server/scripts/preview-local` with
  synthetic accounts: server sign-in with codes matching the browser's
  fingerprint, household draft after approval with the browser in front, a
  recovery file written, read back and verified, household creation, the join
  page with the new copy and the key removed from the address bar, its Open in
  Usuals link joining on the second phone, matching join codes (938 841),
  one-tap approval from the open invite sheet, automatic completion on the
  joining phone, and a shared list edited from both phones.
- The emulator run found two defects, both fixed test-first: the browser's
  Return to Usuals link reset the screen and lost the drafted household, and
  the stored-key sentence did not tick its checkbox.
- A pre-existing wiring-test leak (a view model nothing cleared, watching a
  closed database) caused intermittent `UncaughtExceptionsBeforeTest`; fixed.

Limits: optical QR scanning was not exercised (the sharing screen blocks
screenshots, and the emulators have no camera feed). Emulator Chrome was not
taken through its first-run terms, so the join page was checked in a desktop
browser and its link fired on the phone; the operator's browser approval used
the same HTTP endpoints as the web page.
