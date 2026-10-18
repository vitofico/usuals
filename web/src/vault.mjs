import {
  b64,
  unb64,
  bytes,
  utf8,
  strictJson,
  jsonBytes,
  requireThat,
  Problem,
  keys,
  uint,
  field,
} from "./wire.mjs";
import { random } from "./crypto.mjs";
const iterations = 600000,
  maximum = 160 * 1024 * 1024;
export const vaultInitial = () => ({
  version: 1,
  households: {},
  joining: null,
  reservations: {},
  writes: 0,
});
function aad(record) {
  return bytes(
    "USUALS/BROWSER-VAULT/1\0",
    field(utf8.encode(record.binding)),
    unb64(record.salt, 16),
    uint(record.iterations, 4),
  );
}
export async function derive(passphrase, salt) {
  requireThat(
    typeof passphrase === "string" &&
      passphrase.length >= 14 &&
      passphrase.length <= 1024,
    "weak_passphrase",
  );
  const base = await crypto.subtle.importKey(
    "raw",
    utf8.encode(passphrase),
    "PBKDF2",
    false,
    ["deriveKey"],
  );
  return crypto.subtle.deriveKey(
    { name: "PBKDF2", hash: "SHA-256", iterations, salt },
    base,
    { name: "AES-GCM", length: 256 },
    false,
    ["encrypt", "decrypt"],
  );
}
export async function encryptRecord(state, key, salt, binding) {
  const data = jsonBytes(state);
  requireThat(data.length <= maximum, "too_large");
  strictJson(data, maximum, true);
  const record = {
    version: 1,
    iterations,
    binding,
    salt: b64(salt),
    nonce: b64(random(12)),
  };
  record.ciphertext = b64(
    new Uint8Array(
      await crypto.subtle.encrypt(
        {
          name: "AES-GCM",
          iv: unb64(record.nonce, 12),
          additionalData: aad(record),
        },
        key,
        data,
      ),
    ),
  );
  return record;
}
export async function decryptRecord(record, key, binding) {
  keys(record, [
    "version",
    "iterations",
    "binding",
    "salt",
    "nonce",
    "ciphertext",
  ]);
  requireThat(
    record.version === 1 &&
      record.iterations === iterations &&
      record.binding === binding,
    "upgrade_required",
  );
  const cipher = unb64(record.ciphertext);
  requireThat(cipher.length <= maximum + 16, "too_large");
  const plain = new Uint8Array(
    await crypto.subtle.decrypt(
      {
        name: "AES-GCM",
        iv: unb64(record.nonce, 12),
        additionalData: aad(record),
      },
      key,
      cipher,
    ),
  );
  const state = strictJson(plain, maximum, true);
  requireThat(
    state.version === 1 &&
      Number.isSafeInteger(state.writes) &&
      state.writes >= 0 &&
      state.households &&
      state.reservations,
    "upgrade_required",
  );
  return state;
}
export class Vault {
  constructor(storage, binding) {
    requireThat(
      typeof binding === "string" &&
        binding.length > 0 &&
        binding.length <= 1024,
    );
    this.binding = binding;
    this.storage = storage;
    this.state = null;
    this.key = null;
    this.salt = null;
    this.release = null;
    this.generation = 0;
    this.queue = Promise.resolve();
  }
  async exists() {
    return (await this.storage.read()) !== undefined;
  }
  async unlock(passphrase, locks = globalThis.navigator?.locks) {
    requireThat(locks && crypto?.subtle, "unsupported_browser");
    requireThat(!this.key, "locked");
    let readyResolve, readyReject;
    const ready = new Promise((resolve, reject) => {
      readyResolve = resolve;
      readyReject = reject;
    });
    const generation = ++this.generation;
    const lockTask = locks.request(
      "usuals-encrypted-vault",
      { ifAvailable: true },
      async (lock) => {
        if (!lock) {
          readyReject(new Problem("vault_in_use"));
          return;
        }
        try {
          const record = await this.storage.read();
          const salt = record ? unb64(record.salt, 16) : random(16),
            key = await derive(passphrase, salt);
          const state = record
            ? await decryptRecord(record, key, this.binding)
            : vaultInitial();
          requireThat(generation === this.generation, "locked");
          this.salt = salt;
          this.key = key;
          this.state = state;
          const held = new Promise((resolve) => {
            this.release = resolve;
          });
          if (!record) await this.mutate((s) => s);
          readyResolve();
          await held;
        } catch (error) {
          this.lock();
          readyReject(error);
        }
      },
    );
    lockTask.catch(readyReject);
    await ready;
  }
  lock(drain = Promise.resolve()) {
    this.generation++;
    this.key = null;
    this.salt = null;
    this.state = null;
    const release = this.release;
    this.release = null;
    if (release) Promise.allSettled([this.queue, drain]).then(release);
  }
  read() {
    requireThat(this.key && this.state, "locked");
    return structuredClone(this.state);
  }
  async mutate(change) {
    const run = async () => {
      requireThat(this.key && this.state, "locked");
      const generation = this.generation,
        draft = structuredClone(this.state),
        next = change(draft) ?? draft;
      requireThat(next.writes < 10000000, "quota_exceeded");
      next.writes++;
      const record = await encryptRecord(
        next,
        this.key,
        this.salt,
        this.binding,
      );
      requireThat(generation === this.generation, "locked");
      try {
        await this.storage.write(record);
      } catch {
        this.lock();
        throw new Problem("storage_unavailable");
      }
      requireThat(generation === this.generation, "locked");
      this.state = next;
      return this.read();
    };
    const task = this.queue.then(run);
    this.queue = task.catch(() => {});
    return task;
  }
  async reserve(scope) {
    await this.mutate((s) => {
      const n = s.reservations[scope] ?? 0;
      requireThat(n < 4096, "rotation_required");
      s.reservations[scope] = n + 1;
    });
  }
}
export async function openStorage(binding) {
  requireThat(globalThis.indexedDB, "unsupported_browser");
  const db = await new Promise((resolve, reject) => {
    const req = indexedDB.open("usuals-private-vault", 1);
    req.onupgradeneeded = () => req.result.createObjectStore("vault");
    req.onerror = () => reject(new Problem("storage_unavailable"));
    req.onblocked = () => reject(new Problem("storage_unavailable"));
    req.onsuccess = () => resolve(req.result);
  });
  return {
    read: () =>
      new Promise((resolve, reject) => {
        const tx = db.transaction("vault", "readonly"),
          req = tx.objectStore("vault").get(binding);
        req.onsuccess = () => resolve(req.result);
        req.onerror = () => reject(new Problem("storage_unavailable"));
      }),
    write: (record) =>
      new Promise((resolve, reject) => {
        const tx = db.transaction("vault", "readwrite", {
          durability: "strict",
        });
        tx.objectStore("vault").put(record, binding);
        tx.oncomplete = () => resolve();
        tx.onerror = () => reject(new Problem("storage_unavailable"));
        tx.onabort = () => reject(new Problem("storage_unavailable"));
      }),
  };
}
