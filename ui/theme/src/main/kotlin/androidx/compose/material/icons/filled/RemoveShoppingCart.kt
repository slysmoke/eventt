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

public val Icons.Filled.RemoveShoppingCart: ImageVector
    get() {
        if (_removeShoppingCart != null) {
            return _removeShoppingCart!!
        }
        _removeShoppingCart = materialIcon(name = "Filled.RemoveShoppingCart") {
            materialPath {
                moveTo(22.73f, 22.73f)
                lineTo(2.77f, 2.77f)
                lineTo(2.0f, 2.0f)
                lineToRelative(-0.73f, -0.73f)
                lineTo(0.0f, 2.54f)
                lineToRelative(4.39f, 4.39f)
                lineToRelative(2.21f, 4.66f)
                lineToRelative(-1.35f, 2.45f)
                curveToRelative(-0.16f, 0.28f, -0.25f, 0.61f, -0.25f, 0.96f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(7.46f)
                lineToRelative(1.38f, 1.38f)
                curveToRelative(-0.5f, 0.36f, -0.83f, 0.95f, -0.83f, 1.62f)
                curveToRelative(0.0f, 1.1f, 0.89f, 2.0f, 1.99f, 2.0f)
                curveToRelative(0.67f, 0.0f, 1.26f, -0.33f, 1.62f, -0.84f)
                lineTo(21.46f, 24.0f)
                lineToRelative(1.27f, -1.27f)
                close()
                moveTo(7.42f, 15.0f)
                curveToRelative(-0.14f, 0.0f, -0.25f, -0.11f, -0.25f, -0.25f)
                lineToRelative(0.03f, -0.12f)
                lineToRelative(0.9f, -1.63f)
                horizontalLineToRelative(2.36f)
                lineToRelative(2.0f, 2.0f)
                lineTo(7.42f, 15.0f)
                close()
                moveTo(15.55f, 13.0f)
                curveToRelative(0.75f, 0.0f, 1.41f, -0.41f, 1.75f, -1.03f)
                lineToRelative(3.58f, -6.49f)
                curveToRelative(0.08f, -0.14f, 0.12f, -0.31f, 0.12f, -0.48f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                lineTo(6.54f, 4.0f)
                lineToRelative(9.01f, 9.0f)
                close()
                moveTo(7.0f, 18.0f)
                curveToRelative(-1.1f, 0.0f, -1.99f, 0.9f, -1.99f, 2.0f)
                reflectiveCurveTo(5.9f, 22.0f, 7.0f, 22.0f)
                reflectiveCurveToRelative(2.0f, -0.9f, 2.0f, -2.0f)
                reflectiveCurveToRelative(-0.9f, -2.0f, -2.0f, -2.0f)
                close()
            }
        }
        return _removeShoppingCart!!
    }

private var _removeShoppingCart: ImageVector? = null
