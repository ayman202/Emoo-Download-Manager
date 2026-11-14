package com.abdownloadmanager.desktop.pages.videoquality

import com.abdownloadmanager.desktop.ytdlp.YtDlpFormatSelector
import com.abdownloadmanager.desktop.ytdlp.YtDlpInfo
import com.abdownloadmanager.desktop.ytdlp.YtDlpQualityOption
import com.abdownloadmanager.shared.util.BaseComponent
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    val options: List<YtDlpQualityOption> = YtDlpFormatSelector.buildOptions(info)

    private val _selectedKey = MutableStateFlow(YtDlpFormatSelector.defaultOption(options)?.key)
    val selectedKey: StateFlow<String?> = _selectedKey.asStateFlow()

    fun select(option: YtDlpQualityOption) {
        _selectedKey.value = option.key
    }

    fun download() {
        val selected = options.firstOrNull { it.key == _selectedKey.value } ?: return
        onDownloadRequest(selected)
        onClose()
    }
}
