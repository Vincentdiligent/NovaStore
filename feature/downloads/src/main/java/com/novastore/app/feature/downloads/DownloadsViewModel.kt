package com.novastore.app.feature.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novastore.app.core.downloader.api.DownloadTaskInfo
import com.novastore.app.domain.usecase.GetDownloadQueueUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class DownloadsUiState(
    val loading: Boolean = true,
    val active: List<DownloadTaskInfo> = emptyList(),
    val queued: List<DownloadTaskInfo> = emptyList(),
    val completed: List<DownloadTaskInfo> = emptyList(),
    val failed: List<DownloadTaskInfo> = emptyList(),
)

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    downloadQueue: GetDownloadQueueUseCase,
    private val downloadRequester: com.novastore.app.core.downloader.api.DownloadRequester,
    private val settingsDataStore: com.novastore.app.core.datastore.SettingsDataStore,
) : ViewModel() {

    val uiState: StateFlow<DownloadsUiState> = downloadQueue()
        .map { tasks ->
            DownloadsUiState(
                loading = false,
                active = tasks.filter { it.state == com.novastore.app.core.model.DownloadState.DOWNLOADING || it.state == com.novastore.app.core.model.DownloadState.VERIFYING },
                queued = tasks.filter { it.state == com.novastore.app.core.model.DownloadState.QUEUED || it.state == com.novastore.app.core.model.DownloadState.PAUSED },
                completed = tasks.filter { it.state == com.novastore.app.core.model.DownloadState.COMPLETED },
                failed = tasks.filter { it.state == com.novastore.app.core.model.DownloadState.FAILED || it.state == com.novastore.app.core.model.DownloadState.CANCELLED },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DownloadsUiState())

    /** Completed-download auto-cleanup age in days (0 = keep forever). */
    val autoCleanDays: StateFlow<Int> = settingsDataStore.downloadsAutoCleanDays
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun pause(packageName: String) {
        viewModelScope.launch { downloadRequester.pause(packageName) }
    }

    fun resume(packageName: String) {
        viewModelScope.launch { downloadRequester.resume(packageName) }
    }

    fun cancel(packageName: String) {
        viewModelScope.launch { downloadRequester.cancel(packageName) }
    }

    fun retry(packageName: String) {
        viewModelScope.launch { downloadRequester.retry(packageName) }
    }

    /** Removes every completed download (rows + APK files). */
    fun clearCompleted() {
        viewModelScope.launch { downloadRequester.clearCompleted() }
    }

    /** Removes failed and cancelled entries from the list. */
    fun clearFailed() {
        viewModelScope.launch { downloadRequester.clearFinished() }
    }

    fun setAutoCleanDays(days: Int) {
        viewModelScope.launch { settingsDataStore.setDownloadsAutoCleanDays(days) }
    }
}
