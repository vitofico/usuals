from pathlib import Path
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET

APP = "io.theficos.usuals."
SOURCE = Path("app/src/main/java/io/theficos/usuals")
RESULTS = Path("app/build/test-results/testDebugUnitTest")
OUTPUT = Path("slim-app-junit")
OUTPUT.mkdir(exist_ok=True)

AUTO = APP + "ui.list.MemoryViewModelTest.accepting a completion keeps the typed quantity and clears only the draft"
FILTER = APP + "ui.list.MemoryViewModelTest.favourites already present are detected outside the visible store filter"
SHARE = APP + "wiring.ShareIngestionTest.readsTextAndConnectIntentOnceAcrossRecreation"
SWIPE = APP + "SwipeRemovalScreenTest.swipeUndoAndDismissal"
SYNC = APP + "SyncIndicatorScreenTest.statusChangesKeepListInPlaceAndDetailsRemainAccessible"
RESET = APP + "di.ServiceLocatorResetTest.reset_replaces_the_graph_and_closes_the_old_database"
CONTROLS = [
    AUTO, FILTER, SHARE, SWIPE, SYNC, RESET,
    APP + "AccountIdentityScreenTest.settingsShowTheCurrentPersonAndServerAndForgetThemOnSignOut",
    APP + "AccountIdentityScreenTest.accountWithoutAHumanReadableNameShowsTheServer",
    APP + "AccountIdentityScreenTest.accountPageKeepsCachedIdentityVisibleWhileOfflineWithoutAnyLists",
    APP + "NotificationsScreenTest.controlsStartOffAndEachCategoryAndMasterCanBeDisabled",
    APP + "wiring.SpeechFailureTest.a_press_too_short_to_get_going_is_not_a_broken_microphone",
    APP + "wiring.SpeechFailureTest.every_other_code_is_classified_by_capture_and_not_here",
]

MUTANTS = [
    ("reset-retains-cached-graph", RESET, "di/ServiceLocator.kt",
     "resettables.forEach { it.clear() }", "Unit"),
    ("completion-drops-preceding-item", AUTO, "ui/list/ListViewModel.kt",
     "val drafts = parsed.dropLast(1) + selected", "val drafts = listOf(selected)"),
    ("store-filter-stays-selected", FILTER, "ui/list/ListViewModel.kt",
     "fun onStoreFilter(store: String?) { screen.update",
     "fun onStoreFilter(store: String?) { if (store == null) return; screen.update"),
    ("markdown-note-gets-dropped", SHARE, "MainActivity.kt",
     'setOf("text/plain", "text/markdown", "text/x-markdown")',
     'setOf("text/plain", "text/markdown")'),
    ("undo-never-restores-the-row", SWIPE, "ui/list/ListScreen.kt",
     "currentActions.onUndoSwipeRemoval(removal.token)", "Unit"),
    ("pending-status-shifts-the-list", SYNC, "ui/list/NamedListPicker.kt",
     "    if (syncInfo) AlertDialog", "    if (directory.pending > 0) Spacer(Modifier.height(16.dp))\n    if (syncInfo) AlertDialog"),
]

def run(label, selectors, failed_selector=None):
    shutil.rmtree(RESULTS, ignore_errors=True)
    destination = OUTPUT / label
    destination.mkdir()
    command = ["scripts/dgradle", ":app:testDebugUnitTest", "--max-workers=4"]
    for selector in selectors:
        command.extend(("--tests", selector))
    started = time.monotonic()
    with (destination / "gradle.log").open("w") as log:
        completed = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=900)
    reports = list(RESULTS.glob("TEST-*.xml"))
    for report in reports:
        shutil.copy2(report, destination)
    cases = {}
    failures = set()
    errors = set()
    skips = set()
    for report in reports:
        for case in ET.parse(report).getroot().iter("testcase"):
            name = case.attrib["classname"] + "." + case.attrib["name"]
            if name.endswith("()"):
                name = name[:-2]
            if name in cases:
                raise AssertionError(f"{label}: duplicate test {name}")
            cases[name] = case
            for tag, bucket in (("failure", failures), ("error", errors), ("skipped", skips)):
                if case.find(tag) is not None:
                    bucket.add(name)
    print(f"{label}: {len(cases)} tests, {len(failures)} failures, {len(errors)} errors, "
          f"{len(skips)} skips in {time.monotonic() - started:.2f}s", flush=True)
    if set(cases) != set(selectors) or errors or skips:
        raise AssertionError(f"{label}: missing={set(selectors) - cases.keys()} "
                             f"extra={cases.keys() - set(selectors)} errors={errors} skips={skips}")
    if failed_selector is None:
        if completed.returncode or failures:
            raise AssertionError(f"{label}: control failed: {failures}")
    else:
        failure = cases[failed_selector].find("failure")
        if (completed.returncode == 0 or failures != {failed_selector} or failure is None
                or failure.attrib.get("type", "") not in {
                    "java.lang.AssertionError",
                    "org.junit.ComparisonFailure",
                    "com.google.common.truth.AssertionErrorWithFacts",
                    "com.google.common.truth.ComparisonFailureWithFacts",
                }):
            raise AssertionError(f"{label}: expected assertion failure in {failed_selector}")


run("control", CONTROLS)
for label, selector, relative, before, after in MUTANTS:
    source = SOURCE / relative
    original = source.read_text()
    if original.count(before) != 1:
        raise AssertionError(f"{label}: expected one matching source anchor")
    try:
        source.write_text(original.replace(before, after, 1))
        run(label, [selector], selector)
    finally:
        source.write_text(original)
run("restored", CONTROLS)
