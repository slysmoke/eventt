package org.eventt.features.market

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.eventt.core.model.StaticMarketGroupModel
import org.eventt.core.model.StaticRegionModel
import org.eventt.core.model.StaticStationModel

// ─── Filter bar design tokens ──────────────────────────────────────────────
// A single fixed control height + shape shared by every dropdown chip and text field so a
// row of mixed controls (pickers, numeric inputs, checkboxes) lines up pixel-for-pixel instead
// of drifting like Material's default OutlinedTextField (56.dp) vs. a hand-rolled chip (~38.dp).

internal val FilterFieldHeight = 36.dp
private val FilterFieldShape = RoundedCornerShape(8.dp)
private val FilterLabelHeight = 16.dp
private val FilterLabelGap = 4.dp

// ─── Filter bar container ─────────────────────────────────────────────────

@Composable
internal fun FilterBar(content: @Composable ColumnScope.() -> Unit) {
    Column {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
    }
}

// ─── Shared chip surface (base for all filter dropdowns) ─────────────────

@Composable
private fun ChipSurface(
    onClick: () -> Unit,
    width: Dp,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = FilterFieldShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = modifier.width(width).height(FilterFieldHeight),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

// ─── Label + control column wrapper ──────────────────────────────────────
// Every control — chip, text field, checkbox, switch — is centered in a fixed-height slot
// below a fixed-height label row, so an entire FlowRow of unrelated control types shares one
// visual baseline without per-call-site alignment hacks.

@Composable
internal fun FilterControl(
    label: String,
    content: @Composable BoxScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(FilterLabelGap)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            maxLines = 1,
            modifier = Modifier.height(FilterLabelHeight),
        )
        Box(modifier = Modifier.height(FilterFieldHeight), contentAlignment = Alignment.CenterStart, content = content)
    }
}

// ─── Unlabeled slot (buttons, status text) aligned to the same baseline as FilterControl ───

@Composable
internal fun FilterActionSlot(content: @Composable () -> Unit) {
    Column {
        Spacer(Modifier.height(FilterLabelHeight + FilterLabelGap))
        content()
    }
}

// ─── Compact text field matching ChipSurface's exact height/shape/border ──────────────────
// Material3's OutlinedTextField defaults to a 56.dp min height with no low-level way to shrink
// it for a dense toolbar, so numeric filters use this BasicTextField instead — same visual
// language as the dropdown chips (border, shape, background) plus a focus-state outline.

@Composable
private fun CompactTextField(
    value: String,
    onValueChange: (String) -> Unit,
    width: Dp,
    enabled: Boolean = true,
    placeholder: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val borderColor =
        when {
            isFocused -> colors.primary
            else -> colors.outline.copy(alpha = 0.35f)
        }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        interactionSource = interactionSource,
        textStyle =
            MaterialTheme.typography.bodySmall.copy(
                color = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.4f),
            ),
        cursorBrush = SolidColor(colors.primary),
        modifier = Modifier.width(width).height(FilterFieldHeight),
        decorationBox = { innerField ->
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .clip(FilterFieldShape)
                        .background(colors.surfaceVariant.copy(alpha = if (enabled) 0.5f else 0.25f))
                        .border(if (isFocused) 1.5.dp else 1.dp, borderColor, FilterFieldShape)
                        .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty() && placeholder != null) {
                    Text(placeholder, style = MaterialTheme.typography.bodySmall, color = colors.onSurface.copy(alpha = 0.35f))
                }
                innerField()
            }
        },
    )
}

// ─── Checkbox + numeric field, styled to match every other FilterControl-based control ────

@Composable
internal fun CheckboxParamField(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    value: String,
    onValueChange: (String) -> Unit,
    fieldWidth: Dp = 68.dp,
    // Station Trading's "Vol %" checkbox genuinely means cap-on/cap-off, so disabling the field
    // when unchecked (the default here) is correct. Inter-Region reuses this same component but
    // the checkbox instead picks which side ("Src"/"Dst") the vol% applies to — with the default
    // `enabled = checked`, the "Dst vol %" state (unchecked) could never be edited, since checking
    // the box just relabels it to "Src vol %" rather than unlocking "Dst". Callers where the field
    // should stay editable regardless of the checkbox pass `enabled = true` explicitly.
    fieldEnabled: Boolean = checked,
) {
    FilterControl(label) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = onCheckedChange, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(4.dp))
            CompactTextField(value = value, onValueChange = onValueChange, width = fieldWidth, enabled = fieldEnabled)
        }
    }
}

