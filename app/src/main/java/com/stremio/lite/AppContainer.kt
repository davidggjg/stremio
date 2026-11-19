package com.stremio.lite

import android.content.Context
import com.stremio.lite.data.addon.AddonClient
import com.stremio.lite.data.addon.AddonRepository
import com.stremio.lite.data.addon.CatalogRepository
import com.stremio.lite.data.api.LibraryRepository
import com.stremio.lite.data.api.SessionStore
import com.stremio.lite.data.api.StremioApi
import com.stremio.lite.data.download.DownloadRepository
import com.stremio.lite.util.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Hand-rolled singleton graph. The app has a handful of long-lived objects and no need for a
 * DI framework; everything shares one OkHttp client so connections are pooled.
 */
class AppContainer private constructor(context: Context) {
    private val appContext = context.applicationContext

    /** Lives as long as the process: board state and the download queue outlive any screen. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val http = Http.client(appContext)

    val session = SessionStore(appContext)
    val api = StremioApi(http)
    val addonClient = AddonClient(http)
    val addons = AddonRepository(appContext, api, addonClient, session)
    val catalogs = CatalogRepository(addons, addonClient, scope)
    val library = LibraryRepository(api, session)
    val downloads = DownloadRepository(appContext, Http.downloadClient(appContext))

    companion object {
        @Volatile
        private var instance: AppContainer? = null

        fun get(context: Context): AppContainer =
            instance ?: synchronized(this) {
                instance ?: AppContainer(context).also { instance = it }
            }
    }
}
