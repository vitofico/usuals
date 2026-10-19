import {
  Problem,
  requireThat,
  b64,
  unb64,
  hash,
  hex,
  uuid,
  zero,
  strictJson,
  jsonBytes,
  equal,
  fingerprint,
  joinBytes,
  authBytes,
  parseHeader,
  manifestHash,
  packageHash,
  envelopeHash,
} from "./wire.mjs";
import {
  newDevice,
  sign,
  verify,
  unwrap,
  open,
  seal,
  verifyReceipt,
} from "./crypto.mjs";
import {
  body,
  devices,
  owners,
  genesis,
  transition,
  verifyChain,
} from "./membership.mjs";
import { reduce, snapshotDecode, canonical } from "./reducer.mjs";
import * as owner from "./owner.mjs";
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
const allowedCodes = new Set([
  "unauthenticated",
  "forbidden",
  "not_found",
  "epoch_stale",
  "membership_conflict",
  "candidate_expired",
  "intent_conflict",
  "intent_exists",
  "envelope_conflict",
  "base_unknown",
  "recovery_required",
  "rate_limited",
  "quota_exceeded",
  "too_large",
  "invalid",
  "unavailable",
  "conflict",
]);
export async function request(
  path,
  {
    method = "GET",
    body: payload,
    token,
    csrf,
    max = 1024 * 1024,
    scope = new RunScope(),
    status = 200,
    timeoutMs = 30000,
    empty = false,
  } = {},
) {
  scope.check();
  requireThat(
    path.startsWith("/") &&
      !path.startsWith("//") &&
      !path.includes("#") &&
      !/[\u0000-\u0020\\]/.test(path),
  );
  requireThat(
    Number.isInteger(timeoutMs) && timeoutMs > 0 && timeoutMs <= 30000,
  );
  const headers = { Accept: "application/json" };
  if (payload !== undefined) headers["Content-Type"] = "application/json";
  if (token) headers.Authorization = `Bearer ${token}`;
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
    if (response.status !== status) {
      let code = "invalid";
      try {
        const value = strictJson(bytes, Math.min(max, 65536), true);
        if (allowedCodes.has(value.code)) code = value.code;
      } catch {}
      throw new Problem(code);
    }
    if (status === 204 || (empty && size === 0)) return null;
    return strictJson(bytes, max, true);
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
function originValid(value) {
  const url = new URL(value);
  return (
    url.origin === value &&
    !url.username &&
    !url.password &&
    (url.protocol === "https:" ||
      (url.protocol === "http:" &&
        ["localhost", "127.0.0.1", "[::1]"].includes(url.hostname)))
  );
}
export function parseInvitation(uri, origin) {
  requireThat(
    typeof uri === "string" &&
      uri.startsWith("usuals://join#") &&
      uri.length <= 4096,
  );
  const value = strictJson(unb64(uri.slice(uri.indexOf("#") + 1)), 3072);
  requireThat(
    Object.keys(value).every((k) =>
      [
        "version",
        "origin",
        "serverId",
        "householdId",
        "genesisHash",
        "ownerDeviceId",
        "ownerFingerprint",
        "inviteId",
        "memberId",
        "role",
        "secret",
        "expiresAt",
      ].includes(k),
    ) &&
      value.version === 1 &&
      value.role === "editor" &&
      Number.isSafeInteger(value.expiresAt) &&
      value.expiresAt > 0 &&
      originValid(value.origin) &&
      value.origin === origin,
    "wrong_origin",
  );
  for (const k of ["householdId", "ownerDeviceId", "inviteId", "memberId"])
    uuid(value[k]);
  for (const k of ["serverId", "genesisHash", "ownerFingerprint", "secret"])
    unb64(value[k], 32);
  return value;
}
export class HouseholdClient {
  constructor(
    vault,
    account,
    {
      origin = location.origin,
      clock = () => Date.now(),
      transport = request,
      scope = new RunScope(),
    } = {},
  ) {
    this.vault = vault;
    this.account = account;
    this.origin = origin;
    this.clock = clock;
    this.transport = transport;
    this.scope = scope;
    this.queue = Promise.resolve();
  }
  stop() {
    this.scope.stop();
    return this.queue;
  }
  createHousehold(setup, name) { return this.serial(() => owner.beginCreation(this, setup, name)); }
  completeCreation() { return this.serial(() => owner.finishCreation(this)); }
  abandonRejectedCreation() { return this.serial(() => owner.abandonRejectedCreation(this)); }
  invite(id) { return this.serial(() => owner.invite(this, id)); }
  applicants(id) { return this.serial(() => owner.applicants(this, id)); }
  approve(id, pending, trusted) { return this.serial(() => owner.approve(this, id, pending, trusted)); }
  removeDevice(id, device) { return this.serial(() => owner.remove(this, id, device)); }
  discardInvalidMembership(id) { return this.serial(() => owner.discardInvalidPending(this, id)); }
  denyApplicant(id, request) { return this.serial(async () => {
    await this.refresh(id);
    return this.authorized(id, `/v1/households/${id}/join-requests/${request}/deny`, { method: "POST", empty: true });
  }); }
  serial(action) {
    const promise = this.queue.then(() => {
      this.scope.check();
      return action();
    });
    this.queue = promise.catch(() => {});
    return promise;
  }
  async call(path, options = {}) {
    return this.scope.wait(
      this.transport(path, {
        ...options,
        csrf:
          options.csrf ??
          (options.method && options.method !== "GET"
            ? this.account.csrfToken
            : undefined),
        scope: this.scope,
      }),
    );
  }
  read(id) {
    this.scope.check();
    const h = this.vault.read().households[id];
    requireThat(h, "not_found");
    return h;
  }
  async save(id, change) {
    this.scope.check();
    await this.scope.wait(
      this.vault.mutate((s) => {
        this.scope.check();
        requireThat(s.households[id], "not_found");
        change(s.households[id], s);
      }),
    );
  }
  async info() {
    const info = await this.call("/v1/server-info");
    requireThat(
      (await this.scope.wait(hash(unb64(info.receiptPublicKey, 32)))) ===
        info.serverId && info.protocolVersions.includes(1),
    );
    return info;
  }
  async proof(device, server, purpose = "session", registered = true) {
    const c = await this.call("/v1/devices/challenge", {
      method: "POST",
      body: {
        deviceId: device.publicKeys.id,
        purpose,
        ...(!registered
          ? { signingPublicKey: device.publicKeys.signingPublicKey }
          : {}),
      },
    });
    requireThat(
      c.serverId === server.serverId &&
        c.purpose === purpose &&
        c.expiresAt > this.clock(),
    );
    return {
      challengeId: c.challengeId,
      deviceId: device.publicKeys.id,
      signature: await this.scope.wait(
        sign(
          device,
          authBytes(
            this.origin,
            server.serverId,
            device.publicKeys,
            c,
            purpose,
          ),
        ),
      ),
    };
  }
  async login(device, server) {
    const proof = await this.proof(device, server);
    const session = await this.call("/v1/devices/session", {
      method: "POST",
      body: proof,
    });
    requireThat(
      typeof session.token === "string" &&
        Number.isSafeInteger(session.expiresAt) &&
        session.expiresAt > this.clock(),
    );
    return session;
  }
  async session(id) {
    const h = this.read(id);
    requireThat(!h.removed, "forbidden");
    if (h.session?.expiresAt > this.clock() + 30000) return h.session.token;
    const session = await this.login(h.device, h.server);
    await this.save(id, (s) => {
      s.session = session;
    });
    return session.token;
  }
  async authorized(id, path, options = {}) {
    const token = await this.session(id);
    try {
      return await this.call(path, { ...options, token });
    } catch (e) {
      if (!["unauthenticated", "forbidden"].includes(e.code)) throw e;
      await this.save(id, (h) => {
        h.session = null;
      });
      return this.call(path, { ...options, token: await this.session(id) });
    }
  }
  requestJoin(uri) {
    return this.serial(async () => {
      const invitation = parseInvitation(uri, this.origin);
      requireThat(invitation.expiresAt > this.clock(), "invite_expired");
      requireThat(
        !this.vault.read().households[invitation.householdId] &&
          !this.vault.read().joining && !this.vault.read().creating,
        "already_joined",
      );
      const server = await this.info();
      requireThat(server.serverId === invitation.serverId);
      const device = await this.scope.wait(newDevice()),
        c = await this.call("/v1/devices/challenge", {
          method: "POST",
          body: {
            deviceId: device.publicKeys.id,
            purpose: "join",
            signingPublicKey: device.publicKeys.signingPublicKey,
          },
        });
      requireThat(
        c.serverId === server.serverId &&
          c.purpose === "join" &&
          c.expiresAt > this.clock(),
      );
      const assertion = {
          origin: this.origin,
          serverId: server.serverId,
          householdId: invitation.householdId,
          genesisHash: invitation.genesisHash,
          inviteId: invitation.inviteId,
          requestId: crypto.randomUUID(),
          memberId: invitation.memberId,
          device: device.publicKeys,
          secretHash: await this.scope.wait(hash(unb64(invitation.secret, 32))),
          challengeId: c.challengeId,
          nonce: c.nonce,
          expiresAt: invitation.expiresAt,
        },
        signature = await this.scope.wait(sign(device, joinBytes(assertion))),
        pending = { invitation, server, device, assertion, signature };
      await this.scope.wait(
        this.vault.mutate((s) => {
          requireThat(!s.joining && !s.households[invitation.householdId]);
          s.joining = pending;
        }),
      );
      await this.sendJoin(pending);
      return this.pendingFingerprint();
    });
  }
  async sendJoin(p) {
    const value = await this.call("/v1/join-requests", {
      method: "POST",
      body: {
        secret: p.invitation.secret,
        assertion: b64(joinBytes(p.assertion)),
        signature: p.signature,
      },
      status: 202,
    });
    requireThat(
      value.requestId === p.assertion.requestId &&
        value.expiresAt === p.assertion.expiresAt,
    );
  }
  async pendingFingerprint() {
    const p = this.vault.read().joining;
    requireThat(p, "not_found");
    return hex(
      unb64(
        await this.scope.wait(
          fingerprint(
            p.server.serverId,
            p.invitation.householdId,
            p.device.publicKeys,
          ),
        ),
        32,
      ),
    );
  }
  async chain(id, token, pin, server) {
    const proofs = [];
    let previous = null,
      hashValue = pin;
    for (let n = 0; n < 10000; n++) {
      const page = await this.call(
        `/v1/households/${id}/membership?afterEpoch=${previous?.epoch ?? 0}`,
        { token, max: 8 * 1024 * 1024 },
      );
      if (!page.manifests.length) {
        requireThat(previous && page.nextEpoch === previous.epoch);
        return proofs;
      }
      requireThat(page.manifests.length === 1);
      const proof = page.manifests[0],
        m = previous
          ? await this.scope.wait(
              transition(proof, previous, hashValue, pin, server.serverId),
            )
          : await this.scope.wait(genesis(proof, server, id, pin));
      requireThat(page.nextEpoch === m.epoch);
      proofs.push(proof);
      previous = m;
      hashValue = await this.scope.wait(manifestHash(proof));
    }
    throw new Problem("too_large");
  }
  completeJoin() {
    return this.serial(async () => {
      const p = this.vault.read().joining;
      requireThat(p, "not_found");
      if (this.vault.read().households[p.invitation.householdId]) {
        const id = p.invitation.householdId;
        requireThat(
          equal(this.read(id).device.publicKeys, p.device.publicKeys),
        );
        await this.link(id);
        await this.scope.wait(
          this.vault.mutate((s) => {
            s.joining = null;
          }),
        );
        return id;
      }
      const server = await this.info();
      requireThat(equal(server, p.server));
      let session;
      try {
        session = await this.login(p.device, server);
      } catch (e) {
        if (e.code === "forbidden") {
          requireThat(p.invitation.expiresAt > this.clock(), "invite_expired");
          await this.sendJoin(p);
          return null;
        }
        throw e;
      }
      const id = p.invitation.householdId,
        proofs = await this.chain(
          id,
          session.token,
          p.invitation.genesisHash,
          server,
        ),
        verified = await this.scope.wait(
          verifyChain(proofs, server, id, p.invitation.genesisHash),
        );
      return this.finishJoin(p, session, proofs, verified);
    });
  }
  async finishJoin(p, session, proofs, verified) {
    const id = p.invitation.householdId,
      assertionHash = await this.scope.wait(hash(joinBytes(p.assertion)));
    requireThat(
      proofs.some(
        (proof) =>
          body(proof).approvedJoins[p.assertion.requestId] === assertionHash,
      ),
      "unverified_join",
    );
    requireThat(
      verified.manifest.members.some(
        (m) =>
          m.id === p.invitation.memberId &&
          m.role === p.invitation.role &&
          m.devices.some((d) => equal(d, p.device.publicKeys)),
      ),
    );
    let inviter = false;
    for (const proof of proofs)
      for (const d of owners(body(proof)))
        if (
          d.id === p.invitation.ownerDeviceId &&
          (await this.scope.wait(fingerprint(p.server.serverId, id, d))) ===
            p.invitation.ownerFingerprint
        )
          inviter = true;
    requireThat(inviter, "unverified_join");
    const h = {
      id,
      origin: this.origin,
      server: p.server,
      device: p.device,
      proofs,
      keys: {},
      headPins: {},
      receiptPins: {},
      lists: {},
      session,
      removed: false,
      linked: false,
    };
    h.keys = await this.receiveKeys(h, session.token);
    await this.scope.wait(
      this.vault.mutate((s) => {
        requireThat(!s.households[id]);
        s.households[id] = h;
      }),
    );
    await this.link(id);
    await this.scope.wait(
      this.vault.mutate((s) => {
        s.joining = null;
      }),
    );
    return id;
  }
  async link(id) {
    let h = this.read(id);
    if (h.linked) return;
    const proof = await this.proof(h.device, h.server);
    try {
      await this.call("/api/devices/link", {
        method: "POST",
        body: proof,
        csrf: this.account.csrfToken,
        status: 204,
      });
    } catch (e) {
      if (e.code !== "conflict") throw e;
    }
    await this.save(id, (s) => {
      s.linked = true;
      s.session = null;
    });
    await this.session(id);
  }
  async receiveKeys(h, token) {
    const m = body(h.proofs.at(-1)),
      prior = h.proofs.length > 1 ? body(h.proofs.at(-2)) : null,
      signer =
        prior?.recovery.id === m.signer
          ? prior.recovery
          : devices(prior ?? { members: [] }).find((d) => d.id === m.signer),
      result = { ...h.keys };
    if (!m.lists.length) return result;
    requireThat(signer);
    for (const l of m.lists) {
      const page = await this.call(
        `/v1/households/${h.id}/key-packages?listId=${l.listId}&epoch=${m.epoch}`,
        { token },
      );
      requireThat(page.packages.length === 1);
      const p = page.packages[0];
      requireThat(
        p.listId === l.listId &&
          p.epoch === m.epoch &&
          p.predecessorHash === m.previousHash &&
          m.packageHashes.includes(
            await this.scope.wait(packageHash(h.server.serverId, h.id, p)),
          ),
      );
      result[`${l.listId}:${m.epoch}`] = b64(
        await this.scope.wait(
          unwrap(h.server.serverId, h.id, p, h.device, signer.signingPublicKey),
        ),
      );
    }
    return result;
  }
  async bridge(
    h,
    list,
    target,
    targetHead,
    token,
    epoch = body(h.proofs.at(-1)).epoch,
  ) {
    const pin = h.headPins[list];
    if (!pin) return;
    requireThat(target >= pin.seq, "history_unverifiable");
    let seq = pin.seq,
      head = pin.head;
    while (seq < target) {
      const limit = Math.min(100, target - seq),
        page = await this.call(
          `/v1/lists/${list}/operations?after=${seq}&limit=${limit}`,
          { token, max: 10 * 1024 * 1024 },
        );
      requireThat(page.entries.length > 0 && page.entries.length <= limit);
      for (const entry of page.entries) {
        const header = parseHeader(entry.envelope.header);
        requireThat(
          header.serverId === h.server.serverId &&
            header.householdId === h.id &&
            header.listId === list &&
            header.epoch <= epoch,
        );
        await this.scope.wait(
          verifyReceipt(
            entry.envelope,
            entry.receipt,
            h.server.receiptPublicKey,
            seq + 1,
            head,
          ),
        );
        seq = entry.receipt.seq;
        head = entry.receipt.head;
        requireThat(
          !h.receiptPins[list]?.[seq] || h.receiptPins[list][seq] === head,
        );
      }
      requireThat(page.nextCursor === seq);
    }
    requireThat(head === targetHead, "history_unverifiable");
  }
  async refresh(id, resumePending = true) {
    if (resumePending) await owner.resumeMembership(this, id);
    let h = this.read(id);
    if (!h.linked) {
      await this.link(id);
      h = this.read(id);
    }
    const verified = await this.scope.wait(
      verifyChain(
        h.proofs,
        h.server,
        id,
        await this.scope.wait(manifestHash(h.proofs[0])),
      ),
    );
    let m = verified.manifest,
      currentHash = verified.hash;
    const token = await this.session(id);
    for (let n = 0; n < 10000; n++) {
      const page = await this.call(
        `/v1/households/${id}/membership?afterEpoch=${m.epoch}`,
        { token, max: 8 * 1024 * 1024 },
      );
      if (!page.manifests.length) {
        requireThat(page.nextEpoch === m.epoch);
        break;
      }
      requireThat(page.manifests.length === 1);
      const proof = page.manifests[0],
        nextVerified = await this.scope.wait(
          verifyChain(
            [...h.proofs, proof],
            h.server,
            id,
            await this.scope.wait(manifestHash(h.proofs[0])),
          ),
        ),
        next = nextVerified.manifest;
      requireThat(page.nextEpoch === next.epoch);
      for (const c of next.lists) {
        const receipts = Object.keys(h.receiptPins[c.listId] ?? {}).map(Number);
        requireThat(!receipts.length || c.seq >= Math.max(...receipts));
        await this.bridge(h, c.listId, c.seq, c.head, token, m.epoch);
      }
      h.proofs.push(proof);
      for (const c of next.lists)
        h.headPins[c.listId] = { seq: c.seq, head: c.head };
      h.removed = !devices(next).some((d) => equal(d, h.device.publicKeys));
      await this.save(id, (s) => {
        s.proofs = h.proofs;
        s.headPins = h.headPins;
        s.removed = h.removed;
      });
      m = next;
      currentHash = await this.scope.wait(manifestHash(proof));
    }
    requireThat(!h.removed, "forbidden");
    if (m.lists.some((l) => !h.keys[`${l.listId}:${m.epoch}`])) {
      const received = await this.receiveKeys(h, token);
      await this.save(id, (s) => {
        s.keys = received;
      });
      h = this.read(id);
    }
    return h;
  }
  async checkpoint(id, list) {
    let h = this.read(id),
      m = body(h.proofs.at(-1)),
      currentHash = await this.scope.wait(manifestHash(h.proofs.at(-1))),
      token = await this.session(id),
      saved = h.lists[list]?.confirmed;
    const cutoff = m.lists.find((entry) => entry.listId === list);
    requireThat(cutoff, "not_found");
    if (!h.headPins[list])
      h.headPins[list] = { seq: cutoff.seq, head: cutoff.head };
    const key = unb64(h.keys[`${list}:${m.epoch}`], 32);
    let seq, head, projection;
    if (
      saved &&
      saved.epoch === m.epoch &&
      saved.manifestHash === currentHash &&
      equal(h.headPins[list], { seq: saved.seq, head: saved.head })
    ) {
      ({ seq, head, projection } = saved);
    } else {
      const snapshot = await this.call(`/v1/lists/${list}/snapshot`, {
          token,
          max: 10 * 1024 * 1024,
        }),
        e = snapshot.envelope,
        header = parseHeader(e.header);
      requireThat(
        header.serverId === h.server.serverId &&
          header.householdId === id &&
          header.listId === list &&
          header.epoch === m.epoch &&
          header.baseSeq === snapshot.seq &&
          header.baseHead === snapshot.head,
      );
      if (snapshot.seq >= (h.headPins[list]?.seq ?? 0))
        await this.bridge(h, list, snapshot.seq, snapshot.head, token);
      let author;
      if (header.manifestHash === currentHash)
        author = owners(m).find((d) => d.id === header.authorDeviceId);
      else {
        const snapshotHash = await this.scope.wait(envelopeHash(e));
        requireThat(
          header.manifestHash === m.previousHash &&
            header.authorDeviceId === m.signer &&
            m.lists.some(
              (l) =>
                l.listId === list &&
                l.seq === snapshot.seq &&
                l.head === snapshot.head &&
                l.snapshotHash === snapshotHash,
            ),
        );
        const previous = body(h.proofs.at(-2));
        author =
          m.action === "recover" &&
          previous.recovery.id === header.authorDeviceId
            ? previous.recovery
            : owners(previous).find((d) => d.id === header.authorDeviceId);
      }
      requireThat(author, "unverified_snapshot");
      projection = await this.scope.wait(
        snapshotDecode(
          strictJson(
            await this.scope.wait(
              open(e, key, author.signingPublicKey, 10 * 1024 * 1024),
            ),
            7 * 1024 * 1024,
            true,
          ),
        ),
      );
      seq = snapshot.seq;
      head = snapshot.head;
    }
    while (true) {
      const page = await this.call(
        `/v1/lists/${list}/operations?after=${seq}&limit=100`,
        { token, max: 10 * 1024 * 1024 },
      );
      for (const entry of page.entries) {
        const header = parseHeader(entry.envelope.header),
          author = devices(m).find((d) => d.id === header.authorDeviceId);
        requireThat(
          header.serverId === h.server.serverId &&
            header.householdId === id &&
            header.listId === list &&
            header.epoch === m.epoch &&
            header.manifestHash === currentHash &&
            header.baseSeq < entry.receipt.seq &&
            author,
        );
        await this.scope.wait(
          verifyReceipt(
            entry.envelope,
            entry.receipt,
            h.server.receiptPublicKey,
            seq + 1,
            head,
          ),
        );
        const intent = strictJson(
          await this.scope.wait(
            open(entry.envelope, key, author.signingPublicKey),
          ),
          65536,
          true,
        );
        requireThat(intent.id === header.intentId && intent.listId === list);
        const result = await this.scope.wait(reduce(projection, intent));
        requireThat(
          result.outcome !== "UPGRADE_REQUIRED",
          result.reason ?? "upgrade_required",
        );
        projection = result.projection;
        seq = entry.receipt.seq;
        head = entry.receipt.head;
        requireThat(
          !h.headPins[list] ||
            h.headPins[list].seq !== seq ||
            h.headPins[list].head === head,
        );
        requireThat(
          !h.receiptPins[list]?.[seq] || h.receiptPins[list][seq] === head,
        );
      }
      requireThat(
        page.nextCursor === seq && (!page.hasMore || page.entries.length > 0),
      );
      if (!page.hasMore) break;
    }
    const pin = h.headPins[list],
      receipts = Object.keys(h.receiptPins[list] ?? {}).map(Number);
    requireThat(
      (!pin || seq > pin.seq || (seq === pin.seq && head === pin.head)) &&
        (!receipts.length || seq >= Math.max(...receipts)),
      "history_unverifiable",
    );
    await this.save(id, (s) => {
      s.headPins[list] = { seq, head };
      delete s.receiptPins[list];
      const local = s.lists[list] ?? { pending: [] };
      local.confirmed = {
        seq,
        head,
        projection,
        epoch: m.epoch,
        manifestHash: currentHash,
      };
      local.pending = local.pending.filter((p) => {
        const accepted = projection.seen[p.intent.id];
        if (!accepted) return true;
        if (equal(accepted, p.intent)) return false;
        p.blocked = "intent_conflict";
        return true;
      });
      s.lists[list] = local;
    });
    return this.read(id).lists[list].confirmed;
  }
  synchronize(id, list) {
    return this.serial(async () => {
      await this.refresh(id);
      let checkpoint = await this.checkpoint(id, list),
        h = this.read(id),
        m = body(h.proofs.at(-1)),
        currentHash = await this.scope.wait(manifestHash(h.proofs.at(-1))),
        key = unb64(h.keys[`${list}:${m.epoch}`], 32),
        keyHash = await this.scope.wait(hash(key));
      for (const pending of h.lists[list].pending
        .filter((p) => !p.blocked)
        .slice(0, 100)) {
        let e = pending.envelope ? strictJson(pending.envelope, 100000) : null;
        const header = e ? parseHeader(e.header) : null;
        if (
          e &&
          (header.epoch !== m.epoch || header.manifestHash !== currentHash)
        ) {
          try {
            const committed = await this.authorized(
              id,
              `/v1/lists/${list}/intents/${pending.intent.id}`,
            );
            await this.scope.wait(
              verifyReceipt(
                committed.envelope,
                committed.receipt,
                h.server.receiptPublicKey,
                committed.receipt.seq,
                committed.receipt.previousHead,
              ),
            );
            const oldHeader = parseHeader(committed.envelope.header);
            requireThat(
              oldHeader.intentId === pending.intent.id &&
                oldHeader.authorDeviceId === h.device.publicKeys.id &&
                oldHeader.householdId === id &&
                oldHeader.listId === list &&
                oldHeader.serverId === h.server.serverId,
            );
            await this.save(id, (saved) => {
              const pins = saved.receiptPins[list] ?? {};
              const prior = saved.headPins[list];
              requireThat(
                !pins[committed.receipt.seq] ||
                  pins[committed.receipt.seq] === committed.receipt.head,
              );
              requireThat(
                !prior ||
                  prior.seq !== committed.receipt.seq ||
                  prior.head === committed.receipt.head,
              );
              if (!prior || committed.receipt.seq > prior.seq)
                pins[committed.receipt.seq] = committed.receipt.head;
              pins[committed.receipt.seq] = committed.receipt.head;
              saved.receiptPins[list] = pins;
            });
            const retainedHead = this.read(id).headPins[list];
            requireThat(
              !retainedHead || committed.receipt.seq > retainedHead.seq,
              "history_unverifiable",
            );
            const oldKey = unb64(h.keys[`${list}:${oldHeader.epoch}`], 32),
              existing = strictJson(
                await this.scope.wait(
                  open(
                    committed.envelope,
                    oldKey,
                    h.device.publicKeys.signingPublicKey,
                  ),
                ),
                65536,
                true,
              );
            requireThat(equal(existing, pending.intent), "intent_conflict");
            await this.checkpoint(id, list);
            continue;
          } catch (error) {
            if (error.code !== "not_found") throw error;
          }
          e = null;
        }
        if (!e) {
          e = await this.scope.wait(
            seal(
              {
                serverId: h.server.serverId,
                householdId: id,
                listId: list,
                authorDeviceId: h.device.publicKeys.id,
                intentId: pending.intent.id,
                envelopeId: crypto.randomUUID(),
                epoch: m.epoch,
                manifestHash: currentHash,
                baseSeq: checkpoint.seq,
                baseHead: checkpoint.head,
              },
              jsonBytes(pending.intent),
              key,
              h.device,
              () =>
                this.vault.reserve(
                  `${id}:${list}:${m.epoch}:${h.device.publicKeys.id}:${keyHash}`,
                ),
            ),
          );
          await this.save(id, (s) => {
            const p = s.lists[list].pending.find(
              (p) => p.intent.id === pending.intent.id,
            );
            requireThat(p && equal(p.intent, pending.intent));
            p.envelope = JSON.stringify(e);
          });
        } else
          requireThat(
            equal(
              strictJson(
                await this.scope.wait(
                  open(e, key, h.device.publicKeys.signingPublicKey),
                ),
                65536,
                true,
              ),
              pending.intent,
            ),
          );
        let result;
        try {
          result = await this.authorized(id, `/v1/lists/${list}/operations`, {
            method: "POST",
            body: { envelopes: [e] },
          });
        } catch (error) {
          if (error.code === "intent_exists") {
            await this.checkpoint(id, list);
            continue;
          }
          throw error;
        }
        requireThat(result.results.length === 1);
        const r = result.results[0],
          eh = parseHeader(e.header);
        requireThat(
          r.inputIndex === 0 &&
            r.intentId === eh.intentId &&
            r.envelopeId === eh.envelopeId &&
            ["accepted", "duplicate"].includes(r.outcome) &&
            r.receipt.seq > checkpoint.seq,
        );
        await this.scope.wait(
          verifyReceipt(
            e,
            r.receipt,
            h.server.receiptPublicKey,
            r.receipt.seq,
            r.receipt.previousHead,
          ),
        );
        await this.save(id, (s) => {
          const pins = s.receiptPins[list] ?? {},
            old = s.headPins[list];
          requireThat(
            !pins[r.receipt.seq] || pins[r.receipt.seq] === r.receipt.head,
          );
          requireThat(
            !old || old.seq !== r.receipt.seq || old.head === r.receipt.head,
          );
          pins[r.receipt.seq] = r.receipt.head;
          s.receiptPins[list] = pins;
        });
        checkpoint = await this.checkpoint(id, list);
        h = this.read(id);
      }
      return this.read(id);
    });
  }
  enqueue(id, list, kind, payload) {
    return this.serial(async () => {
      requireThat(["capture", "edit", "check", "delete"].includes(kind));
      const h = this.read(id);
      requireThat(!h.removed && h.lists[list]?.confirmed, "not_found");
      const intent = {
          id: crypto.randomUUID(),
          listId: list,
          kind,
          payload,
          schema: 1,
          normalizer: 1,
        },
        projection = await this.view(id, list),
        result = await this.scope.wait(reduce(projection, intent));
      requireThat(
        ["APPLIED", "MERGED"].includes(result.outcome),
        result.outcome === "CONFLICT"
          ? "edit_conflict"
          : (result.reason ?? "invalid"),
      );
      await this.save(id, (s) => {
        requireThat(s.lists[list].pending.length < 1000, "quota_exceeded");
        s.lists[list].pending.push({ intent, envelope: null, blocked: null });
      });
      return intent;
    });
  }
  async view(id, list) {
    const local = this.read(id).lists[list];
    requireThat(local?.confirmed, "not_found");
    let p = local.confirmed.projection;
    for (const pending of local.pending) {
      if (pending.blocked) continue;
      const result = await this.scope.wait(reduce(p, pending.intent));
      p = result.projection;
    }
    return p;
  }
}
