package com.abdownloadmanager.resources.icons

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val ABDMIcons.AppIcon: ImageVector
    get() {
        if (_AppIcon != null) {
            return _AppIcon!!
        }
        _AppIcon = ImageVector.Builder(
            name = "AppIcon",
            defaultWidth = 48.dp,
            defaultHeight = 48.dp,
            viewportWidth = 48f,
            viewportHeight = 48f
        ).apply {
            // Globe
            path(
                fill = Brush.linearGradient(
                    colorStops = arrayOf(
                        0f to Color(0xFF4FC3F7),
                        0.55f to Color(0xFF1565C0),
                        1f to Color(0xFF0B2E7A)
                    ),
                    start = Offset(10f, 4f),
                    end = Offset(40f, 46f)
                ),
                stroke = SolidColor(Color(0xFF0A2466)),
                strokeLineWidth = 1.6f
            ) {
                moveTo(2f, 24f)
                arcTo(22f, 22f, 0f, false, true, 46f, 24f)
                arcTo(22f, 22f, 0f, false, true, 2f, 24f)
                close()
            }

            // Globe meridian
            path(
                stroke = SolidColor(Color(0x47FFFFFF)),
                strokeLineWidth = 0.8f
            ) {
                moveTo(24f, 2f)
                arcTo(10f, 22f, 0f, false, true, 24f, 46f)
                arcTo(10f, 22f, 0f, false, true, 24f, 2f)
                close()
            }

            // Globe latitude line
            path(
                stroke = SolidColor(Color(0x47FFFFFF)),
                strokeLineWidth = 0.8f
            ) {
                moveTo(4.4f, 13f)
                lineTo(43.6f, 13f)
            }

            // Tray
            path(
                fill = Brush.linearGradient(
                    colorStops = arrayOf(
                        0f to Color(0xFFEEF2F8),
                        1f to Color(0xFF8794A8)
                    ),
                    start = Offset(24f, 37f),
                    end = Offset(24f, 41f)
                ),
                stroke = SolidColor(Color(0xFF12234F)),
                strokeLineWidth = 1f,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(10f, 37f)
                lineTo(38f, 37f)
                lineTo(38f, 41f)
                lineTo(10f, 41f)
                close()
            }

            // Download arrow
            path(
                fill = Brush.linearGradient(
                    colorStops = arrayOf(
                        0f to Color(0xFFB8FF1A),
                        1f to Color(0xFF00C21A)
                    ),
                    start = Offset(24f, 9f),
                    end = Offset(24f, 36f)
                ),
                stroke = SolidColor(Color(0xFF0B3A0B)),
                strokeLineWidth = 1.3f,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(19.5f, 8.5f)
                lineTo(28.5f, 8.5f)
                lineTo(28.5f, 21.5f)
                lineTo(36f, 21.5f)
                lineTo(24f, 35.5f)
                lineTo(12f, 21.5f)
                lineTo(19.5f, 21.5f)
                close()
            }
        }.build()

        return _AppIcon!!
    }

@Suppress("ObjectPropertyName")
private var _AppIcon: ImageVector? = null

