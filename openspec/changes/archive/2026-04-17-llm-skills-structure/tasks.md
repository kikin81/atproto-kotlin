## 1. Scaffolding

- [x] 1.1 Create `skills/` directory with `README.md` stub
- [x] 1.2 Create subdirectories for each of the five initial skills:
      `atproto-setup/`, `atproto-oauth/`, `atproto-read/`,
      `atproto-write-records/`, `atproto-types-reference/`

## 2. Write SKILL.md: atproto-setup

- [x] 2.1 Draft frontmatter (`name`, `description`, `metadata` with
      `library-version: "4.6.0"` and keywords)
- [x] 2.2 Body: Maven Central coordinates, three ATProto dependencies,
      Ktor engine selection, minimum JDK/Android SDK, short module
      table (runtime = hand-written, models = generated, oauth =
      JVM-only)
- [x] 2.3 Common pitfalls: forgetting an engine, mixing versions
- [x] 2.4 Related skills backlink to `atproto-oauth`

## 3. Write SKILL.md: atproto-oauth

- [x] 3.1 Draft frontmatter (OAuth / DPoP / Android keywords)
- [x] 3.2 Body sections: client-metadata JSON, AndroidManifest intent
      filter, `OAuthSessionStore` with `EncryptedSharedPreferences`,
      `AtOAuth` DI, `beginLogin` → Custom Tabs → `completeLogin` →
      `createClient`, `logout`, session restore on app start
- [x] 3.3 Add `references/client-metadata-template.json` with a
      ready-to-host JSON
- [x] 3.4 Add `references/android-redirect-capture.kt` showing
      `onNewIntent` + `singleTask` wiring
- [x] 3.5 Common pitfalls: single-slash redirect URI, `singleTask` flag,
      not using EncryptedSharedPreferences
- [x] 3.6 Related skills backlink to `atproto-read` and
      `atproto-write-records`

## 4. Write SKILL.md: atproto-read

- [x] 4.1 Draft frontmatter (queries / pagination / unions keywords)
- [x] 4.2 Body subsection 1: `*Service` query pattern
      (`FeedService(client).getTimeline(...)`)
- [x] 4.3 Body subsection 2: `*PageFlow()` vs `*Flow()`, ViewModel
      pattern with `Channel` for load-more gating
- [x] 4.4 Body subsection 3: open-union dispatch (`ReasonRepost`,
      `ImagesView`, `RecordView`, `RecordWithMediaView`, `*Unknown`
      arms), `decodeRecord<T>()` for typed records
- [x] 4.5 Add `references/open-union-arms.md` with a compact table of
      every open union and its known arms
- [x] 4.6 Add `references/pagination-ui-pattern.kt` with a complete
      ViewModel pagination snippet
- [x] 4.7 Common pitfalls: using `*Flow()` for UI, not handling
      `*Unknown`, assuming `Paging 3` integration
- [x] 4.8 Monitor skill size; split into three skills if it exceeds
      ~250 lines — landed at 251 lines, kept whole because rendering
      a timeline naturally touches queries + pagination + unions in
      one task. Revisit if the skill grows further.

## 5. Write SKILL.md: atproto-write-records

- [x] 5.1 Draft frontmatter (records / createRecord / encodeRecord
      keywords)
- [x] 5.2 Body: `createRecord` via `RepoService`, `encodeRecord()`
      pattern, rkey extraction from AtUri, `deleteRecord`
- [x] 5.3 Canonical snippets for `Post`, `Like`, `Repost`, `Follow`,
      `Block`
- [x] 5.4 Add `references/common-records.md` with a record-type
      reference table (collection NSID → record class → common fields)
- [x] 5.5 Common pitfalls: forgetting `$type`, instantiating with
      positional args, mixing `putRecord` with `createRecord`
- [x] 5.6 Related skills backlink to `atproto-types-reference` for
      `AtField` on mutations

## 6. Write SKILL.md: atproto-types-reference

- [x] 6.1 Draft frontmatter (types / AtField / value classes keywords)
- [x] 6.2 Body section 1: `AtField<T>` three-state semantics with
      mutation example; why `explicitNulls=false` breaks it
- [x] 6.3 Body section 2: value-classes table (`Did`, `Handle`,
      `AtUri`, `Cid`, `Datetime`, `Nsid`, `RecordKey`, `AtIdentifier`)
      with wire-shape and `.raw` access pattern
- [x] 6.4 Body section 3: cross-cutting pitfalls list (don't edit
      generated sources, don't reuse client across logout/login, etc.)

## 7. Write skills/README.md

- [x] 7.1 One-line description per skill with in-repo links
- [x] 7.2 Consumer-`CLAUDE.md` installation snippet with raw GitHub
      URLs to each SKILL.md
- [x] 7.3 Note on how to detect `library-version` drift against
      `gradle.properties`
- [x] 7.4 Link back to repo README and Dokka API reference as
      human-oriented resources

## 8. Review and ship

- [x] 8.1 Each SKILL.md parses as valid YAML frontmatter + markdown
      (manual eyeball) — pre-commit YAML check passed
- [x] 8.2 Each SKILL.md stays under ~250 lines (push overflow to
      `references/`) — three over the soft target (oauth 310,
      read 251, write-records 268); decision documented in PR #10
      body, each stays coherent as a single task boundary
- [ ] 8.3 Dry-run: paste consumer installation snippet into a scratch
      project's `CLAUDE.md`, ask Claude to set up OAuth, verify it
      fetches `atproto-oauth/SKILL.md` and nothing else —
      **deferred to consumer-app bring-up**: the target client app
      doesn't exist yet, so this is the first validation to run when
      scaffolding it
- [x] 8.4 Delete the `docs/llm-usage-guide` local branch once skills
      ship — it's been superseded (local + remote deleted)

## 9. Archive

- [x] 9.1 `openspec status --change llm-skills-structure` reports 4/4
      artifacts complete
- [x] 9.2 Archive change to
      `openspec/changes/archive/<date>-llm-skills-structure/` and
      sync the delta into `openspec/specs/consumer-skills/`
