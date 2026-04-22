<!--
Thanks for the PR! A few quick things:

- Title should follow Conventional Commits (feat:, fix:, chore:, docs:, …).
  A feat: or fix: title on main cuts a release, so pick deliberately.
- If this closes an issue, reference it below (e.g. "Closes #123").
- Tick the checklist at the bottom before requesting review.
-->

## Summary

<!-- What does this PR do, and why? One short paragraph. -->

## Affected module

- [ ] `runtime`
- [ ] `generator`
- [ ] `models` (generator change — regenerated output included)
- [ ] `oauth`
- [ ] `samples:android`
- [ ] Docs / build / CI

## How it was verified

<!--
List the commands / flows you actually ran. Be specific — "works on my
machine" is not enough. Examples:

- ./gradlew :oauth:test
- ./gradlew :generator:test (golden files updated, diff reviewed)
- Installed sample app, logged in, scrolled 3 pages of timeline
-->

## Breaking changes

<!-- If none, write "none". If yes, describe the break and the migration path. -->

## Checklist

- [ ] Tests added or updated (or: "N/A, docs-only")
- [ ] `./gradlew build` passes locally
- [ ] `./gradlew spotlessCheck` passes locally
- [ ] Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/)
- [ ] For generator changes: golden fixtures regenerated and diff reviewed
- [ ] For public API changes: KDoc added/updated
