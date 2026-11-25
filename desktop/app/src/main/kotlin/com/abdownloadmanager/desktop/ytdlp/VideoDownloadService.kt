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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
    private val streamRunner: YtDlpDownloadRunner,
    private val defaultFolder: () -> String,
    private val stateFile: File,
    /** A folder for the files that yt-dlp downloads before they are handed to the download list. */
    private val tempRoot: File,
) {
    /** A video that yt-dlp is downloading (HLS or DASH), for the progress notification. */
    data class ActiveVideoJob(val id: String, val title: String, val percent: Int)

    private val _activeJobs = MutableStateFlow<List<ActiveVideoJob>>(emptyList())
    val activeJobs: StateFlow<List<ActiveVideoJob>> get() = _activeJobs

    /** A finished video that the download engine is copying into the download list. */
    private class Handover(val server: LocalFileServer, val tempDir: File, val pageUrl: String?, val fileName: String)

    private val handovers = mutableMapOf<Long, Handover>()

    @Serializable
    internal data class VideoJob(
        val outputPath: String,
        val videoId: Long,
        val audioId: Long,
        val pageUrl: String? = null,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    private val lock = Mutex()

    private val _events = MutableSharedFlow<VideoEvent>(extraBufferCapacity = 16)

    /** What happens to the videos (searching, failed, completed), so the app can inform the user. */
    val events: SharedFlow<VideoEvent> = _events

    /** Progress of the first time download of yt-dlp and ffmpeg. */
    val toolDownloadState: StateFlow<ToolDownloadState> get() = ytDlpClient.toolDownloadState

    private fun report(failure: Throwable) {
        failure.printStackTrace()
        _events.tryEmit(
            VideoEvent.Failed(
                kind = VideoErrors.classify(failure),
                message = failure.message?.lineSequence()?.firstOrNull { it.isNotBlank() }?.take(300),
            )
        )
    }
    private val jobs = mutableListOf<VideoJob>()

    /** Call once at startup, after the download system has booted. */
    fun boot() {
        loadState()
        cleanOldTempFolders()
        scope.launch {
            // parts that finished while the app was closed
            val snapshot = lock.withLock { jobs.toList() }
            snapshot.forEach { tryFinish(it) }
        }
        scope.launch {
            // websites change often, so yt-dlp must be kept up to date (it only updates when it is due)
            while (true) {
                try {
                    ytDlpClient.updateIfDue()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(UPDATE_CHECK_EVERY_MILLIS)
            }
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
        _events.tryEmit(VideoEvent.Searching)
        scope.launch {
            try {
                val info = ytDlpClient.fetchInfo(url)
                rejectLiveStream(info)
                val options = YtDlpFormatSelector.buildOptions(info)
                val option = qualityKey?.let { key -> options.firstOrNull { it.key == key } }
                    ?: YtDlpFormatSelector.defaultOption(options)
                    ?: throw YtDlpException("No downloadable video was found on this page")
                downloadOption(info, option, folder ?: defaultFolder())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report(e)
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
        _events.tryEmit(VideoEvent.Searching)
        scope.launch {
            try {
                val info = ytDlpClient.fetchInfo(url)
                rejectLiveStream(info)
                if (YtDlpFormatSelector.buildOptions(info).isEmpty()) {
                    throw YtDlpException("No downloadable video was found on this page")
                }
                // ffmpeg is needed to join the video and the sound. Prepare it while the user chooses a quality.
                scope.launch {
                    try {
                        ytDlpClient.prepareFfmpeg()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                onReady(info, folder ?: defaultFolder())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report(e)
            }
        }
    }

    private fun rejectLiveStream(info: YtDlpInfo) {
        if (info.isLive == true) {
            throw YtDlpException("This is a live stream. Live streams are not supported")
        }
    }

    suspend fun downloadOption(info: YtDlpInfo, option: YtDlpQualityOption, folder: String) {
        try {
            startDownload(info, option, folder)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report(e)
        }
    }

    private suspend fun startDownload(info: YtDlpInfo, option: YtDlpQualityOption, folder: String) {
        if (option.needsYtDlp) {
            // HLS and DASH are downloaded by yt-dlp itself
            downloadWithYtDlp(info, option, folder)
            return
        }
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
        // fail now, not after the whole video was downloaded
        ytDlpClient.requireFfmpeg()
        val baseName = finalFile.nameWithoutExtension
        val videoId = addPart(video, info, folder, "$baseName.f${video.formatId}.${video.ext}")
        val audioId = addPart(audio, info, folder, "$baseName.f${audio.formatId}.${audio.ext}")
        lock.withLock {
            jobs.add(VideoJob(finalFile.absolutePath, videoId, audioId, info.webpageUrl))
            saveState()
        }
    }

    /**
     * 1. yt-dlp downloads the stream (and joins the sound) into a private temporary folder.
     * 2. The finished file is served from this computer, and the download engine "downloads" it into the
     *    download folder. So it appears in the list like any other download.
     */
    private suspend fun downloadWithYtDlp(info: YtDlpInfo, option: YtDlpQualityOption, folder: String) {
        val pageUrl = info.webpageUrl
            ?: throw YtDlpException("This page has no address that yt-dlp can use")
        // yt-dlp needs ffmpeg to read and join these streams
        ytDlpClient.requireFfmpeg()

        val title = sanitizeFileName(info.title)
        val jobId = UUID.randomUUID().toString()
        val tempDir = File(tempRoot, jobId)
        var server: LocalFileServer? = null
        var handedOver = false
        _activeJobs.update { it + ActiveVideoJob(jobId, title, 0) }
        try {
            val produced = streamRunner.download(
                pageUrl = pageUrl,
                formatIds = listOfNotNull(option.video?.formatId, option.audio?.formatId),
                container = option.container,
                outputDir = tempDir,
                baseName = "video",
                onProgress = { progress ->
                    _activeJobs.update { jobs ->
                        jobs.map { if (it.id == jobId) it.copy(percent = progress.percent) else it }
                    }
                },
            )
            // the extension can differ from the planned one (mkv is used when mp4 does not fit)
            val finalName = uniqueFile(File(folder), title, produced.extension).name
            server = LocalFileServer(produced, finalName)
            val id = integrationHandler.addDownload(
                NewDownloadTask(
                    downloadSource = HttpDownloadCredentialsFromIntegration(
                        link = server.url,
                        headers = null,
                        downloadPage = pageUrl,
                        suggestedName = finalName,
                    ),
                    folder = folder,
                    name = finalName,
                    startDownload = true,
                )
            )
            lock.withLock { handovers[id] = Handover(server, tempDir, pageUrl, finalName) }
            handedOver = true
        } finally {
            _activeJobs.update { jobs -> jobs.filterNot { it.id == jobId } }
            if (!handedOver) {
                server?.close()
                tempDir.deleteRecursively()
            }
        }
    }

    /** The engine finished copying the video: the temporary file and the local server are no longer needed. */
    private suspend fun finishHandover(downloadId: Long) {
        val handover = lock.withLock { handovers.remove(downloadId) } ?: return
        handover.server.close()
        handover.tempDir.deleteRecursively()
        try {
            // the address of the item is a local one, show the page of the video instead
            handover.pageUrl?.let { pageUrl ->
                downloadSystem.downloadManager.updateDownloadItem(
                    id = downloadId,
                    downloadJobExtraConfig = null,
                    updater = { it.link = pageUrl },
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
        }
        _events.tryEmit(VideoEvent.Completed(handover.fileName))
    }

    /** Folders of downloads that were interrupted (the app was closed) are removed after a day. */
    private fun cleanOldTempFolders() {
        val limit = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        tempRoot.listFiles()?.filter { it.isDirectory && it.lastModified() < limit }?.forEach {
            it.deleteRecursively()
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
        finishHandover(downloadId)
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

            val videoFile = downloadSystem.getDownloadFile(video)
            val audioFile = downloadSystem.getDownloadFile(audio)
            val finalFile = File(job.outputPath)
            val joinedFile = File(
                finalFile.absoluteFile.parentFile,
                "${finalFile.nameWithoutExtension}.joined.${finalFile.extension}",
            )
            try {
                muxer.merge(video = videoFile, audio = audioFile, output = joinedFile)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report(e)
                // keep the parts and try again next time
                lock.withLock {
                    jobs.add(job)
                    saveState()
                }
                return
            }
            showFinalVideoInList(job, videoFile, joinedFile, finalFile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * The merged file takes the place of the video part, and that download is renamed to the final name.
     * So the list shows one finished download (the final video) instead of the two parts.
     */
    private suspend fun showFinalVideoInList(
        job: VideoJob,
        videoPartFile: File,
        joinedFile: File,
        finalFile: File,
    ) {
        try {
            Files.move(joinedFile.toPath(), videoPartFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            val newSize = videoPartFile.length()
            // renaming a download moves its file, so the merged video ends up as `finalFile`
            downloadSystem.downloadManager.updateDownloadItem(
                id = job.videoId,
                downloadJobExtraConfig = null,
                updater = {
                    it.name = finalFile.name
                    it.contentLength = newSize
                    job.pageUrl?.let { pageUrl -> it.downloadPage = pageUrl }
                },
            )
            removePart(job.audioId)
            _events.tryEmit(VideoEvent.Completed(finalFile.name))
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

    private companion object {
        const val UPDATE_CHECK_EVERY_MILLIS = 6 * 60 * 60 * 1000L
    }
}
