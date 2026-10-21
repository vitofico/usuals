/**
 * The browser used to be its own encrypted device with a local vault (ADR 0017 removed it). Its database and keys are
 * deleted on every start, best effort: deleting what is already gone costs nothing, and a vault an old tab still holds
 * open is removed once that tab closes.
 */
export function forgetLegacyVault(scope = globalThis) {
  const attempt = (action) => { try { action(); } catch {} };
  attempt(() => scope.indexedDB?.deleteDatabase("usuals-private-vault"));
  attempt(() => scope.sessionStorage?.removeItem("usuals-join-invite"));
  attempt(() => scope.localStorage?.removeItem("usuals-tutorial-v1"));
}
