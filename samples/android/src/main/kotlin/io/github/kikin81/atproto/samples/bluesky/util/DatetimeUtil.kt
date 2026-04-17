package io.github.kikin81.atproto.samples.bluesky.util

import io.github.kikin81.atproto.runtime.Datetime
import java.time.Instant

fun datetimeNow(): Datetime = Datetime(Instant.now().toString())
