import { requireThat, hash } from "./wire.mjs";
import { RunScope, request } from "./client.mjs";
import { ListStore, ListFeed, ListsApi, access, archivedAction, arrange, problem } from "./lists.mjs";
import { rows as listRows, isChecked, intent, captures, check, edit, remove, rename, finish, undo, decimal } from "./intents.mjs";
import { normalize } from "./identity.mjs";
import { listColors, listColor, colorChange } from "./list-colors.mjs";
import { palettes, readPalette, writePalette, backgroundStyles, readBackgroundStyle, writeBackgroundStyle } from "./appearance.mjs";
import { tutorialCompleted, completeTutorial } from "./tutorial.mjs";
import { qrMatrix } from "./qr.mjs";
import { connectLink, connectAppLink, parseConnectLink, connectionCode } from "./connect.mjs";
import { importDialog } from "./import-dialog.mjs";
import { forgetLegacyVault } from "./legacy.mjs";
import { DialogFocus, DialogCancellation } from "./dialog-focus.mjs";
import { invitationToken, InvitationsApi } from "./invitations.mjs";

forgetLegacyVault();
let pendingInvitationToken = invitationToken(location), invitation = null;
if (location.pathname === "/join") history.replaceState(null, "", "/join");
const ticketPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
// A phone without a signed-in computer nearby opens /?connect=<ticket>; the ticket waits in this tab through sign-in.
let pendingConnectionId = null, connectionDone = false;
try {
  const candidate = new URL(location.href).searchParams.get("connect");
  if (candidate && ticketPattern.test(candidate)) sessionStorage.setItem("usuals-connect-request", candidate);
  pendingConnectionId = sessionStorage.getItem("usuals-connect-request");
} catch { pendingConnectionId = new URL(location.href).searchParams.get("connect"); }
// A computer's connect QR opened by a phone camera: it only hands the code to the app, so it needs no sign-in here.
let pendingConnect = (() => {
  try { return location.pathname === "/connect" && location.hash.startsWith("#1.") ? (parseConnectLink(location.href), location.href) : null; }
  catch { return null; }
})();
const spaced = (code) => code.slice(0, 3) + " " + code.slice(3);
const $ = (id) => document.getElementById(id);
function localStore() {
  try { return localStorage; } catch { return null; }
}
let palette = readPalette(localStore());
let backgroundStyle = readBackgroundStyle(localStore());
let tutorialSeen = tutorialCompleted(localStore()), tutorialActive = false;
function applyAppearance() {
  document.documentElement.dataset.palette = palette;
  document.documentElement.dataset.backgroundStyle = backgroundStyle;
  document.querySelector('meta[name="theme-color"]').content =
    getComputedStyle(document.documentElement).getPropertyValue("--primary").trim();
}
applyAppearance();
window.matchMedia("(prefers-color-scheme: dark)").addEventListener("change", applyAppearance);
let uiScope = new RunScope();
const api = (path, options = {}) => request(path, { ...options, scope: uiScope });
let importResources = null;
function loadImportResources() {
  if (!importResources) importResources = Promise.all([api("/assets/import-language.json"), api("/model-origins.json"), api("/v1/instance").catch(() => ({ ai: false }))])
    .catch(error => { importResources = null; throw error; });
  return importResources;
}
let availableLocales = ["en"],
  dictionary = {},
  locale = "en",
  account = null,
  config = null,
  view = "list",
  screen = null,
  selected = null,
  generation = 0,
  busy = false,
  dialogCleanup = null,
  dialogRefresh = null,
  store = null,
  lists = null,
  feed = null,
  syncState = "";
const invitations = new InvitationsApi({ transport: api, csrf: () => account?.csrfToken });
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
    else if (key === "value") node.value = value;
    else if (key === "checked") node.checked = value;
    else if (value !== undefined && value !== null) node.setAttribute(key, String(value));
  }
  for (const child of children.flat())
    if (child !== null && child !== undefined)
      node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  return node;
}
const button = (label, action, kind = "secondary", attributes = {}) =>
  el("button", { type: "button", class: kind, onclick: action, ...attributes }, label);
const paragraph = (key, kind = "lede", values = {}) => el("p", { class: kind }, t(key, values));
const brandCollage = (kind = "") => el("div", { class: `brand-collage ${kind}`, "aria-hidden": "true" },
  ...["secondary", "tertiary", "paper", "primary"].map((role) => el("span", { class: `collage-${role}` })));
const loadingAppearance = () => el("div", { class: "loading-appearance", role: "status" },
  brandCollage("loading-collage"), paragraph("loading", "helper"));
