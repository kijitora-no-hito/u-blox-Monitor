package jp.kjtr.ublox.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/*
 * 2 本の UBX ログを GPS 時刻で合わせて再生する（PC 版にない。Android 版 0.5.0、ライブラリ 0.4.0）。
 * u-blox の記録（ublox_*.ubx）と内蔵 GNSS の記録（internal_*.ubx）は同時に別のファイルへ書かれるので、
 * エポック（NAV-PVT の iTOW / RXM-RAWX の rcvTow）の GPS 時刻の順に交互に流し、比較画面などをライブと同じに動かす。
 * 1 本だけの再生も同じ仕組み（ReplayRunner と同じ待ち方。一時停止・シーク・速度の変更が加わる）。
 */

/** GPS 時刻の計算。 */
object GpsTime {
    const val WEEK = 604800.0

    /** UNIX 時刻 0 から GPS 時刻 0（1980-01-06）までの秒数 */
    const val GPS_EPOCH_UNIX = 315964800L

    /** うるう秒（UTC → GPS）。週を決めるだけなので数秒ずれても構わない */
    const val LEAP_SECONDS = 18

    /** 週内秒 [tow] を、おおよその GPS 時刻 [approx] にいちばん近い週に置いた GPS 時刻（GPS 時刻 0 からの秒）。 */
    fun resolve(tow: Double, approx: Double): Double {
        val week = Math.rint((approx - tow) / WEEK)
        return week * WEEK + tow
    }

    /** UTC の UNIX 時刻 [ms] → おおよその GPS 時刻 [s] */
    fun fromUtcMillis(utcMillis: Long): Double = utcMillis / 1000.0 - GPS_EPOCH_UNIX + LEAP_SECONDS
}

/**
 * ログの 1 区切り（エポック 1 つ分のバイト列。最後がそのエポックの NAV-PVT か RXM-RAWX）。
 * [rel] = 区切りの時刻（ファイルの中で週の巻き戻りを展開した週内秒。ファイルの最初の週を 0 とする）。時刻のある区切りが無ければ null。
 */
class Segment(val bytes: ByteArray, val rel: Double?)

/**
 * UBX ログを [Segment] に区切って読む。UBX 以外（NMEA・壊れたバイト）もそのまま区切りに入れる（Pipeline に流すと元のファイルと同じになる）。
 * 区切りはエポックの時刻（NAV-PVT の iTOW・RXM-RAWX の rcvTow）が変わったところ（同じエポックの RAWX と PVT は同じ区切りに入る。
 * 時刻の無いメッセージは前のものと同じ区切り）。
 *
 * 時刻: 週内秒は TOW が半週以上戻ったら次の週とみなして展開する（週の境目・1024 週の巻き戻りに依存しない）。
 * 本当の GPS 時刻は RXM-RAWX の week か、NAV-PVT の UTC（日付・時刻が有効なとき）から週を決めて [offset]（週の整数倍）にする。
 * どちらも無いログは offset = null（もう 1 本に合わせる。[MergedReplayRunner]）。
 */
class UbxSegmentReader(private val input: InputStream) {
    private var buf = ByteArray(64 * 1024)
    private var start = 0
    private var end = 0
    private var eof = false
    private val cur = ByteArrayOutputStream()
    private var relWeek = 0
    private var lastTow: Double? = null
    /** いま貯めている区切りのエポックの時刻 */
    private var curRel: Double? = null

    /** 読んだバイト数 */
    var position = 0L
        private set

    /** 本当の GPS 時刻 = rel + offset（分からなければ null）。最初に分かった値のまま変えない */
    var offset: Double? = null
        private set

    private fun fill() {
        if (start > 0) {
            buf.copyInto(buf, 0, start, end)
            end -= start
            start = 0
        }
        if (end == buf.size) buf = buf.copyOf(buf.size * 2)
        val n = input.read(buf, end, buf.size - end)
        if (n < 0) eof = true else end += n
    }

    private fun junk(n: Int) {
        cur.write(buf, start, n)
        start += n
        position += n
    }

