package jp.kjtr.ubloxmonitor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jp.kjtr.ubloxmonitor.R
import jp.kjtr.ubloxmonitor.data.CorrectionForm
import jp.kjtr.ubloxmonitor.data.CorrectionView
import jp.kjtr.ubloxmonitor.data.MonitorRepository
import jp.kjtr.ubloxmonitor.data.source.Channel
import jp.kjtr.ubloxmonitor.domain.CorrectionKind
import jp.kjtr.ubloxmonitor.domain.Link
import jp.kjtr.ubloxmonitor.domain.Mount
import jp.kjtr.ubloxmonitor.domain.Ntrip
import jp.kjtr.ubloxmonitor.domain.Rtcm

/** バイト数の表示文字列（Python 版 format_bytes）。 */
private fun formatBytes(size: Long): String = when {
    size >= 1e6 -> "%.2f MB".format(size / 1e6)
    size >= 1e3 -> "%.1f kB".format(size / 1e3)
    else -> "$size B"
}

/**
 * RTK 補正（Python 版 CorrectionPanel）。NTRIP / TCP / USB シリアルから補正情報を受け、u-blox 受信機へ転送する。
 * 受信中は受信元の入力を変えられない（転送の ON/OFF だけは変えられる）。
 */
@Composable
fun CorrectionScreen(repo: MonitorRepository, padding: PaddingValues) {
    val manager = repo.correction
    val form by manager.form.collectAsState()
    val view by manager.view.collectAsState()
    val message by manager.message.collectAsState()
    val mounts by manager.mounts.collectAsState()
    val views by repo.views.collectAsState()
    val allCandidates by repo.candidates.collectAsState()
    val candidates = repo.correctionCandidates(allCandidates)
    val ublox = views[Channel.UBLOX]
    val ubloxConnected = ublox?.running == true
    var showMounts by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        repo.refreshDevices()
        manager.publish()
    }
    LaunchedEffect(candidates.map { it.key }) {
        val f = manager.form.value
        if (f.serialDevice == null || candidates.none { it.key == f.serialDevice }) {
            candidates.firstOrNull()?.let { manager.updateForm(f.copy(serialDevice = it.key)) }
        }
    }
    // 一覧の距離は u-blox（無ければ内蔵 GNSS）の位置から
    val here = (ublox?.snapshot?.pvt ?: views[Channel.INTERNAL]?.snapshot?.pvt)
        ?.let { p -> p.latitude?.let { lat -> p.longitude?.let { lon -> lat to lon } } }

    BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
        val wide = maxWidth >= 600.dp
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val source: @Composable () -> Unit = {
                SourceSection(
                    form, view.running, ubloxConnected, ublox?.let { repo.receiverPort() }, message, mounts.loading,
                    candidates.map { it.label to it.key },
                    onChange = { manager.updateForm(it) },
                    onToggle = { if (view.running) manager.stop() else manager.start() },
                    onFetch = {
                        manager.fetchMounts()
                        showMounts = true
                    },
                    onRefreshDevices = { repo.refreshDevices() },
                    onEnableInput = { repo.enableRtcmInput() },
                )
            }
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        source()
                        StatusSection(view)
                    }
                    Column(Modifier.weight(1f)) { MessagesSection(view) }
                }
            } else {
                source()
                StatusSection(view)
                MessagesSection(view)
            }
        }
    }

    if (showMounts && !mounts.loading && mounts.mounts.isNotEmpty()) {
        MountDialog(mounts.mounts, here, onDismiss = { showMounts = false }) { m ->
            showMounts = false
            manager.updateForm(form.copy(mount = m.mount))
        }
    }
}

