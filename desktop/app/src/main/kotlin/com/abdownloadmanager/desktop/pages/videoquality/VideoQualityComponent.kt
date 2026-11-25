package com.abdownloadmanager.desktop.pages.videoquality

import com.abdownloadmanager.desktop.ytdlp.YtDlpFormat
import com.abdownloadmanager.desktop.ytdlp.YtDlpFormatSelector
import com.abdownloadmanager.desktop.ytdlp.YtDlpInfo
import com.abdownloadmanager.desktop.ytdlp.YtDlpQualityOption
import com.abdownloadmanager.desktop.ytdlp.YtDlpSizeProbe
import com.abdownloadmanager.shared.util.BaseComponent
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * The state of the "choose video quality" window.
 */
class VideoQualityComponent(
    ctx: ComponentContext,
    info: YtDlpInfo,
    private val onDownloadRequest: (YtDlpQualityOption) -> Unit,
    val onClose: () -> Unit,
) : BaseComponent(ctx) {

    data class Config(
        val info: YtDlpInfo,
        val folder: String,
    )

    val title: String = info.title.orEmpty()

    private val _options = MutableStateFlow(YtDlpFormatSelector.buildOptions(info))
    val options: StateFlow<List<YtDlpQualityOption>> = _options.asStateFlow()

    private val _selectedKey = MutableStateFlow(YtDlpFormatSelector.defaultOption(_options.value)?.key)
    val selectedKey: StateFlow<String?> = _selectedKey.asStateFlow()

    init {
        findExactSizes()
    }

    fun select(option: YtDlpQualityOption) {
        _selectedKey.value = option.key
    }

    fun download() {
        val selected = _options.value.firstOrNull { it.key == _selectedKey.value } ?: return
        onDownloadRequest(selected)
        onClose()
    }

    /**
     * yt-dlp does not know the size of every file. The servers do, so we ask them in the background
     * and update the list while the window is open.
     */
    private fun findExactSizes() {
        val formats = _options.value
            .flatMap { listOfNotNull(it.video, it.audio) }
            // the size of an HLS or DASH address is the size of a small playlist, not of the video
            .filter { it.filesize == null && it.isDirectHttp }
            .distinctBy { it.formatId }
        if (formats.isEmpty()) return

        scope.launch {
            val limit = Semaphore(4)
            formats.forEach { format ->
                launch {
                    val size = limit.withPermit { YtDlpSizeProbe.probe(format) } ?: return@launch
                    applyExactSize(format.formatId, size)
                }
            }
        }
    }

    private fun applyExactSize(formatId: String, size: Long) {
        _options.update { options ->
            options.map { option ->
                option.copy(
                    video = option.video?.withExactSize(formatId, size),
                    audio = option.audio?.withExactSize(formatId, size),
                )
            }
        }
    }

    private fun YtDlpFormat.withExactSize(id: String, size: Long): YtDlpFormat {
        return if (formatId == id) copy(filesize = size.toDouble()) else this
    }
}
