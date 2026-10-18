# Security Policy

## Assets and scope

Comanda holds a shopping list, local purchase history, confirmed preferences
and aliases, and an optional model API key. The key uses Android Keystore-backed
encrypted preferences and is never included in logs or shopping-data exports.
The settings screen is wired to that store. The configured model endpoint
receives the key for authentication when required. There are no retailer sessions,
payment credentials, accounts or sync server in this build.

Security reports can cover the Android app and its modules: `app`, `auth`,
`capture`, `core`, `data/local`, `smart`, `recipe`, `llm` and `enrich`. In scope:

- Key exposure, missing diagnostic redaction or unintended network disclosure.
- Recipe reads or model calls that bypass explicit actions or the daily ceiling.
- Scan requests while lookup is off, or third-party names applied without confirmation.
- Untrusted page/model content escaping its data and reviewed-output boundary.
- Archive import violating validation, immutable purchase facts or secret isolation.
- Cloud backup or transfer policies exposing excluded preferences and secrets.

Third-party reads run on the phone. Recipe sites see requested URLs; Open Food
Facts sees barcodes only with opt-in. A selected model sees recipe text only
after the explicit model action. The list and its prediction algorithms need
none of these services. See the [security model](docs/security-model.md).

Voice prefers Android on-device recognition when available. Otherwise the
selected system speech service may process audio off the phone. Comanda cannot
attest that service; keyboard capture remains an offline fallback. This boundary
is also stated on the app privacy screen.

## Supported versions and reporting

The project is unreleased. Only the latest development revision is supported;
there are no backports. Report a vulnerability privately to the maintainer.
Once the repository is published, use its private vulnerability-reporting channel.
Do not put keys, private exports or security proofs into public issues.

Include the affected surface, commit, minimal reproduction and impact. The
maintainer aims to acknowledge within 5 business days and provide a fix or
mitigation for confirmed high-severity issues within 30 days. Coordinated
disclosure is preferred. Reporters may choose release-note credit; no bounty
is offered.

## Limits

A compromised/rooted device or physical access to an unlocked phone is outside
this app's protection. Third-party service storage behavior and vulnerabilities
in those services belong to their operators. Dependency issues should also be
reported upstream. Reports about Comanda's use of a dependency remain welcome.

Recipe input is untrusted text, serialized as a JSON string value for the model.
This reduces instruction confusion without making model output authoritative:
all proposed ingredients still require review. A local endpoint label does not
prove that traffic is encrypted or that its server stores nothing. Explicit
shopping exports are readable files at the chosen location.

No encrypted relay or invite flow exists. Work on one requires separate approval;
a future relay must not proxy third-party reads. Documented current limitations
are in [known issues](docs/known-issues.md).
