package jp.kjtr.ublox.android

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Process
import com.hoho.android.usbserial.driver.FtdiSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import jp.kjtr.ublox.core.Link
import jp.kjtr.ublox.core.Pipeline
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * USB シリアルから受信し、送信待ちのパケットを送るスレッド（Python 版 SerialSource）。
 * u-blox 内蔵 USB（CDC-ACM）では baud は無視されるが、変換チップ経由ではそのまま効く。
 */
class UsbSerialSource(
    private val manager: UsbManager,
    val device: UsbDevice,
    private val port: UsbSerialPort,
    val baud: Int,
    private val pipeline: Pipeline,
    override val title: String,
) : Source {
    private val outbox = LinkedBlockingQueue<ByteArray>()
    private val inbox = LinkedBlockingQueue<ByteArray>()
    private var thread: Thread? = null

    @Volatile
    private var stopping = false

    @Volatile
    override var error: String? = null
        private set

    override val canSend = true

    override val isAlive: Boolean get() = thread?.isAlive == true

    override fun send(packets: List<ByteArray>) {
        packets.forEach { outbox.put(it) }
    }

    override fun start() {
        thread = Thread(::run, "usb-serial").apply {
            isDaemon = true
            start()
        }
    }

    /** USB が外れたとき。理由を残して止める。 */
    fun detached() {
        if (error == null) error = "USB が外れました"
        stopping = true
    }

    override fun stop() {
        stopping = true
        thread?.join(2000)
    }

    private fun run() {
        try {
            val connection = manager.openDevice(device) ?: throw IOException("USB を開けません（権限がありません）")
            port.open(connection)
            try {
                port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            } catch (_: UnsupportedOperationException) {
            }
            // CDC-ACM では DTR を立てないと送ってこない機器がある
            try {
                port.setDTR(true)
                port.setRTS(true)
            } catch (_: Exception) {
            }
            // FTDI は既定の latency timer 16 ms。短くしてチップ内のバッファ（FT230X は 512 バイト）を早く吐かせる
            (port as? FtdiSerialDriver.FtdiSerialPort)?.let {
                try {
                    it.setLatencyTimer(1)
                } catch (_: Exception) {
                }
            }
            pipeline.log("接続: $title $baud bps")
            // 921600 bps では FT230X の 512 バイトのバッファが約 5 ms であふれる。読み取りの合間に解析・送信を挟むと
            // 塊ごと取りこぼした（実機 Pixel 10 Pro + FT230X で NAV-PVT・RAWX がほぼ全滅）。
            // このスレッドは USB を読んで列に積むだけにし、解析と送信は別スレッドで行う。
            val worker = Thread(::work, "usb-serial-work").apply {
                isDaemon = true
                start()
            }
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (_: Exception) {
            }
            val buf = ByteArray(READ_BUFFER)
            while (!stopping) {
                val n = port.read(buf, READ_TIMEOUT_MS)
                if (n > 0) inbox.put(buf.copyOf(n))
            }
            worker.join(1000)
        } catch (e: Exception) {
            if (!stopping || error != null) error = error ?: (e.message ?: e.javaClass.simpleName)
        } finally {
            stopping = true
            try {
                port.close()
            } catch (_: Exception) {
            }
        }
    }

    /** 受信したデータの解析と、送信待ちのパケットの送信（読み取りスレッドの外）。 */
    private fun work() {
        try {
            while (!stopping) {
                val chunk = inbox.poll(WORK_POLL_MS, TimeUnit.MILLISECONDS)
                if (chunk != null) pipeline.feed(chunk, 0, chunk.size)
                for (i in 0 until Link.SENDS_PER_LOOP) {
                    val packet = outbox.poll() ?: break
                    port.write(packet, WRITE_TIMEOUT_MS)
                }
            }
            // 止める直前に読んだ分も流す
            while (true) {
                val chunk = inbox.poll() ?: break
                pipeline.feed(chunk, 0, chunk.size)
            }
        } catch (e: Exception) {
            if (!stopping) {
                error = error ?: (e.message ?: e.javaClass.simpleName)
                stopping = true
            }
        }
    }

    private companion object {
        const val READ_TIMEOUT_MS = 50
        const val READ_BUFFER = 16384
        const val WRITE_TIMEOUT_MS = 1000
        const val WORK_POLL_MS = 20L
    }
}
