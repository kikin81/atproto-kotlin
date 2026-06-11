# chat.bsky.group.* service support — design

_Date: 2026-06-11 · Branch: `feat/chat-bsky-group`_

## Goal

Opt the SDK into the 13 **published** `chat.bsky.group` lexicons so consumers can
create and manage group chats (Bluesky's "group chats for up to 50 users").
`createGroup` caps `members` at 49 DIDs; with the creator that is 50 participants.

## Why now

Unlike `app.bsky.embed.gallery` (merged to GitHub source but **not** published to
the AT Protocol network — `RecordNotFound`, unresolvable by `@atproto/lex`), the
group-chat lexicons are **published and proof-verifiable** on the network under the
lexicon authority `did:plc:4v4y5r3lwsbtmsxhile2ljac`. They can be resolved and
CID-pinned today. The SDK currently tracks none of the 17 `chat.bsky.group.*`
operations (only `chat/bsky/group/defs.json`, pulled in transitively by
`chat.bsky.convo.*`).

## Scope

In scope — the 13 published operations:

| NSID | Kind | Input / Params | Output |
|------|------|----------------|--------|
| `createGroup` | procedure | `members` (≤49 DIDs), `name` | `convo` |
| `addMembers` | procedure | `convoId`, `members` | `addedMembers`, `convo` |
| `removeMembers` | procedure | `convoId`, `members` | `convo` |
| `editGroup` | procedure | `convoId`, `name` | `convo` |
| `requestJoin` | procedure | `code` | `convo`, `status` |
| `approveJoinRequest` | procedure | `convoId`, `member` | `convo` |
| `rejectJoinRequest` | procedure | `convoId`, `member` | — |
| `createJoinLink` | procedure | `convoId`, `joinRule`, `requireApproval` | `joinLink` |
| `editJoinLink` | procedure | `convoId`, `joinRule`, `requireApproval` | `joinLink` |
| `enableJoinLink` | procedure | `convoId` | `joinLink` |
| `disableJoinLink` | procedure | `convoId` | `joinLink` |
| `listJoinRequests` | query | `convoId`, `cursor`, `limit` | `cursor`, `requests` |
| `getGroupPublicInfo` | query | `code` | `group` |

Out of scope — 4 GitHub-only, unpublished (`RecordNotFound`, cannot pin):
`getJoinLinkPreviews`, `listMutualGroups`, `updateJoinRequestsRead`,
`withdrawJoinRequest`. Tracked for a follow-up when published.

## Approach

No generator code changes expected; this is a corpus opt-in + regeneration.

1. **Manifest.** Add the 13 NSIDs to the `lexicons` array in
   `generator/lexicons.json`.
2. **Resolve + pin.** `npx lex install <nsid>` for each (from `generator/`),
   writing real CIDs into `resolutions` and the JSON into
   `generator/lexicons/chat/bsky/group/`.
3. **Regenerate.** `./gradlew :generator:generateModels` emits `GroupService`
   (13 methods) + request/response models under
   `models/build/generated/source/lexicon/.../chat/bsky/group/`. `chat.bsky.*`
   auto-inherits the `did:web:api.bsky.chat#bsky_chat` proxy via existing
   `ProxyMapping` — verify, do not modify.

## Tests

- **Models-level** `GroupServiceTest.kt` (new, `models/src/commonTest/.../chat/bsky/group/`)
  using `MockXrpcFixture`, one test per generated method. Asserts: HTTP verb
  (POST for procedures, GET for queries), XRPC path `/xrpc/chat.bsky.group.<m>`,
  the proxy is applied, request serialization (notably `createGroup` with a
  49-member `List<Did>`), and response deserialization. Mirrors `FeedServiceTest`.
- **Generator golden** one case: add `chat.bsky.group.createGroup.json` to
  `generator/src/test/resources/golden/lexicons/`, regenerate with
  `GOLDEN_UPDATE=1`, commit the new golden Kotlin. Locks output + proxy wiring
  for the new namespace and the `maxLength`-bounded-array procedure shape.

## Verification

`./gradlew spotlessApply build` — compiles generated KMP (JVM + iOS), runs all
module tests plus the golden regression. Commit as
`feat(models): add chat.bsky.group.* lexicons for group chats`; semantic-release
will minor-bump.

## Risks / notes

- A full `lex install` of these 13 may pull transitive defs already present
  (`chat.bsky.group.defs`, `chat.bsky.convo.defs`); expected to be no-ops or
  additive.
- If the generator emits anything non-additive elsewhere (it should not — these
  are a new isolated namespace), pause and review before committing.
