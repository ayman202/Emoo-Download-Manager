package com.abdownloadmanager.android.util

import com.abdownloadmanager.android.storage.AppSettingsStorage
import com.abdownloadmanager.shared.util.DownloadSystem
import ir.amirab.downloader.DownloadManagerEvents
import ir.amirab.downloader.downloaditem.DownloadJobStatus
import ir.amirab.downloader.downloaditem.contexts.ResumedBy
import ir.amirab.downloader.downloaditem.contexts.StoppedBy
import ir.amirab.downloader.downloaditem.contexts.User
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Implements the "Download only on Wi-Fi" setting.
 *
 * While the setting is enabled and the device is NOT on an unmetered network:
 *  - active queues are stopped
 *  - active downloads are paused
 *  - any download that tries to start is paused immediately
 *
 * When Wi-Fi comes back (or the setting is turned off) everything that was
 * stopped or paused by this guard is started again.
 */
class WifiOnlyDownloadGuard(
    private val scope: CoroutineScope,
    private val networkMonitor: AndroidNetworkMonitor,
    private val appSettingsStorage: AppSettingsStorage,
    private val downloadSystem: DownloadSystem,
) {
    private val downloadManager get() = downloadSystem.downloadManager
    private val queueManager get() = downloadSystem.queueManager

    private val lock = Mutex()
    private val downloadsPausedByGuard = mutableSetOf<Long>()
    private val queuesStoppedByGuard = mutableSetOf<Long>()
    private var job: Job? = null

    @Synchronized
    fun start() {
        if (job != null) return
        networkMonitor.start()
        job = scope.launch {
            combine(
                appSettingsStorage.onlyDownloadOnWifi,
                networkMonitor.isOnUnmeteredNetwork,
            ) { enabled, onUnmetered ->
                enabled && !onUnmetered
            }
                .distinctUntilChanged()
                .collectLatest { blocked ->
                    if (blocked) {
                        block()
                    } else {
                        release()
                    }
                }
        }
    }

    /**
     * Runs until it is cancelled (when the blocked state changes).
     */
    private suspend fun block() {
        safely { stopActiveQueues() }
        safely { pauseActiveDownloads() }
        downloadManager.listOfJobsEvents.collect { event ->
            when (event) {
                is DownloadManagerEvents.OnJobStarting,
                is DownloadManagerEvents.OnJobStarted -> {
                    safely { pauseDownload(event.downloadItem.id) }
                }

                else -> Unit
            }
        }
    }

    private suspend fun stopActiveQueues() {
        val activeQueues = queueManager.getAll().filter { it.isQueueActive }
        lock.withLock {
            queuesStoppedByGuard.addAll(activeQueues.map { it.id })
        }
        activeQueues.forEach { queue ->
            safely { queue.stopAsync() }
        }
    }

    private suspend fun pauseActiveDownloads() {
        val activeIds = downloadManager.downloadJobs
            .filter { it.status.value is DownloadJobStatus.IsActive }
            .map { it.id }
        activeIds.forEach { id ->
            safely { pauseDownload(id) }
        }
    }

    private suspend fun pauseDownload(id: Long) {
        lock.withLock {
            downloadsPausedByGuard.add(id)
        }
        downloadManager.pause(id, StoppedBy(User))
    }

    /**
     * Starts again everything this guard stopped.
     * It must not be interrupted halfway, otherwise some items could stay paused forever.
     */
    private suspend fun release() {
        withContext(NonCancellable) {
            val (queueIds, downloadIds) = lock.withLock {
                val queues = queuesStoppedByGuard.toList()
                val downloads = downloadsPausedByGuard.toList()
                queuesStoppedByGuard.clear()
                downloadsPausedByGuard.clear()
                queues to downloads
            }
            queueIds.forEach { queueId ->
                safely { queueManager.getQueue(queueId).start() }
            }
            downloadIds.forEach { downloadId ->
                safely { downloadManager.resume(downloadId, ResumedBy(User)) }
            }
        }
    }

    private suspend fun safely(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}