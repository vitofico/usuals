# Reducer fixtures

`reducer.json` contains ordered semantic scenarios using symbolic ids. These
are not transport payloads. The fixture adapter maps them to production commands
and must check every expected fact and outcome through the real reducer.
Initial fields not relevant to a scenario use empty/null defaults; revisions
of supplied initial fields use `genesis`. A finish's `expectedLifecycle` is
separate from its `expectedFields`. Fact paths address rows by item id, not by
canonical identity; counters count the effective projection. `automaticRows`
are initial rows with repeat provenance, and `effectiveCloses` describe the
accepted close that Undo references. Existing purchase facts imply their trips.
`applied_once` means a duplicated input applies once and is idempotent on retry.

The repeat-cycle amendment adds nine scenarios, for 38 reducer scenarios.
It preserves next-shop deferral without resurrecting accepted old occurrences.
