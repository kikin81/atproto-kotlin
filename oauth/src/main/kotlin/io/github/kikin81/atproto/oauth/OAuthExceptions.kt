package io.github.kikin81.atproto.oauth

class OAuthAccountMismatchException(message: String) : RuntimeException(message)

class OAuthSessionExpiredException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class OAuthException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
