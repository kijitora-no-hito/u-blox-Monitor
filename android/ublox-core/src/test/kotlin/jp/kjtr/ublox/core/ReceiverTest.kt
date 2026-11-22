package jp.kjtr.ublox.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ロガー向けの値（GnssFix）と出力の設定（OutputProfile）。 */
class ReceiverTest {
    private fun frames(packets: List<ByteArray>): List<Frame> = packets.flatMap { UbxStream().feed(it) }
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test
    fun gnssFixFromNavPvt() {
        val payload = UbxStream().feed(UbxSamples.navPvt(123000, 35.5, 139.25, carrier = 2, numSv = 17)).single().payload
        val pvt = Ubx.parseNavPvt(payload)!!
        val fix = GnssFix.from(pvt, receivedAtMillis = 1L, receivedElapsedNanos = 2L)
        assertEquals(3, fix.fixType)
        assertEquals(2, fix.carrSoln)
        assertEquals("RTK Fixed", fix.fixLabel)
        assertEquals(17, fix.numSv)
        assertTrue(fix.hasPosition)
        assertEquals(35.5, fix.latitude!!, 1e-7)
        assertEquals(45.123, fix.height!!, 1e-6)
        assertEquals(8.456, fix.heightMsl!!, 1e-6)
        assertEquals(0.05, fix.speedAcc!!, 1e-9)    // sAcc 50 mm/s
        assertEquals(1.0, fix.headingAcc!!, 1e-9)   // headAcc 100000 × 1e-5°
        // 2026/11/17 03:02:03（UbxSamples の日付）
        assertEquals(1794884523000L, fix.utcMillis)
        assertEquals(fix.utcMillis, fix.time)
    }

