package com.stremio.lite.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.stremio.lite.AppContainer
import com.stremio.lite.data.download.DownloadRepository
import com.stremio.lite.data.download.DownloadService
import com.stremio.lite.data.download.DownloadSettings
import com.stremio.lite.data.download.DownloadTask
import java.io.File
import kotlinx.coroutines.flow.StateFlow

class DownloadsViewModel(app: Application) : AndroidViewModel(app) {
    private val container = AppContainer.get(app)
    private val repository: DownloadRepository = container.downloads

    val tasks: StateFlow<List<DownloadTask>> = repository.tasks
    val settings: StateFlow<DownloadSettings> = repository.settings

    fun pause(task: DownloadTask) = repository.pause(task.id)

    fun resume(task: DownloadTask) {
        repository.resume(task.id)
        DownloadService.ensureRunning(getApplication())
    }

    fun retry(task: DownloadTask) {
        repository.retry(task.id)
        DownloadService.ensureRunning(getApplication())
    }

    fun remove(task: DownloadTask, deleteFile: Boolean) = repository.remove(task.id, deleteFile)

    fun resumeAll() {
        repository.resumeAll()
        DownloadService.ensureRunning(getApplication())
    }

    fun pauseAll() = repository.pauseAll()

    fun updateSettings(settings: DownloadSettings) = repository.updateSettings(settings)

    fun fileOf(task: DownloadTask): File = File(task.filePath)

    fun downloadDirPath(): String = repository.downloadDir().absolutePath
}
