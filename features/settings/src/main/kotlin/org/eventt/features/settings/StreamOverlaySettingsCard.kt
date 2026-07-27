package org.eventt.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import org.eventt.features.overlay.StreamOverlayServer
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

private const val ACCENT_SETTING = "stream_overlay.accent"
private const val DEFAULT_ACCENT = "00e5ff"

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

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            accent = StaticDataDao.getSetting(ACCENT_SETTING)?.takeIf { it.isNotBlank() } ?: DEFAULT_ACCENT
        }
    }

    fun saveAccent(hex: String) {
        accent = hex
        scope.launch(Dispatchers.IO) { StaticDataDao.setSetting(ACCENT_SETTING, hex) }
    }

    val url = "http://127.0.0.1:${StreamOverlayServer.PORT}/?accent=$accent"

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Stream Overlay (OBS)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Serves a transparent, animated overlay page (session trades, profit, timer, relists) for " +
                    "OBS's Browser Source. Local only — nothing here is reachable off this machine.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = {
                    scope.launch(Dispatchers.IO) { if (isRunning) StreamOverlayServer.stop() else StreamOverlayServer.start() }
                }) {
                    Text(if (isRunning) "Stop" else "Start")
                }
                OutlinedButton(
                    enabled = isRunning,
                    onClick = { scope.launch(Dispatchers.IO) { StreamOverlayServer.resetSession() } },
                ) {
                    Text("Reset session")
                }
                OutlinedTextField(
                    value = accent,
                    onValueChange = { hex -> saveAccent(hex.filter { it.isLetterOrDigit() }.take(6)) },
                    label = { Text("Accent (hex)") },
                    modifier = Modifier.width(140.dp),
                    singleLine = true,
                )
            }

            if (isRunning) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(url, style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = {
                        val sel = StringSelection(url)
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
                    }) {
                        Text("Copy URL")
                    }
                }
            }
        }
    }
}
