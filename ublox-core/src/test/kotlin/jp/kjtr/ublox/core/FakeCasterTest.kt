package jp.kjtr.ublox.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * tools/fake_caster.py との疎通（実際のソケット）。環境変数 FAKE_CASTER（例 127.0.0.1:2101）が無ければ skip する。
 *
 *   python tools/fake_caster.py --port 2101
 *   $env:FAKE_CASTER="127.0.0.1:2101"; .\gradlew.bat testDebugUnitTest --tests "*FakeCasterTest*"
 */
class FakeCasterTest {
    private val target: Pair<String, Int>? = System.getenv("FAKE_CASTER")?.split(":")?.let { it[0] to it[1].toInt() }

    private fun config(version: Int, mount: String = "TOKYO", password: String = "test") = CorrectionConfig(
        CorrectionKind.NTRIP, host = target!!.first, port = target.second, mount = mount, user = "test",
        password = password, version = version, ggaInterval = 1,
    )

    private val gga = "\$GPGGA,000000.00,3540.00000,N,13946.00000,E,4,12,0.9,40.000,M,36.700,M,,*00\r\n"

    @Test
    fun sourcetableV1AndV2() {
        assumeTrue("FAKE_CASTER が無いので skip", target != null)
        for (version in listOf(1, 2)) {
            val (host, port) = target!!
            val mounts = Ntrip.fetchSourcetable(SocketLink(host, port, false), host, port, "", "", version)
            assertEquals(listOf("TOKYO", "OSAKA", "VRS"), mounts.map { it.mount })
            assertTrue(mounts[2].nmea)
        }
    }

    @Test
    fun streamV1AndV2() {
        assumeTrue("FAKE_CASTER が無いので skip", target != null)
        for (version in listOf(1, 2)) {
            val forwarded = ByteArrayOutputStream()
            val client = CorrectionClient(
                config(version), { SocketLink(it.host, it.port, false) },
                { synchronized(forwarded) { forwarded.write(it) } }, { gga }, reconnect = false,
            )
            client.start()
            val deadline = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < deadline && client.snapshot().messages.size < 5) Thread.sleep(50)
            client.stop()
            client.join(3000)
            val snap = client.snapshot()
            println("NTRIP $version: ${snap.bytes} バイト、メッセージ ${snap.messages.map { it.first }}、基準局 ${snap.station}")
            assertEquals(listOf(1005, 1074, 1084, 1094, 1124), snap.messages.map { it.first })
            assertEquals(1234, snap.station?.id)
            assertEquals(0, snap.crcErrors)
            assertEquals(snap.bytes, forwarded.size().toLong())
        }
    }

    @Test
    fun wrongPasswordAndMissingMount() {
        assumeTrue("FAKE_CASTER が無いので skip", target != null)
        val denied = CorrectionClient(config(2, password = "wrong"), { SocketLink(it.host, it.port, false) }, {})
        denied.run()
        assertTrue(denied.error!!, "401" in denied.error!!)
        val missing2 = CorrectionClient(config(2, mount = "NONE"), { SocketLink(it.host, it.port, false) }, {})
        missing2.run()
        assertTrue(missing2.error!!, "404" in missing2.error!!)
        val missing1 = CorrectionClient(config(1, mount = "NONE"), { SocketLink(it.host, it.port, false) }, {})
        missing1.run()
        assertEquals("マウントポイントが見つかりません（ソーステーブルが返りました）", missing1.error)
    }
}
