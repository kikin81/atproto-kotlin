## ADDED Requirements

### Requirement: Toolchain SHALL detect upstream lexicon drift on a schedule

The system SHALL run a scheduled GitHub Actions workflow
(`.github/workflows/lexicon-bump.yaml`) at least weekly that attempts to
upgrade the pinned `@atproto/lex` npm dependency to the newest published
version and refresh the CID-pinned corpus in `lexicons.json`. When any
of `package.json`, `package-lock.json`, or `lexicons.json` changes as a
result, the workflow SHALL open a pull request against `main` so the
drift can be reviewed, tested by CI, and merged.

The workflow SHALL also be runnable manually via `workflow_dispatch`.

#### Scenario: Upstream release triggers a bump PR

- **GIVEN** the repo is pinned to `@atproto/lex@0.0.24` and upstream has
  published `@atproto/lex@0.0.25` with lexicon changes
- **WHEN** the scheduled `lexicon-bump` workflow runs
- **THEN** a pull request is opened titled
  `chore(lexicons): bump @atproto/lex to 0.0.25`
- **AND** the PR includes the updated `package.json`,
  `package-lock.json`, and `lexicons.json`
- **AND** the PR body summarizes the CID additions, removals, and
  updates in `lexicons.json`
- **AND** CI runs on the PR so the maintainer can verify generator
  regeneration and tests pass against the new corpus before merging

#### Scenario: No upstream changes produces no PR

- **WHEN** the scheduled workflow runs and `npm install @atproto/lex@latest`
  resolves the already-pinned version, **AND** `npx lex install` produces
  no changes to `lexicons.json`
- **THEN** no pull request is opened and the workflow exits successfully

#### Scenario: Successive bumps reuse one PR branch

- **GIVEN** an open PR from a prior scheduled run at
  `chore/lexicon-bump` targeting `0.0.25`
- **WHEN** a subsequent run finds `0.0.26` is now available
- **THEN** the workflow updates the existing `chore/lexicon-bump` branch
  to `0.0.26` rather than opening a second PR

### Requirement: The @atproto/lex npm dependency SHALL be pinned to an exact version

The `generator/package.json` SHALL declare
`@atproto/lex` with an exact version (no `*`, no `^`, no `~`). Version
bumps SHALL flow exclusively through the scheduled lexicon-bump
workflow or explicit manual PRs — never as a side effect of an
unintended `npm install`.

#### Scenario: Fresh npm install picks up the exact pinned version

- **GIVEN** `generator/package.json` declares
  `"@atproto/lex": "0.0.24"`
- **WHEN** a contributor runs `npm install` from a clean clone
- **THEN** the installed version of `@atproto/lex` is `0.0.24` (not a
  later semver-compatible version)

#### Scenario: Scheduled bump uses save-exact

- **WHEN** the scheduled workflow runs
  `npm install @atproto/lex@latest --save-exact`
- **THEN** the resulting `package.json` declares the exact newly
  installed version (no `^` prefix)
