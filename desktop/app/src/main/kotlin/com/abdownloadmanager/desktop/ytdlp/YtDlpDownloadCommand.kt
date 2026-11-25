package com.abdownloadmanager.desktop.ytdlp

import java.io.File

/** How far a yt-dlp download is. */
data class YtDlpProgress(
    /** 0 to 99. It becomes 100 only when the file is finished. */
    val percent: Int,
    val speedBytesPerSecond: Long?,
)

/**
 * Builds the command line that makes yt-dlp download a stream (HLS or DASH), and reads its output.
 * It is separate from the code that runs the process, so that it can be tested without starting anything.
 */
object YtDlpDownloadCommand {

    private const val PROGRESS_PREFIX = "EMOOP|"
    private const val FILE_PREFIX = "EMOOFILE|"

    /**
     * @param formatIds the formats to download. Two ids (video and audio) are joined by yt-dlp.
     * @param container the type of the final file: mp4, mkv, webm...
     */
    fun build(
        ytDlp: File,
        ffmpeg: File?,
        pageUrl: String,
        formatIds: List<String>,
        container: String,
        outputDir: File,
        baseName: String,
        cookiesFile: File? = null,
    ): List<String> = buildList {
        add(ytDlp.absolutePath)
        add("--no-playlist")
        add("--no-warnings")
        add("--encoding")
        add("utf-8")
        add("-f")
        add(formatIds.joinToString("+"))
        add("--merge-output-format")
        add(container)
        add("-o")
        // "%" has a meaning in yt-dlp templates, so the folder and the name must not contain a single one
        add(File(outputDir, baseName).absolutePath.replace("%", "%%") + ".%(ext)s")
        add("--newline")
        add("--progress")
        add("--progress-template")
        add(
            "download:${PROGRESS_PREFIX}" +
                    "%(progress.downloaded_bytes)s|%(progress.total_bytes)s|%(progress.total_bytes_estimate)s|" +
                    "%(progress.speed)s|%(progress.eta)s|%(progress.fragment_index)s|%(progress.fragment_count)s"
        )
        add("--concurrent-fragments")
        add("4")
        // the real path of the finished file, after merging
        add("--print")
        add("after_move:${FILE_PREFIX}%(filepath)s")
        if (ffmpeg != null) {
            add("--ffmpeg-location")
            add(ffmpeg.absolutePath)
        }
        if (cookiesFile != null) {
            add("--cookies")
            add(cookiesFile.absolutePath)
        }
        // everything after "--" is an address, never an option
        add("--")
        add(pageUrl)
    }

    /** Reads a progress line, or returns null when the line is something else (or has no numbers yet). */
    fun parseProgress(line: String): YtDlpProgress? {
        if (!line.startsWith(PROGRESS_PREFIX)) return null
        val parts = line.removePrefix(PROGRESS_PREFIX).split("|")
        if (parts.size < 7) return null
        fun number(index: Int): Double? = parts[index].trim().toDoubleOrNull()

        val downloaded = number(0)
        val total = number(1) ?: number(2)
        val fragmentIndex = number(5)
        val fragmentCount = number(6)

        val percent = when {
            downloaded != null && total != null && total > 0 -> downloaded / total * 100.0
            fragmentIndex != null && fragmentCount != null && fragmentCount > 0 -> fragmentIndex / fragmentCount * 100.0
            else -> return null
        }
        return YtDlpProgress(
            percent = percent.toInt().coerceIn(0, 99),
            speedBytesPerSecond = number(3)?.toLong(),
        )
    }

    /** The path of the finished file, from the line that yt-dlp prints at the end. */
    fun parseFinalPath(line: String): String? =
        line.takeIf { it.startsWith(FILE_PREFIX) }?.removePrefix(FILE_PREFIX)?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Some streams can not be put in an mp4 file (for example when the codecs do not fit).
     * Then the same download is tried again with mkv, which accepts everything.
     */
    fun looksLikeConversionFailure(output: String): Boolean {
        val text = output.lowercase()
        return "postprocessing" in text || "conversion failed" in text || "could not write header" in text
    }
}
