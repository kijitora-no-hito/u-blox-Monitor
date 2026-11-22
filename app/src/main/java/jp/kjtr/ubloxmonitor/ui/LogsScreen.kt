package jp.kjtr.ubloxmonitor.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import jp.kjtr.ubloxmonitor.R
import jp.kjtr.ubloxmonitor.data.MonitorRepository
import jp.kjtr.ubloxmonitor.data.source.Channel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 再生速度（Python 版 REPLAY_SPEEDS）。0 は最大 */
private val REPLAY_SPEEDS = listOf(R.string.speed_1 to 1.0, R.string.speed_10 to 10.0, R.string.speed_60 to 60.0, R.string.speed_max to 0.0)

@Composable
fun LogsScreen(repo: MonitorRepository, padding: PaddingValues, onPlay: () -> Unit) {
    val context = LocalContext.current
    val files by repo.logFiles.collectAsState()
    val views by repo.views.collectAsState()
    var speed by rememberSaveable { mutableDoubleStateOf(repo.prefs.replaySpeed) }
    var deleting by remember { mutableStateOf<File?>(null) }
    val replay = views[Channel.REPLAY]

    LaunchedEffect(Unit) { repo.refreshLogFiles() }
    LaunchedEffect(views[Channel.UBLOX]?.record?.size) { repo.refreshLogFiles() }

    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            repo.startReplay(uri, speed)
            onPlay()
        }
    }

    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Section(stringResource(R.string.replay_title)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                REPLAY_SPEEDS.forEach { (res, v) ->
                    FilterChip(speed == v, { speed = v; repo.prefs.replaySpeed = v }, label = { Text(stringResource(res)) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    try {
                        openDocument.launch(arrayOf("*/*"))
                    } catch (_: ActivityNotFoundException) {
                    }
                }) { Text(stringResource(R.string.open_file)) }
                if (replay?.running == true) {
                    Button(onClick = { repo.stopReplay() }) { Text(stringResource(R.string.stop_replay)) }
                }
            }
            if (replay != null) {
                Text(replay.info, style = MaterialTheme.typography.bodySmall)
                if (replay.running) replay.progress?.let { LinearProgressIndicator(progress = { it }, Modifier.fillMaxWidth()) }
            }
        }

        Section(stringResource(R.string.logs_title)) {
            Text(stringResource(R.string.record_folder, repo.logFolder.path), style = MaterialTheme.typography.bodySmall)
            if (files.isEmpty()) Text(stringResource(R.string.logs_empty))
            files.forEach { file ->
                val recording = repo.isRecordingFile(file)
                HorizontalDivider()
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(file.name, fontWeight = FontWeight.Bold)
                    Text(
                        "%.1f MB  %s%s".format(
                            file.length() / 1e6,
                            SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(Date(file.lastModified())),
                            if (recording) "  " + stringResource(R.string.recording_now) else "",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { repo.startReplay(file, speed); onPlay() }, enabled = !recording) {
                            Text(stringResource(R.string.play))
                        }
                        TextButton(onClick = { share(context, file) }, enabled = !recording) { Text(stringResource(R.string.share)) }
                        TextButton(onClick = { deleting = file }, enabled = !recording) { Text(stringResource(R.string.delete)) }
                    }
                }
            }
        }
    }

    deleting?.let { file ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.delete)) },
            text = { Text(stringResource(R.string.delete_confirm, file.name)) },
            confirmButton = {
                TextButton(onClick = { repo.deleteLog(file); deleting = null }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private fun share(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/octet-stream"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(send, file.name))
    } catch (_: ActivityNotFoundException) {
    }
}
