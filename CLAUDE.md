# CLAUDE.md（ublox_monitor）

「u-blox Monitor」の Android 版。PC 用 Python アプリ（`d:\work\ublox_monitor\`、PySide6、MIT、作者本人のもの）を
Kotlin + Jetpack Compose に移植している。**挙動・表示項目・文言は Python 版を正とする**（測位状態の順位判定、NAV-SAT の known 判定、
STALE_SECONDS、帯域の振り分け、接続時の設定送信など）。共通方針は `d:\work\android\CLAUDE.md`。

## 基本情報

- アプリ名: u-blox Monitor / applicationId・namespace・パッケージ: `jp.kjtr.ubloxmonitor`（公開後は変えない）
- minSdk 24 / compileSdk・targetSdk 36（edge-to-edge 強制。Scaffold は `WindowInsets.safeDrawing`）。縦横どちらも対応（幅 600dp 以上で 2 列）
- versionCode 1 / versionName 0.1.0（1 段目。未公開・配布方法は未定）
- 権限: INTERNET・ACCESS_NETWORK_STATE（地図タイルだけ）、FOREGROUND_SERVICE(_CONNECTED_DEVICE / _LOCATION)、POST_NOTIFICATIONS、
  ACCESS_FINE/COARSE_LOCATION（内蔵 GNSS と location 種別のサービス用。1 段目では要求しない）、ACCESS_MOCK_LOCATION（擬似ロケーション）
- 通信: 地図タブで［地図を表示］を押したときだけ国土地理院のタイル。出典「出典：国土地理院」を地図上に常時表示（消さない）
- 署名: release は `keystore.properties` があれば使う（gps_camera と同じ形。鍵はまだ作っていない。作ったら `release.jks` とともに git 管理外・バックアップ）

## ライブラリ（`gradle/libs.versions.toml`）

AGP 8.7.3 / Kotlin 2.0.21 / Compose BOM 2024.12.01 / lifecycle 2.8.7（runtime-compose・service）/
usb-serial-for-android 3.8.1（jitpack。`settings.gradle.kts` で `com.github.mik3y` だけ jitpack から）/ osmdroid 6.1.20。
Gradle Wrapper 8.11.1（gps_camera からコピー）。チャートは自前の Canvas（外部ライブラリなし）。

## 構成

```
app/src/main/java/jp/kjtr/ubloxmonitor/
├── UbloxApp.kt              Application。osmdroid の設定（UA = applicationId、キャッシュは cacheDir）と MonitorRepository
├── domain/                  純ロジック（Android 依存なし・単体テストあり）
│   ├── Ubx.kt               ← ubx.py  分離（UbxStream）・デコード・build/checksum・VALGET/VALSET・NAV5・RATE・CFG-MSG・MON-SPAN
│   ├── LeBytes.kt           LeReader / LeWriter（struct の代わり。内蔵 GNSS の UBX 合成にも使う）
│   ├── GnssState.kt         ← state.py  集計（snapshot / monitorSnapshot / trendSnapshot）
│   ├── Pipeline.kt          ← serial_link.py  Pipeline・UbxLogger・outputPackets・ReplayClock・ReplayRunner（再生の時計の進め方）
│   ├── ReceiverConfig.kt    ← settings.py の GUI 以外（2 段目の受信機設定で使う）
│   └── Display.kt           ← app.py の status_text など・charts.py の pvt_series / nice_step・map_view.py の thin
├── data/
│   ├── MonitorRepository.kt データ源ごとの Pipeline（Channel）・接続・接続時の設定・記録・再生・擬似ロケーション・画面用スナップショット
│   ├── source/Source.kt     Source インタフェースと Channel（UBLOX / INTERNAL / REPLAY）
│   ├── source/UsbSerialSource.kt  ← SerialSource（読み取り専用スレッド、16 KB バッファ）
│   ├── source/ReplaySource.kt     ← ReplaySource
│   ├── UsbDevices.kt        機器の列挙・ドライバ判定（既定 prober → u-blox の ProbeTable → VID 0x1546 は CDC-ACM）・USB 権限
│   ├── MockLocation.kt      擬似ロケーション（addTestProvider で GPS / NETWORK）
│   └── Prefs.kt             SharedPreferences
├── service/MonitorService.kt   フォアグラウンドサービス（connectedDevice / location）。通知に測位状態と「切断」
└── ui/                      MainActivity（下のナビ: 接続 / メイン / 地図 / 記録）、ConnectScreen、MainScreen、MapScreen、LogsScreen、Charts、Common
app/src/test/.../domain/     UbloxMonitorTest（tests/test_ublox_monitor.py の移植 + 追加）、UbxSamples（tests/ubx_samples.py）、RealSampleTest
```

## 移植元との対応・意図的に変えた点

| Python 版 | Android 版 | 違い |
|---|---|---|
| 接続バー（ポート・ボーレート・受信機側・接続時に設定・出力メッセージを設定） | 接続タブ | COM ポートの代わりに USB 機器。ボーレート候補・既定 921600 は同じ（最後の値を保存） |
| 記録バー（保存先・記録開始・UBX再生・速度） | 接続タブ（記録）・記録タブ（一覧・再生） | 保存先は `getExternalFilesDir("logs")` 固定。一覧から共有（FileProvider）・削除 |
| メインタブ | メインタブ | 縦はスクロールで縦に並べる。データ源（u-blox / 内蔵 / 再生）を切り替えて表示 |
| 地図（Leaflet + OSM） | 地図タブ（osmdroid + 地理院タイル 標準/淡色/写真） | 全データ源の軌跡を色分けで重ねる（u-blox 青・内蔵 赤・再生 紫）。印の中は測位状態の色 |
| 再生中は実機に接続できない | 再生は別の Channel | 実機と再生を同時に動かせる |
| 接続後に受信機設定を読み出す（read_settings） | まだしない | 受信機設定タブと一緒に 2 段目で入れる |
| — | 擬似ロケーション | PC 版にない。u-blox 実機の NAV-PVT だけを流す（再生・内蔵は流さない） |
| ログ欄 | ログ欄 | 行頭の時刻の後に `[u-blox]` などデータ源の名前を入れる |

## 決まりごと

- **domain は Android に依存させない**。Python 版を直したら同じ箇所をここも直し、テストもそろえる。
- 集計は Channel ごとに 1 つ。新しいデータ源は `Source` を実装して `MonitorRepository.startSource(channel, pipeline, source)` から入れる。
- **内蔵 GNSS（次のタスク）**: GnssStatus / GnssMeasurementsEvent / Location から NAV-PVT・NAV-SAT・RXM-RAWX を合成して
  Pipeline.feed に流す（`Ubx.build`・`LeWriter` を使う）。記録も同じ `.ubx`（接頭辞 `internal_`）。
  **擬似ロケーション ON 中の GPS_PROVIDER は自分の擬似位置なので、`Location.isMock`（API 31+）/ `isFromMockProvider` の位置は捨てる**（ループ防止）。
  位置情報の権限要求と、サービスの location 種別（`MonitorService.serviceTypes`）もそのとき有効になる。
- 擬似ロケーションは OFF・切断・抜けたときに必ず `removeTestProvider`（`MockLocation.disable`）。つないでいないときに GPS を差し替えたままにしない。
- 地図は［地図を表示］まで MapView を作らない（作るときも最初から地理院タイル。OSM の既定タイルを取りに行かない）。
- 記録は上書きしない（`createNewFile` が失敗したら開始しない）。記録中のファイルは削除・共有・再生できない。

## 段階計画

1. **1 段目（0.1.0、済）**: USB 接続（u-blox 内蔵 USB / FTDI・CP210x・CH34x・PL2303）・接続時の出力設定（F9/X20 は VALSET の RAM、M8 は CFG-MSG）、
   フォアグラウンドサービス、UBX パーサと集計、メイン画面、記録・共有・削除、再生（1/10/60 倍・最大）、地図、擬似ロケーション
2. **2 段目**: 受信機設定タブ（`ReceiverConfig` の画面。接続時の現在値の読み出し、変更の送信と ACK/NAK、保存先 RAM / BBR+Flash、世代の手動選択）、
   RTK 補正タブ（NTRIP 1.0/2.0・TLS・ソーステーブル、GGA 送信、受信機へ転送、RTCM3 入力の有効化。移植元 `rtcm.py`・`correction_view.py`）、
   内蔵 GNSS（上記）
3. **3 段目**: スペクトル（MON-SPAN の poll、現在値・最大値ホールド・平均。`spectrum_view.py`）、u-blox モニター（信号 RAWX/MEASX、SFRBX、5 分トレンド。
   `monitor_view.py`。集計は `GnssState.monitorSnapshot / trendSnapshot` で用意済み）

## ビルド・確認

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat test assembleDebug lintDebug
```

- `RealSampleTest` は実機（ZED-X20P、HPG 2.00、PROTVER 50.02、FTDI FT230X 経由 UART1、921600 bps）の 30 秒ログを読む。
  **位置が入っているのでログはリポジトリに入れない**。環境変数 `UBX_SAMPLE` か既定のパスに無ければ skip する。
- 実機の出力は 1 Hz で約 30 種類の UBX と NMEA（約 23 KB/秒、最大フレーム 5540 バイト）。未知のクラス・NMEA は集計では読み飛ばし、記録にはそのまま残る。

## 未確認事項（実機で見ること）

- FT230X（0403:6015）で 921600 bps を取りこぼさないか（チェックサムエラーの数で見る）。u-blox 内蔵 USB（CDC-ACM）が既定 prober で見つかるか
- USB を差したときの起動・許可ダイアログ、抜いたときに「通信が切れました」になるか
- 画面を閉じても記録が続くか（通知の「切断」で止まるか）。Android 14+ の connectedDevice 種別での startForeground
- 擬似ロケーション: 開発者オプションで選んだあと Google マップ等に出るか、標高（API 34+ の MSL）、OFF で戻るか
- 地図タイルの表示・切り替え、追従、横向き・タブレットの 2 列
