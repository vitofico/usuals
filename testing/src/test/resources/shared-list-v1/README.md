# S0 synthetic fixtures

`wire.json` is a Python-authored binary framing golden with valid UUID bytes.
Its synthetic ciphertext is not a valid encrypted record. `ed25519-rfc8032.json`
is public RFC 8032 test 1, never an application key. JVM tests use real Tink
primitives for signatures, RAW AES-GCM/JCA interoperability and HPKE rejection.

`reducer.json` contains ordered semantic scenarios using symbolic ids. These
are not transport payloads. The S1 fixture adapter maps them to production commands
and must check every expected fact and outcome through the real reducer.
Initial fields not relevant to a scenario use empty/null defaults; revisions
of supplied initial fields use `genesis`. A finish's `expectedLifecycle` is
separate from its `expectedFields`. Fact paths address rows by item id, not by
canonical identity; counters count the effective projection. `automaticRows`
are initial rows with repeat provenance, and `effectiveCloses` describe the
accepted close that Undo references. Existing purchase facts imply their trips.
`applied_once` means a duplicated input applies once and is idempotent on retry.

`security.json` specifies denials and transaction outcomes for later real
S2/S3 integration tests. The S0 probes validate fixture integrity and contract shape. S1 adds actual
reducer and migration tests; authentication and restore await later phases.
Do not report those future gates as passed because these files parse.

The S1 repeat-cycle amendment adds nine scenarios, for 38 reducer scenarios.
It preserves next-shop deferral without resurrecting accepted old occurrences.

`../pairing-v2/vectors.json` is a Python-authored pairing v2 golden: HKDF
admission and MAC keys from a fixed invite key, the join MAC and 6-digit code
over synthetic assertion bytes, the descriptor anchor and both link forms.
Kotlin (`PairingTest`) and the browser (`pairing.test.mjs`) check every field.
