package io.github.kikin81.atproto.example.feed

import io.github.kikin81.atproto.example.StrongRef
import io.github.kikin81.atproto.runtime.XrpcClient
import io.github.kikin81.atproto.runtime.paginate
import io.github.kikin81.atproto.runtime.paginatePages
import kotlin.collections.List
import kotlinx.coroutines.flow.Flow

public class FeedService(
  private val client: XrpcClient,
) {
  /**
   * Get a paginated timeline.
   */
  public suspend fun getTimeline(request: GetTimelineRequest = GetTimelineRequest()):
      GetTimelineResponse = client.query(
      nsid = "example.feed.getTimeline",
      params = request,
      paramsSerializer = GetTimelineRequest.serializer(),
      responseSerializer = GetTimelineResponse.serializer(),
  )
}

/**
 * Get a paginated timeline.
 */
public fun FeedService.timelineFlow(request: GetTimelineRequest = GetTimelineRequest()):
    Flow<StrongRef> = paginate(
    fetch = { cursor -> getTimeline(request.copy(cursor = cursor)) },
    getCursor = { it.cursor },
    getItems = { it.feed },
)

/**
 * Get a paginated timeline.
 */
public fun FeedService.timelinePageFlow(request: GetTimelineRequest = GetTimelineRequest()):
    Flow<List<StrongRef>> = paginatePages(
    fetch = { cursor -> getTimeline(request.copy(cursor = cursor)) },
    getCursor = { it.cursor },
    getItems = { it.feed },
)
