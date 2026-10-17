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

public val Icons.Filled.BugReport: ImageVector
    get() {
        if (_bugReport != null) {
            return _bugReport!!
        }
        _bugReport = materialIcon(name = "Filled.BugReport") {
            materialPath {
                moveTo(20.0f, 8.0f)
                horizontalLineToRelative(-2.81f)
                curveToRelative(-0.45f, -0.78f, -1.07f, -1.45f, -1.82f, -1.96f)
                lineTo(17.0f, 4.41f)
                lineTo(15.59f, 3.0f)
                lineToRelative(-2.17f, 2.17f)
                curveTo(12.96f, 5.06f, 12.49f, 5.0f, 12.0f, 5.0f)
                curveToRelative(-0.49f, 0.0f, -0.96f, 0.06f, -1.41f, 0.17f)
                lineTo(8.41f, 3.0f)
                lineTo(7.0f, 4.41f)
                lineToRelative(1.62f, 1.63f)
                curveTo(7.88f, 6.55f, 7.26f, 7.22f, 6.81f, 8.0f)
                lineTo(4.0f, 8.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(2.09f)
                curveToRelative(-0.05f, 0.33f, -0.09f, 0.66f, -0.09f, 1.0f)
                verticalLineToRelative(1.0f)
                lineTo(4.0f, 12.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(1.0f)
                curveToRelative(0.0f, 0.34f, 0.04f, 0.67f, 0.09f, 1.0f)
                lineTo(4.0f, 16.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(2.81f)
                curveToRelative(1.04f, 1.79f, 2.97f, 3.0f, 5.19f, 3.0f)
                reflectiveCurveToRelative(4.15f, -1.21f, 5.19f, -3.0f)
                lineTo(20.0f, 18.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(-2.09f)
                curveToRelative(0.05f, -0.33f, 0.09f, -0.66f, 0.09f, -1.0f)
                verticalLineToRelative(-1.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(-1.0f)
                curveToRelative(0.0f, -0.34f, -0.04f, -0.67f, -0.09f, -1.0f)
                lineTo(20.0f, 10.0f)
                lineTo(20.0f, 8.0f)
                close()
                moveTo(14.0f, 16.0f)
                horizontalLineToRelative(-4.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(4.0f)
                verticalLineToRelative(2.0f)
                close()
                moveTo(14.0f, 12.0f)
                horizontalLineToRelative(-4.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(4.0f)
                verticalLineToRelative(2.0f)
                close()
            }
        }
        return _bugReport!!
    }

private var _bugReport: ImageVector? = null
