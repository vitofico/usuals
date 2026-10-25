import { request } from "./client.mjs";

/** Read once, then clear the fragment before starting requests. Invitation secrets never enter persistent browser storage. */
export function invitationToken(location) {
  if (location.pathname !== "/join") return null;
  const token = location.hash.slice(1);
  return /^[A-Za-z0-9_-]{43}$/.test(token) ? token : null;
}
export class InvitationsApi {
  constructor({ transport = request, csrf = () => null } = {}) { Object.assign(this, { transport, csrf }); }
  async capture(token) {
    const challenge = await this.transport("/api/invitations/challenge");
    await this.transport("/api/invitations/context", { method: "POST", csrf: challenge.csrfToken, body: { token }, status: 204 });
  }
  current() { return this.transport("/api/invitations/current"); }
  accept() { return this.transport("/api/invitations/accept", { method: "POST", csrf: this.csrf(), body: {}, max: 8 * 1024 * 1024 }); }
  dismiss() { return this.transport("/api/invitations/dismiss", { method: "POST", csrf: this.csrf(), body: {}, status: 204 }); }
  path(list) { return list ? `/api/lists/${list}/invitations` : "/api/admin/invitations"; }
  async list(list) { return (await this.transport(this.path(list))).invitations; }
  send(list, email, locale) { return this.transport(this.path(list), { method: "POST", csrf: this.csrf(), body: { email, locale }, status: 201 }); }
  resend(list, id) { return this.transport(`${this.path(list)}/${id}/resend`, { method: "POST", csrf: this.csrf(), body: {} }); }
  cancel(list, id) { return this.transport(`${this.path(list)}/${id}`, { method: "DELETE", csrf: this.csrf() }); }
}
