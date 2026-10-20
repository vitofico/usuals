import { Problem, requireThat } from "./wire.mjs";
import { canonical, decimal } from "./reducer.mjs";
import { normalize } from "./identity.mjs";
import { markdownDocument, ingredient } from "./import.mjs";
import { ImageModel } from "./image-model.mjs";
import { preparePhoto, photoBlob, visionChallenge, PhotoCamera } from "./photo.mjs";

export function importDialog({ el, button, t, openDialog, closeDialog, dialog, packs, origins, client, destination, valid, finished }) {
  let closed = false, busy = false, committing = false, mode = "markdown", rows = [], selected = new Set(), image = null, blobUrl = null, camera = null, cameraReady = false;
  const model = new ImageModel({ origins, challenge: visionChallenge });
  const error = el("p", { class: "dialog-error", role: "alert" }), status = el("p", { class: "helper", role: "status" });
  const root = el("div", { class: "import-dialog" });
  const markdown = el("textarea", { id: "import-markdown", class: "field", rows: 7, maxlength: 30001, spellcheck: false });
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
  const markdownPane = el("section"), photoPane = el("section", { hidden: true });
  const controls = el("div", { class: "button-row", role: "group", "aria-label": t("importSource") });
  const sourceButtons = ["markdown", "photo"].map(source => button(t(source === "markdown" ? "importMarkdown" : "importPhoto"), () => {
    stopCamera(); mode = source; update();
  }));
  controls.append(...sourceButtons);
  const current = () => !closed && valid();
  function fail(problem) { error.textContent = t(problem?.code?.startsWith("import") ? problem.code : "importFailed"); }
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
    controls.hidden = reviewing; markdownPane.hidden = reviewing || mode !== "markdown"; photoPane.hidden = reviewing || mode !== "photo";
    sourceButtons.forEach((node, i) => { node.className = ["markdown", "photo"][i] === mode ? "primary" : "secondary"; node.setAttribute("aria-pressed", String(["markdown", "photo"][i] === mode)); });
    root.querySelectorAll("input, textarea, button").forEach(node => { node.disabled = busy; });
    cancel.disabled = committing;
    photoFile.disabled = busy || !model.status.verified;
    startCamera.disabled = busy || !model.status.verified || !!camera;
    capture.disabled = busy || !cameraReady;
    readPhoto.disabled = busy || !image || !model.status.verified;
    add.disabled = busy || !selected.size;
    add.textContent = t("importAdd", { count: selected.size });
    reviewList.querySelectorAll("input[data-existing]").forEach(node => { node.disabled = true; });
    if (!model.status.configured) status.textContent = "";
    else status.textContent = t(model.status.verified ? "importModelReady" : "importModelNotReady", { used: model.status.used, limit: model.status.limit });
  }
  async function showReview(proposals) {
    const projection = await client.view(destination.household, destination.list);
    if (!current()) return;
    const present = new Set(Object.values(projection.rows).filter(r => !r.archived && !projection.tombstones[r.id]).map(r => canonical(r.facts.key, projection.aliases)));
    const grouped = new Map();
    for (const row of proposals) {
      const itemKey = canonical(normalize(row.name), projection.aliases);
      if (!itemKey) continue;
      const previous = grouped.get(itemKey);
      if (previous) {
        if (previous.quantity !== null && row.quantity !== null && previous.unit === row.unit) previous.quantity += row.quantity;
        else { previous.quantity = null; previous.unit = null; }
        previous.original += "; " + row.original;
      } else grouped.set(itemKey, { ...row, key: itemKey, existing: present.has(itemKey) });
    }
    rows = [...grouped.values()]; selected = new Set(rows.map((r, i) => !r.existing ? i : null).filter(i => i !== null));
    reviewList.replaceChildren(...rows.map((row, index) => {
      const checkbox = el("input", { type: "checkbox", checked: selected.has(index), ...(row.existing ? { "data-existing": "true", disabled: true } : {}),
        onchange: () => { if (checkbox.checked) selected.add(index); else selected.delete(index); update(); } });
      return el("li", {}, el("label", { class: "check-row" }, checkbox, el("span", {}, el("strong", {}, row.name),
        row.quantity === null ? null : el("span", { class: "helper" }, t("quantityLabel", { amount: row.quantity, unit: t(row.unit) })),
        el("span", { class: "helper" }, row.original), row.existing ? el("span", { class: "helper" }, t("importAlreadyOnList")) : null)));
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
  const modelSection = origins.length ? el("details", { open: true }, el("summary", {}, t("importModelConnectionTitle")),
    el("p", { class: "helper" }, t("importAllowedOrigins", { origins: origins.join(", ") })), connection) : el("p", { class: "helper" }, t("importNoModelOrigins"));
  const startCamera = button(t("importTakePhoto"), () => run(async () => {
    camera = new PhotoCamera(); video.hidden = false; capture.hidden = false;
    try { cameraReady = await camera.start(video); if (!current()) stopCamera(); }
    catch { stopCamera(); throw new Problem("importCameraFailed"); }
  }));
  const capture = button(t("importCapturePhoto"), () => run(() => { try { showPhoto(camera.capture()); } finally { stopCamera(); } })); capture.hidden = true;
  const readPhoto = button(t("importReadPhoto"), () => run(async () => { const proposed = await model.read(image); if (current()) await showReview(proposed); }), "primary");
  markdownPane.append(el("p", {}, t("importMarkdownHelp")), el("label", { class: "label", for: markdown.id }, t("importMarkdownText")), markdown,
    el("label", { class: "label", for: markdownFile.id }, t("importMarkdownFile")), markdownFile, button(t("importReview"), () => run(readMarkdown), "primary"));
  photoPane.append(el("p", {}, t("importPhotoHelp")), modelSection, status,
    el("label", { class: "label", for: photoFile.id }, t("importChoosePhoto")), photoFile, startCamera, video, capture, preview, readPhoto);
  const empty = el("p", {}, t("importEmpty"));
  const back = button(t("importBack"), () => { rows = []; selected.clear(); review.hidden = true; update(); });
  const add = button(t("importAdd", { count: 0 }), () => run(async () => {
    committing = true; update();
    try {
      const projection = await client.view(destination.household, destination.list);
      if (!current()) return;
      const now = Date.now(), position = Object.keys(projection.rows).length;
      const payloads = rows.filter((_, index) => selected.has(index)).map((row, index) => ({
        proposedId: crypto.randomUUID(), key: row.key, name: row.name, rawText: row.original.slice(0, 1000),
        note: row.note, quantity: row.quantity === null ? null : decimal(row.quantity), unit: row.unit,
        normalizerVersion: 1, createdAt: now, updatedAt: now, position: position + index
      }));
      await client.enqueueCaptures(destination.household, destination.list, payloads);
      if (current()) { closeDialog(); await finished(); }
    } finally { committing = false; }
  }), "primary");
  review.append(reviewHeading, el("p", { class: "helper" }, t("importReviewHelp")), reviewList, empty, el("div", { class: "button-row" }, back, add));
  root.append(el("h2", {}, t("importTitle")), controls, markdownPane, photoPane, review, error, el("div", { class: "button-row" }, cancel));
  const guard = event => { if (committing) { event.preventDefault(); event.stopImmediatePropagation(); } };
  dialog.addEventListener("cancel", guard, true);
  openDialog(root, () => {
    closed = true; model.disconnect(); stopCamera(); image = null;
    if (blobUrl) URL.revokeObjectURL(blobUrl); blobUrl = null;
    preview.removeAttribute("src"); key.value = ""; markdown.value = ""; photoFile.value = ""; markdownFile.value = "";
    dialog.removeEventListener("cancel", guard, true);
  });
  update(); markdown.focus();
}
