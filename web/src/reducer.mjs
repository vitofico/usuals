import {
  requireThat,
  equal,
  utf8,
  field,
  bytes,
  digest,
  hex,
  Problem,
  object,
  primitive,
  number,
  strictInteger,
  isLiteralNumber,
} from "./wire.mjs";
const ROW_FIELDS = [
  "key",
  "name",
  "rawText",
  "quantity",
  "unit",
  "note",
  "gtin",
  "store",
  "checked",
  "checkedAt",
  "position",
  "createdAt",
  "updatedAt",
  "fromSuggestion",
  "normalizerVersion",
  "addedBy",
  "deletedAt",
];
const REVISION_FIELDS = ROW_FIELDS.filter(
  (k) =>
    ![
      "createdAt",
      "updatedAt",
      "normalizerVersion",
      "fromSuggestion",
      "addedBy",
      "deletedAt",
    ].includes(k),
);
export const revisionField = (k) =>
  k === "unit"
    ? "quantity"
    : ["checked", "checkedAt"].includes(k)
      ? "check"
      : ["key", "gtin"].includes(k)
        ? "name"
        : k;
export function emptyProjection() {
  return {
    rows: {},
    purchases: {},
    trips: {},
    preferences: {},
    rules: {},
    aliases: {},
    aliasRevision: null,
    name: "",
    nameRevision: null,
    redirects: {},
    tombstones: {},
    closes: {},
    cancelledCloses: [],
    occurrences: {},
    repeatHeads: {},
    conflicts: {},
    rejected: {},
    seen: {},
  };
}
export function canonical(key, aliases) {
  let value = key;
  const visited = new Set();
  while (Object.hasOwn(aliases, value)) {
    requireThat(!visited.has(value));
    visited.add(value);
    value = aliases[value];
  }
  return value;
}
function factor(from, to) {
  const mass = { GRAM: 1, KILOGRAM: 1000 },
    volume = { MILLILITRE: 1, LITRE: 1000 };
  if (
    from === to &&
    ["GRAM", "KILOGRAM", "MILLILITRE", "LITRE", "PIECE", "PACK"].includes(from)
  )
    return 1;
  const group =
    Object.hasOwn(mass, from) && Object.hasOwn(mass, to)
      ? mass
      : Object.hasOwn(volume, from) && Object.hasOwn(volume, to)
        ? volume
        : null;
  return group ? group[from] / group[to] : null;
}
export function decimal(value) {
  requireThat(Number.isFinite(value));
  if (Object.is(value, -0)) return "-0.0";
  const sign = value < 0 ? "-" : "";
  let s = Math.abs(value).toString();
  if (value === 0) return "0.0";
  const a = Math.abs(value);
  if (a >= 1e7 || a < 1e-3) {
    let [mantissa, power] = a.toExponential().split("e");
    if (!mantissa.includes(".")) mantissa += ".0";
    return sign + mantissa + "E" + Number(power);
  }
  return (value < 0 ? "-" : "") + s + (s.includes(".") ? "" : ".0");
}
function validGtin(value) {
  if (
    typeof value !== "string" ||
    !/^\d{13}$/.test(value) ||
    /^0+$/.test(value)
  )
    return false;
  let sum = 0;
  for (let i = value.length - 2, m = 3; i >= 0; i--, m = m === 3 ? 1 : 3)
    sum += Number(value[i]) * m;
  return (10 - (sum % 10)) % 10 === Number(value.at(-1));
}
function factsValid(f) {
  if (f.name != null) {
    const name = primitive(f.name);
    requireThat(name.trim() && name.length <= 1000);
  }
  if (f.rawText != null) requireThat(primitive(f.rawText).length <= 8000);
  if (f.store != null) requireThat(primitive(f.store).length <= 80);
  if (f.gtin != null) requireThat(validGtin(primitive(f.gtin)));
  if (Object.hasOwn(f, "position"))
    requireThat(Number.isFinite(number(f.position)));
  const q = f.quantity == null ? null : primitive(f.quantity);
  const u = f.unit == null ? null : primitive(f.unit);
  requireThat((q === null) === (u === null));
  if (q !== null) {
    const n = Number(q);
    requireThat(Number.isFinite(n) && n >= 0 && factor(u, u) !== null);
    if (n !== 0 && (n < 1e-6 || n > 1e12))
      throw new Problem("quantity_unsupported");
    requireThat(decimal(n) === q);
  }
}
const pick = (obj, fields) =>
  Object.fromEntries(Object.entries(obj).filter(([k]) => fields.includes(k)));
