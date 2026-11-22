# CLAUDE.md（ublox_monitor）

「u-blox Monitor」の Android 版。PC 用 Python アプリ（`d:\work\ublox_monitor\`、PySide6、MIT、作者本人のもの）を
Kotlin + Jetpack Compose に移植している。**挙動・表示項目・文言は Python 版を正とする**（測位状態の順位判定、NAV-SAT の known 判定、
STALE_SECONDS、帯域の振り分け、接続時の設定送信など）。共通方針は `d:\work\android\CLAUDE.md`。

## 基本情報

- アプリ名: u-blox Monitor / applicationId・namespace・パッケージ: `jp.kjtr.ubloxmonitor`（公開後は変えない）
- minSdk 24 / compileSdk・targetSdk 36（edge-to-edge 強制。Scaffold は `WindowInsets.safeDrawing`）。縦横どちらも対応（幅 600dp 以上で 2 列）
- versionCode 9 / versionName 0.5.0（0.4.0 = 3 段目: スペクトル・u-blox モニター、受信機まわりのライブラリ化。0.4.1 = エミュレータで見つかった u-blox モニターの修正。
  0.4.2 = 「その他」→「このアプリについて」（版・MIT・使っているソフトウェアのライセンス・地図の出典・通信）を追加。GitHub で公開。0.4.3 = 実機（Pixel 10 Pro + X20P/FT230X 921600）で取りこぼし（NAV-PVT・RAWX がほぼ全滅）が出たので USB の読み取りを専用スレッドに分け、FTDI の latency timer を 1 ms に（UsbSerialSource）。0.4.4 = 内蔵 GNSS の NAV-SAT を NAV-PVT（位置が無ければ RXM-RAWX）と同じ epoch の iTOW で続けて出す（端末の時計の iTOW だと u-center で epoch が分かれ、衛星の履歴が 1 回おきに抜けた）。0.4.4 は**公開中（プレビュー）**。0.5.0（2026-11-23、**未公開・実機未確認**）= 地図に Google マップ（標準・航空写真・航空写真+ラベル）・水平精度の円・直近の点・縮尺・ばらつき、地図の表示状態を保存、更新の確認（GitHub API・メインの帯）、u-blox と内蔵の記録の 2 本同時の再生（GPS 時刻で同期）と再生の一時停止・シーク・速度の変更）
- **置き場所・配布（2026-11-22〜）**: PC 版の公開リポジトリ `kijitora-no-hito/u-blox-Monitor`（public・MIT）の `android/` に同居（git subtree で履歴ごと取り込み）。
  正本は `d:\work\ublox_monitor\android`（`d:\work\android\ublox_monitor` はそこを指すジャンクションの予定）。git 操作は `d:\work\ublox_monitor` で行う。
  APK はそのリポジトリの Releases に `android-v<版>` のタグで（Windows 版は `v<版>`。Windows 版を Latest のままにするため Android 版は prerelease）。
  ファイル名は `ublox_monitor_android_<版>.apk`。README の「ダウンロード」の表とリンク・SHA-256 を版ごとに直す。**公開リポジトリなので位置入りのログ・スクリーンショットを入れない**
- ライブラリ `:ublox-core` / `:ublox-android` は版 **0.4.0**（groupId `jp.kjtr.ublox`。アプリの版とは別。0.4.0 = ublox-core に `ReplayMerge.kt`（2 本のログを GPS 時刻で合わせて再生。追加だけ）。0.2.0 = `OutputProfile.Logger(keepRaw)`、0.3.0 = `UbloxReceiver.enableRtcmInput`・`ReceiverConfig.rtcmInputPacket`・`GnssFix.correctionAgeText`（RTK 補正をロガーからも使うため。app の enableRtcmInput も rtcmInputPacket を使う）。公開 API・取り込み方は `docs/LIBRARY.md`。自転車ルートロガー 0.13.0 が composite build（無ければ mavenLocal）で使っている）
- 権限: INTERNET・ACCESS_NETWORK_STATE（地図タイル・RTK 補正・更新の確認）、FOREGROUND_SERVICE(_CONNECTED_DEVICE / _LOCATION)、POST_NOTIFICATIONS、
  ACCESS_FINE/COARSE_LOCATION（内蔵 GNSS と location 種別のサービス用。接続タブで［内蔵 GNSS を開始］を押したときに実行時に要求）、
  ACCESS_MOCK_LOCATION（擬似ロケーション）
- 通信: 地図タブで［地図を表示］を押したときだけ国土地理院のタイル（Google の種類を選んだときは Google Maps SDK のタイル）。地理院を表示中は出典「出典：国土地理院」を地図上に常時表示（消さない）。
  Google のときは Google のロゴ・著作権表示（SDK が出す）だけ。
  更新の確認（0.5.0、既定 ON、「このアプリについて」で OFF）: 起動時に 12 時間に 1 回まで `api.github.com/repos/kijitora-no-hito/u-blox-Monitor/releases?per_page=20` を認証なしで GET（OFF なら一切通信しない）。
  RTK 補正タブで［補正情報の受信を開始］・［一覧を取得］を押したときだけ、利用者が入れた NTRIP キャスター / TCP サーバ
  （GGA を送る設定なら u-blox の現在位置も送る）
- バックアップ: allowBackup="false" に加えて `data_extraction_rules.xml` / `backup_rules.xml` で `correction_secret.xml`
  （パスワードの暗号化値）と `settings.xml` を外す（Android 12+ の端末間転送は allowBackup では止まらないため）
- 署名: release は `keystore.properties` があれば使う（gps_camera と同じ形）。鍵 `release.jks`（alias `ubloxmonitor`、RSA 4096、2026-11-22 作成、
  証明書 SHA-256 `6806c3b4…802d5`）。どちらも git 管理外。**失うと上書き更新できないので PC 外にバックアップ**

## ライブラリ（`gradle/libs.versions.toml`）

AGP 8.7.3 / Kotlin 2.0.21（app・ublox-android は kotlin-android、ublox-core は kotlin-jvm）/ Compose BOM 2024.12.01 / lifecycle 2.8.7（runtime-compose・service）/
usb-serial-for-android 3.8.1（jitpack。`settings.gradle.kts` で `com.github.mik3y` だけ jitpack から。ublox-android の api 依存）/
kotlinx-coroutines-android 1.7.3（ublox-android）/ osmdroid 6.1.20（app だけ）/
play-services-maps 19.0.0・maps-compose / maps-compose-widgets 6.4.1（0.5.0、app だけ。cycle_route_logger と同じ版）/
androidx.fragment 1.8.5（play-services-maps が fragment 1.0 を連れてきて lintVitalRelease の InvalidFragmentVersionForActivityResult で止まるため明示）/
org.json:json 20240303（testImplementation だけ。更新の確認の JSON を JVM のテストで動かすため）。
Gradle Wrapper 8.11.1（gps_camera からコピー）。チャートは自前の Canvas（外部ライブラリなし）。

## 構成（モジュール）

| モジュール | 種類 | パッケージ | 役割 |
|---|---|---|---|
| `:ublox-core` | Kotlin/JVM（Android 非依存） | `jp.kjtr.ublox.core` | プロトコル・集計・記録/再生・設定・RTCM・NTRIP・表示の計算 |
| `:ublox-android` | Android ライブラリ（minSdk 24） | `jp.kjtr.ublox.android` | USB・接続（UbloxReceiver）・内蔵 GNSS・擬似ロケーション・RTK 補正 |
| `:app` | アプリ | `jp.kjtr.ubloxmonitor` | 画面・データ源の切り替え・受信機設定の状態・記録/再生の管理・フォアグラウンドサービス |

依存は app → ublox-android → ublox-core。ほかのアプリ（自転車ルートロガー・ドライブロガー）から使う前提の部品なので、
**ライブラリには app の R・BuildConfig・Prefs・サービスを持ち込まない**（必要なものは引数・インタフェースで渡す）。

```
ublox-core/src/main/kotlin/jp/kjtr/ublox/core/
├── Ubx.kt               ← ubx.py  分離（UbxStream）・デコード・build/checksum・VALGET/VALSET・NAV5・RATE・CFG-MSG・MON-SPAN・utcMillis
├── LeBytes.kt           LeReader / LeWriter（struct の代わり。内蔵 GNSS の UBX 合成にも使う）
├── GnssState.kt         ← state.py  集計（snapshot / monitorSnapshot / trendSnapshot）
├── Pipeline.kt          ← serial_link.py  Pipeline（addPvtListener・addRawListener）・UbxLogger・Link.outputPackets・ReplayClock・ReplayRunner
├── Receiver.kt          （PC 版になし）GnssFix（NAV-PVT を測位 1 回の値に）・OutputProfile（Monitor / Logger）・DynModel
├── ReceiverConfig.kt    ← settings.py の GUI 以外（受信機設定。MON-VER の衛星系から非対応の判定 supportedSystems / unsupportedItems）
├── Spectrum.kt          ← spectrum_view.py の集計（SpectrumTrace・SpectrumSet・niceTicks・BAND_MARKERS・状態の文言）
├── MonitorMath.kt       ← monitor_view.py の計算（systemOffset・bandStatus・trendLines・SLOT・MEASX_FIELDS）
├── ChartMath.kt         ← charts.py の nice_step・time_unit・pvt_series、map_view.py の thin
├── PhoneUbx.kt          （PC 版になし）内蔵 GNSS の値（PhoneFix / PhoneSat / PhoneClock / PhoneMeas）→ NAV-PVT・NAV-SAT・RXM-RAWX
├── Compare.kt           （PC 版になし）比較画面の集計（両者の値・位置の差・共通衛星の C/N0・差の履歴）
├── Rtcm.kt              ← rtcm.py  RTCM3 の分離（RtcmStream・CRC-24Q）・メッセージ名・1005/1006・ECEF⇔緯度経度・基線長・GGA
└── Ntrip.kt             ← rtcm.py  NTRIP の要求・応答ヘッダ・chunked・ソーステーブル・CorrectionClient（受信スレッド・自動再接続）・SocketLink
ublox-core/src/test/…    UbloxMonitorTest（tests/test_ublox_monitor.py の移植 + 追加）、RealSampleTest、PhoneUbxTest、RtcmTest、FakeCasterTest、
                         ReceiverTest（GnssFix・OutputProfile・リスナー）・SupportedSystemsTest（MON-VER の衛星系）、MonitorViewTest（スペクトル・u-blox モニターの計算）
