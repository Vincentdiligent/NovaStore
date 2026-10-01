package com.novastore.app.domain.usecase

import com.novastore.app.core.common.AppResult
import com.novastore.app.core.downloader.api.DownloadRequester
import com.novastore.app.core.downloader.api.DownloadTaskInfo
import javax.inject.Inject

/** Observes the persisted + live download queue. */
class GetDownloadQueueUseCase @Inject constructor(
    private val downloadRequester: DownloadRequester,
) {
    operator fun invoke(): kotlinx.coroutines.flow.Flow<List<DownloadTaskInfo>> =
        downloadRequester.observeQueue()
}
