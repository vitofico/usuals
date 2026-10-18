import test from "node:test";
import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
const directory = new URL("../locales/", import.meta.url),
  english = JSON.parse(await readFile(new URL("en.json", directory), "utf8"));
test("every locale is file-discovered, complete and preserves interpolation placeholders", async () => {
  const names = await readdir(directory);
  for (const expected of [
    "de.json",
    "en.json",
    "es.json",
    "fr.json",
    "it.json",
  ])
    assert.ok(names.includes(expected));
  for (const name of names) {
    const locale = JSON.parse(await readFile(new URL(name, directory), "utf8"));
    assert.deepEqual(
      Object.keys(locale).sort(),
      Object.keys(english).sort(),
      name,
    );
    for (const key of Object.keys(english)) {
      assert.equal(typeof locale[key], "string");
      assert.ok(locale[key].trim(), `${name}:${key}`);
      const placeholders = (v) =>
        [...v.matchAll(/\{([a-z]+)\}/g)].map((x) => x[1]).sort();
      assert.deepEqual(
        placeholders(locale[key]),
        placeholders(english[key]),
        `${name}:${key}`,
      );
      assert.equal(locale[key].includes("\u2014"), false);
    }
  }
});
test("application is buildless and does not embed unsafe HTML or external scripts", async () => {
  const index = await readFile(
      new URL("../index.html", import.meta.url),
      "utf8",
    ),
    app = await readFile(new URL("../src/app.mjs", import.meta.url), "utf8");
  assert.match(index, /<script type="module" src="\/src\/app.mjs"><\/script>/);
  assert.doesNotMatch(index, /<script[^>]*>[^<]+/);
  assert.doesNotMatch(
    app,
    /innerHTML|insertAdjacentHTML|document\.write|eval\(/,
  );
  assert.doesNotMatch(index, /https?:\/\//);
});
