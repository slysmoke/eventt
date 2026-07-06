package org.eventt.core.http

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Interceptor that adds ESI auth token to requests.
 * Expects the Authorization header to be set via request tag or header.
 */
class AuthInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // If already has Authorization header, pass through
        if (request.header("Authorization") != null) {
            return chain.proceed(request)
        }

        // Get token from request header (set by ESI client)
        val token = request.header("X-ESI-Access-Token")
        if (token != null) {
            val authenticatedRequest =
                request
                    .newBuilder()
                    .header("Authorization", "Bearer $token")
                    .removeHeader("X-ESI-Access-Token")
                    .build()
            return chain.proceed(authenticatedRequest)
        }

        return chain.proceed(request)
    }
}
