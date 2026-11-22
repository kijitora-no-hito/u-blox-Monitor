# CLAUDE.md（ublox_monitor）

「u-blox Monitor」の Android 版。PC 用 Python アプリ（`d:\work\ublox_monitor\`、PySide6、MIT、作者本人のもの）を
Kotlin + Jetpack Compose に移植している。**挙動・表示項目・文言は Python 版を正とする**（測位状態の順位判定、NAV-SAT の known 判定、
STALE_SECONDS、帯域の振り分け、接続時の設定送信など）。共通方針は `d:\work\android\CLAUDE.md`。

## 基本情報

- アプリ名: u-blox Monitor / applicationId・namespace・パッケージ: `jp.kjtr.ubloxmonitor`（公開後は変えない）
- minSdk 24 / compileSdk・targetSdk 36（edge-to-edge 強制。Scaffold は `WindowInsets.safeDrawing`）。縦横どちらも対応（幅 600dp 以上で 2 列）
- versionCode 3 / versionName 0.3.0（2 段目: 受信機設定・RTK 補正。未公開・配布方法は未定）
- 権限: INTERNET・ACCESS_NETWORK_STATE（地図タイルだけ）、FOREGROUND_SERVICE(_CONNECTED_DEVICE / _LOCATION)、POST_NOTIFICATIONS、
  ACCESS_FINE/COARSE_LOCATION（内蔵 GNSS と location 種別のサービス用。接続タブで［内蔵 GNSS を開始］を押したときに実行時に要求）、
  ACCESS_MOCK_LOCATION（擬似ロケーション）
- 通信: 地図タブで［地図を表示］を押したときだけ国土地理院のタイル。出典「出典：国土地理院」を地図上に常時表示（消さない）。
  RTK 補正タブで［補正情報の受信を開始］・［一覧を取得］を押したときだけ、利用者が入れた NTRIP キャスター / TCP サーバ
  （GGA を送る設定なら u-blox の現在位置も送る）
- バックアップ: allowBackup="false" に加えて `data_extraction_rules.xml` / `backup_rules.xml` で `correction_secret.xml`
  （パスワードの暗号化値）と `settings.xml` を外す（Android 12+ の端末間転送は allowBackup では止まらないため）
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
│   ├── ReceiverConfig.kt    ← settings.py の GUI 以外（受信機設定タブ）
│   ├── Rtcm.kt              ← rtcm.py  RTCM3 の分離（RtcmStream・CRC-24Q）・メッセージ名・1005/1006・ECEF⇔緯度経度・基線長・GGA
│   ├── Ntrip.kt             ← rtcm.py  NTRIP の要求・応答ヘッダ（ICY / HTTP・401/404）・chunked・ソーステーブル・
│   │                          CorrectionClient（受信スレッド・自動再接続）・CorrectionLink / SocketLink（java.net、TLS）
│   └── Display.kt           ← app.py の status_text など・charts.py の pvt_series / nice_step・map_view.py の thin
├── data/
│   ├── MonitorRepository.kt データ源ごとの Pipeline（Channel）・接続・接続時の設定と受信機設定の読み出し・受信機設定の送信と ACK/NAK・
│   │                        RTCM3 入力の有効化・記録・再生・擬似ロケーション・画面用スナップショット
│   ├── CorrectionManager.kt RTK 補正（CorrectionClient の開始/停止・u-blox への転送・GGA・ソーステーブル・入力の保存）
│   ├── SecretStore.kt       NTRIP のパスワードの暗号化保存（Android Keystore AES/GCM、correction_secret.xml）
│   ├── source/Source.kt     Source インタフェースと Channel（UBLOX / INTERNAL / REPLAY）
│   ├── source/UsbSerialSource.kt  ← SerialSource（読み取り専用スレッド、16 KB バッファ）
│   ├── source/ReplaySource.kt     ← ReplaySource
│   ├── source/UsbCorrectionLink.kt  RTK 補正の受信元の USB シリアル（u-blox とは別の機器。Python 版のシリアルポート）
│   ├── source/InternalGnssSource.kt  内蔵 GNSS（Location / GnssStatus / GnssMeasurementsEvent を詰め替えて PhoneUbx に渡すだけ）
│   ├── UsbDevices.kt        機器の列挙・ドライバ判定（既定 prober → u-blox の ProbeTable → VID 0x1546 は CDC-ACM）・USB 権限
│   ├── MockLocation.kt      擬似ロケーション（addTestProvider で GPS / NETWORK）
│   └── Prefs.kt             SharedPreferences
├── service/MonitorService.kt   フォアグラウンドサービス（connectedDevice / location）。通知に測位状態・補正の受信状態と「切断」
└── ui/                      MainActivity（下のナビ: 接続 / メイン / 比較 / 地図 / その他。横長 600dp 以上は NavigationRail）、
                             「その他」→ ReceiverSettingsScreen・CorrectionScreen・LogsScreen、ConnectScreen、MainScreen、CompareScreen、
                             MapScreen、Charts、Common
tools/fake_caster.py         確認用の偽 NTRIP キャスター（標準ライブラリだけ。下の「ビルド・確認」）
app/src/test/.../domain/     UbloxMonitorTest（tests/test_ublox_monitor.py の移植 + 追加）、UbxSamples（tests/ubx_samples.py）、RealSampleTest、
                             PhoneUbxTest（合成した UBX を Ubx.parse* と Pipeline に通す往復テスト・比較・表示の修正）、
                             RtcmTest（tests/test_rtcm.py の移植。偽キャスターはバイト列の ScriptLink。再接続・401/404・chunked の追加）、
                             FakeCasterTest（tools/fake_caster.py との実ソケットの疎通。環境変数 FAKE_CASTER が無ければ skip）
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
| タブ（メイン / 地図 / RTK 補正 / スペクトル / 受信機設定 / u-blox モニター） | 下のナビ 5 個 + 「その他」 | 接続 / メイン / 比較 / 地図 / その他。受信機設定・RTK 補正・記録は「その他」から（3 段目のスペクトル・u-blox モニターも「その他」に足す）。横長は NavigationRail |
| 受信機設定タブ | 受信機設定（その他） | 項目・確認の文言・ACK/NAK の表示は同じ。VALGET に返ってこない項目は応答がそろったら（または 5 秒で）「—（非対応）」。数値は入力欄（範囲外は送信できない）。未接続・再生中は「受信機につないでいません」で操作できない |
| 受信機設定の世代・保存先 | 同じ | 選んだ世代・保存先を保存して次回も使う（PC 版は毎回 自動判別 / RAM のみ） |
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
- **受信機設定（0.3.0）**: 状態は `MonitorRepository.settings`（SettingsView）。読み出しは `ReceiverConfig.readPackets`（キーは 1 つずつ VALGET）、
  ACK/NAK は `Pipeline.acks` を送信時の位置から見る（VALSET / CFG-NAV5 / CFG-RATE の応答が 1 つでも来たら判定して読み直す）。
  世代は `Prefs.settingsGen`（null = 自動判別）で、出力メッセージの設定（applySetup）にも使う（Python 版と同じ）。
- **RTK 補正（0.3.0）**: 受信は domain の `CorrectionClient`（自前のスレッド）。受信したバイト列は u-blox の `UsbSerialSource.send` に積むだけで、
  書くのは u-blox の読み取りスレッド（読み取りと書き込みが競合しない。1 ループ 4 パケットまで）。**内蔵 GNSS には転送しない**。
  GGA は u-blox の NAV-PVT から（`Rtcm.buildGga`。位置が無ければ送らずに次のループで再試行）。
  通信路は `CorrectionLink`（NTRIP / TCP は `SocketLink`、TLS は Android の既定の HostnameVerifier で確かめる。シリアルは `UsbCorrectionLink`）。
  入力は `Prefs.loadCorrectionForm / saveCorrectionForm`（変更の 0.5 秒後に保存。パスワードは入れない）、パスワードは `SecretStore`。
  受信中はサービスを保つ（`keepsService`）。種別は connectedDevice（u-blox 未接続で NTRIP だけのときは USB の許可が無く前面に出られないことがある。未確認）。

## 段階計画

1. **1 段目（0.1.0、済）**: USB 接続（u-blox 内蔵 USB / FTDI・CP210x・CH34x・PL2303）・接続時の出力設定（F9/X20 は VALSET の RAM、M8 は CFG-MSG）、
   フォアグラウンドサービス、UBX パーサと集計、メイン画面、記録・共有・削除、再生（1/10/60 倍・最大）、地図、擬似ロケーション
1.5. **1.5 段目（0.2.0、済）**: スマホ内蔵 GNSS（NAV-PVT・NAV-SAT・RXM-RAWX の合成、表示・記録 `internal_*.ubx`・再生）、比較タブ、
   記録をデータ源ごとに独立。エミュレータで見つかった修正（地図の重なり・ズーム、グラフの縦軸の余白、受信メッセージの並び、再生のログ）
2. **2 段目（0.3.0、済）**: 受信機設定（接続時の現在値の読み出し、変更の送信と ACK/NAK、保存先 RAM / BBR+Flash、世代の手動選択）、
   RTK 補正（NTRIP 1.0/2.0・TLS・ソーステーブル、TCP、USB シリアル、GGA 送信、受信機へ転送、RTCM3 入力の有効化、自動再接続、
   パスワードの暗号化保存、フォアグラウンドサービスでの受信）、下のナビを 5 個 + 「その他」に。偽 NTRIP キャスター `tools/fake_caster.py`
3. **3 段目**（「その他」に足す）: スペクトル（MON-SPAN の poll、現在値・最大値ホールド・平均。`spectrum_view.py`）、u-blox モニター（信号 RAWX/MEASX、SFRBX、5 分トレンド。
   `monitor_view.py`。集計は `GnssState.monitorSnapshot / trendSnapshot` で用意済み）

## ビルド・確認

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat test assembleDebug lintDebug
```