    /** 次の区切り。終わりなら null。 */
    fun next(): Segment? {
        while (true) {
            // エポックの無いログ（NAV-PVT・RXM-RAWX が無い）でも区切りが大きくなりすぎないように
            if (cur.size() >= MAX_SEGMENT) return take(curRel)
            val avail = end - start
            if (avail < 8 && !eof) {
                fill()
                continue
            }
            if (avail == 0) return flush()
            if (buf[start] != Ubx.SYNC[0] || (avail >= 2 && buf[start + 1] != Ubx.SYNC[1])) {
                // 次の 0xB5 までまとめて飛ばす
                var i = start + 1
                while (i < end && buf[i] != Ubx.SYNC[0]) i++
                junk(i - start)
                continue
            }
            if (avail < 6) {
                junk(avail) // eof の端切れ
                continue
            }
            val len = (buf[start + 4].toInt() and 0xFF) or ((buf[start + 5].toInt() and 0xFF) shl 8)
            if (len > Ubx.MAX_PAYLOAD) {
                junk(1)
                continue
            }
            val total = len + 8
            if (avail < total) {
                if (eof) junk(1) else fill()
                continue
            }
            val ck = Ubx.checksum(buf, start + 2, start + 6 + len)
            if (ck[0] != buf[start + 6 + len] || ck[1] != buf[start + 7 + len]) {
                junk(1)
                continue
            }
            val msg = MsgId(buf[start + 2].toInt() and 0xFF, buf[start + 3].toInt() and 0xFF)
            val rel = time(msg, buf.copyOfRange(start + 6, start + 6 + len))
            val c = curRel
            // 新しいエポックが来たら、それまでを 1 区切りとして返す（このフレームは次の区切りの先頭）
            val done = if (rel != null && c != null && abs(rel - c) > EPOCH_TOLERANCE) take(c) else null
            cur.write(buf, start, total)
            start += total
            position += total
            if (rel != null && (done != null || c == null)) curRel = rel
            if (done != null) return done
        }
    }

    private fun take(rel: Double?): Segment {
        val bytes = cur.toByteArray()
        cur.reset()
        return Segment(bytes, rel)
    }

    private fun flush(): Segment? = if (cur.size() == 0) null else take(curRel)

    /** エポックの時刻（rel）。NAV-PVT・RXM-RAWX 以外は null。本当の時刻が分かれば offset を決める。 */
    private fun time(msg: MsgId, payload: ByteArray): Double? {
        val tow: Double
        var absolute: Double? = null
        when (msg) {
            Ubx.NAV_PVT -> {
                val pvt = Ubx.parseNavPvt(payload) ?: return null
                tow = pvt.itow / 1000.0
                pvt.utcMillis?.let { absolute = GpsTime.resolve(tow, GpsTime.fromUtcMillis(it)) }
            }
            Ubx.RXM_RAWX -> {
                if (payload.size < 16) return null
                val r = LeReader(payload)
                tow = r.f8(0)
                if (!tow.isFinite() || tow < 0 || tow >= GpsTime.WEEK + 1) return null
                val week = r.u2(8)
                // week 0 は不明（受信機が週を知らない）。1980 年代の値も信用しない
                if (week > 1000) absolute = week * GpsTime.WEEK + tow
            }
            else -> return null
        }
        val prev = lastTow
        if (prev != null) {
            if (tow < prev - GpsTime.WEEK / 2) relWeek++ else if (tow > prev + GpsTime.WEEK / 2) relWeek--
        }
        lastTow = tow
        val rel = relWeek * GpsTime.WEEK + tow
        val a = absolute
        if (offset == null && a != null) offset = Math.rint((a - rel) / GpsTime.WEEK) * GpsTime.WEEK
        return rel
    }
}

/** 1 区切りの上限 [バイト]（これを超えたら時刻が同じままで区切る） */
private const val MAX_SEGMENT = 1 shl 20

/** 同じエポックとみなす時刻の差 [s]（RAWX の rcvTow は受信機の時計のずれで iTOW と ms 未満ずれる。25 Hz の間隔より十分小さく） */
private const val EPOCH_TOLERANCE = 0.015

/** ログの GPS 時刻の範囲 [s]（GPS 時刻 0 から）。 */
data class TimeRange(val start: Double, val end: Double) {
    val duration: Double get() = end - start

