package jp.kjtr.ubloxmonitor.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import jp.kjtr.ubloxmonitor.R
import jp.kjtr.ubloxmonitor.data.Prefs
import jp.kjtr.ubloxmonitor.update.Releases
import jp.kjtr.ubloxmonitor.update.UpdateLinks
import jp.kjtr.ubloxmonitor.update.UpdateManager
import jp.kjtr.ubloxmonitor.update.UpdateStatus
import jp.kjtr.ubloxmonitor.update.UpdateUi
import java.text.DateFormat
import java.util.Date

/** ブラウザで開く（ACTION_VIEW。APK のダウンロード・インストールはブラウザと端末に任せる。REQUEST_INSTALL_PACKAGES は要求しない）。 */
fun openInBrowser(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Toast.makeText(context, R.string.update_browser_failed, Toast.LENGTH_LONG).show()
    }
}

/**
 * メイン画面の上部の控えめな帯「新しい版 v… があります」［更新内容］（広げる）［ダウンロード］［あとで］［この版は通知しない］（0.5.0）。
 * Android の通知は使わない。表示したら起動時の確認を 1 回だけ始める（OFF・12 時間以内なら通信しない）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UpdateBanner(updates: UpdateManager) {
    LaunchedEffect(Unit) { updates.checkOnLaunch() }
    val ui by updates.state.collectAsState()
    val r = ui.banner ?: return
    val context = LocalContext.current
    var open by rememberSaveable { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                stringResource(R.string.update_banner, r.version),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            if (open) {
                val notes = remember(r) { Releases.plainNotes(r.notes) }
                Text(
                    notes.ifEmpty { stringResource(R.string.update_notes_empty) },
                    Modifier.padding(vertical = 4.dp).heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    stringResource(R.string.update_install),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { open = !open }) {
                    Text(stringResource(if (open) R.string.update_notes_close else R.string.update_notes))
                }
                TextButton(onClick = { openInBrowser(context, r.downloadUrl) }) { Text(stringResource(R.string.update_download)) }
                TextButton(onClick = { updates.dismiss() }) { Text(stringResource(R.string.update_later)) }
                TextButton(onClick = { updates.skip() }) { Text(stringResource(R.string.update_skip)) }
            }
        }
    }
}

/** 「このアプリについて」の「更新の確認」の節: ON/OFF・［今すぐ確認］・状態の 1 行・リリースのページ。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UpdateSettingsSection(updates: UpdateManager, prefs: Prefs) {
    val ui by updates.state.collectAsState()
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.update_check), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.update_check_desc), style = MaterialTheme.typography.bodySmall)
        }
        Switch(ui.enabled, { v -> prefs.updateCheck = v; updates.publish() })
    }
    Text(updateStatusLine(ui), style = MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { updates.checkNow() }, enabled = ui.enabled && ui.status != UpdateStatus.CHECKING) {
            Text(stringResource(R.string.update_now))
        }
        OutlinedButton(onClick = { openInBrowser(context, UpdateLinks.PAGE_URL) }) { Text(stringResource(R.string.update_page)) }
    }
}

private fun shortDateTime(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))

@Composable
private fun updateStatusLine(ui: UpdateUi): String = when {
    !ui.enabled -> stringResource(R.string.update_status_off)
    ui.status == UpdateStatus.CHECKING -> stringResource(R.string.update_status_checking)
    ui.newer != null -> stringResource(R.string.update_status_available, ui.newer.version)
    ui.status == UpdateStatus.FAILED ->
        if (ui.lastSuccessMs > 0) stringResource(R.string.update_status_failed_at, shortDateTime(ui.lastSuccessMs)) else stringResource(R.string.update_status_failed)
    ui.status == UpdateStatus.LATEST -> stringResource(R.string.update_status_latest, shortDateTime(ui.lastSuccessMs))
    ui.lastSuccessMs > 0 -> stringResource(R.string.update_status_known, shortDateTime(ui.lastSuccessMs))
    else -> stringResource(R.string.update_status_never)
}
