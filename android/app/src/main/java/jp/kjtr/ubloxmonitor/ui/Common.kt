package jp.kjtr.ubloxmonitor.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jp.kjtr.ubloxmonitor.data.ChannelView
import jp.kjtr.ubloxmonitor.data.source.Channel

@Composable
fun UbloxTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme(primary = Color(0xFF64B5F6)) else lightColorScheme(primary = Color(0xFF1565C0)),
        content = content,
    )
}

/** 0xAARRGGBB の Long を Color にする。 */
fun argb(v: Long) = Color(v.toInt())

/** 見出し付きの枠。 */
@Composable
fun Section(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (title.isNotEmpty()) Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

/** 選択肢のプルダウン。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> Dropdown(
    label: String,
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    val text = options.firstOrNull { it.second == selected }?.first ?: ""
    ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { if (enabled) expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = text,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            options.forEach { (name, value) ->
                DropdownMenuItem(text = { Text(name) }, onClick = {
                    expanded = false
                    onSelect(value)
                })
            }
        }
    }
}

/** データ源の切り替え（データのあるものだけ）。 */
@Composable
fun ChannelSelector(views: Map<Channel, ChannelView>, selected: Channel, onSelect: (Channel) -> Unit) {
    if (views.isEmpty()) return
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((channel, view) in views) {
            val running = if (view.running) " ●" else ""
            FilterChip(
                selected = channel == selected,
                onClick = { onSelect(channel) },
                label = { Text(stringResource(channel.labelRes) + running) },
            )
        }
    }
}

/** 読み取り専用の表。weights は列の幅の比。 */
@Composable
fun SimpleTable(headers: List<String>, rows: List<List<String>>, weights: List<Float> = headers.map { 1f }, fontSize: Int = 12) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            headers.forEachIndexed { i, h ->
                Text(
                    h, Modifier.weight(weights[i]), fontSize = fontSize.sp, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center, maxLines = 1,
                )
            }
        }
        HorizontalDivider()
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                row.forEachIndexed { i, v ->
                    Text(v, Modifier.weight(weights[i]), fontSize = fontSize.sp, textAlign = TextAlign.Center, maxLines = 1)
                }
            }
        }
    }
}