ublox-core/src/testFixtures/…  UbxSamples（tests/ubx_samples.py ＋ replay）。app のテストからも使う（公開はしない）

ublox-android/src/main/java/jp/kjtr/ublox/android/
├── UbloxReceiver.kt     入口: devices・requestPermission・connect（許可→開く→MON-VER を待って OutputProfile を送る）・disconnect・
│                        status（ReceiverStatus）・fixes / latestFix（GnssFix）・rawBytes / addRawListener・send・applyProfile・startRecording。
│                        UbloxConnection（1 回の接続。Source でもある）・ConnectOptions。USB 抜け（DETACHED）もここで受ける
├── AndroidLocation.kt   GnssFix.toAndroidLocation（extras に fixType・numSV・carrSoln など。UbloxExtras）
├── Source.kt            Source インタフェース（UsbSerialSource・InternalGnssSource・UbloxConnection・アプリの ReplaySource）
├── UsbDevices.kt        機器の列挙・ドライバ判定（既定 prober → u-blox の ProbeTable → VID 0x1546 は CDC-ACM）・USB 権限（action は <パッケージ名>.UBLOX_USB_PERMISSION）
├── UsbSerialSource.kt   ← SerialSource（読み取り専用スレッド、16 KB バッファ、送信キューは 1 ループ 4 パケット）
├── InternalGnssSource.kt  内蔵 GNSS（Location / GnssStatus / GnssMeasurementsEvent を詰め替えて PhoneUbx に渡すだけ）。文言は res の ublox_internal_*
├── MockLocation.kt      擬似ロケーション（addTestProvider で GPS / NETWORK。publish は toAndroidLocation を使う）
├── CorrectionManager.kt RTK 補正（CorrectionClient の開始/停止・u-blox への転送・GGA・ソーステーブル）。保存先は CorrectionStore、サービスの起動は onStarted
├── CorrectionStore.kt   CorrectionForm・CorrectionStore・SharedPrefsCorrectionStore（corr_* のキー。アプリは settings.xml を渡す）
├── SecretStore.kt       NTRIP のパスワードの暗号化保存（Android Keystore AES/GCM、correction_secret.xml）
└── UsbCorrectionLink.kt RTK 補正の受信元の USB シリアル（u-blox とは別の機器）
ublox-android/src/main/res/  xml/device_filter.xml（アプリの Manifest が参照）、values/strings.xml（ublox_ で始める）

