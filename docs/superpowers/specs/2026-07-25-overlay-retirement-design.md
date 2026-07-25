# Overlay retirement: adopt on-network lexicons (issue #165)

**Date:** 2026-07-25
**Issue:** [#165 — Overlay staleness: 5 overlay(s) need attention](https://github.com/kikin81/atproto-kotlin/issues/165)
**Status:** approved, ready for implementation planning

## Problem

`generator/overlay-lexicons/` vendors 11 lexicon documents that the AT Protocol
network had not yet published when we needed them. Overlays are merged over the
installed corpus with whole-document, overlay-wins semantics
(`generator/src/main/kotlin/io/github/kikin81/atproto/generator/Main.kt:56-64`),
so every overlay permanently masks whatever the network later publishes for that
NSID. A stale overlay is not inert — it silently freezes an NSID.

The weekly `overlay-staleness` workflow filed #165 naming five overlays. Direct
inspection of the installed corpus shows the issue both under- and over-reports.

## Findings

Measured by canonicalizing (`json.dumps(..., sort_keys=True)`) each vendored
file against its counterpart in the installed corpus at `generator/lexicons/`,
ignoring the `$type: com.atproto.lexicon.schema` wrapper key that
`lex install` adds.

### Byte-identical to the installed on-network copy — retiring is a no-op

| NSID | Flagged by #165? |
|---|---|
| `app.bsky.embed.gallery` | yes |
| `chat.bsky.embed.joinLink` | yes |
| `app.bsky.feed.post` | no |
| `app.bsky.feed.defs` | no |
| `app.bsky.embed.recordWithMedia` | no |
| `chat.bsky.convo.defs` | no |
| `chat.bsky.convo.sendMessage` | no |
| `chat.bsky.convo.sendMessageBatch` | no |

The six unflagged entries are the `removeWhenPublished: false` "pinned shadow"
overlays. Their manifest entries say to retire them manually once the network
copy absorbs the local additions (gallery in the embed unions; `replyTo` message
replies in the chat defs). That condition is now met for all six. The staleness
detector cannot see this because it only compares vendored files against
`bluesky-social/atproto@main`, never against the installed corpus — a shadow can
match `main` exactly, read as "in-sync", and still be entirely redundant.

`app.bsky.embed.gallery`, `chat.bsky.embed.joinLink`, `app.bsky.feed.defs`,
`app.bsky.embed.recordWithMedia` and `chat.bsky.convo.defs` currently resolve
transitively (they appear in `lexicons.json` `resolutions` but not in the
`lexicons` array).

### Not yet in the corpus — verified at install time

`app.bsky.embed.getEmbedExternalView` and `chat.bsky.convo.getConvoMembers` are
leaf queries that nothing refs, so nothing pulls them in transitively. #165's
probe reports both as resolvable on-network, but we have not compared the
network copy to the vendored copy. That comparison happens during
implementation, before deletion.

### Must stay: `chat.bsky.group.defs`

The on-network copy has caught up on the join-link preview defs
(`joinLinkPreviewView`, `disabledJoinLinkPreviewView`,
`invalidJoinLinkPreviewView`, `joinLinkViewerState`, `joinRequestConvoView`), so
that half of the overlay's purpose is satisfied. It still omits
`groupPublicView`, which upstream `main` deleted alongside
`chat.bsky.group.getGroupPublicInfo` — but `getGroupPublicInfo` is still
published on-network, is explicitly listed in `generator/lexicons.json`, and
refs `chat.bsky.group.defs#groupPublicView`
(`generator/lexicons/chat/bsky/group/getGroupPublicInfo.json:19`).

Retiring or re-vendoring this overlay from `main` — the two remedies #165
offers — would remove a def that a listed lexicon references, breaking ref
resolution and dropping 14 `GroupPublicView` symbols from `models/api/models.api`.
Both of the issue's suggested actions are wrong for this entry.

Because the overlay is a deliberate superset, it reads as `DRIFTED` on every
run and will refile this issue indefinitely.

## Design

### 1. Retire ten overlays

Remove the ten manifest entries from `generator/overlay-lexicons.json` and
delete their JSON files. Prune the directories that this empties
(`overlay-lexicons/app/**`, `overlay-lexicons/chat/bsky/convo`,
`overlay-lexicons/chat/bsky/embed`); only
`overlay-lexicons/chat/bsky/group/defs.json` remains.

Add all four formerly-overlay-only NSIDs to the `lexicons` array in
`generator/lexicons.json`:

- `app.bsky.embed.gallery`
- `app.bsky.embed.getEmbedExternalView`
- `chat.bsky.convo.getConvoMembers`
- `chat.bsky.embed.joinLink`

`getEmbedExternalView` and `getConvoMembers` must be listed or they disappear
entirely. `gallery` and `joinLink` are listed as a tripwire rather than a
necessity: they currently arrive only through the `app.bsky.feed.post` /
`app.bsky.embed.recordWithMedia` / `app.bsky.feed.defs` / `chat.bsky.convo.defs`
embed unions. Listing them explicitly turns a future upstream removal from those
unions into a loud `lex install` failure instead of a silent deletion of public
types — the exact breaking change this work exists to avoid.

`npx lex install --ci` then repins `resolutions`.

**Order of operations for the two install-time NSIDs:** add to `lexicons.json`,
run `lex install`, diff the fetched copy against the still-present vendored copy,
and only then delete the overlay. If the copies differ, that NSID does not
retire in this change — it is re-vendored from the fetched copy instead, and the
divergence is documented in its manifest `reason`.

### 2. Teach the detector about intentional supersets

`OverlayEntry` in
`generator/src/main/kotlin/io/github/kikin81/atproto/generator/tools/DetectStaleOverlays.kt`
gains two optional fields:

```json
{
  "nsid": "chat.bsky.group.defs",
  "removeWhenPublished": false,
  "expectDrift": true,
  "driftReason": "Superset: re-adds groupPublicView, which upstream main deleted but which is still published on-network and reffed by chat.bsky.group.getGroupPublicInfo."
}
```

Detector behaviour:

- `expectDrift && drifted` → reported as `ℹ️ SUPERSET (expected drift)` with the
  `driftReason`, and **excluded** from `stale_count` / `has_stale`. The weekly
  workflow stops filing issues for it.
- `expectDrift && inSync` → **counted as stale**, with a new verdict: upstream
  has absorbed the local addition, so drop `expectDrift` and retire the overlay.

The second branch is what keeps this from being a permanent mute. The flag
suppresses the noise while converting the same entry into an actionable signal
the moment the situation it describes ends.

The `chat.bsky.group.defs` manifest `reason` is rewritten to drop the
now-satisfied join-link justification, leaving `groupPublicView` as the sole
stated reason.

`DetectStaleOverlaysTest` gains coverage for both branches.

### 3. No-breaking-change gate

`models/api/models.api` is committed and enforced by
kotlinx binary-compatibility-validator, so the proof is mechanical rather than
argued:

```bash
cd generator && npx lex install --ci && cd -
./gradlew :generator:generateModels apiDump
git diff --exit-code models/api/models.api   # must be empty
```

A non-empty diff means an overlay was load-bearing after all. The response is to
identify the responsible NSID and keep that overlay — not to accept the diff.

Then:

```bash
./gradlew :generator:test :models:jvmTest :runtime:jvmTest
```

The full `./gradlew build` is skipped deliberately: iOS simulator test-linking
fails on a Command Line Tools-only machine without a full Xcode install.

`EmbedGalleryUnionTest` passing is the direct evidence that the gallery union
types survive their overlay's retirement.

Expected diff in `generator/lexicons.json`: new `resolutions` CID pins for the
four added NSIDs. CID churn on any other entry is out of scope for this change
and gets investigated before merging.

### 4. Rollout

Single PR, conventional commit `chore(lexicons): retire 10 overlays adopted
on-network (#165)`. `chore` is the accurate type — with `models.api`
byte-identical there is no API change to release, and semantic-release
correctly produces no version bump. Closes #165.

## Risks

**The two install-time NSIDs are the only source of a real API change.** Their
network copies are unverified until `lex install` runs. Contingency is per-NSID:
keep and re-vendor that single overlay, and land the other nine retirements.

**`resolutions` repinning may pull unrelated CID updates.** `lex install`
refreshes what it resolves. If unrelated pins move, that is drift belonging to
the `lexicon-bump` workflow, not to this change; revert those hunks and let the
dedicated workflow handle them.

## Out of scope

Per-def "patch" overlays — teaching the generator to merge overlay `defs` into
the installed document instead of replacing it. That would let
`chat.bsky.group.defs` auto-track upstream while contributing only
`groupPublicView`, eliminating the expected-drift class entirely. It is a real
generator and golden-file change and does not belong in a cleanup PR.
