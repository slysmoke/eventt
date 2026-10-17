/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.material.icons.filled

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

public val Icons.Filled.Calculate: ImageVector
    get() {
        if (_calculate != null) {
            return _calculate!!
        }
        _calculate = materialIcon(name = "Filled.Calculate") {
            materialPath {
                moveTo(19.0f, 3.0f)
                horizontalLineTo(5.0f)
                curveTo(3.9f, 3.0f, 3.0f, 3.9f, 3.0f, 5.0f)
                verticalLineToRelative(14.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(14.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                verticalLineTo(5.0f)
                curveTo(21.0f, 3.9f, 20.1f, 3.0f, 19.0f, 3.0f)
                close()
                moveTo(13.03f, 7.06f)
                lineTo(14.09f, 6.0f)
                lineToRelative(1.41f, 1.41f)
                lineTo(16.91f, 6.0f)
                lineToRelative(1.06f, 1.06f)
                lineToRelative(-1.41f, 1.41f)
                lineToRelative(1.41f, 1.41f)
                lineToRelative(-1.06f, 1.06f)
                lineTo(15.5f, 9.54f)
                lineToRelative(-1.41f, 1.41f)
                lineToRelative(-1.06f, -1.06f)
                lineToRelative(1.41f, -1.41f)
                lineTo(13.03f, 7.06f)
                close()
                moveTo(6.25f, 7.72f)
                horizontalLineToRelative(5.0f)
                verticalLineToRelative(1.5f)
                horizontalLineToRelative(-5.0f)
                verticalLineTo(7.72f)
                close()
                moveTo(11.5f, 16.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(2.0f)
                horizontalLineTo(8.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineTo(6.0f)
                verticalLineToRelative(-1.5f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(1.5f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(2.0f)
                verticalLineTo(16.0f)
                close()
                moveTo(18.0f, 17.25f)
                horizontalLineToRelative(-5.0f)
                verticalLineToRelative(-1.5f)
                horizontalLineToRelative(5.0f)
                verticalLineTo(17.25f)
                close()
                moveTo(18.0f, 14.75f)
                horizontalLineToRelative(-5.0f)
                verticalLineToRelative(-1.5f)
                horizontalLineToRelative(5.0f)
                verticalLineTo(14.75f)
                close()
            }
        }
        return _calculate!!
    }

private var _calculate: ImageVector? = null
