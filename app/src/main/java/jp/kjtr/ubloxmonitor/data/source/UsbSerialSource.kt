package jp.kjtr.ubloxmonitor.data.source

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import jp.kjtr.ubloxmonitor.domain.Link
import jp.kjtr.ubloxmonitor.domain.Pipeline
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue

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
            pipeline.log("接続: $title $baud bps")
            // 921600 bps・1 Hz で 30 種類ほど（約 23 KB/秒、1 フレーム最大 5.5 KB）出す受信機でも
            // 取りこぼさないよう、大きめのバッファで読み続ける（このスレッドは読み取り専用）
            val buf = ByteArray(READ_BUFFER)
            while (!stopping) {
                val n = port.read(buf, READ_TIMEOUT_MS)
                if (n > 0) pipeline.feed(buf, 0, n)
                for (i in 0 until Link.SENDS_PER_LOOP) {
                    val packet = outbox.poll() ?: break
                    port.write(packet, WRITE_TIMEOUT_MS)
                }
            }
        } catch (e: Exception) {
            if (!stopping || error != null) error = error ?: (e.message ?: e.javaClass.simpleName)
        } finally {
            try {
                port.close()
            } catch (_: Exception) {
            }
        }
    }

    private companion object {
        const val READ_TIMEOUT_MS = 50
        const val READ_BUFFER = 16384
        const val WRITE_TIMEOUT_MS = 1000
    }
}