app/src/main/java/jp/kjtr/ubloxmonitor/
├── UbloxApp.kt              Application。osmdroid の設定（UA = applicationId、キャッシュは cacheDir）と MonitorRepository・UpdateManager
├── update/                  （0.5.0）更新の確認。UpdateCheck.kt（純ロジック: AppVersion・Releases.parse・UpdateChecker・UpdatePolicy）、UpdateManager.kt（取得・SharedPreferences `update`）
├── domain/MapMath.kt        （0.5.0）地図の直近の点・ばらつき（DRMS・最大）・距離の表示・地理院⇔Google のズームの換算
├── domain/Display.kt        ← app.py の status_text など（画面の文字列・色。グラフの計算は ublox-core の ChartMath）
├── data/
│   ├── MonitorRepository.kt データ源ごとの Pipeline（Channel）・UbloxReceiver での接続・受信機設定の読み出し/送信と ACK/NAK・
│   │                        RTCM3 入力の有効化・記録・再生・擬似ロケーション・画面用スナップショット・MON-SPAN の poll
│   ├── Prefs.kt             SharedPreferences（settings.xml。RTK 補正の入力は correctionStore）
│   └── source/              Channel（UBLOX / INTERNAL / REPLAY）・ReplaySource
├── service/MonitorService.kt   フォアグラウンドサービス（connectedDevice / location）。u-blox は repo.receiver（isConnected・latestFix）を見る
└── ui/                      MainActivity（下のナビ: 接続 / メイン / 比較 / 地図 / その他。横長 600dp 以上は NavigationRail）、
                             「その他」→ ReceiverSettingsScreen・CorrectionScreen・SpectrumScreen・MonitorScreen・LogsScreen、ConnectScreen、MainScreen、
                             CompareScreen、MapScreen、Charts、Common
