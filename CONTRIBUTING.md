# Contributing to Usuals

Usuals is an offline shopping list with local purchase memory. Read
`AGENTS.md` and the current docs before making a change. Keep capture fast,
keep secrets local and preserve the no-telemetry and no-Play-Services rules.

Use `scripts/dgradle test` and `scripts/dgradle :app:assembleDebug`. New behavior
needs meaningful tests; fixes need regression tests. Verify UI changes on the
emulator. Keep the docs and exported Room schema in step with implementation.
From finalized schema 4 onward, changes need migrations and migration tests.

All user-facing sentences belong to `:app` resources, with English defaults.
Additional UI languages add complete locale folders. See the
[translation guide](docs/translating.md) for editing text, a new-language helper,
formatting checks and the shared glossary. No Android tooling is needed to edit
translations. Keep input-language packs independent. Changes to normalization
bump `NORMALIZER_VERSION`; prompt or model-response contract changes bump
`PROMPT_VERSION`.

Delete dead code and check that `ServiceLocator` constructs new components.
Use deterministic clocks and stable ordering in algorithms. Provider and recipe
HTTP tests use fixtures. No server participates in third-party reads.

Commits use `:emoji: type: subject`, for example:

```
:sparkles: feat: record purchases when a trip closes
:bug: fix: preserve the entered quantity beside a hint
:white_check_mark: test: cover finish and undo
```

Do not add attribution boilerplate. Do not use em dashes in prose. During the
pivot, work on `feat/smart-list`. Publication requires maintainer authorization;
the initial private GitHub publication was approved on 2026-10-18. Ask before
any cluster deployment.

## Code of Conduct

By participating you agree to the [Code of Conduct](CODE_OF_CONDUCT.md).

## Contributor License Agreement

Before a pull request from any contributor other than the maintainer can be
merged, every such contributor on the PR must sign the [Usuals Contributor
License Agreement](CLA.md) by posting a new comment on the PR containing
**exactly**:

```
I have read the Usuals Contributor License Agreement and I hereby sign the CLA
```

The maintainer reviews PR comments before merge and will not merge until every
required signer has commented the phrase above. Automation bots that do not
author copyrightable changes (`dependabot[bot]`, `github-actions[bot]`,
`renovate[bot]`) are implicitly exempt.

The CLA does **not** change the outbound license of Usuals. The app remains
Apache-2.0 unless a specific file, subtree or future release states otherwise.
What it does is preserve the option to license accepted contributions under
AGPL-3.0-or-later for parts of the project that turn out to need it, notably a
future sync relay, without re-collecting permission from every past contributor.
See [`CLA.md`](CLA.md) section 4.

## License

Usuals is licensed under [Apache-2.0](LICENSE). By submitting a contribution
you agree it is provided under Apache-2.0 and under the terms of the
[Usuals CLA](CLA.md).
