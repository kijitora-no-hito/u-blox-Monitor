package jp.kjtr.ubloxmonitor.domain

import jp.kjtr.ublox.core.ChartMath
import jp.kjtr.ublox.core.MsgId
import jp.kjtr.ublox.core.PhoneFix
import jp.kjtr.ublox.core.PhoneUbx
import jp.kjtr.ublox.core.Ubx
import jp.kjtr.ublox.core.UbxSamples
import jp.kjtr.ublox.core.UbxStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 画面の文字列（Display）。集計・グラフの計算のテストは ublox-core。 */
class DisplayTest {
    private fun onlyPayload(packet: ByteArray): ByteArray = UbxStream().feed(packet).single().payload

    @Test
    fun correctionText() {
        val pvt = Ubx.parseNavPvt(onlyPayload(UbxSamples.navPvt(0, correctionAge = 2)))!!
        assertEquals("停止中（経過 1〜2 秒）", Display.correctionText(pvt))
    }

    @Test
    fun displayRowsFromALog() {
        val snap = UbxSamples.replay(UbxSamples.log(seconds = 5)).state.snapshot()
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
    fun syntheticPvtHasNoPdop() {
        val pvt = Ubx.parseNavPvt(onlyPayload(PhoneUbx.navPvt(PhoneFix(1704067200250L, 35.0, 139.0, altitude = 40.0), 5)))!!
        assertEquals("—", Display.statusText(pvt).getValue("pdop"))
    }

    @Test
    fun messageTextShowsKnownFirst() {
        val counts = linkedMapOf(
            MsgId(0x27, 0x00) to 500, MsgId(0x0A, 0x11) to 400, Ubx.RXM_RAWX to 30, Ubx.NAV_PVT to 30,
            Ubx.NAV_SAT to 30, MsgId(0x01, 0x03) to 300, Ubx.MON_VER to 1, MsgId(0x0A, 0x09) to 200,
        )
        val lines = Display.messageText(counts).split("\n")
        assertEquals(listOf("NAV-PVT: 30", "NAV-SAT: 30", "RXM-RAWX: 30", "MON-VER: 1", "27-00: 500", "0A-11: 400"), lines)
    }
}
