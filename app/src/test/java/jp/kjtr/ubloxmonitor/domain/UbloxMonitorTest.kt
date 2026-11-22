package jp.kjtr.ubloxmonitor.domain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import kotlin.math.abs

/** 移植元: tests/test_ublox_monitor.py（GUI のテストを除く）。 */
class UbloxMonitorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun hexMap(m: Map<Long, ByteArray>) = m.mapValues { hex(it.value) }
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun approx(expected: Double, actual: Double?, tol: Double = 1e-6) {
        assertNotNull(actual)
        assertTrue("expected $expected but $actual", abs(expected - actual!!) <= tol * maxOf(1.0, abs(expected)))
    }

    private fun concat(list: List<ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        list.forEach { out.write(it) }
        return out.toByteArray()
    }

    /** data を再生用の時計で Pipeline に流す。 */
    private fun replay(data: ByteArray, chunk: Int = 97): Pipeline {
        val clock = ReplayClock()
        val pipeline = Pipeline(clock)
        pipeline.onEpoch = { step -> clock.value += step }
        var pos = 0
        while (pos < data.size) {
            val n = minOf(chunk, data.size - pos)
            pipeline.feed(data, pos, n)
            pos += n
        }
        return pipeline
    }

    private fun onlyPayload(packet: ByteArray): ByteArray = UbxStream().feed(packet).single().payload

    // ---- ubx ----

    @Test
    fun buildMatchesKnownPacket() {
        assertEquals("b5620a0400000e34", hex(Ubx.build(Ubx.MON_VER)))
    }

    @Test
    fun streamSplitsFramesAcrossChunksAndSkipsGarbage() {
        val packets = listOf(Ubx.build(Ubx.NAV_PVT, ByteArray(92)), Ubx.build(Ubx.MON_VER), UbxSamples.navSat(1))
        val data = concat(
            listOf("\$GNGGA,junk\r\n".toByteArray(), packets[0], bytes(0xB5, 0x00, 0xB5), packets[1], packets[2])
        )
        val stream = UbxStream()
        val frames = ArrayList<Frame>()
        var pos = 0
        while (pos < data.size) {
            val n = minOf(7, data.size - pos)
            frames += stream.feed(data, pos, n)
            pos += n
        }
        assertEquals(listOf(Ubx.NAV_PVT, Ubx.MON_VER, Ubx.NAV_SAT), frames.map { it.msg })
        assertEquals(0, stream.checksumErrors)
    }

    @Test
    fun streamRejectsBadChecksumAndRecovers() {
        val broken = Ubx.build(Ubx.NAV_PVT, ByteArray(92))
        broken[20] = (broken[20].toInt() xor 0xFF).toByte()
        val stream = UbxStream()
        val frames = stream.feed(broken + Ubx.build(Ubx.MON_VER))
        assertEquals(listOf(Ubx.MON_VER), frames.map { it.msg })
        assertEquals(1, stream.checksumErrors)
    }

    @Test
    fun navPvtFields() {
        val pvt = Ubx.parseNavPvt(onlyPayload(UbxSamples.navPvt(123000, 35.5, 139.25, carrier = 2)))!!
        assertEquals("RTK Fixed", pvt.fixLabel)
        approx(35.5, pvt.latitude)
        approx(139.25, pvt.longitude)
        approx(45.123, pvt.height)
        approx(8.456, pvt.heightMsl)
        approx(0.014, pvt.hacc)
        approx(1.25, pvt.speed)
        approx(90.0, pvt.heading)
        approx(1.2, pvt.pdop)
        assertEquals(12, pvt.numSv)
        assertTrue(pvt.timeValid)
    }

    @Test
    fun navPvtFixLabel() {
        val cases = listOf(Triple(1, 0x03, "RTK Float"), Triple(0, 0x03, "DGNSS"), Triple(0, 0x01, "3D"), Triple(2, 0x00, "No Fix"))
        for ((carrier, flags, label) in cases) {
            val payload = onlyPayload(UbxSamples.navPvt(0))
            payload[21] = (flags or (carrier shl 6)).toByte()
            val pvt = Ubx.parseNavPvt(payload)!!
            assertEquals(label, pvt.fixLabel)
            assertEquals(label == "No Fix", pvt.latitude == null)
        }
    }

    @Test
    fun correctionAgeText() {
        val pvt = Ubx.parseNavPvt(onlyPayload(UbxSamples.navPvt(0, correctionAge = 2)))!!
        assertEquals("1〜2 秒", pvt.correctionAgeText)
        assertEquals("停止中（経過 1〜2 秒）", Display.correctionText(pvt))
    }

    @Test
    fun sfrbxSubframeOnlyForLnav() {
        val frame = Ubx.parseRxmSfrbx(onlyPayload(UbxSamples.rxmSfrbx(0, 5, 4)))
        assertEquals(Sfrbx(0, 5, 0, 4), frame)
        val galileo = Ubx.parseRxmSfrbx(onlyPayload(UbxSamples.rxmSfrbx(2, 5, 4)))!!
        assertEquals(0, galileo.subframe)
    }

    @Test
    fun monVerGeneration() {
        val cases = listOf(
            listOf("ZED-F9P", "27.50", "00190000", "F9"), listOf("ZED-X20P", "50.10", "000B0000", "X20"),
            listOf("NEO-M8T", "22.00", "00080000", "M8"),
        )
        for ((module, protver, hardware, gen) in cases) {
            val version = Ubx.parseMonVer(onlyPayload(UbxSamples.monVer(module, protver, hardware)))!!
            assertEquals(gen, version.gen)
            assertEquals(module, version.module)
        }
    }

    @Test
    fun valgetValuesHaveKeyDependentSizes() {
        val payload = LeWriter().zeros(4).u4(0x201100A4).u1(0xF6).u4(0x30210001).u2(200)
            .u4(0x1031001F).u1(1).u4(0x40520001).u4(460800).toByteArray()
        val values = Ubx.parseValget(payload)
        assertEquals(
            mapOf(0x201100A4L to "f6", 0x30210001L to "c800", 0x1031001FL to "01", 0x40520001L to hex(LeWriter().u4(460800).toByteArray())),
            hexMap(values),
        )
    }

    @Test
    fun valsetRoundTripsThroughValgetParser() {
        val packet = Ubx.buildValset(linkedMapOf(0x201100A4L to -10L, 0x30210001L to 200L), Ubx.LAYER_RAM, setOf(0x201100A4L))
        val frame = UbxStream().feed(packet).single()
        assertEquals(Ubx.CFG_VALSET, frame.msg)
        assertArrayEquals(bytes(0, 1, 0, 0), frame.payload.copyOf(4))
        assertEquals(mapOf(0x201100A4L to "f6", 0x30210001L to "c800"), hexMap(Ubx.parseValget(frame.payload)))
    }

    @Test
    fun nav5SetsOnlyTheMasksOfChangedFields() {
        val base = ByteArray(36)
        base[2] = 0; base[3] = 3; base[12] = 5; base[24] = 0; base[25] = 0
        val payload = onlyPayload(Ubx.buildNav5(base, mapOf("minelev" to -5, "mincno" to 20)))
        assertEquals(0x0102, LeReader(payload).u2(0))
        assertEquals(Nav5(dynmodel = 0, fixmode = 3, minelev = -5, minsvs = 0, mincno = 20), Ubx.parseNav5(payload))
    }

    @Test
    fun monSpanBlocksAndFrequencies() {
        val frame = UbxStream().feed(UbxSamples.monSpan()).single()
        assertEquals(Ubx.MON_SPAN, frame.msg)
        val blocks = Ubx.parseMonSpan(frame.payload)
        assertEquals(2, blocks.size)
        val block = blocks[0]
        assertEquals(listOf(128_000_000L, 500_000L, 1_583_400_000L, 54L), listOf(block.span, block.res, block.center, block.pga.toLong()))
        assertEquals(256, block.spectrum.size)
        assertEquals((frame.payload[4].toInt() and 0xFF) * 0.25, block.spectrum[0], 0.0)
        val freqs = Ubx.spanFrequencies(block)
        approx(1583.4, freqs[128])
        approx(1583.4 - 64, freqs[0])
        approx(0.5, freqs[1] - freqs[0])
        // 山は L1 帯の信号の中心周波数のどれかにある
        val peak = (0 until 256).maxBy { block.spectrum[it] }
        assertTrue(listOf(1575.42, 1561.098, 1602.0).minOf { abs(freqs[peak] - it) } < 2)
    }

    // ---- settings ----

    @Test
    fun currentValuesFromKeysAndChangedOnly() {
        val cfg = mapOf(0x201100A4L to bytes(0xF6), 0x20110021L to bytes(0x04), 0x1031001FL to bytes(0x01))
        val values = ReceiverConfig.currentValues("F9", cfg, null, null)
        assertEquals(mapOf("minelev" to -10, "dynmodel" to 4, "GPS" to 1), values)
        val chosen = mapOf("minelev" to 15, "dynmodel" to 4, "GPS" to 1, "mincno" to 30)
        assertEquals(mapOf("minelev" to 15), ReceiverConfig.changed(values, chosen))
    }

    @Test
    fun writePacketsForF9UseOneValset() {
        val packets = ReceiverConfig.writePackets("F9", linkedMapOf("minelev" to -3, "GPS_L5" to 1), Ubx.LAYER_RAM, null, null)
        val frame = UbxStream().feed(concat(packets)).single()
        assertEquals(Ubx.CFG_VALSET, frame.msg)
        assertEquals(mapOf(0x201100A4L to "fd", 0x10310004L to "01"), hexMap(Ubx.parseValget(frame.payload)))
        assertTrue(ReceiverConfig.restartsTracking(mapOf("GPS_L5" to 1)))
        assertFalse(ReceiverConfig.restartsTracking(mapOf("minelev" to -3)))
    }

    @Test
    fun m8ReadsAndWritesLegacyMessages() {
        val nav5 = ByteArray(36)
        nav5[2] = 4; nav5[3] = 3; nav5[12] = 10; nav5[24] = 3; nav5[25] = 25
        val rate = Rate(1000, 1, 1)
        val values = ReceiverConfig.currentValues("M8", emptyMap(), nav5, rate)
        assertEquals(
            mapOf("dynmodel" to 4, "fixmode" to 3, "minelev" to 10, "mincno" to 25, "minsvs" to 3, "measrate" to 1000, "navrate" to 1),
            values,
        )
        val packets = ReceiverConfig.writePackets("M8", linkedMapOf("dynmodel" to 2, "measrate" to 200), Ubx.LAYER_RAM, nav5, rate)
        val frames = UbxStream().feed(concat(packets))
        assertEquals(listOf(Ubx.CFG_NAV5, Ubx.CFG_RATE), frames.map { it.msg })
        assertEquals(2, frames[0].payload[2].toInt())
        assertEquals(0x0001, LeReader(frames[0].payload).u2(0))
        assertEquals(Rate(200, 1, 1), Ubx.parseRate(frames[1].payload))
    }

    @Test
    fun readPacketsPollEachKeySeparately() {
        val packets = ReceiverConfig.readPackets("F9")
        assertEquals(ReceiverConfig.ITEMS.size, packets.size)
        val frames = UbxStream().feed(concat(packets))
        assertTrue(frames.all { it.msg == Ubx.CFG_VALGET && it.payload.size == 8 })
        assertEquals(ReceiverConfig.ITEMS.size, ReceiverConfig.ITEMS.map { it.key }.toSet().size)
    }

    // ---- serial_link / state ----

    @Test
    fun outputPacketsTargetTheConnectedPort() {
        val usb = UbxStream().feed(concat(Link.outputPackets("F9", "USB")))
        assertEquals(Link.OUTPUT_MESSAGES.size, usb.size)
        assertEquals(mapOf(0x20910009L to "01"), hexMap(Ubx.parseValget(usb[0].payload)))    // NAV-PVT USB
        val uart = UbxStream().feed(concat(Link.outputPackets("F9", "UART1")))
        assertEquals(mapOf(0x209102A5L to "01"), hexMap(Ubx.parseValget(uart[2].payload)))   // RXM-RAWX UART1
        val m8 = UbxStream().feed(concat(Link.outputPackets("M8", "USB")))
        assertEquals(Ubx.CFG_MSG, m8[0].msg)
        assertArrayEquals(bytes(0x01, 0x07, 1), m8[0].payload)
        // Unknown は両方送る
        assertEquals(Link.OUTPUT_MESSAGES.size * 2, Link.outputPackets("Unknown", "USB").size)
    }

    @Test
    fun pipelineAggregatesALog() {
        val pipeline = replay(UbxSamples.log(seconds = 20))
        val snap = pipeline.state.snapshot()
        assertEquals("F9", pipeline.version!!.gen)
        assertEquals(0, pipeline.stream.checksumErrors)
        assertEquals(20, snap.pvtHistory.size)
        approx(19.0, snap.now)
        assertEquals(UbxSamples.SATELLITES.size, snap.satellites.size)
        val g05 = snap.satellites.getValue("G05")
        assertEquals(40, g05.azim)
        assertEquals(62, g05.elev)
        assertTrue(g05.used)
        assertEquals(setOf("L1", "L2"), g05.bands.keys)
        assertEquals(4, snap.signals.getValue("GPS L1CA").size)
        assertEquals(20, snap.counts[Ubx.NAV_PVT])
        val monitor = pipeline.state.monitorSnapshot()
        assertEquals(listOf(5, 13, 15, 24), monitor.seen["GPS"])
        assertEquals(10 + 9 * 3, monitor.tracks.getValue("QZSS").getValue(2).rmse)
        assertEquals(SfrbxEntry(20, setOf(1, 2, 3, 4, 5)), monitor.sfrbx.getValue("GPS")[5])
        val trend = pipeline.state.trendSnapshot()
        assertEquals(20 * 3, trend.history.getValue("GPS").getValue(5).size)   // RAWX 2 信号 + MEASX
        assertEquals(20, pipeline.spectrumCount)
    }

    @Test
    fun stateLimitsMonitorSlotsAndDropsStaleSignals() {
        val clock = ReplayClock()
        val state = GnssState(clock)
        state.updateRawx((1 until 10).map { RawxMeas(5, it, 0, 40, prValid = true, cpValid = true) })
        assertEquals(listOf(1, 2, 3, 4, 5, 6), state.monitorSnapshot().seen["QZSS"])
        assertEquals(9, state.snapshot().satellites.size)
        clock.value = 10.0
        assertTrue(state.snapshot().satellites.isEmpty())
    }

    @Test
    fun navSatKnownRule() {
        // 仰角 0・方位 0 で quality < 4 は位置不明、quality >= 4 なら有効
        val payload = LeWriter().u4(0).u1(1).u1(2).u2(0)
            .u1(0).u1(1).u1(30).u1(0).u2(0).u2(0).u4(0x03)
            .u1(0).u1(2).u1(30).u1(0).u2(0).u2(0).u4(0x04)
            .toByteArray()
        val sats = Ubx.parseNavSat(payload)
        assertNull(sats[0].elev)
        assertEquals(0, sats[1].elev)
    }

    @Test
    fun loggerWritesRawBytes() {
        val pipeline = Pipeline()
        pipeline.startLogging(tmp.root)
        val data = UbxSamples.log(seconds = 2)
        pipeline.feed(data)
        val status = pipeline.logStatus()!!
        pipeline.stopLogging()
        assertEquals(data.size.toLong(), status.size)
        assertNull(pipeline.logStatus())
        assertArrayEquals(data, status.file.readBytes())
        assertTrue(status.file.name.matches(Regex("ublox_\\d{8}_\\d{6}\\.ubx")))
    }

    @Test
    fun replayRunnerAdvancesClockByEpochs() {
        val clock = ReplayClock()
        val pipeline = Pipeline(clock)
        val runner = ReplayRunner(pipeline, ByteArrayInputStream(UbxSamples.log(seconds = 10)), 0.0, clock)
        runner.run()
        approx(9.0, clock.value)
        assertEquals(10, pipeline.state.snapshot().pvtHistory.size)
    }

    @Test
    fun thinKeepsLastPoint() {
        val fixes = (0 until 5001).map { (35.0 + it * 1e-6) to 139.0 }
        val points = ChartMath.thin(fixes)
        assertTrue(points.size <= 2501)
        assertEquals(Math.round(fixes.last().first * 1e7) / 1e7 to 139.0, points.last())
    }

    // ---- 表示 ----

    @Test
    fun displayRowsFromALog() {
        val snap = replay(UbxSamples.log(seconds = 5)).state.snapshot()
        val status = Display.statusText(snap.pvt)
        assertTrue(status.getValue("lat").startsWith("35.68"))
        assertTrue(status.getValue("mode") in listOf("RTK Fixed", "RTK Float"))
        val rows = Display.satelliteRows(snap)
        assertEquals(UbxSamples.SATELLITES.size, rows.size)
        assertEquals(listOf("C21", "20", "44", "○"), rows[0].take(4))
        assertTrue(Display.signalRows(snap).isNotEmpty())
        val series = ChartMath.pvtSeries(snap.pvtHistory, 60.0)
        assertEquals(5, series.mode.getValue("測位状態").size)
        assertEquals(setOf("東西", "南北", "高さ"), series.offset.keys)
    }

    @Test
    fun niceStepPicksOneTwoFive() {
        assertEquals(1.0, ChartMath.niceStep(5.0, 6), 1e-12)
        assertEquals(0.2, ChartMath.niceStep(1.0, 6), 1e-12)
        assertEquals(1.0, ChartMath.niceStep(0.5, 4, integer = true), 1e-12)
        assertEquals(1, ChartMath.labelDigits(0.2))
        assertEquals(0, ChartMath.labelDigits(5.0))
    }
}
