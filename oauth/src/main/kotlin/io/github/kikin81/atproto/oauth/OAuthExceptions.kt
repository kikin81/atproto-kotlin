package io.github.kikin81.atproto.oauth

class OAuthAccountMismatchException(message: String) : RuntimeException(message)

class OAuthSessionExpiredException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

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
