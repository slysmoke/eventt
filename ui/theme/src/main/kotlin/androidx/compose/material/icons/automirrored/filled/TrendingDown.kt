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

package androidx.compose.material.icons.automirrored.filled

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

public val Icons.AutoMirrored.Filled.TrendingDown: ImageVector
    get() {
        if (_trendingDown != null) {
            return _trendingDown!!
        }
        _trendingDown = materialIcon(name = "AutoMirrored.Filled.TrendingDown", autoMirror = true) {
            materialPath {
                moveTo(16.0f, 18.0f)
                lineToRelative(2.29f, -2.29f)
                lineToRelative(-4.88f, -4.88f)
                lineToRelative(-4.0f, 4.0f)
                lineTo(2.0f, 7.41f)
                lineTo(3.41f, 6.0f)
                lineToRelative(6.0f, 6.0f)
                lineToRelative(4.0f, -4.0f)
                lineToRelative(6.3f, 6.29f)
                lineTo(22.0f, 12.0f)
                verticalLineToRelative(6.0f)
                close()
            }
        }
        return _trendingDown!!
    }

private var _trendingDown: ImageVector? = null
