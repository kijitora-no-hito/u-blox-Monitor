package jp.kjtr.ubloxmonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import jp.kjtr.ubloxmonitor.R
import jp.kjtr.ubloxmonitor.data.MonitorRepository
import jp.kjtr.ublox.core.ConfigItem
import jp.kjtr.ublox.core.ReceiverConfig
import jp.kjtr.ublox.core.Ubx

/**
 * 受信機設定（Python 版 SettingsPanel）。接続時に読み出した現在値を表示し、変更した項目だけを確認のあとで送る。
 * F9 / X20 は設定キー、M8 は CFG-NAV5 / CFG-RATE（衛星系・信号・入力プロトコルは出さない）。
 * VALGET に返ってこないキー（X20 の GLONASS など）は「—（非対応）」にして操作できなくする。
 */
@Composable
fun ReceiverSettingsScreen(repo: MonitorRepository, padding: PaddingValues) {
    val sv by repo.settings.collectAsState()
    val enabled = sv.connected
    val keyed = ReceiverConfig.usesKeys(sv.gen)

    // 画面の入力（Python 版 chosen）。受信機の値が変わったときだけ置き換える（update_from）
    var chosen by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var texts by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(sv.values, sv.gen) {
        chosen = sv.values
        texts = ReceiverConfig.NAV_ITEMS.filter { it.choices.isEmpty() }
            .mapNotNull { item -> sv.values[item.ident]?.let { item.ident to it.toString() } }.toMap()
    }
    var confirm by remember { mutableStateOf<Map<String, Int>?>(null) }

    fun numberValue(item: ConfigItem): Int? = texts[item.ident]?.trim()?.toIntOrNull()?.takeIf { it in item.minimum..item.maximum }
    val invalid = ReceiverConfig.NAV_ITEMS.any { it.choices.isEmpty() && it.ident in sv.values && numberValue(it) == null }

    fun send() {
        val input = HashMap(chosen)
        ReceiverConfig.NAV_ITEMS.filter { it.choices.isEmpty() }.forEach { item -> numberValue(item)?.let { input[item.ident] = it } }
        val changes = ReceiverConfig.changed(sv.values, input)
        when {
            changes.isEmpty() -> repo.setSettingsStatus("変更はありません。")
            repo.settingsPackets(changes, sv.layers).isEmpty() -> repo.setSettingsStatus("送信できる変更がありません。")
            else -> confirm = changes
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
        val wide = maxWidth >= 600.dp
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Section(stringResource(R.string.settings_title)) {
                Text(sv.status, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { repo.readSettings() }, enabled = enabled) { Text(stringResource(R.string.settings_read)) }
                    Button(onClick = { send() }, enabled = enabled && !invalid && sv.values.isNotEmpty()) {
                        Text(stringResource(R.string.settings_send))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Dropdown(
                        stringResource(R.string.settings_gen), ReceiverConfig.GENERATIONS, sv.genChoice,
                        { repo.setSettingsGen(it) }, Modifier.weight(1f),
                    )
                    Dropdown(
                        stringResource(R.string.settings_layers), ReceiverConfig.LAYERS, sv.layers,
                        { repo.setSettingsLayers(it) }, Modifier.weight(1.4f),
                    )
                }
                Text(
                    stringResource(R.string.settings_gen_info, sv.gen, sv.detected ?: "—") + "\n" +
                        stringResource(R.string.settings_gen_help),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) { NavSection(sv.values, sv.readDone, enabled, chosen, texts, { chosen = it }, { texts = it }) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        TrackingSections(keyed, sv.values, sv.readDone, enabled, chosen) { chosen = it }
                    }
                }
            } else {
                NavSection(sv.values, sv.readDone, enabled, chosen, texts, { chosen = it }, { texts = it })
                TrackingSections(keyed, sv.values, sv.readDone, enabled, chosen) { chosen = it }
            }
        }
    }

    confirm?.let { changes ->
        val saved = sv.layers and Ubx.LAYER_RAM.inv() != 0 && keyed
        val text = buildString {
            append("次の設定を送信します。")
            append(if (saved) "BBR と Flash にも保存します。" else "RAM だけを変更し、保存はしません。")
            append("\n\n")
            append(ReceiverConfig.changeLines(changes).joinToString("\n"))
            if (ReceiverConfig.restartsTracking(changes)) append("\n\n衛星の追尾がいったん止まり、再捕捉が始まります。")
        }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.settings_confirm_title)) },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    repo.sendSettings(changes, sv.layers)
                }) { Text(stringResource(R.string.settings_confirm_send)) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** 読めていない項目の現在値の表示。 */
@Composable
private fun currentText(item: ConfigItem, values: Map<String, Int>, readDone: Boolean): String {
    val value = values[item.ident]
    if (value == null && readDone) return stringResource(R.string.settings_unsupported)
    return ReceiverConfig.describe(item, value)
}

@Composable
private fun NavSection(
    values: Map<String, Int>,
    readDone: Boolean,
    enabled: Boolean,
    chosen: Map<String, Int>,
    texts: Map<String, String>,
    onChosen: (Map<String, Int>) -> Unit,
    onTexts: (Map<String, String>) -> Unit,
) {
    Section(stringResource(R.string.settings_nav)) {
        for (item in ReceiverConfig.NAV_ITEMS) {
            val known = item.ident in values
            Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.settings_current, currentText(item, values, readDone)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (item.choices.isNotEmpty()) {
                    // 選択肢に無い値が読めたら「値 N」として足す（Python 版と同じ）
                    val current = chosen[item.ident] ?: values[item.ident]
                    val options = item.choices.map { it.second to it.first }.let { opts ->
                        if (current != null && opts.none { it.second == current }) opts + (ReceiverConfig.describe(item, current) to current)
                        else opts
                    }
                    Dropdown(
                        item.label, options, current, { v -> if (v != null) onChosen(chosen + (item.ident to v)) },
                        Modifier.fillMaxWidth(), enabled = enabled && known,
                    )
                } else {
                    val text = texts[item.ident].orEmpty()
                    val v = text.trim().toIntOrNull()
                    val bad = known && (v == null || v !in item.minimum..item.maximum)
                    OutlinedTextField(
                        value = text,
                        onValueChange = { onTexts(texts + (item.ident to it)) },
                        enabled = enabled && known,
                        singleLine = true,
                        isError = bad,
                        label = { Text("${item.label}（${item.minimum}〜${item.maximum}）") },
                        suffix = { Text(item.unit) },
                        supportingText = if (bad) {
                            { Text(stringResource(R.string.settings_range, item.minimum, item.maximum)) }
                        } else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackingSections(
    keyed: Boolean,
    values: Map<String, Int>,
    readDone: Boolean,
    enabled: Boolean,
    chosen: Map<String, Int>,
    onChosen: (Map<String, Int>) -> Unit,
) {
    if (!keyed) {
        Section("") { Text(stringResource(R.string.settings_m8_note), style = MaterialTheme.typography.bodyMedium) }
        return
    }
    Section(stringResource(R.string.settings_systems)) {
        Checks(ReceiverConfig.SYSTEM_ITEMS, values, readDone, enabled, chosen, onChosen, hideUnknown = false)
        Text(stringResource(R.string.settings_tracking_note), style = MaterialTheme.typography.bodySmall)
    }
    Section(stringResource(R.string.settings_signals)) {
        if (ReceiverConfig.SIGNAL_ITEMS.none { it.ident in values }) {
            Text(stringResource(R.string.settings_no_signals), style = MaterialTheme.typography.bodySmall)
        }
        Checks(ReceiverConfig.SIGNAL_ITEMS, values, readDone, enabled, chosen, onChosen, hideUnknown = true)
    }
    Section(stringResource(R.string.settings_inputs)) {
        Checks(ReceiverConfig.INPUT_ITEMS, values, readDone, enabled, chosen, onChosen, hideUnknown = false)
    }
}

/** チェックボックスを左上から詰めて並べる。hideUnknown は読めなかった項目を出さない（信号）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Checks(
    items: List<ConfigItem>,
    values: Map<String, Int>,
    readDone: Boolean,
    enabled: Boolean,
    chosen: Map<String, Int>,
    onChosen: (Map<String, Int>) -> Unit,
    hideUnknown: Boolean,
) {
    val unsupported = stringResource(R.string.settings_unsupported).removePrefix("—")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (item in items) {
            val known = item.ident in values
            if (!known && hideUnknown) continue
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = (chosen[item.ident] ?: values[item.ident] ?: 0) != 0,
                    onCheckedChange = { onChosen(chosen + (item.ident to if (it) 1 else 0)) },
                    enabled = enabled && known,
                )
                Text(
                    item.label + if (!known && readDone) unsupported else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (known) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