- `RealSampleTest` は実機（ZED-X20P、HPG 2.00、PROTVER 50.02、FTDI FT230X 経由 UART1、921600 bps）の 30 秒ログを読む。
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
- エミュレータからはホスト `10.0.2.2`、ポート 2101。JVM からの疎通: `$env:FAKE_CASTER="127.0.0.1:2101"; .\gradlew.bat testDebugUnitTest --tests "*FakeCasterTest*"`
- ダミーの MSM なので受信機は RTK にならない（受信量・メッセージ番号・基準局・基線長・再接続の確認用）

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
- 受信機設定（X20P）: 接続時に全項目が読めるか（VALGET 約 40 回）、GLONASS など非対応のキーが「—（非対応）」になるか、
  変更の送信で ACK が出て読み直されるか、BBR+Flash に保存して電源を入れ直しても残るか、衛星系の変更での再捕捉
- RTK 補正（X20P + 有料の補正サービス）: NTRIP 2.0 / 1.0 / TLS で 200 が返るか、ソーステーブルの取得、VRS で GGA を送って配信が始まるか、
  ［受信機の RTCM3 入力を有効にする］（UART1）のあと NAV-PVT が DGNSS → RTK Float → Fixed になるか、補正経過時間・基線長、
  921600 bps の UBX 受信中に RTCM を書いてもチェックサムエラーが増えないか、モバイル回線の切り替わり（Wi-Fi ⇔ LTE）で自動再接続するか、
  画面を閉じても補正が続くか（Android 14+ の connectedDevice 種別。u-blox 未接続で NTRIP だけのときの startForeground）、
  パスワードを保存したあとの再起動で復元されるか、USB シリアルの補正（無線機）を u-blox と同時につないだとき
