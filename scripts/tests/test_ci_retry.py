import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "retry-gradle-download"


class DownloadRetryTest(unittest.TestCase):
    def run_fixture(self, message, failures=1):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            command = root / "command"
            command.write_text(
                '#!/usr/bin/env python3\n'
                'from pathlib import Path\n'
                f'p = Path({str(root / "attempts")!r})\n'
                'n = int(p.read_text()) + 1 if p.exists() else 1\n'
                'p.write_text(str(n))\n'
                f'print({message!r})\n'
                f'raise SystemExit(1 if n <= {failures} else 0)\n'
            )
            command.chmod(0o755)
            # Keep the real retry policy; only remove the delay in these fixtures.
            (root / "sleep").write_text('#!/bin/sh\nexit 0\n')
            (root / "sleep").chmod(0o755)
            result = subprocess.run(
                [str(SCRIPT), str(command)], capture_output=True, text=True,
                env={**os.environ, "PATH": str(root) + os.pathsep + os.environ["PATH"]},
            )
            return result.returncode, int((root / "attempts").read_text())

    def test_transient_download_retries(self):
        self.assertEqual((0, 2), self.run_fixture(
            "Could not GET dependency. Received status code 502 from server"
        ))

    def test_permanent_download_stops(self):
        self.assertEqual((1, 1), self.run_fixture(
            "Could not GET dependency. Received status code 404 from server"
        ))

    def test_assertions_are_never_retried(self):
        self.assertEqual((1, 1), self.run_fixture("There were failing tests"))
        self.assertEqual((1, 1), self.run_fixture(
            "Could not GET dependency. Received status code 502 from server\nThere were failing tests"
        ))

    def test_persistent_outage_fails_after_three_attempts(self):
        self.assertEqual((1, 3), self.run_fixture(
            "Could not GET dependency. Received status code 503 from server", failures=4
        ))
