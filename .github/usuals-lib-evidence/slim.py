"""Hosted narrow JVM controls and synthetic local-library mutations."""

from dataclasses import dataclass
import os
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
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


CAPTURE = "capture"
CAPTURE_PACKAGE = "io.theficos.usuals.capture"
CASES = [
    Case("quality-nochunk-read", LOCAL, LOCAL_PACKAGE, "RoomQualityCacheTest", "RoomQualityCache.kt",
         "gtins.map { it.value }.distinct().chunked(maxBound).flatMap { dao.findAll(it) }",
         "dao.findAll(gtins.map { it.value }.distinct())",
         "reads and observers stay inside the DAO bind bound"),
    Case("quality-nochunk-observe", LOCAL, LOCAL_PACKAGE, "RoomQualityCacheTest", "RoomQualityCache.kt",
         "gtins.map { it.value }.chunked(maxBound).map { dao.observe(it) }",
         "listOf(dao.observe(gtins.map { it.value }))",
         "reads and observers stay inside the DAO bind bound"),
    Case("quality-additives-empty", LOCAL, LOCAL_PACKAGE, "RoomQualityCacheTest", "RoomQualityCache.kt",
         "additives = TagList.encode(quality?.additives)",
         "additives = TagList.encode(quality?.additives?.takeIf { it.isNotEmpty() })",
         "unknown empty and not applicable facts remain distinct"),
    Case("fortnight-7days", LOCAL, LOCAL_PACKAGE, "ItemRepositoryTest", "ItemRepository.kt",
         "FORTNIGHT_MILLIS: Long = 14L *", "FORTNIGHT_MILLIS: Long = 7L *",
         "reap drops tombstones past the fortnight and keeps everything else"),
    Case("transfer-no-rejected", LOCAL, LOCAL_PACKAGE, "ItemTransferTest", "ItemRepository.kt",
         " && it.id !in confirmed.rejected", "", "unusableDestinationAcknowledgementsKeepSource"),
    Case("keystore-no-trim", AUTH, AUTH_PACKAGE, "KeystoreSecretStoreTest", "KeystoreSecretStore.kt",
         "val trimmed = key.trim()", "val trimmed = key", "padded keys are trimmed and blanks are rejected"),
    Case("keystore-clear-noop", AUTH, AUTH_PACKAGE, "KeystoreSecretStoreTest", "KeystoreSecretStore.kt",
         "    override fun clear() {", "    override fun clear() {\n        if (hasModelKey()) return",
         "a fresh instance sees initial replacement and cleared state"),
    Case("keystore-cold-start-loses-key", AUTH, AUTH_PACKAGE, "KeystoreSecretStoreTest", "KeystoreSecretStore.kt",
         "    override fun putModelKey(key: String) {", "    private var writtenHere = false\n\n    override fun putModelKey(key: String) {\n        writtenHere = true",
         "a fresh instance sees initial replacement and cleared state"),
    Case("debounce-le", CAPTURE, CAPTURE_PACKAGE, "ScanAnalyzerTest", "ScanDebounce.kt",
         "now - lastAtMillis < windowMillis", "now - lastAtMillis <= windowMillis",
         "scanning a second carton of the same milk works after the window"),
    Case("debounce-held-resurfaces", CAPTURE, CAPTURE_PACKAGE, "ScanAnalyzerTest", "ScanDebounce.kt",
         "        lastAtMillis = now", "        if (!repeat) lastAtMillis = now",
         "a carton held in frame produces one item"),
    Case("reset-ignored", CAPTURE, CAPTURE_PACKAGE, "ScanAnalyzerTest", "ScanDebounce.kt",
         "            lastGtin = null\n            lastAtMillis = 0L", "            Unit",
         "reset lets the same product be scanned again"),
]

HELD = [
    "redaction-leak: regular constant-redaction assertions run; the credential disclosure is not introduced.",
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
        (LOCAL, LOCAL_PACKAGE, ("QualityObserveTest", "RoomQualityCacheTest", "ItemRepositoryTest", "ItemTransferTest", "ProductTypeMigrationTest")),
        (AUTH, AUTH_PACKAGE, ("KeystoreSecretStoreTest", "SecretStoreRedactionTest", "ProviderSettingsTest", "ProviderSettingsStoreTest", "ProviderSettingsTenancyTest", "SyncVaultTest")),
        (CAPTURE, CAPTURE_PACKAGE, ("ScanAnalyzerTest",)),
    ]
    for module, package, targets in controls:
        if run(out, f"control-{module.replace('/', '-')}", module, package, targets):
            raise RuntimeError(f"Clean {module} control failed")
    for case in CASES:
        source, _ = paths(case)
        untouched = source.read_bytes()
        old = case.before.encode()
        if untouched.count(old) != 1:
            raise RuntimeError(f"{case.label}: mutation anchor is missing or ambiguous")
        candidate = untouched.replace(old, case.after.encode(), 1)
        if case.label == "keystore-cold-start-loses-key":
            marker = b"prefs.getString(KEY_MODEL_KEY, null)?.takeIf { it.isNotEmpty() }"
            if candidate.count(marker) != 1:
                raise RuntimeError("cold-start reads must have one source anchor")
            candidate = candidate.replace(marker, b"if (!writtenHere) null else " + marker, 1)
        try:
            source.write_bytes(candidate)
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
    if os.environ.get("GITHUB_HEAD_REF") != "test/consolidate-suites":
        raise SystemExit("This hosted evidence is scoped to test/consolidate-suites.")
    main(Path(sys.argv[1]))
