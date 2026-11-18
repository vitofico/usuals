"""Hosted JVM control and isolated functional mutation evidence."""

import json
from pathlib import Path
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET

MODULES = ("core/model", "core/parse", "sync", "smart", "enrich", "llm", "recipe")
RESULTS = Path("usuals-jvm-evidence")
RESULTS.mkdir(exist_ok=True)


def run(label, modules, selector=None, failed_test=None):
    args = ["scripts/dgradle", "--no-daemon", "--no-build-cache", "--continue"] + [
        f":{module.replace('/', ':')}:test" for module in modules
    ]
    if selector:
        args += ["--tests", selector]
    roots = {module: Path(module, "build/test-results/test") for module in modules}
    output = RESULTS / label
    output.mkdir()
    start = time.monotonic()
    started_ns = time.time_ns()
    with (output / "gradle.log").open("w") as log:
        status = subprocess.run(args, stdout=log, stderr=subprocess.STDOUT).returncode
    summary = {}
    for module, source in roots.items():
        reports = sorted(report for report in source.glob("TEST-*.xml") if report.stat().st_mtime_ns >= started_ns)
        if not reports:
            raise RuntimeError(f"{label}: no JUnit reports for {module}; see {output}/gradle.log")
        counts = {key: 0 for key in ("tests", "failures", "errors", "skipped")}
        failures = []
        target = output / module
        target.mkdir(parents=True)
        for report in reports:
            shutil.copy2(report, target / report.name)
            suite = ET.parse(report).getroot()
            for key in counts:
                counts[key] += int(suite.get(key, "0"))
            failures.extend(
                case.get("name") for case in suite.iter("testcase") if case.find("failure") is not None
            )
        summary[module] = dict(counts, failed_tests=failures)
    record = dict(elapsed_seconds=round(time.monotonic() - start, 2), status=status, modules=summary)
    (output / "summary.json").write_text(json.dumps(record, indent=2))
    print(label, json.dumps(record), flush=True)
    if failed_test:
        row = summary[modules[0]]
        assert status != 0 and row["tests"] and row["errors"] == row["skipped"] == 0
        assert row["failures"] == 1 and row["failed_tests"] == [failed_test], (label, row)
    else:
        assert status == 0 and all(r["tests"] and r["failures"] == r["errors"] == 0 for r in summary.values())
    return summary


def mutate(case, module, path, old, new, test_class, test_name):
    source = Path(module, path)
    original = source.read_text()
    assert original.count(old) == 1, (case, old)
    source.write_text(original.replace(old, new))
    try:
        return run(case, (module,), f"*{test_class}.{test_name}", test_name)
    finally:
        source.write_text(original)


if __name__ == "__main__":
    control = run("control", MODULES)
    # These opt-in third-party tests were already skipped on main.
    expected_skips = {"enrich": 4, "recipe": 2}
    assert all(row["skipped"] == expected_skips.get(module, 0) for module, row in control.items()), control
    reducer = "src/main/kotlin/io/theficos/usuals/sync/Reducer.kt"
    hints = "src/main/java/io/theficos/usuals/smart/QuantityHints.kt"
    ingredients = "src/main/java/io/theficos/usuals/recipe/MarkdownIngredients.kt"
    fetcher = "src/main/java/io/theficos/usuals/recipe/RecipeFetcher.kt"
    provider = "src/main/java/io/theficos/usuals/llm/OpenAiCompatibleClient.kt"
    relay = "src/main/java/io/theficos/usuals/llm/ServerLlmClient.kt"
    fixture = "reviewedScenariosConvergeAcrossReplayAndRestart"
    hint_test = "quantity agreement considers only the last five and never fills a missing value"
    relay_test = "relay errors and a response with too many items use the client contract"
    cases = (
        ("sync-capture-merge-no-unit-factor", "sync", reducer, "amount + incoming * factor", "amount + incoming", "ReducerTest", fixture),
        ("sync-capture-merge-unit-from-incoming", "sync", reducer, 'if (amount == null) p.getValue("unit") else existing.facts.getValue("unit")', 'p.getValue("unit")', "ReducerTest", fixture),
        ("sync-capture-merge-drops-gtin", "sync", reducer, 'listOf("note", "gtin", "store")', 'listOf("note", "store")', "ReducerTest", fixture),
        ("sync-rejected-partial", "sync", reducer, 'before.copy(rejected = before.rejected + (intent.id to "invalid"))', 'work.state.copy(rejected = before.rejected + (intent.id to "invalid"))', "ReducerTest", fixture),
        ("sync-deletetrip-all-purchases", "sync", reducer, 'state.purchases.filterValues { it.text("tripId") != trip }', "emptyMap()", "HistoryDeletionTest", "deletionRemovesTheWholeShopWithoutRestoringOrChangingRows"),
        ("sync-finish-ignores-deleted-trips", "sync", reducer, "            if (HistoryDeletion.contains(state, trip)) return Outcome.NO_OP\n", "", "HistoryDeletionTest", "deletionSurvivesSnapshotRestartAndTheResendHorizon"),
        ("smart-quantity-hints-window-6", "smart", hints, ".take(5)", ".take(6)", "AssistanceTest", hint_test),
        ("smart-quantity-hints-key-ignores-aliases", "smart", hints, "ItemIdentity.purchaseKey(it, aliases)", "ItemIdentity.purchaseKey(it, emptyMap())", "AssistanceTest", hint_test),
        ("recipe-429-not-blocked", "recipe", fetcher, "setOf(401, 403, 429)", "setOf(401, 403)", "RecipeTest", "fetch distinguishes blocked pages non html and prose without a model"),
        ("recipe-markdown-max-items-edge", "recipe", ingredients, "lines.size <= MAX_ITEMS", "lines.size < MAX_ITEMS", "MarkdownIngredientsTest", "exactLengthAndItemLimitsAreAcceptedWhole"),
        ("llm-server-error-swallowed", "llm", provider, "if (failOnError && outcome is ChatOutcome.Failure) throw ModelCallException(outcome.problem)\n        val text", "val text", "OpenAiCompatibleClientTest", "server extraction reports HTTP errors and non JSON replies"),
        ("llm-zero-amount-accepted", "llm", provider, "it.isFinite() && it > 0.0", "it.isFinite() && it >= 0.0", "OpenAiCompatibleClientTest", "rubbish rows are dropped rather than carried onto the list"),
        ("llm-serverclient-no-item-cap", "llm", relay, "        require(reply.items.size <= 50)\n", "", "ServerLlmClientTest", relay_test),
        ("llm-serverclient-budget-as-unreachable", "llm", relay, '"ai_budget" -> ModelProblem.Kind.BUDGET_EXHAUSTED', '"ai_budget" -> ModelProblem.Kind.UNREACHABLE', "ServerLlmClientTest", relay_test),
    )
    errors = []
    for case in cases:
        try:
            mutate(*case)
        except (RuntimeError, AssertionError) as error:
            errors.append((case[0], repr(error)))
    assert not errors, errors
