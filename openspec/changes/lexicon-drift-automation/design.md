## Overview

Automate lexicon drift detection via a scheduled GitHub Actions workflow
that bumps `@atproto/lex`, regenerates models, and opens a PR when
anything changes. The PR becomes the review surface for upstream changes.

## Workflow: lexicon-bump.yaml

### Trigger

```yaml
on:
  schedule:
    - cron: "0 14 * * 1" # 14:00 UTC Mondays → 7am PT, reviewable during the week
  workflow_dispatch: {}   # manual kickoff for ad-hoc bumps
```

Weekly is a reasonable default: `@atproto/lex` on npm ships at roughly
that cadence, and any faster pollutes the PR queue.

### Job steps

1. **Checkout** with a fetch depth of 1 and `token: ${{ secrets.RELEASE_PAT }}`
   so the PR-creation step can push a branch.
2. **Set up Node 22 + JDK 17** (matches existing workflows).
3. **Bump the npm dependency**:
   ```bash
   cd at-protocol-generator
   npm update @atproto/lex
   ```
   This respects the semver range in `package.json`. After this change
   lands with an exact pin, we use a more explicit approach:
   ```bash
   npm install @atproto/lex@latest --save-exact
   ```
   This always grabs the newest published version and rewrites
   `package.json` + `package-lock.json`.
4. **Refresh the pinned corpus**: `npx lex install` (without `--ci`). This
   rewrites `lexicons.json` with the newest CIDs and re-downloads the
   corpus into `lexicons/` (gitignored).
5. **Regenerate models**: `./gradlew :at-protocol-generator:generateModels`.
6. **Detect changes**:
   ```bash
   git add at-protocol-generator/package.json \
           at-protocol-generator/package-lock.json \
           at-protocol-generator/lexicons.json \
           at-protocol-models/build/generated/source/lexicon/
   if git diff --cached --quiet; then
     echo "no_changes=true" >> "$GITHUB_OUTPUT"
   fi
   ```
   Note: generated sources live under `build/`, which is **not** committed.
   We don't commit generated sources — the PR only includes `package.json`,
   `package-lock.json`, and `lexicons.json`. Generator output is regenerated
   from these three files, so the tests on the PR already validate it.
7. **Open or update a PR** via `peter-evans/create-pull-request@v7`:
   - Branch: `chore/lexicon-bump` (stable branch — successive runs update
     the same PR rather than creating a pile of stale ones)
   - Title: `chore(lexicons): bump @atproto/lex to <version>`
   - Body: see below
   - Labels: `lexicon-bump`, `dependencies`
8. **Skip if no changes**: the entire job short-circuits when
   `no_changes=true` so we don't open empty PRs.

### PR body content

The body is generated inline by the workflow and contains:

```
## Lexicon corpus update

Bumps `@atproto/lex` from `<old>` to `<new>`.

### lexicons.json

- Added: N new lexicon CIDs (list first 20, then "...")
- Removed: N lexicons (list)
- Updated: N lexicons whose CID changed (list)

### What reviewers should check

- [ ] CI passes (generator regeneration, runtime tests, sample APK build)
- [ ] Skim the generator golden-file diff if any generator logic was
      affected
- [ ] Verify no new lexicon references are missing Kotlin types (compile
      failure in `:at-protocol-models`)

### Release impact

If this PR merges with only additive changes (new types, new fields),
expect a `feat:` release. If it removes or renames anything, expect a
`BREAKING CHANGE:` release — the PR title should be updated to
`feat!: bump @atproto/lex to <version>` before merging.
```

We don't try to classify the change as feat/fix automatically — that's a
human judgment call based on looking at the diff.

### Failure modes

- **Workflow runs but CI fails on the PR**: the PR stays open, the
  maintainer investigates. Could be a generator bug surfaced by a new
  lexicon shape (fix the generator, push to the PR branch) or a lexicon
  we genuinely don't want to support yet (close the PR, hold).
- **`npx lex install` fails**: the workflow fails, the maintainer gets a
  notification from GitHub. No PR opened.
- **Two consecutive upstream releases between runs**: the stable branch
  (`chore/lexicon-bump`) gets force-updated to the newer version. Reviews
  in progress get rebased — acceptable, since the unit of review is
  "adopt the current upstream."

## package.json pin

Change:

```json
{ "dependencies": { "@atproto/lex": "*" } }
```

to the currently-resolved version (today that's `0.0.24`):

```json
{ "dependencies": { "@atproto/lex": "0.0.24" } }
```

Exact pinning (not `^`) matches the spirit of CID pinning in
`lexicons.json`: we want explicit, reviewable bumps, not silent semver
drift. The scheduled workflow uses `--save-exact` to keep it that way.

## Permissions

The workflow needs:
- `contents: write` — to push the `chore/lexicon-bump` branch
- `pull-requests: write` — to open/update the PR

These already exist on the `GITHUB_TOKEN` in other workflows, but PR
creation across a branch requires a PAT with `workflow` scope if the PR
is to trigger CI. Use the existing `RELEASE_PAT` secret that
`release.yaml` already relies on.

## Documentation

Add a short section to `CONTRIBUTING.md` under "Larger architectural
changes" or a new "Lexicon updates" section:

```markdown
### Lexicon updates

Upstream AT Protocol lexicons are bumped automatically. A scheduled
workflow opens a PR titled `chore(lexicons): bump @atproto/lex to <v>`
each week when upstream has drifted. To trigger a manual bump, run the
`lexicon-bump` workflow via the Actions tab on GitHub. Merging the PR
cuts a release whose semver bump depends on whether the generator
output added, removed, or broke existing types.
```

## Non-goals

- **Automatic feat/fix classification.** Humans read the diff and adjust
  the PR title before merging if a breaking change landed.
- **Automatic retrying or bisecting generator failures.** If new lexicon
  shapes break the generator, the PR stays red until fixed manually.
- **Consolidating CI lexicon fetches** (the 2x–3x `lex install --ci`
  duplication noted in analysis). That's a separate optimization — this
  change is about drift detection, not CI efficiency.
- **Releasing `:at-protocol-models` with a version string derived from
  `@atproto/lex`.** The existing spec already gestures at this but the
  release pipeline doesn't implement it; treat as out of scope here.
- **Committing generated sources.** Generator output stays in `build/`
  per current architecture.

## Testing

- **Workflow syntax**: Lint the new YAML via the existing
  `open-turo/actions-jvm/lint` step in `ci.yaml` (picks up workflow
  files automatically).
- **Manual smoke test**: Run the workflow via `workflow_dispatch` on the
  feature branch. Verify a PR opens against `main` with the expected
  body shape. Close the test PR without merging.
- **Dry-run**: Optional env var `LEXICON_BUMP_DRY_RUN=true` could skip
  the PR-open step and just print what would have happened — useful for
  debugging the workflow without polluting PRs. Defer unless needed.
