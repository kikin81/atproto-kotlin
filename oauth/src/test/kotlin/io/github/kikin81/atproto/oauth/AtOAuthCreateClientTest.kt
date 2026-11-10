package io.github.kikin81.atproto.oauth

import io.github.kikin81.atproto.runtime.XrpcErrorListener
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

class AtOAuthCreateClientTest {

    private fun oauthWithSession(): AtOAuth {
        val signer = DpopSigner.generate()
        val exported = signer.exportKeyPair()
        val session = OAuthSession(
            accessToken = "at",
            refreshToken = "rt",
            did = "did:plc:testuser",
            handle = "alice.test",
            pdsUrl = "https://pds.test",
            tokenEndpoint = "https://auth.test/token",
            clientId = "https://app.test/meta.json",
            dpopPrivateKey = exported.privateKeyEncoded,
            dpopPublicKey = exported.publicKeyEncoded,
        )
        val store = object : OAuthSessionStore {
            override suspend fun load(): OAuthSession? = session
            override suspend fun save(session: OAuthSession) = Unit
            override suspend fun clear() = Unit
        }
        return AtOAuth(
            clientMetadataUrl = "https://app.test/oauth/client-metadata.json",
            redirectUri = "app.example:/oauth-redirect",
            sessionStore = store,
            httpClient = HttpClient(MockEngine { respondError(HttpStatusCode.NotFound) }),
        )
    }

    @Test
    fun createClientPassesTheErrorListenerToTheClient() = runTest {
        val listener = XrpcErrorListener { _, _ -> }

        val client = oauthWithSession().createClient(errorListener = listener)

        assertSame(listener, client.errorListener)
    }

    @Test
    fun createClientHasNoErrorListenerByDefault() = runTest {
        assertNull(oauthWithSession().createClient().errorListener)
    }
}
