const key = "usuals-tutorial-v1";

export function tutorialCompleted(storage) {
  try { return storage?.getItem(key) === "complete"; }
  catch { return false; }
}

export function completeTutorial(storage) {
  try { storage?.setItem(key, "complete"); } catch {}
}