    @Test
    fun utcMillisMatchesCalendar() {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(2024, 1, 29, 23, 59, 59)
        }
        assertEquals(cal.timeInMillis + 500, Ubx.utcMillis(2024, 2, 29, 23, 59, 59, 500_000_000))
        // nano が負（秒の切り上げの補正）
        assertEquals(cal.timeInMillis - 1, Ubx.utcMillis(2024, 2, 29, 23, 59, 59, -1_000_000))
        assertEquals(0L, Ubx.utcMillis(1970, 1, 1, 0, 0, 0, 0))
        assertNull(Ubx.utcMillis(2024, 13, 1, 0, 0, 0, 0))
    }

    @Test
    fun noFixHasNoPosition() {
        val payload = UbxStream().feed(UbxSamples.navPvt(0)).single().payload
        payload[21] = 0
        val fix = GnssFix.from(Ubx.parseNavPvt(payload)!!)
        assertFalse(fix.hasPosition)
        assertFalse(fix.gnssFixOk)
        assertEquals("No Fix", fix.fixLabel)
    }

    @Test
    fun monitorProfileIsTheOldOutputSetup() {
        assertEquals(
            Link.outputPackets("X20", "UART1").map { hex(it) },
            OutputProfile.MONITOR.packets("X20", "UART1").map { hex(it) },
        )
    }

    @Test
    fun loggerProfileForF9SetsRateDynModelAndOnlyPvtSat() {
        val profile = OutputProfile.Logger(rateHz = 5, dynModel = DynModel.BIKE)
        val f = frames(profile.packets("X20", "USB"))
        assertTrue(f.all { it.msg == Ubx.CFG_VALSET })
        // 1 パケット 1 キー（未対応のキーがあっても他が通るように）、RAM のみ
        assertTrue(f.all { Ubx.parseValget(it.payload).size == 1 && it.payload[1].toInt() == Ubx.LAYER_RAM })
        val values = f.associate { fr -> Ubx.parseValget(fr.payload).entries.single().let { it.key to hex(it.value) } }
        assertEquals("01", values[0x20910009L])     // NAV-PVT（USB）毎エポック
        assertEquals("05", values[0x20910018L])     // NAV-SAT（USB）5 エポックに 1 回 = 1 Hz
        assertEquals("00", values[0x209102A7L])     // RXM-RAWX（USB）止める
        assertEquals("00", values[0x20910234L])     // RXM-SFRBX
        assertEquals("00", values[0x20910207L])     // RXM-MEASX
        assertEquals("c800", values[OutputProfile.KEY_RATE_MEAS])   // 200 ms
        assertEquals("0100", values[OutputProfile.KEY_RATE_NAV])
        assertEquals("0a", values[OutputProfile.KEY_DYNMODEL])      // Bike
        assertEquals(8, f.size)
    }

    @Test
    fun loggerProfileForM8UsesLegacyMessages() {
        val f = frames(OutputProfile.Logger(rateHz = 10, dynModel = DynModel.AUTOMOTIVE).packets("M8", "USB"))
        assertEquals(listOf(Ubx.CFG_MSG, Ubx.CFG_MSG, Ubx.CFG_MSG, Ubx.CFG_MSG, Ubx.CFG_MSG, Ubx.CFG_RATE, Ubx.CFG_NAV5), f.map { it.msg })
        assertEquals("010701", hex(f[0].payload))
        assertEquals("01350a", hex(f[1].payload))
        assertEquals(Rate(100, 1, 1), Ubx.parseRate(f[5].payload))
        assertEquals(0x0001, LeReader(f[6].payload).u2(0))
        assertEquals(4, Ubx.parseNav5(f[6].payload)!!.dynmodel)
        // 動的モデルを変えないときは CFG-NAV5 を送らない
        assertTrue(OutputProfile.Logger(1, null).packets("M8", "USB").none { UbxStream().feed(it).single().msg == Ubx.CFG_NAV5 })
        // Unknown は両方
        assertEquals(8 + 7, OutputProfile.Logger(1, DynModel.BIKE).packets("Unknown", "USB").size)
    }

    @Test
    fun loggerProfileKeepRawOutputsRawxAndSfrbx() {
        // 既定は生データを止める（今までどおり）
        assertFalse(OutputProfile.Logger().keepRaw)
        assertEquals(OutputProfile.Logger(), OutputProfile.LOGGER)
        val f = frames(OutputProfile.Logger(rateHz = 1, dynModel = DynModel.BIKE, keepRaw = true).packets("F9", "USB"))
        val values = f.associate { fr -> Ubx.parseValget(fr.payload).entries.single().let { it.key to hex(it.value) } }
        assertEquals("01", values[0x20910009L])     // NAV-PVT
        assertEquals("01", values[0x209102A7L])     // RXM-RAWX 毎エポック
        assertEquals("01", values[0x20910234L])     // RXM-SFRBX 毎エポック
        assertEquals("00", values[0x20910207L])     // RXM-MEASX は止めたまま
        assertEquals(8, f.size)
        // M8 も同じ（CFG-MSG の rate）
        val m8 = frames(OutputProfile.Logger(rateHz = 1, dynModel = null, keepRaw = true).packets("M8", "USB"))
            .filter { it.msg == Ubx.CFG_MSG }.map { hex(it.payload) }
        assertTrue("021501" in m8)   // RXM-RAWX
        assertTrue("021301" in m8)   // RXM-SFRBX
        assertTrue("021400" in m8)   // RXM-MEASX
        assertTrue(OutputProfile.Logger(keepRaw = true).label.contains("RAWX"))
    }

    @Test
    fun rtcmInputPacketIsRamValsetOnThePort() {
        val f = UbxStream().feed(ReceiverConfig.rtcmInputPacket("X20", "UART1")!!).single()
        assertEquals(Ubx.CFG_VALSET, f.msg)
        assertEquals(Ubx.LAYER_RAM, f.payload[1].toInt())
        assertEquals("01", hex(Ubx.parseValget(f.payload).getValue(0x10730004L)))   // CFG-UART1INPROT-RTCM3X
        val usb = UbxStream().feed(ReceiverConfig.rtcmInputPacket("F9", "USB")!!).single()
        assertEquals(setOf(0x10770004L), Ubx.parseValget(usb.payload).keys)
        assertEquals("RTCM3 入力（USB）", ReceiverConfig.rtcmInputItem("USB")!!.label)
        // M8 は設定キーが無い・知らないポートは作らない
        assertNull(ReceiverConfig.rtcmInputPacket("M8", "USB"))
        assertNull(ReceiverConfig.rtcmInputPacket("F9", "SPI"))
    }

    @Test
    fun correctionAgeTextOnlyWhenCorrected() {
        val payload = UbxStream().feed(UbxSamples.navPvt(0, 35.5, 139.25)).single().payload
        val fix = GnssFix.from(Ubx.parseNavPvt(payload)!!)
        assertEquals(null, fix.copy(correctionAge = 0).correctionAgeText)
        assertEquals("1 秒未満", fix.copy(correctionAge = 1).correctionAgeText)
        assertEquals("2〜5 秒", fix.copy(correctionAge = 3).correctionAgeText)
    }

    @Test(expected = IllegalArgumentException::class)
    fun loggerRateMustBeSupported() {
        OutputProfile.Logger(rateHz = 3)
    }

    @Test
    fun pipelineListenersSeeEveryPvtAndByte() {
        val pipeline = Pipeline()
        var pvts = 0
        var bytes = 0L
        pipeline.addPvtListener { pvts++ }
        pipeline.addRawListener { _, _, n -> bytes += n }
        val data = UbxSamples.log(seconds = 3)
        pipeline.feed(data)
        assertEquals(3, pvts)
        assertEquals(data.size.toLong(), bytes)
    }
}

