# Vendored QR libraries

These browser modules run locally without a CDN, build step or network service.
Spunto informed the phone connection experience; none of its AGPL implementation is copied.

| Module | Upstream | License | SHA-256 of the original download |
| --- | --- | --- | --- |
| qrcodegen.mjs | [Nayuki QR Code generator 1.8.0 ES6 release](https://github.com/nayuki/QR-Code-generator/releases/tag/v1.8.0), tag revision `7ad95cedd8464a87f82221283612732ae4f3f305` | MIT, retained in the module header | `6a1116192ed1dd67fa1bf31e77f5817103d71c23bbac24c382e698b7668bdd01` |
| jsqr.mjs | [jsQR 1.4.0 source](https://github.com/cozmo/jsQR/tree/8e6a036beafa7053dd44b1b76ac578d22b1b3311), `dist/jsQR.js` | Apache-2.0, included at `web/assets/jsqr-LICENSE.txt` | `3325b0888fa4745c4e6940897d8c4f426fbaae76901fcbfe1871a04e90a51655` |

The encoder only adds an ES-module export. The decoder adds a private CommonJS
module object and exports its result; the web app no longer scans codes, so only
the QR tests use it, as an independent check that generated codes decode. Upstream implementation bytes are otherwise
unchanged. To update, review upstream changes, retain licenses and rerun the QR
tests with `node --test web/test/qr.test.mjs`.
