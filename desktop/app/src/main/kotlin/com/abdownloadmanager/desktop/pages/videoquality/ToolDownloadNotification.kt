package com.abdownloadmanager.desktop.pages.videoquality

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.abdownloadmanager.desktop.AppComponent
import com.abdownloadmanager.desktop.ytdlp.ToolDownloadState
import com.abdownloadmanager.resources.Res
import com.abdownloadmanager.shared.ui.widget.NotificationType
import com.abdownloadmanager.shared.ui.widget.ShowNotification
import ir.amirab.util.compose.asStringSource

/**
 * Shows a notification with the progress while yt-dlp or ffmpeg is downloaded (it happens once).
 * The notification disappears by itself when the download is finished.
 */
@Composable
fun ToolDownloadNotification(
    appComponent: AppComponent,
) {
    val state by appComponent.toolDownloadState.collectAsState()
    val downloading = state as? ToolDownloadState.Downloading ?: return
    val description = buildString {
        append(downloading.toolName)
        downloading.percent?.let { append(" - ").append(it).append("%") }
    }
    ShowNotification(
        title = Res.string.tool_download_title.asStringSource(),
        description = description.asStringSource(),
        type = NotificationType.Loading(downloading.percent),
        tag = "video-tools",
    )
}
