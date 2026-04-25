# AT Protocol Compose helpers — Material 3 (`compose-material3`)

Material 3 styling defaults for the AT Protocol Compose helpers.
Optional add-on layered on top of [`:compose`](../compose/README.md).

## Quick start

```kotlin
val annotated = rememberBlueskyAnnotatedString(post.text, post.facets)
Text(annotated)
```

`linkStyle` defaults to
`SpanStyle(color = MaterialTheme.colorScheme.primary)`. Override per
call site:

```kotlin
val annotated = rememberBlueskyAnnotatedString(
    text = post.text,
    facets = post.facets,
    linkStyle = SpanStyle(
        color = MaterialTheme.colorScheme.tertiary,
        textDecoration = TextDecoration.Underline,
    ),
)
```

The composable memoizes on `(text, facets, linkStyle)` so
recompositions reuse the boundary table and annotation graph.

## When not to use this artifact

If your app uses a custom theme stack with its own brand color for
links — Material 3 with a non-`primary` color, or an entirely
different design system — pull `:compose` directly and call
[`buildBlueskyAnnotatedString`](../compose/README.md#tier-2--mapper-convenience)
with your own `styleMapper`. The core artifact has zero Material
dependency.

## Dependencies

```kotlin
implementation("io.github.kikin81.atproto:compose-material3:<version>")
```

Pulls `:compose` (and therefore `:models` + `androidx.compose.ui:ui-text`)
transitively, plus `androidx.compose.material3:material3`.

See [`:compose`'s README](../compose/README.md) for the full API surface
(Tier 1 builder primitive, Tier 2 mapper convenience, byte-mapping
invariant, silent-skip semantics, click handling).
