package com.dpflix.android.network

import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Confiance HTTPS permissive pour les panels/flux IPTV, en complément de
 * `network_security_config.xml` (§res/xml).
 *
 * Pourquoi ce fichier en plus de network_security_config.xml : ce dernier ne couvre
 * que (1) le cleartext HTTP et (2) les certificats explicitement installés à la main
 * par l'utilisateur (`src="user"`). Il ne couvre PAS les certificats auto-signés ou
 * invalides servis directement par le panel sans action de l'utilisateur — cas très
 * fréquent sur des serveurs IPTV bricolés. Sans ce TrustManager, ces panels
 * échoueraient avec une `SSLHandshakeException` même si le flux est par ailleurs
 * valide. C'est probablement une des causes du comportement observé avec Televizo
 * (aucun flux jamais rejeté, y compris sur des panels à la config HTTPS douteuse).
 *
 * Compromis assumé : ceci désactive la vérification du certificat serveur sur les
 * connexions HTTPS de l'app (donc la protection contre l'interception/l'usurpation
 * de serveur). Acceptable ici pour un usage personnel où les identifiants Xtream
 * transitent déjà dans l'URL plutôt que dans un header d'auth séparé, et où
 * l'alternative concrète est simplement "le flux ne se lit pas du tout".
 */
object PermissiveTls {

    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    /** Accepte tout nom d'hôte, y compris quand le certificat ne correspond pas au domaine appelé. */
    val hostnameVerifier = HostnameVerifier { _, _ -> true }

    val sslSocketFactory: SSLSocketFactory by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustAllManager), SecureRandom())
        }.socketFactory
    }

    val trustManager: X509TrustManager get() = trustAllManager
}
