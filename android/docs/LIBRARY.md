# 受信機ライブラリ（ublox-core / ublox-android）

u-blox Monitor（`jp.kjtr.ubloxmonitor`）の受信機まわりを、ほかのアプリ（自転車ルートロガー `cycle_route_logger`・
ドライブロガー `drive_logger` など）からも使えるように 2 つのモジュールに分けたもの。版はライブラリとして **0.3.0**
（アプリの版とは別。0.2.0 = `OutputProfile.Logger` に `keepRaw`、0.3.0 = `UbloxReceiver.enableRtcmInput`・`ReceiverConfig.rtcmInputPacket`・`GnssFix.correctionAgeText`）、groupId は `jp.kjtr.ublox`。

| モジュール | 種類 | パッケージ / namespace | 中身 |
|---|---|---|---|
| `:ublox-core` | Kotlin/JVM（Android 非依存） | `jp.kjtr.ublox.core` | UBX の分離・デコード・組み立て（`Ubx`・`UbxStream`・`LeReader/LeWriter`）、集計（`GnssState`）、`Pipeline`（集計＋記録 `UbxLogger`）・`ReplayClock`・`ReplayRunner`、受信機設定（`ReceiverConfig`）、出力の設定（`OutputProfile`・`DynModel`）、測位の値（`GnssFix`）、RTCM3（`Rtcm`）、NTRIP（`Ntrip`・`CorrectionClient`・`SocketLink`）、内蔵 GNSS の UBX 合成（`PhoneUbx`・`PhoneRawx`）、比較（`Compare`）、グラフの計算（`ChartMath`） |
| `:ublox-android` | Android ライブラリ（minSdk 24） | `jp.kjtr.ublox.android` | `UbloxReceiver`（入口）、USB の列挙・許可・ドライバ判定（`UsbDevices`・`UsbCandidate`・`res/xml/device_filter.xml`）、`UsbSerialSource`（読み取りスレッドと送信キュー）、`GnssFix.toAndroidLocation`、`InternalGnssSource`（スマホ内蔵 GNSS）、`MockLocation`（擬似ロケーション）、`CorrectionManager`（NTRIP→受信機の転送・自動再接続）・`UsbCorrectionLink`・`CorrectionStore`・`SecretStore` |

依存: `ublox-android` → `ublox-core`、usb-serial-for-android 3.8.1（**jitpack**。`com.github.mik3y` だけ jitpack から取る設定が使う側にも要る）、
kotlinx-coroutines-android 1.7.3、androidx.core。`ublox-core` は Kotlin 標準ライブラリだけ。

画面の文字列・色（`Display`）、記録・再生の画面、フォアグラウンドサービス（`MonitorService`）はアプリ側に残してある。

## 公開 API

### ublox-android

