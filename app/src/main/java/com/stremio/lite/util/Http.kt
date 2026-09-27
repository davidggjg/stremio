package com.stremio.lite.util

import java.util.concurrent.TimeUnit
import okhttp3.Dispatcher
import okhttp3.OkHttpClient

object Http {
    /**
     * One shared client: connection pooling is what makes many parallel range requests cheap.
     * The dispatcher limits are raised well above OkHttp's default of 5 per host because the
     * downloader deliberately opens several connections to the same host at once.
     */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dispatcher(
                Dispatcher().apply {
                    maxRequests = 96
                    maxRequestsPerHost = 32
                }
            )
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .build()
    }
}
