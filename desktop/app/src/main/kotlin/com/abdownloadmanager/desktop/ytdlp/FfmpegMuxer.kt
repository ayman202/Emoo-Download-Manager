package com.abdownloadmanager.desktop.ytdlp

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Joins a video-only file and an audio-only file into one file, without re-encoding (`-c copy`). */
class FfmpegMuxer(
    private val toolManager: YtDlpToolManager,
) {
    /** The container of the result is chosen by the extension of [output]. */
    suspend fun merge(video: File, audio: File, output: File) {
        val ffmpeg = toolManager.ensureFfmpeg()
            ?: throw YtDlpException("ffmpeg is not installed. Please install it and try again")

        val outputFile = output.absoluteFile
        outputFile.parentFile.mkdirs()
        val extension = outputFile.extension
        val temp = File(outputFile.parentFile, "${outputFile.nameWithoutExtension}.merging.$extension")

        val command = buildList {
            add(ffmpeg.absolutePath)
            add("-y")
            add("-hide_banner")
            add("-loglevel")
            add("error")
            add("-i")
            add(video.absolutePath)
            add("-i")
            add(audio.absolutePath)
            add("-map")
            add("0:v:0")
            add("-map")
            add("1:a:0")
            add("-c")
            add("copy")
            if (extension.equals("mp4", ignoreCase = true)) {
                // lets the video start playing before it is fully downloaded
                add("-movflags")
                add("+faststart")
            }
            add(temp.absolutePath)
        }

        val result = ProcessRunner.run(command, MERGE_TIMEOUT_MILLIS)
        if (result.exitCode != 0 || !temp.isFile) {
            temp.delete()
            throw YtDlpException("ffmpeg could not merge the files", result.stderr.take(4000))
        }
        Files.move(temp.toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private companion object {
        const val MERGE_TIMEOUT_MILLIS = 30 * 60_000L
    }
}
