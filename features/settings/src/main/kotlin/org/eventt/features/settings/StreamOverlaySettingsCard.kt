package org.eventt.features.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eventt.core.database.StaticDataDao
import org.eventt.features.overlay.OverlayPeriod
import org.eventt.features.overlay.StreamOverlayConfig
import org.eventt.features.overlay.StreamOverlayPage
import org.eventt.features.overlay.StreamOverlayServer
import org.eventt.settings.generated.resources.*
import org.eventt.ui.common.*
import org.jetbrains.compose.resources.stringResource
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private const val ACCENT_SETTING = "stream_overlay.accent"
private const val DEFAULT_ACCENT = "00e5ff"
private const val HIDE_SETTING = "stream_overlay.hide"
private val DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy")

private val PERIODS =
    listOf(
        OverlayPeriod.SESSION to Res.string.overlay_period_session,
        OverlayPeriod.DAY to Res.string.overlay_period_24h,
        OverlayPeriod.WEEK to Res.string.overlay_period_7d,
        OverlayPeriod.MONTH to Res.string.overlay_period_30d,
    )

// Overlay sections that can be switched off (StreamOverlayPage's ?hide= keys).
private val SECTIONS =
    listOf(
        "chart" to Res.string.overlay_sec_chart,
        "sales" to Res.string.overlay_sec_sales,
        "top" to Res.string.overlay_sec_top,
        "orders" to Res.string.overlay_sec_orders,
    )

/**
 * Local OBS Browser Source overlay (issue #15) — start/stop the HTTP server, pick an accent
 * color, copy the URL to paste into OBS. The color travels as a query param on the URL itself
 * (see StreamOverlayPage) rather than a page reload, so re-copying after a color change is what
 * takes effect — same as any other OBS Browser Source URL edit.
 */
@Composable
internal fun StreamOverlaySettingsCard() {
    val scope = rememberCoroutineScope()
    val isRunning by StreamOverlayServer.isRunning.collectAsState()
    var accent by remember { mutableStateOf(DEFAULT_ACCENT) }
    var autostart by remember { mutableStateOf(false) }
    var hidden by remember { mutableStateOf(emptySet<String>()) }
    var period by remember { mutableStateOf(OverlayPeriod.SESSION) }
    var fixedStart by remember { mutableStateOf<LocalDate?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            accent = StaticDataDao.getSetting(ACCENT_SETTING)?.takeIf { it.isNotBlank() } ?: DEFAULT_ACCENT
            autostart = StreamOverlayServer.isAutostartEnabled()
            period = StreamOverlayConfig.period
            fixedStart = StreamOverlayConfig.fixedStart
            hidden =
                StaticDataDao
                    .getSetting(HIDE_SETTING)
                    .orEmpty()
                    .split(",")
                    .filter { it.isNotBlank() }
                    .toSet()
        }
    }

    fun saveAccent(hex: String) {
        accent = hex
        scope.launch(Dispatchers.IO) { StaticDataDao.setSetting(ACCENT_SETTING, hex) }
    }

    fun toggle(section: String) {
        hidden = if (section in hidden) hidden - section else hidden + section
        val v = SECTIONS.map { it.first }.filter { it in hidden }.joinToString(",")
        scope.launch(Dispatchers.IO) { StaticDataDao.setSetting(HIDE_SETTING, v) }
    }

    val hideParam = SECTIONS.map { it.first }.filter { it in hidden }.joinToString(",")
    val url = "http://127.0.0.1:${StreamOverlayServer.PORT}/?accent=$accent" + if (hideParam.isEmpty()) "" else "&hide=$hideParam"

    Section(stringResource(Res.string.overlay_title), stringResource(Res.string.overlay_desc)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton(if (isRunning) stringResource(Res.string.stop) else stringResource(Res.string.start)) {
                scope.launch(Dispatchers.IO) { if (isRunning) StreamOverlayServer.stop() else StreamOverlayServer.start() }
            }
            ActionButton(stringResource(Res.string.reset_session), enabled = isRunning) {
                scope.launch(Dispatchers.IO) { StreamOverlayServer.resetSession() }
            }
            OutlinedTextField(
                value = accent,
                onValueChange = { hex -> saveAccent(hex.filter { it.isLetterOrDigit() }.take(6)) },
                label = { Text(stringResource(Res.string.accent_hex)) },
                modifier = Modifier.width(140.dp),
                singleLine = true,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(Res.string.overlay_period), style = MaterialTheme.typography.bodySmall)
            PERIODS.forEach { (p, label) ->
                Segment(stringResource(label), p == period) {
                    period = p
                    scope.launch(Dispatchers.IO) { StreamOverlayConfig.period = p }
                }
            }
        }
        if (period != OverlayPeriod.SESSION) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(Res.string.overlay_count), style = MaterialTheme.typography.bodySmall)
                Segment(stringResource(Res.string.overlay_rolling, stringResource(PERIODS.toMap().getValue(period))), fixedStart == null) {
                    fixedStart = null
                    scope.launch(Dispatchers.IO) { StreamOverlayConfig.fixedStart = null }
                }
                val start = fixedStart
                Segment(
                    if (start ==
                        null
                    ) {
                        stringResource(Res.string.overlay_from_today)
                    } else {
                        stringResource(Res.string.overlay_from_date, start.format(DATE))
                    },
                    start != null,
                ) {
                    val today = LocalDate.now()
                    fixedStart = today
                    scope.launch(Dispatchers.IO) { StreamOverlayConfig.fixedStart = today }
                }
                if (start != null && start != LocalDate.now()) {
                    Text(
                        stringResource(Res.string.overlay_restart_today),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier =
                            Modifier.clickable {
                                val today = LocalDate.now()
                                fixedStart = today
                                scope.launch(Dispatchers.IO) { StreamOverlayConfig.fixedStart = today }
                            },
                    )
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(Res.string.overlay_sections), style = MaterialTheme.typography.bodySmall)
            SECTIONS.forEach { (key, label) -> Segment(stringResource(label), key !in hidden) { toggle(key) } }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = autostart,
                onCheckedChange = { checked ->
                    autostart = checked
                    scope.launch(Dispatchers.IO) { StreamOverlayServer.setAutostartEnabled(checked) }
                },
            )
            Text(stringResource(Res.string.overlay_autostart), style = MaterialTheme.typography.bodySmall)
        }

        if (isRunning) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(url, style = MaterialTheme.typography.bodySmall, fontFamily = Mono)
                ActionButton(stringResource(Res.string.copy_url)) {
                    val sel = StringSelection(url)
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
                }
            }
            val (w, h) = StreamOverlayPage.obsSize(hidden)
            Text(
                stringResource(Res.string.overlay_obs_size, w.toString(), h.toString()),
                style = MaterialTheme.typography.bodySmall,
                color = AXIS_COLOR,
            )
        }
    }
}
