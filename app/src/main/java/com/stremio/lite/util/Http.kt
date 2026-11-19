package com.stremio.lite.util

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.Cache
import okhttp3.Dispatcher
import okhttp3.OkHttpClient

object Http {

    private const val CACHE_BYTES = 64L * 1024 * 1024

    @Volatile
    private var instance: OkHttpClient? = null

    /**
     * One shared client. Connection pooling is what makes many parallel range requests cheap, and
     * the disk cache is what makes a second visit to the board instant: addons send
     * `Cache-Control: max-age` derived from their `cacheMaxAge`, so honouring it is free correctness.
     */
    fun client(context: Context): OkHttpClient =
        instance ?: synchronized(this) {
            instance ?: build(context).also { instance = it }
        }

    /**
     * A client for transfers that run for minutes, not seconds.
     *
     * The shared client deliberately carries short timeouts so one dead addon cannot stall a
     * catalog row — but those same limits would abort a multi-gigabyte download twenty seconds
     * in. This derives a client that keeps the connection pool and drops the overall call
     * deadline, timing out only on a genuinely silent socket.
     */
    fun downloadClient(context: Context): OkHttpClient = client(context).newBuilder()
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        // Responses are written straight to disk, so the HTTP cache must not try to store them.
        .cache(null)
        .build()

    /** For call sites that cannot reach a Context; valid once [client] has run at least once. */
    val shared: OkHttpClient
        get() = instance ?: synchronized(this) {
            instance ?: OkHttpClient.Builder().applyCommon().build().also { instance = it }
        }

    private fun build(context: Context): OkHttpClient = OkHttpClient.Builder()
        .cache(Cache(File(context.cacheDir, "http"), CACHE_BYTES))
        .applyCommon()
        .build()

    private fun OkHttpClient.Builder.applyCommon(): OkHttpClient.Builder = this
        .dispatcher(
            Dispatcher().apply {
                maxRequests = 96
                maxRequestsPerHost = 32
            }
        )
        // Addon calls are fanned out to every installed addon at once; a dead one must not hold a
        // row hostage, so these are deliberately short. The downloader overrides them per call.
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
}
