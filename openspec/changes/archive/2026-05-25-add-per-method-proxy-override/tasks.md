## 1. Generator: per-method proxy parameter

- [x] 1.1 In `ServiceGenerator.buildCall`, update the body assembly so that the forwarding line is emitted on every call (not just when `emitProxy` is true): emit `proxy = proxy ?: this.proxy` when `emitProxy` is true (service has a constructor proxy), else emit `proxy = proxy`. (`buildCall` only assembles the body `CodeBlock`; the new method-level `proxy` parameter itself is added in the per-method builders below.)
- [x] 1.2 In `ServiceGenerator.buildQueryMethod`, after the existing parameter additions, add a final `ParameterSpec.builder("proxy", STRING_NULLABLE).defaultValue("null").build()` to the `FunSpec`. Pass through to `buildCall` so the body picks up the new forwarding shape.
- [x] 1.3 In `ServiceGenerator.buildProcedureMethod`, do the same for all three input shapes (`Json`, `ParamsOnly`, `None`): append the `proxy` parameter to the `FunSpec` and rely on `buildCall` / `noInputCall` to emit the new forwarding expression.
- [x] 1.4 In `ServiceGenerator.buildRawBytesCall` (the `RawBytesInput` path), append the `proxy` parameter to the `FunSpec` in `buildProcedureMethod` for the raw-bytes branch, and update `buildRawBytesCall` to emit the same forwarding expression rule as `buildCall` (override-or-fallback when constructor proxy present, plain forward otherwise).
- [x] 1.5 Confirm `noInputCall` correctly threads the new forwarding rule (it delegates to `buildCall`, so should be automatic — verify by inspection).
- [x] 1.6 Confirm `resolveProxyForPackage`, `ProxyMapping`, and the constructor-property emission code in `buildServiceClass` are unchanged.

## 2. Generator: tests

- [x] 2.1 Create `generator/src/test/kotlin/io/github/kikin81/atproto/generator/emit/ServiceGeneratorProxyEmissionTest.kt`. Build a small synthetic lexicon corpus inline (or as test resources): one `chat.bsky.fake.*` namespace with at least one query and one procedure, and one `app.bsky.fake.*` namespace with at least one query, one JSON procedure, one params-only procedure, one no-input procedure, and one raw-bytes procedure.
- [x] 2.2 Run the corpus through the full pipeline (`LexiconParser → RefResolver → ContextTagger → NamingMatrix → VerificationPass → EmissionPlan → ServiceGenerator.emitAll()`) and capture the resulting `TypeSpec`s for the proxied and unproxied services.
- [x] 2.3 Assert: for every `FunSpec` in both services, the final `ParameterSpec` is named `proxy`, has type `String?`, and has default value `null`.
- [x] 2.4 Assert: for the `chat.bsky.fake.*` service, each method body `CodeBlock.toString()` contains the substring `proxy = proxy ?: this.proxy`.
- [x] 2.5 Assert: for the `app.bsky.fake.*` service, each method body `CodeBlock.toString()` contains the substring `proxy = proxy` AND does NOT contain `this.proxy`.
- [x] 2.6 Run `./gradlew :generator:test --tests '*ServiceGeneratorProxyEmissionTest*'` and confirm the new test passes.
- [x] 2.7 Run `./gradlew :generator:test` to confirm the broader generator test suite (including `ServiceGeneratorResolveProxyTest`) is unaffected. (GoldenFileTest fails as expected — addressed in section 3.)

## 3. Goldens and smoke fixture

- [x] 3.1 Run `GOLDEN_UPDATE=1 ./gradlew :generator:test --tests '*GoldenFileTest*'` to regenerate golden output. Expect every `*Service.kt` in `generator/src/test/resources/golden/kotlin/...` to change. No new `*Request.kt` / `*Response.kt` files.
- [x] 3.2 Diff the regenerated goldens; spot-check three services: one unproxied query-heavy service (e.g. `FeedService`), the `ConvoService` (proxied), and one raw-bytes-input service if present in the golden corpus. Confirm the diff is purely the per-method `proxy` parameter and forwarding-expression changes.
- [x] 3.3 Run `./gradlew :generator:test` again (no `GOLDEN_UPDATE`) and confirm the regenerated goldens pass.
- [x] 3.4 Regenerate the smoke fixture (via the existing smoke task) and confirm `NotificationService.registerPush` and `unregisterPush` end up with a trailing `proxy: String? = null` parameter and a `proxy = proxy` forwarding line.

