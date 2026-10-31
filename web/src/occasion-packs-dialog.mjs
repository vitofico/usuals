import { rows, isChecked } from "./intents.mjs";
import { itemKey } from "./shopping.mjs";
import { categories } from "./categories.mjs";
import { units, packFacts, packItem, mergePacks, readPackArchive, validatePacks } from "./occasion-packs.mjs";

export function occasionPacksDialog({ el, button, t, openDialog, closeDialog, choices, projection, add, act }) {
  let draft = null, use = false, selected = new Set(), editing = null, committing = false, confirmButton = null;
  const content = el("div", { class: "occasion-packs" });
  const error = el("p", { class: "dialog-error", role: "alert" });
  function field(label, value, update, attributes = {}) {
    const input = el("input", { class: "field", value: value ?? "", ...attributes, oninput: event => update(event.target.value) });
    return el("label", { class: "label" }, t(label), input);
  }
  function open(pack, review, ids = pack.items.map(item => item.id)) {
    draft = structuredClone(pack); use = review; selected = new Set(ids); editing = null; error.textContent = ""; draw();
  }
  function savePacks(packs) { validatePacks(packs); choices.write(value => ({ ...value, packs })); }
  function editor() {
    const item = editing, body = el("div", { class: "pack-fields" });
    if (!use) body.append(field("pack_item_name", item.name, value => item.name = value, { maxlength: 500, required: true }));
    body.append(field("pack_quantity", item.amount, value => item.amount = value === "" ? null : Number(value), { type: "number", min: 0, step: "any" }));
    const unit = el("select", { class: "field", onchange: event => item.unit = event.target.value }, units.map(value => el("option", { value }, t(value))));
    unit.value = item.unit ?? "PIECE";
    body.append(el("label", { class: "label" }, t("pack_unit", { unit: "" }), unit));
    if (!use) {
      body.append(field("pack_note", item.note, value => item.note = value || null, { maxlength: 1000 }), field("pack_store", item.store, value => item.store = value || null, { maxlength: 80 }));
      const category = el("select", { class: "field", onchange: event => item.category = event.target.value || null },
        el("option", { value: "" }, t("pack_category_auto")), categories.map(value => el("option", { value }, t(`category${value}`))));
      category.value = item.category ?? "";
      body.append(el("label", { class: "label" }, t("categoryLabel"), category));
    }
    body.append(el("div", { class: "button-row dialog-actions" }, button(t("pack_save"), () => {
      try {
        item.name = item.name.trim();
        const original = draft.items.find(row => row.id === item.id);
        if (original && item.name !== original.name) item.gtin = null;
        item.unit = item.amount == null ? null : unit.value;
        const items = draft.items.some(row => row.id === item.id) ? draft.items.map(row => row.id === item.id ? item : row) : [...draft.items, item];
        validatePacks([{ ...draft, name: draft.name || "x", items }]);
        draft.items = items; selected.add(item.id); editing = null; draw();
      } catch { error.textContent = t("pack_failed"); }
    }), button(t("pack_cancel"), () => { editing = null; draw(); }, "secondary")));
    return body;
  }
  function draw() {
    const p = projection(); if (!p) { closeDialog(); return; }
    const present = new Set(rows(p).map(row => itemKey(row.facts, p.aliases)));
    const body = el("div", { class: "pack-body" });
    const actions = el("div", { class: "button-row dialog-actions" });
    if (editing) body.append(editor());
    else if (!draft) {
      const saved = choices.read().packs ?? [];
      body.append(button(t("pack_new"), () => open({ id: crypto.randomUUID(), name: "", items: [] }, false)),
        button(t("pack_from_list"), () => {
          const current = rows(projection());
          const items = current.map(row => packItem(row.facts, p.preferences?.[itemKey(row.facts, p.aliases)]?.category ?? null));
          open({ id: crypto.randomUUID(), name: "", items }, false, items.filter((_, index) => !isChecked(current[index])).map(item => item.id));
        }, "secondary", { disabled: rows(p).length > 200 || !rows(p).length || null }));
      if (!saved.length) body.append(el("p", {}, t("pack_empty")));
      for (const pack of saved) body.append(el("section", { class: "pack-card" }, el("h3", {}, pack.name),
        button(t("pack_use"), () => open(pack, true), "primary", { disabled: !pack.items.length || null, "aria-label": t("pack_use_named", { name: pack.name }) }),
        button(t("pack_edit"), () => open(pack, false), "secondary", { "aria-label": t("pack_edit_named", { name: pack.name }) }),
        button(t("pack_delete"), () => act(() => { savePacks(saved.filter(value => value.id !== pack.id)); draw(); }), "text-button", { "aria-label": t("pack_delete_named", { name: pack.name }) })));
      body.append(button(t("pack_export"), () => {
        const url = URL.createObjectURL(new Blob([JSON.stringify({ formatVersion: 1, occasionPacks: saved })], { type: "application/json" }));
        const link = el("a", { href: url, download: "usuals-occasion-packs.json" }); link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
      }, "secondary"));
      const upload = el("input", { type: "file", accept: "application/json,.json", onchange: event => act(async () => {
        try {
          const file = event.target.files[0]; if (!file) return;
          if (file.size > 10 * 1024 * 1024) throw new Error();
          savePacks(mergePacks(choices.read().packs ?? [], readPackArchive(await file.text()))); draw();
        } catch { error.textContent = t("pack_failed"); }
      }) });
      body.append(el("label", { class: "label" }, t("pack_import"), upload));
      actions.append(button(t("close"), closeDialog));
    } else {
      if (use) body.append(el("h3", {}, draft.name), el("p", {}, t("pack_review_help")));
      else body.append(field("pack_name", draft.name, value => { draft.name = value; if (confirmButton) confirmButton.disabled = !value.trim(); }, { maxlength: 100, required: true }));
      for (const item of draft.items) {
        const exists = use && present.has(itemKey(packFacts(item), p.aliases));
        const checkbox = el("input", { type: "checkbox", checked: selected.has(item.id) && !exists || null, disabled: exists || committing || null,
          onchange: event => {
            if (event.target.checked) selected.add(item.id); else selected.delete(item.id);
            if (use && confirmButton) confirmButton.disabled = !draft.items.some(row => selected.has(row.id) && !present.has(itemKey(packFacts(row), p.aliases)));
          } });
        body.append(el("section", { class: "pack-card" }, el("label", { class: "pack-selection" }, checkbox,
          el("span", { class: "item-name" }, item.name, item.amount == null ? "" : ` × ${item.amount} ${t(item.unit)}`)),
          item.note ? el("p", {}, item.note) : null, item.store ? el("p", {}, item.store) : null,
          item.category ? el("p", {}, t(`category${item.category}`)) : null,
          exists ? el("p", { class: "helper" }, t("alreadyOnList")) : null,
          button(t(use ? "pack_quantity" : "pack_edit_item"), () => { editing = structuredClone(item); draw(); }, "secondary", { disabled: exists || committing || null, "aria-label": t("pack_edit_named", { name: item.name }) }),
          use ? null : button(t("pack_remove_item"), () => { draft.items = draft.items.filter(row => row.id !== item.id); selected.delete(item.id); draw(); }, "text-button")));
      }
      if (!use) body.append(button(t("pack_add_item"), () => { editing = { id: crypto.randomUUID(), name: "", amount: null, unit: null }; draw(); }, "secondary", { disabled: draft.items.length >= 200 || null }));
      actions.append(confirmButton = button(t(use ? "pack_add_selected" : "pack_save"), () => act(async () => {
        try {
          if (committing) return;
          const items = draft.items.filter(item => selected.has(item.id));
          if (use) {
            committing = true; draw();
            if (!await add(items.map(packFacts))) throw new Error();
          } else {
            const pack = { ...draft, name: draft.name.trim(), items };
            savePacks([...(choices.read().packs ?? []).filter(value => value.id !== pack.id), pack]);
          }
          draft = null; committing = false; draw();
        } catch { committing = false; draw(); error.textContent = t(use ? "pack_add_failed" : "pack_failed"); }
      }), "primary", { disabled: committing || (use ? !draft.items.some(row => selected.has(row.id) && !present.has(itemKey(packFacts(row), p.aliases))) : !draft.name.trim()) || null }), button(t("pack_cancel"), () => { draft = null; draw(); }, "secondary", { disabled: committing || null }));
    }
    content.replaceChildren(el("h2", {}, t(use && draft ? "pack_review" : "pack_title")), el("p", { class: "helper" }, t("pack_local")), body, error, actions);
  }
  openDialog(content, null, draw, () => { if (!committing) closeDialog(); }); draw();
}
