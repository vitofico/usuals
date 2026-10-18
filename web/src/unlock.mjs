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
  async unlock(passphrase, activate) {
    if (this.cancelled || !this.isCurrent()) return false;
    try {
      await this.candidate.unlock(passphrase);
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
