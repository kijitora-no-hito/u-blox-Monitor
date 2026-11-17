"""u-blox Monitor の GUI。起動: python -m ublox_monitor

タブ構成: メイン(測位・衛星の一般的な表示)、地図、受信機設定、u-blox モニター。
受信機の設定を変えるのは、[出力メッセージを設定](接続時の自動投入を含む)と、
受信機設定タブの [変更を送信] だけ。
"""
# pylint: disable=no-member  # Qt のシグナルは pylint から見えない
import os
import queue
import sys

from PySide6.QtCore import QStandardPaths, Qt, QTimer
from PySide6.QtWidgets import (
    QApplication, QCheckBox, QComboBox, QFileDialog, QFormLayout, QGridLayout, QGroupBox,
    QHBoxLayout, QHeaderView, QLabel, QLineEdit, QMainWindow, QMessageBox, QPlainTextEdit,
    QProgressBar, QPushButton, QSplitter, QTableWidget, QTableWidgetItem, QTabWidget,
    QVBoxLayout, QWidget)

from . import __version__
from . import charts
from . import rtcm
from . import serial_link as link
from . import settings
from . import ubx
from .correction_view import CorrectionPanel
from .map_view import MapPanel
from .monitor_view import MonitorPanel
from .spectrum_view import SpectrumPanel

APP_NAME = "u-blox Monitor"
REFRESH_MS = 250
MAIN_TICKS = 2      # メイン画面は 0.5 秒ごとに更新する
SLOW_TICKS = 4      # 重い表示は 1 秒ごとに更新する
MAX_LOG_LINES = 2000
REPLAY_SPEEDS = (("1倍速", 1.0), ("10倍速", 10.0), ("60倍速", 60.0), ("最大", 0.0))
RECEIVER_PORTS = (("自動", None), ("USB", "USB"), ("UART1", "UART1"), ("UART2", "UART2"))
SIGNAL_COLUMNS = ("信号", "衛星数", "平均C/N0")
SATELLITE_COLUMNS = ("衛星", "方位[°]", "仰角[°]", "測位に使用", "C/N0", *ubx.BANDS)
# 測位状態の順位(ubx.FIX_LABELS の並び)ごとの色
FIX_COLORS = ("#7f8c8d", "#d35400", "#7f8c8d", "#d35400", "#2471a3", "#2471a3", "#16a085",
              "#b7950b", "#0b6e3a")
MODE_STYLE = ("color: white; background-color: %s; font-size: 22px; font-weight: bold;"
              " padding: 6px; border-radius: 4px;")
STATUS_ROWS = (("utc", "時刻(UTC)"), ("lat", "緯度"), ("lon", "経度"), ("height", "楕円体高"),
               ("msl", "標高(MSL)"), ("nrsv", "使用衛星数"), ("hacc", "水平精度"),
               ("vacc", "垂直精度"), ("pdop", "PDOP"), ("speed", "速度"), ("heading", "進行方位"),
               ("correction", "RTK 補正"),
               ("messages", "受信メッセージ"), ("errors", "チェックサムエラー"),
               ("record", "記録"))


def default_folder():
    """記録の既定の保存先を返す。exe 版はインストール先に書けないことがあるのでドキュメントにする。"""
    if getattr(sys, "frozen", False):
        documents = QStandardPaths.writableLocation(
            QStandardPaths.StandardLocation.DocumentsLocation)
        return os.path.join(documents, "ublox_monitor")
    return os.path.join(os.getcwd(), "logs")


def make_table(columns):
    """読み取り専用の表を作る。"""
    table = QTableWidget(0, len(columns))
    table.setHorizontalHeaderLabels(columns)
    table.verticalHeader().hide()
    table.setEditTriggers(QTableWidget.EditTrigger.NoEditTriggers)
    table.horizontalHeader().setSectionResizeMode(QHeaderView.ResizeMode.Stretch)
    return table


