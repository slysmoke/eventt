package org.eventt.features.p2pmarket

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.eventt.core.nostr.OrderSide
import org.eventt.p2pmarket.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Always-visible SELL/BUY tag — a lone icon or color tint reads as decoration and gets missed, and
 * "who's buying, who's selling" is exactly the thing that turns out to matter once you're mixing
 * sell and buy orders in the same list. Same tertiary/primary color pairing already used for the
 * Sell/ShoppingCart icons in Browse, just spelled out in words too.
 */
@Composable
internal fun OrderSideBadge(side: OrderSide) {
    val color = if (side == OrderSide.SELL) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    Text(
        stringResource(if (side == OrderSide.SELL) Res.string.side_sell else Res.string.side_buy),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier =
            Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(color.copy(alpha = 0.15f))
                .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** The order owner's real-world role — the opposite of whoever fulfills it (a SELL order is fulfilled by a buyer). */
@Composable
internal fun orderOwnerRole(side: OrderSide): String =
    stringResource(if (side == OrderSide.SELL) Res.string.role_seller else Res.string.role_buyer)
