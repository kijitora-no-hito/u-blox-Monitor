# CLAUDE.md（ublox_monitor）

「u-blox Monitor」の Android 版。PC 用 Python アプリ（`d:\work\ublox_monitor\`、PySide6、MIT、作者本人のもの）を
Kotlin + Jetpack Compose に移植している。**挙動・表示項目・文言は Python 版を正とする**（測位状態の順位判定、NAV-SAT の known 判定、
STALE_SECONDS、帯域の振り分け、接続時の設定送信など）。共通方針は `d:\work\android\CLAUDE.md`。

## 基本情報

- アプリ名: u-blox Monitor / applicationId・namespace・パッケージ: `jp.kjtr.ubloxmonitor`（公開後は変えない）
- minSdk 24 / compileSdk・targetSdk 36（edge-to-edge 強制。Scaffold は `WindowInsets.safeDrawing`）。縦横どちらも対応（幅 600dp 以上で 2 列）
- versionCode 2 / versionName 0.2.0（1.5 段目: 内蔵 GNSS・比較。未公開・配布方法は未定）
- 権限: INTERNET・ACCESS_NETWORK_STATE（地図タイルだけ）、FOREGROUND_SERVICE(_CONNECTED_DEVICE / _LOCATION)、POST_NOTIFICATIONS、
  ACCESS_FINE/COARSE_LOCATION（内蔵 GNSS と location 種別のサービス用。接続タブで［内蔵 GNSS を開始］を押したときに実行時に要求）、
  ACCESS_MOCK_LOCATION（擬似ロケーション）
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
│   ├── PhoneUbx.kt          （PC 版になし）内蔵 GNSS の値（PhoneFix / PhoneSat / PhoneClock / PhoneMeas）→ NAV-PVT・NAV-SAT・RXM-RAWX
│   ├── Compare.kt           （PC 版になし）比較画面の集計（両者の値・位置の差・共通衛星の C/N0・差の履歴）
│   ├── ReceiverConfig.kt    ← settings.py の GUI 以外（2 段目の受信機設定で使う）
│   └── Display.kt           ← app.py の status_text など・charts.py の pvt_series / nice_step・map_view.py の thin
├── data/
│   ├── MonitorRepository.kt データ源ごとの Pipeline（Channel）・接続・接続時の設定・記録・再生・擬似ロケーション・画面用スナップショット
│   ├── source/Source.kt     Source インタフェースと Channel（UBLOX / INTERNAL / REPLAY）
│   ├── source/UsbSerialSource.kt  ← SerialSource（読み取り専用スレッド、16 KB バッファ）
│   ├── source/ReplaySource.kt     ← ReplaySource
│   ├── source/InternalGnssSource.kt  内蔵 GNSS（Location / GnssStatus / GnssMeasurementsEvent を詰め替えて PhoneUbx に渡すだけ）
│   ├── UsbDevices.kt        機器の列挙・ドライバ判定（既定 prober → u-blox の ProbeTable → VID 0x1546 は CDC-ACM）・USB 権限
│   ├── MockLocation.kt      擬似ロケーション（addTestProvider で GPS / NETWORK）
│   └── Prefs.kt             SharedPreferences
├── service/MonitorService.kt   フォアグラウンドサービス（connectedDevice / location）。通知に測位状態と「切断」
└── ui/                      MainActivity（下のナビ: 接続 / メイン / 比較 / 地図 / 記録）、ConnectScreen、MainScreen、CompareScreen、MapScreen、LogsScreen、Charts、Common
app/src/test/.../domain/     UbloxMonitorTest（tests/test_ublox_monitor.py の移植 + 追加）、UbxSamples（tests/ubx_samples.py）、RealSampleTest、
                             PhoneUbxTest（合成した UBX を Ubx.parse* と Pipeline に通す往復テスト・比較・表示の修正）
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
| ログ欄 | ログ欄 | 行頭の時刻の後に `[u-blox]` などデータ源の名前を入れる。再生の開始（ファイル名・速度・MB）と終了も出す |
| — | 内蔵 GNSS・比較タブ | PC 版にない。内蔵 GNSS を UBX に合成して同じ集計・記録に流し、u-blox（または再生）と比べる |
| 受信メッセージ（件数順 6 種類） | 既知を先に全部 | 既知（MESSAGE_NAMES の並び）を全部、未知は後ろに件数順で合わせて 6 行まで（未知が上に来て NAV-PVT などが隠れたため） |
| グラフの縦軸（fit_y） | 値が一定に近いときは余白 | 幅が整数系列 4・それ以外 0.2 未満なら中央に寄せて広げる（使用衛星数 15 一定で 15〜16 に張り付いたため） |
| PDOP | 0 は「—」 | 内蔵の合成 NAV-PVT は PDOP 不明で 0 を入れるため |

## 決まりごと

- **domain は Android に依存させない**。Python 版を直したら同じ箇所をここも直し、テストもそろえる。
- 集計は Channel ごとに 1 つ。新しいデータ源は `Source` を実装して `MonitorRepository.startSource(channel, pipeline, source)` から入れる。
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
- 地図: 地理院タイルは z18 まで（std・pale・写真とも）、表示は z20 までオーバーズーム、最初に寄せるときは z17、追従はズームを変えない。
  `isTilesScaledToDpi = false`（true だと高密度の画面で引き伸ばされて文字が巨大になる）。地図の領域は clipToBounds で切る（MapView がチップの行の上にはみ出したため）。
- 擬似ロケーションは OFF・切断・抜けたときに必ず `removeTestProvider`（`MockLocation.disable`）。つないでいないときに GPS を差し替えたままにしない。
- 地図は［地図を表示］まで MapView を作らない（作るときも最初から地理院タイル。OSM の既定タイルを取りに行かない）。
- 記録は上書きしない（`createNewFile` が失敗したら開始しない）。記録中のファイルは削除・共有・再生できない。

## 段階計画

1. **1 段目（0.1.0、済）**: USB 接続（u-blox 内蔵 USB / FTDI・CP210x・CH34x・PL2303）・接続時の出力設定（F9/X20 は VALSET の RAM、M8 は CFG-MSG）、
   フォアグラウンドサービス、UBX パーサと集計、メイン画面、記録・共有・削除、再生（1/10/60 倍・最大）、地図、擬似ロケーション
1.5. **1.5 段目（0.2.0、済）**: スマホ内蔵 GNSS（NAV-PVT・NAV-SAT・RXM-RAWX の合成、表示・記録 `internal_*.ubx`・再生）、比較タブ、
   記録をデータ源ごとに独立。エミュレータで見つかった修正（地図の重なり・ズーム、グラフの縦軸の余白、受信メッセージの並び、再生のログ）
2. **2 段目**: 受信機設定タブ（`ReceiverConfig` の画面。接続時の現在値の読み出し、変更の送信と ACK/NAK、保存先 RAM / BBR+Flash、世代の手動選択）、
   RTK 補正タブ（NTRIP 1.0/2.0・TLS・ソーステーブル、GGA 送信、受信機へ転送、RTCM3 入力の有効化。移植元 `rtcm.py`・`correction_view.py`）
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
- 地図タイルの表示・切り替え、追従、横向き・タブレットの 2 列。z17 の文字の大きさ（isTilesScaledToDpi = false で小さすぎないか）
- 内蔵 GNSS（実機）: 許可ダイアログ（「おおよそ」だけ許可したとき）、location 種別の startForeground（Android 14+）、画面を閉じても続くか
- RAWX: 機種ごとの生の測定値の対応・API 31+ の完全な追跡、`internal_*.ubx` を PC 版の再生と RTKLIB convbin（-r ubx）で読めるか、
  疑似距離が u-blox と数 m〜数十 m で合うか（GLONASS・BDS・Galileo の時刻系の変換、週・日跨ぎ）、搬送波位相の符号・半波長、
  codeType・周波数の組み合わせ（L5/E5a/B2a、BDS B1C）が想定どおりの sigId になるか、GLONASS の freqId
- NAV-SAT のまとめ方（デュアル周波数機で C/N0 が L1 の値になるか）、擬似ロケーション ON 中に内蔵の位置が捨てられ表示が出るか
- 比較タブ: u-blox と内蔵を同時に動かしたときの位置の差・方位、共通衛星の C/N0 の棒
