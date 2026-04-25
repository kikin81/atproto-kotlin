# Module compose-material3

Material 3 styling defaults for the AT Protocol Compose helpers.
Optional add-on layered on top of `:compose` — pull this module when
your app uses `androidx.compose.material3:material3` and you want a
one-line default.

## What's in here

- **`@Composable rememberBlueskyAnnotatedString(text, facets, linkStyle)`**
  — Tier 3 of the API. Wraps `:compose`'s
  `buildBlueskyAnnotatedString` with a default `linkStyle` of
  `SpanStyle(color = MaterialTheme.colorScheme.primary)`. Memoizes the
  result on `(text, facets, linkStyle)`.

## Why it's a separate artifact

The core `:compose` artifact has zero Material dependency. Consumers
with custom theme stacks (their own brand color for links) consume
`:compose` directly with their own `styleMapper` — they never pay the
Material3 transitive dependency cost. This module exists for the 80%
case where Material3 is already in the consumer's graph.

# Package io.github.kikin81.atproto.compose.material3

`rememberBlueskyAnnotatedString` composable, the Material3 default for
rendering Bluesky post text with facets.