```kotlin
class UbloxReceiver(context: Context, scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)) {
    val status: StateFlow<ReceiverStatus>        // Disconnected / Connecting(device) / Connected(device, generation, version, receiverPort, profile) / Error(message)
    val fixes: SharedFlow<GnssFix>               // NAV-PVT ごと（No Fix も流れる。hasPosition で絞る）。64 件のバッファ、遅れたら古いものを捨てる
    val latestFix: StateFlow<GnssFix?>           // 最後の測位（表示用）
    val rawBytes: SharedFlow<ByteArray>          // 受信したバイト列のコピー（購読者がいるときだけ。遅れたら捨てる）
    val connection: UbloxConnection?             // 今の接続（Pipeline・世代・受信機側のポート。Source としても使える）
    val pipeline: Pipeline?                      // 今の接続の集計（GnssState・MON-VER・ACK・MON-SPAN）
    val isConnected: Boolean

    fun devices(): List<UsbCandidate>            // シリアルとして使える USB 機器（u-blox を先に）
    fun hasPermission(device: UsbDevice): Boolean
    fun requestPermission(device: UsbDevice, onResult: (Boolean) -> Unit)
    suspend fun requestPermission(device: UsbDevice): Boolean
    fun connect(candidate: UsbCandidate, baud: Int = 921600, options: ConnectOptions = ConnectOptions(),
                onResult: (UbloxConnection?) -> Unit = {})   // 許可が無ければダイアログ → 開く。失敗は null（理由は status の Error）
    fun disconnect()
    fun send(packet: ByteArray): Boolean / send(packets: List<ByteArray>): Boolean   // 送信待ちに積む（書くのは読み取りスレッド）
    fun applyProfile(profile: OutputProfile, generation: String? = null): Boolean
    fun addRawListener(l: (ByteArray, Int, Int) -> Unit) / removeRawListener(l)      // 受信スレッドから。取りこぼさない
    fun startRecording(folder: File, prefix: String = "ublox"): LogStatus?          // そのまま .ubx（上書きしない）
    fun enableRtcmInput(generation: String? = null): RtcmInputResult              // 0.3.0〜 受信機側のポートで RTCM3 入力を ON（RAM のみ。M8 は不可）
    fun stopRecording() / recordStatus(): LogStatus?
}

data class ConnectOptions(
    val profile: OutputProfile? = OutputProfile.MONITOR,  // 接続時（MON-VER の後。2 秒で来なければその時点で）に送る。null は送らない
    val receiverPort: String? = null,                     // USB / UART1 / UART2。null は推定（VID 0x1546 は USB、変換チップは UART1）
    val generation: String? = null,                       // "F9" / "M8" など手動の世代。null は MON-VER から
    val pipeline: Pipeline? = null, val title: String? = null,
    val onLog: ((String) -> Unit)? = null, val versionTimeoutMs: Long = 2000,
)

fun GnssFix.toAndroidLocation(provider: String = "ublox", useReceiverTime: Boolean = true): Location?   // 位置が無ければ null
object UbloxExtras { FIX_TYPE = "fixType"; NUM_SV = "numSV"; CARR_SOLN = "carrSoln"; FIX_LABEL; DIFF_SOLN; PDOP; HEIGHT_MSL }

class InternalGnssSource(context, pipeline, title) : Source   // 内蔵 GNSS → NAV-PVT・NAV-SAT・RXM-RAWX（要 ACCESS_FINE_LOCATION）
class MockLocation(context) { enable(); disable(); publish(fix: GnssFix); publish(pvt: Pvt) }   // 要 ACCESS_MOCK_LOCATION
class CorrectionManager(context, store: CorrectionStore, scope, userAgent, forward, roverPvt, ubloxConnected, log, onStarted)
    // form / updateForm / start / stop / isRunning / fetchMounts / mounts / view / message / publish / statusLine。パスワードは［保存する］のときだけ SecretStore
class SharedPrefsCorrectionStore(sp: SharedPreferences) : CorrectionStore   // corr_* のキー（パスワードは入れない）
class SecretStore(context)   // Keystore の AES/GCM。保存先 correction_secret.xml（使う側でバックアップ・転送から外す）
object UsbDevices { list(context); candidate(device); findDriver(device); requestPermission(context, device, onResult) }
```

`toAndroidLocation` の中身: altitude = 楕円体高（Android の定義どおり）、accuracy = hAcc、verticalAccuracy = vAcc、
speed / bearing と精度（API 26+）、API 34+ は mslAltitudeMeters（標高）、time = 受信機の UTC（無効なら受け取った時刻）、
elapsedRealtimeNanos = 受け取った時刻、extras に fixType・numSV（"satellites" にも）・carrSoln・fixLabel・diffSoln・pdop・heightMsl。

### ublox-core

```kotlin
data class GnssFix(fixType, fixRank, gnssFixOk, carrSoln, diffSoln, numSv, latitude, longitude, height, heightMsl,
                   hAcc, vAcc, speed, speedAcc, heading, headingAcc, pdop, itow, utcMillis, correctionAge,
                   receivedAtMillis, receivedElapsedNanos) { fixLabel; hasPosition; time; correctionAgeText（0.3.0） }

sealed class OutputProfile {
    data object Monitor                                        // NAV-PVT・NAV-SAT・RXM-RAWX・RXM-SFRBX・RXM-MEASX を毎エポック（u-blox Monitor）
    data class Logger(rateHz: Int = 1, dynModel: DynModel? = DynModel.AUTOMOTIVE, keepRaw: Boolean = false)   // keepRaw は 0.2.0〜
    companion object { MONITOR; LOGGER; RATES = [1, 2, 5, 10] }
}
enum class DynModel(value, label) { PORTABLE(0) STATIONARY(2) PEDESTRIAN(3) AUTOMOTIVE(4) SEA(5) AIRBORNE_1G/2G/4G(6-8) WRIST(9) BIKE(10) LAWN_MOWER(11) ESCOOTER(12) }
```

