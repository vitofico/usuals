# Optional model providers

The list works without a model. Settings can save an endpoint, model identifier,
network-local flag and model key, and test the connection. Prose recipe parsing is
the only feature that uses a configured model.

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

For Ollama, Comanda requests `reasoning_effort: none` so bounded extraction and
connection probes receive a final answer rather than spending their output
allowance on a reasoning trace. This uses Ollama's documented
[OpenAI compatibility thinking control](https://docs.ollama.com/api/openai-compatibility).
The provider's usual compatibility behavior applies to other endpoints.
