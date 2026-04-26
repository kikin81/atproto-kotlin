## 1. Pin the npm dependency

- [ ] 1.1 Read current resolved version from
      `generator/package-lock.json` (presently `0.0.24`)
- [ ] 1.2 Replace `"@atproto/lex": "*"` with the exact version in
      `generator/package.json`
- [ ] 1.3 Run `npm ci` locally in `generator/` to confirm
      lockfile is still consistent
- [ ] 1.4 Verify `./gradlew :generator:generateModels` still
      succeeds with the tightened pin

## 2. Scheduled workflow

- [ ] 2.1 Create `.github/workflows/lexicon-bump.yaml` with triggers
      `schedule: cron "0 14 * * 1"` and `workflow_dispatch: {}`
- [ ] 2.2 Add job: checkout with `token: ${{ secrets.RELEASE_PAT }}`,
      setup Node 22, setup JDK 17, setup Android SDK, setup Gradle
- [ ] 2.3 Add step: `npm install @atproto/lex@latest --save-exact` in
      `generator/`
- [ ] 2.4 Add step: `npx lex install` (no `--ci`) to refresh lexicon
      corpus and CID pins
- [ ] 2.5 Add step: `./gradlew :generator:generateModels`
      to validate the new corpus compiles
- [ ] 2.6 Add diff-detection step that sets an output
      `changes=true|false` based on whether `package.json`,
      `package-lock.json`, or `lexicons.json` have staged changes
- [ ] 2.7 Add conditional step: when `changes=true`, extract old and
      new `@atproto/lex` versions and build the PR body (Added /
      Removed / Updated CIDs, reviewer checklist, release impact note)
- [ ] 2.8 Add step: `peter-evans/create-pull-request@v7` with stable
      branch `chore/lexicon-bump`, title
      `chore(lexicons): bump @atproto/lex to <version>`, labels
      `lexicon-bump` and `dependencies`
- [ ] 2.9 Verify workflow YAML passes the existing
      `open-turo/actions-jvm/lint` check

## 3. Documentation

- [ ] 3.1 Add a "Lexicon updates" subsection to `CONTRIBUTING.md`
      explaining the automated bump flow and how to trigger a manual
      run via the Actions tab
- [ ] 3.2 Update the module table note in `CONTRIBUTING.md` or
      `README.md` to reference the automated workflow

## 4. Manual verification

- [ ] 4.1 Push the branch and run the workflow via `workflow_dispatch`
      on the feature branch
- [ ] 4.2 Confirm the workflow completes and, if upstream has drifted,
      opens a PR with the expected title and body shape
- [ ] 4.3 If the test PR opens, close it without merging (the real
      bump PR will come from a later scheduled run against `main`)
- [ ] 4.4 Confirm the PR's own CI passes (generator regeneration,
      runtime tests, sample APK build) against whatever lexicon state
      the test run produced

## 5. Archive

- [ ] 5.1 `openspec status --change lexicon-drift-automation` reports
      all artifacts complete
- [ ] 5.2 Archive the change into
      `openspec/changes/archive/<date>-lexicon-drift-automation/` and
      sync the spec delta into `openspec/specs/lexicon-toolchain/`