`Pipeline`（`feed`・`state: GnssState`・`version`・`acks`・`spectrum`・`startLogging/stopLogging`・`addPvtListener`・`addRawListener`）、
`Ubx`、`ReceiverConfig`、`Rtcm`、`Ntrip`、`CorrectionClient` はアプリと同じもの（u-blox Monitor の CLAUDE.md の対応表）。

### 出力プロファイル（どちらも RAM のみ。電源を切ると戻る）

| | MONITOR | LOGGER |
|---|---|---|
| NAV-PVT | 毎エポック | 毎エポック |
| NAV-SAT | 毎エポック | rateHz エポックに 1 回（= 1 Hz） |
| RXM-RAWX / SFRBX | 毎エポック | **0（止める）**。`keepRaw = true` なら毎エポック（生の .ubx を残すとき） |
| RXM-MEASX | 毎エポック | 0（keepRaw でも止める） |
| 測位レート | 変えない | CFG-RATE-MEAS = 1000 / rateHz ms・CFG-RATE-NAV = 1（M8 は CFG-RATE） |
| ダイナミクスモデル | 変えない | CFG-NAVSPG-DYNMODEL（M8 は CFG-NAV5 の dynModel だけ mask） |

- 1 キー 1 パケット（受信機が対応しないキー・値があっても、その 1 つが NAK になるだけ）。Unknown の世代は F9 / M8 の両方の方式で送る。
- ロガーには `DynModel.BIKE`（自転車）・`AUTOMOTIVE`（車）。Bike・E-scooter は古いファームウェアでは NAK のことがある（`Pipeline.acks` / ログで分かる）。
- 10 Hz は NAV-PVT だけで約 1 KB/秒。UART 経由（FTDI など）は 115200 bps 以上に。NMEA など他の出力は変えない（必要なら受信機設定で止める）。

### RTK 補正（NTRIP）をロガーで使う（自転車ルートロガー 0.13.0 の例）

```kotlin
val corr = CorrectionManager(
    context, SharedPrefsCorrectionStore(context.getSharedPreferences("ntrip", MODE_PRIVATE)), scope,
    userAgent = "NTRIP CycleRouteLogger/0.13.0",
    forward = { ublox.send(it) },                         // 受信機へ（内蔵 GNSS には送らない）
    roverPvt = { ublox.pipeline?.state?.pvt },            // GGA・基線長
    ubloxConnected = { ublox.isConnected },
    log = { Log.i("ntrip", it) },
)
// 受信機がつながった・設定で ON → corr.start()、外れた・OFF → corr.stop()。0.5〜1 秒ごとに corr.publish() で view を更新
ublox.enableRtcmInput().message   // 「RTCM3 入力（UART1） を有効にしました（RAM のみ…）」
```

- 使う側の Manifest に INTERNET。`correction_secret.xml`（SecretStore）と入力の SharedPreferences はバックアップ・端末間の転送から外す。
- `CorrectionManager.start()` の onStarted は前面からの開始を想定（u-blox Monitor は FGS を起動）。ロガーは記録サービスがもう動いているので空でよい。

## ロガーアプリでの使い方の例

前提（両ロガーの今の作り）: 位置は `sensor/LocationHub`（LocationManager の GPS / Google の融合測位）が `updates: SharedFlow<Location>` に流し、
記録は FGS（`RecordingService`、種別 location）が受け取って Room に入れ、GPX は記録した点から書き出す。
u-blox は「LocationHub のもう 1 つの測位元」として差し込むのがいちばん手を入れずに済む。