const content = (value) => (value === null ? "null" : primitive(value));
const nullableText = (value) => (value == null ? null : primitive(value));
const needString = (o, key) => {
  requireThat(Object.hasOwn(o, key));
  return content(o[key]);
};
const integer = (o, key) => {
  requireThat(Object.hasOwn(o, key));
  return strictInteger(o[key]);
};
const truth = (value) => value === true || value === "true";
function safeMaps(state) {
  for (const name of [
    "rows",
    "purchases",
    "trips",
    "preferences",
    "rules",
    "aliases",
    "redirects",
    "tombstones",
    "closes",
    "occurrences",
    "repeatHeads",
    "conflicts",
    "rejected",
    "seen",
  ])
    Object.setPrototypeOf(state[name], null);
  for (const row of Object.values(state.rows))
    Object.setPrototypeOf(row.revisions, null);
  return state;
}
function plainMaps(state) {
  for (const name of [
    "rows",
    "purchases",
    "trips",
    "preferences",
    "rules",
    "aliases",
    "redirects",
    "tombstones",
    "closes",
    "occurrences",
    "repeatHeads",
    "conflicts",
    "rejected",
    "seen",
  ])
    state[name] = Object.fromEntries(Object.entries(state[name]));
  return state;
}
const obj = (o, k) => object(o[k]);
export async function occurrenceId(list, key, rule, purchase, cycle = null) {
  const parts = [
    list,
    key,
    rule,
    purchase,
    ...(cycle ? ["cycle/1", cycle.previous, cycle.deletion, cycle.finish] : []),
  ];
  return hex(await digest(bytes(...parts.map((p) => field(utf8.encode(p))))));
}
function incompatible(value) {
  if (value === null || typeof value !== "object" || isLiteralNumber(value))
    return false;
  return Object.entries(value).some(
    ([k, v]) =>
      (k === "normalizerVersion" && primitive(v) !== "1") ||
      (k === "schema" && primitive(v) !== "1") ||
      incompatible(v),
  );
}
function indexKey(value) {
  return (
    typeof value === "string" &&
    /^(0|[1-9][0-9]*)$/.test(value) &&
    BigInt(value) < 4294967295n
  );
}
function unsupportedOrder(state) {
  return [state.rows, state.closes, state.seen].some((map) =>
    Object.keys(map).some(indexKey),
  );
}
export async function reduce(before, intent) {
  if (indexKey(intent.id) || unsupportedOrder(before))
    return {
      projection: before,
      outcome: "UPGRADE_REQUIRED",
      reason: "legacy_ids_unsupported",
    };
  if (
    intent.schema !== 1 ||
    intent.normalizer !== 1 ||
    incompatible(intent.payload)
  )
    return { projection: before, outcome: "UPGRADE_REQUIRED" };
  if (Object.hasOwn(before.seen, intent.id))
    return {
      projection: before,
      outcome: equal(before.seen[intent.id], intent) ? "NO_OP" : "REJECTED",
    };
  let state = safeMaps(structuredClone(before)),
    outcome;
  const p = intent.payload,
    id = intent.id;
  const resolve = (t) => canonical(t, state.redirects);
  const row = () => {
    const target = nullableText(p.target);
    return target === null
      ? null
      : state.tombstones[target]
        ? state.rows[target]
        : (state.rows[target] ?? state.rows[resolve(target)]);
  };
  const alive = (r) => r && !r.archived && !state.tombstones[r.id]; // Parentheses are intentional: an absent revision and explicit null have the same authority.
  const guarded = (r, expected) =>
    Object.entries(expected).every(
      ([k, v]) =>
        ((k === "lifecycle" ? r.lifecycle : r.revisions[k]) ?? null) ===
        (v == null ? null : primitive(v)),
    );
  const conflict = (
    incoming = p,
    current = row()?.facts ?? {},
    code = "stale",
  ) => {
    state.conflicts[id] = {
      incoming: structuredClone(incoming),
      current: structuredClone(current),
      code,
    };
    return "CONFLICT";
  };
  const put = (r) => {
    state.rows[r.id] = r;
  };
  const canon = (k) => canonical(k, state.aliases);
  const revisions = (f) =>
    Object.fromEntries(
      Object.keys(f)
        .filter((k) => REVISION_FIELDS.includes(k) && f[k] != null)
        .map((k) => [revisionField(k), id]),
    );
  try {
    requireThat(
      typeof id === "string" &&
        id.trim() &&
        typeof intent.listId === "string" &&
        intent.listId.trim(),
    );
    switch (intent.kind) {
      case "capture": {
        const proposed = needString(p, "proposedId"),
          key = canon(needString(p, "key"));
        requireThat(
          !state.rows[proposed] &&
            !state.redirects[proposed] &&
            !state.tombstones[proposed] &&
            key.trim(),
        );
        factsValid(p);
        const existing = Object.values(state.rows).find(
          (r) =>
            alive(r) &&
            !truth(r.facts.checked) &&
            canon(needString(r.facts, "key")) === key,
        );
        if (!existing) {
          requireThat(needString(p, "name").trim());
          const facts = { ...pick(p, ROW_FIELDS), key, checked: false };
          put({
            id: proposed,
            facts,
            revisions: revisions(facts),
            lifecycle: id,
            archived: false,
            enabledBy: null,
            automaticUnchanged: false,
            repeatOccurrence: null,
            repeatRule: null,
            repeatPurchase: null,
            repeatBase: null,
            repeatPrevious: null,
          });
          outcome = "APPLIED";
          break;
        }
        const changes = { ...existing.facts },
          incoming = p.quantity == null ? null : number(p.quantity),
          amount = changes.quantity == null ? null : number(changes.quantity);
        if (incoming !== null) {
          const f =
            amount === null
              ? 1
              : factor(
                  needString(p, "unit"),
                  needString(existing.facts, "unit"),
                );
          if (f === null) {
            outcome = conflict(p, existing.facts, "unit");
            break;
          }
          const sum = amount === null ? incoming : amount + incoming * f;
          requireThat(Number.isFinite(sum));
          if (sum !== 0 && (sum < 1e-6 || sum > 1e12))
            throw new Problem("quantity_unsupported");
          changes.quantity = decimal(sum);
          changes.unit = amount === null ? p.unit : existing.facts.unit;
        }
        for (const k of ["note", "gtin", "store"])
          if (changes[k] == null && p[k] != null) changes[k] = p[k];
        const changed = Object.keys(changes)
          .filter(
            (k) =>
              !equal(changes[k], existing.facts[k]) &&
              REVISION_FIELDS.includes(k),
          )
          .map(revisionField);
        if (changed.length && p.updatedAt != null)
          changes.updatedAt = p.updatedAt;
        put({
          ...existing,
          facts: changes,
          revisions: {
            ...existing.revisions,
            ...Object.fromEntries(changed.map((k) => [k, id])),
          },
        });
        state.redirects[proposed] = existing.id;
        outcome = "MERGED";
        break;
      }
      case "edit": {
        const r = row();
        if (!alive(r)) {
          outcome = conflict();
          break;
        }
        const changes = obj(p, "changes"),
          required = [
            ...new Set(
              Object.keys(changes)
                .filter((k) => REVISION_FIELDS.includes(k))
                .map(revisionField),
            ),
          ],
          expected = obj(p, "expected");
        requireThat(
          Object.keys(changes).length &&
            Object.keys(changes).every(
              (k) =>
                ROW_FIELDS.includes(k) && !["checked", "checkedAt"].includes(k),
            ) &&
            required.every((k) => Object.hasOwn(expected, k)),
        );
        if (!guarded(r, expected)) {
          outcome = conflict(changes, r.facts);
          break;
        }
        const facts = { ...r.facts, ...changes };
        factsValid(facts);
        requireThat(needString(facts, "name").trim());
        const delta = Object.fromEntries(
          Object.entries(p.aliasDelta ?? {}).map(([key, value]) => [
            key,
            content(value),
          ]),
        );
        if (Object.keys(delta).length) {
          if (
            nullableText(p.expectedAliasRevision) !==
            (state.aliasRevision ?? null)
          ) {
            outcome = conflict(changes, r.facts, "alias_stale");
            break;
          }
          Object.keys({ ...state.aliases, ...delta }).forEach((k) =>
            canonical(k, { ...state.aliases, ...delta }),
          );
        }
        if (
          Object.keys(changes).some((k) =>
            ["name", "gtin", "key"].includes(k),
          ) &&
          Object.values(state.rows).some(
            (x) =>
              x.id !== r.id &&
              alive(x) &&
              !truth(x.facts.checked) &&
              canonical(needString(x.facts, "key"), {
                ...state.aliases,
                ...delta,
              }) ===
                canonical(needString(facts, "key"), {
                  ...state.aliases,
                  ...delta,
                }),
          )
        ) {
          outcome = conflict(changes, r.facts, "identity");
          break;
        }
        if (Object.keys(delta).length) {
          state.aliases = { ...state.aliases, ...delta };
          state.aliasRevision = id;
        }
        put({
          ...r,
          facts,
          revisions: {
            ...r.revisions,
            ...Object.fromEntries(required.map((k) => [k, id])),
          },
          automaticUnchanged: false,
        });
        outcome = "APPLIED";
        break;
      }
      case "merge": {
        const source = state.rows[needString(p, "source")],
          target = state.rows[needString(p, "target")];
        if (
          !alive(source) ||
          !alive(target) ||
          source.id === target.id ||
          truth(source.facts.checked) ||
          truth(target.facts.checked)
        ) {
          outcome = conflict();
          break;
        }
        const se = obj(p, "sourceExpected"),
          te = obj(p, "targetExpected");
        if (
          ![...Object.keys(source.revisions), "lifecycle"].every((k) =>
            Object.hasOwn(se, k),
          ) ||
          ![...Object.keys(target.revisions), "lifecycle"].every((k) =>
            Object.hasOwn(te, k),
          )
        ) {
          outcome = conflict(p, target.facts);
          break;
        }
        requireThat(Object.hasOwn(p, "expectedAliasRevision"));
        if (
          !guarded(source, se) ||
          !guarded(target, te) ||
          nullableText(p.expectedAliasRevision) !==
            (state.aliasRevision ?? null)
        ) {
          outcome = conflict(p, target.facts);
          break;
        }
        const destination = canon(needString(target.facts, "key")),
          delta = Object.fromEntries(
            Object.entries(obj(p, "aliasDelta")).map(([key, value]) => [
              key,
              content(value),
            ]),
          ),
          sourceKeys = [
            ...new Set([
              needString(source.facts, "key"),
              ...(source.facts.gtin ? ["gtin:" + source.facts.gtin] : []),
            ]),
          ].filter((k) => k !== destination);
        requireThat(
          Object.keys(delta).length &&
            equal(Object.keys(delta).sort(), sourceKeys.sort()) &&
            Object.values(delta).every((k) => canon(k) === destination),
        );
        const aliases = { ...state.aliases, ...delta };
        requireThat(
          sourceKeys.every((k) => canonical(k, aliases) === destination),
        );
        Object.keys(aliases).forEach((k) => canonical(k, aliases));
        requireThat(
          !Object.values(state.rows).some(
            (r) =>
              alive(r) &&
              ![source.id, target.id].includes(r.id) &&
              !truth(r.facts.checked) &&
              canonical(needString(r.facts, "key"), aliases) === destination,
          ),
        );
        put({
          ...source,
          lifecycle: id,
          facts: { ...source.facts, ...pick(p, ["deletedAt", "updatedAt"]) },
        });
        put({ ...target, automaticUnchanged: false });
        state.aliases = aliases;
        state.aliasRevision = id;
        state.tombstones[source.id] = id;
        state.redirects[source.id] = target.id;
        outcome = "MERGED";
        break;
      }
      case "check": {
        const r = row();
        if (!alive(r)) {
          outcome = conflict();
          break;
        }
        const e = obj(p, "expected");
        requireThat(Object.hasOwn(e, "check"));
        if (!guarded(r, e)) {
          outcome = conflict();
          break;
        }
        requireThat(
          [true, false, "true", "false"].includes(p.checked) &&
            (truth(p.checked)
              ? Number.isSafeInteger(strictInteger(p.checkedAt))
              : p.checkedAt == null),
        );
        put({
          ...r,
          facts: {
            ...r.facts,
            checked: truth(p.checked),
            checkedAt: p.checkedAt ?? null,
            ...pick(p, ["updatedAt"]),
          },
          revisions: { ...r.revisions, check: id },
          automaticUnchanged: false,
        });
        outcome = "APPLIED";
        break;
      }
      case "delete": {
        const r = row();
        if (!alive(r)) {
          outcome = conflict();
          break;
        }
        const e = obj(p, "expected");
        requireThat(Object.hasOwn(e, "lifecycle"));
        if (!guarded(r, e)) {
          outcome = conflict();
          break;
        }
        put({
          ...r,
          lifecycle: id,
          facts: { ...r.facts, ...pick(p, ["updatedAt", "deletedAt"]) },
        });
        state.tombstones[r.id] = id;
        outcome = "APPLIED";
        break;
      }
      case "finish": {
        const trip = needString(p, "tripId");
        requireThat(
          !state.trips[trip] &&
            !Object.values(state.closes).some((c) => c.tripId === trip),
        );
        const accepted = [];
        let stale = false;
        for (const candidate of p.rows) {
          const requested = needString(candidate, "id"),
            target = state.tombstones[requested]
              ? requested
              : resolve(requested);
          if (state.purchases[target]) continue;
          const r = state.rows[target],
            facts = obj(candidate, "facts"),
            e = obj(candidate, "expectedFields");
          if (
            !alive(r) ||
            !truth(r.facts.checked) ||
            (r.lifecycle ?? null) !== (candidate.expectedLifecycle ?? null) ||
            !guarded(r, e) ||
            Object.keys(r.revisions).some((k) => !Object.hasOwn(e, k)) ||
            Object.entries(facts).some(([k, v]) => !equal(r.facts[k], v))
          ) {
            stale = true;
            conflict(candidate, r?.facts ?? {});
            continue;
          }
          requireThat(
            ["key", "name", "quantity", "unit", "checkedAt"].every((k) =>
              Object.hasOwn(facts, k),
            ) && Number.isSafeInteger(strictInteger(facts.checkedAt)),
          );
          const purchase = {
            ...facts,
            id: target,
            tripId: trip,
            boughtAt: facts.checkedAt,
          };
          delete purchase.checkedAt;
          state.purchases[target] = purchase;
          put({
            ...r,
            archived: true,
            lifecycle: id,
            facts: { ...r.facts, deletedAt: p.closedAt },
          });
          accepted.push(target);
        }
        if (!accepted.length) {
          outcome = stale ? "CONFLICT" : "NO_OP";
          break;
        }
        state.trips[trip] = { closedAt: p.closedAt };
        state.closes[id] = { tripId: trip, itemIds: accepted };
        outcome = "APPLIED";
        break;
      }
      case "undo": {
        const finish = needString(p, "finishId"),
          close = state.closes[finish];
        if (state.cancelledCloses.includes(finish) || !close) {
          outcome = "NO_OP";
          break;
        }
        requireThat(close.tripId === needString(p, "tripId"));
        const remove = close.itemIds.filter(
          (i) => nullableText(state.purchases[i]?.tripId) === close.tripId,
        );
        remove.forEach((i) => delete state.purchases[i]);
        delete state.trips[close.tripId];
        state.cancelledCloses.push(finish);
        for (const r of Object.values(state.rows)) {
          if (remove.includes(r.id) && r.lifecycle === finish)
            put({ ...r, archived: false, lifecycle: id });
          else if (r.enabledBy === finish && r.automaticUnchanged && alive(r)) {
            put({ ...r, lifecycle: id });
            state.tombstones[r.id] = id;
            if (
              r.repeatBase &&
              state.repeatHeads[r.repeatBase] === r.repeatOccurrence &&
              r.repeatPrevious
            )
              state.repeatHeads[r.repeatBase] = r.repeatPrevious;
          }
        }
        outcome = "APPLIED";
        break;
      }
      case "repeat": {
        const key = canon(needString(p, "key")),
          ruleRevision = needString(p, "ruleRevision"),
          enabling = needString(p, "enablingPurchase"),
          cycle = p.cycle ?? null,
          base = await occurrenceId(intent.listId, key, ruleRevision, enabling),
          occurrence = await occurrenceId(
            intent.listId,
            key,
            ruleRevision,
            enabling,
            cycle,
          ),
          proposed = needString(p, "proposedId");
        requireThat(
          needString(p, "occurrence") === occurrence &&
            !state.rows[proposed] &&
            !state.redirects[proposed],
        );
        if (state.occurrences[occurrence]) {
          state.redirects[proposed] = state.occurrences[occurrence];
          outcome = "NO_OP";
          break;
        }
        const rule = state.rules[key];
        requireThat(rule && needString(rule, "revision") === ruleRevision);
        const purchase = state.purchases[enabling];
        requireThat(purchase || enabling === ruleRevision);
        const latest = Object.values(state.purchases)
          .filter((x) => canon(needString(x, "key")) === key)
          .sort(
            (a, b) =>
              integer(b, "boughtAt") - integer(a, "boughtAt") ||
              (needString(a, "id") < needString(b, "id")
                ? -1
                : needString(a, "id") > needString(b, "id")
                  ? 1
                  : 0),
          )[0];
        requireThat(
          (latest ? needString(latest, "id") : null) ===
            (purchase ? needString(purchase, "id") : null),
        );
        if (purchase) {
          const now = integer(p, "asOf"),
            bought = integer(purchase, "boughtAt"),
            days = integer(rule, "everyDays");
          requireThat(
            canon(needString(purchase, "key")) === key &&
              days >= 1 &&
              days <= 2147483647 &&
              now >= bought &&
              now - bought >= days * 86400000,
          );
        }
        requireThat(
          !Object.values(state.rows).some(
            (r) => alive(r) && canon(needString(r.facts, "key")) === key,
          ),
        );
        if (cycle) {
          const winner = state.occurrences[cycle.previous],
            prior = state.rows[winner];
          requireThat(
            prior &&
              prior.repeatBase === base &&
              state.repeatHeads[base] === cycle.previous &&
              prior.repeatRule === ruleRevision &&
              prior.repeatPurchase === enabling &&
              prior.repeatOccurrence === cycle.previous &&
              prior.lifecycle === cycle.deletion &&
              state.tombstones[winner] === cycle.deletion &&
              state.seen[cycle.deletion]?.kind === "delete",
          );
          const ordered = Object.keys(state.seen),
            at = ordered.indexOf(cycle.deletion),
            first =
              at < 0
                ? null
                : ordered
                    .slice(at + 1)
                    .find(
                      (x) =>
                        state.closes[x] && !state.cancelledCloses.includes(x),
                    );
          requireThat(first === cycle.finish);
        }
        const enabledBy =
          cycle?.finish ??
          Object.entries(state.closes).find(
            ([k, c]) =>
              c.itemIds.includes(enabling) &&
              !state.cancelledCloses.includes(k),
          )?.[0] ??
          null;
        put({
          id: proposed,
          facts: {
            key,
            name: nullableText(p.name) ?? nullableText(purchase?.name) ?? key,
            quantity: null,
            unit: null,
            checked: false,
            fromSuggestion: true,
            ...pick(p, ["position", "createdAt", "updatedAt"]),
          },
          revisions: { name: id, quantity: id, check: id },
          lifecycle: id,
          archived: false,
          enabledBy,
          automaticUnchanged: true,
          repeatOccurrence: occurrence,
          repeatRule: ruleRevision,
          repeatPurchase: enabling,
          repeatBase: base,
          repeatPrevious: cycle?.previous ?? null,
        });
        state.occurrences[occurrence] = proposed;
        state.repeatHeads[base] = occurrence;
        outcome = "APPLIED";
        break;
      }
      case "alias": {
        const delta = Object.fromEntries(
          Object.entries(obj(p, "delta")).map(([key, value]) => [
            key,
            content(value),
          ]),
        );
        if (nullableText(p.expected) !== (state.aliasRevision ?? null)) {
          outcome = conflict(
            p,
            Object.fromEntries(
              [...Object.keys(delta), ...Object.values(delta)]
                .filter((k) => state.aliases[k])
                .map((k) => [k, state.aliases[k]]),
            ),
            "alias_stale",
          );
          break;
        }
        const aliases = { ...state.aliases, ...delta };
        Object.keys(aliases).forEach((k) => canonical(k, aliases));
        const identities = Object.values(state.rows)
          .filter((r) => alive(r) && !truth(r.facts.checked))
          .map((r) => canonical(needString(r.facts, "key"), aliases));
        if (new Set(identities).size !== identities.length) {
          outcome = conflict(p, row()?.facts ?? {}, "identity");
          break;
        }
        state.aliases = aliases;
        state.aliasRevision = id;
        outcome = "APPLIED";
        break;
      }
      case "preference": {
        const key = needString(p, "key"),
          current = state.preferences[key] ?? {},
          changes = obj(p, "changes");
        if ((current.revision ?? null) !== nullableText(p.expected)) {
          outcome = conflict(changes, current);
          break;
        }
        requireThat(
          Object.keys(changes).every((k) =>
            ["category", "repeatEveryDays"].includes(k),
          ),
        );
        if (changes.repeatEveryDays != null)
          requireThat(
            strictInteger(changes.repeatEveryDays) > 0 &&
              strictInteger(changes.repeatEveryDays) <= 2147483647,
          );
        state.preferences[key] = { ...current, ...changes, revision: id };
        if (Object.hasOwn(changes, "repeatEveryDays")) {
          if (changes.repeatEveryDays === null) delete state.rules[key];
          else
            state.rules[key] = {
              revision: id,
              everyDays: changes.repeatEveryDays,
            };
        }
        outcome = "APPLIED";
        break;
      }
      case "list_name": {
        if (nullableText(p.expected) !== (state.nameRevision ?? null)) {
          outcome = conflict();
          break;
        }
        const name = needString(p, "name");
        requireThat(name.trim() && name.length <= 100);
        state.name = name;
        state.nameRevision = id;
        outcome = "APPLIED";
        break;
      }
      case "import": {
        requireThat(
          Object.keys(p).every((k) =>
            ["rows", "purchases", "trips"].includes(k),
          ),
        );
        const patches = p.rows ?? [],
          purchases = p.purchases ?? [],
          trips = p.trips ?? [];
        requireThat(
          patches.length + purchases.length + trips.length <= 100 &&
            new Set(purchases.map((x) => needString(x, "id"))).size ===
              purchases.length &&
            new Set(patches.map((x) => needString(x, "id"))).size ===
              patches.length,
        );
        for (const patch of patches) {
          const target = needString(patch, "id"),
            existing = state.rows[target],
            facts = obj(patch, "facts");
          requireThat(
            (existing?.lifecycle ?? null) ===
              nullableText(patch.expectedLifecycle) &&
              (!existing || guarded(existing, obj(patch, "expected"))) &&
              needString(facts, "name").trim(),
          );
          factsValid(facts);
          requireThat(
            (!existing || !existing.archived || truth(patch.archived)) &&
              (!state.tombstones[target] || truth(patch.deleted)),
          );
          put({
            id: target,
            facts,
            revisions: revisions(facts),
            lifecycle: id,
            archived: truth(patch.archived),
            enabledBy: null,
            automaticUnchanged: false,
            repeatOccurrence: null,
            repeatRule: null,
            repeatPurchase: null,
            repeatBase: null,
            repeatPrevious: null,
          });
          if (truth(patch.deleted)) state.tombstones[target] = id;
        }
        for (const purchase of purchases) {
          requireThat(
            !state.purchases[needString(purchase, "id")] ||
              equal(state.purchases[needString(purchase, "id")], purchase),
          );
          factsValid(purchase);
        }
        for (const trip of trips) {
          const tid = needString(trip, "id"),
            facts = { ...trip };
          delete facts.id;
          requireThat(!state.trips[tid] || equal(state.trips[tid], facts));
          state.trips[tid] = facts;
        }
        requireThat(
          purchases.every(
            (x) =>
              state.trips[needString(x, "tripId")] ||
              equal(state.purchases[needString(x, "id")], x),
          ),
        );
        purchases.forEach((x) => (state.purchases[needString(x, "id")] = x));
        outcome = "APPLIED";
        break;
      }
      default:
        outcome = "REJECTED";
    }
  } catch (error) {
    if (
      [
        "upgrade_required",
        "quantity_unsupported",
        "number_unsupported",
      ].includes(error?.code)
    )
      return {
        projection: before,
        outcome: "UPGRADE_REQUIRED",
        reason: error.code,
      };
    if (
      error instanceof Problem ||
      error instanceof TypeError ||
      error instanceof RangeError
    )
      outcome = "REJECTED";
    else throw error;
  }
  if (outcome === "REJECTED") {
    state = safeMaps(structuredClone(before));
    state.rejected[id] = "invalid";
  }
  if (unsupportedOrder(state))
    return {
      projection: before,
      outcome: "UPGRADE_REQUIRED",
      reason: "legacy_ids_unsupported",
    };
  state.seen[id] = structuredClone(intent);
  return { projection: plainMaps(state), outcome };
}
export async function snapshotDecode(payload) {
  requireThat(
    payload.schema === 1 && payload.normalizer === 1,
    "upgrade_required",
  );
  const p = { ...emptyProjection(), ...object(payload.projection) };
  requireThat(!unsupportedOrder(p), "legacy_ids_unsupported");
  requireThat(
    Object.keys(p.rows).length <= 20000 &&
      Object.keys(p.purchases).length <= 20000 &&
      Object.keys(p.trips).length <= 20000 &&
      Object.keys(p.aliases).length <= 20000 &&
      p.name.length <= 100,
  );
  for (const [id, r] of Object.entries(p.rows))
    requireThat(id && id.length <= 500 && r.id === id);
  for (const [id, f] of Object.entries(p.purchases))
    requireThat(id && id.length <= 500 && needString(f, "id") === id);
  for (const f of Object.values(p.trips))
    requireThat(Number.isSafeInteger(strictInteger(f.closedAt)));
  for (const edges of [p.aliases, p.redirects])
    for (const k of Object.keys(edges)) {
      requireThat(k && k.length <= 500 && edges[k] && edges[k].length <= 500);
      canonical(k, edges);
    }
  let checked = emptyProjection(),
    n = 0;
  const validate = async (body) => {
    const r = await reduce(checked, {
      id: `snapshot-validation-${n++}`,
      listId: "snapshot-validation",
      kind: "import",
      payload: body,
      schema: 1,
      normalizer: 1,
    });
    if (r.outcome === "UPGRADE_REQUIRED")
      throw new Problem(r.reason ?? "upgrade_required");
    requireThat(["APPLIED", "NO_OP"].includes(r.outcome));
    checked = r.projection;
  };
  const batches = async (xs, k) => {
    for (let i = 0; i < xs.length; i += 100)
      await validate({ [k]: xs.slice(i, i + 100) });
  };
  await batches(
    Object.entries(p.trips).map(([id, f]) => ({ ...f, id })),
    "trips",
  );
  await batches(Object.values(p.purchases), "purchases");
  await batches(
    Object.values(p.rows).map((r) => ({
      id: r.id,
      facts: r.facts,
      archived: r.archived,
      deleted: Object.hasOwn(p.tombstones, r.id),
    })),
    "rows",
  );
  requireThat(
    Object.keys(p.preferences).length <= 20000 &&
      Object.keys(p.rules).length <= 20000,
  );
  Object.values(p.rules).forEach((r) =>
    requireThat(strictInteger(r.everyDays) > 0),
  );
  for (const [id, i] of Object.entries(p.seen))
    requireThat(id === i.id && i.schema === 1 && i.normalizer === 1);
  for (const [id, c] of Object.entries(p.closes))
    requireThat(
      id &&
        id.length <= 500 &&
        c.tripId &&
        c.tripId.length <= 500 &&
        c.itemIds.length <= 20000,
    );
  return p;
}
