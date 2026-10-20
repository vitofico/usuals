import { Problem, requireThat } from "./wire.mjs";
const palette = { red: "#ff0000", blue: "#0000ff", green: "#008000", yellow: "#ffff00", black: "#000000", white: "#ffffff" };
export async function visionChallenge() {
  const colours = Object.keys(palette), random = crypto.getRandomValues(new Uint32Array(5));
  for (let i = 5; i > 0; i--) { const j = random[i - 1] % (i + 1); [colours[i], colours[j]] = [colours[j], colours[i]]; }
  const canvas = document.createElement("canvas"); canvas.width = 384; canvas.height = 256;
  const context = canvas.getContext("2d");
  colours.forEach((colour, i) => { context.fillStyle = palette[colour]; context.fillRect(i % 3 * 128, Math.floor(i / 3) * 128, 128, 128); });
  return { colours, image: canvas.toDataURL("image/png") };
}
/** Only raster bytes are accepted; dimensions are bounded before a decoder is asked to allocate. */
export function rasterInfo(bytes) {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const png = [137, 80, 78, 71, 13, 10, 26, 10].every((b, i) => bytes[i] === b);
  if (png) {
    requireThat(bytes.length >= 33, "importImageInvalid");
    return { mime: "image/png", width: view.getUint32(16), height: view.getUint32(20), orientation: 1 };
  }
  requireThat(bytes[0] === 255 && bytes[1] === 216, "importImageInvalid");
  let width = 0, height = 0, orientation = 1, at = 2;
  while (at + 3 < bytes.length) {
    requireThat(bytes[at++] === 255, "importImageInvalid");
    while (bytes[at] === 255) at++;
    const marker = bytes[at++];
    if (marker === 218 || marker === 217) break;
    if (marker === 1 || marker >= 208 && marker <= 215) continue;
    requireThat(at + 2 <= bytes.length, "importImageInvalid");
    const length = view.getUint16(at), end = at + length;
    requireThat(length >= 2 && end <= bytes.length, "importImageInvalid");
    if ([192, 193, 194, 195, 197, 198, 199, 201, 202, 203, 205, 206, 207].includes(marker)) {
      requireThat(length >= 8, "importImageInvalid"); height = view.getUint16(at + 3); width = view.getUint16(at + 5);
    }
    if (marker === 225 && length >= 16 && String.fromCharCode(...bytes.subarray(at + 2, at + 8)) === "Exif\0\0") {
      const tiff = at + 8, little = view.getUint16(tiff) === 0x4949;
      if ([0x4949, 0x4d4d].includes(view.getUint16(tiff)) && tiff + 8 <= end) {
        const ifd = tiff + view.getUint32(tiff + 4, little);
        if (ifd >= tiff && ifd + 2 <= end) {
          const count = Math.min(view.getUint16(ifd, little), 512);
          for (let i = 0; i < count; i++) {
            const p = ifd + 2 + i * 12; if (p + 12 > end) break;
            if (view.getUint16(p, little) === 274 && view.getUint16(p + 2, little) === 3 && view.getUint32(p + 4, little) === 1)
              orientation = view.getUint16(p + 8, little);
          }
        }
      }
    }
    at = end;
  }
  requireThat(width > 0 && height > 0, "importImageInvalid");
  return { mime: "image/jpeg", width, height, orientation };
}
export async function preparePhoto(file) {
  requireThat(file && file.size > 0 && file.size <= 10 * 1024 * 1024, "importTooLarge");
  const bytes = new Uint8Array(await file.arrayBuffer()), info = rasterInfo(bytes);
  let { width, height } = info;
  if ([5, 6, 7, 8].includes(info.orientation)) [width, height] = [height, width];
  requireThat(width > 0 && height > 0 && width <= 16384 && height <= 16384, "importImageInvalid");
  requireThat(width * height <= 48_000_000, "importTooLarge");
  const scale = Math.min(1, 2048 / Math.max(width, height));
  width = Math.max(1, Math.round(width * scale)); height = Math.max(1, Math.round(height * scale));
  let bitmap;
  try {
    bitmap = await createImageBitmap(new Blob([bytes], { type: info.mime }), { imageOrientation: "from-image", resizeWidth: width, resizeHeight: height, resizeQuality: "high" });
    const canvas = document.createElement("canvas"); canvas.width = width; canvas.height = height;
    const context = canvas.getContext("2d"); context.fillStyle = "white"; context.fillRect(0, 0, width, height); context.drawImage(bitmap, 0, 0, width, height);
    const image = canvas.toDataURL("image/jpeg", .85);
    requireThat(image.length <= 2800000, "importTooLarge");
    return image;
  } catch (error) { if (error instanceof Problem) throw error; throw new Problem("importImageInvalid"); }
  finally { bitmap?.close(); }
}
export function photoBlob(image) {
  const content = atob(image.slice(image.indexOf(",") + 1));
  return new Blob([Uint8Array.from(content, x => x.charCodeAt(0))], { type: "image/jpeg" });
}

export class PhotoCamera {
  #stream = null; #video = null; #closed = false;
  constructor(media = globalThis.navigator?.mediaDevices) { this.media = media; }
  async start(video) {
    requireThat(!this.#closed && this.media?.getUserMedia, "importCameraFailed");
    const stream = await this.media.getUserMedia({ video: { facingMode: { ideal: "environment" }, width: { ideal: 1920 }, height: { ideal: 1080 } }, audio: false });
    if (this.#closed) { stream.getTracks().forEach(t => t.stop()); return false; }
    this.#stream = stream; this.#video = video; video.srcObject = stream;
    try { await video.play(); return !this.#closed; } catch { this.stop(); throw new Problem("importCameraFailed"); }
  }
  capture() {
    requireThat(!this.#closed && this.#stream && this.#video?.videoWidth > 0 && this.#video.videoHeight > 0, "importCameraFailed");
    try {
    const video = this.#video, scale = Math.min(1, 2048 / Math.max(video.videoWidth, video.videoHeight));
    const canvas = document.createElement("canvas"); canvas.width = Math.round(video.videoWidth * scale); canvas.height = Math.round(video.videoHeight * scale);
    canvas.getContext("2d").drawImage(video, 0, 0, canvas.width, canvas.height);
    const image = canvas.toDataURL("image/jpeg", .85);
    requireThat(image.length <= 2800000, "importTooLarge"); return image;
    } catch (error) { if (error instanceof Problem) throw error; throw new Problem("importCameraFailed"); }
    finally { this.stop(); }
  }
  stop() {
    this.#closed = true; this.#stream?.getTracks().forEach(t => t.stop()); this.#stream = null;
    if (this.#video) { this.#video.srcObject = null; this.#video = null; }
  }
}
