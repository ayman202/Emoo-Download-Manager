package com.abdownloadmanager.desktop.pages.videoquality

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.v2.WindowBoundsProvider
import androidx.compose.ui.window.v2.WindowPositionProvider
import androidx.compose.ui.window.v2.WindowSizeProvider
import androidx.compose.ui.window.v2.rememberWindowState
import com.abdownloadmanager.desktop.AppComponent
import com.abdownloadmanager.desktop.ui.template.DialogFooter
import com.abdownloadmanager.desktop.ui.template.DialogMainContent
import com.abdownloadmanager.desktop.ui.template.DialogUi
import com.abdownloadmanager.desktop.window.custom.CustomWindow
import com.abdownloadmanager.desktop.window.custom.rememberWindowController
import com.abdownloadmanager.resources.Res
import com.abdownloadmanager.shared.ui.widget.ActionButton
import com.abdownloadmanager.shared.ui.widget.Text
import com.abdownloadmanager.shared.util.div
import com.abdownloadmanager.shared.util.rememberChild
import com.abdownloadmanager.shared.util.ui.WithContentAlpha
import com.abdownloadmanager.shared.util.ui.myColors
import com.abdownloadmanager.shared.util.ui.theme.LocalUiScale
import com.abdownloadmanager.shared.util.ui.theme.mySpacings
import com.abdownloadmanager.shared.util.ui.theme.myTextSizes
import ir.amirab.util.compose.resources.myStringResource
import ir.amirab.util.desktop.screen.applyUiScale

@Composable
fun VideoQualityDialog(
    appComponent: AppComponent,
) {
    appComponent.videoQualityDialogSlot.rememberChild()?.let {
        VideoQualityDialog(it)
    }
}

@Composable
private fun VideoQualityDialog(
    component: VideoQualityComponent,
) {
    CustomWindow(
        state = rememberWindowState(
            initialBoundsProvider = WindowBoundsProvider(
                sizeProvider = WindowSizeProvider.Fixed(
                    size = DpSize(520.dp, 560.dp)
                        .applyUiScale(LocalUiScale.current),
                ),
                positionProvider = WindowPositionProvider.CenteredOnScreen
            )
        ),
        onCloseRequest = component.onClose,
        alwaysOnTop = true,
        windowController = rememberWindowController(
            title = myStringResource(Res.string.choose_video_quality),
        ),
    ) {
        VideoQualityContent(component)
    }
}

@Composable
private fun VideoQualityContent(
    component: VideoQualityComponent,
) {
    val selectedKey by component.selectedKey.collectAsState()
    DialogUi(
        mainContent = {
            DialogMainContent {
                Column {
                    Text(
                        component.title.ifBlank { "-" },
                        fontSize = myTextSizes.lg,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    Spacer(Modifier.fillMaxWidth().height(1.dp).background(myColors.onBackground / 0.1f))
                    Column(
                        Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 8.dp)
                    ) {
                        component.options.forEach { option ->
                            val selected = option.key == selectedKey
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (selected) myColors.onBackground / 0.12f else Color.Transparent
                                    )
                                    .clickable { component.select(option) }
                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(if (selected) "◉" else "○")
                                Spacer(Modifier.width(mySpacings.mediumSpace))
                                Text(
                                    option.label,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.weight(1f),
                                )
                                WithContentAlpha(0.7f) {
                                    Text(formatSize(option.estimatedSizeBytes))
                                }
                            }
                        }
                    }
                }
            }
        },
        footer = {
            DialogFooter {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ActionButton(
                        text = myStringResource(Res.string.cancel),
                        onClick = component.onClose,
                    )
                    Spacer(Modifier.width(8.dp))
                    ActionButton(
                        text = myStringResource(Res.string.download),
                        enabled = selectedKey != null,
                        onClick = component::download,
                    )
                }
            }
        }
    )
}

private fun formatSize(bytes: Long?): String = when {
    bytes == null -> ""
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    else -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
}