// ─── "All categories + ESI history" hint ──────────────────────────────────
// With no category filter, every profitable candidate costs one live ESI history request —
// the EVE Ref bulk download (Settings) turns those into local DB lookups.

@Composable
internal fun EveRefHint() {
    Text(
        "Tip: scanning all categories fetches price history from ESI per profitable item — " +
            "enable EVE Ref bulk data in Settings to make history lookups local and instant.",
        style = MaterialTheme.typography.labelSmall,
        color = Color(0xFFFFB74D),
    )
}

// ─── Visual separator between filter sections ─────────────────────────────
// Top-padded past the label row so the line sits alongside the fields — FlowRow lines are
// label+field tall (top-aligned), and an unpadded divider would float at label level instead.

@Composable
internal fun FilterDivider() {
    VerticalDivider(
        modifier =
            Modifier
                .padding(top = FilterLabelHeight + FilterLabelGap)
                .height(FilterFieldHeight)
                .padding(horizontal = 4.dp),
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
    )
}

// ─── Route arrow between buy-side and sell-side pickers (Inter-Region only) ────────────────
// Same label-row top offset as FilterDivider, so the arrow points between the chips themselves.

@Composable
internal fun RouteArrow() {
    Box(
        modifier =
            Modifier
                .padding(top = FilterLabelHeight + FilterLabelGap)
                .height(FilterFieldHeight)
                .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            null,
            Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
        )
    }
}

// ─── Region picker ────────────────────────────────────────────────────────

@Composable
internal fun RegionPicker(
    allRegions: List<StaticRegionModel>,
    selectedRegionId: Int,
    width: Dp = 160.dp,
    label: String = "Region",
    accentColor: Color? = null,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val selectedName =
        remember(selectedRegionId, allRegions) {
            allRegions.find { it.regionId == selectedRegionId }?.name ?: "—"
        }
    val filtered =
        remember(searchQuery, allRegions) {
            if (allRegions.isEmpty()) {
                emptyList()
            } else if (searchQuery.isBlank()) {
                allRegions.take(14)
            } else {
                allRegions.filter { it.name.contains(searchQuery, ignoreCase = true) }.take(14)
            }
        }

    FilterControl(label) {
        Box {
            ChipSurface(onClick = {
                expanded = true
                searchQuery = ""
            }, width = width) {
                if (accentColor != null) {
                    Box(
                        modifier =
                            Modifier
                                .size(6.dp)
                                .background(accentColor, androidx.compose.foundation.shape.CircleShape),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    selectedName,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null,
                    Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = {
                    expanded = false
                    searchQuery = ""
                },
                modifier = Modifier.width(width + 40.dp).heightIn(max = 360.dp),
            ) {
                Box(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall,
                        singleLine = true,
                        placeholder = { Text("Search…", style = MaterialTheme.typography.bodySmall, color = Color.Gray) },
                        leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(16.dp)) },
                    )
                }
                HorizontalDivider()
                if (filtered.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("No match for \"$searchQuery\"", style = MaterialTheme.typography.bodySmall, color = Color.Gray) },
                        onClick = {},
                        enabled = false,
                    )
                } else {
                    filtered.forEach { region ->
                        DropdownMenuItem(
                            text = { Text(region.name, style = MaterialTheme.typography.bodySmall) },
                            onClick = {
                                onSelect(region.regionId)
                                expanded = false
                                searchQuery = ""
                            },
                            leadingIcon =
                                if (region.regionId == selectedRegionId) {
                                    { Icon(Icons.Default.Check, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.primary) }
                                } else {
                                    null
                                },
                        )
                    }
                }
            }
        }
    }
}

// ─── Station picker ───────────────────────────────────────────────────────

