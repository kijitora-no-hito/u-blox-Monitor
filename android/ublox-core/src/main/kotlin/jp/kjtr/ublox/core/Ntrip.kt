package jp.kjtr.ublox.core

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * RTCM 補正情報の受信（NTRIP キャスター / TCP サーバ / シリアル）。
 * 移植元: ublox_monitor/rtcm.py の ntrip_request・parse_sourcetable・ChunkedDecoder・_read_header・
 * fetch_sourcetable・CorrectionSource。通信路は CorrectionLink に分けてあり、テストはバイト列で行う。
 *
 * Python 版から足したもの: 切れたときの自動再接続（2→4→8…最大 60 秒）、401 / 404 の分かりやすい文言、
 * ソーステーブルの座標からの距離。
 */

/** ソーステーブルの STR 行。lat / lon は文字列のまま（Python 版と同じ）。 */
data class Mount(
    val mount: String, val id: String, val format: String, val details: String, val nav: String,
    val country: String, val lat: String, val lon: String, val nmea: Boolean,
) {
    val latitude: Double? get() = lat.trim().toDoubleOrNull()?.takeIf { it in -90.0..90.0 }

    /** 0〜360 で書いてある表もあるので -180〜180 にそろえる */
    val longitude: Double?
        get() = lon.trim().toDoubleOrNull()?.let { if (it > 180) it - 360 else it }?.takeIf { it in -180.0..180.0 }
}

/** キャスターに断られた・設定の誤り。permanent なら再接続しない（設定を直す必要がある）。 */
class CorrectionRejected(message: String, val permanent: Boolean) : IOException(message)

object Ntrip {
    const val DEFAULT_PORT = 2101
    const val CONNECT_TIMEOUT = 10.0
    const val READ_TIMEOUT_MS = 1000
    const val NO_DATA_TIMEOUT = 30.0
    const val RATE_WINDOW = 10.0
    const val MAX_HEADER = 16384

    fun userAgent(version: String) = "NTRIP ublox_monitor_android/$version"

    /** NTRIP の要求を作る。version は 1 か 2。 */
    fun request(
        host: String, port: Int, mountpoint: String, user: String, password: String, version: Int,
        userAgent: String = userAgent("0"),
    ): ByteArray {
        val lines = arrayListOf("GET /$mountpoint HTTP/${if (version == 2) "1.1" else "1.0"}", "User-Agent: $userAgent")
        if (version == 2) lines += listOf("Host: $host:$port", "Ntrip-Version: Ntrip/2.0", "Connection: close")
        if (user.isNotEmpty()) lines.add("Authorization: Basic ${base64("$user:$password".toByteArray(Charsets.UTF_8))}")
        return (lines.joinToString("\r\n") + "\r\n\r\n").toByteArray(Charsets.ISO_8859_1)
    }

