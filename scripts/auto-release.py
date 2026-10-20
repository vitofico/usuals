#!/usr/bin/env python3
"""Prepare and publish only a tested main commit's Android version change."""

import argparse
import datetime
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import sys

SPEC = importlib.util.spec_from_file_location("android_release", Path(__file__).with_name("android-release.py"))
RELEASE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RELEASE)
INPUTS = ("app/", "auth/", "capture/", "core/", "data/", "enrich/", "llm/", "recipe/",
          "smart/", "sync/", "testing/", "server/", "web/", "gradle/", "scripts/", "fastlane/",
          "distribution/", ".github/workflows/")
FILES = {"Dockerfile", ".dockerignore", "build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat"}


def git(root, *args):
    return subprocess.check_output(["git", *args], cwd=root, text=True).strip()


def fetch(root):
    # Updating a local tracking ref also observes an intentional history rewrite.
    git(root, "fetch", "--quiet", "origin", "+refs/heads/main:refs/remotes/origin/main", "--tags")
    return git(root, "rev-parse", "origin/main")


def tagged_versions(root):
    versions = []
    for tag in git(root, "tag", "--list", "v*").splitlines():
        if not RELEASE.VERSION_PATTERN.fullmatch(tag[1:]):
            continue
        version = RELEASE.read_version(git(root, "show", f"refs/tags/{tag}:gradle.properties"))
        if version["tag"] != tag:
            raise ValueError("Release tag differs from its committed version.")
        versions.append(version)
    return versions


def next_version(current, history, day):
    date = datetime.date.fromisoformat(day)
    if date.isoformat() != day:
        raise ValueError("Use YYYY-MM-DD for the release date.")
    versions = [current, *history]
    calendars = [RELEASE.calendar_version(v["versionName"]) for v in versions]
    if date < max(calendars)[0]:
        raise ValueError("Release date precedes an existing version.")
    run = max((n for d, n in calendars if d == date), default=0) + 1
    code = max(max(v["versionCode"] for v in versions) + 1, int(date.strftime("%y%m%d")) * 100 + 1)
    return RELEASE.read_version(f"VERSION_NAME={date.strftime('%Y.%m.%d')}.{run}\nVERSION_CODE={code}\n")


def require_clean(root):
    if git(root, "status", "--porcelain", "--untracked-files=normal"):
        raise ValueError("Release source must be clean.")


def version_files(root, version):
    templates = sorted((root / "fastlane/metadata/android").glob("*/changelogs/template.txt"))
    locales = sorted(p for p in (root / "fastlane/metadata/android").iterdir() if p.is_dir())
    if not templates or {p.parent.parent for p in templates} != set(locales):
        raise ValueError("Every store locale needs a changelog template.")
    changes = {}
    for template in templates:
        text = template.read_text().strip()
        if not text or len(text) > 500:
            raise ValueError("Changelog templates must contain 1 to 500 characters.")
        target = template.with_name(f"{version['versionCode']}.txt")
        if target.exists():
            raise ValueError("Refusing to replace a versioned changelog.")
        changes[target] = text + "\n"
    return changes


def prior_attempt(root, source):
    marker = f"Usuals-Release-Source: {source}"
    matches = git(root, "log", "origin/main", "--format=%H", "--fixed-strings", f"--grep={marker}").splitlines()
    matches = [c for c in matches if marker in git(root, "show", "-s", "--format=%B", c).splitlines()]
    if len(matches) > 1:
        raise ValueError("Multiple releases claim the same source.")
    if not matches:
        return None
    commit = matches[0]
    if git(root, "show", "-s", "--format=%P", commit) != source:
        raise ValueError("Release source trailer differs from its parent.")
    version = RELEASE.read_version(git(root, "show", f"{commit}:gradle.properties"))
    if git(root, "rev-parse", f"refs/tags/{version['tag']}^{{commit}}") != commit:
        raise ValueError("Release tag differs from its source commit.")
    paths = git(root, "diff", "--name-only", source, commit).splitlines()
    if "gradle.properties" not in paths or any(p != "gradle.properties" and
            not re.fullmatch(r"fastlane/metadata/android/[^/]+/changelogs/" + str(version["versionCode"]) + r"\.txt", p) for p in paths):
        raise ValueError("Release retry includes implementation changes.")
    original = git(root, "show", f"{source}:gradle.properties")
    expected = replace_version(original, version)
    if git(root, "show", f"{commit}:gradle.properties") != expected:
        raise ValueError("Release retry changes build properties beyond its version.")
    git(root, "checkout", "--quiet", "--detach", commit)
    return {"status": "reuse", "base": source, "commit": commit, **version}


