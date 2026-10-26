import { Problem, requireThat, text } from "./wire.mjs";
export class RunScope {
  constructor() {
    this.controller = new AbortController();
    this.valid = true;
  }
  check() {
    requireThat(this.valid && !this.controller.signal.aborted, "locked");
  }
  stop() {
    this.valid = false;
    this.controller.abort();
  }
  async wait(promise) {
    const value = await promise;
    this.check();
    return value;
  }
}
// Codes the interface explains; any other server answer reads as "invalid".
const allowedCodes = new Set([
  "unauthenticated",
  "forbidden",
  "not_found",
  "list_exists",
  "limit_reached",
  "rate_limited",
  "too_large",
  "invalid",
  "unavailable",
  "upgrade_required",
  "ai_unavailable",
  "ai_budget",
  "ai_monthly_budget",
  "ai_images_unverified",
  "ai_credentials_unavailable",
  "ai_configuration_changed",
]);
/** Same-origin JSON with the session cookie, no redirects and a bounded body. `status` is the expected answer or answers. */
export async function request(
  path,
  {
    method = "GET",
    body: payload,
    csrf,
    max = 1024 * 1024,
    scope = new RunScope(),
    status = 200,
    timeoutMs = 30000,
  } = {},
) {
  scope.check();
  requireThat(
    path.startsWith("/") &&
      !path.startsWith("//") &&
      !path.includes("#") &&
      !/[\u0000- \\]/.test(path),
  );
  requireThat(Number.isInteger(timeoutMs) && timeoutMs > 0 && timeoutMs <= 45000);
  const headers = { Accept: "application/json" };
  if (payload !== undefined) headers["Content-Type"] = "application/json";
  if (csrf) headers["X-CSRF-Token"] = csrf;
  const controller = new AbortController();
  let timedOut = false;
  const stop = () => controller.abort();
  scope.controller.signal.addEventListener("abort", stop, { once: true });
  const timer = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, timeoutMs);
  let reader;
  try {
    const response = await scope.wait(
      fetch(path, {
        method,
        headers,
        body: payload === undefined ? undefined : JSON.stringify(payload),
        credentials: "same-origin",
        redirect: "error",
        cache: "no-store",
        signal: controller.signal,
      }),
    );
    reader = response.body?.getReader();
    let size = 0;
    const chunks = [];
    if (reader) {
      while (true) {
        const chunk = await scope.wait(reader.read());
        if (chunk.done) break;
        size += chunk.value.length;
        requireThat(size <= max, "too_large");
        chunks.push(chunk.value);
      }
    }
    const bytes = new Uint8Array(size);
    let offset = 0;
    for (const chunk of chunks) {
      bytes.set(chunk, offset);
      offset += chunk.length;
    }
    if (![status].flat().includes(response.status)) {
      // A 401 always means signed out, even when a proxy in front of the server answers it without a code.
      let code = response.status === 401 ? "unauthenticated" : "invalid";
      try {
        const value = JSON.parse(text.decode(bytes.subarray(0, 65536)));
        if (allowedCodes.has(value.code)) code = value.code;
      } catch {}
      throw new Problem(code);
    }
    if (response.status === 204) return null;
    try {
      return JSON.parse(text.decode(bytes));
    } catch {
      throw new Problem("invalid");
    }
  } catch (error) {
    controller.abort();
    if (reader) {
      try {
        await reader.cancel();
      } catch {}
    }
    scope.check();
    if (timedOut) throw new Problem("unavailable");
    throw error;
  } finally {
    clearTimeout(timer);
    scope.controller.signal.removeEventListener("abort", stop);
    reader?.releaseLock();
  }
}