    private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** Base64（java.util.Base64 は API 26 からなので自前） */
    fun base64(data: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xFF
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xFF else 0
            sb.append(B64[b0 shr 2]).append(B64[((b0 and 3) shl 4) or (b1 shr 4)])
            sb.append(if (i + 1 < data.size) B64[((b1 and 15) shl 2) or (b2 shr 6)] else '=')
            sb.append(if (i + 2 < data.size) B64[b2 and 63] else '=')
            i += 3
        }
        return sb.toString()
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray, from: Int = 0): Int {
        outer@ for (i in from..data.size - pattern.size) {
            for (j in pattern.indices) if (data[i + j] != pattern[j]) continue@outer
            return i
        }
        return -1
    }

    private val CRLF = byteArrayOf(13, 10)
    private val CRLF2 = byteArrayOf(13, 10, 13, 10)
    private val ICY = "ICY".toByteArray()

    private fun startsWith(data: ByteArray, prefix: ByteArray) =
        data.size >= prefix.size && prefix.indices.all { data[it] == prefix[it] }

    /**
     * 応答のヘッダーと、ヘッダーの後に続いて届いた本体に分ける。ヘッダーがまだ揃っていなければ null。
     * NTRIP 1.0 の「ICY 200 OK」は 1 行だけで、すぐ後に RTCM が続く。それ以外（HTTP、SOURCETABLE）は空行までがヘッダー。
     * force は揃っていなくても（時間切れ・切断）その時点の内容で分ける。
     */
    fun splitHeader(data: ByteArray, force: Boolean = false): Pair<String, ByteArray>? {
        val icy = startsWith(data, ICY)
        val complete = (icy && indexOf(data, CRLF) >= 0) || indexOf(data, CRLF2) >= 0
        if (!complete && !force) return null
        if (icy) {
            val end = indexOf(data, CRLF)
            if (end < 0) return String(data, Charsets.ISO_8859_1) to ByteArray(0)
            var rest = data.copyOfRange(end + 2, data.size)
            if (startsWith(rest, CRLF)) rest = rest.copyOfRange(2, rest.size)
            return String(data, 0, end, Charsets.ISO_8859_1) to rest
        }
        val end = indexOf(data, CRLF2)
        if (end < 0) return String(data, Charsets.ISO_8859_1) to ByteArray(0)
        return String(data, 0, end, Charsets.ISO_8859_1) to data.copyOfRange(end + 4, data.size)
    }

    /** ヘッダーの 1 行目 */
    fun firstLine(header: String): String = header.lineSequence().firstOrNull()?.trim().orEmpty()

    /** 補正データの要求への応答を確かめる。受け付けられなければ CorrectionRejected。 */
    fun checkStreamResponse(header: String) {
        val first = firstLine(header).ifEmpty { "応答なし" }
        when {
            first.startsWith("SOURCETABLE") ->
                throw CorrectionRejected("マウントポイントが見つかりません（ソーステーブルが返りました）", true)
            first.startsWith("ICY 200") || " 200" in first -> return
            " 401" in first -> throw CorrectionRejected("認証に失敗しました（ユーザー名・パスワードを確認してください）: $first", true)
            " 403" in first -> throw CorrectionRejected("接続が拒否されました（契約・利用条件を確認してください）: $first", true)
            " 404" in first -> throw CorrectionRejected("マウントポイントが見つかりません: $first", true)
            else -> throw CorrectionRejected("キャスターの応答: $first", false)
        }
    }

    /** ソーステーブルの要求への応答として受け付けられるか。 */
    fun isSourcetableResponse(header: String): Boolean =
        header.startsWith("SOURCETABLE 200") || " 200" in firstLine(header)

    fun isChunked(header: String) = "chunked" in header.lowercase()

    /** ソーステーブルの STR 行を Mount にする。 */
    fun parseSourcetable(text: String): List<Mount> = text.lines().mapNotNull { line ->
        val f = line.trimEnd('\r').split(";")
        if (f[0] != "STR" || f.size < 12) return@mapNotNull null
        Mount(f[1], f[2], f[3], f[4], f[6], f[8], f[9], f[10], f[11] == "1")
    }

    /** 2 点間の距離 [km]（球面で近似。一覧の目安） */
    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * 6371.0 * asin(min(1.0, sqrt(a)))
    }

    /** ヘッダーを読む（Python 版 _read_header）。 */
    fun readHeader(link: CorrectionLink, clock: () -> Double): Pair<String, ByteArray> {
        var data = ByteArray(0)
        val deadline = clock() + CONNECT_TIMEOUT
        val buf = ByteArray(4096)
        while (clock() < deadline && data.size < MAX_HEADER) {
            splitHeader(data)?.let { return it }
            val n = link.read(buf)
            if (n < 0) break
            if (n > 0) data += buf.copyOf(n)
        }
        return splitHeader(data, force = true)!!
    }

    /** キャスターのソーステーブルを取得する（Python 版 fetch_sourcetable）。 */
    fun fetchSourcetable(
        link: CorrectionLink, host: String, port: Int, user: String, password: String, version: Int,
        userAgent: String = userAgent("0"), clock: () -> Double = { System.nanoTime() / 1e9 },
    ): List<Mount> = link.use {
        link.write(request(host, port, "", user, password, version, userAgent))
        val (header, body) = readHeader(link, clock)
        if (!isSourcetableResponse(header)) {
            val first = firstLine(header).ifEmpty { "応答なし" }
            if (" 401" in first) throw IOException("認証に失敗しました（ユーザー名・パスワードを確認してください）: $first")
            throw IOException("キャスターの応答: $first")
        }
        val decoder = if (isChunked(header)) ChunkedDecoder() else null
        val out = java.io.ByteArrayOutputStream()
        out.write(decoder?.feed(body) ?: body)
        val end = "ENDSOURCETABLE".toByteArray()
        val deadline = clock() + CONNECT_TIMEOUT
        val buf = ByteArray(65536)
        while (indexOf(out.toByteArray(), end) < 0 && clock() < deadline) {
            val n = link.read(buf)
            if (n < 0) break
            if (n == 0) continue
            val chunk = buf.copyOf(n)
            out.write(decoder?.feed(chunk) ?: chunk)
        }
        parseSourcetable(String(out.toByteArray(), Charsets.ISO_8859_1))
    }
}