def replace_version(text, version):
    text = re.sub(r"(?m)^VERSION_NAME=.*$", "VERSION_NAME=" + version["versionName"], text)
    return re.sub(r"(?m)^VERSION_CODE=.*$", "VERSION_CODE=" + str(version["versionCode"]), text)


def prepare(root, source, day):
    if not re.fullmatch(r"[0-9a-f]{40}", source) or git(root, "rev-parse", "HEAD") != source:
        raise ValueError("Build must use the exact checked CI source.")
    require_clean(root)
    tip = fetch(root)
    previous = prior_attempt(root, source)
    if previous:
        return previous
    if tip != source:
        return {"status": "stale"}
    history = tagged_versions(root)
    if history:
        last = max(history, key=lambda v: RELEASE.calendar_version(v["versionName"]))
        changed = git(root, "diff", "--name-only", f"refs/tags/{last['tag']}", source).splitlines()
        if not any(p in FILES or p.startswith(INPUTS) for p in changed):
            return {"status": "unchanged"}
    properties = root / "gradle.properties"
    text = properties.read_text()
    version = next_version(RELEASE.read_version(text), history, day)
    changes = version_files(root, version)
    tag = version["tag"]
    if subprocess.run(["git", "show-ref", "--verify", "--quiet", "refs/tags/" + tag], cwd=root).returncode != 1:
        raise ValueError("Refusing an existing release tag.")
    properties.write_text(replace_version(text, version))
    for path, value in changes.items():
        path.write_text(value)
    git(root, "add", "gradle.properties", *(str(p.relative_to(root)) for p in changes))
    git(root, "-c", "user.name=github-actions[bot]", "-c", "user.email=41898282+github-actions[bot]@users.noreply.github.com",
        "commit", "-m", f":bookmark: chore: release {tag}\n\nUsuals-Release-Source: {source}")
    # Lightweight tags need no second identity and are still immutable release refs.
    git(root, "tag", tag)
    return {"status": "prepared", "base": source, "commit": git(root, "rev-parse", "HEAD"), **version}


def push(root, plan):
    require_clean(root)
    if plan["status"] not in {"prepared", "reuse"} or git(root, "rev-parse", "HEAD") != plan["commit"]:
        raise ValueError("Release plan no longer matches the verified checkout.")
    current = RELEASE.read_version((root / "gradle.properties").read_text())
    if any(plan[key] != value for key, value in current.items()) or git(root, "show", "-s", "--format=%P", "HEAD") != plan["base"]:
        raise ValueError("Release plan differs from its source version or parent.")
    RELEASE.check_source(root, current, plan["tag"])
    if plan["status"] == "reuse":
        tip = fetch(root)
        remote_tag = git(root, "ls-remote", "origin", "refs/tags/" + plan["tag"])
        if not remote_tag or remote_tag.split()[0] != plan["commit"] or subprocess.run(
                ["git", "merge-base", "--is-ancestor", plan["commit"], tip], cwd=root).returncode:
            raise ValueError("Previously published release refs no longer match.")
        return "published"
    result = subprocess.run(["git", "push", "--atomic", "origin", f"{plan['commit']}:refs/heads/main",
                             f"refs/tags/{plan['tag']}:refs/tags/{plan['tag']}"], cwd=root, capture_output=True)
    if result.returncode == 0:
        return "published"
    tip = fetch(root)
    remote_tag = git(root, "ls-remote", "origin", "refs/tags/" + plan["tag"])
    # Recover a transport error after the server accepted the entire atomic push.
    if remote_tag:
        if remote_tag.split()[0] != plan["commit"] or subprocess.run(
                ["git", "merge-base", "--is-ancestor", plan["commit"], tip], cwd=root).returncode:
            raise ValueError("Remote release refs differ from the verified candidate.")
        return "published"
    if tip != plan["base"] and subprocess.run(["git", "merge-base", "--is-ancestor", plan["base"], tip], cwd=root).returncode == 0:
        return "stale"
    raise ValueError("Atomic release push failed without a newer main commit.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["prepare", "push"])
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--source")
    parser.add_argument("--date")
    parser.add_argument("--plan", type=Path, required=True)
    args = parser.parse_args()
    if args.action == "prepare" and not (args.source and args.date):
        parser.error("prepare requires --source and --date")
    try:
        if args.action == "prepare":
            result = prepare(args.root, args.source, args.date)
            args.plan.write_text(json.dumps(result, indent=2) + "\n")
        else:
            result = {"status": push(args.root, json.loads(args.plan.read_text()))}
        print(json.dumps(result))
    except (OSError, ValueError, KeyError, TypeError, subprocess.CalledProcessError) as error:
        print(str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