    /** 重なっている秒数（重ならなければ 0 以下） */
    fun overlap(other: TimeRange): Double = min(end, other.end) - max(start, other.start)
}

/** ログの時刻の範囲を、先頭と末尾のバイト列だけから読む（大きなファイルを全部読まない）。 */
object UbxTimeRange {
    /** 読む先頭・末尾の大きさ（u-blox の 1 Hz の記録で 10 秒ほど） */
    const val PROBE_BYTES = 256 * 1024

    /** [head] の最初と [tail] の最後のエポックの GPS 時刻。どちらかで本当の時刻が分からなければ null。 */
    fun of(head: ByteArray, tail: ByteArray): TimeRange? {
        val first = firstAndLast(head)?.first ?: return null
        val last = firstAndLast(tail)?.second ?: return null
        return TimeRange(first, max(first, last))
    }

    /** バイト列の中の最初と最後のエポックの GPS 時刻（本当の時刻が分からなければ null）。 */
    fun firstAndLast(bytes: ByteArray): Pair<Double, Double>? {
        val r = UbxSegmentReader(bytes.inputStream())
        var first: Double? = null
        var last: Double? = null
        while (true) {
            val s = r.next() ?: break
            val rel = s.rel ?: continue
            if (first == null) first = rel
            last = rel
        }
        val off = r.offset ?: return null
        return (first ?: return null) + off to last!! + off
    }
}

/** u-blox の記録に合う内蔵 GNSS の記録を選ぶ。 */
object LogPairing {
    /**
     * [base] といちばん長く重なる候補（重ならなければ null）。同じ長さなら始まりの近いもの。
     * [candidates] の時刻の範囲が分からないもの（null）は選ばない。
     */
    fun <T> best(base: TimeRange, candidates: List<Pair<T, TimeRange?>>): T? =
        candidates.filter { (_, r) -> r != null && r.overlap(base) > 0 }
            .maxWithOrNull(compareBy<Pair<T, TimeRange?>> { it.second!!.overlap(base) }.thenByDescending { abs(it.second!!.start - base.start) })
            ?.first
}

/** 再生する 1 本（流す先の Pipeline と、ファイル）。 */
class ReplayInput(val pipeline: Pipeline, val input: InputStream, val size: Long)

/**
 * 1〜2 本のログを GPS 時刻の順に流す（ReplayRunner の代わり。スレッドは呼び出し側が用意し、run() をそのスレッドで呼ぶ）。
 *
 * - 時刻: 各ファイルの区切りの時刻 = rel + offset。offset が分からないファイルは、もう 1 本の最初の時刻にいちばん近い週に置く
 *   （両方分からなければ 0。1 本なら関係ない）。最初の区切りの時刻を 0 秒とした経過時間 [elapsed] を [clock] に入れる（両方の Pipeline が同じ時計を使う）。
 * - 待ち方: 前の区切りからの時刻の差 ÷ [speed] だけ待つ（0 は待たない）。差が Link.MAX_EPOCH_GAP より大きい（記録が途切れた）ときは待たない（ReplayRunner と同じ）。
 * - [pause] / [resume]、[speed] は再生中に変えられる。[seekForward] は先へ（待たずに流して追いつく）。戻るのは作り直して [startAt] から（集計は最初から作り直す）。
 */
