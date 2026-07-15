package org.eventt.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eventt.core.image.EveImageServer

/**
 * An item's real icon from images.evetech.net (memory + disk cached by EveImageServer),
 * with the old puzzle-piece placeholder shown while loading or when the fetch fails.
 */
@Composable
fun TypeIcon(
    typeId: Int,
    size: Dp = 24.dp,
    modifier: Modifier = Modifier,
) {
    // The image server only serves power-of-two sizes; 32px covers every list-row use here,
    // 64px anything larger — the disk cache keys include the size, so the two don't collide.
    val px = if (size <= 32.dp) 32 else 64
    val bitmap by produceState<ImageBitmap?>(initialValue = null, typeId, px) {
        value =
            withContext(Dispatchers.IO) {
                runCatching { EveImageServer.getTypeIcon(typeId, px)?.toComposeImageBitmap() }.getOrNull()
            }
    }

    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = null,
            modifier = modifier.size(size).clip(MaterialTheme.shapes.small),
        )
    } else {
        Surface(
            modifier = modifier.size(size),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Extension,
                    contentDescription = null,
                    modifier = Modifier.size(size * 0.6f),
                    tint = Color.Gray,
                )
            }
        }
    }
}
