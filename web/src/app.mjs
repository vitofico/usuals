import {
  Problem,
  requireThat,
  fingerprint,
  hex,
  unb64,
  primitive,
  number,
  isLiteralNumber,
} from "./wire.mjs";
import { Vault, openStorage } from "./vault.mjs";
import { HouseholdClient, RunScope, request } from "./client.mjs";
import { body } from "./membership.mjs";
import { canonical, decimal } from "./reducer.mjs";
import { normalize } from "./identity.mjs";
import { RefreshLoop } from "./refresh.mjs";
import { UnlockAttempt } from "./unlock.mjs";
import { palettes, readPalette, writePalette } from "./appearance.mjs";
import { tutorialCompleted, completeTutorial } from "./tutorial.mjs";
import { prepareHousehold, verifySavedHousehold } from "./recovery.mjs";
const $ = (id) => document.getElementById(id);
function appearanceStorage() {
  try { return localStorage; } catch { return null; }
}
let palette = readPalette(appearanceStorage());
let tutorialSeen = tutorialCompleted(appearanceStorage()), tutorialActive = false;
function applyAppearance() {
  document.documentElement.dataset.palette = palette;
  document.querySelector('meta[name="theme-color"]').content =
    getComputedStyle(document.documentElement).getPropertyValue("--primary").trim();
}
applyAppearance();
window.matchMedia("(prefers-color-scheme: dark)").addEventListener("change", applyAppearance);
let uiScope = new RunScope();
const api = (path, options = {}) =>
  request(path, { ...options, scope: uiScope });
let availableLocales = ["en"],
  dictionary = {},
  locale = "en",
  account = null,
  config = null,
  vault = null,
  client = null,
  scope = null,
  view = "list",
  selected = null,
  generation = 0,
  busy = false,
  idleTimer = null,
  expiryTimer = null,
  dialogCleanup = null;
const t = (key, values = {}) => {
  let value = dictionary[key] ?? dictionary.invalid ?? key;
  for (const [name, replacement] of Object.entries(values))
    value = value.replaceAll(`{${name}}`, String(replacement));
  return value;
};
function el(tag, attributes = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attributes)) {
    if (key.startsWith("on")) node.addEventListener(key.slice(2), value);
    else if (key === "class") node.className = value;
    else if (value !== undefined && value !== null) {
      if (key === "value")
        node.value = isLiteralNumber(value) ? primitive(value) : value;
      else if (key === "checked") node.checked = value;
      else node.setAttribute(key, String(value));
    }
  }
  for (const child of children.flat())
    if (child !== null && child !== undefined)
      node.append(
        child instanceof Node
          ? child
          : document.createTextNode(
              isLiteralNumber(child) ? primitive(child) : String(child),
            ),
      );
  return node;
}
const button = (label, action, kind = "secondary", attributes = {}) =>
  el(
    "button",
    { type: "button", class: kind, onclick: action, ...attributes },
    label,
  );
const paragraph = (key, kind = "lede", values = {}) =>
  el("p", { class: kind }, t(key, values));
