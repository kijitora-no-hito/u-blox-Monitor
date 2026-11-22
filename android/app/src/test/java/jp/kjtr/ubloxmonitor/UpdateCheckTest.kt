package jp.kjtr.ubloxmonitor

import jp.kjtr.ubloxmonitor.update.AppVersion
import jp.kjtr.ubloxmonitor.update.Releases
import jp.kjtr.ubloxmonitor.update.UpdateChecker
import jp.kjtr.ubloxmonitor.update.UpdateMemo
import jp.kjtr.ubloxmonitor.update.UpdatePolicy
import jp.kjtr.ubloxmonitor.update.UpdateStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.5.0: 更新の確認（GitHub API のリリース一覧 → メイン画面の帯）。自転車ルートロガーの UpdateCheckTest に相当。 */
class UpdateCheckTest {

    private fun v(s: String) = AppVersion.parse(s)!!

    @Test
    fun versionsCompareAsNumberTuples() {
        assertTrue(v("0.4.4") < v("0.5.0"))
        assertTrue(v("0.9") < v("0.10"))
        assertTrue(v("0.4.4") < v("0.4.10"))
        assertEquals(0, v("0.5").compareTo(v("0.5.0")))
        assertTrue(v("0.5.0-dev") < v("0.5.0"))
        assertTrue(v("1.0.0") > v("0.99.0"))
        assertEquals(v("0.3.0"), v("v0.3.0"))
        for (bad in listOf("", "abc", "1..2", "1.2.x", "v", "-dev", "1.2.3.4.5")) assertNull(bad, AppVersion.parse(bad))
        assertNotNull(AppVersion.parse(BuildConfig.VERSION_NAME))
    }

    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun rel(tag: String, draft: Boolean = false, pre: Boolean = true, body: String? = "", assets: List<String>? = null): String {
        val ver = tag.substringAfterLast('v')
        val names = assets ?: listOf("ublox_monitor_android_$ver.apk")
        val a = names.joinToString(",") {
            """{"name":${q(it)},"browser_download_url":"https://github.com/kijitora-no-hito/u-blox-Monitor/releases/download/$tag/$it","size":1}"""
        }
        val b = if (body == null) "null" else q(body)
        return """{"tag_name":${q(tag)},"draft":$draft,"prerelease":$pre,"html_url":"https://github.com/kijitora-no-hito/u-blox-Monitor/releases/tag/$tag","body":$b,"assets":[$a],"author":{"login":"x"}}"""
    }

    private fun list(vararg r: String) = "[" + r.joinToString(",") + "]"

    @Test
    fun picksHighestAndroidTagIgnoringWindowsAndDrafts() {
        val text = list(
            rel("v0.9.0", pre = false, assets = listOf("ublox_monitor_Setup_0.9.0.exe")), // Windows 版（無視）
            rel("android-v0.4.4", body = "## u-blox Monitor Android 版 0.4.4\n\n- **修正**: `NAV-SAT`"),
            rel("android-v0.6.0", draft = true), // draft（無視）
            rel("android-v0.10.0"),
            rel("android-v0.5.0"),
        )
        val r = Releases.parse(text)!!
        assertEquals("android-v0.10.0", r.tag)
        assertEquals("0.10.0", r.version)
        assertEquals("https://github.com/kijitora-no-hito/u-blox-Monitor/releases/download/android-v0.10.0/ublox_monitor_android_0.10.0.apk", r.downloadUrl)
        assertEquals("https://github.com/kijitora-no-hito/u-blox-Monitor/releases/tag/android-v0.10.0", r.pageUrl)
        // Android 版が無い（Windows 版だけ）・空・壊れた JSON → null
        assertNull(Releases.parse(list(rel("v0.3.0", pre = false))))
        assertNull(Releases.parse("[]"))
        assertNull(Releases.parse("{\"message\":\"API rate limit exceeded\"}"))
        assertNull(Releases.parse("not json"))
        // 形の違うタグ・URL に入れられない文字は読まない
        assertNull(Releases.parse(list(rel("android-vx.y"), rel("android-v0.5.0/../x"))))
    }