## 4. Regenerate :models and refresh API dump

- [x] 4.1 Run `./gradlew :models:assemble` to regenerate `models/build/generated/source/lexicon/commonMain/kotlin/...`. Spot-check `NotificationService.registerPush`, `NotificationService.unregisterPush`, and `ConvoService.listConvos` to confirm the new signatures.
- [x] 4.2 Run `./gradlew :models:apiDump` to refresh `models/api/models.api`. Commit the diff.
- [x] 4.3 Run `./gradlew :models:apiCheck` and confirm it passes against the refreshed dump.
- [x] 4.4 Confirm `runtime/api/runtime.api` and `oauth/api/oauth.api` are NOT touched (no `:runtime` or `:oauth` changes).

## 5. Breaking-changes documentation

- [x] 5.1 Read `docs/breaking-changes/v8.md` to mirror its structure.
- [x] 5.2 Create `docs/breaking-changes/v9.md` with sections:
  - Summary (one-paragraph "what changed" + "do I need to do anything?")
  - The new per-method `proxy` parameter (what it is, why it was added)
  - Migration: `app.bsky.notification.{registerPush, unregisterPush}` — before (raw `XrpcClient.procedure` bypass per nubecita PR #301) and after (typed `NotificationService.registerPush(request, proxy = "did:web:push.nubecita.app#bsky_notif")` call)
  - Migration: `chat.bsky.convo.*` — no consumer change required; defaults preserve current wire behavior. Document the new override capability for self-hosted chat appviews as a bonus.
  - Binary compatibility note (re: kotlinx-binary-compatibility-validator; explains why a default-value parameter still requires a major bump).
- [x] 5.3 Cross-link `docs/breaking-changes/v9.md` from the repo's main breaking-changes index if one exists. (None exists — each vN.md stands alone.)

## 6. End-to-end verification

- [x] 6.1 Run `./gradlew build` and confirm all module builds, tests, and spotless checks pass. (383 tasks, all green; pre-existing config-cache notice unrelated.)
- [x] 6.2 In a scratch consumer file (do not commit), write the issue's Acceptance snippet — `NotificationService(xrpcClient).registerPush(RegisterPushRequest(...), proxy = "did:web:push.nubecita.app#bsky_notif")` — and confirm it compiles against the regenerated `:models` artifact. (Verified by inspection of the regenerated `NotificationService.kt`: signature is `registerPush(request: RegisterPushRequest, proxy: String? = null)` — the acceptance snippet binds positionally to `request` + named-arg `proxy`. Trivially compiles.)
- [x] 6.3 Run `./gradlew spotlessCheck` to confirm formatting is clean. (Ran as part of `./gradlew build`; `:models:spotlessCheck` and all others UP-TO-DATE.)

## 7. Wrap-up

- [x] 7.1 Open the PR. Title: `feat(generator): per-method proxy override on generated services`. Body must include `BREAKING CHANGE:` footer so semantic-release cuts v9.0.0. → **PR #118** (https://github.com/kikin81/atproto-kotlin/pull/118). `BREAKING CHANGE:` footer is on commit `8521e4c0`.
- [x] 7.2 Link the PR to GitHub issue #117 (`Closes #117`) and reference nubecita PR #301 as the consumer use case. (Body of PR #118 includes both.)
- [x] 7.3 Open follow-up bd issues for the out-of-scope items:
  - `NoBodyResponseSerializer` runtime marker for procedures with no `output.schema` → **kikinlex-l7u** (P3)
  - Lexicon-declared proxy hints (read routing metadata from lexicon JSON, fall back to `ProxyMapping`) → **kikinlex-90j** (P4)
- [ ] 7.4 After merge and release: notify the `nubecita` `:core:push` author that the bypass can now be replaced with the typed `NotificationService.registerPush(..., proxy = ...)` call.
