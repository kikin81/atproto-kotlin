## Why

Upstream AT Protocol lexicons at `@atproto/lex` evolve continuously, but this
repo has no automated way to detect drift. The current flow relies on a
maintainer manually running `npx lex install` locally, noticing changes in
`lexicons.json`, and opening a PR. Two concrete consequences:

1. **Invisible drift.** Between manual bumps, the models published to Maven
   Central diverge silently from the upstream lexicon corpus. Users report
   missing fields or types before the maintainer notices.
2. **Fragile version declaration.** `at-protocol-generator/package.json`
   declares `"@atproto/lex": "*"`. Reproducibility is accidentally maintained
   by `package-lock.json` alone — any `npm install` (instead of `npm ci`)
   would silently pick up a newer major. CI runs `npm ci` so we're safe
   today, but the declaration is a latent footgun.

A scheduled workflow that bumps the lexicon corpus and opens a PR turns
drift into a reviewable, testable event. The PR is the review surface: CI
runs the full test suite against the new corpus; the maintainer skims the
generator output diff before merging.

## What Changes

- **New scheduled GitHub Actions workflow** (`.github/workflows/
  lexicon-bump.yaml`) that runs weekly and on `workflow_dispatch`. The job:
  1. Runs `npm update @atproto/lex` to pick up new npm releases
  2. Runs `npx lex install` (no `--ci`) to refresh `lexicons.json` against
     upstream CIDs
  3. Runs `./gradlew :at-protocol-generator:generateModels`
  4. If any of `package.json`, `package-lock.json`, `lexicons.json`, or
     generated sources changed, opens a PR titled
     `chore(lexicons): bump corpus to @atproto/lex@<version>`
  5. PR body includes: old/new `@atproto/lex` version, summary of
     `lexicons.json` changes (CIDs added/removed/updated), and a
     byte-level summary of generated source deltas.
- **Tighten `package.json` version pin**: change `"@atproto/lex": "*"` to
  a specific version matching what `package-lock.json` currently resolves.
  The scheduled workflow is then responsible for bumping it.
- **Document the flow** in `CONTRIBUTING.md` so external contributors know
  that lexicon bumps are automated and how to trigger a manual run.

## Capabilities

### Modified Capabilities

- `lexicon-toolchain`: adds requirements for automated drift detection and
  strict npm version pinning.

## Impact

- **New file**: `.github/workflows/lexicon-bump.yaml` (scheduled workflow).
- **Modified file**: `at-protocol-generator/package.json` — exact pin for
  `@atproto/lex` instead of `"*"`.
- **Modified file**: `CONTRIBUTING.md` — short section on lexicon updates.
- **No runtime or generator code changes.** The generator already handles
  regeneration idempotently via `generateModels`'s up-to-date check.
- **Breaking changes**: none. The artifact surface is unaffected.
- **Release cadence**: once the workflow lands, new lexicon adoption shifts
  from "whenever someone remembers" to "weekly, reviewable." Each merged
  bump PR cuts either a `fix:` or `feat:` release depending on whether the
  generator output is additive or breaking.
