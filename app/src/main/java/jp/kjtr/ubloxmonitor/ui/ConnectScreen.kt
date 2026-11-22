package jp.kjtr.ubloxmonitor.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import jp.kjtr.ubloxmonitor.R
import jp.kjtr.ubloxmonitor.data.MockStatus
import jp.kjtr.ubloxmonitor.data.MonitorRepository
import jp.kjtr.ubloxmonitor.data.source.Channel
import jp.kjtr.ubloxmonitor.domain.Display
import jp.kjtr.ubloxmonitor.domain.Link

/** 受信機側の選択肢（Python 版 RECEIVER_PORTS）。null は自動 */
private val RECEIVER_PORTS = listOf<Pair<Int, String?>>(
    R.string.port_auto to null, R.string.port_usb to "USB", R.string.port_uart1 to "UART1", R.string.port_uart2 to "UART2",
)

@Composable
fun ConnectScreen(repo: MonitorRepository, padding: PaddingValues) {
    val context = LocalContext.current
    val candidates by repo.candidates.collectAsState()
    val views by repo.views.collectAsState()
    val usbMessage by repo.usbMessage.collectAsState()
    val preferred by repo.preferredDevice.collectAsState()
    val mock by repo.mockState.collectAsState()
    val ublox = views[Channel.UBLOX]
    val connected = ublox?.running == true

    var deviceKey by rememberSaveable { mutableStateOf<String?>(null) }
    var baud by rememberSaveable { mutableIntStateOf(repo.prefs.baud) }
    var receiverPort by rememberSaveable { mutableStateOf(repo.prefs.receiverPort) }
    var autoSetup by rememberSaveable { mutableStateOf(repo.prefs.autoSetup) }

    LaunchedEffect(Unit) { repo.refreshDevices() }
    LaunchedEffect(preferred, candidates) {
        if (preferred != null && candidates.any { it.key == preferred }) deviceKey = preferred
        if (deviceKey == null || candidates.none { it.key == deviceKey }) deviceKey = candidates.firstOrNull()?.key
    }
    val candidate = candidates.firstOrNull { it.key == deviceKey }

    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Section(stringResource(R.string.connect_title)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (candidates.isEmpty()) {
                    Text(stringResource(R.string.no_devices), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                } else {
                    Dropdown(
                        stringResource(R.string.device), candidates.map { it.label to it.key }, deviceKey,
                        { deviceKey = it }, Modifier.weight(1f), enabled = !connected,
                    )
                }
                OutlinedButton(onClick = { repo.refreshDevices() }, enabled = !connected) { Text(stringResource(R.string.refresh)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Dropdown(
                    stringResource(R.string.baud), Link.BAUD_RATES.map { it.toString() to it }, baud,
                    { baud = it }, Modifier.weight(1f), enabled = !connected,
                )
                Dropdown(
                    stringResource(R.string.receiver_port), RECEIVER_PORTS.map { stringResource(it.first) to it.second },
                    receiverPort, { receiverPort = it }, Modifier.weight(1f), enabled = !connected,
                )
            }
            Text(stringResource(R.string.receiver_port_help), style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(autoSetup, { autoSetup = it; repo.prefs.autoSetup = it })
                Text(stringResource(R.string.auto_setup))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (connected) repo.disconnect()
                        else candidate?.let { repo.connect(it, baud, receiverPort, autoSetup) }
                    },
                    enabled = connected || candidate != null,
                ) { Text(stringResource(if (connected) R.string.disconnect else R.string.connect)) }
                OutlinedButton(onClick = { repo.applySetup() }, enabled = connected) {
                    Text(stringResource(R.string.setup_output))
                }
            }
            Text(stringResource(R.string.setup_output_help), style = MaterialTheme.typography.bodySmall)
            val info = if (connected) ublox?.info.orEmpty() else usbMessage
            if (info.isNotEmpty()) Text(info, style = MaterialTheme.typography.bodyMedium)
        }

        Section(stringResource(R.string.record_title)) {
            val recording = ublox?.record != null
            Text(Display.recordText(ublox?.record))
            Button(onClick = { repo.toggleRecord(Channel.UBLOX) }, enabled = connected || recording) {
                Text(stringResource(if (recording) R.string.record_stop else R.string.record_start))
            }
            Text(stringResource(R.string.record_folder, repo.logFolder.path), style = MaterialTheme.typography.bodySmall)
        }

        Section(stringResource(R.string.mock_title)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.mock_switch), Modifier.weight(1f))
                Switch(
                    checked = mock.status == MockStatus.ACTIVE || mock.status == MockStatus.WAITING,
                    onCheckedChange = { repo.setMock(it) },
                )
            }
            val statusText = when (mock.status) {
                MockStatus.OFF -> stringResource(R.string.mock_off)
                MockStatus.WAITING -> stringResource(R.string.mock_waiting)
                MockStatus.ACTIVE -> stringResource(R.string.mock_active, mock.published)
                MockStatus.NOT_ALLOWED -> stringResource(R.string.mock_not_selected)
            }
            Text(statusText, style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.mock_help), style = MaterialTheme.typography.bodySmall)
            if (mock.status == MockStatus.NOT_ALLOWED) {
                OutlinedButton(onClick = {
                    try {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                    } catch (_: ActivityNotFoundException) {
                        context.startActivity(Intent(Settings.ACTION_SETTINGS))
                    }
                }) { Text(stringResource(R.string.open_dev_options)) }
            }
        }
    }
}
