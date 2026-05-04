## ADDED Requirements

### Requirement: XrpcClient SHALL support raw-bytes procedure bodies with caller-supplied Content-Type

The system SHALL provide a `procedure(...)` overload on `XrpcClient` that accepts an `input: ByteArray` plus an `inputContentType: ContentType` and posts the bytes verbatim as the request body with the supplied `Content-Type` header. The overload SHALL run through the same auth/DPoP/401-retry/error-mapping path as the existing JSON-body overload — specifically, it SHALL apply the active `AuthProvider` (per-call override or the client's default), invoke `AuthProvider.onUnauthorized(...)` and retry exactly once on a 401 response, and decode error responses through the same `XrpcErrorMapper` used by the JSON overload. The overload SHALL accept the same `params`/`paramsSerializer`/`responseSerializer`/`errorMapper`/`auth` parameters as the existing JSON overload so that it is a drop-in alternative whenever the body needs to be raw bytes instead of a JSON-serialized object.

#### Scenario: Posting raw bytes with a configured Content-Type

- **GIVEN** an `XrpcClient` configured against a MockEngine that records each request
- **WHEN** the client invokes the raw-bytes procedure overload for `com.atproto.repo.uploadBlob` with `input = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)` and `inputContentType = ContentType.Image.PNG`
- **THEN** the recorded HTTP request is `POST /xrpc/com.atproto.repo.uploadBlob`
- **AND** the request body bytes are exactly `[0x89, 0x50, 0x4E, 0x47]` with no JSON wrapping
- **AND** the request `Content-Type` header is `image/png`

#### Scenario: Auth is applied to raw-bytes procedure requests

- **GIVEN** an `XrpcClient` configured with an `AuthProvider` that adds `Authorization: Bearer test-token` and a MockEngine that records the request
- **WHEN** the client invokes the raw-bytes procedure overload
- **THEN** the recorded request carries `Authorization: Bearer test-token`
- **AND** any DPoP or proof-of-possession headers the provider adds for procedure requests are present, identical to the JSON overload's behavior

#### Scenario: 401 retry path runs for raw-bytes procedure requests

- **GIVEN** an `XrpcClient` configured with an `AuthProvider` whose first attempt produces an expired token and whose `onUnauthorized` refreshes it, against a MockEngine that returns 401 then 200
- **WHEN** the client invokes the raw-bytes procedure overload
- **THEN** the engine receives exactly two requests, the first with the original auth header and the second with the refreshed one
- **AND** the second request carries the same raw-byte body and `Content-Type` as the first

#### Scenario: Error mapping runs for raw-bytes procedure requests

- **GIVEN** a MockEngine that returns HTTP 400 with body `{"error":"InvalidBlobSize","message":"too large"}`
- **WHEN** the client invokes the raw-bytes procedure overload
- **THEN** the call raises a typed `XrpcError` produced by the configured `XrpcErrorMapper` matching name `InvalidBlobSize`, not a generic HTTP exception
