import test from "node:test";
import assert from "node:assert/strict";
import { readdir } from "node:fs/promises";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
const directory = new URL("../src/", import.meta.url);
// These modules are imported by the behavioral suites in this directory.
const exercised = new Set(["history-delete.mjs", "intents.mjs", "connect.mjs", "wire.mjs", "lists.mjs"]);
for (const name of await readdir(directory))
  if (name.endsWith(".mjs") && !exercised.has(name))
    test(`packaged module parses: ${name}`, () => {
      const result = spawnSync(
        process.execPath,
        ["--check", fileURLToPath(new URL(name, directory))],
        { encoding: "utf8", timeout: 10000 },
      );
      assert.equal(result.status, 0, result.stderr);
    });
