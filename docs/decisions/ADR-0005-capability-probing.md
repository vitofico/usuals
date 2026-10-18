# ADR-0005: Probe optional model capabilities

- **Status:** Accepted (2026-09-10), amended 2026-10-15 under [ADR-0012](ADR-0012-smart-shopping-list.md)
- **Relates to:** `LlmClient`, `ModelCapabilities`, `ModelProblem`, `ModelBudget`

## Context

OpenAI-compatible endpoints differ in the response formats they actually honor.
A configured model name cannot establish reachability, accepted credentials or
structured output. The pivot removes retailer capability probes; only the
optional recipe model needs this decision. The previous contract is in Git history.

## Decision

1. Settings provides an explicit Test connection action against the configuration
   currently being edited. `LlmClient.probe` makes a bounded real request and
   validates its structured answer rather than trusting a declared capability.
2. Probes and recipe parses share the saved daily call ceiling and persisted
   UTC-day usage. Zero disables calls, and restarting does not refill the budget.
3. Library failures are structured `ModelProblem` values: kind, HTTP code and a
   bounded redacted excerpt. The app renders English resources or translations;
   raw provider error sentences are not the UI contract.
4. A probe describes that attempt, not a permanent guarantee. Recipe requests
   handle later failures and keep their proposed ingredients in review. There
   is no automatic connection probe on list capture or structured recipe reads.
5. OpenAI-compatible endpoints and Ollama are implemented. The Anthropic choice
   reports unsupported protocol. Ollama calls request no thinking so bounded
   extraction/probe output can contain a final answer.

## Consequences

The list works without a configured model. Connection checks consume visible
budget, and provider errors remain useful without exposing keys. A local label
is configuration, not an assertion about a server's privacy behavior.
