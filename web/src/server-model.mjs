import { Problem, requireThat, number } from "./wire.mjs";
import { request, RunScope } from "./client.mjs";

/** Same-origin requests only. Provider keys are owned by the server account settings. */
export class ServerModel {
  #scope = new RunScope(); #verified = false; #used = 0; #limit = 0; #configured = false; #revision = null;
  constructor({ csrf, valid = () => true, requester = request }) { this.csrf = csrf; this.valid = valid; this.requester = requester; }
  get status() { return { configured: this.#configured, verified: this.#verified, used: this.#used, limit: this.#limit }; }
  disconnect() { this.#scope.stop(); this.#scope = new RunScope(); this.#verified = this.#configured = false; }
  async #call(path, body) {
    requireThat(this.valid(), "locked");
    const scope = this.#scope;
    let reply;
    try { reply = await this.requester("/v1/ai/" + path, { csrf: this.csrf, scope, ...(body === undefined ? {} : { method: "POST", body: { ...body, revision: this.#revision } }), timeoutMs: 45000 }); }
    catch (error) {
      if (["ai_configuration_changed", "ai_images_unverified"].includes(error?.code)) this.#verified = false;
      throw error;
    }
    scope.check(); requireThat(this.valid(), "locked");
    requireThat(!reply.problem, reply.problem === "BUDGET_EXHAUSTED" ? "importModelBudget" : "importModelConnection");
    return reply;
  }
  async settings() {
    const value = await this.#call("settings");
    this.#configured = value.selected != null; this.#revision = value.revision; this.#limit = value.dailyLimit; this.#used = value.used;
    return value;
  }
  async check() {
    this.#verified = false;
    requireThat(this.#configured && this.#revision, "importModelMissing");
    const value = await this.#call("probe-image", {}); this.#used++;
    requireThat(value.images && value.structured, "importModelImages"); this.#verified = true;
  }
  async read(image) {
    requireThat(this.#verified, "importModelImages");
    const match = /^data:(image\/(?:jpeg|png));base64,([A-Za-z0-9+/]+=*)$/.exec(image);
    requireThat(match && match[2].length <= 2800000, "importImageInvalid");
    this.#used++;
    return this.#items(await this.#call("image", { image: match[2], mimeType: match[1] }));
  }
  async text(text) {
    requireThat(typeof text === "string" && text.length > 0 && text.length <= 30000, "importTooLarge");
    requireThat(this.#configured && this.#revision, "importModelMissing");
    this.#used++;
    return this.#items(await this.#call("text", { text }));
  }
  #items(reply) {
    requireThat(Array.isArray(reply.items) && reply.items.length <= 50, "importModelOutput");
    const units = new Set(["PIECE", "GRAM", "KILOGRAM", "MILLILITRE", "LITRE", "PACK"]);
    return reply.items.map(row => {
      requireThat(typeof row.name === "string" && row.name.trim() && row.name.length <= 120, "importModelOutput");
      const quantity = row.amount == null ? null : number(row.amount);
      requireThat(quantity === null || Number.isFinite(quantity) && quantity > 0 && units.has(row.unit), "importModelOutput");
      return { name: row.name, quantity, unit: row.unit, note: row.note, original: row.name };
    });
  }
  toString() { return "ServerModel([redacted])"; }
}
