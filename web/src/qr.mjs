import { qrcodegen } from "./vendor/qrcodegen.mjs";
import jsQR from "./vendor/jsqr.mjs";
import { requireThat, strictJson, unb64, uuid } from "./wire.mjs";

export function qrMatrix(value) {
  requireThat(typeof value === "string" && value.length <= 4096);
  const qr = qrcodegen.QrCode.encodeText(value, qrcodegen.QrCode.Ecc.MEDIUM);
  return Array.from({ length: qr.size }, (_, y) =>
    Array.from({ length: qr.size }, (_, x) => qr.getModule(x, y)));
}

export function verificationQr(uri) {
  requireThat(typeof uri === "string" && uri.startsWith("usuals://device#") && uri.length <= 4096, "unverified");
  const pending = strictJson(unb64(uri.slice("usuals://device#".length)), 3072);
  requireThat(Object.keys(pending).sort().join() === "assertion,requestId,signature", "unverified");
  uuid(pending.requestId); unb64(pending.assertion); unb64(pending.signature, 64);
  return pending;
}

/** The stream belongs to this dialog, including permission prompts resolved after closing. */
export class VerificationCamera {
  constructor(video, canvas, onCode, onProblem, {
    media = globalThis.navigator?.mediaDevices,
    decode = jsQR,
    schedule = setTimeout,
    cancel = clearTimeout,
  } = {}) {
    Object.assign(this, { video, canvas, onCode, onProblem, media, decode, schedule, cancel });
    this.closed = false;
  }
  async start() {
    try {
      requireThat(this.media?.getUserMedia, "cameraUnavailable");
      const stream = await this.media.getUserMedia({ audio: false,
        video: { facingMode: "environment", width: { ideal: 1920 }, height: { ideal: 1080 } } });
      if (this.closed) { stream.getTracks().forEach(t => t.stop()); return; }
      this.stream = stream; this.video.srcObject = stream;
      await this.video.play();
      if (!this.closed) this.tick();
    } catch {
      if (!this.closed) { this.stop(); this.onProblem(); }
    }
  }
  tick() {
    if (this.closed) return;
    try {
      const { video, canvas } = this;
      if (video.readyState >= 2 && video.videoWidth && video.videoHeight) {
        // Keep the centre at camera resolution before bounding it. Shrinking the
        // whole widescreen frame makes a dense signed-device QR unreadable.
        const side = Math.min(video.videoWidth, video.videoHeight);
        canvas.width = canvas.height = Math.min(side, 800);
        const context = canvas.getContext("2d", { willReadFrequently: true });
        context.drawImage(video, (video.videoWidth - side) / 2, (video.videoHeight - side) / 2,
          side, side, 0, 0, canvas.width, canvas.height);
        const result = this.decode(context.getImageData(0, 0, canvas.width, canvas.height).data,
          canvas.width, canvas.height, { inversionAttempts: "dontInvert" });
        if (result?.data?.startsWith("usuals://device#") && result.data.length <= 4096) {
          this.stop(); this.onCode(result.data); return;
        }
      }
      this.timer = this.schedule(() => this.tick(), 350);
    } catch { this.stop(); this.onProblem(); }
  }
  stop() {
    this.closed = true;
    this.cancel(this.timer);
    this.stream?.getTracks().forEach(t => t.stop());
    this.stream = null;
    this.video.srcObject = null;
    this.canvas.width = this.canvas.height = 0;
  }
}
