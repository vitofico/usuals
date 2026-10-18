import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import {
  emptyProjection,
  reduce,
  occurrenceId,
  canonical,
  decimal,
} from "../src/reducer.mjs";
import { normalize } from "../src/identity.mjs";
const fixture = JSON.parse(
  await readFile(
    new URL(
      "../../testing/src/test/resources/shared-list-v1/reducer.json",
      import.meta.url,
    ),
    "utf8",
  ),
);
function initial(input) {
  const p = emptyProjection();
  for (const r of input.rows ?? []) {
    const facts = {},
      revisions = {};
    for (const [k, v] of Object.entries(r)) {
      if (k.endsWith("Revision")) revisions[k.slice(0, -8)] = v;
      else if (!["id", "lifecycle", "archived"].includes(k)) facts[k] = v;
    }
    p.rows[r.id] = {
      id: r.id,
      facts,
      revisions,
      lifecycle: r.lifecycle ?? null,
      archived: r.archived === true,
      enabledBy: null,
      automaticUnchanged: false,
      repeatOccurrence: null,
      repeatRule: null,
      repeatPurchase: null,
      repeatBase: null,
      repeatPrevious: null,
    };
  }
  for (const r of input.automaticRows ?? []) {
    const f = { ...r };
    for (const k of ["id", "enabledBy", "unchanged"]) delete f[k];
    p.rows[r.id] = {
      id: r.id,
      facts: f,
      revisions: {},
      lifecycle: null,
      archived: false,
      enabledBy: r.enabledBy,
      automaticUnchanged: r.unchanged === true,
      repeatOccurrence: null,
      repeatRule: null,
      repeatPurchase: null,
      repeatBase: null,
      repeatPrevious: null,
    };
  }
  for (const f of input.purchases ?? []) {
    p.purchases[f.id] = { key: f.name.toLowerCase(), ...f };
    p.trips[f.tripId] = {};
  }
  Object.assign(p, {
    rules: input.rules ?? {},
    preferences: input.preferences ?? {},
    aliases: input.aliases ?? {},
    aliasRevision: input.aliases ? "genesis" : null,
    name: input.name ?? "",
    nameRevision: input.nameRevision ?? null,
  });
  for (const c of input.effectiveCloses ?? [])
    p.closes[c.id] = { tripId: c.tripId, itemIds: c.itemIds };
  return p;
}
async function repeatReady(p, key, rule) {
  const purchase = Object.values(p.purchases)
      .filter((x) => x.key === key)
      .sort(
        (a, b) =>
          (b.boughtAt ?? 0) - (a.boughtAt ?? 0) || (a.id < b.id ? -1 : 1),
      )[0],
    base = await occurrenceId(
      "home",
      key,
      rule.revision,
      purchase?.id ?? rule.revision,
    ),
    h = p.repeatHeads[base] ?? base,
    winner = p.occurrences[h];
  if (!winner) return true;
  const row = p.rows[winner],
    deletion = p.tombstones[winner];
  if (
    !row ||
    !deletion ||
    p.seen[deletion]?.kind !== "delete" ||
    row.lifecycle !== deletion
  )
    return false;
  const ordered = Object.keys(p.seen),
    i = ordered.indexOf(deletion);
  return (
    i >= 0 &&
    ordered
      .slice(i + 1)
      .some((k) => p.closes[k] && !p.cancelledCloses.includes(k))
  );
}
async function facts(p) {
  return {
    rows: Object.fromEntries(Object.values(p.rows).map((r) => [r.id, r.facts])),
    repeatReady: Object.fromEntries(
      await Promise.all(
        Object.entries(p.rules).map(async ([k, r]) => [
          k,
          await repeatReady(p, k, r),
        ]),
      ),
    ),
    activeRowCount: Object.values(p.rows).filter(
      (r) => !r.archived && !p.tombstones[r.id],
    ).length,
    purchases: p.purchases,
    purchaseCount: Object.keys(p.purchases).length,
    tripCount: Object.keys(p.trips).length,
    conflicts: Object.fromEntries(
      Object.entries(p.conflicts).map(([k, c]) => [
        k,
        { incoming: c.incoming },
      ]),
    ),
    redirects: p.redirects,
    tombstones: p.tombstones,
    cancelledCloses: Object.fromEntries(
      p.cancelledCloses.map((k) => [k, true]),
    ),
    aliases: p.aliases,
    preferences: p.preferences,
    name: p.name,
  };
}
for (const scenario of fixture.cases)
  test(`shared reducer: ${scenario.id}`, async () => {
    const scoped = scenario.initial.lists ?? { home: scenario.initial };
    let first = Object.fromEntries(
        Object.entries(scoped).map(([k, v]) => [k, initial(v)]),
      ),
      second = structuredClone(first);
    const outcomes = {},
      retries = new Set(),
      occurrences = {};
    for (const source of scenario.operations) {
      const list = source.list ?? "home",
        op = structuredClone(source);
      if (op.kind === "repeat") {
        const cycle = op.cycle
            ? { ...op.cycle, previous: occurrences[op.cycle.previous] }
            : null,
          occurrence = await occurrenceId(
            list,
            op.key,
            op.ruleRevision,
            op.enablingPurchase,
            cycle,
          );
        occurrences[op.occurrence] = occurrence;
        op.occurrence = occurrence;
        if (cycle) op.cycle = cycle;
      }
      const payload = { ...op };
      for (const k of ["id", "kind", "list"]) delete payload[k];
      const intent = {
        id: op.id,
        listId: list,
        kind: op.kind,
        payload,
        schema: 1,
        normalizer: 1,
      };
      if (Object.hasOwn(outcomes, op.id)) retries.add(op.id);
      const result = await reduce(first[list], intent);
      outcomes[op.id] ??= result.outcome.toLowerCase();
      first[list] = result.projection;
      const restart = JSON.parse(JSON.stringify(second[list])),
        one = await reduce(restart, intent);
      second[list] = (await reduce(one.projection, intent)).projection;
    }
    assert.deepEqual(second, first);
    const actual = scenario.initial.lists
      ? {
          lists: Object.fromEntries(
            await Promise.all(
              Object.entries(first).map(async ([k, p]) => [k, await facts(p)]),
            ),
          ),
        }
      : await facts(first.home);
    for (const [path, want] of Object.entries(scenario.expected.facts)) {
      const got = path.split(".").reduce((v, k) => v?.[k], actual) ?? null;
      assert.deepEqual(got, want, path);
    }
    for (const [id, want] of Object.entries(scenario.expected.outcomes)) {
      let got = outcomes[id];
      if (
        retries.has(id) &&
        ["applied", "merged"].includes(got) &&
        want === "applied_once"
      )
        got = "applied_once";
      assert.equal(got, want, `outcome ${id}`);
    }
  });
