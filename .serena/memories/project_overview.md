# Project Overview: atproto-kotlin

## Purpose & Consumer App
`atproto-kotlin` is a Kotlin Multiplatform (JVM + iOS + Android) SDK for AT Protocol and Bluesky.
It directly powers **Nubecita**, an Android Bluesky client.
The primary ongoing objective is keeping the Lexicon schema up to date with the latest upstream Bluesky changes so Nubecita maintains 1:1 functional parity with the official Bluesky client app.

## Repository Information
- **Repository:** https://github.com/kikin81/atproto-kotlin
- **Maven Central:** `io.github.kikin81.atproto:{runtime,models,oauth,compose,compose-material3}`
- **Published version:** 9.11.0 (configured in `gradle.properties`, auto-bumped by semantic-release on push to main)
- **API Docs:** https://kikin81.github.io/atproto-kotlin/api/

## Modules
- `:runtime` (KMP - JVM + iOS, Published): Core runtime types, primitives, value classes (`AtUri`, `Did`, `Handle`, `Datetime`), `AtField` 3-state optionality, `OpenUnion` base types, `XrpcClient`, `AuthProvider` (Bearer + DPoP).
- `:generator` (JVM-only, Internal): Lexicon parser, IR, ref resolver, KotlinPoet code generator emitting models, services, unions, and records.
- `:models` (KMP - JVM + iOS, Published): Generated models, records, unions, and XRPC service interfaces from Lexicon corpus. Output path: `models/build/generated/source/lexicon/commonMain/kotlin`.
- `:oauth` (JVM-only, Published): AT Protocol OAuth 2.0 (PAR + PKCE + DPoP) public client implementation.
- `:compose` (Android library, Published): Bluesky facet parsing and `AnnotatedString` helpers for Jetpack Compose UI (no Material dependency).
- `:compose-material3` (Android library, Published): Material 3 `remember`-based wrapper for `BlueskyAnnotatedString`.
- `:samples:android` (Android application, Internal): Reference Bluesky client demo showing OAuth login flow and timeline feed.