app/src/test/…               DisplayTest（画面の文字列）、UpdateCheckTest（0.5.0）、MapMathTest（0.5.0）
tools/fake_caster.py         確認用の偽 NTRIP キャスター（標準ライブラリだけ。下の「ビルド・確認」）
docs/LIBRARY.md              ライブラリの構成・公開 API・ロガーでの使い方・取り込み方の 3 案
```

## 移植元との対応・意図的に変えた点

| Python 版 | Android 版 | 違い |
|---|---|---|
| 接続バー（ポート・ボーレート・受信機側・接続時に設定・出力メッセージを設定） | 接続タブ | COM ポートの代わりに USB 機器。ボーレート候補・既定 921600 は同じ（最後の値を保存） |
| 記録バー（保存先・記録開始・UBX再生・速度） | 接続タブ（記録）・記録タブ（一覧・再生） | 保存先は `getExternalFilesDir("logs")` 固定。一覧から共有（FileProvider）・削除 |
| メインタブ | メインタブ | 縦はスクロールで縦に並べる。データ源（u-blox / 内蔵 / 再生）を切り替えて表示 |
| 地図（Leaflet + OSM） | 地図タブ（osmdroid + 地理院タイル 標準/淡色/写真） | 全データ源の軌跡を色分けで重ねる（u-blox 青・内蔵 赤・再生 紫）。印の中は測位状態の色 |
| 再生中は実機に接続できない | 再生は別の Channel | 実機と再生を同時に動かせる |
| 接続後に受信機設定を読み出す（read_settings） | 同じ | MON-VER（または 2 秒）を待ってから。「接続時に設定」がオフでも読み出す（Python 版と同じ） |
| タブ（メイン / 地図 / RTK 補正 / スペクトル / 受信機設定 / u-blox モニター） | 下のナビ 5 個 + 「その他」 | 接続 / メイン / 比較 / 地図 / その他。受信機設定・RTK 補正・スペクトル・u-blox モニター・記録は「その他」から。横長は NavigationRail |
| 受信機設定タブ | 受信機設定（その他） | 項目・確認の文言・ACK/NAK の表示は同じ。VALGET に返ってこない項目は応答がそろったら（または 5 秒で）「—（非対応）」。数値は入力欄（範囲外は送信できない）。未接続・再生中は「受信機につないでいません」で操作できない |
| 受信機設定: 値の有無で対応を判断 | MON-VER の衛星系の行も見る | **PC 版にない**（0.4.0）。拡張（`GPS;GAL;BDS`・`SBAS;QZSS`・`NAVIC`）に無い衛星系とその信号は「—（非対応）」で操作不可（X20P は GLONASS のキーに 0 を返し NAK にならないため）。行が無い古いファームウェアは PC 版と同じ |
| 受信機設定の世代・保存先 | 同じ | 選んだ世代・保存先を保存して次回も使う（PC 版は毎回 自動判別 / RAM のみ）。保存先の表示名は「RAM のみ」「RAM + BBR + Flash」で、説明は下の行（360dp で切れたため） |
| スペクトルタブ | スペクトル（その他） | 項目・文言・目印・1 秒の poll・応答なしの判定（3 回）は同じ。マウスオーバーの代わりにタッチ／横ドラッグでカーソル（離しても残る）。見出しはグラフの上の行、横軸の目盛りは幅に合わせて 3〜8。データ源はメインと同じ選択（内蔵は「MON-SPAN がありません」） |
| u-blox モニタータブ | u-blox モニター（その他） | 図の座標・色・項目は同じ。横長（600dp 以上の横向き）は PC 版の 2 段の配置を縮小。縦画面は衛星系ごとの段（使っている枠だけ）を横スクロール、左端に項目名の列、トレンドは帯域のチップで 1 つずつ（データの無い帯域だけ無効。4 段の名前と上端・下端の目盛りは図の中）。縦画面は短いタブ名（信号 / SFRBX / トレンド）、MON-VER で非対応の衛星系は出さず、衛星が無い衛星系は「衛星なし」の 1 行（0.4.1）。信号・SFRBX は 0.5 秒、トレンドは 1 秒ごと（PC 版は 0.25 / 1 秒）。内蔵 GNSS は MEASX・SFRBX が無い旨を出す |
| RTK 補正: シリアルポート（無線機など） | USB シリアル | u-blox をつないでいる機器とは別の USB 機器（候補から u-blox の機器を除く）。開くたびに deviceName で探し直す |
| RTK 補正: 切れたら止まる | 自動で再接続 | **PC 版にない**。つながらない・相手が切った・30 秒データが来ない → 2→4→8…最大 60 秒で再接続（停止まで）。10 秒以上受信できていた接続が切れたら 2 秒から。401・403・404・ソーステーブルが返った（マウントポイント無し）は再接続しない |
| RTK 補正: 401 は「キャスターの応答: …401…」 | 「認証に失敗しました（ユーザー名・パスワードを確認してください）: …」 | 403・404 も文言を足した（どれも 1 行目をそのまま後ろに付ける） |
| RTK 補正: パスワードは保存しない | 既定は保存しない | ［パスワードを保存する］（既定 OFF）を ON にしたときだけ Keystore の AES/GCM で暗号化して保存。OFF にしたら消す。復号できない値は捨てる |
| RTK 補正: マウントポイントのプルダウン（ツールチップ） | 一覧のダイアログ | 名前・識別・形式・衛星系・座標・GGA の要否。現在位置（u-blox、無ければ内蔵 GNSS）があれば距離を出して近い順 |
| RTK 補正の受信 | フォアグラウンドサービスの下 | 画面を閉じても続く。通知に「RTK 補正: 受信中 x kbps / 再接続待ち」。通知の「切断」で補正も止める |
| User-Agent `NTRIP ublox_monitor/版` | `NTRIP ublox_monitor_android/版` | |
| — | 擬似ロケーション | PC 版にない。u-blox 実機の NAV-PVT だけを流す（再生・内蔵は流さない） |
| ログ欄 | ログ欄 | 行頭の時刻の後に `[u-blox]` などデータ源の名前を入れる。再生の開始（ファイル名・速度・MB）と終了も出す |
| — | 内蔵 GNSS・比較タブ | PC 版にない。内蔵 GNSS を UBX に合成して同じ集計・記録に流し、u-blox（または再生）と比べる |
| 受信メッセージ（件数順 6 種類） | 既知を先に全部 | 既知（MESSAGE_NAMES の並び）を全部、未知は後ろに件数順で合わせて 6 行まで（未知が上に来て NAV-PVT などが隠れたため） |
| グラフの縦軸（fit_y） | 値が一定に近いときは余白 | 幅が整数系列 4・それ以外 0.2 未満なら中央に寄せて広げる（使用衛星数 15 一定で 15〜16 に張り付いたため） |
| PDOP | 0 は「—」 | 内蔵の合成 NAV-PVT は PDOP 不明で 0 を入れるため |

## 決まりごと

- **ublox-core は Android に依存させない**（java.time も使わない。minSdk 24 で無いため。暦は自前の `Ubx.utcMillis`）。
  Python 版を直したら同じ箇所をここも直し、テストもそろえる。
- **ライブラリ（ublox-core / ublox-android）は他のアプリからも使う**: app の R・BuildConfig・Prefs・MonitorService を参照しない。
  公開 API を変えたら `docs/LIBRARY.md` と版（各 build.gradle.kts の `version`、今は 0.3.0）を直し、`.\gradlew.bat publishToMavenLocal` が通ることを確かめる。
  ライブラリの Manifest は権限を宣言しない（使う側が宣言する）。ライブラリの文言リソースは `ublox_` で始める。
- **u-blox の接続は UbloxReceiver に任せる**: 許可・開く・MON-VER を待って（2 秒で打ち切り）OutputProfile を送る・USB 抜けで閉じる。
  アプリは `ConnectOptions(profile = MONITOR（接続時に設定 OFF なら null）, generation = Prefs.settingsGen, pipeline = 自分の Pipeline)` で呼び、
  返った `UbloxConnection` を Channel.UBLOX の Source にする。受信機設定の読み出しは `status` が Connected になってから（出力の設定の後。Python 版と同じ順）。
  `UbloxConnection.stop()` はその接続だけを閉じる（つなぎ直した新しい接続は閉じない）。
- 出力プロファイルはどちらも RAM のみ・1 キー 1 パケット。`OutputProfile.Logger` は NAV-PVT 毎エポック・NAV-SAT 1 Hz・RAWX/SFRBX/MEASX を 0・
  CFG-RATE-MEAS/NAV・DYNMODEL（M8 は CFG-MSG・CFG-RATE・CFG-NAV5）。**companion の RATES は LOGGER より先に初期化**（逆にすると Logger の init で落ちる）。
- 集計は Channel ごとに 1 つ。新しいデータ源は `Source`（ublox-android）を実装して `MonitorRepository.startSource(channel, pipeline, source)` から入れる。
- **スペクトル・u-blox モニター（0.4.0）**: 画面が出ている間だけ LaunchedEffect で `repo.pipeline(channel)` を読む（Python 版の「表示中のタブだけ更新」）。
  MON-SPAN の poll は u-blox が選ばれていて［自動更新］のときだけ 1 秒ごと（`repo.pollSpectrum`。受信機の設定は変えない）。
  集計（SpectrumSet）は画面が持ち、Pipeline が作り直されたら消す。図の論理座標は PC 版のまま（`MonitorMath`）、縦画面は 1 論理 px = 0.85 dp。
- **内蔵 GNSS（0.2.0）**: `InternalGnssSource` は Android の値を domain のデータクラスに詰め替えるだけ。合成は `PhoneUbx`（NAV-PVT・NAV-SAT）と
  `PhoneRawx`（RXM-RAWX。locktime・clkReset の状態を持つのでデータ源ごとに 1 つ）で、`Channel.INTERNAL` の Pipeline.feed に流す。
  記録も同じバイト列（接頭辞 `internal_`）なので、再生・PC 版・RTKLIB convbin で読める。コールバックは専用の HandlerThread で受ける。
  **擬似ロケーション ON 中の GPS_PROVIDER は自分の擬似位置なので、`Location.isMock`（API 31+）/ `isFromMockProvider` の位置は捨てる**（ループ防止。
  捨てている間は「擬似ロケーション中のため内蔵の位置は使えません」。衛星・生の測定値は出す）。
  位置の権限は［内蔵 GNSS を開始］で要求し、許可されてから startSource → サービスが location 種別で前面に出る（`MonitorService.serviceTypes`）。
  API 31+ は `GnssMeasurementRequest.setFullTracking(true)`、API 24-30 は通常登録＋「GNSS 測定の完全な追跡を強制」の案内。
  生の測定値に非対応（API 31+ `GnssCapabilities.hasMeasurements`、それ以前はコールバックの status）なら RAWX なしで NAV-PVT・NAV-SAT だけ。
- **合成で推定・近似にしている値**（u-blox の実機の値とは意味が違う。比べるときの注意）:
  - NAV-PVT: hAcc = Location.accuracy（Android は 68% の半径）、vAcc = verticalAccuracyMeters（無ければ 0）、tAcc・pDOP・velD・headVeh・magDec は 0、
    fixType は高度があれば 3（無ければ 2）、flags は gnssFixOK だけ、hMSL は API 34+ の mslAltitudeMeters・無ければ**楕円体高と同じ値**、
    iTOW は Location.time（UTC）＋うるう秒（GnssClock.leapSecond、無ければ 18）、numSV は直近の GnssStatus の usedInFix の数
  - NAV-SAT: iTOW は端末の時計（System.currentTimeMillis）から、qualityInd は C/N0>0 で 4・使用中で 7、health は 0（不明）、prRes は 0、
    周波数ごとのエントリは 1 機にまとめ C/N0 は L1 帯（無ければ最大）。GLONASS の FCN 表現（93-106）・不明な衛星系は捨てる。1 Hz 程度に間引く
  - RXM-RAWX: prStdev・cpStdev・doStdev は Android の不確かさを u-blox の符号に当てはめたもの、locktime は ADR が続いた時間を自前で数える、
    GLONASS の freqId は搬送波周波数から（無ければ 0 = FCN −7 になる）、周波数が無い測定は L1 帯（GLONASS は FCN 0、BDS は B1I）とみなす、
    BDS B1C の codeType 不明は 5（B1Cp）、HALF_CYCLE_REPORTED を subHalfCyc に当てている、時計が不連続になった epoch は出さず次の epoch で clkReset
- **比較タブ**: 基準は u-blox か再生（`Prefs.compareBase`）。位置の差の履歴（`DiffHistory`、最大 30 分）は画面を開いていなくても 1 秒ごとに、
  両方の NAV-PVT が新しい（STALE_SECONDS × 2 以内）ときだけ足す。基準を変えると消す。色は地図と同じ（u-blox 青・再生 紫・内蔵 赤）。
- 地図: 地理院タイルは z18 まで（std・pale・写真とも）、表示は z22 までオーバーズーム（0.5.0 で 20 → 22）、最初に寄せるときは z17、追従はズームを変えない。
  `isTilesScaledToDpi = false`（true だと高密度の画面で引き伸ばされて文字が巨大になる）。地図の領域は clipToBounds で切る（MapView がチップの行の上にはみ出したため）。
- 擬似ロケーションは OFF・切断・抜けたときに必ず `removeTestProvider`（`MockLocation.disable`）。つないでいないときに GPS を差し替えたままにしない。
- 地図は［地図を表示］まで MapView を作らない（作るときも最初から地理院タイル。OSM の既定タイルを取りに行かない）。
  0.5.0 から表示中かどうかを `Prefs.mapShown` に保存（一度押せば［地図を閉じる］までタブの切り替え・再起動をまたいで表示のまま。通信の説明は表示前だけ）。
- **Google マップ（0.5.0）**: `MapType` に G_NORMAL / G_SATELLITE / G_HYBRID（`ui/GoogleMapPane.kt`、maps-compose）。キーは `local.properties` の `MAPS_API_KEY` を
  app/build.gradle.kts で読んで `manifestPlaceholders`（secrets-gradle-plugin は使っていない）、無い・`PLACEHOLDER…` なら `BuildConfig.HAS_MAPS_KEY = false` で Google の種類を出さない
  （公開リポジトリなので他の人はキー無しでビルドできる）。キーの制限: パッケージ名 `jp.kjtr.ubloxmonitor` ＋ release・debug の SHA-1（2026-11-23 にユーザーが登録済み）。
  地理院と Google は表示位置（`MapCamera`、MonitorRepository が 1 つ持つ。zoom は Google の単位、osmdroid は +log2(density)）を共有し、切り替えても同じ場所・縮尺。
  どちらの地図にも 軌跡・水平精度の円（hAcc を m で）・直近 120 点の点（`MapMath.RECENT_POINTS`、丸めない）・現在位置の印・縮尺（osmdroid は ScaleBarOverlay、Google は maps-compose-widgets の ScaleBar）。
  上の行に「水平精度・直近 N 点のばらつき DRMS（最大）」を cm で。Google の最初の寄せは z18。
- **更新の確認（0.5.0）**: cycle_route_logger の update/ と同じ形。取得先は GitHub API のリリース一覧（prerelease を含む・draft は除く）で、`android-v<版>` の一番新しい版を選ぶ
  （Windows 版の `v<版>` は無視）。ダウンロードは asset `ublox_monitor_android_<版>.apk`（無ければ他の .apk → リリースのページ。URL は https://github.com/ のものだけ）。
  帯はメインタブの一番上（［更新内容］で本文を広げる・［ダウンロード］・［あとで］= 次の起動まで・［この版は通知しない］= 保存。新しい版が出たらまた出す。［今すぐ確認］で取り消し）。
  設定は「その他」→「このアプリについて」の「更新の確認」（`Prefs.updateCheck`、既定 ON）。UA `ublox_monitor_android/<版>`、`Accept: application/vnd.github+json`。
  debug だけ `adb shell am start -n jp.kjtr.ubloxmonitor/.ui.MainActivity --es updateAs 0.4.4` でいまの版をその版として比べてすぐ確認。
- **2 本同時の再生（0.5.0）**: 記録の一覧の ublox_* に［内蔵と比較再生］。相手の internal_* は GPS 時刻の範囲（`UbxTimeRange`、先頭・末尾 256 KB だけ読む。
  ファイル名や更新時刻は使わない＝OneDrive などにコピーすると更新時刻が変わるため）がいちばん長く重なるものを選んでおき、手で変えられる。
  u-blox は Channel.REPLAY、内蔵は Channel.REPLAY_INTERNAL（「再生（内蔵）」、橙）に流し、比較の基準を「再生」にする（基準が再生で REPLAY_INTERNAL があれば比較の相手はそれ。
  `CompareView.side`）。比較の差の履歴の時刻は再生の時計。再生は ublox-core の `MergedReplayRunner`（1 本の再生も同じ。`ReplaySource.Companion` が 2 本目）:
  区切りはエポックの時刻（NAV-PVT iTOW / RXM-RAWX rcvTow、差 15 ms 以内は同じエポック）、時刻 = 週内秒を巻き戻りで展開 ＋ offset（RAWX の week か PVT の UTC から週。
  無ければもう 1 本の最初の時刻に近い週＝「週内秒だけで合わせています」）。待ち方は ReplayRunner と同じ（5 秒より大きい間は待たない）。
  一時停止・再開・速度の変更は再生中に（2 本とも）。シークは先へは待たずに流して追いつき、前へ（・終わった後）は最初から作り直してそこまで待たずに流す（集計・差の履歴は作り直し）。
  シークのスライダーは全体の長さ（ファイルの先頭・末尾の時刻）が分かるときだけ（「ファイルを選んで再生」の Uri は −1 分 / +1 分 / +10 分だけ）。
  実機のログ（`C:\Users\rei\OneDrive\Documents\ublox_monitor\phone_20261122\`、`UBX_PAIR_DIR` で変えられる）で `ReplayMergeTest.realPairedLogs`（無ければ skip。位置が入っているのでリポジトリに入れない）。
- 記録は上書きしない（`createNewFile` が失敗したら開始しない）。記録中のファイルは削除・共有・再生できない。
- **受信機設定（0.3.0）**: 状態は `MonitorRepository.settings`（SettingsView）。読み出しは `ReceiverConfig.readPackets`（キーは 1 つずつ VALGET）、
  ACK/NAK は `Pipeline.acks` を送信時の位置から見る（VALSET / CFG-NAV5 / CFG-RATE の応答が 1 つでも来たら判定して読み直す）。
  世代は `Prefs.settingsGen`（null = 自動判別）で、出力メッセージの設定（applySetup）にも使う（Python 版と同じ）。
- **RTK 補正（0.3.0）**: 受信は domain の `CorrectionClient`（自前のスレッド）。受信したバイト列は u-blox の `UsbSerialSource.send` に積むだけで、
  書くのは u-blox の読み取りスレッド（読み取りと書き込みが競合しない。1 ループ 4 パケットまで）。**内蔵 GNSS には転送しない**。
  GGA は u-blox の NAV-PVT から（`Rtcm.buildGga`。位置が無ければ送らずに次のループで再試行）。
  通信路は `CorrectionLink`（NTRIP / TCP は `SocketLink`、TLS は Android の既定の HostnameVerifier で確かめる。シリアルは `UsbCorrectionLink`）。
  入力は `Prefs.correctionStore`（ublox-android の SharedPrefsCorrectionStore）（変更の 0.5 秒後に保存。パスワードは入れない）、パスワードは `SecretStore`。
  受信中はサービスを保つ（`keepsService`）。種別は connectedDevice（u-blox 未接続で NTRIP だけのときは USB の許可が無く前面に出られないことがある。未確認）。

## 段階計画

1. **1 段目（0.1.0、済）**: USB 接続（u-blox 内蔵 USB / FTDI・CP210x・CH34x・PL2303）・接続時の出力設定（F9/X20 は VALSET の RAM、M8 は CFG-MSG）、
   フォアグラウンドサービス、UBX パーサと集計、メイン画面、記録・共有・削除、再生（1/10/60 倍・最大）、地図、擬似ロケーション
1.5. **1.5 段目（0.2.0、済）**: スマホ内蔵 GNSS（NAV-PVT・NAV-SAT・RXM-RAWX の合成、表示・記録 `internal_*.ubx`・再生）、比較タブ、
   記録をデータ源ごとに独立。エミュレータで見つかった修正（地図の重なり・ズーム、グラフの縦軸の余白、受信メッセージの並び、再生のログ）
2. **2 段目（0.3.0、済）**: 受信機設定（接続時の現在値の読み出し、変更の送信と ACK/NAK、保存先 RAM / BBR+Flash、世代の手動選択）、
   RTK 補正（NTRIP 1.0/2.0・TLS・ソーステーブル、TCP、USB シリアル、GGA 送信、受信機へ転送、RTCM3 入力の有効化、自動再接続、
   パスワードの暗号化保存、フォアグラウンドサービスでの受信）、下のナビを 5 個 + 「その他」に。偽 NTRIP キャスター `tools/fake_caster.py`
3. **3 段目（0.4.0、済）**: スペクトル（MON-SPAN の poll、現在値・最大値ホールド・平均、タッチで読む）、u-blox モニター（信号 RAWX/MEASX、SFRBX、5 分トレンド）。
   受信機まわりのライブラリ化（`:ublox-core` / `:ublox-android` 0.1.0、UbloxReceiver・GnssFix・OutputProfile.LOGGER、maven-publish、`docs/LIBRARY.md`）。
   受信機設定の修正（MON-VER の衛星系で非対応を判定、保存先の表示）
3.5. **0.5.0（済・実機未確認）**: Google マップ・RTK のばらつき表示、地図の表示状態の保存、更新の確認、u-blox と内蔵の 2 本同時の再生・再生の一時停止・シーク
4. **次の候補**: 自転車ルートロガー・ドライブロガーへの取り込み（取り込み方は未定。`docs/LIBRARY.md` の 3 案）

## ビルド・確認

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat test assembleDebug lintDebug     # 3 モジュールとも（ublox-core は test だけ）
.\gradlew.bat publishToMavenLocal              # ライブラリを ~/.m2 の jp.kjtr.ublox:ublox-core / ublox-android:0.3.0 に
```

