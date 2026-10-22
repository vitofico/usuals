/** Keeps the original trigger across nested dialogs, including a list refresh while one is open. */
export class DialogFocus {
  opener = null;

  remember({ nested, activeElement }) {
    if (!nested) this.opener = activeElement;
  }

  restore(fallback) {
    const opener = this.opener;
    this.opener = null;
    const available = (node) => node?.isConnected && !node.disabled && !node.hidden;
    const target = available(opener) ? opener : available(fallback) ? fallback : null;
    target?.focus({ preventScroll: true });
  }
}

/** Native Escape uses the current dialog's cancel action, and never a previous dialog's. */
export class DialogCancellation {
  handler = null;

  set(handler = null) { this.handler = handler; }
  clear() { this.handler = null; }

  invoke(fallback) {
    const action = this.handler ?? fallback;
    this.clear();
    action();
  }
}
