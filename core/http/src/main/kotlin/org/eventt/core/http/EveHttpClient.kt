package org.eventt.core.http

import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

object EveHttpClient {
    // Non-atomic check-then-build-then-assign is a real race: several concurrent callers at
    // startup (SSO token verify, static-data import, ESI status check) could each observe null,
    // each build and briefly hold their own client/connection pool, with all but the last
    // assignment silently discarded. @Volatile plus double-checked locking (getClient/configure/
    // close all take lockObj) makes this correct.
    @Volatile
    private var instance: OkHttpClient? = null
    private val lockObj = Any()

    // ESI best practices require a User-Agent identifying the app (see
    // https://developers.eveonline.com/docs/services/esi/best-practices/). The real app
    // name/version/contact is only known to the :app module, so it's set once at startup via
    // configure() rather than hardcoded here (core:http has no dependency on :app).
    @Volatile
    private var userAgent: String = "eventt-unconfigured (contact the app's maintainer for this User-Agent)"

    /** Call once at app startup, before any ESI request. Rebuilds the client if already created. */
    fun configure(userAgent: String) {
        this.userAgent = userAgent
        synchronized(lockObj) { instance = null }
    }

    fun getClient(enableLogging: Boolean = false): OkHttpClient {
        instance?.let { return it }
        synchronized(lockObj) {
            instance?.let { return it }

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

            val built = clientBuilder.build()
            instance = built
            return built
        }
    }

    fun close() {
        synchronized(lockObj) {
            instance?.dispatcher?.executorService?.shutdown()
            instance?.connectionPool?.evictAll()
            instance = null
        }
    }
}