    @Test
    fun downloadFallsBackToAnyApkThenPage() {
        val other = Releases.parse(list(rel("android-v0.5.0", assets = listOf("notes.txt", "other.apk"))))!!
        assertTrue(other.downloadUrl.endsWith("/other.apk"))
        val none = Releases.parse(list(rel("android-v0.5.0", assets = emptyList())))!!
        assertEquals(none.pageUrl, none.downloadUrl)
        // GitHub 以外の URL は使わない
        val evil = Releases.parse(
            """[{"tag_name":"android-v0.5.0","draft":false,"html_url":"https://example.com/x",
               "assets":[{"name":"ublox_monitor_android_0.5.0.apk","browser_download_url":"https://example.com/a.apk"}]}]""",
        )!!
        assertTrue(evil.pageUrl.startsWith("https://github.com/kijitora-no-hito/u-blox-Monitor/releases/tag/"))
        assertEquals(evil.pageUrl, evil.downloadUrl)
        // 本文が null でも読める
        assertEquals("", Releases.parse(list(rel("android-v0.5.0", body = null)))!!.notes)
    }

    @Test
    fun plainNotesStripsMarkdown() {
        val n = "## 見出し\r\n\r\n\r\n> **注意**: `0.4.2` は使わない\n- 項目 1\n* 項目 2\n"
        assertEquals("見出し\n\n注意: 0.4.2 は使わない\n・項目 1\n・項目 2", Releases.plainNotes(n))
    }

    @Test
    fun policyInterval() {
        assertEquals(12, UpdatePolicy.INTERVAL_HOURS)
        assertTrue(UpdatePolicy.isDue(1000, 0))
        assertFalse(UpdatePolicy.isDue(UpdatePolicy.INTERVAL_MS - 1 + 5, 5))
        assertTrue(UpdatePolicy.isDue(UpdatePolicy.INTERVAL_MS + 5, 5))
        assertTrue(UpdatePolicy.isDue(4, 5)) // 時計が戻った
    }

    /** 取得を同期で返すテスト用の checker。 */
    private class Harness(current: String, var response: String?, memo: UpdateMemo = UpdateMemo(), var now: Long = 1_000_000L) {
        var saved = memo
        val checker = UpdateChecker(current, { done -> done(response) }, { saved }, { saved = it }, { now })
    }

    @Test
    fun checkerFlow() {
        val h = Harness("0.4.4", list(rel("android-v0.5.0"), rel("v0.9.0", pre = false)))
        // OFF なら取りに行かない
        assertFalse(h.checker.onLaunch(false))
        assertEquals(0, h.checker.fetches)
        assertTrue(h.checker.onLaunch(true))
        assertEquals(UpdateStatus.AVAILABLE, h.checker.status)
        assertEquals("0.5.0", h.checker.banner(true)!!.version)
        assertNull(h.checker.banner(false))
        // 12 時間以内はもう取りに行かない
        assertFalse(h.checker.onLaunch(true))
        // あとで → 出さない（メモリだけ）
        h.checker.dismiss()
        assertNull(h.checker.banner(true))
        // 覚えた版は次の起動でも出る（オフラインでも）
        val h2 = Harness("0.4.4", null, h.saved)
        assertEquals("0.5.0", h2.checker.banner(true)!!.version)
        // この版は通知しない → 保存され、次の起動でも出ない
        h2.checker.skip()
        assertEquals("android-v0.5.0", h2.saved.skippedTag)
        val h3 = Harness("0.4.4", list(rel("android-v0.5.0")), h2.saved)
        assertNull(h3.checker.banner(true))
        // もっと新しい版が出たらまた出す
        h3.response = list(rel("android-v0.5.1"))
        h3.now += UpdatePolicy.INTERVAL_MS
        assertTrue(h3.checker.onLaunch(true))
        assertEquals("0.5.1", h3.checker.banner(true)!!.version)
        // 今すぐ確認は skip を取り消す
        h3.checker.skip()
        assertNull(h3.checker.banner(true))
        assertTrue(h3.checker.checkNow(true))
        assertEquals("0.5.1", h3.checker.banner(true)!!.version)
        assertEquals("", h3.saved.skippedTag)
    }

    @Test
    fun checkerLatestAndFailure() {
        val h = Harness("0.5.0", list(rel("android-v0.5.0")))
        assertTrue(h.checker.checkNow(true))
        assertEquals(UpdateStatus.LATEST, h.checker.status)
        assertNull(h.checker.banner(true))
        h.response = null
        assertTrue(h.checker.checkNow(true))
        assertEquals(UpdateStatus.FAILED, h.checker.status)
        assertTrue(h.saved.lastSuccessMs > 0)
        // debug の pretend: 古い版として比べ直す
        h.response = list(rel("android-v0.5.0"))
        assertTrue(h.checker.pretend("0.4.0", true))
        assertEquals("0.5.0", h.checker.banner(true)!!.version)
        assertFalse(h.checker.pretend("bad", true))
    }
}