/** MON-VER の拡張の衛星系の行で、受信機が対応しない衛星系・信号を「—（非対応）」にする（X20P の GLONASS）。 */
class SupportedSystemsTest {
    private fun version(systems: List<String>) =
        Ubx.parseMonVer(UbxStream().feed(UbxSamples.monVer("ZED-X20P", "50.02", "000B0000", systems)).single().payload)!!

    @Test
    fun x20pHasNoGlonass() {
        val v = version(listOf("GPS;GAL;BDS", "SBAS;QZSS", "NAVIC"))
        val supported = ReceiverConfig.supportedSystems(v)!!
        assertEquals(setOf("GPS", "GAL", "BDS", "SBAS", "QZSS", "NAVIC"), supported)
        val unsupported = ReceiverConfig.unsupportedItems(supported)
        assertEquals(setOf("GLO", "GLO_L1", "GLO_L2"), unsupported)
        // X20P は GLONASS のキーにも 0 を返す（NAK にならない）が、現在値には入れない
        val cfg = mapOf(0x10310025L to byteArrayOf(0), 0x10310018L to byteArrayOf(0), 0x1031001FL to byteArrayOf(1))
        val values = ReceiverConfig.currentValues("X20", cfg, null, null, unsupported)
        assertEquals(mapOf("GPS" to 1), values)
        // 変更も送らない（現在値に無い項目は changed に入らない）
        assertTrue(ReceiverConfig.changed(values, mapOf("GLO" to 1, "GPS" to 1)).isEmpty())
    }

    @Test
    fun f9pWithGlonassAndOldFirmware() {
        val f9p = ReceiverConfig.supportedSystems(version(listOf("GPS;GLO;GAL;BDS", "SBAS;QZSS")))
        assertEquals(setOf("NAVIC", "NAVIC_L5"), ReceiverConfig.unsupportedItems(f9p))
        // 衛星系の行が無い古いファームウェアは今までどおり（値の有無だけ）
        assertNull(ReceiverConfig.supportedSystems(version(emptyList())))
        assertTrue(ReceiverConfig.unsupportedItems(null).isEmpty())
        assertNull(ReceiverConfig.supportedSystems(null))
    }
}