function notice(key, error = false) {
  const node = $("notice");
  node.textContent = t(key);
  node.classList.toggle("error", error);
  node.hidden = false;
}
function clearNotice() {
  $("notice").hidden = true;
}
function failure(error) {
  if (error?.name === "AbortError" || error?.code === "locked") return;
  if (["unauthenticated", "forbidden"].includes(error?.code) && !client) {
    lock();
    loadAccount().catch(() => {});
  }
  notice(dictionary[error?.code] ? error.code : "serverUnavailable", true);
}
async function act(action, { rerender = true } = {}) {
  if (busy) return;
  busy = true;
  const own = generation;
  try {
    await action();
    if (own === generation && rerender) await render();
  } catch (error) {
    if (own === generation) failure(error);
  } finally {
    if (own === generation) busy = false;
  }
}
function resetIdle() {
  clearTimeout(idleTimer);
  if (client) idleTimer = setTimeout(() => lock(), 5 * 60 * 1000);
}
function lock() {
  generation++;
  refreshLoop.stop();
  uiScope.stop();
  uiScope = new RunScope();
  const draining = client?.stop();
  scope?.stop();
  client = null;
  scope = null;
  vault?.lock(draining);
  clearTimeout(idleTimer);
  clearTimeout(expiryTimer);
  busy = false;
  closeDialog();
  if (dictionary.locked) {
    clearNotice();
    render().catch(() => {});
  }
}
function title(main, subtitle, extra = null) {
  return el(
    "section",
    { class: "hero" },
    el("div", {}, el("h1", {}, t(main)), paragraph(subtitle)),
    extra,
  );
}
function openDialog(content, cleanup = null) {
  closeDialog();
  dialogCleanup = cleanup;
  $("dialog-content").replaceChildren(content);
  $("dialog").showModal();
}
function closeDialog() {
  const cleanup = dialogCleanup;
  dialogCleanup = null;
  cleanup?.();
  $("dialog").close();
  $("dialog-content").replaceChildren();
}
function finishTutorial() {
  tutorialSeen = true;
  completeTutorial(appearanceStorage());
  closeDialog();
}
function tutorialDialog() {
  let page = 0;
  const steps = ["Welcome", "Household", "Vault", "List"];
  const symbols = ["✓", "↗", "○", "✓"];
  function draw() {
    const last = page === steps.length - 1;
    $("dialog-content").replaceChildren(
      el("section", { class: "tutorial" },
        el("div", { class: "tutorial-top" },
          el("img", { src: "/assets/mark.svg", alt: "", width: 40, height: 40 }),
          button(t("tutorialSkip"), finishTutorial, "text-button")),
        el("p", { class: "eyebrow" }, t("tutorialStep", { current: page + 1, total: steps.length })),
        el("progress", { class: "tutorial-progress", max: steps.length, value: page + 1,
          "aria-label": t("tutorialStep", { current: page + 1, total: steps.length }) }),
        el("div", { class: "tutorial-art", "aria-hidden": "true" }, symbols[page]),
        el("h2", { id: "tutorial-heading", tabindex: -1 }, t(`tutorial${steps[page]}Title`)),
        paragraph(`tutorial${steps[page]}Body`),
        paragraph(`tutorial${steps[page]}Hint`, "helper"),
        el("div", { class: "button-row tutorial-actions" },
          page ? button(t("tutorialBack"), () => { page = Math.max(0, page - 1); draw(); }) : null,
          button(t(last ? "tutorialFinish" : "tutorialNext"), () => {
            if (last) finishTutorial();
            else { page = Math.min(steps.length - 1, page + 1); draw(); }
          }, "primary")),
      ),
    );
    $("tutorial-heading").focus();
  }
  openDialog(el("div"), () => { tutorialActive = false; });
  tutorialActive = true;
  draw();
}
function messageDialog(head, description, confirm) {
  const form = el(
    "form",
    {
      onsubmit: (event) => {
        event.preventDefault();
        closeDialog();
        act(confirm);
      },
    },
    el("h2", {}, t(head)),
    paragraph(description),
    el(
      "div",
      { class: "button-row" },
      button(t("cancel"), closeDialog),
      el("button", { type: "submit", class: "primary" }, t("confirm")),
    ),
  );
  openDialog(form);
}
async function localeLoad(language) {
  const response = await fetch(`/locales/${language}.json`, {
    credentials: "same-origin",
    cache: "no-store",
    redirect: "error",
  });
  requireThat(response.ok);
  const catalog = await response.json();
  dictionary = catalog;
  locale = language;
  document.documentElement.lang = language;
  document.title = "Usuals";
  for (const n of document.querySelectorAll("[data-i18n]"))
    n.textContent = t(n.dataset.i18n);
  for (const n of document.querySelectorAll("[data-i18n-label]"))
    n.setAttribute("aria-label", t(n.dataset.i18nLabel));
  $("navigation").setAttribute("aria-label", t("navigation"));
  $("language").setAttribute("aria-label", t("language"));
  $("language").textContent = language.toUpperCase();
}
async function languageDialog() {
  const own = generation;
  try {
    const catalogs = await Promise.all(
      availableLocales.map(async (code) => {
        const response = await fetch(`/locales/${code}.json`, {
          credentials: "same-origin",
          cache: "no-store",
          redirect: "error",
        });
        requireThat(response.ok);
        return [code, (await response.json()).nativeName];
      }),
    );
    if (own !== generation) return;
    openDialog(
      el(
        "div",
        {},
        el("h2", {}, t("language")),
        el(
          "div",
          { class: "locale-options" },
          catalogs.map(([code, name]) =>
            button(
              name,
              async () => {
                try {
                  await localeLoad(code);
                  try {
                    localStorage.setItem("usuals-language", code);
                  } catch {}
                  closeDialog();
                  await render();
                } catch (e) {
                  failure(e);
                }
              },
              code === locale ? "primary" : "secondary",
            ),
          ),
        ),
        el("div", { class: "button-row" }, button(t("close"), closeDialog)),
      ),
    );
  } catch (error) {
    if (own === generation) failure(error);
  }
}
function appearanceDialog() {
  const choices = palettes.map((id) => el("label", { class: "theme-choice" },
    el("input", { type: "radio", name: "palette", value: id, checked: id === palette,
      onchange: () => {
        palette = writePalette(id, appearanceStorage());
        applyAppearance();
      },
    }),
    el("span", { class: "theme-copy" }, el("strong", {}, t(`palette_${id}`)),
      el("span", {}, t(`palette_${id}_description`))),
    el("span", { class: "theme-swatches", "data-preview-palette": id, "aria-hidden": "true" },
      el("i"), el("i"), el("i")),
  ));
  openDialog(el("div", {},
    el("h2", {}, t("appearance")),
    el("fieldset", { class: "theme-options" },
      el("legend", { class: "helper" }, t("appearanceHint")), ...choices),
    el("div", { class: "button-row" }, button(t("close"), closeDialog)),
  ));
}
async function loadAccount() {
  const own = generation;
  const configuration = await api("/auth/config");
  if (own !== generation) return;
  config = configuration;
  try {
    const me = await api("/api/me");
    if (own !== generation) return;
    requireThat(
      me.user &&
        me.user.issuer &&
        me.user.subject &&
        me.user.id &&
        Number.isSafeInteger(me.expiresAt),
    );
    account = me;
    expiryTimer = setTimeout(
      () => {
        lock();
        account = null;
        notice("unauthenticated", true);
        render().catch(() => {});
      },
      Math.max(0, me.expiresAt - Date.now()),
    );
  } catch (error) {
    if (own !== generation) return;
    if (error.code === "unauthenticated") account = null;
    else throw error;
  }
  await render();
}
function welcome() {
  const signed = Boolean(account),
    content = el(
      "section",
      { class: "welcome" },
      el(
        "div",
        {},
        el("p", { class: "eyebrow" }, t("yourEveryday")),
        el("h1", {}, t("welcomeTitle")),
        paragraph("welcomeText"),
        el("div", { class: "welcome-actions" }, ...welcomeActions(signed)),
        paragraph(
          signed
            ? "lockHelp"
            : config?.localEnabled
              ? "localSignInText"
              : "signInText",
          "helper",
        ),
      ),
      el("div", { class: "welcome-art", "aria-hidden": "true" }),
    );
  return content;
}
async function unlockDialog() {
  if (!account) {
    notice("accountRequired", true);
    return;
  }
  const own = generation,
    verifiedAccount = await api("/api/me");
  if (own !== generation) return;
  requireThat(
    verifiedAccount.user.id === account.user.id &&
      verifiedAccount.user.issuer === account.user.issuer &&
      verifiedAccount.user.subject === account.user.subject,
    "unauthenticated",
  );
  account = verifiedAccount;
  const binding = JSON.stringify([
    location.origin,
    account.user.issuer,
    account.user.subject,
    account.user.id,
  ]);
  const storage = await openStorage(binding);
  if (own !== generation) return;
  const candidate = new Vault(storage, binding);
  const attempt = new UnlockAttempt(candidate, () => own === generation);
  const exists = await candidate.exists();
  if (own !== generation) return;
  const password = el("input", {
      id: "vault-password",
      type: "password",
      class: "field",
      autocomplete: exists ? "current-password" : "new-password",
      minlength: 14,
      maxlength: 1024,
      required: true,
    }),
    confirm = exists
      ? null
      : el("input", {
          id: "vault-confirm",
          type: "password",
          class: "field",
          autocomplete: "new-password",
          minlength: 14,
          maxlength: 1024,
          required: true,
        }),
    error = el("p", { class: "dialog-error", role: "alert" }),
    submit = el(
      "button",
      { type: "submit", class: "primary" },
      t(exists ? "unlock" : "continue"),
    );
  const form = el(
    "form",
    {
      onsubmit: async (event) => {
        event.preventDefault();
        if (submit.disabled) return;
        if (confirm && password.value !== confirm.value) {
          error.textContent = t("passphraseMismatch");
          return;
        }
        submit.disabled = true;
        let activated = false,
          activationGeneration = own;
        try {
          requireThat(
            isSecureContext && navigator.locks && crypto.subtle,
            "unsupported_browser",
          );
          const unlocked = await attempt.unlock(
            password.value,
            (unlockedVault) => {
              const nextScope = new RunScope();
              const nextClient = new HouseholdClient(unlockedVault, account, {
                scope: nextScope,
              });
              vault = unlockedVault;
              scope = nextScope;
              client = nextClient;
              generation++;
              activationGeneration = generation;
              activated = true;
            },
          );
          password.value = "";
          if (confirm) confirm.value = "";
          if (!unlocked) return;
          clearTimeout(expiryTimer);
          expiryTimer = setTimeout(
            () => {
              lock();
              account = null;
              notice("unauthenticated", true);
              render().catch(() => {});
            },
            Math.max(0, account.expiresAt - Date.now()),
          );
          busy = false;
          closeDialog();
          clearNotice();
          resetIdle();
          refreshLoop.start();
          await render();
          await syncSelected();
        } catch (e) {
          const code = dictionary[e.code]
            ? e.code
            : e.name === "OperationError"
              ? "wrong_passphrase"
              : activated
                ? "invalid"
                : "unsupported_browser";
          if (activated && activationGeneration === generation) {
            lock();
            notice(code, true);
          } else {
            candidate.lock();
            if (!attempt.cancelled && own === generation)
              error.textContent = t(code);
          }
        } finally {
          submit.disabled = false;
        }
      },
    },
    el("h2", {}, t(exists ? "unlockVault" : "vaultTitle")),
    paragraph(exists ? "unlockText" : "vaultText"),
    el("label", { for: "vault-password", class: "label" }, t("passphrase")),
    password,
    ...(confirm
      ? [
          el(
            "label",
            { for: "vault-confirm", class: "label" },
            t("confirmPassphrase"),
          ),
          confirm,
        ]
      : []),
    paragraph("passphraseHelp", "helper"),
    error,
    el(
      "div",
      { class: "button-row" },
      button(t("cancel"), () => {
        password.value = "";
        if (confirm) confirm.value = "";
        closeDialog();
      }),
      submit,
    ),
  );
  openDialog(form, () => {
    attempt.cancel();
    password.value = "";
    if (confirm) confirm.value = "";
  });
}
function householdOptions() {
  if (!vault?.key) return [];
  return Object.values(vault.read().households).flatMap((h) =>
    body(h.proofs.at(-1)).lists.map((l) => ({
      household: h.id,
      list: l.listId,
      label: h.lists[l.listId]?.confirmed?.projection.name || t("listLabel"),
    })),
  );
}
async function render() {
  const own = generation;
  $("lock").hidden = !client;
  $("refresh").hidden = !client;
  if (view !== "list") $("sync-status").textContent = t(client ? "vaultUnlocked" : "locked");
  $("section-label").textContent = t(
    view === "list"
      ? "shoppingList"
      : view === "sharing"
        ? "sharing"
        : view === "admin"
          ? "admin"
          : "account",
  );
  for (const n of document.querySelectorAll("[data-view]"))
    n.classList.toggle("active", n.dataset.view === view);
  let adminButton = document.querySelector('[data-view="admin"]');
  if (account?.user.admin && !adminButton) {
    adminButton = el(
      "button",
      {
        type: "button",
        class: "nav-button",
        "data-view": "admin",
        "data-i18n": "admin",
      },
      el("span", { class: "nav-icon" }, "✧"),
      t("admin"),
    );
    $("navigation").append(adminButton);
  }
  if (!account?.user.admin) adminButton?.remove();
  if (view === "account") {
    await renderAccount(own);
    return;
  }
  if (view === "admin") {
    await renderAdmin(own);
    return;
  }
  if (!client) {
    $("sync-status").textContent = t("locked");
    $("content").replaceChildren(welcome());
    return;
  }
  if (view === "sharing") {
    await renderSharing(own);
    return;
  }
  const options = householdOptions();
  if (
    !selected ||
    !options.some(
      (o) => o.household === selected.household && o.list === selected.list,
    )
  )
    selected = options[0] ?? null;
  if (!selected) {
    $("sync-status").textContent = t("synced");
    $("content").replaceChildren(
      title("connectTitle", "connectText"),
      joinPanel(),
    );
    return;
  }
  const h = client.read(selected.household),
    local = h.lists[selected.list];
  if (!local?.confirmed) {
    $("content").replaceChildren(
      title("listTitle", "listSubtitle"),
      paragraph("loading"),
    );
    return;
  }
  const p = await client.view(selected.household, selected.list);
  if (own !== generation) return;
  const rows = Object.values(p.rows)
      .filter((r) => !r.archived && !p.tombstones[r.id])
      .sort(
        (a, b) =>
          number(a.facts.position ?? 0) - number(b.facts.position ?? 0) ||
          (a.facts.createdAt ?? 0) - (b.facts.createdAt ?? 0) ||
          (a.id < b.id ? -1 : 1),
      ),
    needed = rows.filter((r) => !r.facts.checked),
    picked = rows.filter((r) => r.facts.checked);
  const select = el(
    "select",
    {
      class: "list-select",
      "aria-label": t("listLabel"),
      onchange: (event) => {
        const option = options[Number(event.target.value)];
        selected = option;
        act(async () => {
          await syncSelected();
        });
      },
    },
    options.map((o, i) =>
      el(
        "option",
        {
          value: i,
          ...(o.household === selected.household && o.list === selected.list
            ? { selected: true }
            : {}),
        },
        o.label,
      ),
    ),
  );
  const input = el("input", {
      id: "capture-name",
      type: "text",
      maxlength: 1000,
      autocomplete: "off",
      placeholder: t("addPlaceholder"),
      "aria-label": t("addPlaceholder"),
      required: true,
    }),
    capture = el(
      "form",
      {
        class: "capture",
        onsubmit: (event) => {
          event.preventDefault();
          const name = input.value.trim();
          if (!name || name.length > 1000) {
            notice("invalidName", true);
            return;
          }
          act(async () => {
            await client.enqueue(selected.household, selected.list, "capture", {
              proposedId: crypto.randomUUID(),
              key: canonical(normalize(name), p.aliases),
              name,
              rawText: name,
              quantity: null,
              unit: null,
              normalizerVersion: 1,
              createdAt: Date.now(),
              updatedAt: Date.now(),
              position: rows.length,
            });
            input.value = "";
            await syncSelected();
          });
        },
      },
      input,
      el("button", { type: "submit", "aria-label": t("add") }, "+"),
    );
  const section = (key, items) => [
    el(
      "div",
      { class: "list-section-label" },
      t(key),
      el("span", {}, items.length),
    ),
    el(
      "ul",
      { class: "items" },
      items.map((r) => itemNode(r)),
    ),
  ];
  const list = el(
    "section",
    { class: "paper" },
    el(
      "div",
      { class: "paper-header" },
      select,
      el("span", { class: "badge" }, t("privateByDesign")),
    ),
    capture,
    ...(needed.length ? section("toPickUp", needed) : []),
    ...(picked.length ? section("pickedUp", picked) : []),
    ...(!rows.length
      ? [
          el(
            "div",
            { class: "empty" },
            el("div", { class: "empty-mark", "aria-hidden": "true" }, "✧"),
            el("h2", {}, t("emptyTitle")),
            paragraph("emptyText"),
          ),
        ]
      : []),
    el("progress", {
      class: "visually-hidden",
      value: picked.length,
      max: Math.max(rows.length, 1),
      "aria-label": t("pickedUp"),
    }),
  );
  const aside = el(
    "aside",
    { class: "aside-card" },
    el("p", { class: "eyebrow" }, t("yourEveryday")),
    el("h2", {}, t("quietTitle")),
    el("p", {}, t("quietText")),
    el(
      "div",
      { class: "fine-print" },
      el("span", { "aria-hidden": "true" }, "◇"),
      t("privateNote"),
    ),
  );
  $("sync-status").textContent = t(local.pending.length ? "pending" : "synced");
  $("content").replaceChildren(
    title(
      "listTitle",
      "listSubtitle",
      el("span", { class: "pill" }, t("itemCount", { count: needed.length })),
    ),
    el("div", { class: "list-layout" }, list, aside),
  );
  if (Object.keys(p.conflicts).length || local.pending.some((x) => x.blocked))
    notice("conflictNotice", true);
}
function itemNode(row) {
  const checked = row.facts.checked === true || row.facts.checked === "true",
    check = el(
      "button",
      {
        type: "button",
        class: "check",
        role: "checkbox",
        id: `check-${row.id}`,
        "aria-checked": checked,
        "aria-label": t(checked ? "uncheckItem" : "checkItem", {
          name: row.facts.name,
        }),
        onclick: () =>
          act(async () => {
            await client.enqueue(selected.household, selected.list, "check", {
              target: row.id,
              expected: { check: row.revisions.check ?? null },
              checked: !checked,
              checkedAt: checked ? null : Date.now(),
              updatedAt: Date.now(),
            });
            await syncSelected();
          }),
      },
      checked ? "✓" : "",
    );
  const metadata = [];
  if (row.facts.quantity != null)
    metadata.push(
      t("quantityLabel", {
        amount: new Intl.NumberFormat(locale, {
          maximumFractionDigits: 10,
        }).format(number(row.facts.quantity)),
        unit: t(row.facts.unit),
      }),
    );
  if (row.facts.store) metadata.push(row.facts.store);
  if (row.facts.note) metadata.push(row.facts.note);
  return el(
    "li",
    { class: `item${checked ? " checked" : ""}` },
    check,
    el(
      "div",
      { class: "item-info" },
      el("div", { class: "item-name" }, row.facts.name),
      metadata.length
        ? el("div", { class: "item-meta" }, metadata.join(" · "))
        : null,
    ),
    button("···", () => editDialog(row), "item-edit", {
      "aria-label": t("editItem", { name: row.facts.name }),
    }),
  );
}
function editDialog(row) {
  const fields = {};
  const field = (key, type = "text", max = 1000) => {
    const node = el("input", {
      id: `edit-${key}`,
      class: "field",
      type,
      maxlength: max,
      value:
        key === "quantity"
          ? (row.facts.quantity ?? "")
          : (row.facts[key] ?? ""),
      ...(type === "number" ? { min: 0, step: "any" } : {}),
    });
    fields[key] = node;
    return [el("label", { class: "label", for: `edit-${key}` }, t(key)), node];
  };
  const unit = el(
      "select",
      { id: "edit-unit", class: "field" },
      ["PIECE", "PACK", "GRAM", "KILOGRAM", "MILLILITRE", "LITRE"].map((k) =>
        el(
          "option",
          { value: k, ...(row.facts.unit === k ? { selected: true } : {}) },
          t(k),
        ),
      ),
    ),
    error = el("p", { class: "dialog-error", role: "alert" });
  const form = el(
    "form",
    {
      onsubmit: (event) => {
        event.preventDefault();
        const name = fields.name.value.trim(),
          amount = fields.quantity.value;
        try {
          requireThat(name && name.length <= 1000, "invalidName");
          requireThat(
            amount === "" ||
              (Number.isFinite(Number(amount)) && Number(amount) >= 0),
            "invalidQuantity",
          );
          const changes = {};
          for (const k of ["name", "note", "store"]) {
            const value = fields[k].value.trim() || null;
            if (value !== (row.facts[k] ?? null)) changes[k] = value;
          }
          if (changes.name) changes.key = normalize(name);
          const quantity = amount === "" ? null : decimal(Number(amount)),
            selectedUnit = amount === "" ? null : unit.value;
          if (
            quantity !== (row.facts.quantity ?? null) ||
            selectedUnit !== (row.facts.unit ?? null)
          ) {
            changes.quantity = quantity;
            changes.unit = selectedUnit;
          }
          if (!Object.keys(changes).length) {
            closeDialog();
            return;
          }
          const expected = {};
          for (const k of Object.keys(changes))
            expected[
              ["name", "key", "gtin"].includes(k)
                ? "name"
                : ["quantity", "unit"].includes(k)
                  ? "quantity"
                  : k
            ] =
              row.revisions[
                ["name", "key", "gtin"].includes(k)
                  ? "name"
                  : ["quantity", "unit"].includes(k)
                    ? "quantity"
                    : k
              ] ?? null;
          changes.updatedAt = Date.now();
          closeDialog();
          act(async () => {
            await client.enqueue(selected.household, selected.list, "edit", {
              target: row.id,
              changes,
              expected,
            });
            await syncSelected();
          });
        } catch (e) {
          error.textContent = t(e.code ?? "invalid");
        }
      },
    },
    el("h2", {}, t("editItem", { name: row.facts.name })),
    ...field("name"),
    el(
      "div",
      { class: "row-grid" },
      el("div", {}, ...field("quantity", "number")),
      el(
        "div",
        {},
        el("label", { class: "label", for: "edit-unit" }, t("unit")),
        unit,
      ),
    ),
    ...field("note", "text", 8000),
    ...field("store", "text", 80),
    error,
    el(
      "div",
      { class: "button-row" },
      button(
        t("remove"),
        () => {
          closeDialog();
          act(async () => {
            await client.enqueue(selected.household, selected.list, "delete", {
              target: row.id,
              expected: { lifecycle: row.lifecycle ?? null },
              deletedAt: Date.now(),
              updatedAt: Date.now(),
            });
            await syncSelected();
          });
        },
        "danger",
      ),
      button(t("cancel"), closeDialog),
      el("button", { type: "submit", class: "primary" }, t("save")),
    ),
  );
  openDialog(form);
}
async function refreshSelected() {
  if (!client) return;
  const own = generation,
    options = householdOptions();
  if (
    !selected ||
    !options.some(
      (o) => o.list === selected.list && o.household === selected.household,
    )
  )
    selected = options[0] ?? null;
  if (!selected) return;
  try {
    const focusId = document.activeElement?.id;
    await client.synchronize(selected.household, selected.list);
    if (own !== generation) return;
    clearNotice();
    await render();
    if (own === generation && focusId)
      $(focusId)?.focus({ preventScroll: true });
  } catch (e) {
    if (own !== generation) return;
    await render();
    failure(e);
  }
}
function joinPanel() {
  if (vault.read().creating) return el("section", { class: "paper panel-body" },
    el("h2", {}, t("creationPending")), paragraph("creationPendingHelp"),
    button(t("creationResume"), () => act(() => finishHouseholdSetup(c => c.completeCreation())), "primary"), vault.read().creating.rejected ? button(t("creationAbandon"), () =>
      messageDialog("creationAbandon", "creationAbandonHelp", () => client.abandonRejectedCreation())) : null);
  if (vault.read().joining) return fingerprintPanel();
  const input = el("textarea", {
    id: "invite",
    class: "field",
    maxlength: 4096,
    rows: 4,
    required: true,
    spellcheck: false,
    autocomplete: "off",
  });
  return el(
    "div",
    { class: "panel" },
    el("section", { class: "paper panel-body" }, el("h2", {}, t("createHouseholdTitle")),
      paragraph("createHouseholdText"), button(t("createHousehold"), () => act(createHouseholdDialog), "primary")),
    Object.keys(vault.read().households).length ? null : firstHouseholdPanel(),
    el(
      "section",
      { class: "paper" },
      el(
        "form",
        {
          class: "panel-body",
          onsubmit: (event) => {
            event.preventDefault();
            const value = input.value.trim();
            input.value = "";
            act(async () => {
              await client.requestJoin(value);
              view = "sharing";
            });
          },
        },
        el("h2", {}, t("joinTitle")),
        paragraph("joinText"),
        el("label", { class: "label", for: "invite" }, t("invitation")),
        input,
        paragraph("joinHelp", "helper"),
        el(
          "div",
          { class: "button-row" },
          el("button", { type: "submit", class: "primary" }, t("join")),
        ),
      ),
    ),
  );
}
function firstHouseholdPanel() {
  return el(
    "details",
    { class: "paper panel-body" },
    el("summary", {}, t("firstHouseholdTitle")),
    paragraph("firstHouseholdText"),
    el(
      "ol",
      { class: "setup-steps" },
      el("li", {}, t("firstHouseholdStart"), " ",
        el("code", { class: "server-address" }, location.origin)),
      el("li", {}, t("firstHouseholdCode")),
      el("li", {}, t("firstHouseholdShare")),
      el("li", {}, t("firstHouseholdInvite")),
    ),
  );
}
async function finishHouseholdSetup(start) {
  const own = generation;
  try {
    selected = await start(client); view = "list"; await syncSelected();
  } catch (error) {
    if (own === generation) await render();
    throw error;
  }
}
async function createHouseholdDialog() {
  const own = generation, active = client;
  let draft = await prepareHousehold(location.origin, await active.info());
  if (own !== generation) return;
  const url = URL.createObjectURL(new Blob([draft.file], { type: "application/json" }));
  const name = el("input", { id: "new-list-name", class: "field", required: true, maxlength: 100, autocomplete: "off" });
  const key = el("input", { id: "recovery-key-check", class: "field", type: "password", required: true, autocomplete: "off", spellcheck: false });
  const file = el("input", { id: "recovery-file-check", type: "file", accept: ".json,application/json", required: true });
  const saved = el("input", { type: "checkbox", required: true });
  const error = el("p", { class: "dialog-error", role: "alert" });
  openDialog(el("form", { onsubmit: event => {
    event.preventDefault();
    act(async () => {
      requireThat(draft && saved.checked && file.files.length === 1 && file.files[0].size <= 8 * 1024 * 1024);
      error.textContent = "";
      let verified;
      try { verified = await verifySavedHousehold(draft, new Uint8Array(await file.files[0].arrayBuffer()), key.value.trim()); }
      catch { if (own === generation) error.textContent = t("recoveryReadBackError"); return; }
      if (own !== generation) return;
      const label = name.value.trim();
      closeDialog();
      await finishHouseholdSetup(c => c.createHousehold(verified, label));
    });
  } }, el("h2", {}, t("createHouseholdTitle")), paragraph("recoverySaveHelp"),
  el("label", { class: "label", for: "new-list-name" }, t("listName")), name,
  el("a", { href: url, download: "usuals-recovery.json", class: "secondary" }, t("recoveryDownload")),
  el("p", { class: "label" }, t("recoveryKey")), el("p", { class: "fingerprint" }, draft.secret),
  paragraph("recoveryKeyHelp", "helper"),
  el("label", { class: "label", for: "recovery-file-check" }, t("recoveryReadBack")), file,
  el("label", { class: "label", for: "recovery-key-check" }, t("recoveryKeyCheck")), key,
  el("label", { class: "check-row" }, saved, t("recoverySaved")), error,
  el("div", { class: "button-row" }, button(t("cancel"), closeDialog),
    el("button", { type: "submit", class: "primary" }, t("createHousehold")))),
  () => { URL.revokeObjectURL(url); draft = null; key.value = ""; file.value = ""; });
  name.focus();
}
async function invitationDialog(id) {
  const own = generation, uri = await client.invite(id);
  if (own !== generation) return;
  const input = el("textarea", { class: "field", rows: 5, readonly: true, value: uri, spellcheck: false,
    "aria-label": t("invitation") });
  openDialog(el("div", {}, el("h2", {}, t("ownerInvite")), paragraph("ownerInviteHelp"), input,
    el("div", { class: "button-row" }, button(t("close"), closeDialog))), () => { input.value = ""; });
  input.focus(); input.select();
}
async function applicantsDialog(id) {
  const own = generation, requests = await client.applicants(id);
  if (own !== generation) return;
  openDialog(el("div", {}, el("h2", {}, t("ownerRequests")), paragraph("ownerApproveHelp"),
    ...requests.map(r => {
      const error = el("p", { class: "dialog-error", role: "alert" });
      const input = el("input", { class: "field", required: true, maxlength: 100, autocomplete: "off", spellcheck: false,
        "aria-label": t("ownerFingerprintInput") });
      return el("form", { class: "paper panel-body", onsubmit: event => {
        event.preventDefault(); const trusted = input.value;
        act(async () => {
          error.textContent = "";
          try { await client.approve(id, r.pending, trusted); closeDialog(); }
          catch (e) { if (own === generation) error.textContent = t(e.code === "unverified" ? "ownerFingerprintMismatch" : dictionary[e.code] ? e.code : "serverUnavailable"); }
        });
      } }, el("p", { class: "fingerprint" }, r.fingerprint.match(/.{1,4}/g).join(" ")),
      el("label", { class: "label" }, t("ownerFingerprintInput"), input), error,
      el("div", { class: "button-row" }, button(t("ownerDeny"), () => act(async () => {
        await client.denyApplicant(id, r.pending.requestId); closeDialog();
      })), el("button", { type: "submit", class: "primary" }, t("ownerApprove"))));
    }), requests.length ? null : paragraph("ownerNoRequests"),
    el("div", { class: "button-row" }, button(t("close"), closeDialog))));
}
function fingerprintPanel() {
  const value = el("p", {
    class: "fingerprint",
    "aria-label": t("fingerprint"),
  });
  const own = generation;
  client
    .pendingFingerprint()
    .then((f) => {
      if (own === generation) value.textContent = f.match(/.{1,4}/g).join(" ");
    })
    .catch((error) => {
      if (own === generation) failure(error);
    });
  return el(
    "section",
    { class: "paper panel" },
    el(
      "div",
      { class: "panel-body" },
      el("h2", {}, t("fingerprintTitle")),
      paragraph("fingerprintText"),
      value,
      el(
        "div",
        { class: "button-row" },
        button(
          t("checkApproval"),
          () =>
            act(async () => {
              const joined = await client.completeJoin();
              if (!joined) {
                notice("waitingApproval");
                return;
              }
              view = "list";
              selected = null;
              await render();
              await syncSelected();
              notice("joined");
            }),
          "primary",
        ),
      ),
    ),
  );
}
async function renderSharing(own) {
  const state = vault.read(),
    panels = [];
  for (const h of Object.values(state.households)) {
    const m = body(h.proofs.at(-1)),
      isOwner = !h.removed && m.members.some(member => member.role === "owner" && member.devices.some(d => d.id === h.device.publicKeys.id)),
      rows = [];
    for (const member of m.members)
      for (const device of member.devices) {
        const print = hex(
          unb64(await fingerprint(h.server.serverId, h.id, device), 32),
        );
        if (own !== generation) return;
        rows.push(
          el(
            "div",
            { class: "account-row" },
            el("div", { class: "avatar", "aria-hidden": "true" }, "◇"),
            el(
              "div",
              { class: "account-info" },
              el(
                "div",
                { class: "account-name" },
                device.id === h.device.publicKeys.id
                  ? t("deviceThis")
                  : t(member.role),
              ),
              el(
                "div",
                { class: "account-detail" },
                print.match(/.{1,4}/g).join(" "),
              ),
            ),
            el("span", { class: "badge" }, t(member.role)),
            isOwner && device.id !== h.device.publicKeys.id ? button(t("ownerRemove"), () =>
              messageDialog("ownerRemove", "ownerRemoveHelp", () => client.removeDevice(h.id, device.id))) : null,
          ),
        );
      }
    panels.push(
      el(
        "section",
        { class: "paper" },
        el(
          "div",
          { class: "panel-body" },
          el("p", { class: "eyebrow" }, t("householdLabel")),
          el("h2", {}, t("householdDevices")),
          ...rows,
          paragraph(isOwner ? "ownerHelp" : "householdHelp", "helper"),
          isOwner ? el("div", { class: "button-row" },
            button(t("ownerInvite"), () => act(() => invitationDialog(h.id)), "primary"),
            button(t("ownerRequests"), () => act(() => applicantsDialog(h.id))),
            h.pendingMembership ? button(t("ownerDiscardObsolete"), () =>
              messageDialog("ownerDiscardObsolete", "ownerDiscardHelp", () => client.discardInvalidMembership(h.id))) : null) : null,
        ),
      ),
    );
  }
  panels.push(joinPanel());
  if (own !== generation) return;
  $("content").replaceChildren(
    title("connectTitle", "connectText"),
    el(
      "div",
      { class: "panel" },
      ...panels,
      paragraph("browserTrust", "helper"),
    ),
  );
}
const date = (n) =>
  Number.isFinite(n)
    ? new Intl.DateTimeFormat(locale, {
        dateStyle: "medium",
        timeStyle: "short",
      }).format(n)
    : t("dateUnknown");
