package io.github.kikin81.atproto.samples.bluesky.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.kikin81.atproto.oauth.AtOAuth
import io.github.kikin81.atproto.oauth.OAuthSessionStore
import io.github.kikin81.atproto.samples.bluesky.session.AndroidOAuthSessionStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideHttpClient(): HttpClient = HttpClient(CIO)

    @Provides
    @Singleton
    fun provideSessionStore(@ApplicationContext context: Context): OAuthSessionStore = AndroidOAuthSessionStore(context)

    @Provides
    @Singleton
    fun provideAtOAuth(
        sessionStore: OAuthSessionStore,
        httpClient: HttpClient,
    ): AtOAuth = AtOAuth(
        clientMetadataUrl = "https://kikin81.github.io/atproto-kotlin/oauth/client-metadata.json",
        redirectUri = "io.github.kikin81:/oauth-redirect",
        sessionStore = sessionStore,
        httpClient = httpClient,
    )
}
