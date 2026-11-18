package com.abdownloadmanager.desktop.ytdlp

import kotlinx.serialization.json.Json
import java.io.File

/**
 * Runs yt-dlp to find out what can be downloaded from a web page.
 * yt-dlp is only used to discover the links. The actual download is done by the app's own engine.
 */
class YtDlpClient(
    private val toolManager: YtDlpToolManager,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    suspend fun version(): String {
        val ytDlp = toolManager.ensureYtDlp()
        val result = ProcessRunner.run(listOf(ytDlp.absolutePath, "--version"), 30_000)
        if (result.exitCode != 0) {
            throw YtDlpException("yt-dlp could not start", result.stderr)
        }
        return result.stdout.trim()
    }

    /**
     * Returns the title and the available formats of a single video page.
     * Playlists are not expanded here (`--no-playlist`).
     *
     * @param cookiesFile an optional cookies file in the Netscape format (for pages that need a login).
     */
    suspend fun fetchInfo(url: String, cookiesFile: File? = null): YtDlpInfo {
        return try {
            fetchInfoOnce(url, cookiesFile)
        } catch (e: YtDlpException) {
            if (!VideoErrors.looksLikeOutdatedYtDlp(e)) throw e
            // A website changed its pages. A newer yt-dlp usually understands it, so update and try once more.
            toolManager.updateNow() ?: throw e
            fetchInfoOnce(url, cookiesFile)
        }
    }

    /** Checks for a newer yt-dlp when the last check is old. Call it from time to time. */
    suspend fun updateIfDue() {
        toolManager.updateIfDue()
    }

    private suspend fun fetchInfoOnce(url: String, cookiesFile: File?): YtDlpInfo {
        val ytDlp = toolManager.ensureYtDlp()
        val command = buildList {
            add(ytDlp.absolutePath)
            add("-J")
            add("--no-playlist")
            add("--no-warnings")
            add("--encoding")
            add("utf-8")
            if (cookiesFile != null) {
                add("--cookies")
                add(cookiesFile.absolutePath)
            }
            // everything after "--" is a URL, never an option
            add("--")
            add(url)
        }
        val result = ProcessRunner.run(command, FETCH_TIMEOUT_MILLIS)
        if (result.exitCode != 0) {
            throw YtDlpException(
                message = result.stderr.lines().lastOrNull { it.isNotBlank() }?.take(400)
                    ?: "yt-dlp failed with exit code ${result.exitCode}",
                details = result.stderr.take(4000),
            )
        }
        return try {
            json.decodeFromString<YtDlpInfo>(result.stdout)
        } catch (e: Exception) {
            throw YtDlpException("The output of yt-dlp could not be read", e.message)
        }
    }

    private companion object {
        const val FETCH_TIMEOUT_MILLIS = 90_000L
    }
}
