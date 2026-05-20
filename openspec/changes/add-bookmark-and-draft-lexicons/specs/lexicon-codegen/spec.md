## ADDED Requirements

### Requirement: Generated SDK SHALL include `app.bsky.bookmark.*` clients

The system SHALL include `app.bsky.bookmark.defs`, `app.bsky.bookmark.createBookmark`, `app.bsky.bookmark.deleteBookmark`, and `app.bsky.bookmark.getBookmarks` in the manifest at `generator/lexicons.json` and emit a `BookmarkService` Kotlin class in package `io.github.kikin81.atproto.app.bsky.bookmark` exposing the three endpoints as suspending functions, plus a `BookmarkView` data class for the `bookmarkView` ref in `app.bsky.bookmark.defs`.

#### Scenario: Generated BookmarkService exposes the three endpoints

- **WHEN** a consumer constructs `BookmarkService(xrpcClient)` after building `:models`
- **THEN** the class exposes `suspend fun getBookmarks(request: GetBookmarksRequest = GetBookmarksRequest()): GetBookmarksResponse`, `suspend fun createBookmark(request: CreateBookmarkRequest): Unit`, and `suspend fun deleteBookmark(request: DeleteBookmarkRequest): Unit`
- **AND** `GetBookmarksResponse.bookmarks` is typed `List<BookmarkView>` with an optional `cursor: String?`

#### Scenario: getBookmarks hits the correct XRPC path with paginated query params

- **WHEN** a consumer calls `BookmarkService(xrpcClient).getBookmarks(GetBookmarksRequest(limit = 25, cursor = "abc"))` against a `MockEngine`-backed `XrpcClient`
- **THEN** the outgoing HTTP request is `GET .../xrpc/app.bsky.bookmark.getBookmarks` with `limit=25` and `cursor=abc` in the query string
- **AND** a JSON response of `{"cursor":"next","bookmarks":[]}` decodes into a typed `GetBookmarksResponse`

### Requirement: Generated SDK SHALL include `app.bsky.draft.*` clients

The system SHALL include `app.bsky.draft.defs`, `app.bsky.draft.createDraft`, `app.bsky.draft.deleteDraft`, `app.bsky.draft.getDrafts`, and `app.bsky.draft.updateDraft` in the manifest at `generator/lexicons.json` and emit a `DraftService` Kotlin class in package `io.github.kikin81.atproto.app.bsky.draft` exposing the four endpoints as suspending functions, plus a `Draft` data class for the `draft` ref in `app.bsky.draft.defs`.

#### Scenario: Generated DraftService exposes the four endpoints

- **WHEN** a consumer constructs `DraftService(xrpcClient)` after building `:models`
- **THEN** the class exposes `suspend fun getDrafts(request: GetDraftsRequest = GetDraftsRequest()): GetDraftsResponse`, `suspend fun createDraft(request: CreateDraftRequest): CreateDraftResponse`, `suspend fun updateDraft(request: UpdateDraftRequest): Unit`, and `suspend fun deleteDraft(request: DeleteDraftRequest): Unit`
- **AND** `CreateDraftResponse.id` is typed `String`

#### Scenario: createDraft posts the JSON-encoded input to the correct XRPC path

- **WHEN** a consumer calls `DraftService(xrpcClient).createDraft(CreateDraftRequest(draft = Draft(posts = listOf(DraftPost(text = "hello")))))` against a `MockEngine`-backed `XrpcClient`
- **THEN** the outgoing HTTP request is `POST .../xrpc/app.bsky.draft.createDraft` with `Content-Type: application/json`
- **AND** the request body contains a JSON object with a `draft` property whose `posts[0].text` is `"hello"`
- **AND** a JSON response of `{"id":"draft-123"}` decodes into a typed `CreateDraftResponse` with `id = "draft-123"`
