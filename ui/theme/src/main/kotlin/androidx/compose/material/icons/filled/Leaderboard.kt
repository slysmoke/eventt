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

public val Icons.Filled.Leaderboard: ImageVector
    get() {
        if (_leaderboard != null) {
            return _leaderboard!!
        }
        _leaderboard = materialIcon(name = "Filled.Leaderboard") {
            materialPath {
                moveTo(7.5f, 21.0f)
                horizontalLineTo(2.0f)
                verticalLineTo(9.0f)
                horizontalLineToRelative(5.5f)
                verticalLineTo(21.0f)
                close()
                moveTo(14.75f, 3.0f)
                horizontalLineToRelative(-5.5f)
                verticalLineToRelative(18.0f)
                horizontalLineToRelative(5.5f)
                verticalLineTo(3.0f)
                close()
                moveTo(22.0f, 11.0f)
                horizontalLineToRelative(-5.5f)
                verticalLineToRelative(10.0f)
                horizontalLineTo(22.0f)
                verticalLineTo(11.0f)
                close()
            }
        }
        return _leaderboard!!
    }

private var _leaderboard: ImageVector? = null
