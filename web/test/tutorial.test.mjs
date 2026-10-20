import test from "node:test";
import assert from "node:assert/strict";
import { tutorialCompleted, completeTutorial } from "../src/tutorial.mjs";

test("tutorial completion survives reopening and preserves other local preferences", () => {
  const values = new Map([["usuals-language", "de"], ["usuals-palette", "grove"]]);
  const storage = { getItem: (key) => values.get(key), setItem: (key, value) => values.set(key, value) };
  assert.equal(tutorialCompleted(storage), false);
  completeTutorial(storage);
  assert.equal(tutorialCompleted(storage), true);
  assert.equal(values.get("usuals-language"), "de");
  assert.equal(values.get("usuals-palette"), "grove");
});

test("unavailable storage never prevents opening or finishing a tutorial", () => {
  const blocked = { getItem() { throw Error("blocked"); }, setItem() { throw Error("full"); } };
  for (const storage of [null, blocked]) {
    assert.equal(tutorialCompleted(storage), false);
    assert.doesNotThrow(() => completeTutorial(storage));
  }
  assert.equal(tutorialCompleted({ getItem: () => "unknown" }), false);
});
