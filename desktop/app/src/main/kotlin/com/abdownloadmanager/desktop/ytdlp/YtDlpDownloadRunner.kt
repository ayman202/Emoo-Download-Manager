package com.abdownloadmanager.desktop.ytdlp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Lets yt-dlp itself download a stream (HLS or DASH). yt-dlp knows how to read their playlists, how to
 * decrypt them, which headers a website needs, and how to join the pieces.
 *
 * The process is stopped when the coroutine is cancelled.
 */
class YtDlpDownloadRunner(
    private val toolManager: YtDlpToolManager,
) {
    /**
     * @param outputDir a folder that belongs only to this download (it is emptied if a second try is needed).
     * @return the finished file, inside [outputDir].
     */
    suspend fun download(
        pageUrl: String,
        formatIds: List<String>,
        container: String,
        outputDir: File,
        baseName: String,
        onProgress: (YtDlpProgress) -> Unit,
    ): File {
        val ytDlp = toolManager.ensureYtDlp()
        val ffmpeg = toolManager.findFfmpeg()
        outputDir.mkdirs()

        var failure: YtDlpException? = null
        // mkv accepts any codec, so it is the second try when the first container does not fit the stream
        for (candidate in listOf(container, "mkv").distinct()) {
            try {
                return runOnce(ytDlp, ffmpeg, pageUrl, formatIds, candidate, outputDir, baseName, onProgress)
            } catch (e: YtDlpException) {
                failure = e
                val text = listOfNotNull(e.message, e.details).joinToString("\n")
                if (!YtDlpDownloadCommand.looksLikeConversionFailure(text)) throw e
                outputDir.listFiles()?.forEach { it.deleteRecursively() }
            }
        }
        throw failure ?: YtDlpException("yt-dlp could not download the video")
    }

    private suspend fun runOnce(
        ytDlp: File,
        ffmpeg: File?,
        pageUrl: String,
        formatIds: List<String>,
        container: String,
        outputDir: File,
        baseName: String,
        onProgress: (YtDlpProgress) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val command = YtDlpDownloadCommand.build(ytDlp, ffmpeg, pageUrl, formatIds, container, outputDir, baseName)
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        process.outputStream.close()

        val job: Job = coroutineContext[Job]!!
        // stops yt-dlp at once when the download is cancelled (reading its output can not be interrupted)
        val stopOnCancel = job.invokeOnCompletion {
            if (process.isAlive) process.destroyForcibly()
        }
        try {
            val tail = ArrayDeque<String>()
            var finalPath: String? = null
            var best = -1

            process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    job.ensureActive()
                    YtDlpDownloadCommand.parseProgress(line)?.let { progress ->
                        // with several fragments in parallel the numbers jump, so it only moves forward
                        if (progress.percent > best) {
                            best = progress.percent
                            onProgress(progress)
                        }
                    }
                    YtDlpDownloadCommand.parseFinalPath(line)?.let { finalPath = it }
                    if (!line.startsWith("EMOOP|")) {
                        tail.addLast(line)
                        if (tail.size > 30) tail.removeFirst()
                    }
                }
            }

            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw YtDlpException(
                    message = tail.lastOrNull { it.startsWith("ERROR") }?.take(400)
                        ?: tail.lastOrNull { it.isNotBlank() }?.take(400)
                        ?: "yt-dlp failed with exit code $exitCode",
                    details = tail.joinToString("\n"),
                )
            }

            val file = finalPath?.let { File(it) }?.takeIf { it.isFile }
                ?: outputDir.listFiles()
                    ?.filter { it.isFile && it.name.startsWith(baseName) && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                    ?.maxByOrNull { it.length() }
                ?: throw YtDlpException("yt-dlp finished, but the video file was not found")
            file
        } finally {
            stopOnCancel.dispose()
            if (process.isAlive) process.destroyForcibly()
        }
    }
}
