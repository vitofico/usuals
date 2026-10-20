import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("auto_release", Path(__file__).resolve().parents[1] / "auto-release.py")
AUTO = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(AUTO)


def version(name="2026.10.20.1", code=26102001):
    return AUTO.RELEASE.read_version(f"VERSION_NAME={name}\nVERSION_CODE={code}\n")


class VersionTest(unittest.TestCase):
    def test_daily_runs_and_code_are_monotonic_across_all_history(self):
        current = version()
        for history, date, expected_name, expected_code in [
            ([], "2026-10-20", "2026.10.20.2", 26102002),
            ([], "2026-10-21", "2026.10.21.1", 26102101),
            ([version("2026.10.20.99", 26102099)], "2026-10-20", "2026.10.20.100", 26102100),
            ([version("2026.10.19.2", 260929000)], "2026-10-20", "2026.10.20.2", 260929001),
            ([version("2026.10.20.150", 26102150)], "2026-10-21", "2026.10.21.1", 26102151),
        ]:
            with self.subTest(history=history, date=date):
                result = AUTO.next_version(current, history, date)
                self.assertEqual((result["versionName"], result["versionCode"]), (expected_name, expected_code))

    def test_backward_calendar_noncanonical_dates_and_exhaustion_fail(self):
        for date in ["2026-10-19", "2026-02-30", "20261020"]:
            with self.subTest(date=date), self.assertRaises(ValueError):
                AUTO.next_version(version(), [], date)
        with self.assertRaises(ValueError):
            AUTO.next_version(version(), [version("2026.10.21.1")], "2026-10-20")
        with self.assertRaises(ValueError):
            AUTO.next_version(version(code=AUTO.RELEASE.MAX_CODE), [], "2026-10-20")


class GitReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "checkout"
        self.remote = Path(self.temp.name) / "remote.git"
        subprocess.run(["git", "init", "--quiet", "--bare", "--initial-branch=main", str(self.remote)], check=True)
        self.root.mkdir()
        self.git("init", "--quiet", "--initial-branch=main")
        self.git("config", "user.name", "Release test")
        self.git("config", "user.email", "release-test@example.invalid")
        self.git("config", "commit.gpgsign", "false")
        self.git("remote", "add", "origin", str(self.remote))
        self.write("gradle.properties", "VERSION_NAME=2026.10.20.1\nVERSION_CODE=26102001\n")
        for locale in ["en-US", "es-ES"]:
            self.write(f"fastlane/metadata/android/{locale}/changelogs/template.txt", "Updates and improvements.\n")
        self.write("app/source.kt", "original\n")
        self.baseline = self.commit("initial")
        self.git("tag", "v2026.10.20.1")
        self.git("push", "--quiet", "origin", "main", "--tags")
        self.write("app/source.kt", "tested implementation\n")
        self.source = self.commit("tested change")
        self.git("push", "--quiet", "origin", "main")

    def git(self, *args, root=None):
        return subprocess.check_output(["git", *args], cwd=root or self.root, text=True, stderr=subprocess.PIPE).strip()

    def write(self, name, text):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)

    def commit(self, message):
        self.git("add", ".")
        self.git("commit", "--quiet", "-m", message)
        return self.git("rev-parse", "HEAD")

    def prepare(self):
        return AUTO.prepare(self.root, self.source, "2026-10-20")

    def remote_tip(self):
        return self.git("rev-parse", "main", root=self.remote)

    def advance_main(self):
        other = Path(self.temp.name) / "other"
        subprocess.run(["git", "clone", "--quiet", str(self.remote), str(other)], check=True)
        self.git("config", "user.name", "Concurrent change", root=other)
        self.git("config", "user.email", "concurrent@example.invalid", root=other)
        (other / "app/source.kt").write_text("concurrent change\n")
        self.git("add", ".", root=other)
        self.git("commit", "--quiet", "-m", "concurrent change", root=other)
        self.git("push", "--quiet", "origin", "main", root=other)
        return self.remote_tip()

    def test_prepare_only_changes_metadata_and_pushes_nothing_until_verified(self):
        plan = self.prepare()
        self.assertEqual(plan["status"], "prepared")
        self.assertEqual(plan["versionName"], "2026.10.20.2")
        self.assertEqual(self.remote_tip(), self.source)
        self.assertEqual(self.git("tag", root=self.remote), "v2026.10.20.1")
        paths = self.git("diff", "--name-only", self.source, "HEAD").splitlines()
        self.assertEqual(paths, ["fastlane/metadata/android/en-US/changelogs/26102002.txt",
                                 "fastlane/metadata/android/es-ES/changelogs/26102002.txt", "gradle.properties"])
        self.assertIn(f"Usuals-Release-Source: {self.source}", self.git("log", "-1", "--format=%B"))
        self.assertEqual(AUTO.push(self.root, plan), "published")
        self.assertEqual(self.remote_tip(), plan["commit"])
        self.assertEqual(self.git("rev-parse", plan["tag"], root=self.remote), plan["commit"])

    def test_concurrent_main_push_is_stale_and_atomic_push_leaks_no_tag(self):
        plan = self.prepare()
        tip = self.advance_main()
        self.assertEqual(AUTO.push(self.root, plan), "stale")
        self.assertEqual(self.remote_tip(), tip)
        self.assertEqual(self.git("tag", root=self.remote), "v2026.10.20.1")
        self.assertEqual(self.git("rev-parse", "HEAD"), plan["commit"])

    def test_stale_source_is_skipped_before_any_version_changes(self):
        self.advance_main()
        self.assertEqual(self.prepare(), {"status": "stale"})
        self.assertEqual(self.git("rev-parse", "HEAD"), self.source)
        self.assertEqual(self.git("status", "--porcelain"), "")

    def test_retry_reuses_exact_release_and_bot_version_does_not_release_again(self):
        plan = self.prepare()
        AUTO.push(self.root, plan)
        self.git("checkout", "--quiet", "--detach", self.source)
        retry = self.prepare()
        self.assertEqual(retry, {**plan, "status": "reuse"})
        self.assertEqual(AUTO.push(self.root, retry), "published")
        self.assertEqual(AUTO.prepare(self.root, plan["commit"], "2026-10-20"), {"status": "unchanged"})

    def test_retry_after_newer_main_still_reuses_original_release(self):
        plan = self.prepare()
        AUTO.push(self.root, plan)
        self.advance_main()
        self.git("checkout", "--quiet", "--detach", self.source)
        retry = self.prepare()
        self.assertEqual(retry["commit"], plan["commit"])
        self.assertEqual(AUTO.push(self.root, retry), "published")

    def test_retry_refuses_changed_or_missing_remote_tag(self):
        plan = self.prepare()
        AUTO.push(self.root, plan)
        plan["status"] = "reuse"
        self.git("tag", "--delete", plan["tag"], root=self.remote)
        with self.assertRaisesRegex(ValueError, "no longer match"):
            AUTO.push(self.root, plan)

    def test_auth_failure_is_not_misreported_as_concurrent_change(self):
        plan = self.prepare()
        hook = self.remote / "hooks/pre-receive"
        hook.write_text("#!/bin/sh\nexit 1\n")
        hook.chmod(0o755)
        with self.assertRaisesRegex(ValueError, "without a newer main"):
            AUTO.push(self.root, plan)
        self.assertEqual(self.remote_tip(), self.source)
        self.assertEqual(self.git("tag", root=self.remote), "v2026.10.20.1")

    def test_transport_failure_after_accepted_push_recovers_original_refs(self):
        plan = self.prepare()
        run = subprocess.run

        def accepted_then_failed(args, **kwargs):
            result = run(args, **kwargs)
            if args[:2] == ["git", "push"]:
                self.assertEqual(result.returncode, 0)
                return subprocess.CompletedProcess(args, 1)
            return result

        with patch.object(AUTO.subprocess, "run", side_effect=accepted_then_failed):
            self.assertEqual(AUTO.push(self.root, plan), "published")
        self.assertEqual(self.remote_tip(), plan["commit"])

    def test_retry_refuses_release_commit_with_implementation_changes(self):
        plan = self.prepare()
        self.write("app/source.kt", "unreviewed implementation\n")
        self.git("add", ".")
        self.git("commit", "--amend", "--no-edit", "--quiet")
        self.git("tag", "--force", plan["tag"])
        self.git("push", "--quiet", "origin", "HEAD:main", "--tags")
        self.git("checkout", "--quiet", "--detach", self.source)
        with self.assertRaisesRegex(ValueError, "implementation changes"):
            self.prepare()

    def test_retry_cannot_hide_build_configuration_in_version_properties(self):
        plan = self.prepare()
        properties = self.root / "gradle.properties"
        properties.write_text(properties.read_text() + "org.gradle.parallel=true\n")
        self.git("add", ".")
        self.git("commit", "--amend", "--no-edit", "--quiet")
        self.git("tag", "--force", plan["tag"])
        self.git("push", "--quiet", "origin", "HEAD:main", "--tags")
        self.git("checkout", "--quiet", "--detach", self.source)
        with self.assertRaisesRegex(ValueError, "beyond its version"):
            self.prepare()

    def test_retry_refuses_wrong_tag_or_false_parent_trailer(self):
        plan = self.prepare()
        self.git("tag", "--force", plan["tag"], self.source)
        self.git("push", "--quiet", "origin", "HEAD:main", "--tags")
        self.git("checkout", "--quiet", "--detach", self.source)
        with self.assertRaisesRegex(ValueError, "tag differs"):
            self.prepare()
        # A marker claiming an unrelated checked commit cannot reuse an APK.
        self.git("checkout", "--quiet", "--detach", plan["commit"])
        self.git("commit", "--amend", "--quiet", "-m", f"false marker\n\nUsuals-Release-Source: {self.baseline}")
        self.git("push", "--quiet", "--force", "origin", "HEAD:main")
        self.git("checkout", "--quiet", "--detach", self.baseline)
        with self.assertRaisesRegex(ValueError, "differs from its parent"):
            AUTO.prepare(self.root, self.baseline, "2026-10-20")

    def test_plan_must_match_clean_verified_checkout_version_and_parent(self):
        plan = self.prepare()
        for corrupt in [{"versionCode": 1}, {"base": self.baseline}, {"commit": self.source}, {"status": "manual"}]:
            with self.subTest(corrupt=corrupt), self.assertRaises(ValueError):
                AUTO.push(self.root, {**plan, **corrupt})
        self.write("app/source.kt", "dirty\n")
        with self.assertRaisesRegex(ValueError, "clean"):
            AUTO.push(self.root, plan)

    def test_templates_validate_before_any_source_mutation(self):
        template = self.root / "fastlane/metadata/android/es-ES/changelogs/template.txt"
        for invalid in ["", "x" * 501]:
            with self.subTest(invalid=len(invalid)):
                template.write_text(invalid)
                self.source = self.commit("invalid template")
                self.git("push", "--quiet", "origin", "main")
                with self.assertRaisesRegex(ValueError, "1 to 500"):
                    self.prepare()
                self.assertEqual(self.git("status", "--porcelain"), "")
        template.unlink()
        self.source = self.commit("missing template")
        self.git("push", "--quiet", "origin", "main")
        with self.assertRaisesRegex(ValueError, "Every store locale"):
            self.prepare()

    def test_never_overwrites_existing_changelog(self):
        self.write("fastlane/metadata/android/en-US/changelogs/26102002.txt", "Existing notes\n")
        self.source = self.commit("reserved notes")
        self.git("push", "--quiet", "origin", "main")
        with self.assertRaisesRegex(ValueError, "replace a versioned changelog"):
            self.prepare()
        self.assertEqual(self.git("status", "--porcelain"), "")

    def test_documentation_only_push_does_not_publish_but_build_changes_do(self):
        self.git("checkout", "--quiet", self.baseline, "--", "app/source.kt")
        self.write("README.md", "Documentation update\n")
        self.source = self.commit("documentation only")
        self.git("push", "--quiet", "origin", "main")
        self.assertEqual(self.prepare(), {"status": "unchanged"})
        self.write(".github/workflows/android-ci.yaml", "changed build workflow\n")
        self.source = self.commit("build change")
        self.git("push", "--quiet", "origin", "main")
        self.assertEqual(self.prepare()["status"], "prepared")

    def test_wrong_source_and_dirty_checkout_cannot_prepare_release(self):
        for source in ["main", self.baseline, "a" * 40]:
            with self.subTest(source=source), self.assertRaises(ValueError):
                AUTO.prepare(self.root, source, "2026-10-20")
        self.write("untracked.txt", "unexpected\n")
        with self.assertRaisesRegex(ValueError, "clean"):
            self.prepare()


if __name__ == "__main__":
    unittest.main()
