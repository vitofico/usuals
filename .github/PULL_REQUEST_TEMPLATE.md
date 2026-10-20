## What and why

<!-- One to three sentences. What changes, and what problem it solves. Link the
     issue if there is one. -->

## Checklist

- [ ] `scripts/dgradle test` passes locally (the Docker run is the authoritative
      one, a host Gradle is not)
- [ ] Tests cover the change, and they are deterministic: no wall clock, no
      locale assumptions, no hash-map ordering
- [ ] No telemetry, analytics, crash reporting or third-party SDK added
- [ ] No Google Play Services dependency added, directly or transitively
      (this is what keeps the F-Droid build possible, see the one-way doors in
      the design)
- [ ] The invariants in [`AGENTS.md`](../AGENTS.md) still hold, or this PR adds
      an ADR under `docs/decisions/` that supersedes the one it changes
- [ ] User-facing strings are in Spanish where the design says so, and say what
      happened and what to do next
- [ ] No AI attribution anywhere: not in the commits, not in this PR body, not
      in a code comment

## Related issues

<!-- Closes #N, refs #M. -->