- `RealSampleTest`（ublox-core）は実機（ZED-X20P、HPG 2.00、PROTVER 50.02、FTDI FT230X 経由 UART1、921600 bps）の 30 秒ログを読む
  （MEASX・SFRBX・RAWX あり、MON-SPAN・MON-VER なし。u-blox モニターの材料もここで確かめる）。
  **位置が入っているのでログはリポジトリに入れない**。環境変数 `UBX_SAMPLE` か既定のパスに無ければ skip する。
- 実機の出力は 1 Hz で約 30 種類の UBX と NMEA（約 23 KB/秒、最大フレーム 5540 バイト）。未知のクラス・NMEA は集計では読み飛ばし、記録にはそのまま残る。

### 偽 NTRIP キャスター（`tools/fake_caster.py`、Python 3 の標準ライブラリだけ）

```powershell
python tools\fake_caster.py --port 2101                 # 0.0.0.0:2101 で待ち受け。Ctrl+C で終了
python tools\fake_caster.py --port 2101 --drop-after 15 # 15 秒ごとに切る（自動再接続の確認）
```

- マウントポイント TOKYO / OSAKA / VRS（VRS は GGA の送信が必要と表に書いてある）。認証はユーザー `test` / パスワード `test`（違えば 401）
- NTRIP 1.0 は `ICY 200 OK`、要求に `Ntrip-Version: Ntrip/2.0` があれば `HTTP/1.1 200` + chunked。無いマウントポイントは 1.0 ならソーステーブル、2.0 なら 404
- 1 秒ごとに 1005（ID 1234、東京付近）と中身がダミーの 1074 / 1084 / 1094 / 1124（CRC は正しい）。受け取った GGA は標準出力に出す
- エミュレータからはホスト `10.0.2.2`、ポート 2101。JVM からの疎通: `$env:FAKE_CASTER="127.0.0.1:2101"; .\gradlew.bat :ublox-core:test --tests "*FakeCasterTest*"`
- ダミーの MSM なので受信機は RTK にならない（受信量・メッセージ番号・基準局・基線長・再接続の確認用）

