import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { join } from "node:path";
import { strictJson, equal, jsonBytes } from "../src/wire.mjs";
import { snapshotDecode, reduce } from "../src/reducer.mjs";
import { normalize } from "../src/identity.mjs";
const directory = process.env.COMANDA_WEB_INTEROP_DIR;
test(
  "authoritative JVM normalization and reducer edge cases",
  { skip: !directory },
  async () => {
    const fixture = strictJson(
      new Uint8Array(await readFile(join(directory, "kotlin-input.json"))),
      10 * 1024 * 1024,
      true,
    );
    assert.ok(fixture.normalizationCases?.length);
    assert.ok(fixture.reducerCases?.length);
    for (const c of fixture.normalizationCases)
      assert.equal(normalize(c.input), c.expected, c.input);
    const failures = [];
    for (const c of fixture.reducerCases) {
      try {
        let before;
        try {
          before = await snapshotDecode({
            schema: 1,
            normalizer: 1,
            projection: c.before,
          });
        } catch (error) {
          if (c.unsupportedLegacyIds && error.code === "legacy_ids_unsupported")
            continue;
          throw error;
        }
        const result = await reduce(before, c.intent);
        if (
          result.outcome === "UPGRADE_REQUIRED" &&
          result.reason === "number_unsupported"
        ) {
          assert.equal(
            c.unsupportedNumber,
            true,
            `Unexpected number representation: ${c.name}`,
          );
          assert.equal(result.projection, before, c.name);
          continue;
        }
        if (
          result.outcome === "UPGRADE_REQUIRED" &&
          result.reason === "legacy_ids_unsupported"
        ) {
          assert.equal(
            c.unsupportedLegacyIds,
            true,
            `Unexpected legacy IDs: ${c.name}`,
          );
          assert.equal(result.projection, before, c.name);
          continue;
        }
        if (
          result.outcome === "UPGRADE_REQUIRED" &&
          result.reason === "quantity_unsupported"
        ) {
          assert.equal(
            c.unsupportedQuantity,
            true,
            `Unexpected unsupported quantity: ${c.name}`,
          );
          assert.equal(result.projection, before, c.name);
          continue;
        }
        assert.equal(result.outcome, c.expectedOutcome, c.name);
        assert.deepEqual(
          Object.fromEntries(
            Object.entries(result.projection.rows).map(([id, row]) => [
              id,
              row.facts,
            ]),
          ),
          c.expectedRows,
          `${c.name}: rows`,
        );
        for (const [property, expected] of [
          ["aliases", "expectedAliases"],
          ["tombstones", "expectedTombstones"],
          ["purchases", "expectedPurchases"],
        ])
          assert.deepEqual(
            result.projection[property],
            c[expected],
            `${c.name}: ${property}`,
          );
        const reparsed = strictJson(
          jsonBytes(result.projection),
          7 * 1024 * 1024,
          true,
        );
        assert.equal(
          equal(reparsed, result.projection),
          true,
          `${c.name}: lossless restart`,
        );
      } catch (error) {
        failures.push(`${c.name}: ${error.message.slice(0, 700)}`);
      }
    }
    assert.equal(
      failures.length,
      0,
      `${failures.length} JVM differential failures:\n${failures.slice(0, 25).join("\n")}`,
    );
  },
);
