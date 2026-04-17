## Overview

Create five task-oriented skills under `skills/`, modeled on
`github.com/android/skills`. Each skill is a self-contained SKILL.md
with YAML frontmatter (for agent discovery) and a markdown body
(for agent consumption). A top-level `skills/README.md` serves as a
thin index for humans browsing the repo and agents looking up what's
available.

## Directory layout

```
skills/
├── README.md                         # Index + install guidance
├── atproto-setup/
│   └── SKILL.md
├── atproto-oauth/
│   ├── SKILL.md
│   └── references/
│       ├── client-metadata-template.json
│       └── android-redirect-capture.kt
├── atproto-read/
│   ├── SKILL.md
│   └── references/
│       ├── open-union-arms.md
│       └── pagination-ui-pattern.kt
├── atproto-write-records/
│   ├── SKILL.md
│   └── references/
│       └── common-records.md
└── atproto-types-reference/
    └── SKILL.md
```

Flat (no category subdirectories) because we only have 5 skills and
all belong to one capability. `android/skills` uses categories because
it covers the whole Android platform — we don't need that hierarchy.

## Skill frontmatter format

Matches `android/skills` conventions verbatim so any tooling they build
works against our files too:

```yaml
---
name: atproto-oauth
description: >
  Use this skill when integrating AT Protocol OAuth 2.0 login into an
  Android app using the kikin81/atproto-kotlin library. Covers hosting
  the client-metadata JSON, configuring the Android intent filter for
  the redirect URI, implementing an encrypted OAuthSessionStore, and
  driving the beginLogin / completeLogin flow.
license: MIT (see repo LICENSE)
metadata:
  author: kikin81
  library-version: "4.6.0"
  keywords:
    - AT Protocol
    - Bluesky
    - OAuth
    - DPoP
    - Android
    - authentication
---
```

Key rules:
- **`description` is what agents pattern-match on.** Start with
  "Use this skill when…" so the trigger phrasing is explicit.
- **`library-version`** in metadata so agents and humans can detect
  drift against current `gradle.properties`.
- **`keywords`** supplement the description for fuzzy matching.

## Skill content conventions

Each SKILL.md body:
1. **Objective** — one sentence, what task this skill completes
2. **Prerequisites** — what agents should assume already exists
3. **Step-by-step** — numbered, copy-pasteable code blocks
4. **Common pitfalls** — `## Common pitfalls` subsection, bulleted
5. **Related skills** — backlinks to other SKILL.md files when a task
   naturally chains into another (e.g. `atproto-oauth` → `atproto-read`)

Target size: 100–250 lines per SKILL.md. Push overflow into
`references/*` files that the SKILL.md explicitly links.

## Skill scopes and boundaries

### atproto-setup

Scope: first-install Gradle wiring. Repositories, three ATProto
dependencies + Ktor engine, minimum SDK / JDK, module overview
(runtime = hand-written, models = generated, oauth = JVM-only).

Out of scope: any actual API calls.

### atproto-oauth

Scope: the full OAuth pipeline for an Android consumer.
- Hosting `client-metadata.json` (references template)
- AndroidManifest intent filter for the redirect URI
- `AndroidOAuthSessionStore` with `EncryptedSharedPreferences`
- `AtOAuth` DI/instantiation
- `beginLogin` → Custom Tabs → `completeLogin` → `createClient`
- `logout`

Out of scope: non-Android OAuth, non-OAuth authentication.

### atproto-read

Scope: reading from the AT Protocol. Three coherent subsections:
1. Calling a `*Service` query (`FeedService(client).getTimeline(...)`)
2. Paginating with `*PageFlow()` + UI suspension pattern
3. Handling open unions — feed reason, post embeds, quoted records,
   `*Unknown` arms, `decodeRecord<T>()` for typed records

This is the largest skill. If it grows past ~250 lines, split by
subsection into `atproto-read-queries`, `atproto-read-pagination`,
`atproto-read-unions`. Not splitting preemptively — artificial
boundaries hurt the "how do I render a timeline" query which touches
all three.

