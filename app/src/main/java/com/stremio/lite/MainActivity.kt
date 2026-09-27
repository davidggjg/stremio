package com.stremio.lite

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.stremio.lite.ui.StremioApp
import com.stremio.lite.ui.theme.StremioTheme

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* progress notification only */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Needed on Android 13+ for the download progress notification the foreground service posts.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val openDownloads = intent?.getBooleanExtra(EXTRA_OPEN_DOWNLOADS, false) == true

        setContent {
            StremioTheme {
                StremioApp(startOnDownloads = openDownloads)
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_DOWNLOADS = "open_downloads"
    }
}