@Composable
private fun SourceSection(
    form: CorrectionForm,
    running: Boolean,
    ubloxConnected: Boolean,
    receiverPort: String?,
    message: String,
    loadingMounts: Boolean,
    devices: List<Pair<String, String>>,
    onChange: (CorrectionForm) -> Unit,
    onToggle: () -> Unit,
    onFetch: () -> Unit,
    onRefreshDevices: () -> Unit,
    onEnableInput: () -> Unit,
) {
    val editable = !running
    Section(stringResource(R.string.corr_source)) {
        Dropdown(
            stringResource(R.string.corr_kind),
            listOf(
                stringResource(R.string.corr_kind_ntrip) to CorrectionKind.NTRIP,
                stringResource(R.string.corr_kind_tcp) to CorrectionKind.TCP,
                stringResource(R.string.corr_kind_serial) to CorrectionKind.SERIAL,
            ),
            form.kind, { onChange(form.copy(kind = it)) }, Modifier.fillMaxWidth(), enabled = editable,
        )
        if (form.kind != CorrectionKind.SERIAL) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    form.host, { onChange(form.copy(host = it)) }, Modifier.weight(1f), enabled = editable, singleLine = true,
                    label = { Text(stringResource(R.string.corr_host)) },
                    placeholder = { Text(stringResource(R.string.corr_host_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    form.port, { onChange(form.copy(port = it.filter(Char::isDigit).take(5))) }, Modifier.width(96.dp),
                    enabled = editable, singleLine = true, label = { Text(stringResource(R.string.corr_port)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        }
        if (form.kind == CorrectionKind.NTRIP) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(form.tls, { onChange(form.copy(tls = it)) }, enabled = editable)
                    Text(stringResource(R.string.corr_tls))
                }
                Dropdown(
                    stringResource(R.string.corr_version), listOf("NTRIP 2.0" to 2, "NTRIP 1.0" to 1), form.version,
                    { onChange(form.copy(version = it)) }, Modifier.weight(1f), enabled = editable,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    form.mount, { onChange(form.copy(mount = it)) }, Modifier.weight(1f), enabled = editable, singleLine = true,
                    label = { Text(stringResource(R.string.corr_mount)) },
                )
                OutlinedButton(onClick = onFetch, enabled = editable && !loadingMounts) { Text(stringResource(R.string.corr_fetch)) }
            }
            OutlinedTextField(
                form.user, { onChange(form.copy(user = it)) }, Modifier.fillMaxWidth(), enabled = editable, singleLine = true,
                label = { Text(stringResource(R.string.corr_user)) },
            )
            OutlinedTextField(
                form.password, { onChange(form.copy(password = it)) }, Modifier.fillMaxWidth(), enabled = editable,
                singleLine = true, label = { Text(stringResource(R.string.corr_password)) },
                placeholder = {
                    Text(stringResource(if (form.savePassword) R.string.corr_password_hint_on else R.string.corr_password_hint_off))
                },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(form.savePassword, { onChange(form.copy(savePassword = it)) }, enabled = editable)
                Text(stringResource(R.string.corr_save_password))
            }
            Text(stringResource(R.string.corr_save_password_help), style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(form.gga, { onChange(form.copy(gga = it)) }, enabled = editable)
                    Text(stringResource(R.string.corr_gga))
                }
                OutlinedTextField(
                    form.ggaInterval, { onChange(form.copy(ggaInterval = it.filter(Char::isDigit).take(3))) },
                    Modifier.width(110.dp), enabled = editable && form.gga, singleLine = true,
                    label = { Text(stringResource(R.string.corr_gga_interval)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            Text(stringResource(R.string.corr_gga_help), style = MaterialTheme.typography.bodySmall)
        }
        if (form.kind == CorrectionKind.SERIAL) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (devices.isEmpty()) {
                    Text(stringResource(R.string.corr_serial_none), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                } else {
                    Dropdown(
                        stringResource(R.string.corr_serial_device), devices, form.serialDevice ?: devices.first().second,
                        { onChange(form.copy(serialDevice = it)) }, Modifier.weight(1f), enabled = editable,
                    )
                }
                OutlinedButton(onClick = onRefreshDevices, enabled = editable) { Text(stringResource(R.string.refresh)) }
            }
            Dropdown(
                stringResource(R.string.corr_baud), Link.BAUD_RATES.map { it.toString() to it }, form.serialBaud,
                { onChange(form.copy(serialBaud = it)) }, Modifier.fillMaxWidth(), enabled = editable,
            )
            Text(stringResource(R.string.corr_serial_help), style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onToggle) { Text(stringResource(if (running) R.string.corr_stop else R.string.corr_start)) }
            Checkbox(form.forward, { onChange(form.copy(forward = it)) })
            Text(stringResource(R.string.corr_forward))
        }
        Text(stringResource(R.string.corr_reconnect_help), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onEnableInput, enabled = ubloxConnected) { Text(stringResource(R.string.corr_enable_input)) }
        Text(stringResource(R.string.corr_enable_input_help, receiverPort ?: "—"), style = MaterialTheme.typography.bodySmall)
        if (form.kind != CorrectionKind.SERIAL) {
            Text(stringResource(R.string.corr_notice), style = MaterialTheme.typography.bodySmall)
        }
        if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun StatusSection(view: CorrectionView) {
    val snap = view.snapshot
    val pvt = view.pvt
    val state = when {
        snap == null -> "—"
        else -> buildString {
            append(snap.status)
            snap.retryIn?.let { append("（%.0f 秒後）".format(it)) }
            snap.error?.let { append("（$it）") }
        }
    }
    val rows = listOf(
        R.string.corr_row_state to state,
        R.string.corr_row_bytes to (snap?.let { formatBytes(it.bytes) } ?: "—"),
        R.string.corr_row_rate to (snap?.let { "%.1f kbps".format(it.rate * 8 / 1000) } ?: "—"),
        R.string.corr_row_age to (snap?.age?.let { "%.1f 秒".format(it) } ?: "—"),
        R.string.corr_row_crc to (snap?.crcErrors?.toString() ?: "—"),
        R.string.corr_row_reconnects to (snap?.let { "${it.reconnects} 回" } ?: "—"),
        R.string.corr_row_station to (snap?.station?.let { "ID %d  %.7f°, %.7f°  %.2f m".format(it.id, it.lat, it.lon, it.height) } ?: "—"),
        R.string.corr_row_baseline to (view.baseline?.let { "%.3f km".format(it / 1000) } ?: "—"),
        R.string.corr_row_fix to (pvt?.fixLabel ?: "—"),
        R.string.corr_row_corr_age to (pvt?.correctionAgeText ?: "—"),
    )
    Section(stringResource(R.string.corr_status)) {
        SelectionContainer {
            Column {
                for ((res, value) in rows) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                        Text(stringResource(res), Modifier.weight(1f), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value, Modifier.weight(1.6f), fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
private fun MessagesSection(view: CorrectionView) {
    Section(stringResource(R.string.corr_messages)) {
        val rows = view.snapshot?.messages.orEmpty().map { (type, count, age) ->
            listOf(type.toString(), Rtcm.messageName(type), count.toString(), "%.1f".format(age))
        }
        if (rows.isEmpty()) {
            Text("—")
        } else {
            SimpleTable(
                listOf(
                    stringResource(R.string.corr_col_number), stringResource(R.string.corr_col_name),
                    stringResource(R.string.corr_col_count), stringResource(R.string.corr_col_age),
                ),
                rows, listOf(0.8f, 2.2f, 0.9f, 1.1f),
            )
        }
    }
}

/** ソーステーブルから選ぶ。現在位置が分かれば距離を出し、近い順に並べる。 */
@Composable
private fun MountDialog(mounts: List<Mount>, here: Pair<Double, Double>?, onDismiss: () -> Unit, onSelect: (Mount) -> Unit) {
    val withDistance = mounts.map { m ->
        val km = if (here != null && m.latitude != null && m.longitude != null) {
            Ntrip.distanceKm(here.first, here.second, m.latitude!!, m.longitude!!)
        } else null
        m to km
    }.let { list -> if (here != null) list.sortedBy { it.second ?: Double.MAX_VALUE } else list }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(stringResource(R.string.corr_mounts_title, mounts.size))
                if (here != null) Text(stringResource(R.string.corr_mount_sorted), style = MaterialTheme.typography.bodySmall)
            }
        },
        text = {
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                items(withDistance) { (m, km) ->
                    Column(Modifier.fillMaxWidth().clickable { onSelect(m) }.padding(vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(m.mount, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                            km?.let { Text("%.1f km".format(it), style = MaterialTheme.typography.bodySmall) }
                        }
                        Text("${m.id}  形式 ${m.format} ${m.details}", style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        Text(
                            "衛星系 ${m.nav}  位置 ${m.lat}, ${m.lon}" + if (m.nmea) "  ${stringResource(R.string.corr_mount_nmea)}" else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
