# atproto-kotlin skills

Task-oriented skills for LLM agents consuming the
[`kikin81/atproto-kotlin`](https://github.com/kikin81/atproto-kotlin)
library from a downstream Kotlin or Android project. Structure modeled
on [`github.com/android/skills`](https://github.com/android/skills).

Each skill is a self-contained `SKILL.md` with YAML frontmatter that
agents pattern-match against the current task. Fetch only the skill
that matches what you're doing — skills are designed to stand alone.

**Documented library version:** `4.6.0`

## Skills

| Skill | Use when |
|---|---|
| [`atproto-setup`](./atproto-setup/SKILL.md) | Wiring Gradle dependencies, picking a Ktor engine, understanding the three published artifacts. Run first. |
| [`atproto-oauth`](./atproto-oauth/SKILL.md) | Integrating OAuth 2.0 login for Bluesky into an Android app: client metadata, intent filter, session store, `AtOAuth` flow. |
| [`atproto-read`](./atproto-read/SKILL.md) | Calling XRPC queries via `*Service` classes, paginating with `*PageFlow()`, dispatching over open unions (embeds, reasons, quoted records). |
| [`atproto-write-records`](./atproto-write-records/SKILL.md) | Creating or deleting posts, likes, reposts, follows, blocks via `RepoService.createRecord` / `deleteRecord` + `encodeRecord()`. |
| [`atproto-types-reference`](./atproto-types-reference/SKILL.md) | Cross-cutting rules: `AtField<T>` three-state semantics, runtime value classes (`Did`, `Handle`, `AtUri`, …), pitfalls that span skills. Keep loaded alongside task-specific skills. |

## Installation: paste into your consumer repo's `CLAUDE.md`

Copy this block into the `CLAUDE.md` of the downstream app that
consumes the library. Claude agents working in that repo will fetch
only the skill that matches the current task instead of reading the
whole library.

```markdown
## atproto-kotlin

This app consumes `io.github.kikin81.atproto:{runtime,models,oauth}:4.6.0`.

Before implementing any ATProto feature, fetch the relevant skill from
the library repository. Do not load all of them — pick the one matching
the current task.

- Setup (Gradle deps, Ktor engine): https://raw.githubusercontent.com/kikin81/atproto-kotlin/main/skills/atproto-setup/SKILL.md
- OAuth login flow (Android): https://raw.githubusercontent.com/kikin81/atproto-kotlin/main/skills/atproto-oauth/SKILL.md
- Reading feeds, pagination, embeds: https://raw.githubusercontent.com/kikin81/atproto-kotlin/main/skills/atproto-read/SKILL.md
- Posting, liking, following, deleting: https://raw.githubusercontent.com/kikin81/atproto-kotlin/main/skills/atproto-write-records/SKILL.md
- Cross-cutting type rules (AtField, value classes, pitfalls): https://raw.githubusercontent.com/kikin81/atproto-kotlin/main/skills/atproto-types-reference/SKILL.md

Some skills have a `references/` directory with supplementary material
(client-metadata templates, snippet files, open-union arm tables). Fetch
those only when the SKILL.md explicitly links to them.
```

## Version drift

Each `SKILL.md`'s frontmatter contains a `metadata.library-version`
field pinned to the version the snippets were validated against
(currently `4.6.0`). If a consumer is on a newer library version, the
skills may need updating — open an issue at
https://github.com/kikin81/atproto-kotlin/issues.

To detect drift in your own project, compare your `gradle.properties`
(or wherever you pin the atproto-kotlin version) against the
`library-version` at the top of each SKILL.md.

## For humans

Humans browsing the library still have the
[Dokka API reference](https://kikin81.github.io/atproto-kotlin/api/)
and the [sample Android app](../samples/android/README.md). Skills are
the agent-oriented surface; Dokka is the symbol-oriented one.

## Contributing

New skills or edits are welcome. Keep each `SKILL.md` under ~250 lines
— push overflow into `references/*` files that the SKILL.md links.
Frontmatter must include `name`, `description` (starting with "Use this
skill when…"), and a `metadata` block with `library-version` and
`keywords`.