const accountLabel = (user) =>
  user.displayName || user.username || user.email || user.subject;
async function renderAccount(own) {
  if (!account) {
    $("content").replaceChildren(welcome());
    return;
  }
  const user = account.user,
    card = el(
      "section",
      { class: "paper" },
      el(
        "div",
        { class: "panel-body" },
        el("p", { class: "eyebrow" }, t("signedInAs")),
        el(
          "div",
          { class: "account-row" },
          el(
            "div",
            { class: "avatar", "aria-hidden": "true" },
            accountLabel(user).slice(0, 1).toUpperCase(),
          ),
          el(
            "div",
            { class: "account-info" },
            el("div", { class: "account-name" }, accountLabel(user)),
            el(
              "div",
              { class: "account-detail" },
              user.username || user.email || user.subject,
            ),
          ),
          user.admin
            ? el("span", { class: "badge" }, t("administrator"))
            : null,
        ),
        paragraph("accountEncryption", "helper"),
        el(
          "div",
          { class: "button-row" },
          ...(user.issuer === "urn:usuals:local"
            ? [button(t("changePassword"), passwordDialog)]
            : []),
          button(t("signOut"), () =>
            act(async () => {
              const csrf = account.csrfToken;
              lock();
              await api("/auth/logout", { method: "POST", csrf, status: 204 });
              account = null;
              view = "list";
              await render();
            }),
          ),
        ),
      ),
    );
  let sessions;
  try {
    sessions = (await api("/api/sessions")).sessions;
  } catch (e) {
    if (own !== generation) return;
    failure(e);
    sessions = [];
  }
  if (own !== generation) return;
  const list = el(
    "section",
    { class: "paper" },
    el(
      "div",
      { class: "panel-body" },
      el("h2", {}, t("sessions")),
      ...sessions.map((s) =>
        el(
          "div",
          { class: "account-row" },
          el(
            "div",
            { class: "account-info" },
            el(
              "div",
              { class: "account-name" },
              s.current
                ? t("currentSession")
                : t("sessionCreated", { date: date(s.createdAt) }),
            ),
            el(
              "div",
              { class: "account-detail" },
              t("sessionExpiry", { date: date(s.expiresAt) }),
            ),
          ),
          button(
            t("revoke"),
            () =>
              messageDialog("confirmRevoke", "confirmRevokeText", async () => {
                await api(`/api/sessions/${s.id}/revoke`, {
                  method: "POST",
                  csrf: account.csrfToken,
                  status: 204,
                });
                if (s.current) {
                  lock();
                  account = null;
                  view = "list";
                  await render();
                }
              }),
            "secondary",
          ),
        ),
      ),
      paragraph("sessionHelp", "helper"),
    ),
  );
  $("content").replaceChildren(
    title("accountTitle", "accountSubtitle"),
    el(
      "div",
      { class: "panel" },
      card,
      list,
      paragraph("browserTrust", "helper"),
    ),
  );
}
async function renderAdmin(own, after = null, previous = []) {
  if (!account?.user.admin) {
    view = "account";
    await render();
    return;
  }
  let result;
  try {
    result = await api(
      "/api/admin/users" + (after ? "?after=" + encodeURIComponent(after) : ""),
    );
  } catch (e) {
    if (own !== generation) return;
    failure(e);
    return;
  }
  if (own !== generation) return;
  const people = [...previous, ...result.users],
    rows = people.map((u) =>
      el(
        "div",
        { class: "account-row" },
        el(
          "div",
          { class: "avatar", "aria-hidden": "true" },
          accountLabel(u).slice(0, 1).toUpperCase(),
        ),
        el(
          "div",
          { class: "account-info" },
          el("div", { class: "account-name" }, accountLabel(u)),
          el(
            "div",
            { class: "account-detail" },
            u.username || u.email || u.subject,
          ),
        ),
        u.disabled
          ? el("span", { class: "badge" }, t("disabled"))
          : u.admin
            ? el("span", { class: "badge" }, t("administrator"))
            : null,
        button(
          t(u.disabled ? "enable" : "disable"),
          () =>
            u.disabled
              ? act(async () => {
                  await api(`/api/admin/users/${u.id}`, {
                    method: "POST",
                    body: { disabled: false },
                    csrf: account.csrfToken,
                  });
                })
              : messageDialog(
                  "confirmDisable",
                  "confirmDisableText",
                  async () => {
                    await api(`/api/admin/users/${u.id}`, {
                      method: "POST",
                      body: { disabled: true },
                      csrf: account.csrfToken,
                    });
                    if (u.id === account.user.id) {
                      lock();
                      account = null;
                      view = "list";
                      await render();
                    }
                  },
                ),
          "secondary",
        ),
        ...(u.issuer === "urn:usuals:local" && u.id !== account.user.id
          ? [
              button(
                t("resetPassword"),
                () =>
                  messageDialog(
                    "resetPasswordTitle",
                    "resetPasswordText",
                    async () => {
                      const own = generation;
                      const result = await api(
                        `/api/admin/users/${u.id}/reset-local-password`,
                        { method: "POST", csrf: account.csrfToken },
                      );
                      if (own !== generation) return;
                      await render();
                      if (own === generation) secretDialog(result);
                    },
                  ),
                "secondary",
              ),
            ]
          : []),
        button(
          t("revokeAll"),
          () =>
            messageDialog("confirmRevoke", "confirmRevokeText", async () => {
              await api(`/api/admin/users/${u.id}/revoke-sessions`, {
                method: "POST",
                csrf: account.csrfToken,
                status: 204,
              });
              if (u.id === account.user.id) {
                lock();
                account = null;
                view = "list";
                await render();
              }
            }),
          "secondary",
        ),
      ),
    );
  $("content").replaceChildren(
    title("adminTitle", "adminSubtitle"),
    el(
      "div",
      { class: "panel" },
      paragraph("adminHelp", "warning-box"),
      ...(config?.localEnabled
        ? [
            el(
              "div",
              { class: "button-row" },
              button(t("createLocalUser"), localUserDialog, "primary"),
            ),
          ]
        : []),
      el(
        "section",
        { class: "paper" },
        el(
          "div",
          { class: "panel-body" },
          ...rows,
          ...(!rows.length ? [paragraph("noPeople")] : []),
          ...(result.next
            ? [
                el(
                  "div",
                  { class: "button-row" },
                  button(t("morePeople"), () =>
                    act(() => renderAdmin(own, result.next, people), {
                      rerender: false,
                    }),
                  ),
                ),
              ]
            : []),
        ),
      ),
    ),
  );
}
$("navigation").addEventListener("click", (event) => {
  const node = event.target.closest("[data-view]");
  if (node) {
    view = node.dataset.view;
    clearNotice();
    render().catch(failure);
  }
});
const refreshLoop = new RefreshLoop({
  refresh: refreshSelected,
  canRun: () =>
    Boolean(client) &&
    !document.hidden &&
    !busy &&
    view === "list" &&
    !$("dialog").open &&
    !(
      $("content").contains(document.activeElement) &&
      ["INPUT", "TEXTAREA", "SELECT"].includes(document.activeElement.tagName)
    ),
});
function syncSelected() {
  return refreshLoop.run(true);
}
window.addEventListener("online", () => {
  if (client && !document.hidden && !busy) refreshLoop.run().catch(failure);
});
$("dialog").addEventListener("cancel", (event) => {
  event.preventDefault();
  if (tutorialActive) finishTutorial();
  else closeDialog();
});
$("tutorial").addEventListener("click", tutorialDialog);
$("language").addEventListener("click", languageDialog);
$("appearance").addEventListener("click", appearanceDialog);
$("lock").addEventListener("click", lock);
$("refresh").addEventListener("click", () => act(syncSelected));
document.addEventListener("visibilitychange", () => {
  if (document.hidden) lock();
});
window.addEventListener("pagehide", lock);
for (const event of ["pointerdown", "keydown"])
  document.addEventListener(event, resetIdle, { passive: true });