## 未確認事項（実機で見ること）

- 0.5.0: Google マップ（3 種類）が表示されるか（キーの制限: パッケージ名 + SHA-1）、z21〜22 まで寄れるか、地理院⇔Google の切り替えで場所・縮尺が保たれるか、
  水平精度の円・直近の点・縮尺の見え方（RTK Fixed の cm 単位）、地図の表示状態がタブの切り替え・再起動で残るか、
  更新の帯（`updateAs 0.4.4` で android-v0.4.4 が出ない・0.4.0 で出る）・［この版は通知しない］が再起動後も効くか・OFF で通信しないか、
  2 本同時の再生（自動で選ぶ相手・一時停止・スライダー・速度の変更・比較タブの差・地図の 2 色）、1 本の再生が前と同じか

- FT230X（0403:6015）で 921600 bps を取りこぼさないか（チェックサムエラーの数で見る）。u-blox 内蔵 USB（CDC-ACM）が既定 prober で見つかるか
- USB を差したときの起動・許可ダイアログ、抜いたときに「通信が切れました」になるか
- 画面を閉じても記録が続くか（通知の「切断」で止まるか）。Android 14+ の connectedDevice 種別での startForeground
- 擬似ロケーション: 開発者オプションで選んだあと Google マップ等に出るか、標高（API 34+ の MSL）、OFF で戻るか
- 地図タイルの表示・切り替え、追従、横向き・タブレットの 2 列。z17 の文字の大きさ（isTilesScaledToDpi = false で小さすぎないか）
- 内蔵 GNSS（実機）: 許可ダイアログ（「おおよそ」だけ許可したとき）、location 種別の startForeground（Android 14+）、画面を閉じても続くか
- RAWX: 機種ごとの生の測定値の対応・API 31+ の完全な追跡、`internal_*.ubx` を PC 版の再生と RTKLIB convbin（-r ubx）で読めるか、
  疑似距離が u-blox と数 m〜数十 m で合うか（GLONASS・BDS・Galileo の時刻系の変換、週・日跨ぎ）、搬送波位相の符号・半波長、
  codeType・周波数の組み合わせ（L5/E5a/B2a、BDS B1C）が想定どおりの sigId になるか、GLONASS の freqId
