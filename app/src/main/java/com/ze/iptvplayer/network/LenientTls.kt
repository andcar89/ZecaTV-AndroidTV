package com.ze.iptvplayer.network

import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

/**
 * Some IPTV/Xtream panels serve their playlist, EPG, or stream endpoints over HTTPS with an
 * incomplete or self-signed certificate chain. Browsers and dedicated player apps often tolerate
 * this (cached intermediate certs, more lenient validation) — Android's default TLS stack
 * (Conscrypt) does not, and rejects it with a "Chain validation failed" error.
 *
 * This builds OkHttpClients that skip certificate chain and hostname validation entirely. That's
 * a deliberate, contained trade-off: it only affects requests made through clients built here —
 * i.e. traffic to whichever single IPTV provider the user has configured — not the app's TLS
 * trust in general. It does mean those specific requests would accept a forged certificate from
 * a man-in-the-middle. That's an acceptable trade for playlist/video traffic to a provider the
 * user already trusts and chose to connect to, but it's a real trade-off worth knowing about
 * rather than a free fix.
 */
object LenientTls {

    private val trustAllCerts: Array<TrustManager> = arrayOf(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })

    private val trustManager: X509TrustManager = trustAllCerts[0] as X509TrustManager

    private val sslSocketFactory by lazy {
        SSLContext.getInstance("TLS").apply { init(null, trustAllCerts, SecureRandom()) }.socketFactory
    }

    private val hostnameVerifier = HostnameVerifier { _, _ -> true }

    fun newClientBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .sslSocketFactory(sslSocketFactory, trustManager)
        .hostnameVerifier(hostnameVerifier)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
}
