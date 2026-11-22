package jp.kjtr.ubloxmonitor.domain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * 移植元: tests/test_rtcm.py。偽のキャスターはソケットの代わりに、決めたバイト列を返す [ScriptLink] で置き換える。
 * NTRIP の再接続（Python 版に無い）のテストも足した。
 */
class RtcmTest {
    companion object {
        /** 東京付近の ECEF [m] */
        val STATION = Triple(-3961904.0123, 3348993.7654, 3698211.4321)

        /** value を length ビットの 2 の補数で bits に足す。 */
        private fun setBits(bits: StringBuilder, value: Long, length: Int) {
            val masked = value and ((1L shl length) - 1)
            bits.append(java.lang.Long.toBinaryString(masked).padStart(length, '0'))
        }

        /** 1005 のペイロードを作る。 */
        fun msg1005(stationId: Int = 1234, ecef: Triple<Double, Double, Double> = STATION): ByteArray {
            val bits = StringBuilder()
            for ((value, length) in listOf(
                1005L to 12, stationId.toLong() to 12, 0L to 6, 1L to 1, 1L to 1, 1L to 1, 0L to 1,
                (ecef.first * 1e4).roundToLong() to 38, 0L to 1, 0L to 1, (ecef.second * 1e4).roundToLong() to 38,
                0L to 2, (ecef.third * 1e4).roundToLong() to 38,
            )) setBits(bits, value, length)
            while (bits.length % 8 != 0) bits.append('0')
            return ByteArray(bits.length / 8) { i -> bits.substring(i * 8, i * 8 + 8).toInt(2).toByte() }
        }

        /** 中身はダミーの MSM ペイロードを作る。 */
        fun msm(msgType: Int, size: Int = 40): ByteArray {
            val out = ByteArray(2 + size)
            out[0] = (msgType shr 4).toByte()
            out[1] = ((msgType and 0x0F) shl 4).toByte()
            return out
        }

        val SOURCETABLE = "STR;TOKYO;Tokyo;RTCM 3.3;1005(10),1077(1);2;GPS+GLO;NET;JPN;35.68;139.77;1;0;" +
            "gen;none;B;N;9600;\r\nSTR;OSAKA;Osaka;RTCM 3.2;1074(1);2;GPS;NET;JPN;34.69;135.50;" +
            "0;0;gen;none;B;N;9600;\r\nENDSOURCETABLE\r\n"
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun onlyPayload(packet: ByteArray): ByteArray = UbxStream().feed(packet).single().payload

    private fun approx(expected: Double, actual: Double?, tol: Double) {
        assertNotNull(actual)
        assertTrue("expected $expected but $actual", abs(expected - actual!!) <= tol)
    }

    @Test
    fun crc24qCheckValue() {
        assertEquals(0xCDE703, Rtcm.crc24q("123456789".toByteArray()))
    }

    @Test
    fun streamSplitsFramesAndRejectsBadCrc() {
        val frames = listOf(Rtcm.buildFrame(msm(1077)), Rtcm.buildFrame(msg1005()))
        val broken = Rtcm.buildFrame(msm(1087))
        broken[10] = (broken[10].toInt() xor 0xFF).toByte()
        val data = concat(byteArrayOf(0, 'j'.code.toByte(), 'u'.code.toByte(), 'n'.code.toByte(), 'k'.code.toByte()), frames[0], broken, frames[1])
        val stream = RtcmStream()
        val payloads = ArrayList<ByteArray>()
        for (pos in data.indices step 5) payloads += stream.feed(data, pos, minOf(5, data.size - pos))
        assertEquals(listOf(1077, 1005), payloads.map { Rtcm.messageType(it) })
        assertEquals(1, stream.crcErrors)
    }

    @Test
    fun stationPositionAndBaseline() {
        val station = Rtcm.parseStation(msg1005())!!
        assertEquals(1234, station.id)
        approx(STATION.first, station.x, 1e-4)
        approx(STATION.second, station.y, 1e-4)
        approx(STATION.third, station.z, 1e-4)
        val back = Rtcm.llhToEcef(station.lat, station.lon, station.height)
        approx(STATION.first, back.first, 1e-3)
        approx(STATION.second, back.second, 1e-3)
        approx(STATION.third, back.third, 1e-3)
        val pvt = Ubx.parseNavPvt(onlyPayload(UbxSamples.navPvt(0, station.lat, station.lon)))
        // サンプルの楕円体高は 45.123 m なので、距離はほぼ高さの差になる
        approx(abs(station.height - 45.123), Rtcm.baseline(station, pvt), 0.01)
        assertNull(Rtcm.baseline(null, pvt))
        assertNull(Rtcm.parseStation(msm(1077)))
    }

    @Test
    fun messageNames() {
        assertEquals("GPS MSM7", Rtcm.messageName(1077))
        assertEquals("BeiDou MSM7", Rtcm.messageName(1127))
        assertEquals("基準局の位置", Rtcm.messageName(1005))
        assertEquals("", Rtcm.messageName(9999))
    }

    @Test
    fun ggaSentence() {
        val sentence = Rtcm.buildGga(Ubx.parseNavPvt(onlyPayload(UbxSamples.navPvt(123000, 35.5, 139.25, carrier = 2))))!!
        val (body, checksum) = sentence.trim().substring(1).split("*")
        val fields = body.split(",")
        assertEquals(listOf("GPGGA", "030203.00", "3530.00000", "N", "13915.00000", "E", "4"), fields.take(7))
        assertEquals("8.456", fields[9])
        assertEquals("36.667", fields[11])
        var expected = 0
        body.toByteArray().forEach { expected = expected xor it.toInt() }
        assertEquals(expected, checksum.toInt(16))
        assertTrue(sentence.endsWith("\r\n"))
        assertNull(Rtcm.buildGga(null))
        // 南緯・西経
        val south = Rtcm.buildGga(Ubx.parseNavPvt(onlyPayload(UbxSamples.navPvt(0, -33.25, -70.5, carrier = 1))))!!
        assertTrue(south, ",3315.00000,S,07030.00000,W,5," in south)
    }

    @Test
    fun navPvtCorrectionAge() {
        val pvt = Ubx.parseNavPvt(onlyPayload(UbxSamples.navPvt(0, correctionAge = 4)))!!
        assertEquals(4, pvt.correctionAge)
        assertEquals("5〜10 秒", pvt.correctionAgeText)
        assertTrue(pvt.diffSoln)
    }

    @Test
    fun chunkedDecoderAcrossPieces() {
        val body = "5\r\nhello\r\n6;ext=1\r\n world\r\n0\r\n\r\n".toByteArray()
        val decoder = ChunkedDecoder()
        val out = ByteArrayOutputStream()
        for (i in body.indices step 3) out.write(decoder.feed(body.copyOfRange(i, minOf(i + 3, body.size))))
        assertEquals("hello world", String(out.toByteArray()))
    }

    @Test
    fun rtcm3InputKeysCoverEachReceiverPort() {
        val keys = ReceiverConfig.RTCM3_INPUT.values.map { ReceiverConfig.ITEM_BY_IDENT.getValue(it).key }.toSet()
        assertEquals(setOf(0x10770004L, 0x10730004L, 0x10750004L), keys)
    }

    // ---- NTRIP の要求・応答 ----

    @Test
    fun requestHeaders() {
        val v1 = String(Ntrip.request("caster.example", 2101, "TOKYO", "user", "pass", 1, "NTRIP test/1"))
        assertTrue(v1.startsWith("GET /TOKYO HTTP/1.0\r\nUser-Agent: NTRIP test/1\r\n"))
        assertTrue("Authorization: Basic dXNlcjpwYXNz" in v1)
        assertFalse("Ntrip-Version" in v1)
        assertTrue(v1.endsWith("\r\n\r\n"))
        val v2 = String(Ntrip.request("caster.example", 2101, "", "", "", 2))
        assertTrue(v2.startsWith("GET / HTTP/1.1\r\n"))
        assertTrue("Host: caster.example:2101\r\n" in v2 && "Ntrip-Version: Ntrip/2.0\r\n" in v2)
        assertFalse("Authorization" in v2)
        assertEquals("", Ntrip.base64(ByteArray(0)))
        assertEquals("YQ==", Ntrip.base64("a".toByteArray()))
        assertEquals("YWI=", Ntrip.base64("ab".toByteArray()))
        assertEquals("dGVzdDp0ZXN0", Ntrip.base64("test:test".toByteArray()))
    }

    @Test
    fun splitHeaderIcyAndHttp() {
        assertNull(Ntrip.splitHeader("ICY 200".toByteArray()))
        val (h1, r1) = Ntrip.splitHeader("ICY 200 OK\r\n\r\nAB".toByteArray())!!
        assertEquals("ICY 200 OK", h1)
        assertEquals("AB", String(r1))
        assertNull(Ntrip.splitHeader("HTTP/1.1 200 OK\r\nX: y\r\n".toByteArray()))
        val (h2, r2) = Ntrip.splitHeader("HTTP/1.1 200 OK\r\nX: y\r\n\r\nCD".toByteArray())!!
        assertEquals("HTTP/1.1 200 OK\r\nX: y", h2)
        assertEquals("CD", String(r2))
        assertEquals("" to "", Ntrip.splitHeader(ByteArray(0), force = true)!!.let { it.first to String(it.second) })
    }

    @Test
    fun streamResponseMessages() {
        Ntrip.checkStreamResponse("ICY 200 OK")
        Ntrip.checkStreamResponse("HTTP/1.1 200 OK\r\nNtrip-Version: Ntrip/2.0")
        fun reject(header: String): CorrectionRejected =
            try {
                Ntrip.checkStreamResponse(header)
                throw AssertionError("拒否されない: $header")
            } catch (e: CorrectionRejected) {
                e
            }
        reject("HTTP/1.1 401 Unauthorized").let { assertTrue(it.permanent && "401" in it.message!! && "認証" in it.message!!) }
        reject("SOURCETABLE 200 OK").let { assertTrue(it.permanent && "マウントポイント" in it.message!!) }
        reject("HTTP/1.1 404 Not Found").let { assertTrue(it.permanent && "マウントポイント" in it.message!!) }
        reject("HTTP/1.1 503 Service Unavailable").let { assertFalse(it.permanent) }
        reject("").let { assertTrue("応答なし" in it.message!!) }
    }

    @Test
    fun sourcetableParseAndDistance() {
        val mounts = Ntrip.parseSourcetable(SOURCETABLE)
        assertEquals(listOf("TOKYO", "OSAKA"), mounts.map { it.mount })
        assertTrue(mounts[0].nmea)
        assertFalse(mounts[1].nmea)
        assertEquals("RTCM 3.3", mounts[0].format)
        assertEquals(35.68, mounts[0].latitude!!, 1e-9)
        val km = Ntrip.distanceKm(mounts[0].latitude!!, mounts[0].longitude!!, mounts[1].latitude!!, mounts[1].longitude!!)
        assertTrue("$km", km in 390.0..410.0)   // 東京〜大阪 約 400 km
        val wrapped = Mount("A", "", "", "", "", "", "35", "220", false)
        assertEquals(-140.0, wrapped.longitude!!, 1e-9)
        assertNull(Mount("A", "", "", "", "", "", "x", "", false).latitude)
    }

    // ---- 偽のキャスター（バイト列） ----

    /** chunks を順に返し、尽きたら -1（相手が切った）を返す通信路。 */
    private class ScriptLink(chunks: List<ByteArray>, private val onRead: () -> Unit = {}) : CorrectionLink {
        private val queue = ArrayDeque(chunks)
        val written = ByteArrayOutputStream()
        var closed = false

        override fun read(buf: ByteArray): Int {
            onRead()
            val next = queue.removeFirstOrNull() ?: return -1
            if (next.size > buf.size) {
                next.copyInto(buf, 0, 0, buf.size)
                queue.addFirst(next.copyOfRange(buf.size, next.size))
                return buf.size
            }
            next.copyInto(buf)
            return next.size
        }

        override fun write(data: ByteArray) = written.write(data)
        override fun close() {
            closed = true
        }
    }

    private val frames = listOf(Rtcm.buildFrame(msg1005()), Rtcm.buildFrame(msm(1077)), Rtcm.buildFrame(msm(1127)))
    private val gga = "\$GPGGA,000000.00,3540.00000,N,13946.00000,E,1,08,1.0,40.0,M,36.7,M,,*00\r\n"

    private fun config(version: Int) = CorrectionConfig(
        CorrectionKind.NTRIP, host = "127.0.0.1", port = 2101, mount = "TOKYO", user = "user", password = "pass",
        version = version, ggaInterval = 1,
    )

    /** 偽のキャスターの応答 script で受信して、転送されたバイト列・通信路・クライアントを返す。 */
    private fun runClient(version: Int, script: List<ByteArray>): Triple<ByteArray, ScriptLink, CorrectionClient> {
        val link = ScriptLink(script)
        val forwarded = ByteArrayOutputStream()
        val client = CorrectionClient(config(version), { link }, { forwarded.write(it) }, { gga }, reconnect = false)
        client.run()
        return Triple(forwarded.toByteArray(), link, client)
    }

    private fun pieces(data: ByteArray, size: Int) = (data.indices step size).map { data.copyOfRange(it, minOf(it + size, data.size)) }

    private fun checkForwarded(forwarded: ByteArray, link: ScriptLink, client: CorrectionClient, version: Int) {
        assertArrayEquals(concat(*frames.toTypedArray()), forwarded)
        val request = String(link.written.toByteArray())
        assertTrue(request.startsWith("GET /TOKYO HTTP/1."))
        assertTrue("Authorization: Basic dXNlcjpwYXNz" in request)
        assertEquals(version == 2, "Ntrip-Version: Ntrip/2.0" in request)
        assertTrue("\$GPGGA" in request)
        val snap = client.snapshot()
        assertEquals(listOf(1005, 1077, 1127), snap.messages.map { it.first })
        assertEquals(1234, snap.station?.id)
        assertEquals(0, snap.crcErrors)
        assertEquals(forwarded.size.toLong(), snap.bytes)
        assertEquals("相手が接続を切りました", client.error)
        assertEquals("停止", client.status)
        assertTrue(link.closed)
    }

    @Test
    fun ntripV1ForwardsRtcmAndSendsGga() {
        val data = concat(*frames.toTypedArray())
        val script = listOf("ICY 200 OK\r\n".toByteArray() + data.copyOfRange(0, 20), data.copyOfRange(20, data.size))
        val (forwarded, link, client) = runClient(1, script)
        checkForwarded(forwarded, link, client, 1)
    }

    @Test
    fun ntripV2ChunkedForwardsRtcm() {
        val data = concat(*frames.toTypedArray())
        val out = ByteArrayOutputStream()
        out.write("HTTP/1.1 200 OK\r\nNtrip-Version: Ntrip/2.0\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray())
        for (piece in listOf(data.copyOfRange(0, 30), data.copyOfRange(30, data.size))) {
            out.write("%x\r\n".format(piece.size).toByteArray())
            out.write(piece)
            out.write("\r\n".toByteArray())
        }
        // ヘッダーと本体が 7 バイトずつばらばらに届く場合
        val (forwarded, link, client) = runClient(2, pieces(out.toByteArray(), 7))
        checkForwarded(forwarded, link, client, 2)
    }

    @Test
    fun ntripRejectionIsNotRetried() {
        val sleeps = ArrayList<Double>()
        val link = ScriptLink(listOf("HTTP/1.1 401 Unauthorized\r\n\r\n".toByteArray()))
        val forwarded = ByteArrayOutputStream()
        val client = CorrectionClient(config(2), { link }, { forwarded.write(it) }, sleeper = { sleeps.add(it) })
        client.run()
        assertEquals(0, forwarded.size())
        assertTrue(client.error!!, "401" in client.error!!)
        assertTrue(sleeps.isEmpty())
        assertEquals("停止", client.status)
    }

    @Test
    fun mountpointMissingIsReported() {
        val body = SOURCETABLE.toByteArray()
        val (_, _, client) = runClient(1, listOf("SOURCETABLE 200 OK\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray() + body))
        assertEquals("マウントポイントが見つかりません（ソーステーブルが返りました）", client.error)
    }

    @Test
    fun sourcetableFetch() {
        val body = SOURCETABLE.toByteArray()
        val reply = "SOURCETABLE 200 OK\r\nContent-Type: text/plain\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray() + body
        val link = ScriptLink(pieces(reply, 50))
        val mounts = Ntrip.fetchSourcetable(link, "127.0.0.1", 2101, "", "", 1)
        assertEquals(listOf("TOKYO", "OSAKA"), mounts.map { it.mount })
        assertTrue(mounts[0].nmea && !mounts[1].nmea)
        assertTrue(String(link.written.toByteArray()).startsWith("GET / HTTP/1.0"))
        // NTRIP 2.0（HTTP/1.1 200 + chunked）
        val v2 = ByteArrayOutputStream()
        v2.write("HTTP/1.1 200 OK\r\nNtrip-Version: Ntrip/2.0\r\nContent-Type: gnss/sourcetable\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray())
        v2.write("%x\r\n".format(body.size).toByteArray())
        v2.write(body)
        v2.write("\r\n0\r\n\r\n".toByteArray())
        assertEquals(2, Ntrip.fetchSourcetable(ScriptLink(pieces(v2.toByteArray(), 13)), "h", 2101, "", "", 2).size)
        // 401
        try {
            Ntrip.fetchSourcetable(ScriptLink(listOf("HTTP/1.1 401 Unauthorized\r\n\r\n".toByteArray())), "h", 2101, "u", "p", 2)
            throw AssertionError("401 で例外にならない")
        } catch (e: IOException) {
            assertTrue("401" in e.message!!)
        }
    }

    @Test
    fun reconnectBacksOffExponentially() {
        lateinit var client: CorrectionClient
        val opened = intArrayOf(0)
        client = CorrectionClient(
            config(2),
            {
                opened[0]++
                throw IOException("つながりません")
            },
            {},
            sleeper = { if (client.delays.size >= 8) client.stop() },
        )
        client.run()
        assertEquals(listOf(2.0, 4.0, 8.0, 16.0, 32.0, 60.0, 60.0, 60.0), client.delays.toList())
        assertEquals(8, opened[0])
        assertEquals("つながりません", client.error)
        assertEquals(7, client.reconnects)
    }

    @Test
    fun reconnectAfterStableSessionStartsAgainFromTwoSeconds() {
        var now = 0.0
        lateinit var client: CorrectionClient
        val forwarded = ByteArrayOutputStream()
        val events = ArrayList<String>()
        client = CorrectionClient(
            config(1),
            // 1 フレーム届いたあと 11 秒たって切れる接続
            { ScriptLink(listOf("ICY 200 OK\r\n".toByteArray() + frames[0])) { now += 11.0 } },
            { forwarded.write(it) },
            onEvent = { events.add(it) },
            clock = { now },
            sleeper = { now += it; if (client.delays.size >= 3) client.stop() },
        )
        client.run()
        assertEquals(listOf(2.0, 2.0, 2.0), client.delays.toList())
        assertEquals(frames[0].size * 3, forwarded.size())
        assertTrue(events.any { "再開" in it })
        assertTrue(events.first(), events.first().startsWith("補正情報の受信が止まりました: 相手が接続を切りました"))
    }

    @Test
    fun noDataTimeoutReconnects() {
        var now = 0.0
        lateinit var client: CorrectionClient
        val link = object : CorrectionLink {
            var first = true
            override fun read(buf: ByteArray): Int {
                if (first) {
                    first = false
                    val head = "ICY 200 OK\r\n".toByteArray()
                    head.copyInto(buf)
                    return head.size
                }
                now += 1.0
                return 0   // データが来ない
            }

            override fun write(data: ByteArray) {}
            override fun close() {}
        }
        client = CorrectionClient(config(1), { link }, {}, clock = { now }, sleeper = { client.stop() })
        client.run()
        assertEquals("30 秒間データが届きません", client.error)
        assertEquals(listOf(2.0), client.delays.toList())
    }

    @Test
    fun configHidesPassword() {
        assertFalse("pass" in config(2).toString().replace("password", ""))
        assertEquals("TOKYO", config(2).target)
    }
}
