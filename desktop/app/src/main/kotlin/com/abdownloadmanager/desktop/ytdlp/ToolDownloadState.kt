package com.abdownloadmanager.desktop.ytdlp

/** What the tool manager is doing, so that the app can show a progress notification. */
sealed interface ToolDownloadState {
    data object Idle : ToolDownloadState

    /** @param percent null while the total size is not known yet. */
    data class Downloading(val toolName: String, val percent: Int?) : ToolDownloadState
}
