"""Hosted narrow JVM controls and synthetic local-library mutations."""

from dataclasses import dataclass
import os
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
BASE = "f19062f"
LOCAL = "data/local"
AUTH = "auth"
LOCAL_PACKAGE = "io.theficos.usuals.data.local"
AUTH_PACKAGE = "io.theficos.usuals.auth"


@dataclass(frozen=True)
class Case:
    label: str
    module: str
    package: str
    target: str
    source: str
    before: str
    after: str
    behavior: str
    compare_baseline: bool = False


CASES = [
    Case("removed-stale-seq", LOCAL, LOCAL_PACKAGE, "SyncedListReplicaTest", "LocalReplica.kt",
         "it.synced && seq >= it.seq", "it.synced", "removedEntriesDeleteLocallyAndTheHigherSeqWins", True),
    Case("drop-keeps-prefs", LOCAL, LOCAL_PACKAGE, "SyncedListReplicaTest", "LocalReplica.kt",
         '"trip", "item_pref", "local_item_pref", "item_alias"',
         '"trip", "item_alias"', "droppingOneListDeletesOnlyItsScopedFactsAndKeepsASelection", True),
    Case("prune-lt", LOCAL, LOCAL_PACKAGE, "SyncedListReplicaTest", "SyncDao.kt",
         "ordinal <= (SELECT MAX(ordinal)", "ordinal < (SELECT MAX(ordinal)",
         "settledIntentsBeyondTheResendHorizonArePrunedUnlessAConflictNamesThem"),
    Case("deletetrip-archived", LOCAL, LOCAL_PACKAGE, "HistoryRepositoryTest", "HistoryRepository.kt",
         'listId() != scope || database.listDao().find(scope)?.archived != false', 'listId() != scope',
         "only the displayed unarchived list can delete a trip", True),
    Case("fortnight-7days", LOCAL, LOCAL_PACKAGE, "ItemRepositoryTest", "ItemRepository.kt",
         'FORTNIGHT_MILLIS: Long = 14L *', 'FORTNIGHT_MILLIS: Long = 7L *',
         "reap drops tombstones past the fortnight and keeps everything else"),
    Case("transfer-no-rejected", LOCAL, LOCAL_PACKAGE, "ItemTransferTest", "ItemRepository.kt",
         ' && it.id !in confirmed.rejected', '',
         "rejectedReceiptWithAnExistingDestinationCopyKeepsSource", True),
    Case("vault-reset-any-error", AUTH, AUTH_PACKAGE, "SyncVaultTest", "SyncVault.kt",
         '            if (problem.problem !in setOf(VaultProblem.KEY_UNAVAILABLE, VaultProblem.CORRUPT)) throw problem\n',
         '', "resetRejectsWriteFailuresAndOnlyResetsUnavailableState"),
]

HELD = [
    "prune-unscoped: the ordinary cross-list bystander is asserted; the unscoped delete is not applied.",
    "deletetrip-no-scope-guard: the ordinary wrong-list cases are asserted; the scope guard is not removed.",
    "vault-cbc: existing user data is never re-encrypted and the application’s encryption mode is not downgraded.",
]


