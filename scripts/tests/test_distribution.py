import json
from pathlib import Path
import re
import subprocess
import tempfile
import unittest
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[2]


class DistributionTest(unittest.TestCase):
    def test_obtainium_excludes_debug_and_sidecar_assets(self):
        config = json.loads((ROOT / "distribution/obtainium.json").read_text())
        settings = json.loads(config["additionalSettings"])
        self.assertEqual(config["id"], "io.theficos.usuals")
        self.assertEqual(config["url"], "https://github.com/vitofico/usuals")
        self.assertFalse(settings["includePrereleases"])
        names = ["app-release.apk", "app-debug.apk", "app-release-unsigned.apk", "app-release.apk.sha256", "release-identity.json"]
        self.assertEqual([name for name in names if re.fullmatch(settings["apkFilterRegEx"], name)], ["app-release.apk"])
        link = (ROOT / "distribution/obtainium-link.txt").read_text().strip()
        self.assertEqual(json.loads(unquote(link.removeprefix("obtainium://app/"))), config)
        self.assertNotRegex(link.lower(), r"token|password|authorization")

    def test_fdroid_uses_the_actual_version_and_signing_identity(self):
        properties = dict(re.findall(r"(?m)^(VERSION_NAME|VERSION_CODE)=(.+)$", (ROOT / "gradle.properties").read_text()))
        recipe = (ROOT / "distribution/fdroid/metadata/io.theficos.usuals.yml").read_text()
        version, code = properties["VERSION_NAME"], properties["VERSION_CODE"]
        for text in [f"versionName: {version}", f"versionCode: {code}", f"commit: v{version}",
                     f"CurrentVersion: {version}", f"CurrentVersionCode: {code}"]:
            self.assertIn(text, recipe)
        pin = (ROOT / "scripts/release-certificate-sha256.txt").read_text().strip()
        self.assertRegex(pin, r"^[0-9a-f]{64}$")
        self.assertRegex(recipe, r"(?m)^AllowedAPKSigningKeys: " + pin + r"$")
        pattern = re.search(r"(?m)^UpdateCheckMode: Tags (.+)$", recipe).group(1)
        self.assertTrue(re.fullmatch(pattern, "v" + version))
        for rejected in ["main", "v" + version + "-debug", "v" + version + "-rc1"]:
            self.assertIsNone(re.fullmatch(pattern, rejected))
        data = re.search(r"(?m)^UpdateCheckData: (.+)$", recipe).group(1).split("|")
        self.assertEqual(re.search(data[1], (ROOT / data[0]).read_text()).group(1), code)
        self.assertEqual(re.search(data[3], (ROOT / data[2]).read_text()).group(1), version)

    def test_all_store_locales_are_complete_and_within_catalog_limits(self):
        code = re.search(r"(?m)^VERSION_CODE=(\d+)$", (ROOT / "gradle.properties").read_text()).group(1)
        locales = ROOT / "fastlane/metadata/android"
        self.assertTrue({"en-US", "es-ES", "it-IT", "fr-FR", "de-DE"}.issubset({p.name for p in locales.iterdir() if p.is_dir()}))
        for locale in (p for p in locales.iterdir() if p.is_dir()):
            for name, limit in [("title.txt", 50), ("short_description.txt", 80), ("full_description.txt", 4000), (f"changelogs/{code}.txt", 500)]:
                text = (locale / name).read_text().strip()
                self.assertTrue(text, str(locale / name))
                self.assertLessEqual(len(text), limit, str(locale / name))
        self.assertGreaterEqual(len(list((locales / "en-US/images/phoneScreenshots").glob("*.png"))), 2)

    def test_signing_refuses_missing_files_or_an_unpinned_identity_before_docker(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            command = [str(ROOT / "scripts/sign-release"), directory]
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(result.returncode, 2)
            self.assertIn("Missing or unreadable signing file", result.stderr)
            for name in ["usuals-release.keystore", "keystore-password.txt", "key-password.txt", "key-alias.txt", "certificate-sha256.txt"]:
                (root / name).write_text("synthetic, not a real key")
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(result.returncode, 2)
            self.assertIn("differs from the committed", result.stderr)


if __name__ == "__main__":
    unittest.main()