### atproto-write-records

Scope: mutations via `RepoService`.
- `createRecord` pattern with `encodeRecord(serializer, value, type)`
- `deleteRecord` pattern with rkey extraction from AtUri
- Canonical recipes for `Post`, `Like`, `Repost`, `Follow`, `Block`
- Datetime formatting for `createdAt`

Out of scope: `putRecord` (rare in practice), mutation batching.

### atproto-types-reference

Scope: cross-cutting type semantics that every other skill assumes.
- `AtField<T>` three-state optionality (`Missing` / `Null` / `Defined`)
- Runtime value classes (`Did`, `Handle`, `AtUri`, `Cid`, `Datetime`,
  `Nsid`, `RecordKey`, `AtIdentifier`) with a table of each one's
  wire shape and when to use it
- The "don't do this" pitfalls that span multiple skills
  (no raw strings, no `explicitNulls=false`, no direct `XrpcClient`
  calls for ATProto, etc.)

This is the skill agents load once and remember rules from. The other
skills link back to specific sections here.

## skills/README.md

Concise index:
- One-line description per skill
- Installation snippet the consumer pastes into their repo's
  `CLAUDE.md` — specifically, a block pointing to the raw GitHub URLs
  of each SKILL.md so the agent can `WebFetch` on demand
- Note about using `gh api` or direct clone for bulk installation

Example installation snippet (goes in consumer's `CLAUDE.md`):

```markdown
## atproto-kotlin skills

When working on ATProto features, reference these skills as needed:

- Setup (Gradle deps): https://raw.githubusercontent.com/kikin81/atproto-kotlin/main/skills/atproto-setup/SKILL.md
- OAuth login flow: https://raw.githubusercontent.com/kikin81/atproto-kotlin/main/skills/atproto-oauth/SKILL.md
- Reading feeds + pagination + embeds: https://raw.githubusercontent.com/kikin81/atproto-kotlin/main/skills/atproto-read/SKILL.md
- Posting, liking, deleting: https://raw.githubusercontent.com/kikin81/atproto-kotlin/main/skills/atproto-write-records/SKILL.md
- Type reference (AtField, value classes): https://raw.githubusercontent.com/kikin81/atproto-kotlin/main/skills/atproto-types-reference/SKILL.md

Fetch the one that matches the task — not all of them.
```

## Source material

The 5 skills can be carved from the closed PR #8 (`docs/llm-usage-guide`
branch, `LLM_USAGE.md`). The branch is retained as reference. Each
skill's body is 60–80% content already written there, reshaped for
the SKILL.md structure and trimmed for focus.

## Versioning and drift

`metadata.library-version` in each SKILL.md documents which library
version the snippets were validated against. A future change can
automate this via a pre-commit hook that reads `gradle.properties` and
updates every SKILL.md's `library-version` field.

For this change: set `library-version: "4.6.0"` (current release) in
every skill and move on. Drift management is out of scope.

## Non-goals

- **Plugin publishing.** Not a separate npm package, not an
  installable Claude Code plugin. Agents fetch raw URLs.
- **Dokka replacement.** Dokka still exists for human API reference.
  Skills are for task-oriented agent use.
- **Automated version-bump hook.** Manual for now. Defer to a
  follow-up change once we see how often versions drift.
- **Sample-app-specific skills.** The `:samples:android` app is
  referenced as prior art but isn't documented as a skill — consumers
  build their own UI.
- **CI validation that SKILL.md examples compile.** Would be nice —
  tests-as-skills — but way out of scope here.

## Testing

- **Manual smoke test:** paste the installation block into a scratch
  `CLAUDE.md` in a throwaway Android project, ask Claude to "set up
  OAuth with Bluesky," verify it fetches `atproto-oauth/SKILL.md` and
  the snippets compile against the published artifacts.
- **Structural lint:** each SKILL.md must parse as valid YAML front-
  matter + markdown (no custom validator — just eyeball during review).
- **No build CI for this change.** Docs-only.
