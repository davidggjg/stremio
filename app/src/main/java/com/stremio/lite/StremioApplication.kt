package com.stremio.lite

import android.app.Application

class StremioApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Builds the shared graph (session, addons, download queue) before any screen asks for it.
        AppContainer.get(this)
    }
}
