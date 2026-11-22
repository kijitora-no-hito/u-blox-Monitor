package jp.kjtr.ubloxmonitor.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import jp.kjtr.ubloxmonitor.data.source.Channel
import jp.kjtr.ubloxmonitor.data.source.InternalGnssSource
import jp.kjtr.ubloxmonitor.data.source.ReplaySource
import jp.kjtr.ubloxmonitor.data.source.Source
import jp.kjtr.ubloxmonitor.data.source.UsbSerialSource
import jp.kjtr.ubloxmonitor.domain.CommonCn0
import jp.kjtr.ubloxmonitor.domain.Compare
import jp.kjtr.ubloxmonitor.domain.DiffHistory
import jp.kjtr.ubloxmonitor.domain.Link
import jp.kjtr.ubloxmonitor.domain.PositionDiff
import jp.kjtr.ubloxmonitor.domain.Pvt
import jp.kjtr.ubloxmonitor.domain.STALE_SECONDS
import jp.kjtr.ubloxmonitor.domain.SideSummary
import jp.kjtr.ubloxmonitor.domain.TimedDiff
import jp.kjtr.ubloxmonitor.domain.LogStatus
import jp.kjtr.ubloxmonitor.domain.MonVer
import jp.kjtr.ubloxmonitor.domain.Pipeline
import jp.kjtr.ubloxmonitor.domain.ReplayClock
import jp.kjtr.ubloxmonitor.domain.Snapshot
import jp.kjtr.ubloxmonitor.domain.Ubx
import jp.kjtr.ubloxmonitor.service.MonitorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** 画面に出す 1 データ源の状態。 */
class ChannelView(
    val channel: Channel,
    val title: String,
    val snapshot: Snapshot,
    val checksumErrors: Int,
    val version: MonVer?,
    val record: LogStatus?,
    val running: Boolean,
    val canSend: Boolean,
    val progress: Float?,
    /** 接続中の受信機の情報・終了の理由など（Python 版の info_label） */
    val info: String,
)

/** 比較画面の状態。base は比較の基準（u-blox か再生）。 */
class CompareView(
    val base: Channel,
    val baseRunning: Boolean,
    val internalRunning: Boolean,
    val baseSummary: SideSummary?,
    val internalSummary: SideSummary?,
    /** 両方とも新しい位置があるときだけ */
    val diff: PositionDiff?,
    val history: List<TimedDiff>,
    val cn0: CommonCn0?,
)

/** 擬似ロケーションの状態。 */
enum class MockStatus { OFF, WAITING, ACTIVE, NOT_ALLOWED }

data class MockState(val status: MockStatus, val published: Long = 0)

/**
 * 受信・デコード・集計・記録・擬似ロケーションを持つ（Application スコープ）。
 * 画面は views を 0.5 秒ごとに受け取って描く。接続を保つのは MonitorService。
 * 集計（Pipeline）はデータ源の区分（Channel）ごとに 1 つ持ち、u-blox・内蔵 GNSS・再生を同時に動かせる。
 */