```kotlin
// Application などで 1 つ
val ublox = UbloxReceiver(context)

// 機器の選択（設定画面）。USB を差して起動されたときは intent の EXTRA_DEVICE で選んでおく
val candidates = ublox.devices()

// 記録の開始（前面＝利用者の操作の直後に。許可のダイアログが出ることがある）
ublox.connect(
    candidates.first(), baud = 921600,
    ConnectOptions(profile = OutputProfile.Logger(rateHz = 1, dynModel = DynModel.BIKE, keepRaw = saveUbx)),   // 車は AUTOMOTIVE。.ubx を残すなら keepRaw
) { conn -> if (conn == null) showError((ublox.status.value as? ReceiverStatus.Error)?.message) }

// FGS（RecordingService）の中で: 位置を Location にして今のトラックの処理へ
scope.launch {
    ublox.fixes.collect { fix ->
        // LocationHub は provider 名で GPS かを見ている（gps / fused 以外はネットワーク扱い）ので GPS_PROVIDER を渡す
        val loc = fix.toAndroidLocation(LocationManager.GPS_PROVIDER) ?: return@collect   // No Fix は捨てる
        onLocation(loc)        // 既存の RideTracker / 記録・GPX の処理にそのまま
    }
}
scope.launch {
    ublox.status.collect { s ->
        when (s) {
            is ReceiverStatus.Connected -> showReceiver("${s.generation} ${s.version?.module ?: ""}")
            is ReceiverStatus.Error -> fallBackToPhoneGps(s.message)      // USB 抜けなど。端末の GPS に戻す
            else -> {}
        }
    }
}

// 生の UBX も残すなら（PC 版の u-blox Monitor や RTKLIB で後から解析できる）。RAWX・SFRBX を入れるには接続時に keepRaw = true
ublox.startRecording(File(context.getExternalFilesDir("ubx"), ""), prefix = "ride")

// 終わり
ublox.stopRecording()
ublox.disconnect()
```

- **記録**: `startRecording` は受信スレッドで書くので取りこぼさない（`rawBytes` の Flow は遅れたら捨てるので記録には使わない。
  自分で書くなら `addRawListener`）。ファイル名は `<prefix>_年月日_時分秒.ubx`、同名があれば開始しない（IOException）。
- **USB 抜け**: ライブラリが ACTION_USB_DEVICE_DETACHED を見て閉じ、`status` が `Error("通信が切れました: USB が外れました")` になる
  （記録も閉じる）。自動でつなぎ直さない。続けたいなら USB_DEVICE_ATTACHED を受けて同じ機器（`UsbCandidate.key` = deviceName は差し直すと変わるので VID/PID で探す）に
  `connect` し直す。その間は端末の GPS に戻すのが無難（トラックは切らずに provider だけ変わる）。
- **フォアグラウンドサービス**: ライブラリはサービスに依存しない。ロガーの FGS の下で `UbloxReceiver` を持てば画面を閉じても続く。
  USB の受信機を使う間は種別に **connectedDevice** を足す（`foregroundServiceType="location|connectedDevice"`、
  権限 `FOREGROUND_SERVICE_CONNECTED_DEVICE`）。Android 14+ の connectedDevice の前提は「`UsbManager.requestPermission` で許可を得ていること」なので、
  **`connect` が成功した後に** `startForeground(..., LOCATION or CONNECTED_DEVICE)` する（許可の前に connectedDevice を付けると SecurityException）。
  lint の ForegroundServicePermission は USB を知らないので `tools:ignore`（u-blox Monitor の Manifest と同じ）。
- **USB の許可**: 許可は「その機器を差している間」だけ。差し直すとまたダイアログになる。差したときにアプリを起動させて
  「常にこのアプリで開く」を選んでもらうと次から出ない（`<intent-filter>` に USB_DEVICE_ATTACHED、meta-data に
  `@xml/device_filter`。このファイルはライブラリに入っている）。
- **権限**: ライブラリの Manifest は何も宣言しない。USB だけなら追加の権限は要らない（`uses-feature android.hardware.usb.host required=false` を推奨）。
  内蔵 GNSS（`InternalGnssSource`）は FINE_LOCATION、擬似ロケーションは ACCESS_MOCK_LOCATION、RTK 補正は INTERNET。
- **時刻**: `GnssFix.time` は受信機の UTC（有効なとき）。端末の時計より正確だが、端末の GPS の点と混ぜるときは差（数百 ms 以内）に注意。
- **精度の意味**: hAcc は u-blox の推定精度で、Android の accuracy（68%）とは定義が少し違う。RTK Fixed なら cm 級になる。
  `extras.getInt("carrSoln")` で RTK の状態を記録に残せる（GPX の `<fix>` や拡張に入れるなど）。

## 取り込み方