test("unknown version does not consume an intent", async () => {
  const p = emptyProjection();
  assert.deepEqual(
    await reduce(p, {
      id: "a",
      listId: "b",
      kind: "capture",
      schema: 2,
      normalizer: 1,
      payload: {},
    }),
    { projection: p, outcome: "UPGRADE_REQUIRED" },
  );
});
test("invalid quantities roll back the complete operation", async () => {
  for (const quantity of ["NaN", "Infinity", "-1.0", "1", "0x10"]) {
    const r = await reduce(emptyProjection(), {
      id: "a",
      listId: "b",
      kind: "capture",
      schema: 1,
      normalizer: 1,
      payload: {
        proposedId: "x",
        key: "milk",
        name: "Milk",
        quantity,
        unit: "LITRE",
      },
    });
    assert.equal(r.outcome, "REJECTED");
    assert.deepEqual(r.projection.rows, {});
  }
});
test("version 1 identity folding preserves Spanish enye and existing plurals", () => {
  assert.equal(normalize("AÑO"), "año");
  assert.notEqual(normalize("año"), normalize("ano"));
  assert.equal(normalize("LA leche de avena!"), "leche avena");
  assert.equal(normalize("LÁPICES"), "lapiz");
  assert.equal(normalize("Milk"), "milk");
  assert.equal(normalize("of"), "of");
  assert.equal(canonical("a", { a: "b" }), "b");
  assert.throws(() => canonical("a", { a: "b", b: "a" }));
});
test("wire decimal formatting uses JVM notation at exponent thresholds", () => {
  for (const [number, want] of [
    [1, "1.0"],
    [1.5, "1.5"],
    [1e7, "1.0E7"],
    [1e-4, "1.0E-4"],
    [-0, "-0.0"],
  ])
    assert.equal(decimal(number), want);
});

test("only canonical nonzero EAN13 shopping facts are accepted", async () => {
  for (const gtin of [
    "0000000000000",
    "96385074",
    "036000291452",
    "10012345678902",
    "4006381333932",
  ]) {
    const result = await reduce(emptyProjection(), {
      id: "capture",
      listId: "list",
      kind: "capture",
      schema: 1,
      normalizer: 1,
      payload: { proposedId: "row", key: "product", name: "Product", gtin },
    });
    assert.equal(result.outcome, "REJECTED", gtin);
    assert.deepEqual(result.projection.rows, {});
  }
  const accepted = await reduce(emptyProjection(), {
    id: "capture",
    listId: "list",
    kind: "capture",
    schema: 1,
    normalizer: 1,
    payload: {
      proposedId: "row",
      key: "product",
      name: "Product",
      gtin: "4006381333931",
    },
  });
  assert.equal(accepted.outcome, "APPLIED");
});

test("unsupported quantity arithmetic stops without consuming the intent", async () => {
  for (const quantity of ["4.9E-324", "1.0E-9", "1.0E15"]) {
    const before = emptyProjection(),
      r = await reduce(before, {
        id: "extreme",
        listId: "list",
        kind: "capture",
        schema: 1,
        normalizer: 1,
        payload: {
          proposedId: "row",
          key: "milk",
          name: "Milk",
          quantity,
          unit: "LITRE",
        },
      });
    assert.equal(r.outcome, "UPGRADE_REQUIRED");
    assert.equal(r.reason, "quantity_unsupported");
    assert.equal(r.projection, before);
    assert.deepEqual(r.projection.seen, {});
  }
});

test("hexadecimal positions stop before consumption while hexadecimal quantities remain invalid", async () => {
  const before = emptyProjection(),
    base = {
      id: "hex",
      listId: "list",
      kind: "capture",
      schema: 1,
      normalizer: 1,
      payload: { proposedId: "row", key: "milk", name: "Milk" },
    };
  const position = await reduce(before, {
    ...base,
    payload: { ...base.payload, position: "0x1.0p0" },
  });
  assert.equal(position.outcome, "UPGRADE_REQUIRED");
  assert.equal(position.reason, "number_unsupported");
  assert.equal(position.projection, before);
  const quantity = await reduce(before, {
    ...base,
    payload: { ...base.payload, quantity: "0x1.0p0", unit: "PIECE" },
  });
  assert.equal(quantity.outcome, "REJECTED");
  assert.deepEqual(quantity.projection.rows, {});
});
