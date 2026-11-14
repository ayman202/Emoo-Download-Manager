package com.abdownloadmanager.desktop.ytdlp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The part of the `yt-dlp -J` output that we care about.
 * Every field is optional because different websites fill different fields.
 */
@Serializable
data class YtDlpInfo(
    val id: String? = null,
    val title: String? = null,
    val uploader: String? = null,
    val duration: Double? = null,
    val thumbnail: String? = null,
    @SerialName("webpage_url") val webpageUrl: String? = null,
    val extractor: String? = null,
    val ext: String? = null,
    @SerialName("_type") val type: String? = null,
    val formats: List<YtDlpFormat> = emptyList(),
)

@Serializable
data class YtDlpFormat(
    @SerialName("format_id") val formatId: String,
    val ext: String? = null,
    @SerialName("format_note") val formatNote: String? = null,
    val width: Double? = null,
    val height: Double? = null,
    val fps: Double? = null,
    val vcodec: String? = null,
    val acodec: String? = null,
    val filesize: Double? = null,
    @SerialName("filesize_approx") val filesizeApprox: Double? = null,
    val tbr: Double? = null,
    val url: String? = null,
    val protocol: String? = null,
    @SerialName("http_headers") val httpHeaders: Map<String, String> = emptyMap(),
) {
    val hasVideo: Boolean get() = !vcodec.isNullOrBlank() && vcodec != "none"
    val hasAudio: Boolean get() = !acodec.isNullOrBlank() && acodec != "none"
    val heightPx: Int? get() = height?.toInt()
    val sizeBytes: Long? get() = (filesize ?: filesizeApprox)?.toLong()

    /** A plain file served over http(s), which our own download engine can download. */
    val isDirectHttp: Boolean get() = protocol == "https" || protocol == "http"
}
