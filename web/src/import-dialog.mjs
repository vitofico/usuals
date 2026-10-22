import { Problem, requireThat } from "./wire.mjs";
import { canonical, decimal, rows as listRows } from "./intents.mjs";
import { normalize } from "./identity.mjs";
import { markdownDocument, ingredient } from "./import.mjs";
import { ServerModel } from "./server-model.mjs";
import { ImageModel } from "./image-model.mjs";
import { preparePhoto, photoBlob, visionChallenge, PhotoCamera } from "./photo.mjs";

/** `projection` reads the destination list as the server last sent it; `capture` sends the confirmed items to it. */
export function importDialog({ el, button, t, openDialog, closeDialog, dialog, packs, origins, server = null, projection, capture: send, valid, finished }) {
  let closed = false, busy = false, committing = false, mode = "markdown", rows = [], selected = new Set(), image = null, blobUrl = null, camera = null, cameraReady = false;
  const model = server ? new ServerModel({ csrf: server.csrf, valid }) : new ImageModel({ origins, challenge: visionChallenge });
  const error = el("p", { class: "dialog-error", role: "alert" }), status = el("p", { class: "helper", role: "status" });
  const root = el("div", { class: "import-dialog" });
  const markdown = el("textarea", { id: "import-markdown", class: "field", rows: 5, maxlength: 30001, spellcheck: false });
  const markdownFile = el("input", { id: "import-markdown-file", type: "file", accept: ".md,.markdown,.txt,text/markdown,text/plain" });
  const photoFile = el("input", { id: "import-photo-file", type: "file", accept: "image/jpeg,image/png" });
  const preview = el("img", { class: "import-preview", alt: t("importPhotoPreview"), hidden: true });
  const video = el("video", { class: "import-preview", autoplay: true, muted: true, playsinline: true, hidden: true });
  const endpoint = el("input", { id: "import-endpoint", class: "field", type: "url", required: true, autocomplete: "off" });
  const modelName = el("input", { id: "import-model", class: "field", required: true, maxlength: 128, autocomplete: "off" });
  const key = el("input", { id: "import-key", class: "field", type: "password", autocomplete: "off", maxlength: 4096 });
  const limit = el("input", { id: "import-limit", class: "field", type: "number", min: 2, max: 200, value: 20, required: true });
  const cancel = button(t("cancel"), closeDialog);
  const review = el("section", { hidden: true }), reviewList = el("ul", { class: "import-review" });
  const reviewHeading = el("h3", { tabindex: -1 }, t("importReviewTitle"));
  const reviewHelp = el("p", { class: "helper" });
  const textPane = el("section", { hidden: true }), recipeText = el("textarea", { class: "field", rows: 8, maxlength: 30000, "aria-label": t("importRecipeText") });
  const markdownPane = el("section"), photoPane = el("section", { hidden: true });
  const controls = el("div", { class: "source-choices", role: "group", "aria-label": t("importSource") });
  const sources = server ? ["markdown", "photo", "text"] : ["markdown", "photo"];
  const sourceButtons = sources.map(source => button(t(source === "markdown" ? "importMarkdown" : source === "photo" ? "importPhoto" : "importRecipeText"), () => {
    stopCamera(); mode = source; update();
  }));
  controls.append(...sourceButtons);
  const current = () => !closed && valid();
  function fail(problem) { error.textContent = t(problem?.code?.startsWith("import") || problem?.code?.startsWith("ai_") ? problem.code : "importFailed"); }
  function stopCamera() { camera?.stop(); camera = null; cameraReady = false; video.hidden = true; capture.hidden = true; }
  function showPhoto(value) {
    if (blobUrl) URL.revokeObjectURL(blobUrl);
    image = value; blobUrl = URL.createObjectURL(photoBlob(value)); preview.src = blobUrl; preview.hidden = false;
  }
  async function run(action) {
    if (busy || !current()) return;
    busy = true; error.textContent = ""; update();
    try { await action(); } catch (problem) { if (current()) fail(problem); }
    finally { busy = false; if (current()) update(); }
  }
  function update() {
    const reviewing = rows.length > 0 || !review.hidden;
    reviewHelp.textContent = t(mode === "markdown" ? "importMarkdownReviewHelp" : "importReviewHelp");
    controls.hidden = reviewing; markdownPane.hidden = reviewing || mode !== "markdown"; photoPane.hidden = reviewing || mode !== "photo";
    textPane.hidden = reviewing || mode !== "text";
    sourceButtons.forEach((node, i) => { node.className = `source-choice${sources[i] === mode ? " active" : ""}`; node.setAttribute("aria-pressed", String(sources[i] === mode)); });
    root.querySelectorAll("input, textarea, button").forEach(node => { node.disabled = busy; });
    cancel.disabled = committing;
    photoFile.disabled = busy || (!server && !model.status.verified);
    startCamera.disabled = busy || (!server && !model.status.verified) || !!camera;
    capture.disabled = busy || !cameraReady;
    readPhoto.disabled = busy || !image || (server ? !model.status.configured : !model.status.verified);
    if (readText) readText.disabled = busy || !model.status.configured || !recipeText.value.trim();
    back.hidden = !reviewing; add.hidden = !reviewing;
    add.disabled = busy || !selected.size;
    add.textContent = t("importAdd", { count: selected.size });
    reviewList.querySelectorAll("input[data-existing]").forEach(node => { node.disabled = true; });
    if (server || !model.status.configured) status.textContent = "";
    else status.textContent = t(model.status.verified ? "importModelReady" : "importModelNotReady", { used: model.status.used, limit: model.status.limit });
  }
  const present = (p) => new Set(listRows(p).map(r => canonical(r.facts.key, p.aliases)));
  async function showReview(proposals) {
    const p = projection();
    if (!current() || !p) return;
    const existing = present(p);
    const grouped = new Map();
    for (const row of proposals) {
      const itemKey = canonical(normalize(row.name), p.aliases);
      if (!itemKey) continue;
      const previous = grouped.get(itemKey);
      if (previous) {
        if (previous.quantity !== null && row.quantity !== null && previous.unit === row.unit) previous.quantity += row.quantity;
        else { previous.quantity = null; previous.unit = null; }
        previous.original += "; " + row.original;
      } else grouped.set(itemKey, { ...row, key: itemKey, existing: existing.has(itemKey) });
    }
    rows = [...grouped.values()]; selected = new Set(rows.map((r, i) => !r.existing ? i : null).filter(i => i !== null));
    reviewList.replaceChildren(...rows.map((row, index) => {
      const checkbox = el("input", { type: "checkbox", checked: selected.has(index), ...(row.existing ? { "data-existing": "true", disabled: true } : {}),
        onchange: () => { if (checkbox.checked) selected.add(index); else selected.delete(index); update(); } });
      return el("li", {}, el("label", { class: "check-row" }, checkbox, el("span", {}, el("strong", {}, row.name),
        row.quantity === null ? null : el("span", { class: "helper" }, t("quantityLabel", { amount: row.quantity, unit: t(row.unit) })),
        row.original.trim() === row.name.trim() ? null : el("span", { class: "helper" }, row.original),
        row.existing ? el("span", { class: "helper" }, t("importAlreadyOnList")) : null)));
    }));
    empty.hidden = rows.length > 0; review.hidden = false; stopCamera(); update();
    reviewHeading.focus({ preventScroll: true }); dialog.scrollTop = 0;
  }
  function readMarkdown() { const document = markdownDocument(markdown.value, packs); return showReview(document.entries.map(line => ingredient(line, packs)).filter(Boolean)); }
  markdownFile.addEventListener("change", () => run(async () => {
    const file = markdownFile.files[0]; if (!file) return;
    requireThat(file.size <= 120000, "importTooLarge");
    let text; try { text = new TextDecoder("utf-8", { fatal: true }).decode(await file.arrayBuffer()); } catch { throw new Problem("importMarkdownInvalid"); }
    if (!current()) return; markdown.value = text; await readMarkdown();
  }));
  photoFile.addEventListener("change", () => run(async () => { const file = photoFile.files[0]; if (file) { const prepared = await preparePhoto(file); if (current()) showPhoto(prepared); } }));
  const connection = el("form", { onsubmit: event => {
    event.preventDefault(); run(async () => {
      const credential = key.value; key.value = "";
      model.connect({ endpoint: endpoint.value, model: modelName.value, apiKey: credential, limit: Number(limit.value) });
      await model.check();
    });
  } },
  el("label", { class: "label", for: endpoint.id }, t("importModelEndpointLabel")), endpoint,
  el("label", { class: "label", for: modelName.id }, t("importModelName")), modelName,
  el("label", { class: "label", for: key.id }, t("importModelKey")), key,
  el("label", { class: "label", for: limit.id }, t("importModelLimit")), limit,
  el("p", { class: "helper" }, t("importModelMemory")), el("button", { type: "submit", class: "secondary" }, t("importConnectModel")));
  for (const field of [endpoint, modelName, key, limit]) field.addEventListener("input", () => { model.disconnect(); stopCamera(); update(); });
  const serverDetails = el("p", { class: "helper" }), textServerDetails = el("p", { class: "helper" });
  const textServerDescription = el("p", { class: "helper" }, t("serverAiPrivacy"));
  const serverDescription = el("p", { class: "helper" }, t("serverAiPrivacy"));
  if (server) model.settings().then(settings => {
    if (!current()) return;
    const selected = settings.profiles.find(p => p.id === settings.selected);
    serverDescription.textContent = t("serverAiPrivacy") + (selected ? " " + selected.label + ". " + t(selected.isLocal ? "serverAiLocal" : "serverAiExternal") : ""); textServerDescription.textContent = serverDescription.textContent;
    serverDetails.textContent = textServerDetails.textContent = selected ? selected.model + " · " + selected.imageModel + " · " + selected.baseUrl : ""; update();
  }).catch(fail);
  const modelSection = server ? el("section", {}, serverDescription, el("details", {}, el("summary", {}, t("serverAiDetails")), serverDetails)) : origins.length ? el("details", { open: true }, el("summary", {}, t("importModelConnectionTitle")),
    el("p", { class: "helper" }, t("importAllowedOrigins", { origins: origins.join(", ") })), connection) : el("p", { class: "helper" }, t("importNoModelOrigins"));
  const startCamera = button(t("importTakePhoto"), () => run(async () => {
    camera = new PhotoCamera(); video.hidden = false; capture.hidden = false;
    try { cameraReady = await camera.start(video); if (!current()) stopCamera(); }
    catch { stopCamera(); throw new Problem("importCameraFailed"); }
  }));
  const capture = button(t("importCapturePhoto"), () => run(() => { try { showPhoto(camera.capture()); } finally { stopCamera(); } })); capture.hidden = true;
  const readPhoto = button(t("importReadPhoto"), () => run(async () => { if (server && !model.status.verified) await model.check(); const proposed = await model.read(image); if (current()) await showReview(proposed); }), "primary");
  markdownPane.append(el("p", {}, t("importMarkdownHelp")), el("label", { class: "label", for: markdown.id }, t("importMarkdownText")), markdown,
    el("details", { class: "file-disclosure" }, el("summary", {}, t("importMarkdownFile")),
      el("label", { class: "visually-hidden", for: markdownFile.id }, t("importMarkdownFile")), markdownFile),
    button(t("importReview"), () => run(readMarkdown), "primary"));
  photoPane.append(el("p", {}, t("importPhotoHelp")), modelSection, status,
    el("label", { class: "label", for: photoFile.id }, t("importChoosePhoto")), photoFile, startCamera, video, capture, preview, readPhoto);
  const empty = el("p", {}, t("importEmpty"));
  const back = button(t("importBack"), () => { rows = []; selected.clear(); review.hidden = true; update(); });
  const add = button(t("importAdd", { count: 0 }), () => run(async () => {
    committing = true; update();
    try {
      const p = projection();
      if (!current() || !p) return;
      // An item someone added meanwhile is left alone rather than having its quantity raised.
      const added = present(p), items = rows.filter((row, index) => selected.has(index) && !added.has(row.key)).map(row => ({
        key: row.key, name: row.name, rawText: row.original.slice(0, 1000), note: row.note,
        quantity: row.quantity === null ? null : decimal(row.quantity), unit: row.unit,
      }));
      if (items.length) await send(items);
      if (current()) { closeDialog(); await finished(); }
    } finally { committing = false; }
  }), "primary");
  review.append(reviewHeading, reviewHelp, reviewList, empty);
  const readText = server ? button(t("importRecipeText"), () => run(async () => { await showReview(await model.text(recipeText.value)); }), "primary") : null;
  recipeText.addEventListener("input", update);
  if (server) textPane.append(el("p", {}, t("importRecipeTextHelp")), textServerDescription,
    el("details", {}, el("summary", {}, t("serverAiDetails")), textServerDetails), recipeText, readText);
  root.append(el("h2", {}, t("importTitle")), controls, markdownPane, photoPane, textPane, review, error, el("div", { class: "button-row" }, cancel, back, add));
  const guard = event => { if (committing) { event.preventDefault(); event.stopImmediatePropagation(); } };
  dialog.addEventListener("cancel", guard, true);
  openDialog(root, () => {
    closed = true; model.disconnect(); stopCamera(); image = null;
    if (blobUrl) URL.revokeObjectURL(blobUrl); blobUrl = null;
    preview.removeAttribute("src"); recipeText.value = ""; key.value = ""; markdown.value = ""; photoFile.value = ""; markdownFile.value = "";
    dialog.removeEventListener("cancel", guard, true);
  });
  update(); markdown.focus();
}
