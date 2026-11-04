/** Keep a failed removal reviewable, and never send twice while the first request is pending. */
export function deleteShopDialog({ el, button, t, openDialog, closeDialog, date, exists, remove, back }) {
  let busy = false, active = true;
  const error = el("p", { class: "dialog-error", role: "alert" });
  const cancel = () => { if (!busy) back(); };
  const confirm = button(t("historyDelete"), async () => {
    if (busy || !active) return;
    if (!exists()) { closeDialog(); return; }
    busy = true; confirm.disabled = true; error.textContent = "";
    try {
      const outcome = await remove();
      if (!active) return;
      if (outcome === "deleted") back();
      else error.textContent = t(outcome === "unsupported" ? "historyDeleteUnsupported" : "historyDeleteFailed");
    } catch { if (active) error.textContent = t("historyDeleteFailed"); }
    finally { busy = false; confirm.disabled = false; }
  }, "danger");
  openDialog(el("div", {}, el("h2", {}, t("historyDeleteTitle", { date })),
    el("p", { class: "lede" }, t("historyDeleteHelp")), error,
    el("div", { class: "button-row" }, button(t("cancel"), cancel), confirm)),
  () => { active = false; }, () => { if (!exists() && !busy) closeDialog(); }, cancel);
}