**決めた方式（2026-11-22、自転車ルートロガー 0.13.0 から）: composite build ＋ mavenLocal の予備。**
使う側の `settings.gradle.kts` で `local.properties` の `ublox.dir`（無ければ既定 `../../ublox_monitor/android`、使う側のフォルダからの相対）を読み、
そのフォルダがあれば `includeBuild(dir)`（`jp.kjtr.ublox:ublox-android` / `ublox-core` の座標を `:ublox-android` / `:ublox-core` に置き換える）、
無ければ `mavenLocal()` から（ここで `publishToMavenLocal` した版を）取る。どちらも依存は座標 `jp.kjtr.ublox:ublox-android:<版>` で書く。
jitpack（`com.github.mik3y` だけ）は使う側の `dependencyResolutionManagement` にも要る。

```kotlin
// 使う側の settings.gradle.kts（cycle_route_logger の例）
val localProps = java.util.Properties().apply {
    val f = file("local.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}
val ubloxDir = file(localProps.getProperty("ublox.dir") ?: "../../ublox_monitor/android")
val useUbloxSource = ubloxDir.resolve("settings.gradle.kts").isFile
if (useUbloxSource) {
    includeBuild(ubloxDir) {
        dependencySubstitution {
            substitute(module("jp.kjtr.ublox:ublox-android")).using(project(":ublox-android"))
            substitute(module("jp.kjtr.ublox:ublox-core")).using(project(":ublox-core"))
        }
    }
}
// dependencyResolutionManagement の repositories に: if (!useUbloxSource) mavenLocal { content { includeGroup("jp.kjtr.ublox") } }
```

- composite build でも、このビルドの `:app` は設定（configure）されるだけで作られない（keystore.properties が無くても通る）。
  AGP / Kotlin の版は使う側とそろえる（今はどちらも AGP 8.7.3 / Kotlin 2.0.21）。
- 他の PC では u-blox-Monitor リポジトリを、`android/` が上の場所に来るように clone するか、`ublox.dir` を書くか、ここで `publishToMavenLocal` する。
- 今使っているのは自転車ルートロガー（0.13.0〜）。ドライブロガーも同じ作りで入れる予定。

検討したときの比較（参考）:

| | mavenLocal に publish | ソースのコピー | composite build（includeBuild） |
|---|---|---|---|
| やり方 | ここで `.\gradlew.bat publishToMavenLocal` → ロガーの settings に `mavenLocal()`、`implementation("jp.kjtr.ublox:ublox-android:0.1.0")` | `ublox-core` / `ublox-android` のフォルダをロガーにコピーして `include` | ロガーの settings.gradle.kts に `includeBuild("../ublox_monitor")`、依存は同じ座標で書く（Gradle が project に置き換える） |
| 更新 | 版を上げて publish し直す。版で固定できる | 手で同期（差分が出やすい） | いつも最新のソース（ublox_monitor を変えるとロガーのビルドも変わる） |
| アプリ単位の git | ◎（ロガーのリポジトリに入るのは座標だけ。ただし他の PC・CI では ~/.m2 に無いのでビルドできない） | ◎（ロガーだけでビルドできる） | △（隣のフォルダの ublox_monitor が必要。リポジトリが別なので他の PC では両方 clone） |
| ビルドの重さ | 軽い（aar / jar） | ふつう | ublox_monitor の設定も読む（app は作らない） |
| 向き | 版を決めて配るとき | 手を入れて別物にしてよいとき | ライブラリも一緒に直しながら作るとき |

当時のおすすめ: **開発中は composite build、公開する版を作るときは mavenLocal の版を固定**（どちらも座標が同じなので切り替えは settings の 1 行）。
ソースのコピーは「このワークスペースは 1 アプリ = 1 フォルダで完結」の決まりには合うが、二重管理になる。
いずれの案でも、ロガーの `settings.gradle.kts` の `dependencyResolutionManagement` に jitpack（`com.github.mik3y` だけ）を足すこと
（usb-serial-for-android は Maven Central に無い）。

```powershell
# 確認済み（2026-11-22、0.3.0）
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
cd d:\work\ublox_monitor\android
.\gradlew.bat publishToMavenLocal
# → ~/.m2/repository/jp/kjtr/ublox/ublox-core/0.3.0（jar・sources・pom・module）、ublox-android/0.3.0（aar・sources・pom・module）
```

ライブラリの版（0.3.0）は `ublox-core/build.gradle.kts`・`ublox-android/build.gradle.kts` の `version`。公開 API を変えたら上げる。
