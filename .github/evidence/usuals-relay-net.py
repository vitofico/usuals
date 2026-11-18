"""Measure the unmodified suite, then replay small relay mutations in disposable databases."""
import os
from pathlib import Path
import shutil
import subprocess
import time
from xml.etree import ElementTree as ET

root = Path.cwd()
out = Path(os.environ["RUNNER_TEMP"]) / "relay-evidence"
out.mkdir(exist_ok=True)
source = root / "server/src/main/kotlin/io/theficos/usuals/relay"
prefix = "io.theficos.usuals.relay."
cases = [
    ("srv-feed-first-entry", "Lists.kt", "entry === entries.first() || bytes <= PAGE_BYTES", "bytes <= PAGE_BYTES", "ListsTest.firstFeedEntryOverPageByteBudgetStillAdvancesCursor"),
    ("srv-close-nonowned-noseq", "Lists.kt", 'else c.execute("UPDATE list SET seq=? WHERE id=?", seq, id(list))', 'else Unit', "AccountLifecycleTest.closureIsIdempotentRevokesBothCredentialsAndPreservesOtherOwnersLists"),
    ("srv-invite-never-fails", "Invitations.kt", 'if (attempts >= 8) "failed" else "queued"', '"queued"', "InvitationsTest.eightFailedDeliveriesStopRetryingAndExpiredInvitationsAreRetainedForThirtyDays"),
    ("srv-invite-purge-now", "Invitations.kt", 'clock() - 30L * 86400000', 'clock()', "InvitationsTest.eightFailedDeliveriesStopRetryingAndExpiredInvitationsAreRetainedForThirtyDays"),
]
def run(label, selector, mutant=False):
    reports = root / "server/build/test-results/test"
    shutil.rmtree(reports, ignore_errors=True)
    command = [str(runner), "--tests", prefix + selector, "--no-parallel", "--max-workers=3"]
    with (out / (label + ".log")).open("w") as log:
        start = time.monotonic()
        status = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=540).returncode
    xml = list(reports.glob("TEST-*.xml"))
    dest = out / label
    dest.mkdir(exist_ok=True)
    for p in xml:
        shutil.copy2(p, dest / p.name)
    tests = [case for p in xml for case in ET.parse(p).iter("testcase")]
    assert len(tests) == 1 and tests[0].get("name").split("(")[0] == selector.split(".")[-1], label
    failed = tests[0].find("failure")
    assert tests[0].find("skipped") is None and tests[0].find("error") is None
    assert (status != 0) == mutant and (failed is not None) == mutant, label
    if mutant:
        assert "Assertion" in failed.get("type", "") or "ComparisonFailure" in failed.get("type", ""), failed.attrib
    print(label, round(time.monotonic()-start, 2), flush=True)
runner = root / "server/scripts/relay-net-verify-local"
original_runner = (root / "server/scripts/test-local").read_text()
marker = "scripts/dgradle -p server test --rerun webTest installDist"
assert original_runner.count(marker) == 1
runner.write_text(original_runner.replace(marker, "scripts/dgradle -p server :test --rerun"))
runner.chmod(0o755)
try:
    for name, file, old, new, selector in cases:
        path = source / file
        original = path.read_text()
        assert original.count(old) == 1, name
        run(name + "-control", selector)
        try:
            path.write_text(original.replace(old, new))
            run(name + "-mutant", selector, True)
        finally:
            path.write_text(original)
        run(name + "-restored", selector)
finally:
    runner.unlink(missing_ok=True)
