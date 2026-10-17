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

public val Icons.Filled.FontDownload: ImageVector
    get() {
        if (_fontDownload != null) {
            return _fontDownload!!
        }
        _fontDownload = materialIcon(name = "Filled.FontDownload") {
            materialPath {
                moveTo(9.93f, 13.5f)
                horizontalLineToRelative(4.14f)
                lineTo(12.0f, 7.98f)
                close()
                moveTo(20.0f, 2.0f)
                lineTo(4.0f, 2.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(16.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(16.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                lineTo(22.0f, 4.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                close()
                moveTo(15.95f, 18.5f)
                lineToRelative(-1.14f, -3.0f)
                lineTo(9.17f, 15.5f)
                lineToRelative(-1.12f, 3.0f)
                lineTo(5.96f, 18.5f)
                lineToRelative(5.11f, -13.0f)
                horizontalLineToRelative(1.86f)
                lineToRelative(5.11f, 13.0f)
                horizontalLineToRelative(-2.09f)
                close()
            }
        }
        return _fontDownload!!
    }

private var _fontDownload: ImageVector? = null
