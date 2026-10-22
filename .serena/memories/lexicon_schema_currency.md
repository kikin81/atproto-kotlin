# Lexicon Schema Currency & Upstream Parity

## Objective
Keep the AT Protocol lexicon schemas up to date with the latest changes from `bluesky-social/atproto` so that the Android client **Nubecita** maintains parity with the official Bluesky client.

## Components & File Structure
- `generator/package.json`: Pins the exact version of the `@atproto/lex` npm package.
- `generator/lexicons.json`: The manifest specifying explicitly opted-in NSIDs (`lexicons` array) and pinned CID resolutions (`resolutions` map).
- `generator/lexicons/`: Installed lexicon JSON corpus populated by `npx lex install`.
- `generator/overlay-lexicons/`: Vendored custom or unmerged lexicons, tracked in `generator/overlay-lexicons.json`.
- `generator/field-default-overrides.json`: Field default overrides when upstream schema diverges from reality.
- `generator/src/test/resources/golden/`: Golden tests comparing generator outputs against known lexicons.

## Upstream Refresh & Bump Workflow
1. Check available version:
   `npm view @atproto/lex version`
2. Update pin in `generator/package.json` to exact version (e.g. `npm install @atproto/lex@latest --save-exact` in `generator/`).
3. Re-install lexicon corpus:
   `cd generator && npx lex install && cd ..`
4. Regenerate models:
   `./gradlew :generator:generateModels`
5. Check for uncovered upstream NSIDs (lexicon drift):
   `./gradlew :generator:detectLexiconDrift`
6. Check for stale overlays:
   `./gradlew :generator:detectStaleOverlays`
7. Update golden files if codegen output or annotations evolved intentionally:
   `GOLDEN_UPDATE=1 ./gradlew :generator:test --tests '*GoldenFileTest*'`
8. Validate build and test suites:
   `./gradlew build`

## Automated CI/CD
- `.github/workflows/lexicon-bump.yaml`: Scheduled weekly run (Mondays 14:00 UTC) and manual `workflow_dispatch`. Tests bump against `@atproto/lex@latest`, runs `npx lex install`, regenerates models, and opens a PR (`chore/lexicon-bump`).
- `.github/workflows/lexicon-drift-detect.yaml`: Runs drift detection against upstream `bluesky-social/atproto` repository tree.
- `.github/workflows/overlay-staleness.yaml`: Validates if vendored overlays in `generator/overlay-lexicons` have been merged upstream or drifted.
