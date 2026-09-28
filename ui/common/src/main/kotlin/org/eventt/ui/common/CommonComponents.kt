package org.eventt.ui.common

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.eventt.common.generated.resources.*
import org.eventt.core.model.CorpFeature
import org.jetbrains.compose.resources.stringResource

/**
 * Reusable search field with icon.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = stringResource(Res.string.search_placeholder),
    onClear: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        trailingIcon =
            if (query.isNotEmpty()) {
                {
                    IconButton(onClick = { onClear?.invoke() ?: onQueryChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(Res.string.clear))
                    }
                }
            } else {
                null
            },
    )
}

/**
 * Reusable card with title and content.
 */
@Composable
fun ContentCard(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                actions()
            }
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

/**
 * Banner shown on a corp-context screen when the acting character's ESI token got a 403 on one
 * or more of [features] — explains a blank/stale corp section instead of leaving it unexplained.
 * Once denied, [EsiClient][org.eventt.core.esi.EsiClient] stops calling ESI for that feature on
 * its own, so [onRetry] (clear the denial + re-sync) is the only way it tries again.
 * No-ops when [features] is empty.
 */
@Composable
fun CorpAccessNotice(
    features: Set<CorpFeature>,
    onRetry: () -> Unit,
) {
    if (features.isEmpty()) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                stringResource(
                    Res.string.corp_access_notice,
                    features.map { corpFeatureName(it) }.joinToString(", "),
                ),
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onRetry) {
                Text(stringResource(Res.string.retry), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

/**
 * Empty state placeholder.
 */
@Composable
fun EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Default.Info,
    title: String = stringResource(Res.string.no_data),
    description: String = stringResource(Res.string.nothing_to_display),
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color.Gray)
        Spacer(modifier = Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = Color.Gray)
        Spacer(modifier = Modifier.height(4.dp))
        Text(description, style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
    }
}

/**
 * Loading indicator overlay.
 */
@Composable
fun LoadingOverlay(
    isLoading: Boolean,
    modifier: Modifier = Modifier,
    message: String = stringResource(Res.string.loading),
) {
    if (isLoading) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.padding(24.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                shape = MaterialTheme.shapes.medium,
                shadowElevation = 8.dp,
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/**
 * Confirmation dialog.
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    confirmText: String = stringResource(Res.string.confirm),
    dismissText: String = stringResource(Res.string.cancel),
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = {
                onConfirm()
                onDismiss()
            }) { Text(confirmText) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissText) }
        },
    )
}

// Refresh button with ESI cooldown countdown.
// Shows "Xm Ys" when data is still fresh; disables button during cooldown.
@Composable
fun EsiRefreshButton(
    isLoading: Boolean,
    expiresAtMs: Long?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    // When set, renders as a text button (e.g. "Refresh Orders") instead of a bare icon.
    label: String? = null,
    // Extra disable condition beyond this button's own isLoading/cooldown — e.g. a sibling
    // refresh that touches the same data is in flight.
    enabled: Boolean = true,
) {
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            nowMs = System.currentTimeMillis()
        }
    }

    val remainingSec = expiresAtMs?.let { ((it - nowMs) / 1000).coerceAtLeast(0) } ?: 0L
    val coolingDown = remainingSec > 0
    val cooldownText =
        if (coolingDown) {
            val mins = remainingSec / 60
            val secs = remainingSec % 60
            if (mins >
                0
            ) {
                stringResource(Res.string.cooldown_min_sec, mins, "%02d".format(secs))
            } else {
                stringResource(Res.string.cooldown_sec, secs)
            }
        } else {
            null
        }

    if (label != null) {
        TextButton(onClick = onClick, modifier = modifier, enabled = enabled && !isLoading && !coolingDown) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(6.dp))
            }
            Text(if (cooldownText != null) "$label ($cooldownText)" else label)
        }
        return
    }

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (cooldownText != null) {
            Text(
                cooldownText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(2.dp))
        }
        IconButton(onClick = onClick, enabled = enabled && !isLoading && !coolingDown) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = stringResource(Res.string.refresh),
                    tint =
                        if (coolingDown) {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                )
            }
        }
    }
}

/** Runs [action] on a right mouse button press — e.g. "open the chart" on a table row. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun Modifier.onRightClick(action: () -> Unit): Modifier =
    this.onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press) {
        if (it.buttons.isSecondaryPressed) action()
    }

@Composable
private fun corpFeatureName(feature: CorpFeature): String =
    stringResource(
        when (feature) {
            CorpFeature.WALLET -> Res.string.corp_feature_wallet
            CorpFeature.ASSETS -> Res.string.corp_feature_assets
            CorpFeature.ORDERS -> Res.string.corp_feature_orders
            CorpFeature.CONTRACTS -> Res.string.corp_feature_contracts
        },
    )
