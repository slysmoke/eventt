package org.eventt.core.http

import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

object EveHttpClient {
    private var instance: OkHttpClient? = null

    // ESI best practices require a User-Agent identifying the app (see
    // https://developers.eveonline.com/docs/services/esi/best-practices/). The real app
    // name/version/contact is only known to the :app module, so it's set once at startup via
    // configure() rather than hardcoded here (core:http has no dependency on :app).
    @Volatile
    private var userAgent: String = "eventt-unconfigured (contact the app's maintainer for this User-Agent)"

    /** Call once at app startup, before any ESI request. Rebuilds the client if already created. */
    fun configure(userAgent: String) {
        this.userAgent = userAgent
        instance = null
    }

    fun getClient(enableLogging: Boolean = false): OkHttpClient {
        if (instance != null) return instance!!

        val clientBuilder =
            OkHttpClient
                .Builder()
                .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS)
                .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT))
                .retryOnConnectionFailure(true)
                .addInterceptor(UserAgentInterceptor { userAgent })
                .addInterceptor(EsiThrottleInterceptor())

        if (enableLogging) {
            val logging =
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BODY
                }
            clientBuilder.addInterceptor(logging)
        }

        instance = clientBuilder.build()
        return instance!!
    }

    fun close() {
        instance?.dispatcher?.executorService?.shutdown()
        instance?.connectionPool?.evictAll()
        instance = null
    }
}
