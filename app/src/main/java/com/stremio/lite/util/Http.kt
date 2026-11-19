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
