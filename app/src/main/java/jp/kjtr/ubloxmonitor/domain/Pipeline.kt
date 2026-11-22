package jp.kjtr.ubloxmonitor.domain

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * 受信データの振り分け、UBX ログの記録と再生、出力メッセージの設定パケット。
 * 移植元: ublox_monitor/serial_link.py（COM ポートの部分は data/UsbSerialSource.kt）。
 */

object Link {
    val BAUD_RATES = listOf(9600, 19200, 38400, 57600, 115200, 230400, 460800, 921600)
    const val DEFAULT_BAUD = 921600
    const val UBLOX_USB_VID = 0x1546
    const val READ_CHUNK = 4096
    const val MAX_EPOCH_GAP = 5.0
    const val SENDS_PER_LOOP = 4

    /** 接続時に出力させるメッセージ */
    val OUTPUT_MESSAGES = listOf(Ubx.NAV_PVT, Ubx.NAV_SAT, Ubx.RXM_RAWX, Ubx.RXM_SFRBX, Ubx.RXM_MEASX)

    /** CFG-MSGOUT のキー（I2C 用）。ポートごとのキーは PORT_OFFSETS を足す */
    val MSGOUT_KEYS: Map<MsgId, Long> = mapOf(
        Ubx.NAV_PVT to 0x20910006L, Ubx.NAV_SAT to 0x20910015L, Ubx.RXM_RAWX to 0x209102A4L,
        Ubx.RXM_SFRBX to 0x20910231L, Ubx.RXM_MEASX to 0x20910204L,
    )
    val PORT_OFFSETS = mapOf("UART1" to 1, "UART2" to 2, "USB" to 3)

    /** 受信機側のポート名の推定。u-blox 内蔵 USB なら USB、USB シリアル変換なら UART1。 */
    fun guessReceiverPort(vendorId: Int): String = if (vendorId == UBLOX_USB_VID) "USB" else "UART1"

    /**
     * 表示に使うメッセージの出力を有効（rate=0 で無効）にするパケットを返す。RAM のみ変更する。
     * 未対応のメッセージが 1 つあっても他が通るよう、メッセージごとに分けて送る。
     */
    fun outputPackets(gen: String, receiverPort: String, rate: Int = 1): List<ByteArray> {
        val packets = ArrayList<ByteArray>()
        if (gen in listOf("F9", "X20", "Unknown")) {
            val offset = PORT_OFFSETS.getValue(receiverPort)
            OUTPUT_MESSAGES.forEach { msg ->
                packets.add(Ubx.buildValset(mapOf(MSGOUT_KEYS.getValue(msg) + offset to rate.toLong())))
            }
        }
        if (gen in listOf("M8", "Unknown")) {
            OUTPUT_MESSAGES.forEach { packets.add(Ubx.buildCfgMsg(it, rate)) }
        }
        return packets
    }

    /** 記録ファイル名（ublox_年月日_時分秒.ubx）。 */
    fun logFileName(date: Date = Date(), prefix: String = "ublox"): String =
        "${prefix}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(date)}.ubx"
}

/** 受信したバイト列をそのまま .ubx に保存する。既存のファイルは上書きしない。 */
class UbxLogger(folder: File, prefix: String = "ublox") {
    val file: File
    private val out: FileOutputStream
    var size = 0L
        private set

    init {
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("フォルダを作れません: $folder")
        file = File(folder, Link.logFileName(prefix = prefix))
        if (!file.createNewFile()) throw IOException("同じ名前のファイルがあります: ${file.name}")
        out = FileOutputStream(file)
    }

    /** data を書き込む。 */
    fun write(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        out.write(data, offset, length)
        size += length
    }

    /** ファイルを閉じる。 */
    fun close() = out.close()
}

/** 記録中の状態。 */
data class LogStatus(val file: File, val size: Long)

/** ACK / NAK の記録。 */
data class Ack(val ok: Boolean, val target: MsgId)

/**
 * 受信データを分離・デコードして集計と記録へ流す。
 * onLog はログ欄に出す 1 行（時刻付き）を受け取る。onPvt は NAV-PVT を受けたとき（受信スレッドから）呼ばれる。
 */
