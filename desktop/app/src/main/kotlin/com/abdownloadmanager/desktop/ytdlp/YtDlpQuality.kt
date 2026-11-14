package com.abdownloadmanager.desktop.ytdlp

/**
 * One choice that the user can pick, for example "1080p MP4 (H.264)".
 *
 * - [video] is null for audio-only options.
 * - [audio] is null when the video file already contains the sound (no merge needed).
 */
data class YtDlpQualityOption(
    /** A stable key such as `1080p|H.264|mp4`. Links expire, so the key is used to find the same quality again later. */
    val key: String,
    val label: String,
    val video: YtDlpFormat?,
    val audio: YtDlpFormat?,
    /** The extension of the final file: mp4, webm, mkv, m4a... */
    val container: String,
    val codec: String?,
) {
    val isAudioOnly: Boolean get() = video == null
    val needsMerge: Boolean get() = video != null && audio != null
    val heightPx: Int? get() = video?.heightPx

    val estimatedSizeBytes: Long?
        get() = listOfNotNull(video?.sizeBytes, audio?.sizeBytes)
            .takeIf { it.isNotEmpty() }
            ?.sum()
}

/** Turns the long list of formats from yt-dlp into a short list of understandable choices. */
object YtDlpFormatSelector {

    private data class VideoGroup(
        val height: Int,
        val fps60: Boolean,
        val codec: String,
        val ext: String,
    )

    private val bestBitrate = compareBy<YtDlpFormat> { it.tbr ?: 0.0 }
        .thenBy { it.sizeBytes ?: 0L }

    fun buildOptions(info: YtDlpInfo): List<YtDlpQualityOption> {
        val usable = info.formats.filter { it.isUsable() }
        val audios = usable.filter { it.hasAudio && !it.hasVideo }
        val videos = usable.filter { it.hasVideo && it.heightPx != null }

        val videoOptions = videos
            .groupBy {
                VideoGroup(
                    height = it.heightPx!!,
                    fps60 = (it.fps ?: 0.0) >= 50.0,
                    codec = codecName(it),
                    ext = it.ext.orEmpty(),
                )
            }
            .map { (group, formats) ->
                val video = formats.maxWith(bestBitrate)
                // a format that already contains sound does not need a separate audio file
                val audio = if (video.hasAudio) null else pickAudio(audios, video.ext)
                val container = containerFor(video, audio)
                YtDlpQualityOption(
                    key = "${group.height}p${if (group.fps60) "60" else ""}|${group.codec}|$container",
                    label = "${group.height}p${if (group.fps60) "60" else ""} ${container.uppercase()} (${group.codec})",
                    video = video,
                    audio = audio,
                    container = container,
                    codec = group.codec,
                )
            }
            .sortedWith(
                compareByDescending<YtDlpQualityOption> { it.heightPx ?: 0 }
                    .thenByDescending { it.key.contains("p60|") }
                    .thenBy { codecRank(it.codec) }
            )

        val audioOptions = audios
            .groupBy { it.ext.orEmpty() }
            .map { (ext, formats) ->
                val best = formats.maxWith(bestBitrate)
                val bitrate = best.tbr?.toInt()?.let { " $it kbps" }.orEmpty()
                YtDlpQualityOption(
                    key = "audio|$ext",
                    label = "Audio ${ext.uppercase()}$bitrate",
                    video = null,
                    audio = best,
                    container = ext.ifEmpty { "m4a" },
                    codec = null,
                )
            }
            .sortedByDescending { it.audio?.tbr ?: 0.0 }

        return videoOptions + audioOptions
    }

    /** The option to pre-select: the highest H.264 quality up to 1080p, because it plays everywhere. */
    fun defaultOption(options: List<YtDlpQualityOption>): YtDlpQualityOption? {
        val videos = options.filter { !it.isAudioOnly }
        return videos.firstOrNull { it.codec == "H.264" && (it.heightPx ?: 0) <= 1080 }
            ?: videos.firstOrNull()
            ?: options.firstOrNull()
    }

    fun findByKey(info: YtDlpInfo, key: String): YtDlpQualityOption? =
        buildOptions(info).firstOrNull { it.key == key }

    private fun YtDlpFormat.isUsable(): Boolean =
        isDirectHttp &&
            !url.isNullOrBlank() &&
            ext != "mhtml" && // storyboards (small preview pictures)
            !formatId.endsWith("-drc") && // the same sound with dynamic range compression
            formatNote?.contains("DRC", ignoreCase = true) != true

    private fun pickAudio(audios: List<YtDlpFormat>, videoExt: String?): YtDlpFormat? {
        val preferredExt = when (videoExt) {
            "mp4" -> "m4a"
            "webm" -> "webm"
            else -> null
        }
        val pool = audios.filter { it.ext == preferredExt }.ifEmpty { audios }
        return pool.maxWithOrNull(bestBitrate)
    }

    private fun containerFor(video: YtDlpFormat, audio: YtDlpFormat?): String {
        val videoExt = video.ext.orEmpty().ifEmpty { "mp4" }
        return when {
            audio == null -> videoExt
            videoExt == "mp4" && audio.ext == "m4a" -> "mp4"
            videoExt == "webm" && audio.ext == "webm" -> "webm"
            else -> "mkv" // mkv can hold any combination
        }
    }

    private fun codecName(format: YtDlpFormat): String {
        val codec = format.vcodec.orEmpty().lowercase()
        return when {
            codec.startsWith("avc") || codec.startsWith("h264") -> "H.264"
            codec.startsWith("vp09") || codec.startsWith("vp9") -> "VP9"
            codec.startsWith("av01") || codec.startsWith("av1") -> "AV1"
            codec.startsWith("hev") || codec.startsWith("hvc") || codec.startsWith("h265") -> "H.265"
            else -> codec.substringBefore('.').uppercase().ifEmpty { "Video" }
        }
    }

    private fun codecRank(codec: String?): Int = when (codec) {
        "H.264" -> 0
        "VP9" -> 1
        "AV1" -> 2
        else -> 3
    }
}
