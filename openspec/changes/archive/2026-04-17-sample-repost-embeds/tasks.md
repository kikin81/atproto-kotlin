## 1. Repost attribution header

- [x] 1.1 Thread `FeedViewPost` (not just `PostView`) into `PostRow` so
      the row can see `entry.reason`
- [x] 1.2 Add a `RepostHeader` composable that pattern-matches
      `entry.reason as? ReasonRepost` and renders
      "Reposted by @${reason.by.handle.raw}" with a small repeat icon
- [x] 1.3 Render the header above the existing author/timestamp row,
      only when the cast succeeds (ReasonPin / Unknown / null → no header)

## 2. Quoted record extraction helpers

- [x] 2.1 Add `extractQuotedRecord(post: PostView): RecordViewRecord?`
      that unwraps `RecordView.record` and `RecordWithMediaView.record.record`,
      returning null for the NotFound / Blocked / Detached / Unknown arms
- [x] 2.2 Add `extractQuotedPlaceholder(post: PostView): String?`
      returning a localized placeholder for each non-record arm
- [x] 2.3 Extend `extractFirstImageThumb` to also accept
      `RecordWithMediaView` by inspecting `embed.media as? ImagesView`

## 3. Quoted record card composable

- [x] 3.1 Add a `QuotedRecordCard(record: RecordViewRecord)` composable
      with a bordered rounded container
- [x] 3.2 Decode `record.value` to `app.bsky.feed.Post` via
      `decodeRecord<Post>()`; tolerate decode failure by rendering only
      the quoted author handle
- [x] 3.3 Render quoted author handle, quoted post text (if non-blank),
      and the first image thumbnail from `record.embeds` if present
- [x] 3.4 Wire the card into `PostRow` between the comment text and the
      outer image thumbnail; render the placeholder text in the same slot
      when `extractQuotedRecord` returns null but `extractQuotedPlaceholder`
      returns a string

## 4. Tests

- [x] 4.1 Add a canned-JSON fixture for a reposted plain post and assert
      the extracted reposter handle matches
- [x] 4.2 Add a canned-JSON fixture for a quote post (RecordView arm)
      and assert `extractQuotedRecord` returns the expected author and
      decoded text
- [x] 4.3 Add a canned-JSON fixture for a quote+media post
      (RecordWithMediaView arm) and assert both the quoted record and the
      media thumbnail are extracted
- [x] 4.4 Add canned-JSON fixtures for each non-record
      RecordViewRecordUnion arm (NotFound, Blocked, Detached, Unknown)
      and assert `extractQuotedPlaceholder` returns the expected text
      and `extractQuotedRecord` returns null
- [x] 4.5 Run `./gradlew :samples:android:testDebugUnitTest` and confirm
      all new tests pass alongside existing ones

## 5. Build verification

- [x] 5.1 `./gradlew :samples:android:assembleDebug` builds
- [x] 5.2 `./gradlew spotlessCheck` passes
- [x] 5.3 Manual test on device: log in, scroll the timeline, and confirm
      reposts display a "Reposted by" header and quote posts display a
      bordered sub-card with the quoted author + text
