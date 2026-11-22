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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import jp.kjtr.ubloxmonitor.R
import jp.kjtr.ubloxmonitor.data.MonitorRepository
import jp.kjtr.ubloxmonitor.data.ReplayView
import jp.kjtr.ublox.core.LogPairing
import jp.kjtr.ublox.core.TimeRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    // 「内蔵と比較再生」を押した u-blox の記録（相手を選ぶダイアログ）
    var pairing by remember { mutableStateOf<File?>(null) }
    val replay = views[Channel.REPLAY]
    val rv by repo.replay.collectAsState()

    LaunchedEffect(Unit) { repo.refreshLogFiles() }
    LaunchedEffect(views[Channel.UBLOX]?.record?.size, views[Channel.INTERNAL]?.record?.size) { repo.refreshLogFiles() }

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
                    // 再生中なら今の再生の速度も変える（2 本とも）
                    FilterChip(speed == v, { speed = v; repo.setReplaySpeed(v) }, label = { Text(stringResource(res)) })
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
                val r = rv
                if (r != null) ReplayControls(repo, r)
                if (replay.running && r?.duration == null) replay.progress?.let { LinearProgressIndicator(progress = { it }, Modifier.fillMaxWidth()) }
            }
            Text(stringResource(R.string.replay_pair_help), style = MaterialTheme.typography.bodySmall)
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
                        if (file.name.startsWith("ublox")) {
                            TextButton(onClick = { pairing = file }, enabled = !recording) { Text(stringResource(R.string.replay_pair)) }
                        }
                        TextButton(onClick = { share(context, file) }, enabled = !recording) { Text(stringResource(R.string.share)) }
                        TextButton(onClick = { deleting = file }, enabled = !recording) { Text(stringResource(R.string.delete)) }
                    }
                }
            }
        }
    }

    pairing?.let { base ->
        PairDialog(
            repo, base, files.filter { it.name.startsWith("internal") && !repo.isRecordingFile(it) },
            onDismiss = { pairing = null },
        ) { other ->
            pairing = null
            repo.startPairReplay(base, other, speed)
            onPlay()
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

/** 秒を「分:秒」（1 時間以上は「時:分:秒」）に。 */
private fun clockText(sec: Double): String {
    val t = sec.coerceAtLeast(0.0).toLong()
    return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%d:%02d".format(t / 60, t % 60)
}

/** 再生の操作（0.5.0）: 一時停止・再開、経過時間と全体、シーク（スライダー・−1 分 / +1 分 / +10 分）。2 本同時の再生でも両方に効く。 */
@Composable
private fun ReplayControls(repo: MonitorRepository, r: ReplayView) {
    if (r.paired) {
        Text(
            stringResource(if (r.aligned) R.string.replay_pair_synced else R.string.replay_pair_unsynced, r.names[0], r.names[1]),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    val total = r.duration
    val timeText = if (total != null) stringResource(R.string.replay_time_total, clockText(r.elapsed), clockText(total))
    else stringResource(R.string.replay_time, clockText(r.elapsed))
    Text(
        timeText +
            (if (r.paused) "  " + stringResource(R.string.replay_paused) else "") +
            (if (r.seeking) "  " + stringResource(R.string.replay_seeking) else ""),
        style = MaterialTheme.typography.bodySmall,
    )
    if (total != null && total > 0) {
        // ドラッグ中は指の位置、離したらそこへシーク
        var dragging by remember { mutableStateOf(false) }
        var drag by remember { mutableFloatStateOf(0f) }
        val value = if (dragging) drag else (r.elapsed / total).toFloat().coerceIn(0f, 1f)
        Slider(
            value = value,
            onValueChange = { dragging = true; drag = it },
            onValueChangeFinished = { dragging = false; repo.seekReplay(drag * total) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { repo.toggleReplayPause() }, enabled = r.running) {
            Text(stringResource(if (r.paused) R.string.replay_resume else R.string.replay_pause))
        }
        OutlinedButton(onClick = { repo.seekReplay(r.elapsed - 60) }) { Text(stringResource(R.string.replay_back_1m)) }
        OutlinedButton(onClick = { repo.seekReplay(r.elapsed + 60) }) { Text(stringResource(R.string.replay_fwd_1m)) }
        OutlinedButton(onClick = { repo.seekReplay(r.elapsed + 600) }) { Text(stringResource(R.string.replay_fwd_10m)) }
    }
}

/**
 * u-blox の記録 [base] と合わせて再生する内蔵 GNSS の記録を選ぶ（0.5.0）。GPS 時刻の範囲がいちばん長く重なるものを最初から選んでおく
 * （範囲はファイルの先頭と末尾だけ読む）。重ならないものも選べる（そのときは時刻のとおりに、重ならない部分は片方だけ流れる）。
 */
@Composable
private fun PairDialog(repo: MonitorRepository, base: File, candidates: List<File>, onDismiss: () -> Unit, onPlay: (File) -> Unit) {
    var ranges by remember { mutableStateOf<Map<File, TimeRange?>?>(null) }
    var baseRange by remember { mutableStateOf<TimeRange?>(null) }
    var chosen by remember { mutableStateOf<File?>(null) }
    LaunchedEffect(base, candidates) {
        withContext(Dispatchers.IO) {
            val b = repo.timeRange(base)
            val m = candidates.associateWith { repo.timeRange(it) }
            baseRange = b
            ranges = m
            chosen = b?.let { LogPairing.best(it, m.toList()) }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.replay_pair_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.replay_pair_base, base.name), style = MaterialTheme.typography.bodySmall)
                val m = ranges
                when {
                    m == null -> Text(stringResource(R.string.replay_pair_reading), style = MaterialTheme.typography.bodySmall)
                    candidates.isEmpty() -> Text(stringResource(R.string.replay_pair_none), style = MaterialTheme.typography.bodySmall)
                    else -> {
                        if (baseRange == null) Text(stringResource(R.string.replay_pair_no_time), style = MaterialTheme.typography.bodySmall)
                        else if (chosen == null) Text(stringResource(R.string.replay_pair_no_overlap), style = MaterialTheme.typography.bodySmall)
                        for (f in candidates) {
                            val r = m[f]
                            val b = baseRange
                            val note = when {
                                r == null -> stringResource(R.string.replay_pair_unknown)
                                b == null -> clockText(r.duration)
                                r.overlap(b) > 0 -> stringResource(R.string.replay_pair_overlap, clockText(r.overlap(b)))
                                else -> stringResource(R.string.replay_pair_apart)
                            }
                            Row(
                                Modifier.fillMaxWidth().clickable { chosen = f },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(chosen == f, { chosen = f })
                                Column {
                                    Text(f.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(note, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { chosen?.let(onPlay) }, enabled = chosen != null) { Text(stringResource(R.string.replay_pair_play)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
