# Overlay retirement: adopt on-network lexicons (issue #165)

**Date:** 2026-07-25
**Issue:** [#165 — Overlay staleness: 5 overlay(s) need attention](https://github.com/kikin81/atproto-kotlin/issues/165)
**Status:** revised after review; ready for implementation planning

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

Add five NSIDs to the `lexicons` array in `generator/lexicons.json`:

- `app.bsky.embed.gallery`
- `app.bsky.embed.getEmbedExternalView`
- `app.bsky.embed.recordWithMedia`
- `chat.bsky.convo.getConvoMembers`
- `chat.bsky.embed.joinLink`

**Necessity.** `getEmbedExternalView` and `getConvoMembers` are leaf queries that
nothing refs. Unlisted, they resolve via nothing and disappear entirely.

**Tripwire.** The other three are listed by an explicit rule: *an NSID that is
reachable only as a union member gets an explicit listing; an NSID reachable by
a direct `"ref"` from a listed lexicon does not.*

A direct ref is load-bearing for resolution — if upstream deletes the target,
`lex install` or `RefResolver` fails loudly on its own. A union member is not:
upstream can drop it from the union and every remaining document still resolves,
so the only visible effect is public types silently vanishing from
`models/api/models.api`. That is precisely the breaking change this work exists
to avoid, so union-member-only NSIDs get pinned into `lexicons[]` to convert
that silent deletion into a `lex install` failure.

Applying the rule to the transitive-only NSIDs (verified by grepping the
installed corpus for `"ref": "<nsid>` versus bare union-member occurrences):

| NSID | Reachability | Listed? |
|---|---|---|
| `app.bsky.embed.gallery` | union member only | yes |
| `chat.bsky.embed.joinLink` | union member only (`chat.bsky.convo.defs:205`) | yes |
| `app.bsky.embed.recordWithMedia` | union member only (`embed/record.json:72`, `feed/defs.json:33`) | yes |
| `app.bsky.feed.defs` | direct ref (`graph/defs.json:259`, `embed/record.json:115`) | no |
| `chat.bsky.convo.defs` | direct ref (`getConvo.json:19` and ~8 more) | no |

`app.bsky.embed.recordWithMedia` is included on review. It was originally left
out on the assumption that it patterned with `feed.defs` and `convo.defs`, but
it has no direct `"ref"` anywhere in the corpus — it appears only inside embed
unions, exactly like `gallery` and `joinLink`. Leaving it out would have made
the rule look arbitrary and left a real gap.

`npx lex install` then repins `resolutions`, and a following `npx lex install --ci`
proves the result self-consistent. Both are needed: `--ci` is verify-only and
cannot mint the new pins, so running it alone fails with `Lexicons manifest is
out of date`.

**Order of operations for the two install-time NSIDs:** add to `lexicons.json`,
run `lex install`, diff the fetched copy against the still-present vendored copy,
and only then delete the overlay. If the copies differ, that NSID does not
retire in this change — it is re-vendored from the fetched copy instead, and the
divergence is documented in its manifest `reason`.

### 2a. Close the false-negative: detect redundant overlays

The detector only ever compares a vendored file against
`bluesky-social/atproto@main`. A shadow that matches `main` reads as "in-sync"
even when the network has fully absorbed it and the overlay now contributes
nothing. That blind spot is why six redundant overlays went unreported while
#165 named five, and it is the more dangerous half: a false positive is noise, a
false negative is an NSID silently frozen at a vendored snapshot.

Fixing it does not need a new network fetch. The workflow's probe step
(`.github/workflows/overlay-staleness.yaml:62-100`) already runs the real
`npx lex install` per overlay NSID into `$tmp_dir`, then discards the fetched
document. Keep it and compare:

```bash
# inside the existing probe loop, when publishable=true
fetched="$tmp_dir/$(nsid_to_path "$nsid").json"
if [ -f "$fetched" ] && \
   diff -q <(jq -S 'del(.["$type"])' "$fetched") \
           <(jq -S . "overlay-lexicons/$(nsid_to_path "$nsid").json") >/dev/null
then redundant=true; else redundant=false; fi
```

The `del(.["$type"])` strips the `com.atproto.lexicon.schema` wrapper key that
`lex install` adds and the vendored copies lack. The per-NSID booleans are
exported as `OVERLAY_REDUNDANT_JSON` alongside the existing
`OVERLAY_PUBLISHABLE_JSON`, and `DetectStaleOverlays` reads it into a third
verdict:

- `redundant` → **counted as stale**: `♻️ REDUNDANT — vendored copy is identical
  to the on-network document; retire it.` This fires regardless of
  `removeWhenPublished`, which is what makes it catch pinned shadows.

Absent the env var (local runs), redundancy reads as unknown and is simply not
reported — the same degradation the publishable signal already has locally.

**Caveat to state in the report:** the probe resolves the *latest* on-network
document, whereas the build installs the CID pinned in `lexicons.json`. A
`REDUNDANT` verdict therefore means "redundant against the network as of this
run", which can lead the pins. That is the correct signal for a retirement
decision, but the `models.api` gate in §3 — which runs against the pinned
corpus — remains the thing that actually authorizes a retirement.

### 2b. Teach the detector about intentional supersets

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
cd generator && npx lex install && npx lex install --ci && cd -
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
five added NSIDs, three of which (`gallery`, `joinLink`, `recordWithMedia`)
already have pins and should therefore show only an added `lexicons[]` entry, not
a changed pin. CID churn on any other entry means a pending `lexicon-bump` — see
Risks.

### 4. Rollout

Single PR, two commits so the mechanical retirement stays reviewable
independently of the detector logic:

1. `chore(generator): detect redundant overlays + expected-drift supersets` —
   §2a and §2b, with their tests. Landing this first means the detector can be
   run against the *pre*-retirement tree and should independently name the same
   ten overlays, which is a free cross-check on the manual diffing in Findings.
2. `chore(lexicons): retire 10 overlays adopted on-network (#165)` — §1, plus
   the `expectDrift` manifest entry for `chat.bsky.group.defs`.

`chore` is the accurate type for both — `:generator` is unpublished, and with
`models.api` byte-identical there is no API change to release, so
semantic-release correctly produces no version bump. Closes #165.

## Risks

**The two install-time NSIDs are the only source of a real API change.** Their
network copies are unverified until `lex install` runs. Contingency is per-NSID:
keep and re-vendor that single overlay, and land the other nine retirements.

**`resolutions` repinning may pull unrelated CID updates.** `lex install`
refreshes what it resolves, so adding five NSIDs can surface CID movement on
entries this change never touched.

Do **not** revert those hunks. `resolutions` is a lockfile, and `--ci` is
documented as *"error if the installed lexicons do not match the CIDs in the
lexicons.json manifest"* — a manifest with drift hunks reverted is by definition
out of date, so `lex install --ci` rejects it. That is the exact failure that
held `main` red for 12 days and blocked #170, #174 and two Renovate PRs.

The remedy is to let the `lexicon-bump` workflow land its own PR, then rebase
this branch onto it and re-run `lex install --ci`. Unrelated CID movement is a
signal to wait for that bump, never to hand-edit the lockfile. If the rebase
changes `models.api`, that change belongs to the bump, not to this retirement —
evaluate it there.

## Out of scope

Per-def "patch" overlays — teaching the generator to merge overlay `defs` into
the installed document instead of replacing it. That would let
`chat.bsky.group.defs` auto-track upstream while contributing only
`groupPublicView`, eliminating the expected-drift class entirely. It is a real
generator and golden-file change and does not belong in a cleanup PR.
