import test from "node:test";
import assert from "node:assert/strict";
import { UnlockAttempt } from "../src/unlock.mjs";

function delayedVault() {
  let finish;
  return {
    key: null,
    held: false,
    unlock() {
      return new Promise((resolve) => {
        finish = () => {
          this.key = "synthetic-key";
          this.held = true;
          resolve();
        };
      });
    },
    lock() {
      this.key = null;
      this.held = false;
    },
    finish() {
      finish();
    },
  };
}

test("cancelled delayed unlock cannot replace the next dialog's active vault", async () => {
  const a = delayedVault(),
    b = delayedVault();
  let activeVault = null,
    client = null,
    generation = 0;
  const activate = (candidate) => {
    activeVault = candidate;
    client = { vault: candidate };
    generation++;
  };
  const attemptA = new UnlockAttempt(a, () => generation === 0);
  const pendingA = attemptA.unlock("synthetic phrase A", activate);
  attemptA.cancel(); // Cancel, ESC and replacement all use the same cleanup hook.
  const attemptB = new UnlockAttempt(b, () => generation === 0);
  const pendingB = attemptB.unlock("synthetic phrase B", activate);
  b.finish();
  assert.equal(await pendingB, true);
  attemptB.cancel(); // Closing a successfully activated dialog transfers ownership.
  assert.equal(b.held, true);
  a.finish(); // An underlying operation may still complete after cancellation.
  assert.equal(await pendingA, false);
  assert.equal(a.key, null);
  assert.equal(a.held, false);
  assert.equal(activeVault, b);
  assert.equal(client.vault, activeVault);
  activeVault.lock();
  assert.equal(b.key, null);
  assert.equal(b.held, false);
});

test("lifecycle change discards a delayed candidate before activation", async () => {
  const candidate = delayedVault();
  let current = true,
    activations = 0;
  const attempt = new UnlockAttempt(candidate, () => current);
  const pending = attempt.unlock("synthetic phrase", () => activations++);
  current = false;
  candidate.finish();
  assert.equal(await pending, false);
  assert.equal(activations, 0);
  assert.equal(candidate.key, null);
  assert.equal(candidate.held, false);
  attempt.cancel();
  assert.equal(
    await attempt.unlock("unused phrase", () => activations++),
    false,
  );
  assert.equal(activations, 0);
});
