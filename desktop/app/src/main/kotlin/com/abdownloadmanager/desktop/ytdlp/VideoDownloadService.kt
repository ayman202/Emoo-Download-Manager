package com.abdownloadmanager.desktop.ytdlp

import com.abdownloadmanager.integration.IntegrationHandler
import com.abdownloadmanager.integration.model.HttpDownloadCredentialsFromIntegration
import com.abdownloadmanager.integration.model.NewDownloadTask
import com.abdownloadmanager.shared.util.DownloadSystem
import ir.amirab.downloader.DownloadManagerEvents
import ir.amirab.downloader.downloaditem.DownloadStatus
import ir.amirab.downloader.downloaditem.EmptyContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Downloads a video from a web page:
 *  1. yt-dlp finds the available qualities of the page.
 *  2. The picture and the sound are downloaded by the app's own fast engine (as two normal downloads).
 *  3. When both are finished, ffmpeg joins them into one file and the two parts are removed.
 *
 * The waiting merges are saved in [stateFile], so they are not lost when the app is closed.
 */
class VideoDownloadService(
    private val scope: CoroutineScope,
    private val downloadSystem: DownloadSystem,
    private val integrationHandler: IntegrationHandler,
    private val ytDlpClient: YtDlpClient,
    private val muxer: FfmpegMuxer,
    private val defaultFolder: () -> String,
    private val stateFile: File,
) {
    @Serializable
    internal data class VideoJob(
        val outputPath: String,
        val videoId: Long,
        val audioId: Long,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    private val lock = Mutex()
    private val jobs = mutableListOf<VideoJob>()

    /** Call once at startup, after the download system has booted. */
    fun boot() {
        loadState()
        scope.launch {
            // parts that finished while the app was closed
            val snapshot = lock.withLock { jobs.toList() }
            snapshot.forEach { tryFinish(it) }
        }
        scope.launch {
            downloadSystem.downloadEvents.collect { event ->
                if (event is DownloadManagerEvents.OnJobCompleted) {
                    onPartCompleted(event.downloadItem.id)
                }
            }
        }
    }

    /**
     * Finds the video on [url] and starts downloading it. It returns immediately.
     * [qualityKey] is a key of [YtDlpQualityOption] such as `720p|H.264|mp4`. The default quality is used when it is null.
     */
    fun requestDownload(url: String, qualityKey: String?, folder: String?) {
        scope.launch {
            try {
                val info = ytDlpClient.fetchInfo(url)
                val options = YtDlpFormatSelector.buildOptions(info)
                val option = qualityKey?.let { key -> options.firstOrNull { it.key == key } }
                    ?: YtDlpFormatSelector.defaultOption(options)
                    ?: throw YtDlpException("No downloadable video was found on this page")
                downloadOption(info, option, folder ?: defaultFolder())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Finds the video on [url] and calls [onReady] with the result, so the user can choose a quality.
     * It returns immediately.
     */
    fun requestWithChoice(
        url: String,
        folder: String?,
        onReady: (info: YtDlpInfo, folder: String) -> Unit,
    ) {
        scope.launch {
            try {
                val info = ytDlpClient.fetchInfo(url)
                onReady(info, folder ?: defaultFolder())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    suspend fun downloadOption(info: YtDlpInfo, option: YtDlpQualityOption, folder: String) {
        val title = sanitizeFileName(info.title)
        val finalFile = uniqueFile(File(folder), title, option.container)

        if (!option.needsMerge) {
            // one file that already contains everything: it is just a normal download
            val format = option.video ?: option.audio ?: return
            addPart(format, info, folder, finalFile.name)
            return
        }

        val video = option.video ?: return
        val audio = option.audio ?: return
        val baseName = finalFile.nameWithoutExtension
        val videoId = addPart(video, info, folder, "$baseName.f${video.formatId}.${video.ext}")
        val audioId = addPart(audio, info, folder, "$baseName.f${audio.formatId}.${audio.ext}")
        lock.withLock {
            jobs.add(VideoJob(finalFile.absolutePath, videoId, audioId))
            saveState()
        }
    }

    private suspend fun addPart(
        format: YtDlpFormat,
        info: YtDlpInfo,
        folder: String,
        name: String,
    ): Long {
        return integrationHandler.addDownload(
            NewDownloadTask(
                downloadSource = HttpDownloadCredentialsFromIntegration(
                    link = format.url.orEmpty(),
                    headers = format.httpHeaders
                        .filterKeys { !it.equals("Accept-Encoding", ignoreCase = true) }
                        .ifEmpty { null },
                    downloadPage = info.webpageUrl,
                    suggestedName = name,
                ),
                folder = folder,
                name = name,
                startDownload = true,
            )
        )
    }

    private suspend fun onPartCompleted(downloadId: Long) {
        val job = lock.withLock {
            jobs.firstOrNull { it.videoId == downloadId || it.audioId == downloadId }
        } ?: return
        tryFinish(job)
    }

    private suspend fun tryFinish(job: VideoJob) {
        try {
            val video = downloadSystem.getDownloadItemById(job.videoId)
            val audio = downloadSystem.getDownloadItemById(job.audioId)
            if (video == null || audio == null) {
                // the user deleted one of the parts, so there is nothing to merge
                forget(job)
                return
            }
            if (video.status != DownloadStatus.Completed || audio.status != DownloadStatus.Completed) {
                return
            }
            // only one caller may merge a job (both parts can finish at the same moment)
            val claimed = lock.withLock {
                jobs.remove(job).also { removed -> if (removed) saveState() }
            }
            if (!claimed) return

            try {
                muxer.merge(
                    video = downloadSystem.getDownloadFile(video),
                    audio = downloadSystem.getDownloadFile(audio),
                    output = File(job.outputPath),
                )
                removePart(job.videoId)
                removePart(job.audioId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                // keep the parts and try again next time
                lock.withLock {
                    jobs.add(job)
                    saveState()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun forget(job: VideoJob) {
        lock.withLock {
            if (jobs.remove(job)) saveState()
        }
    }

    private suspend fun removePart(id: Long) {
        downloadSystem.removeDownload(
            id = id,
            alsoRemoveFile = true,
            context = EmptyContext,
        )
    }

    private fun loadState() {
        try {
            if (stateFile.isFile) {
                jobs.addAll(
                    json.decodeFromString(ListSerializer(VideoJob.serializer()), stateFile.readText())
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveState() {
        try {
            stateFile.absoluteFile.parentFile.mkdirs()
            stateFile.writeText(json.encodeToString(ListSerializer(VideoJob.serializer()), jobs.toList()))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun sanitizeFileName(title: String?): String {
        val cleaned = title.orEmpty()
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(120)
            .trim()
            .trimEnd('.')
            .trim()
        return cleaned.ifBlank { "video" }
    }

    /** `name.ext`, or `name (1).ext`, `name (2).ext`... when the file already exists or is already planned. */
    private fun uniqueFile(folder: File, name: String, extension: String): File {
        val planned = jobs.map { it.outputPath }.toSet()
        var candidate = File(folder, "$name.$extension")
        var counter = 1
        while (candidate.exists() || candidate.absolutePath in planned) {
            candidate = File(folder, "$name ($counter).$extension")
            counter++
        }
        return candidate
    }
}
