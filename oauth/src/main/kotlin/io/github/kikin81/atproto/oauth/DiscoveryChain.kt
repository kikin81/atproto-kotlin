package io.github.kikin81.atproto.oauth

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Resolved authorization server metadata — everything the OAuth flow
 * needs to construct PAR requests, authorization URLs, and token
 * exchange calls.
 */
data class AuthServerMetadata(
    val issuer: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val parEndpoint: String,
    val revocationEndpoint: String?,
    val pdsUrl: String,
    val did: String,
    val handle: String,
)

/**
 * Implements the AT Protocol discovery chain:
 *
 * 1. **Handle → DID**: HTTP fallback via `/.well-known/atproto-did`
 *    on the handle's domain. (DNS `_atproto.<handle>` TXT is preferred
 *    but requires a DNS library; HTTP fallback covers all cases.)
 * 2. **DID → PDS**: Fetch the DID document from `plc.directory` (for
 *    `did:plc`) or the DID method's resolution endpoint, extract the
 *    `#atproto_pds` service endpoint.
 * 3. **PDS → Auth Server**: Fetch `/.well-known/oauth-protected-resource`
 *    → `authorization_servers[0]`, then `/.well-known/oauth-authorization-server`
 *    → extract all OAuth endpoint URLs.
 * 4. **Bidirectional handle verification**: verify the DID document's
 *    `alsoKnownAs` field claims the original handle.
 */