@Composable
internal fun StationPicker(
    stations: List<StaticStationModel>,
    selectedStationId: Long?,
    width: Dp = 200.dp,
    label: String = "Station",
    onSelect: (Long?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val selectedName =
        remember(selectedStationId, stations) {
            stations.find { it.stationId == selectedStationId }?.name ?: "All stations"
        }
    val filtered =
        remember(searchQuery, stations) {
            if (searchQuery.isBlank()) {
                stations.take(14)
            } else {
                stations.filter { it.name.contains(searchQuery, ignoreCase = true) }.take(14)
            }
        }

    FilterControl(label) {
        Box {
            ChipSurface(onClick = {
                expanded = true
                searchQuery = ""
            }, width = width) {
                Text(
                    selectedName,
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (selectedStationId == null) {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (selectedStationId != null) {
                    Icon(
                        Icons.Default.Close,
                        null,
                        Modifier.size(12.dp).clickable { onSelect(null) },
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                    Spacer(Modifier.width(2.dp))
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null,
                    Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = {
                    expanded = false
                    searchQuery = ""
                },
                modifier = Modifier.width(width + 120.dp).heightIn(max = 400.dp),
            ) {
                Box(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall,
                        singleLine = true,
                        placeholder = { Text("Search station…", style = MaterialTheme.typography.bodySmall, color = Color.Gray) },
                        leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(16.dp)) },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("All stations", style = MaterialTheme.typography.bodySmall, color = Color.Gray) },
                    onClick = {
                        onSelect(null)
                        expanded = false
                        searchQuery = ""
                    },
                    leadingIcon =
                        if (selectedStationId == null) {
                            { Icon(Icons.Default.Check, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.primary) }
                        } else {
                            null
                        },
                )
                HorizontalDivider()
                if (stations.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("No stations in this region", style = MaterialTheme.typography.bodySmall, color = Color.Gray) },
                        onClick = {},
                        enabled = false,
                    )
                } else if (filtered.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("No match for \"$searchQuery\"", style = MaterialTheme.typography.bodySmall, color = Color.Gray) },
                        onClick = {},
                        enabled = false,
                    )
                } else {
                    filtered.forEach { station ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    station.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            onClick = {
                                onSelect(station.stationId)
                                expanded = false
                                searchQuery = ""
                            },
                            leadingIcon =
                                if (station.stationId == selectedStationId) {
                                    { Icon(Icons.Default.Check, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.primary) }
                                } else {
                                    null
                                },
                        )
                    }
                }
            }
        }
    }
}

// ─── Group dropdown (category / subcategory) ─────────────────────────────

@Composable
internal fun GroupDropdown(
    label: String,
    groups: List<StaticMarketGroupModel>,
    selected: StaticMarketGroupModel?,
    placeholder: String,
    width: Dp,
    onSelect: (StaticMarketGroupModel?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    FilterControl(label) {
        Box {
            ChipSurface(onClick = { expanded = !expanded }, width = width) {
                Text(
                    selected?.name ?: placeholder,
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (selected == null) {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (selected != null) {
                    Icon(
                        Icons.Default.Close,
                        null,
                        Modifier.size(13.dp).clickable { onSelect(null) },
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                    Spacer(Modifier.width(2.dp))
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null,
                    Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.width(width + 20.dp).heightIn(max = 300.dp),
            ) {
                DropdownMenuItem(
                    text = { Text(placeholder, style = MaterialTheme.typography.bodySmall, color = Color.Gray) },
                    onClick = {
                        onSelect(null)
                        expanded = false
                    },
                )
                HorizontalDivider()
                groups.forEach { g ->
                    DropdownMenuItem(
                        text = { Text(g.name, style = MaterialTheme.typography.bodySmall) },
                        onClick = {
                            onSelect(g)
                            expanded = false
                        },
                        leadingIcon =
                            if (g == selected) {
                                { Icon(Icons.Default.Check, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.primary) }
                            } else {
                                null
                            },
                    )
                }
            }
        }
    }
}

// ─── Trade type chip ──────────────────────────────────────────────────────

@Composable
internal fun TradeTypeChip(
    selected: InterRegionTradeType,
    onSelect: (InterRegionTradeType) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    FilterControl("Trade Type") {
        Box {
            ChipSurface(onClick = { expanded = !expanded }, width = 175.dp) {
                Text(
                    selected.label,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null,
                    Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.width(210.dp),
            ) {
                InterRegionTradeType.entries.forEach { t ->
                    DropdownMenuItem(
                        text = { Text(t.label, style = MaterialTheme.typography.bodySmall) },
                        onClick = {
                            onSelect(t)
                            expanded = false
                        },
                        leadingIcon =
                            if (t == selected) {
                                { Icon(Icons.Default.Check, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.primary) }
                            } else {
                                null
                            },
                    )
                }
            }
        }
    }
}

// ─── Compact number field ─────────────────────────────────────────────────

@Composable
internal fun ParamField(
    label: String,
    value: String,
    width: Dp,
    onValue: (String) -> Unit,
) {
    FilterControl(label) {
        CompactTextField(value = value, onValueChange = onValue, width = width)
    }
}