/** `action` is an optional [label, handler] pair, such as Undo. */
function notice(key, error = false, values = {}, action = null) {
  const node = $("notice");
  node.replaceChildren(t(key, values), ...(action ? [" ", button(action[0], action[1], "text-button")] : []));
  node.classList.toggle("error", error);
  node.hidden = false;
}
function clearNotice() {
  $("notice").hidden = true;
}
function failure(error) {
  if (error?.name === "AbortError" || error?.code === "locked") return;
  if (error?.code === "unauthenticated" && account) {
    signedOut();
    return;
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
function setSync(state) {
  syncState = state;
  for (const node of document.querySelectorAll("[data-sync-status]")) {
    node.textContent = account && state ? t(state) : "";
    node.dataset.syncState = state;
  }
}
function startLists() {
  store = new ListStore();
  lists = new ListsApi({ store, csrf: () => account.csrfToken, transport: api });
  feed = new ListFeed({
    store,
    onUpdate: () => {
      dialogRefresh?.();
      if (screen === "lists") renderLists();
    },
    onStatus: setSync,
    onSignedOut: () => signedOut(),
  });
  feed.start();
}
/** Ends everything that belongs to the signed-in account: its lists, its requests and any open dialog. */
async function leaveAccount(message = null) {
  generation++;
  feed?.stop();
  uiScope.stop();
  uiScope = new RunScope();
  store = lists = feed = selected = null;
  account = null;
  if (invitation && invitation.state !== "none") invitation = { state: "signin" };
  busy = false;
  view = "list";
  closeDialog();
  setSync("");
  await render();
  if (message) notice(message, true);
}
const signedOut = () => leaveAccount("unauthenticated").catch(() => {});
function title(main, subtitle, extra = null) {
  return el("section", { class: "hero" }, el("div", {}, el("h1", {}, t(main)), paragraph(subtitle)), extra);
}
const dialogFocus = new DialogFocus(), dialogCancellation = new DialogCancellation();
function openDialog(content, cleanup = null, refresh = null, cancel = null) {
  dialogFocus.remember({ nested: $("dialog").open, activeElement: document.activeElement });
  closeDialog(false);
  dialogCleanup = cleanup;
  dialogRefresh = refresh;
  dialogCancellation.set(cancel);
  const dialog = $("dialog"), heading = content.querySelector("h2");
  dialog.classList.toggle("pairing", content.classList.contains("pair-device"));
  dialog.removeAttribute("aria-labelledby");
  dialog.removeAttribute("aria-label");
  if (heading) {
    heading.id ||= "dialog-heading";
    dialog.setAttribute("aria-labelledby", heading.id);
  } else dialog.setAttribute("aria-label", t("quietDialogTitle"));
  const actions = content.querySelector(":scope > .button-row");
  actions?.classList.add("dialog-actions");
  $("dialog-content").replaceChildren(content);
  dialog.showModal();
  dialog.scrollTop = 0;
}
function closeDialog(restoreFocus = true) {
  const cleanup = dialogCleanup;
  dialogCleanup = null;
  dialogRefresh = null;
  dialogCancellation.clear();
  cleanup?.();
  $("dialog").close();
  $("dialog-content").replaceChildren();
  if (restoreFocus) {
    const fallback = screen === "lists" && captureInput.isConnected ? captureInput : document.querySelector(".nav-button.active");
    dialogFocus.restore(fallback);
  }
}
function finishTutorial() {
  tutorialSeen = true;
  completeTutorial(localStore());
  closeDialog();
}
function tutorialDialog() {
  let page = 0;
  const steps = ["Welcome", "List", "Share"];
  const symbols = ["✓", "+", "↗"];
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
        page === 0 ? brandCollage("tutorial-collage") : el("div", { class: "tutorial-art", "aria-hidden": "true" }, symbols[page]),
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
    $("dialog").setAttribute("aria-labelledby", "tutorial-heading");
    $("dialog").removeAttribute("aria-label");
    $("tutorial-heading").focus();
  }
  openDialog(el("div"), () => { tutorialActive = false; });
  tutorialActive = true;
  draw();
}
function messageDialog(head, description, confirm, values = {}, cancel = closeDialog) {
  openDialog(el("form", {
    onsubmit: (event) => {
      event.preventDefault();
      closeDialog();
      act(confirm);
    },
  },
  el("h2", {}, t(head, values)),
  paragraph(description, "lede", values),
  el("div", { class: "button-row" },
    button(t("cancel"), cancel),
    el("button", { type: "submit", class: "primary" }, t("confirm")))), null, null, cancel);
}
async function localeLoad(language) {
  const response = await fetch(`/locales/${language}.json`, { credentials: "same-origin", cache: "no-store", redirect: "error" });
  requireThat(response.ok);
  dictionary = await response.json();
  locale = language;
  document.documentElement.lang = language;
  document.title = "Usuals";
  for (const n of document.querySelectorAll("[data-i18n]")) n.textContent = t(n.dataset.i18n);
  for (const n of document.querySelectorAll("[data-i18n-label]")) n.setAttribute("aria-label", t(n.dataset.i18nLabel));
  $("navigation").setAttribute("aria-label", t("navigation"));
}
function preferencesDialog() {
  openDialog(el("div", {}, el("h2", {}, t("quietPreferences")),
    el("div", { class: "menu-actions" },
      button(t("appearance"), appearanceDialog),
      button(`${t("language")} · ${locale.toUpperCase()}`, languageDialog),
      button(t("tutorialReplay"), tutorialDialog)),
    paragraph("serverHeld", "helper"),
    el("div", { class: "button-row" }, button(t("close"), closeDialog))));
}
async function languageDialog() {
  const own = generation;
  try {
    const catalogs = await Promise.all(availableLocales.map(async (code) => {
      const response = await fetch(`/locales/${code}.json`, { credentials: "same-origin", cache: "no-store", redirect: "error" });
      requireThat(response.ok);
      return [code, (await response.json()).nativeName];
    }));
    if (own !== generation) return;
    openDialog(el("div", {}, el("h2", {}, t("language")),
      el("div", { class: "locale-options" }, catalogs.map(([code, name]) => button(name, async () => {
        try {
          await localeLoad(code);
          try { localStorage.setItem("usuals-language", code); } catch {}
          closeDialog();
          await render();
        } catch (e) { failure(e); }
      }, code === locale ? "primary" : "secondary"))),
      el("div", { class: "button-row" }, button(t("close"), closeDialog))));
  } catch (error) {
    if (own === generation) failure(error);
  }
}
function appearanceDialog() {
  const choices = palettes.map((id) => el("label", { class: "theme-choice" },
    el("input", { type: "radio", name: "palette", value: id, checked: id === palette,
      onchange: () => {
        palette = writePalette(id, localStore());
        applyAppearance();
      },
    }),
    el("span", { class: "theme-copy" }, el("strong", {}, t(`palette_${id}`)),
      el("span", {}, t(`palette_${id}_description`))),
    el("span", { class: "theme-swatches", "data-preview-palette": id, "aria-hidden": "true" },
      el("i"), el("i"), el("i")),
  ));
  const backgrounds = backgroundStyles.map((id) => el("label", { class: "theme-choice" },
    el("input", { type: "radio", name: "background-style", value: id, checked: id === backgroundStyle,
      onchange: () => {
        backgroundStyle = writeBackgroundStyle(id, localStore());
        applyAppearance();
      },
    }),
    el("span", { class: "theme-copy" }, el("strong", {}, t(`background_${id}`)),
      el("span", {}, t(`background_${id}_description`))),
    el("span", { class: "background-preview", "data-preview-background": id, "aria-hidden": "true" },
      id === "artwork" ? brandCollage() : null, el("i"), el("i"), el("i")),
  ));
  openDialog(el("div", {},
    el("h2", {}, t("appearance")),
    el("fieldset", { class: "theme-options" },
      el("legend", { class: "helper" }, t("appearanceHint")), ...choices),
    el("fieldset", { class: "theme-options" },
      el("legend", {}, t("backgroundStyle")), paragraph("backgroundStyleHint", "helper"), ...backgrounds),
    el("div", { class: "button-row" }, button(t("close"), closeDialog)),
  ));
}
async function loadAccount() {
  const own = generation;
  const configuration = await api("/auth/config");
  if (own !== generation) return;
  config = configuration;
  let me = null;
  try {
    me = await api("/api/me");
    requireThat(me.user && me.user.id && me.csrfToken);
  } catch (error) {
    if (error.code !== "unauthenticated") throw error;
    me = null;
  }
  if (own !== generation) return;
  account = me;
  if (config.emailInvitations) {
    try {
      if (pendingInvitationToken) { await invitations.capture(pendingInvitationToken); pendingInvitationToken = null; }
      invitation = await invitations.current();
      if (location.pathname === "/join" && invitation.state === "none") invitation = { state: "unavailable" };
    } catch (error) {
      if (error?.code === "invitation_unavailable") { invitation = { state: "unavailable" }; pendingInvitationToken = null; }
      else throw error;
    }
  }
  if (account && !feed) startLists();
  await render();
}
function welcome() {
  return el("section", { class: "welcome" },
    el("div", {},
      el("p", { class: "eyebrow" }, t("yourEveryday")),
      el("h1", {}, t("welcomeTitle")),
      paragraph("welcomeText"),
      el("div", { class: "welcome-actions" }, ...welcomeActions()),
      paragraph(config?.emailInvitations ? "emailSignInText" : config?.localEnabled ? "localSignInText" : "signInText", "helper")),
    brandCollage("welcome-art"));
}
async function render() {
  const own = generation;
  screen = null;
  setSync(syncState);
  $("main").dataset.view = view;
  $("section-label").textContent = t(view === "admin" ? "admin" : view === "account" ? "account" : "lists");
  for (const n of document.querySelectorAll(".nav-button[data-view]")) {
    n.classList.toggle("active", n.dataset.view === view);
    if (n.dataset.view === view) n.setAttribute("aria-current", "page");
    else n.removeAttribute("aria-current");
  }
  let adminButton = document.querySelector('[data-view="admin"]');
  if (account?.user.admin && !adminButton) {
    adminButton = el("button", { type: "button", class: "nav-button", "data-view": "admin", "data-i18n": "admin" },
      el("span", { class: "nav-icon" }, "✧"), t("admin"));
    $("navigation").append(adminButton);
  }
  if (!account?.user.admin) adminButton?.remove();
  if (connectionDone) {
    $("content").replaceChildren(title("connectionApproved", "connectionReturn"),
      el("div", { class: "button-row" }, el("a", { class: "primary", href: "usuals://connected" }, t("returnToUsuals")),
        button(t("close"), () => { connectionDone = false; render().catch(failure); })));
    return;
  }
  if (pendingConnect) {
    $("content").replaceChildren(connectLanding());
    return;
  }
  if (invitation && invitation.state !== "none") {
    $("content").replaceChildren(invitationLanding());
    return;
  }
  if (!account) {
    $("content").replaceChildren(welcome());
    return;
  }
  if (pendingConnectionId && ticketPattern.test(pendingConnectionId)) {
    await renderConnectionApproval(own, pendingConnectionId);
    return;
  }
  if (view === "account") return renderAccount(own);
  if (view === "admin") return renderAdmin(own);
  screen = "lists";
  renderLists();
}
const listName = (list) => list.projection.name || t("untitledList");
function invitationLanding() {
  const state = invitation.state;
  const dismiss = async () => { await invitations.dismiss(); invitation = null; history.replaceState(null, "", "/"); };
  const join = () => act(async () => {
    const result = await invitations.accept();
    if (result.list) { store.put(result.list); selected = result.list.id; }
    await dismiss(); finishTutorial();
    notice("invitationJoined");
  });
  const switchAccount = () => act(async () => {
    const result = await api("/auth/switch", { method: "POST", body: {}, csrf: account.csrfToken });
    location.assign(result.url);
  });
  const content = [el("h1", {}, t(state === "unavailable" ? "invitationUnavailable" : "invitationTitle"))];
  if (state === "signin") content.push(paragraph("invitationIntro"), el("div", { class: "button-row" },
    config.registrationPath ? el("a", { class: "primary", href: config.registrationPath }, t("createAccountToJoin")) : null,
    el("a", { class: config.registrationPath ? "secondary" : "primary", href: "/auth/login?fresh=true" }, t("emailSignIn"))));
  else if (state === "ready") content.push(paragraph("invitationReady", "lede", { name: invitation.inviter || t("inviteSomeone") }),
    button(t("joinList"), join, "primary"));
  else if (state === "wrong_account") content.push(paragraph("invitationWrongAccount"), button(t("switchAccount"), switchAccount, "primary"));
  else if (state === "reauthenticate") content.push(paragraph("invitationReauthenticate"),
    el("a", { class: "primary", href: "/auth/login?fresh=true" }, t("verifyAndContinue")));
  else if (state === "accepted") content.push(button(t("openInvitationList"), () => act(async () => {
    if (invitation.listId) selected = invitation.listId;
    await dismiss(); finishTutorial();
  }), "primary"));
  else content.push(paragraph("invitationUnavailableText"));
  content.push(el("div", { class: "button-row" }, button(t("backToLists"), () => act(dismiss), "text-button")));
  return el("section", { class: "paper account-settings" }, ...content);
}
// The capture field survives re-rendering, so a change arriving from the phone never eats what is being typed.
const captureInput = el("input", { id: "capture-name", type: "text", maxlength: 1000, autocomplete: "off", required: true });
// Whether the Archived area is open survives re-rendering too.
let archivedOpen = false;
const pickedOpen = new Map();
function archivedRow(list) {
  const values = { name: listName(list) };
  return el("li", { class: "item" },
    el("div", { class: "item-info" }, el("div", { class: "item-name" }, values.name),
      archivedAction(list) === "leave" ? el("div", { class: "item-meta" }, t("archivedByOwner")) : null),
    archivedAction(list) === "leave"
      ? button(t("leaveList"), () => messageDialog("leaveListTitle", "leaveListText", () => lists.leave(list.id, account.user.id), values),
        "danger", { "aria-label": t("leaveListName", values) })
      : button(t("restoreList"), () => act(async () => { await lists.archive(list.id, false); selected = list.id; if ($("dialog").open) closeDialog(); }),
        "secondary", { "aria-label": t("restoreListName", values) }));
}
function archivedArea(archived) {
  if (!archived.length) return null;
  return el("section", { class: "paper archived" },
    el("details", { open: archivedOpen || null, ontoggle: (event) => { archivedOpen = event.target.open; } },
      el("summary", {}, t("archivedLists", { count: archived.length })),
      el("ul", { class: "items" }, archived.map(archivedRow))));
}
function listSyncLabel() {
  return el("span", { class: "sync-status list-sync", "data-sync-status": "", "data-sync-state": syncState, role: "status" }, account && syncState ? t(syncState) : "");
}
function renderLists() {
  if (!store.cursor) {
    $("content").replaceChildren(title("listTitle", "listSubtitle"), listSyncLabel(), loadingAppearance());
    return;
  }
  const { active, archived, selected: shown } = arrange(store.lists.values(), selected, listName, locale);
  selected = shown;
  if (!active.length) {
    $("content").replaceChildren(title("listTitle", "listSubtitle"), listSyncLabel(), el("div", {},
      el("section", { class: "paper empty" },
        brandCollage("empty-collage"),
        ...(archived.length ? [paragraph("allListsArchived")] : [el("h2", {}, t("firstListTitle")), paragraph("firstListText")]),
        button(t("newList"), newListDialog, "primary")),
      archivedArea(archived)));
    return;
  }
  const list = store.lists.get(selected), id = list.id, items = listRows(list.projection),
    needed = items.filter((r) => !isChecked(r)), picked = items.filter(isChecked);
  const focused = document.activeElement === captureInput;
  captureInput.placeholder = t("addPlaceholder");
  captureInput.setAttribute("aria-label", t("addPlaceholder"));
  const capture = el("form", { class: "capture", onsubmit: (event) => {
    event.preventDefault();
    const name = captureInput.value.trim();
    if (busy) return;
    if (!name || name.length > 1000) {
      notice("invalidName", true);
      return;
    }
    captureInput.value = "";
    act(async () => {
      try {
        const current = store.lists.get(id);
        requireThat(current, "not_found");
        await change(id, "capture", captures(current.projection, [{ name }], Date.now())[0]);
      } catch (error) {
        if (!captureInput.value) captureInput.value = name;
        throw error;
      }
    });
  } }, el("label", { for: captureInput.id, class: "visually-hidden" }, t("addPlaceholder")),
    captureInput, el("button", { type: "submit", "aria-label": t("add") }, t("quietAdd")));
  const section = (key, rows, extra = null) => [
    el("div", { class: "list-section-label" }, el("span", {}, t(key)), el("span", { class: "header-actions" }, extra, el("span", { class: "list-accent-count", "data-list-color": listColor(list.projection) }, rows.length))),
    el("ul", { class: "items" }, rows.map((r) => itemNode(id, r))),
  ];
  const people = access(list, account.user.id);
  const pickedSection = picked.length ? el("section", { class: "picked-section" },
    el("details", { open: pickedOpen.get(id) || null, ontoggle: (event) => { pickedOpen.set(id, event.target.open); } },
      el("summary", {}, t("quietPicked", { count: picked.length })),
      el("ul", { class: "items" }, picked.map((r) => itemNode(id, r)))),
    el("div", { class: "finish-actions" }, button(t("finishShopping"), () => act(() => finishShopping(id)), "secondary"))) : null;
  const paper = el("section", { class: "paper list-paper", "aria-label": listName(list) },
    capture,
    ...(needed.length ? section("toPickUp", needed) : []),
    pickedSection,
    ...(!items.length ? [el("div", { class: "empty" },
      brandCollage("empty-collage"),
      el("h2", {}, t("emptyTitle")), paragraph("emptyText"))] : []),
    el("progress", { class: "visually-hidden", value: picked.length, max: Math.max(items.length, 1), "aria-label": t("pickedUp") }));
  $("content").replaceChildren(
    el("section", { class: "list-heading" },
      brandCollage("list-heading-collage"),
      el("div", { class: "list-title-row" },
        el("h1", {}, el("button", { type: "button", class: "list-title-trigger", id: "list-switcher", onclick: listPickerDialog,
          "aria-haspopup": "dialog", "aria-label": t("quietChangeListFor", { name: listName(list) }) },
          el("span", { class: "list-title-name", "data-list-color": listColor(list.projection) }, listName(list)), el("span", { class: "list-chevron", "aria-hidden": "true" }, "⌄"))),
        el("button", { type: "button", class: "text-button list-options", onclick: () => listMenu(id),
          "aria-haspopup": "dialog", "aria-label": t("quietListOptionsFor", { name: listName(list) }) },
          el("span", { class: "list-options-label" }, t("quietListOptions")), el("span", { class: "list-options-icon", "aria-hidden": "true" }, "···"))),
      el("p", { class: "list-summary" }, el("span", {}, t("itemCount", { count: needed.length })),
        el("span", {}, t(people.length > 1 ? "sharedTitle" : "justYou")), listSyncLabel())),
    el("div", { class: "list-layout" }, el("div", {}, paper, archivedArea(archived))));
  if (focused) captureInput.focus({ preventScroll: true });
}
function listPickerDialog() {
  const { active, archived } = arrange(store.lists.values(), selected, listName, locale);
  openDialog(el("div", {}, el("h2", {}, t("quietChooseList")),
    el("div", { class: "list-choices" }, active.map((list) =>
      button([el("span", { class: "list-color-dot", "data-list-color": listColor(list.projection), "aria-hidden": "true" }), listName(list)], () => {
        selected = list.id;
        closeDialog(false); clearNotice();
        render().then(() => dialogFocus.restore($("list-switcher"))).catch(failure);
      }, `list-choice${list.id === selected ? " active" : ""}`, { "aria-pressed": list.id === selected }))),
    archivedArea(archived),
    el("div", { class: "button-row" }, button(t("close"), closeDialog), button(t("newList"), newListDialog, "primary"))));
}
/** Shows what the server recorded for this change: a conflict or a rejection, in the projection's own terms. */
function report(reply) {
  const issue = problem(reply);
  if (issue) notice(dictionary[issue] ? issue : "conflict_stale", true);
  else clearNotice();
  return reply;
}
const change = async (listId, kind, payload) => report(await lists.apply(listId, kind, payload));
function itemNode(listId, row) {
  const checked = isChecked(row);
  const metadata = [];
  if (row.facts.quantity != null)
    metadata.push(t("quantityLabel", {
      amount: new Intl.NumberFormat(locale, { maximumFractionDigits: 10 }).format(Number(row.facts.quantity)),
      unit: t(row.facts.unit),
    }));
  if (row.facts.store) metadata.push(row.facts.store);
  if (row.facts.note) metadata.push(row.facts.note);
  return el("li", { class: `item${checked ? " checked" : ""}` },
    el("button", { type: "button", class: "check", role: "checkbox", id: `check-${row.id}`, "aria-checked": checked,
      "aria-label": t(checked ? "uncheckItem" : "checkItem", { name: row.facts.name }),
      onclick: () => act(() => change(listId, "check", check(row, !checked, Date.now()))) }, checked ? "✓" : ""),
    el("div", { class: "item-info" },
      el("div", { class: "item-name" }, row.facts.name),
      metadata.length ? el("div", { class: "item-meta" }, metadata.join(" · ")) : null),
    button("···", () => editDialog(listId, row), "item-edit", { "aria-label": t("editItem", { name: row.facts.name }) }));
}
function editDialog(listId, row) {
  const fields = {};
  const field = (key, type = "text", max = 1000) => {
    const node = el("input", { id: `edit-${key}`, class: "field", type, maxlength: max, value: row.facts[key] ?? "",
      ...(type === "number" ? { min: 0, step: "any" } : {}) });
    fields[key] = node;
    return [el("label", { class: "label", for: `edit-${key}` }, t(key)), node];
  };
  const unit = el("select", { id: "edit-unit", class: "field" },
    ["PIECE", "PACK", "GRAM", "KILOGRAM", "MILLILITRE", "LITRE"].map((k) =>
      el("option", { value: k, ...(row.facts.unit === k ? { selected: true } : {}) }, t(k))));
  const error = el("p", { class: "dialog-error", role: "alert" });
  const form = el("form", { onsubmit: (event) => {
    event.preventDefault();
    const name = fields.name.value.trim(), amount = fields.quantity.value;
    try {
      requireThat(name && name.length <= 1000, "invalidName");
      requireThat(amount === "" || (Number.isFinite(Number(amount)) && Number(amount) >= 0), "invalidQuantity");
      const changes = {};
      for (const k of ["name", "note", "store"]) {
        const value = fields[k].value.trim() || null;
        if (value !== (row.facts[k] ?? null)) changes[k] = value;
      }
      if (changes.name) changes.key = normalize(name);
      const quantity = amount === "" ? null : decimal(Number(amount)), selectedUnit = amount === "" ? null : unit.value;
      if (quantity !== (row.facts.quantity ?? null) || selectedUnit !== (row.facts.unit ?? null)) {
        changes.quantity = quantity;
        changes.unit = selectedUnit;
      }
      closeDialog();
      if (Object.keys(changes).length) act(() => change(listId, "edit", edit(row, changes, Date.now())));
    } catch (e) {
      error.textContent = t(e.code ?? "invalid");
    }
  } },
  el("h2", {}, t("editItem", { name: row.facts.name })),
  ...field("name"),
  el("div", { class: "row-grid" },
    el("div", {}, ...field("quantity", "number")),
    el("div", {}, el("label", { class: "label", for: "edit-unit" }, t("unit")), unit)),
  el("details", { class: "edit-details", open: row.facts.note || row.facts.store ? true : null },
    el("summary", {}, t("quietItemDetails")), ...field("note", "text", 8000), ...field("store", "text", 80)),
  el("details", { class: "edit-actions" }, el("summary", {}, t("quietItemActions")),
    button(t("remove"), () => messageDialog("quietRemoveItemTitle", "quietRemoveItemHelp",
      () => change(listId, "delete", remove(row, Date.now())), { name: row.facts.name }, () => openDialog(form)), "danger")),
  error,
  el("div", { class: "button-row" },
    button(t("cancel"), closeDialog),
    el("button", { type: "submit", class: "primary" }, t("save"))));
  openDialog(form);
}
/** Moves picked-up items into the list's history as one trip, with Undo while the notice shows. */
async function finishShopping(listId) {
  const list = store.lists.get(listId), payload = list && finish(list.projection, Date.now());
  if (!payload) return;
  const reply = await change(listId, "finish", payload);
  const count = Object.values(reply.list.projection.purchases ?? {}).filter((p) => p.tripId === payload.tripId).length;
  if (count && !problem(reply))
    notice("finished", false, { count }, [t("undo"), () => act(() => change(listId, "undo", undo(reply.intent)))]);
}
function nameDialog(heading, initial, save) {
  const input = el("input", { id: "list-name", class: "field", required: true, maxlength: 100, autocomplete: "off", value: initial });
  openDialog(el("form", { onsubmit: (event) => {
    event.preventDefault();
    const name = input.value.trim();
    if (!name) return;
    closeDialog();
    if (name !== initial) act(() => save(name));
  } },
  el("h2", {}, t(heading)),
  el("label", { class: "label", for: "list-name" }, t("listName")), input,
  el("div", { class: "button-row" }, button(t("cancel"), closeDialog), el("button", { type: "submit", class: "primary" }, t(heading === "newList" ? "newList" : "save")))));
  input.focus();
  input.select();
}
function newListDialog() {
  nameDialog("newList", "", async (name) => { selected = (await lists.create(name)).id; });
}
function listMenu(listId) {
  const list = store.lists.get(listId);
  if (!list) return;
  const values = { name: listName(list) };
  openDialog(el("div", {}, el("h2", {}, listName(list)),
    el("div", { class: "menu-actions" },
      button(t("share"), () => shareDialog(listId)),
      button(t("newList"), newListDialog),
      button(t("listColorTitle"), () => listColorDialog(listId)),
      // A rename is guarded by the name this browser showed, so two people renaming at once see a conflict.
      button(t("renameList"), () => nameDialog("renameList", list.projection.name, (name) => change(listId, "list_name", rename(list.projection, name)))),
      button(t("importTitle"), () => { closeDialog(); openImport(listId); }),
      button(t("archiveList"), () => messageDialog("archiveListTitle", list.role === "owner" ? "archiveListOwnerText" : "archiveListText",
        () => lists.archive(listId, true), values)),
      list.role === "owner"
        ? button(t("deleteList"), () => messageDialog("deleteListTitle", "deleteListText", () => lists.delete(listId), values), "danger")
        : button(t("leaveList"), () => messageDialog("leaveListTitle", "leaveListText", () => lists.leave(listId, account.user.id), values), "danger")),
    el("div", { class: "button-row" }, button(t("close"), closeDialog))));
}
function listColorDialog(listId) {
  const list = store.lists.get(listId);
  if (!list) return;
  const initial = listColor(list.projection);
  let chosen = initial;
  const preview = el("h3", { "data-list-color": chosen, class: "list-color-preview" }, listName(list));
  const options = ["automatic", ...listColors].map((color) => {
    const radio = el("input", { type: "radio", name: "list-color", value: color, checked: color === chosen,
      onchange: () => { chosen = color; preview.dataset.listColor = color; } });
    return el("label", { class: "list-color-option" }, radio,
      el("span", { class: "list-color-dot", "data-list-color": color, "aria-hidden": "true" }), t(`listColor_${color}`));
  });
  openDialog(el("form", { onsubmit: (event) => {
    event.preventDefault(); closeDialog();
    if (chosen !== initial) act(() => change(listId, "list_color", colorChange(list.projection, chosen)));
  } }, el("h2", {}, t("listColorTitle")), preview,
    el("fieldset", { class: "list-color-options" }, el("legend", { class: "visually-hidden" }, t("listColorTitle")), options),
    el("div", { class: "button-row" }, button(t("cancel"), closeDialog), el("button", { type: "submit", class: "primary" }, t("save")))));
}
function openImport(listId) {
  act(async () => {
    const own = generation;
    const [packs, modelConfig, instance] = await loadImportResources();
    if (own !== generation || !store?.lists.has(listId)) return;
    const projection = () => (own === generation ? store.lists.get(listId)?.projection ?? null : null);
    importDialog({ el, button, t, openDialog, closeDialog, dialog: $("dialog"), packs, origins: modelConfig.origins,
      server: instance.ai ? { csrf: account.csrfToken } : null, projection,
      capture: async (items) => report(await lists.send(listId, captures(projection(), items, Date.now()).map((p) => intent(listId, "capture", p)))),
      valid: () => projection() !== null, finished: () => render() });
  }, { rerender: false });
}
function personRow(name, detail, action) {
  return el("div", { class: "account-row" },
    el("div", { class: "avatar", "aria-hidden": "true" }, (name || "?").slice(0, 1).toUpperCase()),
    el("div", { class: "account-info" }, el("div", { class: "account-name" }, name), detail ? el("div", { class: "account-detail" }, detail) : null),
    action);
}
/** People with access, and a search over this server's accounts: picking someone shares the list at once. */
function shareDialog(listId) {
  if (config.emailInvitations) return emailSharingDialog(listId);
  let found = [], searched = null, lookups = 0, timer = null;
  const heading = el("h2", {}), members = el("div", {}), results = el("div", { "aria-live": "polite" });
  const error = el("p", { class: "dialog-error", role: "alert" });
  const search = el("input", { id: "share-search", class: "field", type: "search", autocomplete: "off", maxlength: 200,
    placeholder: t("shareSearch") });
  const run = (action) => act(async () => {
    error.textContent = "";
    try { await action(); }
    catch (e) {
      if (e?.code === "unauthenticated") throw e;
      error.textContent = t(dictionary[e?.code] ? e.code : "serverUnavailable");
    }
    draw();
  });
  function draw() {
    if (!heading.isConnected) return;
    const list = store?.lists.get(listId);
    if (!list) {
      closeDialog();
      return;
    }
    heading.textContent = t("shareTitle", { name: listName(list) });
    members.replaceChildren(...access(list, account.user.id).map((m) => personRow(m.you ? t("youName", { name: m.name }) : m.name, t(m.role),
      m.action === "remove" ? button(t("shareRemove"), () => run(() => lists.removeMember(listId, m.id)), "secondary", { "aria-label": t("shareRemovePerson", { name: m.name }) })
        : m.action === "leave" ? button(t("leaveList"), () => messageDialog("leaveListTitle", "leaveListText", () => lists.leave(listId, m.id), { name: listName(list) }), "danger")
          : null)));
    const present = new Set([list.owner.id, ...list.members.map((m) => m.id)]);
    const candidates = found.filter((p) => !present.has(p.id));
    results.replaceChildren(...(searched === null ? [] : candidates.length
      ? candidates.map((p) => personRow(p.name, p.detail, button(t("shareAdd"), () => run(() => lists.addMember(listId, p.id)), "primary", { "aria-label": t("shareAddPerson", { name: p.name }) })))
      : [paragraph(searched ? "shareNoMatch" : found.length ? "shareEveryone" : "shareNoPeople", "helper")]));
  }
  async function lookup() {
    const mine = ++lookups, query = search.value.trim();
    try {
      const people = await lists.people(query);
      if (mine !== lookups) return;
      found = people;
      searched = query;
      draw();
    } catch (e) {
      if (mine === lookups) error.textContent = t(dictionary[e?.code] ? e.code : "serverUnavailable");
    }
  }
  search.addEventListener("input", () => { clearTimeout(timer); timer = setTimeout(lookup, 250); });
  openDialog(el("div", {}, heading,
    el("p", { class: "label" }, t("peopleWithAccess")), members,
    el("label", { class: "label", for: "share-search" }, t("shareAddPeople")), search, results, error,
    paragraph("shareHelp", "helper"),
    el("div", { class: "button-row" }, button(t("close"), closeDialog))),
  () => { clearTimeout(timer); lookups++; }, draw);
  draw();
  lookup();
  search.focus();
}
/** Pending mail is separate from membership. The list feed remains the authority for people with access. */
function emailSharingDialog(listId = null) {
  let pending = [], closed = false, loading = false;
  const heading = el("h2", {}), members = el("div", {}), rows = el("div", { "aria-live": "polite" });
  const error = el("p", { class: "dialog-error", role: "alert" });
  const email = el("input", { id: "invite-email", class: "field", type: "email", autocomplete: "email", maxlength: 254, required: true });
  const submit = el("button", { type: "submit", class: "primary" }, t("sendInvitation"));
  async function refresh() {
    if (closed || loading) return;
    loading = true;
    try {
      const next = await invitations.list(listId);
      if (JSON.stringify(next) !== JSON.stringify(pending)) { pending = next; if (!closed) draw(); }
    }
    catch (e) { if (!closed) error.textContent = t(dictionary[e?.code] ? e.code : "serverUnavailable"); }
    finally { loading = false; }
  }
  const run = (action) => act(async () => {
    error.textContent = "";
    try { await action(); await refresh(); }
    catch (e) { if (e?.code === "unauthenticated") throw e; error.textContent = t(dictionary[e?.code] ? e.code : "serverUnavailable"); }
  }, { rerender: false });
  function draw() {
    if (closed) return;
    const list = listId ? store?.lists.get(listId) : null;
    if (listId && !list) { closeDialog(); return; }
    heading.textContent = list ? t("shareTitle", { name: listName(list) }) : t("invitePeopleTitle");
    members.replaceChildren(...(list ? access(list, account.user.id).map((m) => personRow(m.you ? t("youName", { name: m.name }) : m.name, t(m.role),
      m.action === "remove" ? button(t("shareRemove"), () => run(() => lists.removeMember(listId, m.id)))
        : m.action === "leave" ? button(t("leaveList"), () => run(async () => { await lists.leave(listId, m.id); closeDialog(); render(); }), "danger") : null)) : []));
    rows.replaceChildren(...pending.filter((p) => !["accepted", "revoked"].includes(p.state)).map((p) => {
      const seconds = Math.max(0, Math.ceil((p.resendAt - Date.now()) / 1000));
      const manageable = p.senderId === account.user.id || list?.role === "owner";
      return personRow(p.email, t(p.state === "expired" ? "inviteExpired" : p.delivery === "failed" ? "inviteDeliveryFailed" : p.delivery === "sent" ? "inviteDeliverySent" : "inviteDeliveryQueued"),
        manageable ? el("div", { class: "button-row" },
          p.state === "pending" ? button(seconds ? t("inviteResendIn", { seconds }) : t("resendInvitation"), () => run(() => invitations.resend(listId, p.id)), "secondary", { disabled: seconds ? true : null, "data-resend-at": p.resendAt }) : null,
          button(t("cancelInvitation"), () => run(() => invitations.cancel(listId, p.id)), "text-button")) : null);
    }));
  }
  const form = el("form", { onsubmit: async (event) => {
    event.preventDefault(); submit.disabled = true;
    try { await run(async () => { await invitations.send(listId, email.value, locale); email.value = ""; notice("invitationSent"); }); }
    finally { submit.disabled = false; }
  } }, el("label", { class: "label", for: "invite-email" }, t("emailAddress")), email,
    el("div", { class: "button-row" }, submit));
  const timer = setInterval(() => {
    for (const node of rows.querySelectorAll("[data-resend-at]")) {
      const seconds = Math.max(0, Math.ceil((Number(node.dataset.resendAt) - Date.now()) / 1000));
      node.textContent = seconds ? t("inviteResendIn", { seconds }) : t("resendInvitation"); node.disabled = seconds > 0;
    }
  }, 1000), poll = setInterval(refresh, 5000);
  openDialog(el("div", {}, heading, listId ? el("p", { class: "label" }, t("peopleWithAccess")) : null, members,
    paragraph(listId ? "inviteHelp" : "inviteBetaHelp", "helper"), form, rows, error,
    el("div", { class: "button-row" }, button(t("close"), closeDialog))),
  () => { closed = true; clearInterval(timer); clearInterval(poll); }, draw);
  draw(); refresh(); email.focus();
}
const date = (n) => Number.isFinite(n)
  ? new Intl.DateTimeFormat(locale, { dateStyle: "medium", timeStyle: "short" }).format(n)
  : t("dateUnknown");
const accountLabel = (user) => user.displayName || user.username || user.email || user.subject;
function clearConnectionRequest() {
  pendingConnectionId = null;
  try { sessionStorage.removeItem("usuals-connect-request"); } catch {}
  history.replaceState(null, "", "/");
}
/** A phone that signed in through this browser: approve it after checking it shows the same code. */
async function renderConnectionApproval(own, id) {
  let ticket;
  try { ticket = await api("/api/connect/" + id); }
  catch (error) {
    if (own === generation && ["not_found", "invalid"].includes(error.code)) {
      clearConnectionRequest(); notice("connectExpired", true); await render(); return;
    }
    throw error;
  }
  if (own !== generation) return;
  const compare = el("input", { type: "checkbox", required: true });
  $("content").replaceChildren(title("connectionTitle", "connectionCompare"),
    el("form", { class: "paper panel-body", onsubmit: event => { event.preventDefault(); act(async () => {
      requireThat(compare.checked && Date.now() < ticket.expiresAt);
      await api("/api/connect/" + id + "/approve", { method: "POST", csrf: account.csrfToken });
      clearConnectionRequest(); connectionDone = true;
    }); } }, el("p", { class: "server-address" }, location.origin),
      el("p", { class: "pair-code", "aria-label": t("connectionCodeLabel") }, spaced(connectionCode(ticket.fingerprint))),
      el("label", { class: "check-row" }, compare, t("connectionCompared")),
      el("button", { type: "submit", class: "primary" }, t("approvePhone")),
      button(t("cancel"), () => { clearConnectionRequest(); render().catch(failure); })));
}
function qrCode(uri) {
  const matrix = qrMatrix(uri), size = matrix.length + 8, ns = "http://www.w3.org/2000/svg";
  const svg = document.createElementNS(ns, "svg");
  svg.setAttribute("viewBox", `0 0 ${size} ${size}`); svg.setAttribute("class", "pair-qr");
  svg.setAttribute("role", "img"); svg.setAttribute("aria-label", t("connectQr"));
  const background = document.createElementNS(ns, "rect");
  background.setAttribute("width", String(size)); background.setAttribute("height", String(size)); background.setAttribute("fill", "#fff");
  const path = document.createElementNS(ns, "path");
  path.setAttribute("fill", "#000");
  path.setAttribute("d", matrix.flatMap((row, y) => row.flatMap((dark, x) => dark ? [`M${x + 4},${y + 4}h1v1h-1z`] : [])).join(""));
  svg.append(background, path); return svg;
}
/** One QR for the person's own phone. The phone that scans it connects to this account at once, with no code to compare. */
async function connectPhoneDialog() {
  const own = generation, secret = crypto.getRandomValues(new Uint8Array(32));
  const offer = await api("/api/connect/offers", { method: "POST", csrf: account.csrfToken, body: { secretHash: await hash(secret) } });
  if (own !== generation) return;
  const qr = qrCode(connectLink(location.origin, offer.id, secret));
  secret.fill(0);
  const status = el("p", { class: "helper", role: "status" }, t("connectPhoneWaiting"));
  const again = button(t("connectNewCode"), () => { closeDialog(); act(connectPhoneDialog, { rerender: false }); });
  again.hidden = true;
  let closed = false, polling = false;
  const stop = () => { closed = true; clearInterval(poller); qr.remove(); };
  const end = (key, retry = false) => { stop(); status.textContent = t(key); again.hidden = !retry; };
  const poll = async () => {
    if (closed || polling) return;
    polling = true;
    try {
      const found = await api("/api/connect/offers/" + offer.id).catch(e => { if (e.code === "not_found") return null; throw e; });
      if (closed) return;
      if (found?.ticket) end("connectionApproved");
      else if (!found || Date.now() >= offer.expiresAt) end("connectExpired", true);
    } catch {} finally { polling = false; }
  };
  const poller = setInterval(poll, 2000);
  const heading = el("h2", { tabindex: -1 }, t("connectionAddPhone"));
  openDialog(el("div", { class: "pair-device" }, heading, paragraph("connectPhoneIntro"),
    el("p", { class: "server-address" }, location.origin),
    el("div", { class: "pair-layout" }, qr,
      el("div", { class: "pair-instructions" },
        el("ol", { class: "setup-steps" }, el("li", {}, t("connectPhoneStepScan")), el("li", {}, t("connectPhoneStepOpen"))),
        status)),
    el("div", { class: "button-row" }, again, button(t("close"), closeDialog))), () => {
    stop();
    if (screen === "account") render().catch(failure);
  });
  heading.focus();
}
/** The /connect page a phone camera opens: it hands the code to the installed app. */
function connectLanding() {
  return el("section", { class: "paper panel-body" }, el("h2", {}, t("connectLandingTitle")), paragraph("connectLandingText"),
    el("p", { class: "server-address" }, location.origin),
    el("div", { class: "button-row" }, el("a", { class: "primary", href: connectAppLink(pendingConnect) }, t("openInUsuals")),
      button(t("close"), () => { pendingConnect = null; render().catch(failure); })));
}
async function serverSettingsDialog() {
  const own = generation, settings = await api("/v1/ai/settings", { csrf: account.csrfToken });
  if (own !== generation) return;
  const key = el("input", { type: "password", class: "field", autocomplete: "off", maxlength: 4096, "aria-label": t("importModelKey") });
  const selected = settings.profiles.find(p => p.id === settings.selected);
  const advanced = el("details", {}, el("summary", {}, t("settingsAdvanced")),
    selected ? el("p", { class: "helper" }, selected.model + " · " + selected.imageModel + " · " + selected.baseUrl) : null,
    el("p", { class: "helper" }, t("serverAiUsage", { used: settings.used, limit: settings.dailyLimit })),
    button(t("serverAiDefault"), () => act(async () => { await api("/v1/ai/settings", { method: "POST", csrf: account.csrfToken, body: {} }); closeDialog(); })),
    el("label", { class: "label" }, t("serverAiPersonal"), key), paragraph("serverAiKeyHelp", "helper"),
    ...settings.profiles.map(p => button(p.label + " · " + p.model, () => act(async () => {
      const draft = key.value; key.value = "";
      await api("/v1/ai/settings", { method: "POST", csrf: account.csrfToken, body: { profile: p.id, ...(draft ? { key: draft } : {}) } }); closeDialog();
    }))));
  openDialog(el("div", {}, el("h2", {}, t("serverAiTitle")),
    selected ? el("section", {}, paragraph("serverAiConfigured"), el("p", {}, selected.label),
      el("p", { class: "helper" }, t(selected.isLocal ? "serverAiLocal" : "serverAiExternal"))) : paragraph("ai_unavailable"),
    paragraph("serverAiPrivacy"), advanced,
    el("div", { class: "button-row" }, button(t("close"), closeDialog))), () => { key.value = ""; });
}
async function renderAccount(own) {
  const user = account.user;
  const label = accountLabel(user), detail = user.username || user.email || user.subject;
  const identity = el("section", { class: "account-identity" },
    el("p", { class: "eyebrow" }, t("signedInAs")),
    el("p", { class: "account-name" }, label),
    detail && detail !== label ? el("p", { class: "account-detail" }, detail) : null,
    user.admin ? el("span", { class: "badge" }, t("administrator")) : null);
  const load = (path, field) => api(path).then((value) => value[field]).catch((e) => {
    if (own === generation) failure(e);
    return [];
  });
  const [phones, sessions] = await Promise.all([load("/api/connections", "connections"), load("/api/sessions", "sessions")]);
  // The person may have moved to another view while these loaded.
  if (own !== generation || !account || view !== "account") return;
  const phonesCard = el("section", { class: "paper" }, el("div", { class: "panel-body" },
    el("h2", {}, t("connectionAddPhone")),
    paragraph("phonesHelp", "helper"),
    el("div", { class: "button-row account-primary" }, button(t("connectionAddPhone"), () => act(connectPhoneDialog, { rerender: false }), "primary")),
    el("details", { class: "account-disclosure" }, el("summary", {}, t("connectionSessions"), el("span", { class: "count" }, phones.length)),
      ...phones.map((phone) => personRow(t("phoneLabel", { id: phone.id.slice(-4) }), t("phoneExpiry", { date: date(phone.expiresAt) }),
        button(t("connectionRevoke"), () => messageDialog("disconnectTitle", "disconnectText", async () => {
          await api(`/api/connections/${phone.id}/revoke`, { method: "POST", csrf: account.csrfToken, status: [200, 204] });
        })))),
      phones.length ? null : paragraph("noPhones", "helper"))));
  const sessionRows = sessions.map((session) => el("div", { class: "account-row" },
    el("div", { class: "account-info" },
      el("div", { class: "account-name" }, session.current ? t("currentSession") : t("sessionCreated", { date: date(session.createdAt) })),
      el("div", { class: "account-detail" }, t("sessionExpiry", { date: date(session.expiresAt) }))),
    button(t("revoke"), () => messageDialog("confirmRevoke", "confirmRevokeText", async () => {
      await api(`/api/sessions/${session.id}/revoke`, { method: "POST", csrf: account.csrfToken, status: 204 });
      if (session.current) await leaveAccount();
    }))));
  const settings = el("section", { class: "paper account-settings" },
    el("details", { class: "account-disclosure" }, el("summary", {}, t("serverAiTitle")),
      paragraph("serverAiPrivacy", "helper"),
      button(t("quietAiSettings"), () => act(serverSettingsDialog, { rerender: false }))),
    el("details", { class: "account-disclosure" }, el("summary", {}, t("quietSecurity")),
      el("div", { class: "button-row" },
        ...(user.issuer === "urn:usuals:local" ? [button(t("changePassword"), passwordDialog)] : []),
        button(t("signOut"), () => act(async () => {
          await api("/auth/logout", { method: "POST", csrf: account.csrfToken, status: 204 });
          await leaveAccount();
        })))),
    el("details", { class: "account-disclosure" }, el("summary", {}, t("sessions"), el("span", { class: "count" }, sessions.length)),
      ...sessionRows, paragraph("sessionHelp", "helper")));
  screen = "account";
  $("content").replaceChildren(el("section", { class: "hero" }, el("h1", {}, t("quietAccountTitle"))),
    el("div", { class: "panel" }, identity, phonesCard, settings));
}
async function renderAdmin(own, after = null, previous = []) {
  if (!account?.user.admin) {
    view = "account";
    await render();
    return;
  }
  let result;
  try {
    result = await api("/api/admin/users" + (after ? "?after=" + encodeURIComponent(after) : ""));
  } catch (e) {
    if (own === generation) failure(e);
    return;
  }
  if (own !== generation || view !== "admin") return;
  const people = [...previous, ...result.users];
  const rows = people.map((u) => el("div", { class: "account-row" },
    el("div", { class: "avatar", "aria-hidden": "true" }, accountLabel(u).slice(0, 1).toUpperCase()),
    el("div", { class: "account-info" },
      el("div", { class: "account-name" }, accountLabel(u)),
      el("div", { class: "account-detail" }, u.username || u.email || u.subject)),
    u.disabled ? el("span", { class: "badge" }, t("disabled")) : u.admin ? el("span", { class: "badge" }, t("administrator")) : null,
    button(t(u.disabled ? "enable" : "disable"), () => u.disabled
      ? act(async () => {
        await api(`/api/admin/users/${u.id}`, { method: "POST", body: { disabled: false }, csrf: account.csrfToken });
      })
      : messageDialog("confirmDisable", "confirmDisableText", async () => {
        await api(`/api/admin/users/${u.id}`, { method: "POST", body: { disabled: true }, csrf: account.csrfToken });
        if (u.id === account.user.id) await leaveAccount();
      })),
    ...(u.issuer === "urn:usuals:local" && u.id !== account.user.id
      ? [button(t("resetPassword"), () => messageDialog("resetPasswordTitle", "resetPasswordText", async () => {
        const own = generation;
        const reset = await api(`/api/admin/users/${u.id}/reset-local-password`, { method: "POST", csrf: account.csrfToken });
        if (own !== generation) return;
        await render();
        if (own === generation) secretDialog(reset);
      }))]
      : []),
    button(t("revokeAll"), () => messageDialog("confirmRevoke", "confirmRevokeText", async () => {
      await api(`/api/admin/users/${u.id}/revoke-sessions`, { method: "POST", csrf: account.csrfToken, status: 204 });
      if (u.id === account.user.id) await leaveAccount();
    }))));
  $("content").replaceChildren(
    title("adminTitle", "adminSubtitle"),
    el("div", { class: "panel" },
      paragraph("adminHelp", "warning-box"),
      ...(config?.emailInvitations ? [el("div", { class: "button-row" }, button(t("invitePeopleTitle"), () => emailSharingDialog(), "primary"))] : []),
      ...(config?.localEnabled ? [el("div", { class: "button-row" }, button(t("createLocalUser"), localUserDialog, "primary"))] : []),
      el("section", { class: "paper" }, el("div", { class: "panel-body" },
        ...rows,
        ...(!rows.length ? [paragraph("noPeople")] : []),
        ...(result.next ? [el("div", { class: "button-row" },
          button(t("morePeople"), () => act(() => renderAdmin(own, result.next, people), { rerender: false })))] : [])))));
}
$("navigation").addEventListener("click", (event) => {
  const node = event.target.closest("[data-view]");
  if (node) {
    view = node.dataset.view;
    clearNotice();
    render().catch(failure);
  }
});
document.addEventListener("visibilitychange", () => feed?.nudge());
window.addEventListener("online", () => feed?.nudge());
$("dialog").addEventListener("cancel", (event) => {
  event.preventDefault();
  if (tutorialActive) finishTutorial();
  else dialogCancellation.invoke(closeDialog);
});
$("preferences").addEventListener("click", preferencesDialog);
(async () => {
  const inventory = await fetch("/locales.json", { credentials: "same-origin", cache: "no-store", redirect: "error" });
  requireThat(inventory.ok);
  availableLocales = await inventory.json();
  requireThat(Array.isArray(availableLocales) && availableLocales.includes("en") &&
    availableLocales.every((code) => /^[a-z]{2}(?:-[A-Za-z0-9]{2,8})?$/.test(code)));
  let preferred = "en";
  try {
    const saved = localStorage.getItem("usuals-language");
    if (availableLocales.includes(saved)) preferred = saved;
  } catch {}
  await localeLoad(preferred);
  loadImportResources().catch(() => {});
  if (location.hash) history.replaceState(null, "", location.pathname + location.search);
  $("content").replaceChildren(loadingAppearance());
  await loadAccount();
  if (!tutorialSeen && !pendingConnect && !config?.emailInvitations) tutorialDialog();
})().catch(failure);

function passwordValid(value) {
  const length = [...value].length;
  return length >= 15 && length <= 128 && !/[\u0000-\u001f\u007f-\u009f]/.test(value);
}
function usernameValid(value) {
  return /^[a-zA-Z0-9._-]{3,64}$/.test(value);
}
function localAuthDialog(activation = false) {
  const username = el("input", { id: "local-username", class: "field", autocomplete: "username", minlength: 3, maxlength: 64, required: true }),
    password = el("input", { id: "local-password", class: "field", type: "password",
      autocomplete: activation ? "new-password" : "current-password", required: true }),
    secret = activation ? el("input", { id: "local-setup", class: "field", type: "password", autocomplete: "off", required: true }) : null,
    error = el("p", { class: "dialog-error", role: "alert" }),
    submit = el("button", { type: "submit", class: "primary" }, t(activation ? "activate" : "localSignIn")),
    own = generation;
  openDialog(el("form", {
    onsubmit: async (event) => {
      event.preventDefault();
      if (submit.disabled) return;
      submit.disabled = true;
      try {
        requireThat(usernameValid(username.value), "invalidUsername");
        if (activation) requireThat(passwordValid(password.value), "invalidPassword");
        const challenge = await api("/auth/local/challenge");
        if (own !== generation) return;
        requireThat(challenge.expiresAt > Date.now());
        await api("/auth/local/" + (activation ? "activate" : "login"), {
          method: "POST",
          csrf: challenge.csrfToken,
          body: { username: username.value, password: password.value, ...(secret ? { setupToken: secret.value } : {}) },
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
        error.textContent = t(e.code === "unauthenticated" ? "localLoginFailed" : dictionary[e.code] ? e.code : "invalid");
      } finally {
        submit.disabled = false;
      }
    },
  },
  el("h2", {}, t(activation ? "activateAccount" : "localSignIn")),
  paragraph(activation ? "activationText" : "localSignInText"),
  el("label", { class: "label", for: "local-username" }, t("username")),
  username,
  ...(secret ? [el("label", { class: "label", for: "local-setup" }, t("setupToken")), secret] : []),
  el("label", { class: "label", for: "local-password" }, t(activation ? "newAccountPassword" : "accountPassword")),
  password,
  ...(activation ? [paragraph("passwordHelp", "helper")] : []),
  error,
  el("div", { class: "button-row" }, button(t("cancel"), closeDialog), submit)));
}
function passwordDialog() {
  const current = el("input", { id: "current-account-password", type: "password", class: "field", autocomplete: "current-password", required: true }),
    next = el("input", { id: "new-account-password", type: "password", class: "field", autocomplete: "new-password", required: true }),
    error = el("p", { class: "dialog-error", role: "alert" }),
    submit = el("button", { type: "submit", class: "primary" }, t("changePassword")),
    own = generation;
  openDialog(el("form", {
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
        await leaveAccount();
        notice("passwordChanged");
      } catch (e) {
        if (own === generation) error.textContent = t(dictionary[e.code] ? e.code : "invalid");
      } finally {
        submit.disabled = false;
      }
    },
  },
  el("h2", {}, t("changePassword")),
  paragraph("passwordHelp"),
  el("label", { class: "label", for: "current-account-password" }, t("currentAccountPassword")),
  current,
  el("label", { class: "label", for: "new-account-password" }, t("newAccountPassword")),
  next,
  error,
  el("div", { class: "button-row" }, button(t("cancel"), closeDialog), submit)));
}
function localUserDialog() {
  const username = el("input", { id: "create-username", class: "field", autocomplete: "off", minlength: 3, maxlength: 64, required: true }),
    name = el("input", { id: "create-display-name", class: "field", autocomplete: "off", maxlength: 160, required: true }),
    error = el("p", { class: "dialog-error", role: "alert" }),
    submit = el("button", { type: "submit", class: "primary" }, t("create")),
    own = generation;
  openDialog(el("form", {
    onsubmit: async (event) => {
      event.preventDefault();
      if (submit.disabled) return;
      submit.disabled = true;
      try {
        requireThat(usernameValid(username.value), "invalidUsername");
        const result = await api("/api/admin/local-users", {
          method: "POST",
          status: 201,
          body: { username: username.value, displayName: name.value.trim() },
          csrf: account.csrfToken,
        });
        if (own !== generation) return;
        closeDialog();
        await render();
        if (own === generation) secretDialog(result);
      } catch (e) {
        if (own === generation) error.textContent = t(dictionary[e.code] ? e.code : "invalid");
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
  el("label", { class: "label", for: "create-display-name" }, t("displayName")),
  name,
  error,
  el("div", { class: "button-row" }, button(t("cancel"), closeDialog), submit)));
}
function secretDialog(result) {
  const secret = el("input", { id: "one-use-secret", class: "field", readonly: true, value: result.setupToken, autocomplete: "off", spellcheck: false });
  openDialog(el("div", {},
    el("h2", {}, t("activationSecretTitle")),
    el("p", { class: "lede" }, t("activationSecretText", { date: date(result.expiresAt) })),
    el("label", { class: "label", for: "one-use-secret" }, t("setupToken")),
    secret,
    el("div", { class: "button-row" }, button(t("close"), () => {
      secret.value = "";
      closeDialog();
    }))));
}
function welcomeActions() {
  if (!config?.enabled) return [paragraph("ssoDisabled", "warning-box")];
  const actions = [];
  if (config.registrationPath && !config.invitationOnly)
    actions.push(el("a", { class: "primary", href: config.registrationPath }, t("createAccount")));
  if (config.localEnabled)
    actions.push(button(t("localSignIn"), () => localAuthDialog(false), "primary"));
  if (config.oidcEnabled)
    actions.push(el("a", { class: config.localEnabled || config.registrationPath ? "secondary" : "primary", href: "/auth/login" }, t(config.emailInvitations ? "emailSignIn" : "signIn")));
  if (config.localEnabled)
    actions.push(el("details", { class: "auth-help" }, el("summary", {}, t("quietAccountHelp")),
      button(t("activateAccount"), () => localAuthDialog(true), "text-button")));
  return actions;
}
