package org.eventt.features.p2pmarket

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eventt.core.database.CharacterDao
import org.eventt.core.database.NostrOrderModel
import org.eventt.core.database.P2pAttributionDefault
import org.eventt.core.database.StaticDataDao
import org.eventt.core.database.TransactionAttribution
import org.eventt.core.model.StaticRegionModel
import org.eventt.core.model.StaticTypeModel
import org.eventt.core.model.stringBlocking
import org.eventt.core.nostr.MinLotUnit
import org.eventt.core.nostr.NostrIdentityService
import org.eventt.core.nostr.OrderDraft
import org.eventt.core.nostr.OrderFilter
import org.eventt.core.nostr.OrderRepository
import org.eventt.core.nostr.OrderSide
import org.eventt.core.nostr.PostOrderResult
import org.eventt.p2pmarket.generated.resources.*
import org.eventt.ui.common.*
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import java.util.Locale

private data class MyOrderRowData(
    val order: NostrOrderModel,
    val typeName: String,
)

// Remembers the last region you posted in across posts, tab switches, and restarts — re-picking
// the same region (usually a home trade hub) for every single order got old fast.
private const val LAST_REGION_SETTING_KEY = "p2pmarket.last_region_id"

@Composable
fun MyOrdersScreen() {
    val scope = rememberCoroutineScope()

    var myOrders by remember { mutableStateOf<List<NostrOrderModel>>(emptyList()) }
    var myOrderRows by remember { mutableStateOf<List<MyOrderRowData>>(emptyList()) }

    var showPostForm by remember { mutableStateOf(false) }
    var side by remember { mutableStateOf(OrderSide.SELL) }
    var itemQuery by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf<StaticTypeModel?>(null) }
    var itemSearchResults by remember { mutableStateOf<List<StaticTypeModel>>(emptyList()) }
    var regionId by remember { mutableStateOf<Int?>(null) }
    var regionQuery by remember { mutableStateOf("") }
    var regionSearchResults by remember { mutableStateOf<List<StaticRegionModel>>(emptyList()) }
    var priceText by remember { mutableStateOf("") }
    var isSuggestingPrice by remember { mutableStateOf(false) }
    var priceSuggestionNote by remember { mutableStateOf<String?>(null) }
    var qtyText by remember { mutableStateOf("") }
    var minLotText by remember { mutableStateOf("1") }
    var minLotUnit by remember { mutableStateOf(MinLotUnit.UNITS) }
    var tradingAs by remember { mutableStateOf<String?>(null) }
    var isPosting by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }
    var defaultAttribution by remember { mutableStateOf<TransactionAttribution?>(null) }
    var editing by remember { mutableStateOf<NostrOrderModel?>(null) }

    LaunchedEffect(Unit) {
        defaultAttribution = withContext(Dispatchers.IO) { P2pAttributionDefault.get() }
    }

    LaunchedEffect(Unit) {
        val savedRegion =
            withContext(Dispatchers.IO) {
                StaticDataDao.getSetting(LAST_REGION_SETTING_KEY)?.toIntOrNull()?.let { StaticDataDao.getRegionById(it) }
            }
        savedRegion?.let {
            regionId = it.regionId
            regionQuery = it.name
        }
    }
    LaunchedEffect(Unit) {
        OrderRepository.browse(OrderFilter()).collect { orders ->
            myOrders = orders.filter { it.isMine }
        }
    }
    LaunchedEffect(myOrders) {
        myOrderRows =
            withContext(Dispatchers.IO) {
                myOrders.map { order ->
                    MyOrderRowData(
                        order,
                        StaticDataDao.getTypeById(order.typeId)?.name ?: stringBlocking(Res.string.type_n, order.typeId),
                    )
                }
            }
    }

    // Refreshed every time this tab is (re)entered, so switching the active character in Settings
    // is reflected here without a manual reload.
    LaunchedEffect(Unit) {
        val identity = withContext(Dispatchers.IO) { NostrIdentityService.getActiveIdentity() }
        tradingAs =
            identity?.let { id ->
                id.characterId?.let { withContext(Dispatchers.IO) { CharacterDao.getById(it)?.name } } ?: id.label
            }
    }

    // On-demand only (via the price field's suggest button) — never auto-fills, so it can never
    // silently clobber a price you've already typed in, and always reflects whichever side
    // (Selling/Buying) is currently selected instead of going stale after the first fill.
    fun suggestPrice() {
        val type = selectedType ?: return
        isSuggestingPrice = true
        scope.launch(Dispatchers.IO) {
            val charId = NostrIdentityService.getActiveIdentity()?.characterId
            val salesTaxPct = charId?.let { StaticDataDao.getCharSalesTax(it) } ?: 8.0
            val brokerFeePct = charId?.let { StaticDataDao.getCharBrokersFee(it) } ?: 3.0
            val recommended = SavingsBadgeService.recommendedPrice(type.typeId, side, salesTaxPct, brokerFeePct)
            withContext(Dispatchers.Main) {
                if (recommended != null) {
                    priceText = String.format(Locale.US, "%.2f", recommended.price)
                    val feeLabel = stringBlocking(if (side == OrderSide.SELL) Res.string.fee_sales_tax else Res.string.fee_broker)
                    val basePrice = String.format(Locale.US, "%,.2f", recommended.basePrice)
                    val adjustmentPct = String.format(Locale.US, "%.1f", recommended.adjustmentPct)
                    priceSuggestionNote = stringBlocking(Res.string.based_on, basePrice, adjustmentPct, feeLabel)
                } else {
                    priceSuggestionNote = stringBlocking(Res.string.no_forge_data)
                }
                isSuggestingPrice = false
            }
        }
    }

    fun submit() {
        val type = selectedType
        val region = regionId
        val price = priceText.toDoubleOrNull()
        val qty = qtyText.toLongOrNull()
        val minLot = minLotText.toLongOrNull()

        formError =
            when {
                type == null -> stringBlocking(Res.string.err_pick_item)
                region == null -> stringBlocking(Res.string.err_pick_region)
                price == null || price <= 0 -> stringBlocking(Res.string.err_invalid_price)
                qty == null || qty <= 0 -> stringBlocking(Res.string.err_invalid_qty)
                minLot == null || minLot <= 0 -> stringBlocking(Res.string.err_invalid_min_lot)
                else -> null
            }
        if (formError != null) return

        isPosting = true
        scope.launch(Dispatchers.IO) {
            val identity = NostrIdentityService.getActiveIdentity()
            if (identity == null) {
                withContext(Dispatchers.Main) {
                    formError = getString(Res.string.err_no_identity)
                    isPosting = false
                }
                return@launch
            }
            val traderName = identity.characterId?.let { CharacterDao.getById(it)?.name } ?: identity.label
            val result =
                OrderRepository.postNewOrder(
                    OrderDraft(
                        side = side,
                        typeId = type!!.typeId,
                        regionId = region!!,
                        price = price!!,
                        qtyTotal = qty!!,
                        minLot = minLot!!,
                        minLotUnit = minLotUnit,
                        traderChar = traderName,
                        traderCharId = identity.characterId,
                    ),
                )
            withContext(Dispatchers.Main) {
                isPosting = false
                when (result) {
                    is PostOrderResult.Posted -> {
                        itemQuery = ""
                        selectedType = null
                        priceText = ""
                        priceSuggestionNote = null
                        qtyText = ""
                        showPostForm = false
                    }

                    PostOrderResult.NoIdentity -> {
                        formError = getString(Res.string.err_no_identity)
                    }

                    PostOrderResult.RateLimited -> {
                        formError = getString(Res.string.err_rate_limited)
                    }
                }
            }
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showPostForm) {
            Panel(
                stringResource(Res.string.post_a_new_order),
                Modifier.fillMaxWidth(),
                header = {
                    Spacer(Modifier.weight(1f))
                    Segment("✕", false) { showPostForm = false }
                },
            ) {
                Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Segment(stringResource(Res.string.selling), side == OrderSide.SELL) { side = OrderSide.SELL }
                        Segment(stringResource(Res.string.buying), side == OrderSide.BUY) { side = OrderSide.BUY }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(modifier = Modifier.weight(1f)) {
                            SearchField(
                                query = itemQuery,
                                onQueryChange = { q ->
                                    itemQuery = q
                                    selectedType = null
                                    if (q.length >= 2) {
                                        scope.launch(Dispatchers.IO) {
                                            itemSearchResults = StaticDataDao.searchMarketTypes(q, limit = 10)
                                        }
                                    } else {
                                        itemSearchResults = emptyList()
                                    }
                                },
                                placeholder = stringResource(Res.string.search_item),
                            )
                            if (itemSearchResults.isNotEmpty()) {
                                LazyColumn(modifier = Modifier.heightIn(max = 140.dp)) {
                                    items(itemSearchResults, key = { it.typeId }) { type ->
                                        Row(
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        selectedType = type
                                                        itemQuery = type.name
                                                        itemSearchResults = emptyList()
                                                        priceText = ""
                                                    }.padding(8.dp),
                                        ) {
                                            Text(type.name, style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                }
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            SearchField(
                                query = regionQuery,
                                onQueryChange = { q ->
                                    regionQuery = q
                                    regionId = null
                                    if (q.length >= 2) {
                                        scope.launch(Dispatchers.IO) { regionSearchResults = StaticDataDao.searchRegions(q, limit = 10) }
                                    } else {
                                        regionSearchResults = emptyList()
                                    }
                                },
                                placeholder = stringResource(Res.string.search_region),
                            )
                            if (regionSearchResults.isNotEmpty()) {
                                LazyColumn(modifier = Modifier.heightIn(max = 140.dp)) {
                                    items(regionSearchResults, key = { it.regionId }) { region ->
                                        Row(
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        regionId = region.regionId
                                                        regionQuery = region.name
                                                        regionSearchResults = emptyList()
                                                        scope.launch(Dispatchers.IO) {
                                                            StaticDataDao.setSetting(LAST_REGION_SETTING_KEY, region.regionId.toString())
                                                        }
                                                    }.padding(8.dp),
                                        ) {
                                            Text(region.name, style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = priceText,
                            onValueChange = {
                                priceText = it
                                priceSuggestionNote = null
                            },
                            label = { Tip(stringResource(Res.string.tip_p_price)) { Text(stringResource(Res.string.price_per_unit)) } },
                            singleLine = true,
                            trailingIcon = {
                                IconButton(onClick = ::suggestPrice, enabled = selectedType != null && !isSuggestingPrice) {
                                    if (isSuggestingPrice) {
                                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                    } else {
                                        Icon(
                                            Icons.Default.Lightbulb,
                                            contentDescription = stringResource(Res.string.suggest_price),
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = qtyText,
                            onValueChange = { qtyText = it },
                            label = { Text(stringResource(Res.string.total_quantity)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    priceSuggestionNote?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = minLotText,
                            onValueChange = { minLotText = it },
                            label = { Tip(stringResource(Res.string.tip_p_min_lot)) { Text(stringResource(Res.string.min_lot)) } },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Segment(stringResource(Res.string.units), minLotUnit == MinLotUnit.UNITS) { minLotUnit = MinLotUnit.UNITS }
                        Segment("ISK", minLotUnit == MinLotUnit.ISK) { minLotUnit = MinLotUnit.ISK }
                    }
                    Text(
                        tradingAs?.let {
                            stringResource(
                                Res.string.trading_as,
                                it,
                            )
                        } ?: stringResource(Res.string.no_char_selected_settings),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (tradingAs != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                    formError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Button(onClick = { submit() }, enabled = !isPosting) {
                        if (isPosting) {
                            CircularProgressIndicator(Modifier.height(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.height(4.dp))
                        }
                        Text(if (isPosting) stringResource(Res.string.posting) else stringResource(Res.string.post_order))
                    }
                }
            }
        }
        val now = System.currentTimeMillis() / 1000
        Panel(
            stringResource(Res.string.my_active_orders),
            Modifier.fillMaxWidth().weight(1f),
            header = {
                Spacer(Modifier.weight(1f))
                Text(stringResource(Res.string.attribute_new_trades), style = MaterialTheme.typography.labelSmall, color = AXIS_COLOR)
                AttributionPicker(
                    defaultAttribution,
                    onSelect = { attribution ->
                        defaultAttribution = attribution
                        scope.launch(Dispatchers.IO) { P2pAttributionDefault.set(attribution) }
                    },
                )
                if (!showPostForm) Segment("+ " + stringResource(Res.string.post_new_order), true) { showPostForm = true }
            },
        ) {
            val cols =
                listOf<Col<MyOrderRowData>>(
                    Col(stringResource(Res.string.h_side), 0.5f, end = false) { it.order.side },
                    Col(stringResource(Res.string.h_item), 2.4f, end = false) { it.typeName.lowercase() },
                    Col(stringResource(Res.string.h_price), 1f) { it.order.price },
                    Col(stringResource(Res.string.h_qty), 0.9f) { it.order.qtyRemaining },
                    Col(stringResource(Res.string.h_value), 1f, tip = stringResource(Res.string.tip_p_value), sort = {
                        it.order.qtyRemaining *
                            it.order.price
                    }),
                    Col(
                        stringResource(Res.string.h_expires),
                        0.8f,
                        tip = stringResource(Res.string.tip_p_my_expires),
                        sort = { it.order.expiration },
                    ),
                    Col("", 1.8f),
                )
            Table(
                cols = cols,
                rows = myOrderRows,
                empty = stringResource(Res.string.no_active_orders_yet),
                defaultSort = 5,
                defaultDesc = false,
                key = { "${it.order.orderUuid}:${it.order.pubkey}" },
            ) { row ->
                val order = row.order
                listOf(
                    Cell(order.side) { OrderSideBadge(OrderSide.valueOf(order.side.uppercase())) },
                    Cell(row.typeName) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TypeIcon(order.typeId, size = 18.dp)
                            Column {
                                Text(
                                    row.typeName,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                ExpiringSoonLabel(order.expiration)
                            }
                        }
                    },
                    Cell(formatPriceAbbr(order.price)),
                    Cell("${formatVolume(order.qtyRemaining)}/${formatVolume(order.qtyTotal)}"),
                    Cell(formatIsk(order.qtyRemaining * order.price), AXIS_COLOR),
                    Cell(formatDurationShort(order.expiration - now), AXIS_COLOR),
                    Cell("") {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Segment(stringResource(Res.string.edit), false) { editing = order }
                            Segment(stringResource(Res.string.renew), false) {
                                scope.launch(Dispatchers.IO) { OrderRepository.renewOrder(order) }
                            }
                            Segment(stringResource(Res.string.cancel), false) {
                                scope.launch(Dispatchers.IO) { OrderRepository.cancelOrder(order) }
                            }
                        }
                    },
                )
            }
        }
    }

    editing?.let { order ->
        EditOrderDialog(
            order = order,
            onDismiss = { editing = null },
            onSave = { newPrice, newQtyRemaining ->
                scope.launch(Dispatchers.IO) { OrderRepository.editOrder(order, newPrice, newQtyRemaining) }
                editing = null
            },
        )
    }
}

// Price/qty are the only fields that actually change trader intent — region, item, side and min
// lot stay fixed for the life of an order_uuid (changing any of those is a new order, not an
// edit). Qty here is what's *remaining* right now (matching the qty column everywhere else),
// not the order's original total; already-reserved stock still carries over untouched, see
// OrderRepository.editOrder.
@Composable
private fun EditOrderDialog(
    order: NostrOrderModel,
    onDismiss: () -> Unit,
    onSave: (newPrice: Double, newQtyRemaining: Long) -> Unit,
) {
    var priceText by remember { mutableStateOf(String.format(Locale.US, "%.2f", order.price)) }
    var qtyText by remember { mutableStateOf(order.qtyRemaining.toString()) }
    val price = priceText.toDoubleOrNull()
    val qty = qtyText.toLongOrNull()
    val error =
        when {
            price == null || price <= 0 -> stringResource(Res.string.err_invalid_price)
            qty == null || qty <= 0 -> stringResource(Res.string.err_invalid_qty)
            else -> null
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.edit_order)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = priceText,
                    onValueChange = { priceText = it },
                    label = { Tip(stringResource(Res.string.tip_p_price)) { Text(stringResource(Res.string.price_per_unit)) } },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = qtyText,
                    onValueChange = { qtyText = it },
                    label = { Text(stringResource(Res.string.quantity_available)) },
                    singleLine = true,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(price!!, qty!!) }, enabled = error == null) { Text(stringResource(Res.string.save)) }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
        },
    )
}
