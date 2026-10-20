# F-Droid preparation, paused

Status on 2026-10-20: local corrections only. Do not reopen, comment on or replace
[MR 53283](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/53283), push a fork
branch or trigger F-Droid CI. Vito explicitly requested waiting three months and
no resubmission. No reminder, scheduled submission or age exception is configured.

The maintainer closed the request on 2026-10-19 for its template, maintenance age
and lack of unique features. A successful technical pipeline did not override
those review findings.

| Feedback | Local correction | Remaining gate |
|---|---|---|
| MR template was not followed | [Draft](merge-request-draft.md) preserves the official App inclusion headings, checklist wording and section order. Unmet policy, future MR setup and future pipeline checks remain unchecked. | Re-fetch the then-current template and fill only verified answers when a new request is separately authorized. |
| More than three months of maintenance | Use the first public source/APK release on 2026-10-19 as a conservative documented baseline. Reconsider no earlier than **2027-01-20**. | Show continued maintenance during that period. A date alone is insufficient. Do not backdate history or automatically submit on that date. |
| Insufficient unique features | Draft explains the implemented purchase-memory, confirmed quantity/rebuy workflow and optional encrypted sharing, with released-source links and comparisons to ShoppingList, KitchenOwl and Grocy Android. | F-Droid decides whether that adds sufficient value. Refresh comparisons and demonstrate the actual future candidate. |

## Metadata correction

[io.theficos.usuals.yml](metadata/io.theficos.usuals.yml) now pins the full
`2968d12f300ecf6c075c789e51b7984ae9d900a6` source hash of `v2026.10.19.1`,
instead of using a tag in `commit`. Its version code, binary URL, public signing
certificate, NonFreeNet declaration and unsigned-build settings are retained.
Maintainer notes explain that this is a reference for the existing release.

At any future submission, replace this reference with the latest verified signed
release and keep only that candidate. Unreleased QR and Markdown/photo features
must not be advertised as part of the older APK. No published APK, source tag,
release asset or signing key is changed by this preparation.

## Reviewer evidence

The draft links feature code at the exact released source hash and includes two
screenshots from that release. Its comparisons describe what the other projects
publish, without claiming that familiar list, reminder, barcode or recipe features
are exclusive to Usuals. The proposed extra value is the combined local purchase
memory and optional encrypted-household workflow.

For a short manual check of the pinned release:

1. Start Android without an account, server or model. Add a quantity-bearing item,
   check it and choose **Finish shop**. Inspect the trip and exercise Undo.
2. Set an explicit per-item repeat. Inspect the schedule and suggestion controls.
   The deterministic repeat/suggestion/quantity tests provide dated-history cases;
   interval predictions remain disabled and are not an advertised working feature.
3. Share a recipe URL containing Recipe JSON-LD. Inspect the ingredient review and
   existing-item exclusion before adding. Models and product lookup stay optional.
4. With a separately prepared disposable relay and two devices, inspect signed
   device approval and an encrypted shared list. Android local lists still work
   without signing in. This is independent of the no-server check above.

Before a future request, record current build/reproduction results, screenshots
and a demonstration against the actual candidate. The closed request's nine green
jobs are historical evidence for its old release, not future CI results.

## Validation for this correction

Run release-safety checks through `scripts/dgradle verifyReleaseTools`. Use the
official `fdroid lint --format io.theficos.usuals` against a disposable copy of the
metadata and verify the pinned hash against the local immutable release tag,
version properties and certificate pin. Check all draft source/screenshot links
against that commit and preserve the official template headings/checklist wording.

This correction changes distribution metadata and documentation only. Android
source, resources and APK inputs are unchanged, so the completed Android build,
904-test gate and 16 KB emulator checks from the import implementation remain
applicable. New F-Droid inclusion/reproducibility CI must wait for a future request.

References checked 2026-10-20:

- [Official App inclusion template](https://gitlab.com/fdroid/fdroiddata/-/blob/master/.gitlab/merge_request_templates/App%20inclusion.md)
- [Contribution guide](https://gitlab.com/fdroid/fdroiddata/-/blob/master/CONTRIBUTING.md)
- [Inclusion policy](https://f-droid.org/en/docs/Inclusion_Policy/)
- [Build metadata reference](https://f-droid.org/en/docs/Build_Metadata_Reference/)
- [Quick start guide](https://f-droid.org/en/docs/Submitting_to_F-Droid_Quick_Start_Guide/)