/** HTTP の chunked 転送を解く。 */
class ChunkedDecoder {
    private var buffer = ByteArray(0)
    private var remaining: Int? = null

    /** data を追加し、取り出せた本体のバイト列を返す。 */
    fun feed(data: ByteArray): ByteArray {
        buffer += data
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            var rem = remaining
            if (rem == null) {
                var end = -1
                for (i in 0 until buffer.size - 1) if (buffer[i] == 13.toByte() && buffer[i + 1] == 10.toByte()) {
                    end = i
                    break
                }
                if (end < 0) break
                val sizeText = String(buffer, 0, end, Charsets.ISO_8859_1).split(";")[0].trim().ifEmpty { "0" }
                rem = sizeText.toIntOrNull(16) ?: throw IOException("chunked の長さが読めません: $sizeText")
                remaining = rem
                buffer = buffer.copyOfRange(end + 2, buffer.size)
            }
            if (buffer.size < rem + 2) {
                val take = minOf(rem, buffer.size)
                out.write(buffer, 0, take)
                buffer = buffer.copyOfRange(take, buffer.size)
                remaining = rem - take
                break
            }
            out.write(buffer, 0, rem)
            buffer = buffer.copyOfRange(rem + 2, buffer.size)
            remaining = null
        }
        return out.toByteArray()
    }
}

/** 補正情報の通信路（ソケット・USB シリアル）。 */
interface CorrectionLink : Closeable {
    /** 読めたバイト数。データが来ないまま時間切れなら 0、相手が切ったら -1。 */
    fun read(buf: ByteArray): Int

    fun write(data: ByteArray)

    /** 別のスレッドから読み取りを打ち切る（ソケットは閉じる）。 */
    fun abort() {}
}

/**
 * TCP（必要なら TLS）のソケット。TLS はホスト名も確かめる（verifyHost があればそれで、無ければ JSSE の HTTPS の規則）。
 */
class SocketLink(
    host: String, port: Int, tls: Boolean,
    verifyHost: ((String, SSLSession) -> Boolean)? = null,
) : CorrectionLink {
    private val socket: Socket
    private val input: InputStream
    private val output: OutputStream

    init {
        val plain = Socket()
        try {
            plain.connect(InetSocketAddress(host, port), (Ntrip.CONNECT_TIMEOUT * 1000).toInt())
            socket = if (tls) {
                val s = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain, host, port, true) as SSLSocket
                if (verifyHost == null) s.sslParameters = s.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                s.soTimeout = (Ntrip.CONNECT_TIMEOUT * 1000).toInt()
                s.startHandshake()
                if (verifyHost != null && !verifyHost(host, s.session)) {
                    s.close()
                    throw SSLPeerUnverifiedException("証明書のホスト名が $host と一致しません")
                }
                s
            } else plain
            socket.soTimeout = Ntrip.READ_TIMEOUT_MS
            input = socket.getInputStream()
            output = socket.getOutputStream()
        } catch (e: Exception) {
            try {
                plain.close()
            } catch (_: IOException) {
            }
            throw e
        }
    }

    override fun read(buf: ByteArray): Int = try {
        input.read(buf)
    } catch (_: SocketTimeoutException) {
        0
    }

    override fun write(data: ByteArray) {
        output.write(data)
        output.flush()
    }

    override fun abort() = close()

    override fun close() {
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }
}

enum class CorrectionKind { NTRIP, TCP, SERIAL }

/** 受信元の設定。serialDevice は data 層が USB 機器を探すための名前。 */
data class CorrectionConfig(
    val kind: CorrectionKind,
    val host: String = "",
    val port: Int = Ntrip.DEFAULT_PORT,
    val tls: Boolean = false,
    val version: Int = 2,
    val mount: String = "",
    val user: String = "",
    val password: String = "",
    /** GGA を送る間隔 [秒]。0 は送らない */
    val ggaInterval: Int = 0,
    val serialDevice: String = "",
    val serialLabel: String = "",
    val serialBaud: Int = 115200,
) {
    /** ログに出す受信先（Python 版 target） */
    val target: String
        get() = when (kind) {
            CorrectionKind.NTRIP -> mount.ifEmpty { host }
            CorrectionKind.TCP -> "$host:$port"
            CorrectionKind.SERIAL -> serialLabel.ifEmpty { serialDevice }
        }

    // パスワードをログ・例外に出さない
    override fun toString() = "CorrectionConfig($kind, $host:$port, mount=$mount, user=$user, tls=$tls, v$version)"
}

