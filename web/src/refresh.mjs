/** One active refresh and at most one trailing request, regardless of timer ticks. */
export class RefreshLoop {
  constructor({
    refresh,
    canRun,
    schedule = (callback, delay) => globalThis.setInterval(callback, delay),
    cancel = (id) => globalThis.clearInterval(id),
  }) {
    this.refresh = refresh;
    this.canRun = canRun;
    this.schedule = schedule;
    this.cancel = cancel;
    this.timer = null;
    this.pending = null;
    this.again = false;
    this.generation = 0;
  }
  start() {
    this.stop();
    this.timer = this.schedule(() => {
      if (this.canRun()) this.run().catch(() => {});
    }, 20000);
  }
  run(force = false) {
    if (this.pending) {
      if (force) this.again = true;
      return this.pending;
    }
    if (!this.canRun() && !force) return Promise.resolve();
    const generation = this.generation;
    const task = Promise.resolve().then(async () => {
      do {
        this.again = false;
        if (generation !== this.generation) return;
        await this.refresh();
      } while (generation === this.generation && this.again);
    });
    this.pending = task.finally(() => {
      if (generation === this.generation) {
        this.pending = null;
        this.again = false;
      }
    });
    return this.pending;
  }
  stop() {
    this.generation++;
    if (this.timer !== null) this.cancel(this.timer);
    this.timer = null;
    this.pending = null;
    this.again = false;
  }
}
