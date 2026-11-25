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

    /**
     * `true` when a part is HLS or DASH (many small pieces). Our own engine downloads only plain files,
     * so yt-dlp downloads these (it also handles encryption and the headers that those sites need).
     */
    val needsYtDlp: Boolean get() = listOfNotNull(video, audio).any { !it.isDirectHttp }
    val heightPx: Int? get() = video?.heightPx

    /** The total size of the parts, or null when the size of any part is unknown. */
    val estimatedSizeBytes: Long?
        get() {
            val sizes = listOfNotNull(video, audio).map { it.sizeBytes }
            return if (sizes.isEmpty() || sizes.any { it == null }) null else sizes.filterNotNull().sum()
        }

    /** `true` when the size is a guess (calculated from the bitrate) and not the exact size of the file. */
    val isSizeEstimated: Boolean
        get() = listOfNotNull(video, audio).any { it.filesize == null }
}

/** Turns the long list of formats from yt-dlp into a short list of understandable choices. */
object YtDlpFormatSelector {

    private data class VideoGroup(
        val height: Int,
        val fps60: Boolean,
        val codec: String,
        val ext: String,
    )

    private val bestBitrate = compareBy<YtDlpFormat> { it.bitrateKbps ?: 0.0 }
        .thenBy { it.sizeBytes ?: 0L }

    /** A plain file is preferred over a stream with the same quality, then the higher bitrate wins. */
    private val bestFormat = compareBy<YtDlpFormat> { if (it.isDirectHttp) 1 else 0 }
        .then(bestBitrate)

    fun buildOptions(info: YtDlpInfo): List<YtDlpQualityOption> {
        val usable = info.formats
            .filter { it.isUsable() }
            .map { it.withEstimatedSize(info.duration) }
        val audios = usable.filter { it.hasAudio && !it.hasVideo }
        val videos = usable.filter { it.hasVideo && it.heightPx != null }
        // one stream that has everything but does not tell its size (common for HLS): offered as it is
        val unknownQualityStreams = usable.filter { it.hasVideo && it.hasAudio && it.heightPx == null }

        val unknownQualityOptions = unknownQualityStreams.map { stream ->
            val container = containerFor(stream, null)
            YtDlpQualityOption(
                key = "stream|${stream.formatId}",
                label = "Original quality" +
                        (stream.bitrateKbps?.let { " (${it.toInt()} kbps)" } ?: "") +
                        (stream.streamKind?.let { " · $it" } ?: ""),
                video = stream,
                audio = null,
                container = container,
                codec = null,
            )
        }

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
                val video = formats.maxWith(bestFormat)
                // a format that already contains sound does not need a separate audio file
                val audio = if (video.hasAudio) null else pickAudio(audios, video.ext)
                val container = containerFor(video, audio)
                YtDlpQualityOption(
                    key = "${group.height}p${if (group.fps60) "60" else ""}|${group.codec}|$container",
                    label = "${group.height}p${if (group.fps60) "60" else ""} ${container.uppercase()} (${group.codec})" +
                            (listOfNotNull(video, audio).firstNotNullOfOrNull { it.streamKind }?.let { " · $it" } ?: ""),
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

        // Audio files are grouped by the kind of file they make, so a plain m4a and an HLS audio with AAC sound
        // (which is also an m4a file) are the same choice, and the plain file wins.
        val audioOptions = audios
            .groupBy { audioContainer(it) }
            .map { (container, formats) ->
                val best = formats.maxWith(bestFormat)
                val bitrate = best.bitrateKbps?.toInt()?.let { " $it kbps" }.orEmpty()
                YtDlpQualityOption(
                    key = "audio|$container",
                    label = "Audio ${container.uppercase()}$bitrate" + (best.streamKind?.let { " · $it" } ?: ""),
                    video = null,
                    audio = best,
                    container = container,
                    codec = null,
                )
            }
            .sortedByDescending { it.audio?.bitrateKbps ?: 0.0 }

        return videoOptions + unknownQualityOptions + audioOptions
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

    /** Many websites do not tell the size of a file. It can be estimated from the bitrate and the duration. */
    private fun YtDlpFormat.withEstimatedSize(durationSeconds: Double?): YtDlpFormat {
        if (filesize != null || filesizeApprox != null) return this
        val kbps = bitrateKbps ?: return this
        val seconds = durationSeconds?.takeIf { it > 0.0 } ?: return this
        return copy(filesizeApprox = kbps * 1000.0 / 8.0 * seconds)
    }

    private fun YtDlpFormat.isUsable(): Boolean =
        isSupportedProtocol &&
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
        return pool.maxWithOrNull(bestFormat)
    }

    private fun containerFor(video: YtDlpFormat, audio: YtDlpFormat?): String {
        val videoExt = video.ext.orEmpty().ifEmpty { "mp4" }
        return when {
            audio == null -> videoExt
            videoExt == "mp4" && (audio.ext == "m4a" || audio.acodec?.startsWith("mp4a") == true) -> "mp4"
            videoExt == "webm" && audio.ext == "webm" -> "webm"
            else -> "mkv" // mkv can hold any combination
        }
    }

    /** HLS audio is often an mp4 file that only contains AAC sound: that is an m4a file. */
    private fun audioContainer(audio: YtDlpFormat): String {
        val ext = audio.ext.orEmpty()
        return if (ext == "mp4" && audio.acodec?.startsWith("mp4a") == true) "m4a" else ext.ifEmpty { "m4a" }
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
