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

public val Icons.Filled.Podcasts: ImageVector
    get() {
        if (_podcasts != null) {
            return _podcasts!!
        }
        _podcasts = materialIcon(name = "Filled.Podcasts") {
            materialPath {
                moveTo(14.0f, 12.0f)
                curveToRelative(0.0f, 0.74f, -0.4f, 1.38f, -1.0f, 1.72f)
                verticalLineTo(22.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(-8.28f)
                curveToRelative(-0.6f, -0.35f, -1.0f, -0.98f, -1.0f, -1.72f)
                curveToRelative(0.0f, -1.1f, 0.9f, -2.0f, 2.0f, -2.0f)
                reflectiveCurveTo(14.0f, 10.9f, 14.0f, 12.0f)
                close()
                moveTo(12.0f, 6.0f)
                curveToRelative(-3.31f, 0.0f, -6.0f, 2.69f, -6.0f, 6.0f)
                curveToRelative(0.0f, 1.74f, 0.75f, 3.31f, 1.94f, 4.4f)
                lineToRelative(1.42f, -1.42f)
                curveTo(8.53f, 14.25f, 8.0f, 13.19f, 8.0f, 12.0f)
                curveToRelative(0.0f, -2.21f, 1.79f, -4.0f, 4.0f, -4.0f)
                reflectiveCurveToRelative(4.0f, 1.79f, 4.0f, 4.0f)
                curveToRelative(0.0f, 1.19f, -0.53f, 2.25f, -1.36f, 2.98f)
                lineToRelative(1.42f, 1.42f)
                curveTo(17.25f, 15.31f, 18.0f, 13.74f, 18.0f, 12.0f)
                curveTo(18.0f, 8.69f, 15.31f, 6.0f, 12.0f, 6.0f)
                close()
                moveTo(12.0f, 2.0f)
                curveTo(6.48f, 2.0f, 2.0f, 6.48f, 2.0f, 12.0f)
                curveToRelative(0.0f, 2.85f, 1.2f, 5.41f, 3.11f, 7.24f)
                lineToRelative(1.42f, -1.42f)
                curveTo(4.98f, 16.36f, 4.0f, 14.29f, 4.0f, 12.0f)
                curveToRelative(0.0f, -4.41f, 3.59f, -8.0f, 8.0f, -8.0f)
                reflectiveCurveToRelative(8.0f, 3.59f, 8.0f, 8.0f)
                curveToRelative(0.0f, 2.29f, -0.98f, 4.36f, -2.53f, 5.82f)
                lineToRelative(1.42f, 1.42f)
                curveTo(20.8f, 17.41f, 22.0f, 14.85f, 22.0f, 12.0f)
                curveTo(22.0f, 6.48f, 17.52f, 2.0f, 12.0f, 2.0f)
                close()
            }
        }
        return _podcasts!!
    }

private var _podcasts: ImageVector? = null
