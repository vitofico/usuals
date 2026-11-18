"""Hosted JVM slim control and retained functional mutation evidence."""

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
    expected_skips = {"enrich": 4, "recipe": 1}
    assert all(row["skipped"] == expected_skips.get(module, 0) for module, row in control.items()), control
    provider = "src/main/java/io/theficos/usuals/llm/OpenAiCompatibleClient.kt"
    budget = "src/main/java/io/theficos/usuals/llm/Budget.kt"
    barcode = "src/main/java/io/theficos/usuals/smart/BarcodeMemory.kt"
    allergen = "src/main/java/io/theficos/usuals/smart/AllergenAvoidance.kt"
    limiter = "src/main/java/io/theficos/usuals/enrich/OffRateLimiter.kt"
    off = "src/main/java/io/theficos/usuals/enrich/OpenFoodFactsEnricher.kt"
    ranges = "src/main/java/io/theficos/usuals/enrich/RestrictedCirculation.kt"
    provider_test = "provider probes send the supported structured output parameters"
    cases = (
        ("sync-color-no-guard", "sync", "src/main/kotlin/io/theficos/usuals/sync/Reducer.kt", '            if (p["expected"].stringOrNull() != ListAppearance.revision(state))\n                return conflict(p, state.preferences[ListAppearance.KEY] ?: JsonObject(emptyMap()), "list_color_stale")\n', "", "ListColorTest", "colorsAreDurableIndependentOfNamesAndGuardConcurrentChoices"),
        ("core-gtin-accepts-all-zero", "core/model", "src/main/java/io/theficos/usuals/model/Gtin.kt", "            if (ean13.all { it == '0' }) return null\n", "", "GtinTest", "invalid barcodes are rejected"),
        ("core-parse-name-lowercased", "core/parse", "src/main/java/io/theficos/usuals/parse/LocalUtteranceParser.kt", "name = name,", "name = name.lowercase(),", "LocalUtteranceParserTest", "parses the shapes people actually say"),
        ("core-todraft-drops-note", "core/parse", "src/main/java/io/theficos/usuals/parse/ItemDraft.kt", "note = note,", "note = null,", "ItemDraftTest", "parsed input becomes a row with the original text and a stable key"),
        ("smart-barcode-memory-recent-6", "smart", barcode, "const val RECENT = 5", "const val RECENT = 6", "BarcodeMemoryTest", "only the last five barcoded purchases vote, so a brand switch settles"),
        ("smart-allergen-trace-dup", "smart", allergen, "it in wanted && it !in contains", "it in wanted", "AllergenAvoidanceTest", "allergen and trace results retain the whole warning state"),
        ("llm-terra-max-tokens", "llm", provider, 'put(if (terra) "max_completion_tokens" else "max_tokens", maxTokens)', 'put("max_tokens", maxTokens)', "OpenAiCompatibleClientTest", provider_test),
        ("llm-anthropic-response-format", "llm", provider, 'if (config.kind != ProviderKind.ANTHROPIC) {\n                putJsonObject("response_format")', 'run {\n                putJsonObject("response_format")', "OpenAiCompatibleClientTest", provider_test),
        ("llm-budget-hourly", "llm", budget, "Math.floorDiv(clock(), MILLIS_PER_DAY)", "Math.floorDiv(clock(), 3_600_000L)", "ModelBudgetTest", "the spent allowance refills only at the next UTC midnight"),
        ("llm-budget-save-before-increment", "llm", budget, "saveUsage(ModelUsage(day, used + 1))", "saveUsage(ModelUsage(day, used))", "ModelBudgetTest", "saved usage survives reconstruction and ceiling changes do not refill it"),
        ("enrich-restricted-band-to-28", "enrich", ranges, "20..29", "20..28", "RestrictedCirculationTest", "restricted circulation covers the whole reserved prefix range"),
        ("enrich-limiter-wait-plus-one", "enrich", limiter, "return untilOldestExpires.coerceIn", "return (untilOldestExpires + 1).coerceIn", "OffRateLimiterTest", "a budget spent at the end of one window does not refill at the start of the next"),
        ("enrich-backoff-last-wins", "enrich", limiter, "blockedUntil.updateAndGet { current -> maxOf(current, until) }", "blockedUntil.set(until)", "OffRateLimiterTest", "the longest server backoff holds permits until its exact deadline"),
        ("enrich-malformed-tags-as-checked-none", "enrich", off, "if (kept.isEmpty() && array.isNotEmpty()) null else kept", "kept", "OpenFoodFactsEnricherTest", "absent empty and malformed tags retain their distinct meanings"),
    )
    errors = []
    for case in cases:
        try:
            mutate(*case)
        except (RuntimeError, AssertionError) as error:
            errors.append((case[0], repr(error)))
    assert not errors, errors
