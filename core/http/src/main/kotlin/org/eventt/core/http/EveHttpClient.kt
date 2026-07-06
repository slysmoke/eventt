package org.eventt.core.http

import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

object EveHttpClient {

    private var instance: OkHttpClient? = null

    fun getClient(enableLogging: Boolean = false): OkHttpClient {
        if (instance != null) return instance!!

        val clientBuilder = OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT))
            .retryOnConnectionFailure(true)

        if (enableLogging) {
            val logging = HttpLoggingInterceptor().apply {
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
