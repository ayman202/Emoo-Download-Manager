package com.abdownloadmanager.desktop.ytdlp

/** Why a video could not be downloaded, in words that a normal user understands. */
enum class VideoErrorKind {
    /** The page asks for a sign in, an age check or a bot check. */
    NeedsLogin,

    /** yt-dlp found no video on the page, or the website is not supported. */
    NoVideo,

    /** The video is private, removed, or blocked in the country. */
    Unavailable,

    /** The video is protected and can not be downloaded. */
    Drm,

    /** The website could not be reached. */
    Network,

    /** ffmpeg is needed but is not installed (Linux and macOS). */
    FfmpegMissing,

    /** yt-dlp or ffmpeg could not be downloaded. */
    ToolsDownload,

    /** The video and the audio were downloaded, but joining them failed. */
    MergeFailed,

    Unknown,
}

/** What the video downloader tells the rest of the app, so that the user can be informed. */
sealed interface VideoEvent {
    /** A request arrived and the page is being analyzed (it can take a few seconds). */
    data object Searching : VideoEvent

    data class Failed(
        val kind: VideoErrorKind,
        /** The first line of the technical message, shown only for [VideoErrorKind.Unknown]. */
        val message: String?,
    ) : VideoEvent

    /** The video was downloaded, joined and added to the list. */
    data class Completed(val fileName: String) : VideoEvent
}

object VideoErrors {

    fun classify(error: Throwable): VideoErrorKind {
        return when (error) {
            is YtDlpException -> classifyText(listOfNotNull(error.message, error.details).joinToString("\n"))
            is java.io.IOException -> VideoErrorKind.ToolsDownload
            else -> VideoErrorKind.Unknown
        }
    }

    /** yt-dlp prints these when a website changed in a way that the installed yt-dlp does not understand yet. */
    fun looksLikeOutdatedYtDlp(error: YtDlpException): Boolean {
        val t = listOfNotNull(error.message, error.details).joinToString("\n").lowercase()
        return listOf(
            "unable to extract", "please report this issue", "signature extraction failed",
            "nsig extraction failed", "unable to find video info", "unable to parse",
        ).any { it in t }
    }

    /** The order matters: the first group that matches wins. */
    fun classifyText(text: String): VideoErrorKind {
        val t = text.lowercase()
        fun has(vararg words: String) = words.any { it in t }

        return when {
            has("ffmpeg is not installed", "ffmpeg.exe was not found") -> VideoErrorKind.FfmpegMissing
            has("download failed with http", "the download was cut off", "too small to be valid") -> VideoErrorKind.ToolsDownload
            has("ffmpeg could not merge") -> VideoErrorKind.MergeFailed
            has("drm") -> VideoErrorKind.Drm
            has(
                "sign in", "log in", "login required", "use --cookies", "registered users",
                "members-only", "members only", "confirm your age", "not a bot",
            ) -> VideoErrorKind.NeedsLogin

            has(
                "video unavailable", "this video is not available", "has been removed",
                "is private", "not available in your country", "geo restrict", "copyright",
                "http error 404", "http error 410", "account associated with this video has been terminated",
            ) -> VideoErrorKind.Unavailable

            has(
                "unsupported url", "no video formats found", "no video could be found",
                "no downloadable video",
            ) -> VideoErrorKind.NoVideo

            has(
                "unable to download", "failed to resolve", "name or service not known",
                "temporary failure in name resolution", "getaddrinfo", "timed out",
                "took too long", "connection refused", "connection reset", "network is unreachable",
                "certificate verify failed",
            ) -> VideoErrorKind.Network

            else -> VideoErrorKind.Unknown
        }
    }
}