class MergedReplayRunner(
    private val inputs: List<ReplayInput>,
    speed: Double,
    private val clock: ReplayClock,
    /** 最初からこの経過時間 [s] まで待たずに流す（後ろへのシーク） */
    startAt: Double = 0.0,
) {
    init {
        require(inputs.isNotEmpty() && inputs.size <= 2)
    }

    @Volatile
    var speed: Double = speed
        set(v) {
            field = v
            wake()
        }

    @Volatile
    var paused = false
        private set

    @Volatile
    private var stopped = false

    @Volatile
    private var target: Double = startAt

    /** 最初の区切りからの経過時間 [s] */
    @Volatile
    var elapsed = 0.0
        private set

    /** 再生の始まりの GPS 時刻（分からなければ null） */
    @Volatile
    var startGps: Double? = null
        private set

    /** ファイルごとに読んだバイト数 */
    val positions = LongArray(inputs.size)

    /** 2 本目の時刻を 1 本目に合わせられなかった（どちらかに本当の時刻が無かった） */
    @Volatile
    var aligned = true
        private set

    private val waiter = Object()

    private fun wake() = synchronized(waiter) { waiter.notifyAll() }

    fun pause() {
        paused = true
        wake()
    }

    fun resume() {
        paused = false
        wake()
    }

    fun stop() {
        stopped = true
        wake()
    }

    /** 経過時間 [seconds] まで進める（今より前なら何もしない。戻るのは作り直す）。 */
    fun seekForward(seconds: Double) {
        if (seconds > elapsed) {
            target = seconds
            wake()
        }
    }

    /** いま待たずに流している（シーク中） */
    val seeking: Boolean get() = target > elapsed

    fun run() {
        val readers = inputs.map { UbxSegmentReader(it.input) }
        try {
            val heads = arrayOfNulls<Segment>(readers.size)
            for (i in readers.indices) heads[i] = readers[i].next()
            val offsets = alignOffsets(readers, heads)
            var startT: Double? = null
            var lastT: Double? = null
            while (!stopped) {
                // いちばん早い区切り（時刻の無い区切りは先に出す）
                var pick = -1
                var pickT = Double.MAX_VALUE
                for (i in heads.indices) {
                    val s = heads[i] ?: continue
                    val t = s.rel?.let { it + offsets[i] } ?: Double.NEGATIVE_INFINITY
                    if (pick < 0 || t < pickT) {
                        pick = i
                        pickT = t
                    }
                }
                if (pick < 0) break
                val seg = heads[pick]!!
                if (seg.rel != null) {
                    val t = pickT
                    if (startT == null) {
                        startT = t
                        startGps = if (readers.any { it.offset != null }) t else null
                    }
                    val prev = lastT
                    if (prev != null && t > prev) {
                        val step = t - prev
                        if (step <= Link.MAX_EPOCH_GAP) waitFor(step, t - startT)
                    }
                    if (stopped) break
                    if (prev == null || t > prev) lastT = t
                    elapsed = lastT!! - startT
                    clock.value = elapsed
                }
                inputs[pick].pipeline.feed(seg.bytes)
                positions[pick] += seg.bytes.size
                heads[pick] = readers[pick].next()
            }
        } finally {
            for (i in inputs) runCatching { i.input.close() }
        }
    }

    /** 区切りの時刻のずれ（rel → GPS 時刻）。 */
    private fun alignOffsets(readers: List<UbxSegmentReader>, heads: Array<Segment?>): DoubleArray {
        val out = DoubleArray(readers.size)
        val known = readers.indices.firstOrNull { readers[it].offset != null }
        for (i in readers.indices) {
            val off = readers[i].offset
            if (off != null) {
                out[i] = off
                continue
            }
            if (readers.size < 2) continue
            aligned = false
            // もう 1 本の最初の時刻にいちばん近い週に置く
            val other = known ?: continue
            val ref = heads[other]?.rel?.let { it + readers[other].offset!! } ?: continue
            val mine = heads[i]?.rel ?: continue
            out[i] = Math.rint((ref - mine) / GpsTime.WEEK) * GpsTime.WEEK
        }
        return out
    }

    /** 時刻の差 [step] 秒を再生速度で待つ（シーク中・最大速度は待たない。一時停止中は止まる）。[at] = この区切りの経過時間。 */
    private fun waitFor(step: Double, at: Double) {
        // 残りを「データの秒」で持つ（待っている途中で速度を変えても、その速度で続きを待つ）
        var remaining = step
        synchronized(waiter) {
            while (!stopped) {
                if (at <= target) return // シーク中（目標まで待たない）
                if (paused) {
                    waiter.wait()
                    continue
                }
                val sp = speed
                if (sp <= 0) return
                val ms = (remaining / sp * 1000).roundToLong()
                if (ms <= 0) return
                val t0 = System.nanoTime()
                waiter.wait(ms)
                remaining -= (System.nanoTime() - t0) / 1e9 * sp
                if (remaining <= 0) return
            }
        }
    }
}