- NAV-SAT のまとめ方（デュアル周波数機で C/N0 が L1 の値になるか）、擬似ロケーション ON 中に内蔵の位置が捨てられ表示が出るか
- 比較タブ: u-blox と内蔵を同時に動かしたときの位置の差・方位、共通衛星の C/N0 の棒
- 受信機設定（X20P）: 接続時に全項目が読めるか（VALGET 約 40 回）、GLONASS など非対応のキーが「—（非対応）」になるか、
  変更の送信で ACK が出て読み直されるか、BBR+Flash に保存して電源を入れ直しても残るか、衛星系の変更での再捕捉
- ライブラリ化（0.4.0）: 接続→MON-VER→出力の設定→受信機設定の読み出しの順・時間が 0.3.0 と同じか、USB を抜いたときに
  「通信が切れました: USB が外れました」になり記録・擬似ロケーションが止まるか、つないだまま［接続］し直したとき、許可ダイアログで拒否したときの文言、
  USB 権限の action 名が変わった（`jp.kjtr.ubloxmonitor.UBLOX_USB_PERMISSION`）ので許可ダイアログの結果が届くか、通知の u-blox の行（latestFix）
- 受信機設定（0.4.0、X20P）: GLONASS と GLONASS L1/L2 が「—（非対応）」で押せないか、QZSS L1C/B（NAK）が出ないままか、NavIC は出るか。
  幅 360dp の縦画面で保存先が「RAM + BBR + Flash」まで読めるか
