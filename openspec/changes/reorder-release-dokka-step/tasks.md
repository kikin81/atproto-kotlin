## 1. Edit release.yaml

- [ ] 1.1 Open `.github/workflows/release.yaml`, locate the `release` job
- [ ] 1.2 Identify the block of four contiguous steps under the `release` job that handle Dokka:
  - `name: Generate API docs` (runs `./gradlew :generator:generateModels :dokkaGeneratePublicationHtml --no-configuration-cache`)
  - `name: Stage API docs for GitHub Pages` (runs `rm -rf docs/api && cp -r build/dokka/html docs/api`)
  - `uses: stefanzweifel/git-auto-commit-action@v5` (commit message `"docs: update API documentation [skip ci]"`, file pattern `"docs/api/**"`)
- [ ] 1.3 Move those three steps as a block to immediately **after** the `id: release` step (`uses: open-turo/actions-jvm/release@v2`)
- [ ] 1.4 Leave all setup steps (Checkout, Node, Install lexicon corpus, JDK, Android SDK, Gradle) in their current position and order. They precede everything.
- [ ] 1.5 Leave the `id: release` step's `env:` and `with:` blocks untouched — the inputs to open-turo/actions-jvm/release do not change.
- [ ] 1.6 No changes to the `lint`, `test`, or `build` gate jobs.

## 2. Local static verification

- [ ] 2.1 `actionlint .github/workflows/release.yaml` passes (or rely on the pre-commit `Lint GitHub Actions workflow files` hook when committing)
- [ ] 2.2 Visually confirm in the edited YAML: the "Generate API docs" step now comes after the "id: release" step, and the three-step Dokka block stays contiguous
- [ ] 2.3 No other step in the job references `build/dokka/html` or `docs/api/` between Dokka generation and the auto-commit

## 3. Commit + PR

- [ ] 3.1 Branch `fix/release-dokka-ordering` off `main`
- [ ] 3.2 Commit as `fix(release): run Dokka after semantic-release version bump`
- [ ] 3.3 Open PR; CI lint/test/build jobs should pass without changes (they don't touch the release job)
- [ ] 3.4 Merge to `main`. **This merge itself will not trigger the fix** — it's a `fix:` commit which is a patch bump by semantic-release convention, but the bumping runs via the unchanged ordering on this merge. The reorder takes effect on **subsequent** pushes.

## 4. Post-merge verification on next release

- [ ] 4.1 On the next release-triggering push to `main` (any `feat:`/`fix:`/`BREAKING CHANGE:` commit), inspect the workflow run in the GitHub Actions UI and confirm the step order matches the new YAML
- [ ] 4.2 After the release completes, curl the docs site and grep for `library-version`; confirm the label matches the tag cut in that release
- [ ] 4.3 On a subsequent non-release push (e.g., a `chore:` or `docs:` PR), confirm docs regenerate but the `library-version` label stays at the last released version — not bumped, not stale
- [ ] 4.4 Archive this change with `openspec archive reorder-release-dokka-step` once 4.1–4.3 pass

## 5. Optional cleanups (out of scope for this change)

- Add `concurrency: main` to the release job if we start seeing `git push` races from simultaneous pushes (none observed today; left as a follow-up if needed).
- Consider moving the Dokka step into its own dependent job (`docs: needs: [release]`) for stronger isolation. Current inline arrangement is adequate for a single-writer pipeline.
