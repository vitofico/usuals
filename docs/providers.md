# Optional model providers

The list works without a model. Settings can save an endpoint, model identifier,
network-local flag and model key, and test the connection. Prose recipes and photos of written shopping lists or ingredient lists can use
a configured model. Markdown and structured recipe pages work locally.

The client supports OpenAI-compatible chat completions, including Ollama.
The Anthropic option reports that this build does not support its protocol.
Keys live in Keystore, separate from provider settings. Probes validate a real
structured reply rather than trusting provider declarations. Errors are redacted
and bounded before reaching the app.

For Ollama, use `http://10.0.2.2:11434/v1` from the Android emulator,
`http://host.docker.internal:11434/v1` from Docker, or the machine's LAN address
from a physical phone. The model field must match an installed model. A server
bound only to loopback cannot be reached from a different device.

A hosted endpoint sees what is submitted to it. The app makes calls directly;
there is no model proxy. The daily call ceiling is enforced in code. Zero means
model use is disabled. The running budget reads the saved ceiling, and changes
apply immediately.

Tests use local scripted HTTP responses. No CI test requires a live model.

## Recipe calls and local reasoning models

Recipe pages with structured data never call a model. For a prose page, the
review sheet asks before sending its text. Input is a JSON object containing
one `recipe_text` string value; the reply must match the ingredient contract.
Model output remains a draft until selected ingredients are added.

The running budget starts from the saved daily ceiling. Changes apply at once,
and UTC-day usage persists before requests so restarting cannot refill it.
Connection probes share this allowance. A ceiling of zero makes no model call.

For Ollama, Usuals requests `reasoning_effort: none` so bounded extraction and
connection probes receive a final answer rather than spending their output
allowance on a reasoning trace. This uses Ollama's documented
[OpenAI compatibility thinking control](https://docs.ollama.com/api/openai-compatibility).
The provider's usual compatibility behavior applies to other endpoints.

## Photos of written lists

Choose **Photo** in Android recipe import, then select a photo or use the camera.
The preview is local. **Read photo with my model** first submits a randomized
six-colour test image to the currently configured endpoint. Only a correct
structured answer enables submitting the prepared photo. Text-only, unavailable
or unsupported providers leave the list untouched. Each request consumes one
Android daily-budget attempt, including failed probes. Reading normally takes
two attempts. Cancelling closes the underlying request.

Android and web remove source metadata by re-encoding oriented pixels as JPEG,
limit source files to 10 MiB and 48 million pixels, and reduce the longest edge
to at most 2048 pixels. Prepared uploads are limited to approximately 2 MiB.
A model proposes only visible written items, without guessing from dishes or
packages. Always check names and quantities in the review before adding them.
The capability test is a compatibility check, not an accuracy guarantee.

## Browser model connection

An operator must set `USUALS_WEB_MODEL_ORIGINS` to a JSON array of exact origins,
for example `["https://models.example.org"]`. The default is `[]`, which leaves
photo import unavailable. HTTPS is required except for localhost loopback QA.
Wildcards, credentials and paths are rejected. This sets the browser CSP and
public `/model-origins.json`; it never configures server-side model calls.

The provider must allow the web app origin through CORS and implement the
OpenAI-compatible Chat Completions image content format. In **Import ingredients
> Photo**, enter its base endpoint, model and optional key. A synthetic image
check must succeed before choosing or capturing a photo. Changing any connection
field disconnects the previous provider. Keys and photos are held only for the
open dialog, cleared on close or vault lock, and never enter IndexedDB or the
relay. The request cap counts attempts for that connection, including probes;
it is a client guard and not a billing limit. Use a restricted provider key
suitable for browser access. Markdown import needs no provider connection.

The wire format follows the official [OpenAI image input guide](https://developers.openai.com/api/docs/guides/images-vision).