def fill_table(table, rows):
    """表の内容を rows で置き換える。"""
    table.setRowCount(len(rows))
    for row, values in enumerate(rows):
        for col, value in enumerate(values):
            item = QTableWidgetItem(str(value))
            item.setTextAlignment(Qt.AlignmentFlag.AlignCenter)
            table.setItem(row, col, item)


def format_value(value, digits=1, unit=""):
    """数値の表示文字列。値が無ければ「—」。"""
    return "—" if value is None else f"{value:.{digits}f}{unit}"


def signal_rows(snap):
    """信号別の追尾数の表の行を作る。"""
    return [(name, len(vals), f"{sum(vals) / len(vals):.1f}")
            for name, vals in sorted(snap["signals"].items())]


def satellite_rows(snap):
    """全衛星の表の行を作る。"""
    rows = []
    for name in sorted(snap["satellites"]):
        sat = snap["satellites"][name]
        rows.append((name, "—" if sat["azim"] is None else sat["azim"],
                     "—" if sat["elev"] is None else sat["elev"],
                     "○" if sat["used"] else "", sat["cno"] or "—",
                     *(sat["bands"].get(band, "—") for band in ubx.BANDS)))
    return rows


def status_text(snap):
    """測位状態の表示文字列を dict で返す。"""
    pvt = snap["pvt"]
    if pvt is None:
        return dict.fromkeys(("mode", "utc", "lat", "lon", "height", "msl", "nrsv", "hacc",
                              "vacc", "pdop", "speed", "heading"), "—")
    return {"mode": pvt.fix_label,
            "utc": pvt.utc if pvt.time_valid else f"{pvt.utc}(未確定)",
            "lat": format_value(pvt.latitude, 8, "°"),
            "lon": format_value(pvt.longitude, 8, "°"),
            "height": format_value(pvt.height, 3, " m"),
            "msl": format_value(pvt.height_msl, 3, " m"),
            "nrsv": str(pvt.num_sv),
            "hacc": format_value(pvt.hacc, 3, " m"),
            "vacc": format_value(pvt.vacc, 3, " m"),
            "pdop": f"{pvt.pdop:.2f}",
            "speed": format_value(pvt.speed, 2, " m/s"),
            "heading": format_value(pvt.heading, 1, "°")}


def message_text(counts):
    """受信メッセージ数の表示文字列を返す。"""
    if not counts:
        return "—"
    return "\n".join(f"{ubx.MESSAGE_NAMES.get(msg, f'{msg[0]:02X}-{msg[1]:02X}')}: {count}"
                     for msg, count in sorted(counts.items(), key=lambda kv: -kv[1])[:6])


