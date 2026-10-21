/** A dialog owns its candidate until one synchronous, current activation. */
export class UnlockAttempt {
  constructor(candidate, isCurrent) {
    this.candidate = candidate;
    this.isCurrent = isCurrent;
    this.cancelled = false;
    this.transferred = false;
  }
  cancel() {
    this.cancelled = true;
    if (!this.transferred) this.candidate.lock();
  }
  /** A null passphrase opens the vault with the key this browser remembered. */
  async unlock(passphrase, activate) {
    if (this.cancelled || !this.isCurrent()) return false;
    try {
      await (passphrase === null ? this.candidate.unlockRemembered() : this.candidate.unlock(passphrase));
      if (this.cancelled || !this.isCurrent()) {
        this.candidate.lock();
        return false;
      }
      activate(this.candidate);
      this.transferred = true;
      return true;
    } catch (error) {
      this.candidate.lock();
      if (this.cancelled || !this.isCurrent()) return false;
      throw error;
    }
  }
}
