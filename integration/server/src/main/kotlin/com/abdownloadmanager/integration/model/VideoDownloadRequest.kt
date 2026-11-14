package com.abdownloadmanager.integration.model

import kotlinx.serialization.Serializable

/**
 * Asks the app to find the video on a web page and download it.
 *
 * @param url the address of the web page (not of a file).
 * @param quality an optional quality key such as `720p|H.264|mp4`. The best compatible quality is used when it is null.
 * @param folder an optional download folder. The default folder is used when it is null.
 */
@Serializable
data class VideoDownloadRequest(
    val url: String,
    val quality: String? = null,
    val folder: String? = null,
)