class PvtPanel(QWidget):
    """測位履歴のグラフをまとめた画面。位置は平均からの差だけを表示する。"""

    def __init__(self):
        super().__init__()
        self.charts = {
            "mode": charts.ModeChart(),
            "offset": charts.LineChart("位置の変化(平均からの差)", "[m]"),
            "accuracy": charts.LineChart("推定精度", "[m]", y_floor=0),
            "nrsv": charts.LineChart("使用衛星数", "[機]", y_floor=0, integer=True),
        }
        grid = QGridLayout(self)
        for index, chart in enumerate(self.charts.values()):
            grid.addWidget(chart, index // 2, index % 2)

    def update_history(self, points, window):
        """測位履歴でグラフを描き直す。"""
        for key, data in charts.pvt_series(points, window).items():
            self.charts[key].set_data(data, window)

    def clear(self):
        """全グラフを消す。"""
        for chart in self.charts.values():
            chart.clear()


class MainWindow(QMainWindow):
    """メインウィンドウ。"""
    # pylint: disable=too-many-instance-attributes,attribute-defined-outside-init
    # pylint: disable=too-many-public-methods

    def __init__(self):
        super().__init__()
        self.setWindowTitle(f"{APP_NAME} {__version__}")
        self.resize(1400, 900)
        self.pipeline = link.Pipeline()
        self.source = None
        self.status = {}
        self._tick = 0
        self._pending_setup = False
        self._pending_read = False
        self._pending_ticks = 0
        self._awaiting_ack = False
        self._acks_seen = 0
        self.correction = None
        self._forward = True
        self._correction_logged = None
        self._build_ui()
        self.refresh_ports()
        self.timer = QTimer(self)
        self.timer.timeout.connect(self.refresh)
        self.timer.start(REFRESH_MS)
        self._update_buttons()

    # ---- 画面の組み立て ----

    def _build_ui(self):
        """画面を組み立てる。"""
        root = QWidget()
        layout = QVBoxLayout(root)
        layout.addLayout(self._build_connection_bar())
        layout.addLayout(self._build_record_bar())
        self.tabs = QTabWidget()
        self.map_panel = MapPanel()
        self.settings_panel = settings.SettingsPanel(self.read_settings, self.send_settings)
        self.monitor_panel = MonitorPanel()
        self.spectrum_panel = SpectrumPanel()
        self.correction_panel = CorrectionPanel(self.toggle_correction, self.enable_rtcm_input)
        self.tabs.addTab(self._build_main_tab(), "メイン")
        self.tabs.addTab(self.map_panel, "地図")
        self.tabs.addTab(self.correction_panel, "RTK 補正")
        self.tabs.addTab(self.spectrum_panel, "スペクトル")
        self.tabs.addTab(self.settings_panel, "受信機設定")
        self.tabs.addTab(self.monitor_panel, "u-blox モニター")
        layout.addWidget(self.tabs, 1)
        self.setCentralWidget(root)

    def _build_connection_bar(self):
        """接続の操作列を作る。"""
        row = QHBoxLayout()
        self.port_box = QComboBox()
        self.port_box.setMinimumWidth(280)
        self.refresh_button = QPushButton("更新")
        self.refresh_button.clicked.connect(self.refresh_ports)
        self.baud_box = QComboBox()
        for baud in link.BAUD_RATES:
            self.baud_box.addItem(str(baud), baud)
        self.baud_box.setCurrentIndex(link.BAUD_RATES.index(link.DEFAULT_BAUD))
        self.receiver_port_box = QComboBox()
        for label, name in RECEIVER_PORTS:
            self.receiver_port_box.addItem(label, name)
        self.receiver_port_box.setToolTip(
            "PC がつながっている受信機側のポート。出力メッセージの設定先に使う。\n"
            "自動: u-blox の USB なら USB、USB シリアル変換なら UART1 とみなす")
        self.connect_button = QPushButton("接続")
        self.connect_button.clicked.connect(self.toggle_connection)
        self.setup_button = QPushButton("出力メッセージを設定")
        self.setup_button.setToolTip("NAV-PVT / NAV-SAT / RXM-RAWX / RXM-SFRBX / RXM-MEASX の\n"
                                     "出力を有効にする(RAM のみ。電源を切ると戻る)")
        self.setup_button.clicked.connect(self.apply_setup)
        self.auto_setup_check = QCheckBox("接続時に設定")
        self.auto_setup_check.setChecked(True)
        self.auto_setup_check.setToolTip("接続したときに [出力メッセージを設定] を自動で行う")
        for widget in (QLabel("ポート:"), self.port_box, self.refresh_button, QLabel("ボーレート:"),
                       self.baud_box, QLabel("受信機側:"), self.receiver_port_box,
                       self.connect_button, self.setup_button, self.auto_setup_check):
            row.addWidget(widget)
        self.info_label = QLabel("未接続")
        row.addWidget(self.info_label, 1)
        return row

    def _build_record_bar(self):
        """記録・再生の操作列を作る。"""
        row = QHBoxLayout()
        self.folder_edit = QLineEdit(default_folder())
        folder_button = QPushButton("参照")
        folder_button.clicked.connect(self.choose_folder)
        self.record_button = QPushButton("記録開始")
        self.record_button.clicked.connect(self.toggle_record)
        self.speed_box = QComboBox()
        for label, speed in REPLAY_SPEEDS:
            self.speed_box.addItem(label, speed)
        self.replay_button = QPushButton("UBX再生")
        self.replay_button.clicked.connect(self.toggle_replay)
        self.progress = QProgressBar()
        self.progress.setRange(0, 1000)
        self.progress.setFixedWidth(160)
        self.progress.setTextVisible(False)
        for widget in (QLabel("保存先:"), self.folder_edit, folder_button, self.record_button,
                       self.speed_box, self.replay_button, self.progress):
            row.addWidget(widget)
        return row

    def _build_status_box(self):
        """測位の状態欄を作る。"""
        box = QGroupBox("測位")
        form = QFormLayout(box)
        self.mode_label = QLabel("—")
        self.mode_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.mode_label.setStyleSheet(MODE_STYLE % FIX_COLORS[0])
        form.addRow(self.mode_label)
        for key, label in STATUS_ROWS:
            self.status[key] = QLabel("—")
            self.status[key].setTextInteractionFlags(
                Qt.TextInteractionFlag.TextSelectableByMouse)
            form.addRow(label, self.status[key])
        box.setMinimumWidth(270)
        return box

    @staticmethod
    def _titled(title, widget):
        """枠付きの見出しを付ける。"""
        box = QGroupBox(title)
        layout = QVBoxLayout(box)
        layout.addWidget(widget)
        return box

    def _build_main_tab(self):
        """メインタブ(測位、スカイプロット、C/N0、履歴、ログ)を作る。"""
        self.sky_plot = charts.SkyPlot()
        self.cn0_bars = charts.Cn0Bars()
        self.signal_table = make_table(SIGNAL_COLUMNS)
        upper = QSplitter(Qt.Orientation.Horizontal)
        upper.addWidget(self._build_status_box())
        upper.addWidget(self._titled("スカイプロット(塗りつぶし: 測位に使用)", self.sky_plot))
        right = QSplitter(Qt.Orientation.Vertical)
        right.addWidget(self._titled("衛星ごとの C/N0 [dB-Hz]", self.cn0_bars))
        right.addWidget(self._titled("信号別の追尾数", self.signal_table))
        upper.addWidget(right)
        upper.setSizes([290, 380, 700])

        self.lower_tabs = QTabWidget()
        self.pvt_panel = PvtPanel()
        self.satellite_table = make_table(SATELLITE_COLUMNS)
        self.lower_tabs.addTab(self.pvt_panel, "測位履歴")
        self.lower_tabs.addTab(self.satellite_table, "全衛星")
        self.window_box = QComboBox()
        for label, seconds in charts.TIME_WINDOWS:
            self.window_box.addItem(label, seconds)
        self.window_box.setCurrentIndex(charts.DEFAULT_WINDOW)
        self.lower_tabs.setCornerWidget(self.window_box)
        self.console = QPlainTextEdit()
        self.console.setReadOnly(True)
        self.console.setMaximumBlockCount(MAX_LOG_LINES)
        lower = QSplitter(Qt.Orientation.Horizontal)
        lower.addWidget(self.lower_tabs)
        lower.addWidget(self._titled("ログ", self.console))
        lower.setSizes([950, 420])

        split = QSplitter(Qt.Orientation.Vertical)
        split.addWidget(upper)
        split.addWidget(lower)
        split.setSizes([430, 400])
        return split

    # ---- 接続・再生・記録 ----

    def _is_serial(self):
        """実機に接続中か。"""
        return isinstance(self.source, link.SerialSource) and self.source.is_alive()

    def _is_replay(self):
        """再生中か。"""
        return isinstance(self.source, link.ReplaySource) and self.source.is_alive()

    def _update_buttons(self):
        """状態に合わせてボタンを切り替える。"""
        serial_on = self._is_serial()
        replay_on = self._is_replay()
        recording = self.pipeline.log_status() is not None
        self.connect_button.setText("切断" if serial_on else "接続")
        self.connect_button.setEnabled(not replay_on)
        for widget in (self.port_box, self.baud_box, self.refresh_button):
            widget.setEnabled(not serial_on)
        self.setup_button.setEnabled(serial_on)
        self.settings_panel.read_button.setEnabled(serial_on)
        self.settings_panel.send_button.setEnabled(serial_on)
        self.replay_button.setText("再生停止" if replay_on else "UBX再生")
        self.replay_button.setEnabled(not serial_on)
        self.record_button.setText("記録停止" if recording else "記録開始")
        self.record_button.setEnabled(serial_on or recording)

    def refresh_ports(self):
        """COM ポートの一覧を更新する。"""
        self.port_box.clear()
        for device, description, receiver_port in link.available_ports():
            self.port_box.addItem(f"{device}  {description}", (device, receiver_port))

    def _start(self, pipeline, source):
        """データ源を切り替えて開始する。"""
        self._stop_source()
        self.pipeline = pipeline
        self._acks_seen = 0
        self.pvt_panel.clear()
        self.spectrum_panel.reset()
        self.source = source
        source.start()
        self._update_buttons()

    def _stop_source(self):
        """データ源と記録を止める。"""
        if self.source is not None:
            self.source.stop()
        self.source = None
        self.pipeline.stop_logging()
        self._pending_setup = self._pending_read = False
        self._update_buttons()

    def toggle_connection(self):
        """接続/切断を切り替える。"""
        if self._is_serial():
            self._stop_source()
            self.info_label.setText("切断しました")
            return
        if self.port_box.currentData() is None:
            QMessageBox.warning(self, APP_NAME, "COM ポートが見つかりません。")
            return
        pipeline = link.Pipeline()
        device = self.port_box.currentData()[0]
        self._start(pipeline, link.SerialSource(pipeline, device, self.baud_box.currentData()))
        # 世代によって設定の方法が違うので、MON-VER の応答を待ってから設定を送る
        self.source.send([ubx.build(ubx.MON_VER)])
        self._pending_setup = self.auto_setup_check.isChecked()
        self._pending_read = True
        self._pending_ticks = 0

    def toggle_replay(self):
        """UBX ログの再生を開始/停止する。"""
        if self._is_replay():
            self._stop_source()
            self.info_label.setText("再生を止めました")
            return
        path, _ = QFileDialog.getOpenFileName(self, "UBX ログを開く", self.folder_edit.text(),
                                              "UBX (*.ubx);;すべてのファイル (*.*)")
        if path:
            self.start_replay(path, self.speed_box.currentData())

    def start_replay(self, path, speed):
        """指定の UBX ログを再生する。"""
        clock = link.ReplayClock()
        pipeline = link.Pipeline(clock)
        self._start(pipeline, link.ReplaySource(pipeline, path, speed, clock))
        self.info_label.setText(f"再生中: {os.path.basename(path)}")

    def choose_folder(self):
        """保存先を選ぶ。"""
        folder = QFileDialog.getExistingDirectory(self, "保存先", self.folder_edit.text())
        if folder:
            self.folder_edit.setText(folder)

    def toggle_record(self):
        """記録を開始/停止する。"""
        if self.pipeline.log_status() is not None:
            self.pipeline.stop_logging()
        else:
            try:
                self.pipeline.start_logging(self.folder_edit.text())
            except OSError as exc:
                QMessageBox.critical(self, APP_NAME, f"記録を開始できません。\n{exc}")
        self._update_buttons()

    # ---- 受信機の設定 ----

    def _generation(self):
        """設定に使う受信機の世代を返す。"""
        detected = self.pipeline.version["gen"] if self.pipeline.version else None
        return self.settings_panel.generation(detected)

    def _receiver_port(self):
        """PC がつながっている受信機側のポート名を返す。"""
        chosen = self.receiver_port_box.currentData()
        if chosen or self.port_box.currentData() is None:
            return chosen or "USB"
        return self.port_box.currentData()[1]

    def apply_setup(self):
        """表示に使うメッセージの出力を有効にする。"""
        if not self._is_serial():
            return
        port = self._receiver_port()
        self.pipeline.log(f"出力メッセージを設定します({self._generation()}、受信機側 {port})")
        self.source.send(link.output_packets(self._generation(), port))

    def read_settings(self):
        """受信機の現在の設定を読み出す。"""
        if not self._is_serial():
            return
        self.pipeline.cfg_values.clear()
        self.pipeline.nav5 = self.pipeline.rate = None
        self.source.send(settings.read_packets(self._generation()))
        self.settings_panel.status_label.setText("現在値を読み出しています。")

    def send_settings(self, changes, layers):
        """確認のうえ設定を送り、結果を読み直す。"""
        if not self._is_serial():
            return
        gen = self._generation()
        packets = settings.write_packets(gen, changes, layers, self.pipeline.nav5,
                                         self.pipeline.rate)
        if not packets:
            self.settings_panel.status_label.setText("送信できる変更がありません。")
            return
        saved = bool(layers & ~ubx.LAYER_RAM) and settings.uses_keys(gen)
        text = ("次の設定を送信します。" +
                ("BBR と Flash にも保存します。" if saved else "RAM だけを変更し、保存はしません。") +
                "\n\n" + "\n".join(settings.change_lines(changes)))
        if settings.restarts_tracking(changes):
            text += "\n\n衛星の追尾がいったん止まり、再捕捉が始まります。"
        if QMessageBox.question(self, APP_NAME, text) != QMessageBox.StandardButton.Yes:
            return
        self.pipeline.log("設定を送信: " + "、".join(settings.change_lines(changes)))
        self._acks_seen = len(self.pipeline.acks)
        self._awaiting_ack = True
        self.settings_panel.status_label.setText("送信しました。応答を待っています。")
        self.source.send(packets)

    def _update_pending(self):
        """接続後、世代が分かったら(または 2 秒待っても分からなければ)設定の投入と読み出しをする。"""
        if not (self._pending_setup or self._pending_read) or not self._is_serial():
            return
        self._pending_ticks += 1
        if self.pipeline.version is None and self._pending_ticks < 2000 // REFRESH_MS:
            return
        if self._pending_setup:
            self.apply_setup()
        if self._pending_read:
            self.read_settings()
        self._pending_setup = self._pending_read = False

    def _update_settings(self):
        """受信機設定タブに現在値と送信結果を反映する。"""
        gen = self._generation()
        values = settings.current_values(gen, self.pipeline.cfg_values, self.pipeline.nav5,
                                         self.pipeline.rate)
        self.settings_panel.update_from(values, gen)
        if not self._awaiting_ack:
            if values and "読み出しています" in self.settings_panel.status_label.text():
                self.settings_panel.status_label.setText(f"{len(values)} 項目を読み出しました。")
            return
        replies = [ok for ok, target in self.pipeline.acks[self._acks_seen:]
                   if target in (ubx.CFG_VALSET, ubx.CFG_NAV5, ubx.CFG_RATE)]
        if not replies:
            return
        self._awaiting_ack = False
        self.read_settings()
        self.settings_panel.status_label.setText(
            "受信機が設定を受け付けました。" if all(replies)
            else "受信機が設定を拒否しました(NAK)。値の範囲や対応状況を確認してください。")

    # ---- RTK 補正 ----

    def _correction_running(self):
        """補正情報を受信中か。"""
        return self.correction is not None and self.correction.is_alive()

    def toggle_correction(self):
        """補正情報の受信を開始/停止する。"""
        panel = self.correction_panel
        if self._correction_running():
            self.correction.stop()
            self.pipeline.log("補正情報の受信を停止しました")
            panel.set_running(False)
            return
        try:
            config = panel.config()
        except ValueError as exc:
            panel.message_label.setText(str(exc))
            return
        panel.save()
        self._forward = panel.forward_check.isChecked()
        self.correction = rtcm.CorrectionSource(config, self._forward_correction, self._gga)
        self._correction_logged = None
        self.correction.start()
        target = config.get("mount") or config.get("host") or config.get("port")
        self.pipeline.log(f"補正情報の受信を開始: {target}")
        panel.message_label.setText("" if self._is_serial() else
                                    "受信機が未接続です。接続すると受信機へ転送します。")
        panel.set_running(True)

    def _forward_correction(self, data):
        """受信した補正情報を受信機へ送る(補正の受信スレッドから呼ばれる)。"""
        source = self.source
        if self._forward and isinstance(source, link.SerialSource) and source.is_alive():
            source.send([data])

    def _gga(self):
        """キャスターへ送る GGA 文を返す(補正の受信スレッドから呼ばれる)。"""
        return rtcm.build_gga(self.pipeline.state.pvt)

    def enable_rtcm_input(self):
        """接続中のポートで RTCM3 の入力を受け付けるよう、受信機を設定する(RAM のみ)。"""
        panel = self.correction_panel
        if not self._is_serial():
            panel.message_label.setText("受信機に接続してから押してください。")
            return
        gen = self._generation()
        if not settings.uses_keys(gen):
            panel.message_label.setText("M8 世代の RTCM 入力の設定には対応していません。")
            return
        port = self._receiver_port()
        item = settings.ITEM_BY_IDENT[settings.RTCM3_INPUT[port]]
        self.source.send([ubx.build_valset({item.key: 1})])
        self.pipeline.log(f"{item.label} を有効にしました(RAM)")
        panel.message_label.setText(f"{item.label} を有効にしました(RAM のみ。電源を切ると戻ります)。")

    def _update_correction(self):
        """RTK 補正のタブを更新する。"""
        panel = self.correction_panel
        running = self._correction_running()
        self._forward = panel.forward_check.isChecked()
        panel.set_running(running)
        snap = self.correction.snapshot() if self.correction else None
        pvt = self.pipeline.state.pvt
        station = snap["station"] if snap else None
        panel.update_view(snap, pvt, rtcm.baseline(station, pvt))

    def _check_correction(self):
        """補正の受信が止まったら(エラーなど)ログに出す。"""
        source = self.correction
        if source is None or source.is_alive() or self._correction_logged is source:
            return
        self._correction_logged = source
        if source.error:
            self.pipeline.log(f"補正情報の受信が止まりました: {source.error}")
            self.correction_panel.message_label.setText(f"受信が止まりました: {source.error}")

    def _correction_text(self, pvt):
        """メインタブに出す RTK 補正の状態。"""
        age = pvt.correction_age_text if pvt else "—"
        if not self._correction_running():
            return f"停止中(経過 {age})" if pvt and pvt.correction_age else "停止中"
        snap = self.correction.snapshot()
        return f"受信中 {snap['rate'] * 8 / 1000:.1f} kbps(経過 {age})"

    def _update_spectrum(self):
        """スペクトルのタブを更新する。接続中は 1 秒ごとに MON-SPAN を要求する。"""
        connected = self._is_serial()
        panel = self.spectrum_panel
        if connected and panel.poll_check.isChecked() and self._tick % SLOW_TICKS == 0:
            self.source.send([ubx.build(ubx.MON_SPAN)])
            panel.polls += 1
        panel.update_from(self.pipeline.spectrum, self.pipeline.spectrum_count, connected)

    # ---- 表示の更新 ----

    def _drain_log(self):
        """積まれたログをログ欄へ移す。"""
        try:
            while True:
                self.console.appendPlainText(self.pipeline.events.get_nowait())
        except queue.Empty:
            pass

    def _check_source(self):
        """データ源の終了や切断を検出する。"""
        source = self.source
        if source is None or source.is_alive():
            return
        if source.error:
            message = f"通信が切れました: {source.error}"
        else:
            message = "再生が終わりました"
        self.info_label.setText(message)
        self.pipeline.log(message)
        self.source = None
        self.pipeline.stop_logging()
        self._update_buttons()

    def _update_info(self):
        """接続中の受信機の情報と、再生の進み具合を表示する。"""
        if self._is_replay():
            self.progress.setValue(int(1000 * self.source.position / max(1, self.source.size)))
        if not self._is_serial():
            return
        version = self.pipeline.version
        if version is None:
            self.info_label.setText(f"{self.source.port} に接続中(受信機の応答待ち)")
        else:
            self.info_label.setText(f"{version['gen']} {version['module']}  HW {version['hw']}  "
                                    f"SW {version['sw']}  PROTVER {version['protver']}")

    def _update_status(self, snap):
        """測位の状態欄を更新する。"""
        for key, value in status_text(snap).items():
            if key == "mode":
                self.mode_label.setText(value)
            else:
                self.status[key].setText(value)
        rank = snap["pvt"].fix_rank if snap["pvt"] else 0
        self.mode_label.setStyleSheet(MODE_STYLE % FIX_COLORS[rank])
        self.status["messages"].setText(message_text(snap["counts"]))
        self.status["errors"].setText(str(self.pipeline.stream.checksum_errors))
        self.status["correction"].setText(self._correction_text(snap["pvt"]))
        log = self.pipeline.log_status()
        self.status["record"].setText(
            f"{os.path.basename(log[0])}\n{log[1] / 1e6:.1f} MB" if log else "停止中")

    def _update_main(self, snap):
        """メインタブを更新する。"""
        self._update_status(snap)
        self.sky_plot.set_satellites(snap["satellites"])
        self.cn0_bars.set_satellites(snap["satellites"])
        fill_table(self.signal_table, signal_rows(snap))
        if self.lower_tabs.currentWidget() is self.pvt_panel:
            if self._tick % SLOW_TICKS == 0:
                self.pvt_panel.update_history(snap["pvt_history"],
                                              self.window_box.currentData())
        else:
            fill_table(self.satellite_table, satellite_rows(snap))

    def refresh(self):
        """画面を更新する。表示中のタブだけ描き直す。"""
        self._tick += 1
        self._check_source()
        self._check_correction()
        self._drain_log()
        self._update_info()
        self._update_pending()
        current = self.tabs.currentWidget()
        if current is self.monitor_panel:
            self.monitor_panel.refresh(self.pipeline.state, self._tick % SLOW_TICKS == 0)
        elif current is self.settings_panel:
            self._update_settings()
        elif current is self.spectrum_panel:
            self._update_spectrum()
        elif current is self.correction_panel:
            self._update_correction()
        elif self._tick % MAIN_TICKS == 0:
            snap = self.pipeline.state.snapshot()
            if current is self.map_panel:
                rank = snap["pvt"].fix_rank if snap["pvt"] else 0
                self.map_panel.set_track(snap["pvt_history"], FIX_COLORS[rank])
            else:
                self._update_main(snap)
        self._update_buttons()

    def closeEvent(self, event):  # pylint: disable=invalid-name
        """閉じるときにスレッドと記録を止める。"""
        if self.correction is not None:
            self.correction.stop()
        self.correction_panel.save()
        self._stop_source()
        event.accept()


def main():
    """アプリケーションを起動する。"""
    app = QApplication(sys.argv)
    app.setOrganizationName("kijitora-no-hito")
    app.setApplicationName("ublox_monitor")
    window = MainWindow()
    window.show()
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
