## Abstract

**LOCAL DRAFT ONLY. Do not submit, reopen or post this draft.** The maintenance
requirement remains unmet. Reconsider no earlier than **2027-01-20**, with evidence
of ongoing maintenance since the public release on 2026-10-19 and a fresh policy,
release and distinctiveness review. Passing time alone is insufficient. No age
exception or automated submission is requested.

### Please introduce the functions and usage of your app in summary, and also attach some screenshots.

Usuals is an Android shopping list with local purchase history, per-item repeat
rules, history-ranked suggestions and quantity hints. It works without an account,
server or model. Checking items and choosing **Finish shop** records a dated trip;
Undo restores that shop. Optionally, signing in to a self-hosted Usuals server
keeps the lists in sync with its web app and lets accounts on that server share
lists with each other.

Recipe URLs are read on the device and ingredients require review. Open Food Facts
lookup is optional and suggested product names need confirmation. Keyboard input
works offline; voice depends on the selected Android speech service. English,
Spanish, Italian, French and German interfaces are included.

![Shopping list](https://raw.githubusercontent.com/vitofico/usuals/e2d097b71f14f4cc34d08ea08070d608225427ef/fastlane/metadata/android/en-US/images/phoneScreenshots/1.png)
![Product details](https://raw.githubusercontent.com/vitofico/usuals/e2d097b71f14f4cc34d08ea08070d608225427ef/fastlane/metadata/android/en-US/images/phoneScreenshots/2.png)


### Please elaborate why you wrote this app. Please highlight the unique functions it provides over other similar apps in F-Droid.

I wrote Usuals to retain a household's purchase memory on the phone and make
repeat shopping work before asking someone to run or sign in to a server. The
additional value proposed for review is this combined workflow:

- Completed shops become dated purchase facts with Undo. Rebuy hints use that
  local history, and scheduled repeats are choices for individual items.
- Suggestions can be added, snoozed or muted. Quantity hints require confirmation;
  an interval predictor remains disabled pending real-history replay evidence.
- Ingredient extraction from structured recipe pages and optional barcode naming
  happen directly on the device. Imported ingredients are reviewed against the
  current list and recent purchases before adding them.
- Sharing is optional and self-hosted. Signed in, the phone keeps an offline copy
  of the account's lists and syncs it with the server; the person picks who to
  share a list with by name. Android's local lists remain usable without any
  account.

These claims can be inspected in the released source:
[trip history](https://github.com/vitofico/usuals/blob/e2d097b71f14f4cc34d08ea08070d608225427ef/data/local/src/main/java/io/theficos/usuals/data/local/HistoryRepository.kt),
[repeat rules](https://github.com/vitofico/usuals/blob/e2d097b71f14f4cc34d08ea08070d608225427ef/smart/src/main/java/io/theficos/usuals/smart/RepeatPlanner.kt),
[suggestion ranking](https://github.com/vitofico/usuals/blob/e2d097b71f14f4cc34d08ea08070d608225427ef/smart/src/main/java/io/theficos/usuals/smart/SuggestionEngine.kt),
[quantity hints](https://github.com/vitofico/usuals/blob/e2d097b71f14f4cc34d08ea08070d608225427ef/smart/src/main/java/io/theficos/usuals/smart/QuantityHints.kt),
and [recipe extraction](https://github.com/vitofico/usuals/blob/e2d097b71f14f4cc34d08ea08070d608225427ef/recipe/src/main/java/io/theficos/usuals/recipe/RecipeFetcher.kt).
The pinned release still used the earlier encrypted household sharing; link the
server-held list sync from the future candidate instead.

Comparison checked on 2026-10-20 against the projects' published descriptions:

| Similar F-Droid application | Documented focus | Usuals workflow proposed for review |
|---|---|---|
| [ShoppingList](https://f-droid.org/packages/com.wbpxre150.shoppinglist/) | Offline lists, quantities/prices, reminders and file sharing. | Dated completed-shop history feeding per-item repeats, staple suggestions and confirmed quantity hints. |
| [KitchenOwl](https://f-droid.org/packages/com.tombursch.kitchenowl/) | Self-hosted grocery lists, recipes, meal planning and expenses; its [README](https://github.com/tombursch/kitchenowl) documents partial offline support. | Account-free Android purchase memory, with self-hosted list sync and sharing as an optional extension. |
| [Grocy Android](https://github.com/patzly/grocy-android) | A companion for an existing Grocy server, including inventory workflows and barcode processing. | A standalone shopping/purchase-history workflow that needs no inventory server. |

This comparison describes documented workflows, not an exhaustive audit or a claim
that any single feature is exclusive. Whether this combination adds sufficient
value remains F-Droid's review decision. The current unsubmitted QR and Markdown/
photo work is not claimed as part of the pinned 2026.10.19.1 binary. Refresh this
case against the actual future release and current alternatives before submitting.


## Checklist

### Policy

The overall inclusion-policy checkbox stays unchecked: age and distinctiveness
are still subject to review. I am the upstream author and authorize inclusion;
no third-party author permission is needed. Source uses Apache-2.0, with bundled
font/library notices. Optional proprietary model services are declared NonFreeNet.
No model key is bundled, and offline/local-model operation remains available.

* [ ] The app complies with the [inclusion criteria](https://f-droid.org/docs/Inclusion_Policy).
* [x] The original app author has been notified (and does not oppose the inclusion). If you are not the author, please paste the link of the reply from the author.
* [x] The upstream app source code repo contains the app metadata in a [Fastlane](https://gitlab.com/snippets/1895688) or [Triple-T](https://gitlab.com/snippets/1901490) folder structure. The summary and description must be included and images, icon, and changelog should also be provided for better user experience. The `en-US` locale must be included.

### Docs

* [x] Please read [the guide](https://gitlab.com/fdroid/fdroiddata/-/blob/master/CONTRIBUTING.md) first if this is your first contribution.
* [x] Please make sure your metadata follows the best practice in [our templates](https://gitlab.com/fdroid/fdroiddata/tree/master/templates).
* [x] Please read the [Build Metadata Reference](https://f-droid.org/docs/Build_Metadata_Reference/) and make sure your metadata is valid.
* [x] Please read the [Quick Start Guide](https://f-droid.org/en/docs/Submitting_to_F-Droid_Quick_Start_Guide/).

### Merge Request Setup

Planned title: `New app: Usuals`. The old request is [closed MR 53283](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/53283).
No replacement branch, MR or CI pipeline has been created. Check fork visibility,
branch protection, duplicate requests, current issues and the actual Changes tab
at the time of a separately authorized future submission.

* [x] The title of this merge request should follow "New app: app name" format.
* [ ] Please make sure your fdroiddata fork is public and your branch is not protected. See <https://docs.gitlab.com/user/project/repository/branches/protected/>.
* [ ] Please read [our Git guide](https://gitlab.com/fdroid/wiki/-/wikis/Tips-for-fdroiddata-contributors/Git-Usage) if you don't know how to rebase your branch. Don't rebase your branch if there is no conflict.
* [ ] All related [fdroiddata](https://gitlab.com/fdroid/fdroiddata/issues) and [RFP issues](https://gitlab.com/fdroid/rfp/issues) have been referenced in this merge request
* [ ] Please only submit one app in one MR.

### Metadata

The local reference belongs at `metadata/io.theficos.usuals.yml` in fdroiddata.
It retains the existing release, certificate and reproducible-build settings.
There are no srclibs or external source repositories/submodules. The published
universal APK was approximately 4 MiB, so ABI splits are not proposed. Before a
future MR, replace the reference with the latest verified signed release, retain
only that candidate and refresh screenshots/descriptions to its actual features.

* [x] Metadata must be put in `metadata/<applicationId>.yml`.
* [x] Metadata must be a valid YAML file.
* [x] Metadata must use LF as line ending.
* [ ] Don't add summary/description/changelog/images or anything that should be provided in upstream repo. Please check the Changes tab to make sure there is no other unrelated files added in the MR.
* [x] Releases are tagged and auto update is enabled unless there is a special reason.
* [x] There is an issue tracker and contact info of the author so that we can report bugs and contact the author.
* [x] An AuthorName must be added. It doesn't need to be the real name.
* [ ] External repos are added as git submodules instead of srclibs. You can update git submodules without opening an MR in this repo and the submodule is covered by our scanner.
* [x] Enable [Reproducible Builds](https://f-droid.org/docs/Reproducible_Builds). We'll use your signature for improved security/reliability, also allowing users to switch between different channels. Do note that if you don't enable reproducible build then the apk will be signed with our key so you can't enable it later. If you can't enable this, please add the reasons here.
* [ ] Setup abi split if the APK is large and the splitted ones can be much smaller.
* [ ] Only the latest versions should be kept in the metadata before it's merged. If you update the metadata, please replace the old versions with the new ones.
* [x] Don't add any disabled versions in the metadata.
* [x] The `commit` field should be the full hash. Please don't use tag or branch in commit.

### Pipeline

The old request passed nine technical jobs; that did not establish eligibility.
These boxes remain unchecked for any future request. Re-run official metadata
lint, a clean source build, developer-signature reproduction, emulator checks and
the independent F-Droid pipeline for the exact future release. Record or explain
all report findings. Never use the old green pipeline as a current result.

* [ ] All pipelines should pass.
* [ ] All warnings and errors in the Reports tab should be fixed or explained.
* [ ] F-Droid CI runners are under GitLab's FOSS program, so there's no need for you to pay for any CI time. If Gitlab starts asking for phone numbers or credit cards don't submit anything, just leave a note in the MR so we know we need to trigger the CI.

Template source: [F-Droid App inclusion.md](https://gitlab.com/fdroid/fdroiddata/-/blob/master/.gitlab/merge_request_templates/App%20inclusion.md),
retrieved 2026-10-20. Official section headings and checklist wording are retained.
Re-fetch the template before any future request. Preparation status and evidence
are tracked in the [local F-Droid notes](README.md).
