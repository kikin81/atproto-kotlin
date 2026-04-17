## Why

A monolithic `LLM_USAGE.md` (PR #8) was drafted to give LLM agents
consuming this library a single-load cheat sheet. Closed in favor of a
**skills-based structure** modeled on
[`github.com/android/skills`](https://github.com/android/skills) because:

1. **Token efficiency.** A single 480-line doc loads in full every time
   an agent needs *any* ATProto knowledge. Skills load only the section
   relevant to the current task.
2. **Triggerable via frontmatter.** Each skill's `description` field
   ("Use this skill when…") acts as a discovery index — agents pattern-
   match the current request against the skill list and fetch only
   matching ones.
3. **Better failure mode.** If the agent fetches the wrong skill, the
   cost is small and the right one is still discoverable. Wrong guesses
   on a monolith mean wasted context either way.
4. **Version isolation.** Bumping only the OAuth flow doesn't churn the
   pagination docs. Each skill has its own `metadata` block.
5. **Reference artifacts.** Skills can carry a `references/` directory
   with supporting material (client metadata templates, snippet files)
   that the agent fetches only when the SKILL.md explicitly points to it.

The use case — a single client app consumed by one author — doesn't
warrant a full plugin-publishing flow. Skills stay in this repo and the
consumer's `CLAUDE.md` points agents to the relevant raw URLs.

## What Changes

- **New top-level `skills/` directory** containing task-oriented skills
  with the `android/skills` layout conventions: YAML frontmatter
  (`name`, `description`, `metadata`), markdown body, optional
  `references/` subdirectory.
- **Initial skill set (5 skills)** covering the coherent tasks an
  agent will encounter in a consuming Android app:
  - `atproto-setup` — Gradle dependencies, module overview, Ktor engine
  - `atproto-oauth` — Client metadata, Android redirect plumbing,
    session store, `AtOAuth` login/logout flow
  - `atproto-read` — `*Service` query pattern, `*PageFlow` pagination,
    open-union dispatch (embeds, reasons, quoted records)
  - `atproto-write-records` — `createRecord` / `deleteRecord` via
    `RepoService`, `encodeRecord()` pattern, common record types
    (`Post`, `Like`, `Follow`, `Repost`)
  - `atproto-types-reference` — `AtField<T>` three-state optionality,
    runtime value classes, cross-cutting pitfalls
- **`skills/README.md`** as a concise index: skill list, one-line hook
  each, pointer to installation instructions for the consumer's
  `CLAUDE.md`.
- **No `LLM_USAGE.md`.** The skills replace it entirely.

## Capabilities

### New Capabilities

- `consumer-skills`: collection of Claude-Code-compatible SKILL.md
  files that guide LLM agents through common ATProto consumer-side
  tasks.

## Impact

- **New directory**: `skills/` with 5 SKILL.md files plus a README and
  references material (client-metadata template, snippet files where
  useful).
- **No production code changes.** Docs-only; runtime, generator, OAuth
  module, and sample app are untouched.
- **Breaking changes**: none.
- **Version drift**: skills are tagged with the documented library
  version in their `metadata` block. A follow-up change can automate
  version bumps against `gradle.properties` if drift becomes painful.
- **Distribution**: no plugin marketplace entry. Agents discover skills
  via a pointer in the consuming repo's `CLAUDE.md` and fetch raw
  SKILL.md URLs on demand.