class Pipeline(
    clock: () -> Double = { System.currentTimeMillis() / 1000.0 },
    private val onLog: (String) -> Unit = {},
) {
    val stream = UbxStream()
    val state = GnssState(clock)

    @Volatile
    var version: MonVer? = null
    /** 設定キー → 値のバイト列（CFG-VALGET） */
    val cfgValues: MutableMap<Long, ByteArray> = java.util.concurrent.ConcurrentHashMap()
    /** CFG-NAV5 のペイロード（M8） */
    @Volatile
    var nav5: ByteArray? = null
    /** CFG-RATE（M8） */
    @Volatile
    var rate: Rate? = null
    val acks: MutableList<Ack> = java.util.Collections.synchronizedList(ArrayList())
    /** 最新の MON-SPAN */
    @Volatile
    var spectrum: List<SpanBlock>? = null
    @Volatile
    var spectrumCount = 0
    /** エポックが進んだときに呼ぶ関数（引数は進んだ秒数） */
    var onEpoch: ((Double) -> Unit)? = null
    /** NAV-PVT を受けたときに呼ぶ関数 */
    var onPvt: ((Pvt) -> Unit)? = null
    private var epochMsg: MsgId? = null
    private var epochTow: Double? = null
    private var logger: UbxLogger? = null
    private val logLock = Any()

    /** ログ欄に出す 1 行を積む。 */
    fun log(text: String) {
        onLog("${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}  $text")
    }

    /** 記録を始める。 */
    fun startLogging(folder: File, prefix: String = "ublox") = synchronized(logLock) {
        if (logger == null) {
            logger = UbxLogger(folder, prefix)
            log("記録開始: ${logger!!.file.path}")
        }
    }

    /** 記録を止める。 */
    fun stopLogging() = synchronized(logLock) {
        logger?.let {
            try {
                it.close()
            } catch (_: IOException) {
            }
            log("記録停止: ${it.file.path}")
        }
        logger = null
    }

    /** 記録中ならその状態、そうでなければ null。 */
    fun logStatus(): LogStatus? = synchronized(logLock) { logger?.let { LogStatus(it.file, it.size) } }

    /** 受信したバイト列を処理する。 */
    fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        synchronized(logLock) {
            val l = logger
            if (l != null) {
                try {
                    l.write(data, offset, length)
                } catch (e: IOException) {
                    log("記録に失敗しました: ${e.message}")
                    try {
                        l.close()
                    } catch (_: IOException) {
                    }
                    logger = null
                }
            }
        }
        for (frame in stream.feed(data, offset, length)) {
            state.count(frame.msg)
            handle(frame.msg, frame.payload)
        }
    }

    /** エポックの進みを onEpoch に知らせる。最初に届いた種類のメッセージを基準にする。 */
    private fun epoch(msg: MsgId, tow: Double) {
        if (epochMsg == null) epochMsg = msg
        if (msg != epochMsg) return
        val previous = epochTow
        epochTow = tow
        val cb = onEpoch
        if (previous != null && cb != null) {
            val step = tow - previous
            cb(if (step > 0 && step <= Link.MAX_EPOCH_GAP) step else 0.0)
        }
    }

    /** 1 メッセージを処理する。 */
    private fun handle(msg: MsgId, payload: ByteArray) {
        when (msg) {
            Ubx.NAV_PVT -> Ubx.parseNavPvt(payload)?.let { pvt ->
                epoch(msg, pvt.itow / 1000.0)
                state.updatePvt(pvt)
                onPvt?.invoke(pvt)
            }
            Ubx.NAV_SAT -> state.updateSat(Ubx.parseNavSat(payload))
            Ubx.RXM_RAWX -> {
                val (tow, meas) = Ubx.parseRxmRawx(payload)
                if (tow != null) epoch(msg, tow)
                state.updateRawx(meas)
            }
            Ubx.RXM_MEASX -> state.updateMeasx(Ubx.parseRxmMeasx(payload))
            Ubx.RXM_SFRBX -> Ubx.parseRxmSfrbx(payload)?.let { state.updateSfrbx(it) }
            Ubx.MON_VER -> Ubx.parseMonVer(payload)?.let { v ->
                version = v
                log("受信機: ${v.gen} ${v.module} HW ${v.hw} SW ${v.sw} PROTVER ${v.protver}")
            }
            Ubx.MON_SPAN -> {
                val blocks = Ubx.parseMonSpan(payload)
                if (blocks.isNotEmpty()) {
                    spectrum = blocks
                    spectrumCount++
                }
            }
            Ubx.CFG_VALGET -> cfgValues.putAll(Ubx.parseValget(payload))
            Ubx.CFG_NAV5 -> if (payload.size >= 36) nav5 = payload.copyOf()
            Ubx.CFG_RATE -> rate = Ubx.parseRate(payload) ?: rate
            Ubx.ACK_ACK, Ubx.ACK_NAK -> if (payload.size >= 2) {
                val target = MsgId(payload[0].toInt() and 0xFF, payload[1].toInt() and 0xFF)
                acks.add(Ack(msg == Ubx.ACK_ACK, target))
                // 未対応キーの読み出しは NAK になるのが普通なので、ログには出さない
                if (target != Ubx.CFG_VALGET) {
                    log("${if (msg == Ubx.ACK_ACK) "ACK" else "NAK（拒否）"}: $target")
                }
            }
        }
    }
}

/** 再生用の時計。データの時刻の進みに合わせて進める。 */
class ReplayClock : () -> Double {
    @Volatile
    var value = 0.0
    override fun invoke(): Double = value
}

/**
 * UBX ログの再生（Python 版 ReplaySource.run / _on_epoch）。speed は再生速度の倍率で、0 は待たずに読む。
 * スレッドは呼び出し側が用意し、run() をそのスレッドで呼ぶ。stop() で待ちを切り上げて終わる。
 */
class ReplayRunner(
    private val pipeline: Pipeline,
    private val input: InputStream,
    private val speed: Double,
    private val clock: ReplayClock,
) {
    @Volatile
    var position = 0L
        private set
    @Volatile
    private var stopped = false
    private val waiter = Object()

    init {
        pipeline.onEpoch = ::onEpoch
    }

    /** エポックが進んだ分だけ時計を進め、再生速度に合わせて待つ。 */
    private fun onEpoch(step: Double) {
        clock.value += step
        if (speed > 0 && step > 0) {
            val ms = (step / speed * 1000).toLong()
            synchronized(waiter) {
                if (!stopped && ms > 0) waiter.wait(ms)
            }
        }
    }

    fun stop() {
        stopped = true
        synchronized(waiter) { waiter.notifyAll() }
    }

    /** 最後まで（または stop() まで）読む。読み出しの例外はそのまま投げる。 */
    fun run() {
        input.use { stream ->
            // エポックごとに待てるよう、小さく区切って読む
            val buf = ByteArray(if (speed == 0.0) Link.READ_CHUNK else 256)
            while (!stopped) {
                val n = stream.read(buf)
                if (n < 0) break
                if (n == 0) continue
                position += n
                pipeline.feed(buf, 0, n)
            }
        }
    }
}
