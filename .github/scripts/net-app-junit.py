from pathlib import Path
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET

APP = "io.theficos.usuals."
SOURCE = Path("app/src/main/java/io/theficos/usuals")
RESULTS = Path("app/build/test-results/testDebugUnitTest")
OUTPUT = Path("net-app-junit")
OUTPUT.mkdir(exist_ok=True)

HISTORY = APP + "HistoryDeletionRoomScreenTest.cancelAndConfirmDeleteChangeOnlyTheSelectedShop"
FINISH = APP + "ShoppingFinishRoomScreenTest.finishUsesDisplayedVersionsAndUndoRestoresOnlyTheCompletedRow"
ROSTER = APP + "di.RepositoryListDirectoryTest.soloJoinedAndOwnedListsKeepTheirSharingLabels"
SCAN = APP + "di.RepositoryScanStoreTest.rescanUsesTheLatestPurchasedSpellingAfterTheActiveRowIsGone"
REMOVE = APP + "ui.list.ListViewModelTest.repeated_remove_while_the_first_list_is_being_deleted_keeps_the_next_list"
CONTROLS = [
    HISTORY, FINISH, ROSTER, SCAN, REMOVE,
    APP + "wiring.HistoryDeletionWiringTest.offlineListKeepsHistoryDeletion",
    APP + "wiring.HistoryDeletionWiringTest.syncedListWithoutRelayCapabilityPreservesHistory",
    APP + "di.RepositoryScanStoreTest.a category the person chose outranks the lookup's guess",
]

MUTANTS = [
    ("cancel-keeps-dialog-open", HISTORY, "ui/history/HistoryScreen.kt",
     "dismissButton = { TextButton(onClick = { deletingTrip = null })",
     "dismissButton = { TextButton(onClick = { Unit })"),
    ("finish-ignores-displayed-versions", FINISH, "ui/list/ListScreen.kt",
     "vm.onFinishShop(ids, rows.mapNotNull { it.version }.takeIf { it.size == rows.size })",
     "vm.onFinishShop(ids)"),
    ("finish-view-model-ignores-displayed-versions", FINISH, "ui/list/ListViewModel.kt",
     "if (versions == null) historyStore.finish(onlyIds) else historyStore.finishVersions(versions, onlyIds)",
     "historyStore.finish(onlyIds)"),
    ("duplicate-list-removal-not-blocked", REMOVE, "ui/list/ListViewModel.kt",
     "if (screen.value.busy || screen.value.matchingBusy || captureLock.isLocked || screen.value.capture != CaptureState.Idle) return",
     "if (screen.value.matchingBusy || captureLock.isLocked || screen.value.capture != CaptureState.Idle) return"),
    ("solo-list-marked-shared", ROSTER, "di/RepositoryListDirectory.kt", "people(list) > 1", "people(list) > 0"),
    ("oldest-purchased-spelling", SCAN, "di/RepositoryScanStore.kt",
     ".maxByOrNull { it.boughtAt }", ".minByOrNull { it.boughtAt }"),
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