(async () => {
  const inventory = await fetch("/locales.json", {
    credentials: "same-origin",
    cache: "no-store",
    redirect: "error",
  });
  requireThat(inventory.ok);
  availableLocales = await inventory.json();
  requireThat(
    Array.isArray(availableLocales) &&
      availableLocales.includes("en") &&
      availableLocales.every((code) =>
        /^[a-z]{2}(?:-[A-Za-z0-9]{2,8})?$/.test(code),
      ),
  );
  let preferred = "en";
  try {
    const saved = localStorage.getItem("usuals-language");
    if (availableLocales.includes(saved)) preferred = saved;
  } catch {}
  await localeLoad(preferred);
  if (location.hash) {
    history.replaceState(null, "", location.pathname + location.search);
  }
  $("content").replaceChildren(paragraph("loading"));
  await loadAccount();
  if (!tutorialSeen) tutorialDialog();
})().catch(failure);

function passwordValid(value) {
  const length = [...value].length;
  return (
    length >= 15 && length <= 128 && !/[\u0000-\u001f\u007f-\u009f]/.test(value)
  );
}
function usernameValid(value) {
  return /^[a-zA-Z0-9._-]{3,64}$/.test(value);
}
function localAuthDialog(activation = false) {
  const username = el("input", {
      id: "local-username",
      class: "field",
      autocomplete: "username",
      minlength: 3,
      maxlength: 64,
      required: true,
    }),
    password = el("input", {
      id: "local-password",
      class: "field",
      type: "password",
      autocomplete: activation ? "new-password" : "current-password",
      required: true,
    }),
    secret = activation
      ? el("input", {
          id: "local-setup",
          class: "field",
          type: "password",
          autocomplete: "off",
          required: true,
        })
      : null,
    error = el("p", { class: "dialog-error", role: "alert" }),
    submit = el(
      "button",
      { type: "submit", class: "primary" },
      t(activation ? "activate" : "localSignIn"),
    ),
    own = generation;
  openDialog(
    el(
      "form",
      {
        onsubmit: async (event) => {
          event.preventDefault();
          if (submit.disabled) return;
          submit.disabled = true;
          try {
            requireThat(usernameValid(username.value), "invalidUsername");
            if (activation)
              requireThat(passwordValid(password.value), "invalidPassword");
            const challenge = await api("/auth/local/challenge");
            if (own !== generation) return;
            requireThat(challenge.expiresAt > Date.now());
            await api("/auth/local/" + (activation ? "activate" : "login"), {
              method: "POST",
              csrf: challenge.csrfToken,
              body: {
                username: username.value,
                password: password.value,
                ...(secret ? { setupToken: secret.value } : {}),
              },
              status: 204,
            });
            password.value = "";
            if (secret) secret.value = "";
            if (own !== generation) return;
            closeDialog();
            clearNotice();
            await loadAccount();
          } catch (e) {
            if (own !== generation) return;
            error.textContent = t(
              e.code === "unauthenticated"
                ? "localLoginFailed"
                : dictionary[e.code]
                  ? e.code
                  : "invalid",
            );
          } finally {
            submit.disabled = false;
          }
        },
      },
      el("h2", {}, t(activation ? "activateAccount" : "localTitle")),
      paragraph(activation ? "activationText" : "localSignInText"),
      el("label", { class: "label", for: "local-username" }, t("username")),
      username,
      ...(secret
        ? [
            el(
              "label",
              { class: "label", for: "local-setup" },
              t("setupToken"),
            ),
            secret,
          ]
        : []),
      el(
        "label",
        { class: "label", for: "local-password" },
        t(activation ? "newAccountPassword" : "accountPassword"),
      ),
      password,
      ...(activation ? [paragraph("passwordHelp", "helper")] : []),
      error,
      el(
        "div",
        { class: "button-row" },
        button(t("cancel"), closeDialog),
        submit,
      ),
    ),
  );
}
function passwordDialog() {
  const current = el("input", {
      id: "current-account-password",
      type: "password",
      class: "field",
      autocomplete: "current-password",
      required: true,
    }),
    next = el("input", {
      id: "new-account-password",
      type: "password",
      class: "field",
      autocomplete: "new-password",
      required: true,
    }),
    error = el("p", { class: "dialog-error", role: "alert" }),
    submit = el(
      "button",
      { type: "submit", class: "primary" },
      t("changePassword"),
    ),
    own = generation;
  openDialog(
    el(
      "form",
      {
        onsubmit: async (event) => {
          event.preventDefault();
          if (submit.disabled) return;
          submit.disabled = true;
          try {
            requireThat(passwordValid(next.value), "invalidPassword");
            await api("/auth/local/password", {
              method: "POST",
              csrf: account.csrfToken,
              body: { currentPassword: current.value, newPassword: next.value },
              status: 204,
            });
            current.value = "";
            next.value = "";
            if (own !== generation) return;
            lock();
            account = null;
            view = "list";
            await render();
            notice("passwordChanged");
          } catch (e) {
            if (own === generation)
              error.textContent = t(dictionary[e.code] ? e.code : "invalid");
          } finally {
            submit.disabled = false;
          }
        },
      },
      el("h2", {}, t("changePassword")),
      paragraph("passwordHelp"),
      el(
        "label",
        { class: "label", for: "current-account-password" },
        t("currentAccountPassword"),
      ),
      current,
      el(
        "label",
        { class: "label", for: "new-account-password" },
        t("newAccountPassword"),
      ),
      next,
      error,
      el(
        "div",
        { class: "button-row" },
        button(t("cancel"), closeDialog),
        submit,
      ),
    ),
  );
}
function localUserDialog() {
  const username = el("input", {
      id: "create-username",
      class: "field",
      autocomplete: "off",
      minlength: 3,
      maxlength: 64,
      required: true,
    }),
    name = el("input", {
      id: "create-display-name",
      class: "field",
      autocomplete: "off",
      maxlength: 160,
      required: true,
    }),
    error = el("p", { class: "dialog-error", role: "alert" }),
    submit = el("button", { type: "submit", class: "primary" }, t("create")),
    own = generation;
  openDialog(
    el(
      "form",
      {
        onsubmit: async (event) => {
          event.preventDefault();
          if (submit.disabled) return;
          submit.disabled = true;
          try {
            requireThat(usernameValid(username.value), "invalidUsername");
            const result = await api("/api/admin/local-users", {
              method: "POST",
              status: 201,
              body: {
                username: username.value,
                displayName: name.value.trim(),
              },
              csrf: account.csrfToken,
            });
            if (own !== generation) return;
            closeDialog();
            await render();
            if (own === generation) secretDialog(result);
          } catch (e) {
            if (own === generation)
              error.textContent = t(dictionary[e.code] ? e.code : "invalid");
          } finally {
            submit.disabled = false;
          }
        },
      },
      el("h2", {}, t("createLocalUser")),
      paragraph("createUserText"),
      el("label", { class: "label", for: "create-username" }, t("username")),
      username,
      paragraph("usernameHelp", "helper"),
      el(
        "label",
        { class: "label", for: "create-display-name" },
        t("displayName"),
      ),
      name,
      error,
      el(
        "div",
        { class: "button-row" },
        button(t("cancel"), closeDialog),
        submit,
      ),
    ),
  );
}
function secretDialog(result) {
  const secret = el("input", {
    id: "one-use-secret",
    class: "field",
    readonly: true,
    value: result.setupToken,
    autocomplete: "off",
    spellcheck: false,
  });
  openDialog(
    el(
      "div",
      {},
      el("h2", {}, t("activationSecretTitle")),
      el(
        "p",
        { class: "lede" },
        t("activationSecretText", { date: date(result.expiresAt) }),
      ),
      el("label", { class: "label", for: "one-use-secret" }, t("setupToken")),
      secret,
      el(
        "div",
        { class: "button-row" },
        button(t("close"), () => {
          secret.value = "";
          closeDialog();
        }),
      ),
    ),
  );
}
function welcomeActions(signed) {
  if (signed)
    return [button(t("unlockVault"), () => unlockDialog().catch(failure), "primary")];
  if (!config?.enabled) return [paragraph("ssoDisabled", "warning-box")];
  const actions = [];
  if (config.localEnabled) {
    actions.push(
      button(t("localSignIn"), () => localAuthDialog(false), "primary"),
    );
    actions.push(
      button(t("activateAccount"), () => localAuthDialog(true), "secondary"),
    );
  }
  if (config.oidcEnabled)
    actions.push(
      el(
        "a",
        {
          class: config.localEnabled ? "secondary" : "primary",
          href: "/auth/login",
        },
        t("signIn"),
      ),
    );
  return actions;
}
