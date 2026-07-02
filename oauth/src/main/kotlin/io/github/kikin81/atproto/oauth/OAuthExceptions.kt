package io.github.kikin81.atproto.oauth

class OAuthAccountMismatchException(message: String) : RuntimeException(message)

class OAuthSessionExpiredException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Thrown when a token refresh could not be completed for a reason that does NOT
 * prove the refresh token is dead — a network failure, a 5xx/429/408, an
 * unparseable (e.g. captive-portal) body, or any non-`invalid_grant` error
 * response — as opposed to a genuinely revoked/expired refresh token
 * (`error=invalid_grant`, which is [OAuthSessionExpiredException]).
 *
 * The session is deliberately left intact: a later request with real
 * connectivity can refresh cleanly. Callers must treat this as a retryable
 * error, NOT a sign-out — surfacing it as "logged out" is the bug this type
 * exists to prevent.
 */
class OAuthRefreshFailedException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class OAuthException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Thrown by [AtOAuth.beginSignup] when the configured authorization server's
 * `/.well-known/oauth-authorization-server` metadata does not advertise
 * `"create"` in `prompt_values_supported`. Per OIDC Prompt Create 1.0 the
 * `prompt=create` value tells the server "render the signup UI"; servers
 * that don't advertise the value may silently ignore it or reject the PAR.
 *
 * Consumers targeting non-conformant entryways can bypass the check via
 * `beginSignup(authServer, requirePromptCreateSupport = false)`.
 */
class OAuthSignupNotSupportedException(
    val authServerUrl: String,
    val advertisedPromptValues: List<String>,
) : RuntimeException(
    "Authorization server '$authServerUrl' does not advertise 'create' in " +
        "prompt_values_supported (advertised: $advertisedPromptValues). " +
        "Pass requirePromptCreateSupport=false to override.",
)