/** 表示用の集計（Python 版 snapshot）。rate は B/秒、messages は 番号 →（受信数, 最終受信からの秒）。 */
data class CorrectionSnapshot(
    val status: String,
    val error: String?,
    val bytes: Long,
    val rate: Double,
    val crcErrors: Int,
    val age: Double?,
    val messages: List<Triple<Int, Int, Double>>,
    val station: Station?,
    val running: Boolean,
    /** 再接続の回数 */
    val reconnects: Int,
    /** 再接続まで の秒数（待っているときだけ） */
    val retryIn: Double?,
)

/**
 * 補正情報を受信する（Python 版 CorrectionSource）。受信したバイト列は onData に渡す（受信機へ転送する）。
 * gga は送る GGA 文（または null）を返す関数で、NTRIP のときに ggaInterval 秒ごとに送る。
 *
 * Python 版との違い: 切れたら（つながらない・相手が切った・30 秒データが来ない）指数バックオフで再接続する
 * （2→4→8…最大 60 秒、stop() まで）。認証失敗・マウントポイント無しなど設定の誤りは再接続しない。
 * 10 秒以上受信できていた接続が切れたときは 2 秒から数え直す。
 */
class CorrectionClient(
    val config: CorrectionConfig,
    private val open: (CorrectionConfig) -> CorrectionLink,
    private val onData: (ByteArray) -> Unit,
    private val gga: (() -> String?)? = null,
    private val userAgent: String = Ntrip.userAgent("0"),
    private val onEvent: (String) -> Unit = {},
    private val reconnect: Boolean = true,
    private val clock: () -> Double = { System.nanoTime() / 1e9 },
    sleeper: ((Double) -> Unit)? = null,
) {
    @Volatile
    var status = "接続中"
        private set

    @Volatile
    var error: String? = null
        private set

    @Volatile
    private var stopped = false

    @Volatile
    private var current: CorrectionLink? = null

    @Volatile
    private var retryAt: Double? = null

    @Volatile
    var reconnects = 0
        private set

    /** 再接続の待ち時間の記録（テスト用） */
    val delays: MutableList<Double> = java.util.Collections.synchronizedList(ArrayList())

    private var bytes = 0L
    private var lastData: Double? = null
    private val messages = LinkedHashMap<Int, DoubleArray>()   // 番号 → [受信数, 最終受信時刻]
    private var station: Station? = null
    private val stream = RtcmStream()
    private val recent = ArrayDeque<Pair<Double, Int>>()
    private val lock = Any()
    private val waiter = Object()
    private val sleep: (Double) -> Unit = sleeper ?: ::waitSeconds
    private var thread: Thread? = null

    val isAlive: Boolean get() = thread?.isAlive == true

    fun start() {
        thread = Thread(::run, "correction").apply {
            isDaemon = true
            start()
        }
    }

    /** 止める（待たない）。終了を待つなら join。 */
    fun stop() {
        stopped = true
        current?.abort()
        synchronized(waiter) { waiter.notifyAll() }
    }

    fun join(ms: Long) {
        thread?.join(ms)
    }

    private fun waitSeconds(seconds: Double) {
        val end = System.currentTimeMillis() + (seconds * 1000).toLong()
        synchronized(waiter) {
            while (!stopped) {
                val left = end - System.currentTimeMillis()
                if (left <= 0) break
                waiter.wait(left)
            }
        }
    }

    /** 受信したバイト列を集計して転送する。 */
    private fun received(data: ByteArray) {
        if (data.isEmpty()) return
        val now = clock()
        synchronized(lock) {
            bytes += data.size
            lastData = now
            recent.addLast(now to data.size)
            for (payload in stream.feed(data)) {
                val type = Rtcm.messageType(payload) ?: continue
                val entry = messages.getOrPut(type) { doubleArrayOf(0.0, now) }
                entry[0] += 1
                entry[1] = now
                Rtcm.parseStation(payload)?.let { station = it }
            }
        }
        onData(data)
    }

    fun snapshot(): CorrectionSnapshot {
        val now = clock()
        synchronized(lock) {
            while (recent.isNotEmpty() && recent.first().first < now - Ntrip.RATE_WINDOW) recent.removeFirst()
            val rate = recent.sumOf { it.second } / Ntrip.RATE_WINDOW
            return CorrectionSnapshot(
                status = status, error = error, bytes = bytes, rate = rate, crcErrors = stream.crcErrors,
                age = lastData?.let { now - it },
                messages = messages.entries.sortedBy { it.key }.map { Triple(it.key, it.value[0].toInt(), now - it.value[1]) },
                station = station, running = isAlive && !stopped, reconnects = reconnects,
                retryIn = retryAt?.let { maxOf(0.0, it - now) },
            )
        }
    }

    /** 再接続までの待ち時間 [秒]（n 回目の失敗） */
    fun backoff(failures: Int): Double = min(MAX_BACKOFF, 2.0 * (1L shl (failures - 1).coerceIn(0, 10)))

    /** スレッドの本体（テストでは呼び出し側のスレッドで呼ぶ）。 */
    fun run() {
        var failures = 0
        while (!stopped) {
            var receivingSince: Double? = null
            try {
                status = "接続中"
                val link = open(config)
                current = link
                if (stopped) {
                    link.abort()
                    link.close()
                    break
                }
                link.use { session(it) { receivingSince = clock() } }
            } catch (e: CorrectionRejected) {
                if (!stopped) error = e.message
                if (e.permanent) break
            } catch (e: Exception) {
                if (!stopped) error = e.message ?: e.javaClass.simpleName
            } finally {
                current = null
            }
            if (stopped || !reconnect) break
            val since = receivingSince
            if (since != null && clock() - since >= STABLE_SECONDS) failures = 0
            failures++
            val delay = backoff(failures)
            delays.add(delay)
            onEvent("補正情報の受信が止まりました: $error（%.0f 秒後に再接続）".format(delay))
            retryAt = clock() + delay
            status = "再接続待ち"
            sleep(delay)
            retryAt = null
            if (!stopped) reconnects++
        }
        status = "停止"
    }

    /** 1 回の接続。受信を始めたら onReceiving を呼ぶ。 */
    private fun session(link: CorrectionLink, onReceiving: () -> Unit) {
        val cfg = config
        val ntrip = cfg.kind == CorrectionKind.NTRIP
        var decoder: ChunkedDecoder? = null
        if (ntrip) {
            link.write(Ntrip.request(cfg.host, cfg.port, cfg.mount, cfg.user, cfg.password, cfg.version, userAgent))
            val (header, rest) = Ntrip.readHeader(link, clock)
            Ntrip.checkStreamResponse(header)
            var body = rest
            if (Ntrip.isChunked(header)) {
                decoder = ChunkedDecoder()
                body = decoder.feed(rest)
            }
            received(body)
        }
        status = if (cfg.kind == CorrectionKind.SERIAL) "受信中（${cfg.target}）" else "受信中"
        error = null
        if (reconnects > 0) onEvent("補正情報の受信を再開しました（再接続 $reconnects 回目）")
        val connectedAt = clock()
        onReceiving()
        var nextGga = 0.0
        val buf = ByteArray(65536)
        while (!stopped) {
            val now = clock()
            if (ntrip && gga != null && cfg.ggaInterval > 0 && now >= nextGga) {
                val sentence = gga.invoke()
                if (sentence != null) {
                    link.write(sentence.toByteArray(Charsets.US_ASCII))
                    nextGga = now + cfg.ggaInterval
                }
            }
            val n = link.read(buf)
            if (n == 0) {
                // シリアル（無線機など）は黙っていることがあるので見ない（Python 版と同じ）
                val last = maxOf(synchronized(lock) { lastData } ?: connectedAt, connectedAt)
                if (cfg.kind != CorrectionKind.SERIAL && clock() - last > Ntrip.NO_DATA_TIMEOUT) {
                    throw IOException("%.0f 秒間データが届きません".format(Ntrip.NO_DATA_TIMEOUT))
                }
                continue
            }
            if (n < 0) {
                if (stopped) break
                throw IOException("相手が接続を切りました")
            }
            val data = buf.copyOf(n)
            received(decoder?.feed(data) ?: data)
        }
    }

    companion object {
        const val MAX_BACKOFF = 60.0
        const val STABLE_SECONDS = 10.0
    }
}
