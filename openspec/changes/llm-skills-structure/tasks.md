## 1. Scaffolding

- [ ] 1.1 Create `skills/` directory with `README.md` stub
- [ ] 1.2 Create subdirectories for each of the five initial skills:
      `atproto-setup/`, `atproto-oauth/`, `atproto-read/`,
      `atproto-write-records/`, `atproto-types-reference/`

## 2. Write SKILL.md: atproto-setup

- [ ] 2.1 Draft frontmatter (`name`, `description`, `metadata` with
      `library-version: "4.6.0"` and keywords)
- [ ] 2.2 Body: Maven Central coordinates, three ATProto dependencies,
      Ktor engine selection, minimum JDK/Android SDK, short module
      table (runtime = hand-written, models = generated, oauth =
      JVM-only)
- [ ] 2.3 Common pitfalls: forgetting an engine, mixing versions
- [ ] 2.4 Related skills backlink to `atproto-oauth`

## 3. Write SKILL.md: atproto-oauth

- [ ] 3.1 Draft frontmatter (OAuth / DPoP / Android keywords)
- [ ] 3.2 Body sections: client-metadata JSON, AndroidManifest intent
      filter, `OAuthSessionStore` with `EncryptedSharedPreferences`,
      `AtOAuth` DI, `beginLogin` → Custom Tabs → `completeLogin` →
      `createClient`, `logout`, session restore on app start
- [ ] 3.3 Add `references/client-metadata-template.json` with a
      ready-to-host JSON
- [ ] 3.4 Add `references/android-redirect-capture.kt` showing
      `onNewIntent` + `singleTask` wiring
- [ ] 3.5 Common pitfalls: single-slash redirect URI, `singleTask` flag,
      not using EncryptedSharedPreferences
- [ ] 3.6 Related skills backlink to `atproto-read` and
      `atproto-write-records`

## 4. Write SKILL.md: atproto-read

- [ ] 4.1 Draft frontmatter (queries / pagination / unions keywords)
- [ ] 4.2 Body subsection 1: `*Service` query pattern
      (`FeedService(client).getTimeline(...)`)
- [ ] 4.3 Body subsection 2: `*PageFlow()` vs `*Flow()`, ViewModel
      pattern with `Channel` for load-more gating
- [ ] 4.4 Body subsection 3: open-union dispatch (`ReasonRepost`,
      `ImagesView`, `RecordView`, `RecordWithMediaView`, `*Unknown`
      arms), `decodeRecord<T>()` for typed records
- [ ] 4.5 Add `references/open-union-arms.md` with a compact table of
      every open union and its known arms
- [ ] 4.6 Add `references/pagination-ui-pattern.kt` with a complete
      ViewModel pagination snippet
- [ ] 4.7 Common pitfalls: using `*Flow()` for UI, not handling
      `*Unknown`, assuming `Paging 3` integration
- [ ] 4.8 Monitor skill size; split into three skills if it exceeds
      ~250 lines

## 5. Write SKILL.md: atproto-write-records

- [ ] 5.1 Draft frontmatter (records / createRecord / encodeRecord
      keywords)
- [ ] 5.2 Body: `createRecord` via `RepoService`, `encodeRecord()`
      pattern, rkey extraction from AtUri, `deleteRecord`
- [ ] 5.3 Canonical snippets for `Post`, `Like`, `Repost`, `Follow`,
      `Block`
- [ ] 5.4 Add `references/common-records.md` with a record-type
      reference table (collection NSID → record class → common fields)
- [ ] 5.5 Common pitfalls: forgetting `$type`, instantiating with
      positional args, mixing `putRecord` with `createRecord`
- [ ] 5.6 Related skills backlink to `atproto-types-reference` for
      `AtField` on mutations

## 6. Write SKILL.md: atproto-types-reference

- [ ] 6.1 Draft frontmatter (types / AtField / value classes keywords)
- [ ] 6.2 Body section 1: `AtField<T>` three-state semantics with
      mutation example; why `explicitNulls=false` breaks it
- [ ] 6.3 Body section 2: value-classes table (`Did`, `Handle`,
      `AtUri`, `Cid`, `Datetime`, `Nsid`, `RecordKey`, `AtIdentifier`)
      with wire-shape and `.raw` access pattern
- [ ] 6.4 Body section 3: cross-cutting pitfalls list (don't edit
      generated sources, don't reuse client across logout/login, etc.)

## 7. Write skills/README.md

- [ ] 7.1 One-line description per skill with in-repo links
- [ ] 7.2 Consumer-`CLAUDE.md` installation snippet with raw GitHub
      URLs to each SKILL.md
- [ ] 7.3 Note on how to detect `library-version` drift against
      `gradle.properties`
- [ ] 7.4 Link back to repo README and Dokka API reference as
      human-oriented resources

## 8. Review and ship

- [ ] 8.1 Each SKILL.md parses as valid YAML frontmatter + markdown
      (manual eyeball)
- [ ] 8.2 Each SKILL.md stays under ~250 lines (push overflow to
      `references/`)
- [ ] 8.3 Dry-run: paste consumer installation snippet into a scratch
      project's `CLAUDE.md`, ask Claude to set up OAuth, verify it
      fetches `atproto-oauth/SKILL.md` and nothing else
- [ ] 8.4 Delete the `docs/llm-usage-guide` local branch once skills
      ship — it's been superseded

## 9. Archive

- [ ] 9.1 `openspec status --change llm-skills-structure` reports 4/4
      artifacts complete
- [ ] 9.2 Archive change to
      `openspec/changes/archive/<date>-llm-skills-structure/` and
      sync the delta into `openspec/specs/consumer-skills/`