- スペクトル（X20P）: 3 ブロック（1586.000 / 1191.938 / 1254.906 MHz、span 128 MHz、res 500 kHz、PGA 5/5/3 dB）が縦に並ぶか、1 秒ごとに更新されるか、
  タッチ・横ドラッグでカーソルが出て縦スクロールと取り合わないか、目印の文字の重なり、最大値・平均・クリア、画面を離れたら poll が止まるか
  （ログで MON-SPAN の数が増えなくなる）、MON-SPAN を含む記録の再生
- u-blox モニター（X20P）: 縦画面の横スクロールと文字の大きさ（0.85 dp/px）、横向き・タブレットの PC 版の配置、トレンドの絞り込み、内蔵 GNSS のときの表示、
  BeiDou の 24 枠を超える衛星（表示枠が無ければ出ない。PC 版と同じ）
- RTK 補正（X20P + 有料の補正サービス）: NTRIP 2.0 / 1.0 / TLS で 200 が返るか、ソーステーブルの取得、VRS で GGA を送って配信が始まるか、
  ［受信機の RTCM3 入力を有効にする］（UART1）のあと NAV-PVT が DGNSS → RTK Float → Fixed になるか、補正経過時間・基線長、
  921600 bps の UBX 受信中に RTCM を書いてもチェックサムエラーが増えないか、モバイル回線の切り替わり（Wi-Fi ⇔ LTE）で自動再接続するか、
  画面を閉じても補正が続くか（Android 14+ の connectedDevice 種別。u-blox 未接続で NTRIP だけのときの startForeground）、
  パスワードを保存したあとの再起動で復元されるか、USB シリアルの補正（無線機）を u-blox と同時につないだとき