class DiscoveryChain(
    private val httpClient: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    /**
     * Resolves a handle or DID all the way to a fully-populated
     * [AuthServerMetadata] ready for the OAuth flow.
     */
    suspend fun resolve(handleOrDid: String): AuthServerMetadata {
        val (did, handle) = if (handleOrDid.startsWith("did:")) {
            val doc = resolveDid(handleOrDid)
            val handle = extractHandleFromDidDoc(doc, handleOrDid)
            handleOrDid to handle
        } else {
            val did = resolveHandle(handleOrDid)
            val doc = resolveDid(did)
            verifyBidirectionalHandle(doc, handleOrDid, did)
            did to handleOrDid
        }

        val pdsUrl = resolvePds(did)
        val authServerUrl = resolveAuthServer(pdsUrl)
        val metadata = fetchAuthServerMetadata(authServerUrl)

        return AuthServerMetadata(
            issuer = metadata.issuer ?: authServerUrl,
            authorizationEndpoint = metadata.authorizationEndpoint
                ?: throw OAuthDiscoveryException("authorization_endpoint missing from auth server metadata at $authServerUrl"),
            tokenEndpoint = metadata.tokenEndpoint
                ?: throw OAuthDiscoveryException("token_endpoint missing from auth server metadata at $authServerUrl"),
            parEndpoint = metadata.pushedAuthorizationRequestEndpoint
                ?: throw OAuthDiscoveryException("pushed_authorization_request_endpoint missing from auth server metadata at $authServerUrl"),
            revocationEndpoint = metadata.revocationEndpoint,
            pdsUrl = pdsUrl,
            did = did,
            handle = handle,
        )
    }

    /**
     * Handle → DID resolution. Tries two methods in order:
     *
     * 1. **DNS-over-HTTPS**: queries `_atproto.<handle>` TXT record via
     *    Cloudflare's DoH endpoint. Most custom-domain Bluesky handles
     *    (e.g. `franciscovelazquez.com`) use a DNS TXT record like
     *    `did=did:plc:abc123` for handle verification.
     *
     * 2. **HTTP fallback**: fetches `https://<handle>/.well-known/atproto-did`.
     *    Used by handles that host the DID at their domain directly
     *    (less common but supported by the spec).
     */
    internal suspend fun resolveHandle(handle: String): String {
        // Try DNS-over-HTTPS first (most common for custom domains)
        val dnsDid = tryResolveDns(handle)
        if (dnsDid != null) return dnsDid

        // Fallback to HTTP /.well-known/atproto-did
        val httpDid = tryResolveHttp(handle)
        if (httpDid != null) return httpDid

        throw OAuthDiscoveryException(
            "Failed to resolve handle '$handle': neither DNS TXT record " +
                "(_atproto.$handle) nor HTTP (https://$handle/.well-known/atproto-did) returned a valid DID",
        )
    }

    private suspend fun tryResolveDns(handle: String): String? {
        val dohUrl = "https://cloudflare-dns.com/dns-query?name=_atproto.$handle&type=TXT"
        return try {
            val response = httpClient.get(dohUrl) {
                header("Accept", "application/dns-json")
            }
            if (response.status != HttpStatusCode.OK) return null
            val body = response.bodyAsText()
            val dnsResponse = json.decodeFromString(DnsResponse.serializer(), body)
            val txtData = dnsResponse.Answer?.firstOrNull { it.type == 16 }?.data ?: return null
            // TXT record data is quoted: "did=did:plc:abc123" → strip quotes + prefix
            val cleaned = txtData.trim('"').trim()
            if (cleaned.startsWith("did=")) {
                val did = cleaned.removePrefix("did=")
                if (did.startsWith("did:")) did else null
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun tryResolveHttp(handle: String): String? {
        val url = "https://$handle/.well-known/atproto-did"
        return try {
            val response = httpClient.get(url)
            if (response.status != HttpStatusCode.OK) return null
            val did = response.bodyAsText().trim()
            if (did.startsWith("did:")) did else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * DID → DID document via `plc.directory` (for `did:plc`) or
     * the DID method's resolution endpoint.
     */
    internal suspend fun resolveDid(did: String): DidDocument {
        val url = when {
            did.startsWith("did:plc:") -> "https://plc.directory/$did"
            did.startsWith("did:web:") -> {
                val host = did.removePrefix("did:web:")
                "https://$host/.well-known/did.json"
            }
            else -> throw OAuthDiscoveryException("Unsupported DID method: $did")
        }
        val response = try {
            httpClient.get(url)
        } catch (e: Exception) {
            throw OAuthDiscoveryException("Failed to fetch DID document for '$did' from $url", e)
        }
        if (response.status != HttpStatusCode.OK) {
            throw OAuthDiscoveryException("DID document fetch for '$did' returned ${response.status}")
        }
        return try {
            json.decodeFromString(DidDocument.serializer(), response.bodyAsText())
        } catch (e: Exception) {
            throw OAuthDiscoveryException("Failed to parse DID document for '$did'", e)
        }
    }

    /**
     * Extracts the PDS URL from the DID document's service array.
     */
    internal suspend fun resolvePds(did: String): String {
        val doc = resolveDid(did)
        val pdsService = doc.service?.firstOrNull { it.id == "#atproto_pds" }
            ?: throw OAuthDiscoveryException("DID document for '$did' has no #atproto_pds service")
        return pdsService.serviceEndpoint
    }

    /**
     * PDS → authorization server via `/.well-known/oauth-protected-resource`.
     */
    internal suspend fun resolveAuthServer(pdsUrl: String): String {
        val url = "${pdsUrl.trimEnd('/')}/.well-known/oauth-protected-resource"
        val response = try {
            httpClient.get(url)
        } catch (e: Exception) {
            throw OAuthDiscoveryException("Failed to fetch resource server metadata from $url", e)
        }
        if (response.status != HttpStatusCode.OK) {
            throw OAuthDiscoveryException("Resource server metadata at $url returned ${response.status}")
        }
        val metadata = try {
            json.decodeFromString(ResourceServerMetadata.serializer(), response.bodyAsText())
        } catch (e: Exception) {
            throw OAuthDiscoveryException("Failed to parse resource server metadata from $url", e)
        }
        return metadata.authorizationServers.firstOrNull()
            ?: throw OAuthDiscoveryException("Resource server metadata at $url has empty authorization_servers array")
    }

    /**
     * Fetches the authorization server's OAuth metadata.
     */
    internal suspend fun fetchAuthServerMetadata(authServerUrl: String): AuthorizationServerMetadata {
        val url = "${authServerUrl.trimEnd('/')}/.well-known/oauth-authorization-server"
        val response = try {
            httpClient.get(url)
        } catch (e: Exception) {
            throw OAuthDiscoveryException("Failed to fetch auth server metadata from $url", e)
        }
        if (response.status != HttpStatusCode.OK) {
            throw OAuthDiscoveryException("Auth server metadata at $url returned ${response.status}")
        }
        return try {
            json.decodeFromString(AuthorizationServerMetadata.serializer(), response.bodyAsText())
        } catch (e: Exception) {
            throw OAuthDiscoveryException("Failed to parse auth server metadata from $url", e)
        }
    }

    private fun verifyBidirectionalHandle(doc: DidDocument, handle: String, did: String) {
        val expectedUri = "at://$handle"
        val alsoKnownAs = doc.alsoKnownAs ?: emptyList()
        if (alsoKnownAs.none { it == expectedUri }) {
            throw OAuthDiscoveryException(
                "Bidirectional handle verification failed: DID document for '$did' does not claim handle '$handle' " +
                    "(expected 'at://$handle' in alsoKnownAs, found: $alsoKnownAs)",
            )
        }
    }

    private fun extractHandleFromDidDoc(doc: DidDocument, did: String): String {
        val atUri = doc.alsoKnownAs?.firstOrNull { it.startsWith("at://") }
            ?: throw OAuthDiscoveryException("DID document for '$did' has no at:// handle in alsoKnownAs")
        return atUri.removePrefix("at://")
    }
}

// --- Internal data classes for JSON parsing ---

@Serializable
internal data class DidDocument(
    val id: String? = null,
    val alsoKnownAs: List<String>? = null,
    val service: List<DidService>? = null,
)

@Serializable
internal data class DidService(
    val id: String,
    val type: String? = null,
    val serviceEndpoint: String,
)

@Serializable
internal data class ResourceServerMetadata(
    val resource: String? = null,
    val authorization_servers: List<String> = emptyList(),
) {
    val authorizationServers: List<String> get() = authorization_servers
}

@Serializable
internal data class AuthorizationServerMetadata(
    val issuer: String? = null,
    val authorization_endpoint: String? = null,
    val token_endpoint: String? = null,
    val pushed_authorization_request_endpoint: String? = null,
    val revocation_endpoint: String? = null,
    val dpop_signing_alg_values_supported: List<String>? = null,
    val scopes_supported: List<String>? = null,
) {
    val authorizationEndpoint: String? get() = authorization_endpoint
    val tokenEndpoint: String? get() = token_endpoint
    val pushedAuthorizationRequestEndpoint: String? get() = pushed_authorization_request_endpoint
    val revocationEndpoint: String? get() = revocation_endpoint
}

@Serializable
internal data class DnsResponse(
    val Answer: List<DnsAnswer>? = null,
)

@Serializable
internal data class DnsAnswer(
    val type: Int = 0,
    val data: String? = null,
)
