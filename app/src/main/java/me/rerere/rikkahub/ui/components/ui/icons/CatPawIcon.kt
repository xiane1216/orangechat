/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.components.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 猫爪印 (Apple 风格黑色填充)
 * 四个小肉垫 + 一个大肉垫
 */
public val CatPawIcon: ImageVector
    get() {
        if (_catPawIcon != null) {
            return _catPawIcon!!
        }
        _catPawIcon = ImageVector.Builder(
            name = "CatPaw",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            // 左外小肉垫
            path(
                fill = SolidColor(Color(0xFF000000)),
                fillAlpha = 1.0f,
                stroke = null,
                pathFillType = PathFillType.EvenOdd
            ) {
                moveTo(3.9f, 8.5f)
                arcToRelative(1.6f, 2.2f, 0f, true, false, 3.2f, 0f)
                arcToRelative(1.6f, 2.2f, 0f, true, false, -3.2f, 0f)
                close()
            }
            // 左内小肉垫
            path(
                fill = SolidColor(Color(0xFF000000)),
                fillAlpha = 1.0f,
                stroke = null,
                pathFillType = PathFillType.EvenOdd
            ) {
                moveTo(7.5f, 7f)
                arcToRelative(1.5f, 2f, 0f, true, false, 3f, 0f)
                arcToRelative(1.5f, 2f, 0f, true, false, -3f, 0f)
                close()
            }
            // 右内小肉垫
            path(
                fill = SolidColor(Color(0xFF000000)),
                fillAlpha = 1.0f,
                stroke = null,
                pathFillType = PathFillType.EvenOdd
            ) {
                moveTo(13.5f, 7f)
                arcToRelative(1.5f, 2f, 0f, true, false, 3f, 0f)
                arcToRelative(1.5f, 2f, 0f, true, false, -3f, 0f)
                close()
            }
            // 右外小肉垫
            path(
                fill = SolidColor(Color(0xFF000000)),
                fillAlpha = 1.0f,
                stroke = null,
                pathFillType = PathFillType.EvenOdd
            ) {
                moveTo(16.9f, 8.5f)
                arcToRelative(1.6f, 2.2f, 0f, true, false, 3.2f, 0f)
                arcToRelative(1.6f, 2.2f, 0f, true, false, -3.2f, 0f)
                close()
            }
            // 大肉垫 (主掌垫) - 上窄下宽的圆润形状
            path(
                fill = SolidColor(Color(0xFF000000)),
                fillAlpha = 1.0f,
                stroke = null,
                pathFillType = PathFillType.EvenOdd
            ) {
                moveTo(8f, 12.5f)
                curveToRelative(0f, -1.5f, 8f, -1.5f, 8f, 0f)
                curveToRelative(2f, 2.5f, 1.5f, 7.5f, -4f, 9f)
                curveToRelative(-5.5f, -1.5f, -6f, -6.5f, -4f, -9f)
                close()
            }
        }.build()
        return _catPawIcon!!
    }

private var _catPawIcon: ImageVector? = null