def run(root_out, label, module, package, targets):
    directory = root_out / label
    directory.mkdir()
    reports = ROOT / module / "build/test-results/testDebugUnitTest"
    relative_task = ":" + module.replace("/", ":") + ":testDebugUnitTest"
    command = [str(ROOT / "scripts/dgradle"), relative_task]
    for target in targets:
        (reports / f"TEST-{package}.{target}.xml").unlink(missing_ok=True)
        command += ["--tests", f"{package}.{target}"]
    command += ["--rerun", "--no-build-cache", "--no-daemon", "--no-watch-fs", "--max-workers=2"]
    with (directory / "gradle.log").open("wb") as stream:
        result = subprocess.run(
            command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT, timeout=420
        )
    roots = []
    for target in targets:
        path = reports / f"TEST-{package}.{target}.xml"
        if not path.is_file():
            raise RuntimeError(f"{label}: expected fresh {path.name}; Gradle exit {result.returncode}")
        shutil.copy2(path, directory)
        roots.append(ET.parse(path).getroot())
    errors = sum(int(root.attrib["errors"]) for root in roots)
    skips = sum(int(root.attrib["skipped"]) for root in roots)
    failures = [
        (entry.attrib["name"], failure.attrib.get("type", ""), failure.attrib.get("message", ""))
        for root in roots
        for entry in root.iter("testcase")
        for failure in entry.findall("failure")
    ]
    if errors or skips or (bool(result.returncode) != bool(failures)):
        raise RuntimeError(f"{label}: unexpected exit {result.returncode}, errors {errors}, skips {skips}")
    tests = sum(int(root.attrib["tests"]) for root in roots)
    print(f"{label}: {tests} tests, {len(failures)} failures, {errors} errors, {skips} skips", flush=True)
    return failures


def paths(case):
    package_path = case.package.replace(".", "/")
    source = ROOT / case.module / "src/main/java" / package_path / case.source
    tests = ROOT / case.module / "src/test/java" / package_path / (case.target + ".kt")
    return source, tests


def main(out):
    out.mkdir(parents=True, exist_ok=True)
    print(*[f"HELD {reason}" for reason in HELD], sep="\n", flush=True)
    controls = [
        (LOCAL, LOCAL_PACKAGE, ("SyncedListReplicaTest", "HistoryRepositoryTest", "ItemRepositoryTest", "ItemTransferTest")),
        (AUTH, AUTH_PACKAGE, ("KeystoreSecretStoreTest", "SyncVaultTest")),
    ]
    for module, package, targets in controls:
        if run(out, f"control-{module.replace('/', '-')}", module, package, targets):
            raise RuntimeError(f"Clean {module} control failed")
    for case in CASES:
        source, tests = paths(case)
        untouched = source.read_bytes()
        original_tests = tests.read_bytes()
        old = case.before.encode()
        if untouched.count(old) != 1:
            raise RuntimeError(f"{case.label}: mutation anchor is missing or ambiguous")
        if case.compare_baseline:
            baseline_tests = subprocess.check_output(
                ["git", "show", f"{BASE}:{tests.relative_to(ROOT)}"], cwd=ROOT,
            )
            try:
                tests.write_bytes(baseline_tests)
                if run(out, f"baseline-control-{case.label}", case.module, case.package, (case.target,)):
                    raise RuntimeError(f"{case.label}: baseline clean test failed")
                source.write_bytes(untouched.replace(old, case.after.encode(), 1))
                baseline = run(out, f"baseline-mutant-{case.label}", case.module, case.package, (case.target,))
            finally:
                source.write_bytes(untouched)
                tests.write_bytes(original_tests)
            if any("AssertionError" not in kind and "AssertionError" not in detail for _, kind, detail in baseline):
                raise RuntimeError(f"{case.label}: baseline mutation crashed before a behavioral assertion")
            print(f"{case.label} baseline: {'ALREADY CAUGHT' if baseline else 'SURVIVED'}", flush=True)
        try:
            source.write_bytes(untouched.replace(old, case.after.encode(), 1))
            failures = run(out, f"candidate-mutant-{case.label}", case.module, case.package, (case.target,))
        finally:
            source.write_bytes(untouched)
        if not any(
            case.behavior in name and ("AssertionError" in kind or "AssertionError" in detail)
            for name, kind, detail in failures
        ):
            # Truth assertion subclasses describe a mismatch as expected/but was.
            if not any(case.behavior in name and "expected" in detail and "but was" in detail
                       for name, _, detail in failures):
                raise RuntimeError(f"{case.label}: no named behavioral assertion failed")


if __name__ == "__main__":
    if os.environ.get("GITHUB_HEAD_REF") != "test/behavioral-net":
        raise SystemExit("This hosted evidence is scoped to test/behavioral-net.")
    main(Path(sys.argv[1]))