class MonitorRepository(private val app: Context) {
    val prefs = Prefs(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val usbManager = app.getSystemService(Context.USB_SERVICE) as UsbManager
    private val mock = MockLocation(app)

    private class Runtime(val channel: Channel, val pipeline: Pipeline, val source: Source) {
        @Volatile
        var ended = false
        var endMessage: String? = null
        var pendingSetup = false
        var pendingTicks = 0
        /** 受信機側のポート（u-blox のみ） */
        var receiverPort: String = "USB"
        var deviceName: String? = null
    }

    private val runtimes = ConcurrentHashMap<Channel, Runtime>()

    private val _views = MutableStateFlow<Map<Channel, ChannelView>>(emptyMap())
    val views: StateFlow<Map<Channel, ChannelView>> = _views.asStateFlow()

    /** メイン画面に出すデータ源 */
    val selected = MutableStateFlow(Channel.UBLOX)

    private val logLines = ArrayDeque<String>()
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _candidates = MutableStateFlow<List<UsbCandidate>>(emptyList())
    val candidates: StateFlow<List<UsbCandidate>> = _candidates.asStateFlow()

    /** USB を差して起動されたときに選んでおく機器 */
    val preferredDevice = MutableStateFlow<String?>(null)

    /** 接続欄の 1 行（未接続・切断しました・エラーなど） */
    private val _usbMessage = MutableStateFlow(app.getString(jp.kjtr.ubloxmonitor.R.string.status_not_connected))
    val usbMessage: StateFlow<String> = _usbMessage.asStateFlow()

    private val _mockState = MutableStateFlow(MockState(if (prefs.mockLocation) MockStatus.WAITING else MockStatus.OFF))
    val mockState: StateFlow<MockState> = _mockState.asStateFlow()

    private val _logFiles = MutableStateFlow<List<File>>(emptyList())
    val logFiles: StateFlow<List<File>> = _logFiles.asStateFlow()

    /** 記録の保存先（アプリ固有の外部ストレージ。権限は要らない） */
    val logFolder: File get() = app.getExternalFilesDir("logs") ?: File(app.filesDir, "logs")

    /** 比較の基準（u-blox か再生） */
    private val _compareBase = MutableStateFlow(
        runCatching { Channel.valueOf(prefs.compareBase) }.getOrNull()?.takeIf { it != Channel.INTERNAL } ?: Channel.UBLOX
    )
    val compareBase: StateFlow<Channel> = _compareBase.asStateFlow()

    private val _compare = MutableStateFlow<CompareView?>(null)
    val compare: StateFlow<CompareView?> = _compare.asStateFlow()

    /** 基準から見た内蔵 GNSS の位置の差の履歴（画面を開いていなくても 1 秒ごとに数える） */
    private val diffHistory = DiffHistory()
    private var lastPair: Pair<Pvt?, Pvt?> = null to null

    private var tick = 0

    init {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED && device != null) onDetached(device)
                refreshDevices()
            }
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        // システムのブロードキャストなので EXPORTED（他のアプリからは送れない保護されたもの）
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        refreshDevices()
        refreshLogFiles()
        scope.launch {
            while (true) {
                delay(REFRESH_MS)
                tick++
                try {
                    checkSources()
                    updatePending()
                    if (tick % MAIN_TICKS == 0) updateDiffHistory()
                    if (tick % MAIN_TICKS == 0 && _views.subscriptionCount.value > 0) publishViews()
                } catch (e: Exception) {
                    addLog("内部エラー: ${e.message}")
                }
            }
        }
    }

    // ---- ログ ----

    private fun addLog(line: String) = synchronized(logLines) {
        logLines.addLast(line)
        while (logLines.size > MAX_LOG_LINES) logLines.removeFirst()
        _logs.value = logLines.toList()
    }

    private fun label(channel: Channel) = app.getString(channel.labelRes)

    private fun newPipeline(channel: Channel, clock: () -> Double = { System.currentTimeMillis() / 1000.0 }): Pipeline {
        val tag = label(channel)
        // 「時刻  本文」の本文の前にデータ源の名前を入れる
        return Pipeline(clock) { line -> addLog(line.replaceFirst("  ", "  [$tag] ")) }
    }

    // ---- データ源 ----

    /** 動いている u-blox / 内蔵 GNSS があるか（サービスを保つ条件） */
    fun hasLiveSource(): Boolean = runtimes.values.any { it.channel.live && !it.ended && it.source.isAlive }

    fun isRunning(channel: Channel): Boolean = runtimes[channel]?.let { !it.ended && it.source.isAlive } == true

    /** そのデータ源の最新の NAV-PVT（通知用） */
    fun latestPvt(channel: Channel) = runtimes[channel]?.pipeline?.state?.pvt

    fun recordStatus(channel: Channel) = runtimes[channel]?.pipeline?.logStatus()

    /**
     * データ源を差し替えて開始する（区分ごとに 1 つ）。内蔵 GNSS もここから入れる。
     * 開始したら集計を作り直す（Python 版 _start と同じく、前のデータは消える）。
     */
    fun startSource(channel: Channel, pipeline: Pipeline, source: Source): Unit = synchronized(runtimes) {
        stopChannel(channel, null)
        val runtime = Runtime(channel, pipeline, source)
        runtimes[channel] = runtime
        source.start()
        selected.value = channel
        if (channel.live) MonitorService.start(app)
        publishViews()
    }

    private fun stopChannel(channel: Channel, message: String?) {
        val runtime = runtimes[channel] ?: return
        runtime.source.stop()
        runtime.pipeline.stopLogging()
        runtime.pendingSetup = false
        if (!runtime.ended) {
            runtime.ended = true
            runtime.endMessage = message
            if (message != null) runtime.pipeline.log(message)
        }
        if (channel == Channel.UBLOX) stopMock()
    }

    fun refreshDevices() {
        _candidates.value = try {
            UsbDevices.list(app)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** USB を差して起動されたとき。 */
    fun onDeviceAttached(device: UsbDevice?) {
        refreshDevices()
        if (device != null) preferredDevice.value = device.deviceName
    }

    private fun onDetached(device: UsbDevice) {
        val runtime = runtimes[Channel.UBLOX] ?: return
        val source = runtime.source as? UsbSerialSource ?: return
        if (source.device.deviceName == device.deviceName) source.detached()
    }

    /** u-blox 受信機に接続する。receiverPortChoice は null で自動。 */
    fun connect(candidate: UsbCandidate, baud: Int, receiverPortChoice: String?, autoSetup: Boolean) {
        prefs.baud = baud
        prefs.receiverPort = receiverPortChoice
        prefs.autoSetup = autoSetup
        UsbDevices.requestPermission(app, candidate.device) { granted ->
            if (!granted) {
                _usbMessage.value = app.getString(jp.kjtr.ubloxmonitor.R.string.usb_permission_denied)
                return@requestPermission
            }
            val port = candidate.driver.ports.firstOrNull()
            if (port == null) {
                _usbMessage.value = app.getString(jp.kjtr.ubloxmonitor.R.string.open_failed, "ポートがありません")
                return@requestPermission
            }
            val pipeline = newPipeline(Channel.UBLOX)
            val title = candidate.label
            pipeline.onPvt = { pvt -> if (mock.isActive) mock.publish(pvt) }
            val source = UsbSerialSource(usbManager, candidate.device, port, baud, pipeline, title)
            startSource(Channel.UBLOX, pipeline, source)
            runtimes[Channel.UBLOX]?.let {
                it.receiverPort = receiverPortChoice ?: candidate.receiverPortGuess
                it.deviceName = candidate.device.deviceName
                // 世代によって設定の方法が違うので、MON-VER の応答を待ってから設定を送る
                source.send(listOf(Ubx.build(Ubx.MON_VER)))
                it.pendingSetup = autoSetup
                it.pendingTicks = 0
            }
            _usbMessage.value = ""
            if (prefs.mockLocation) startMock()
        }
    }

    fun disconnect() {
        if (isRunning(Channel.UBLOX)) {
            stopChannel(Channel.UBLOX, app.getString(jp.kjtr.ubloxmonitor.R.string.status_disconnected))
            _usbMessage.value = app.getString(jp.kjtr.ubloxmonitor.R.string.status_disconnected)
        }
        publishViews()
    }

    /** 通知の「切断」。動いているデータ源をすべて止める。 */
    fun disconnectAll() {
        disconnect()
        if (isRunning(Channel.INTERNAL)) stopChannel(Channel.INTERNAL, app.getString(jp.kjtr.ubloxmonitor.R.string.internal_stopped))
        if (isRunning(Channel.REPLAY)) stopChannel(Channel.REPLAY, app.getString(jp.kjtr.ubloxmonitor.R.string.replay_stopped))
        publishViews()
    }

    // ---- 内蔵 GNSS ----

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * 内蔵 GNSS を開始する（位置情報の権限が許可されてから呼ぶ）。u-blox とは独立に動く。
     * サービスは startSource の中で起動し、権限があるので location 種別になる。
     */
    fun startInternal() {
        if (!hasLocationPermission()) {
            addLog(app.getString(jp.kjtr.ubloxmonitor.R.string.internal_no_permission))
            return
        }
        val pipeline = newPipeline(Channel.INTERNAL)
        val title = "${app.getString(jp.kjtr.ubloxmonitor.R.string.source_internal)}（${Build.MANUFACTURER} ${Build.MODEL}）"
        startSource(Channel.INTERNAL, pipeline, InternalGnssSource(app, pipeline, title))
    }

    fun stopInternal() {
        if (isRunning(Channel.INTERNAL)) stopChannel(Channel.INTERNAL, app.getString(jp.kjtr.ubloxmonitor.R.string.internal_stopped))
        publishViews()
    }

    // ---- 比較 ----

    /** 比較の基準を選ぶ（u-blox か再生）。差の履歴は消す。 */
    fun setCompareBase(channel: Channel) {
        if (channel == Channel.INTERNAL || channel == _compareBase.value) return
        prefs.compareBase = channel.name
        _compareBase.value = channel
        diffHistory.clear()
        lastPair = null to null
        publishViews()
    }

    /** 基準と内蔵の両方に新しい位置があれば、差を履歴に足す（どちらかが更新されたときだけ）。 */
    private fun updateDiffHistory() {
        val base = runtimes[_compareBase.value]?.pipeline?.state ?: return
        val internal = runtimes[Channel.INTERNAL]?.pipeline?.state ?: return
        val bp = base.pvt ?: return
        val ip = internal.pvt ?: return
        if (base.clock() - base.pvtTime > STALE_SECONDS * 2 || internal.clock() - internal.pvtTime > STALE_SECONDS * 2) return
        if (lastPair.first === bp && lastPair.second === ip) return
        lastPair = bp to ip
        val diff = Compare.diff(bp, ip) ?: return
        diffHistory.add(TimedDiff(System.currentTimeMillis() / 1000.0, diff))
    }

    private fun buildCompare(map: Map<Channel, ChannelView>): CompareView {
        val base = _compareBase.value
        val b = map[base]
        val i = map[Channel.INTERNAL]
        val bs = b?.snapshot?.let { Compare.summary(it) }
        val isum = i?.snapshot?.let { Compare.summary(it) }
        val bp = b?.snapshot?.pvt
        val ip = i?.snapshot?.pvt
        val diff = if (bs?.fresh == true && isum?.fresh == true && bp != null && ip != null) Compare.diff(bp, ip) else null
        val cn0 = if (b != null && i != null) Compare.commonCn0(b.snapshot, i.snapshot) else null
        return CompareView(base, b?.running == true, i?.running == true, bs, isum, diff, diffHistory.toList(), cn0)
    }

    private fun generation(runtime: Runtime): String = runtime.pipeline.version?.gen ?: "Unknown"

    /** 表示に使うメッセージの出力を有効にする（Python 版 apply_setup）。 */
    fun applySetup() {
        val runtime = runtimes[Channel.UBLOX] ?: return
        if (runtime.ended || !runtime.source.isAlive) return
        val gen = generation(runtime)
        runtime.pipeline.log("出力メッセージを設定します（$gen、受信機側 ${runtime.receiverPort}）")
        runtime.source.send(Link.outputPackets(gen, runtime.receiverPort))
    }

    /** 接続後、世代が分かったら（または 2 秒待っても分からなければ）設定を送る。 */
    private fun updatePending() {
        val runtime = runtimes[Channel.UBLOX] ?: return
        if (!runtime.pendingSetup || runtime.ended || !runtime.source.isAlive) return
        runtime.pendingTicks++
        if (runtime.pipeline.version == null && runtime.pendingTicks < 2000 / REFRESH_MS) return
        runtime.pendingSetup = false
        applySetup()
        // 受信機設定の読み出し（Python 版 read_settings）は受信機設定タブと一緒に 2 段目で入れる
    }

    /** データ源の終了や切断を検出する。 */
    private fun checkSources() {
        var changed = false
        for (runtime in runtimes.values) {
            if (runtime.ended || runtime.source.isAlive) continue
            val error = runtime.source.error
            val message = when {
                runtime.channel == Channel.INTERNAL ->
                    app.getString(jp.kjtr.ubloxmonitor.R.string.internal_ended, error ?: "")
                error != null -> "通信が切れました: $error"
                runtime.channel.live -> app.getString(jp.kjtr.ubloxmonitor.R.string.status_disconnected)
                else -> app.getString(jp.kjtr.ubloxmonitor.R.string.replay_finished_name, runtime.source.title)
            }
            runtime.ended = true
            runtime.endMessage = message
            runtime.pipeline.log(message)
            runtime.pipeline.stopLogging()
            if (runtime.channel == Channel.UBLOX) {
                _usbMessage.value = message
                stopMock()
                refreshDevices()
            }
            if (!runtime.channel.live) refreshLogFiles()
            changed = true
        }
        if (changed) publishViews()
    }

    // ---- 再生 ----

    fun startReplay(file: File, speed: Double) {
        prefs.replaySpeed = speed
        try {
            startReplay(FileInputStream(file), file.length(), file.name, speed)
        } catch (e: IOException) {
            addLog("再生できません: ${e.message}")
        }
    }

    fun startReplay(uri: Uri, speed: Double) {
        prefs.replaySpeed = speed
        try {
            var size = 0L
            var name = uri.lastPathSegment ?: "UBX"
            app.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                }
            }
            val input = app.contentResolver.openInputStream(uri) ?: throw IOException("開けません")
            startReplay(input, size, name, speed)
        } catch (e: Exception) {
            addLog("再生できません: ${e.message}")
        }
    }

    private fun startReplay(input: java.io.InputStream, size: Long, name: String, speed: Double) {
        val clock = ReplayClock()
        val pipeline = newPipeline(Channel.REPLAY, clock)
        val source = ReplaySource(pipeline, input, speed, clock, size, name)
        startSource(Channel.REPLAY, pipeline, source)
        // 再生でもログ欄に開始を出す（終了は checkSources で「再生が終わりました」）
        val speedText = if (speed > 0) "%.0f 倍速".format(speed) else "最大"
        pipeline.log(app.getString(jp.kjtr.ubloxmonitor.R.string.replay_started, name, speedText, "%.1f".format(size / 1e6)))
    }

    fun stopReplay() {
        if (isRunning(Channel.REPLAY)) stopChannel(Channel.REPLAY, app.getString(jp.kjtr.ubloxmonitor.R.string.replay_stopped))
        publishViews()
    }

    // ---- 記録 ----

    /** 記録を開始/停止する。動いている実機・内蔵 GNSS だけ。 */
    fun toggleRecord(channel: Channel) {
        val runtime = runtimes[channel] ?: return
        if (runtime.pipeline.logStatus() != null) {
            runtime.pipeline.stopLogging()
        } else if (channel.live && !runtime.ended && runtime.source.isAlive) {
            try {
                runtime.pipeline.startLogging(logFolder, channel.logPrefix)
            } catch (e: IOException) {
                runtime.pipeline.log("記録を開始できません: ${e.message}")
            }
        }
        refreshLogFiles()
        publishViews()
    }

    fun refreshLogFiles() {
        val files = logFolder.listFiles { f -> f.isFile && f.name.endsWith(".ubx", ignoreCase = true) }
        _logFiles.value = files?.sortedByDescending { it.name } ?: emptyList()
    }

    /** 記録中のファイルは消さない。 */
    fun deleteLog(file: File): Boolean {
        if (runtimes.values.any { it.pipeline.logStatus()?.file == file }) return false
        val ok = file.delete()
        refreshLogFiles()
        return ok
    }

    fun isRecordingFile(file: File) = runtimes.values.any { it.pipeline.logStatus()?.file == file }

    // ---- 擬似ロケーション ----

    /** 擬似ロケーションの ON/OFF（流すのは u-blox 実機の位置だけ）。 */
    fun setMock(enabled: Boolean) {
        prefs.mockLocation = enabled
        if (!enabled) {
            mock.disable()
            _mockState.value = MockState(MockStatus.OFF)
            return
        }
        if (isRunning(Channel.UBLOX)) {
            startMock()
        } else {
            // 許可の確認だけしておく（つないでいないのに GPS を差し替えたままにしない）
            try {
                mock.enable()
                mock.disable()
                _mockState.value = MockState(MockStatus.WAITING)
            } catch (_: SecurityException) {
                notAllowed()
            }
        }
    }

    private fun startMock() {
        try {
            mock.enable()
            _mockState.value = MockState(MockStatus.ACTIVE)
        } catch (_: SecurityException) {
            notAllowed()
        }
    }

    private fun notAllowed() {
        mock.disable()
        prefs.mockLocation = false
        _mockState.value = MockState(MockStatus.NOT_ALLOWED)
    }

    private fun stopMock() {
        mock.disable()
        if (_mockState.value.status == MockStatus.ACTIVE) _mockState.value = MockState(MockStatus.WAITING)
    }

    /** サービスの終了時など。 */
    fun releaseMock() = stopMock()

    // ---- 表示 ----

    private fun info(runtime: Runtime): String {
        val running = !runtime.ended && runtime.source.isAlive
        if (!running) return runtime.endMessage ?: ""
        if (!runtime.channel.live) return app.getString(jp.kjtr.ubloxmonitor.R.string.replay_running, runtime.source.title)
        runtime.source.statusText?.let { return "${runtime.source.title}\n$it" }
        val v = runtime.pipeline.version
            ?: return app.getString(jp.kjtr.ubloxmonitor.R.string.connecting_wait, runtime.source.title)
        return "${v.gen} ${v.module}  HW ${v.hw}  SW ${v.sw}  PROTVER ${v.protver}"
    }

    /** 画面用のスナップショットを作り直す。 */
    fun publishViews() {
        val map = LinkedHashMap<Channel, ChannelView>()
        for (channel in Channel.entries) {
            val runtime = runtimes[channel] ?: continue
            val running = !runtime.ended && runtime.source.isAlive
            map[channel] = ChannelView(
                channel = channel,
                title = runtime.source.title,
                snapshot = runtime.pipeline.state.snapshot(),
                checksumErrors = runtime.pipeline.stream.checksumErrors,
                version = runtime.pipeline.version,
                record = runtime.pipeline.logStatus(),
                running = running,
                canSend = running && runtime.source.canSend,
                progress = (runtime.source as? ReplaySource)?.progress,
                info = info(runtime),
            )
        }
        _views.value = map
        _compare.value = buildCompare(map)
        val ms = _mockState.value
        if (ms.status == MockStatus.ACTIVE) _mockState.value = ms.copy(published = mock.published)
    }

    companion object {
        const val REFRESH_MS = 250L
        /** 画面は 0.5 秒ごとに更新する */
        const val MAIN_TICKS = 2
        const val MAX_LOG_LINES = 2000
    }
}
