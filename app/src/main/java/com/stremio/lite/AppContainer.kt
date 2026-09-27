package com.stremio.lite

import android.content.Context
import com.stremio.lite.data.addon.AddonClient
import com.stremio.lite.data.addon.AddonRepository
import com.stremio.lite.data.api.LibraryRepository
import com.stremio.lite.data.api.SessionStore
import com.stremio.lite.data.api.StremioApi
import com.stremio.lite.data.download.DownloadRepository
import com.stremio.lite.util.Http

/**
 * Hand-rolled singleton graph. The app has a handful of long-lived objects and no need for a
 * DI framework; everything shares one OkHttp client so connections are pooled.
 */
class AppContainer private constructor(context: Context) {
    private val appContext = context.applicationContext

    val session = SessionStore(appContext)
    val api = StremioApi(Http.client)
    val addonClient = AddonClient(Http.client)
    val addons = AddonRepository(appContext, api, addonClient, session)
    val library = LibraryRepository(api, session)
    val downloads = DownloadRepository(appContext, Http.client)

    companion object {
        @Volatile
        private var instance: AppContainer? = null

        fun get(context: Context): AppContainer =
            instance ?: synchronized(this) {
                instance ?: AppContainer(context).also { instance = it }
            }
    }
}
