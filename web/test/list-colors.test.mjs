import test from "node:test";
import assert from "node:assert/strict";
import { listColors, listColor, colorChange, appearanceKey } from "../src/list-colors.mjs";

test("existing lists follow the theme and all curated identities round trip", () => {
  assert.equal(listColor({}), "automatic");
  assert.equal(listColor({ preferences: { [appearanceKey]: { color: "unknown" } } }), "automatic");
  for (const color of listColors) {
    const projection = { preferences: { [appearanceKey]: { color, revision: "reviewed" } } };
    assert.equal(listColor(JSON.parse(JSON.stringify(projection))), color);
    assert.deepEqual(colorChange(projection, "automatic"), { color: null, expected: "reviewed" });
  }
});
test("a choice uses the reviewed revision and refuses arbitrary colors", () => {
  const reviewed = { preferences: { [appearanceKey]: { color: "sage", revision: "one" } } };
  assert.deepEqual(colorChange(reviewed, "rose"), { color: "rose", expected: "one" });
  assert.throws(() => colorChange(reviewed, "#ff0000"), /invalid_color/);
});
