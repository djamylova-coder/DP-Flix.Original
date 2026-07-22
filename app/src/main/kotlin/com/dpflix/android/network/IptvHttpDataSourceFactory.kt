package com.dpflix.android.network

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * Fabrique un [DataSource.Factory] pour ExoPlayer/Media3 durci pour la lecture de flux
 * IPTV (HLS live, panels Xtream/M3U de fiabilité variable).
 *
 * Réécrite (2026-07-22) pour passer par [OkHttpDataSource] plutôt que le
 * `DefaultHttpDataSource` natif — nécessaire pour brancher deux mécanismes que
 * `DefaultHttpDataSource` seul ne permet pas facilement :
 *
 * 1. **Cascade de User-Agent** ([NetworkConstants.USER_AGENT_FALLBACKS], via
 *    [userAgentFallbackInterceptor]) : au lieu d'imposer une seule signature à toutes
 *    les requêtes (ancien comportement, potentiellement filtré par certains panels),
 *    on essaie d'abord sans en-tête personnalisé, puis on retombe sur des signatures
 *    de lecteurs IPTV connus seulement si la requête échoue réellement (code HTTP non
 *    2xx). C'est le changement principal par rapport à l'ancienne version : il n'y a
 *    plus de User-Agent unique forcé.
 *
 * 2. **TLS permissif** ([PermissiveTls]) : accepte les certificats HTTPS auto-signés
 *    ou invalides que certains panels servent directement, sans action possible côté
 *    utilisateur (contrairement au cleartext, réglable via network_security_config.xml).
 *
 * Conservés de l'ancienne version :
 * - `setAllowCrossProtocolRedirects` (ici : `followRedirects`/`followSslRedirects` sur
 *   OkHttpClient) : plusieurs panels redirigent l'URL demandée vers un CDN sur un autre
 *   protocole ou domaine.
 * - Timeouts et retries généreux, adaptés à des serveurs de panels souvent lents/surchargés.
 */
@UnstableApi
object IptvHttpDataSourceFactory {

    private const val CONNECT_TIMEOUT_MS = 15_000L
    private const val READ_TIMEOUT_MS = 20_000L

    /**
     * Réessaie la même requête avec les User-Agent successifs de
     * [NetworkConstants.USER_AGENT_FALLBACKS] tant que la réponse n'est pas un succès
     * (2xx). S'arrête au premier succès ; retourne la dernière réponse obtenue si
     * aucune tentative n'aboutit (pour que l'erreur remontée reste informative).
     */
    private val userAgentFallbackInterceptor = Interceptor { chain ->
        var lastResponse: Response? = null
        var response: Response? = null
        for (userAgent in NetworkConstants.USER_AGENT_FALLBACKS) {
            lastResponse?.close()
            val request = chain.request().newBuilder().apply {
                if (userAgent != null) header("User-Agent", userAgent) else removeHeader("User-Agent")
            }.build()
            response = chain.proceed(request)
            if (response.isSuccessful) return@Interceptor response
            lastResponse = response
        }
        response ?: chain.proceed(chain.request())
    }

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .sslSocketFactory(PermissiveTls.sslSocketFactory, PermissiveTls.trustManager)
            .hostnameVerifier(PermissiveTls.hostnameVerifier)
            .addInterceptor(userAgentFallbackInterceptor)
            .build()
    }

    /**
     * Exposé publiquement (2026-07-22) : c'est ce client — cascade de User-Agent + TLS
     * permissif — que [com.dpflix.android.player.PlayerController] doit utiliser pour
     * construire son propre [OkHttpDataSource.Factory], plutôt qu'un `OkHttpClient()`
     * nu comme c'était le cas avant ce correctif. Voir le README du correctif pour le
     * constat : c'est PlayerController, pas cet objet, qui est le point de câblage réel
     * du lecteur vidéo.
     */
    fun httpClient(): OkHttpClient = okHttpClient

    /** DataSource.Factory à passer à HlsMediaSource.Factory (ou tout MediaSource.Factory Media3).
     *  Context requis par DefaultDataSource.Factory (type non-nullable côté Media3) pour la
     *  gestion des schémas non-HTTP (fichiers locaux, assets). */
    fun create(context: Context): DataSource.Factory {
        val httpDataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
        return DefaultDataSource.Factory(context, httpDataSourceFactory)
    }

    /**
     * Politique de retry pour les chargements HLS (segments, playlists) : plus
     * tolérante que les valeurs par défaut de Media3 sur les erreurs réseau
     * transitoires, fréquentes sur des panels IPTV surchargés.
     */
    fun loadErrorHandlingPolicy(): LoadErrorHandlingPolicy = object : DefaultLoadErrorHandlingPolicy() {
        override fun getMinimumLoadableRetryCount(dataType: Int): Int = 6

        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            // Backoff simple : 500ms, 1s, 2s, 4s... plafonné à 8s.
            val exponent = (loadErrorInfo.errorCount - 1).coerceAtMost(4)
            return (500L * (1L shl exponent)).coerceAtMost(8_000L)
        }
    }
}

/*
 * `create(context)`/`loadErrorHandlingPolicy()` ci-dessus restent utilisables tels quels si un
 * jour un `HlsMediaSource.Factory` explicite est construit ailleurs dans le projet, mais
 * ne sont PAS le chemin réellement emprunté aujourd'hui : voir le README du correctif —
 * c'est `PlayerController.httpDataSourceFactory`, avec `DefaultMediaSourceFactory` et
 * `ResilientLoadErrorHandlingPolicy`, qui pilote la lecture vidéo, et c'est lui qui a été
 * corrigé pour utiliser [httpClient] ci-dessus. Aucune dépendance Gradle supplémentaire
 * à ajouter : `androidx.media3:media3-datasource-okhttp` est déjà sur le classpath
 * (PlayerController.kt l'importait déjà avant ce correctif).
 */
