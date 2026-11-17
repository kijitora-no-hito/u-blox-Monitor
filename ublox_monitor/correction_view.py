"""RTK 補正(RTCM)のタブ。NTRIP / TCP / シリアルから補正情報を受け、受信機へ転送する。"""
import threading

from PySide6.QtCore import QSettings, Qt
from PySide6.QtWidgets import (
    QCheckBox, QComboBox, QFormLayout, QGridLayout, QGroupBox, QHBoxLayout, QHeaderView,
    QLabel, QLineEdit, QPushButton, QSpinBox, QTableWidget, QTableWidgetItem, QVBoxLayout,
    QWidget)

from . import rtcm
from . import serial_link as link

KINDS = (("NTRIP キャスター", "ntrip"), ("TCP サーバ", "tcp"), ("シリアルポート(無線機など)", "serial"))
MESSAGE_COLUMNS = ("番号", "内容", "受信数", "最終受信 [秒前]")
SETTINGS_KEYS = ("kind", "host", "port", "tls", "version", "mount", "user", "gga", "gga_interval",
                 "serial_port", "serial_baud", "forward")


def format_bytes(size):
    """バイト数の表示文字列。"""
    if size >= 1e6:
        return f"{size / 1e6:.2f} MB"
    if size >= 1e3:
        return f"{size / 1e3:.1f} kB"
    return f"{size} B"


class CorrectionPanel(QWidget):  # pylint: disable=too-many-instance-attributes
    """RTK 補正のタブ。on_toggle() で接続/切断、on_enable_input() で受信機の RTCM3 入力を有効にする。"""

    def __init__(self, on_toggle, on_enable_input):
        super().__init__()
        self._fetch = None
        self._mounts = []
        self.kind_box = QComboBox()
        for label, kind in KINDS:
            self.kind_box.addItem(label, kind)
        self.host_edit = QLineEdit()
        self.host_edit.setPlaceholderText("例: ntrip.example.jp")
        self.port_spin = QSpinBox()
        self.port_spin.setRange(1, 65535)
        self.port_spin.setValue(2101)
        self.tls_check = QCheckBox("TLS(暗号化)")
        self.version_box = QComboBox()
        self.version_box.addItem("NTRIP 2.0", 2)
        self.version_box.addItem("NTRIP 1.0", 1)
        self.mount_box = QComboBox()
        self.mount_box.setEditable(True)
        self.mount_box.setMinimumWidth(200)
        self.mount_button = QPushButton("一覧を取得")
        self.mount_button.setToolTip("キャスターのソーステーブル(マウントポイントの一覧)を取得する")
        self.mount_button.clicked.connect(self._fetch_mounts)
        self.user_edit = QLineEdit()
        self.password_edit = QLineEdit()
        self.password_edit.setEchoMode(QLineEdit.EchoMode.Password)
        self.password_edit.setPlaceholderText("保存しません")
        self.gga_check = QCheckBox("現在位置(GGA)をキャスターへ送る")
        self.gga_check.setToolTip("VRS など、利用者の位置が必要なマウントポイントで使う。\n"
                                  "NAV-PVT の位置を送るので、位置情報がキャスターに伝わる")
        self.gga_spin = QSpinBox()
        self.gga_spin.setRange(1, 600)
        self.gga_spin.setValue(10)
        self.gga_spin.setSuffix(" 秒ごと")
        self.serial_box = QComboBox()
        self.serial_refresh = QPushButton("更新")
        self.serial_refresh.clicked.connect(self.refresh_ports)
        self.serial_baud = QComboBox()
        for baud in link.BAUD_RATES:
            self.serial_baud.addItem(str(baud), baud)
        self.serial_baud.setCurrentText("115200")
        self.forward_check = QCheckBox("受信機へ転送する")
        self.forward_check.setChecked(True)
        self.connect_button = QPushButton("補正情報の受信を開始")
        self.connect_button.clicked.connect(on_toggle)
        self.input_button = QPushButton("受信機の RTCM3 入力を有効にする")
        self.input_button.setToolTip("接続中のポートで RTCM3 を受け付けるよう設定する(RAM のみ)")
        self.input_button.clicked.connect(on_enable_input)
        self.message_label = QLabel("")
        self.message_label.setWordWrap(True)
        self.status = {}
        self.table = QTableWidget(0, len(MESSAGE_COLUMNS))
        self.table.setHorizontalHeaderLabels(MESSAGE_COLUMNS)
        self.table.verticalHeader().hide()
        self.table.setEditTriggers(QTableWidget.EditTrigger.NoEditTriggers)
        self.table.horizontalHeader().setSectionResizeMode(QHeaderView.ResizeMode.Stretch)

        layout = QHBoxLayout(self)
        left = QVBoxLayout()
        left.addWidget(self._build_source_box())
        left.addWidget(self._build_status_box(), 1)
        layout.addLayout(left, 2)
        box = QGroupBox("受信した RTCM3 メッセージ")
        QVBoxLayout(box).addWidget(self.table)
        layout.addWidget(box, 3)
        self.kind_box.currentIndexChanged.connect(self._update_fields)
        self.refresh_ports()
        self._load()
        self._update_fields()

    def _build_source_box(self):
        """受信元の設定欄を作る。"""
        box = QGroupBox("受信元")
        grid = QGridLayout(box)
        self._rows = {}
        rows = (
            ("kind", "種類", (self.kind_box,)),
            ("host", "ホスト / ポート", (self.host_edit, self.port_spin, self.tls_check)),
            ("version", "プロトコル", (self.version_box,)),
            ("mount", "マウントポイント", (self.mount_box, self.mount_button)),
            ("user", "ユーザー名", (self.user_edit,)),
            ("password", "パスワード", (self.password_edit,)),
            ("gga", "GGA", (self.gga_check, self.gga_spin)),
            ("serial", "シリアルポート", (self.serial_box, self.serial_refresh, self.serial_baud)),
        )
        for row, (key, label, widgets) in enumerate(rows):
            title = QLabel(label)
            line = QHBoxLayout()
            for widget in widgets:
                line.addWidget(widget, 1 if widget in (self.host_edit, self.mount_box,
                                                      self.serial_box) else 0)
            grid.addWidget(title, row, 0)
            grid.addLayout(line, row, 1)
            self._rows[key] = (title, widgets)
        buttons = QHBoxLayout()
        for widget in (self.connect_button, self.forward_check):
            buttons.addWidget(widget)
        buttons.addStretch(1)
        buttons.addWidget(self.input_button)
        grid.addLayout(buttons, len(rows), 0, 1, 2)
        grid.addWidget(self.message_label, len(rows) + 1, 0, 1, 2)
        grid.setColumnStretch(1, 1)
        return box

    def _build_status_box(self):
        """受信状況の欄を作る。"""
        box = QGroupBox("受信状況")
        form = QFormLayout(box)
        for key, label in (("state", "状態"), ("bytes", "受信量"), ("rate", "受信レート"),
                           ("age", "最後に受信してから"), ("crc", "CRC エラー"),
                           ("station", "基準局"), ("baseline", "基線長"),
                           ("fix", "受信機の測位状態"), ("corr_age", "受信機の補正経過時間")):
            self.status[key] = QLabel("—")
            self.status[key].setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
            form.addRow(label, self.status[key])
        return box

    def _update_fields(self):
        """受信元の種類に合わせて入力欄を出し分ける。"""
        kind = self.kind_box.currentData()
        visible = {"host": kind in ("ntrip", "tcp"), "serial": kind == "serial"}
        for key in ("version", "mount", "user", "password", "gga"):
            visible[key] = kind == "ntrip"
        for key, (title, widgets) in self._rows.items():
            show = visible.get(key, True)
            title.setVisible(show)
            for widget in widgets:
                widget.setVisible(show)
        self.tls_check.setVisible(kind == "ntrip")

    def refresh_ports(self):
        """シリアルポートの一覧を更新する。"""
        current = self.serial_box.currentData()
        self.serial_box.clear()
        for device, description, _receiver_port in link.available_ports():
            self.serial_box.addItem(f"{device}  {description}", device)
        index = self.serial_box.findData(current)
        if index >= 0:
            self.serial_box.setCurrentIndex(index)

    def config(self):
        """入力から受信元の設定を作る。足りない項目があれば ValueError。"""
        kind = self.kind_box.currentData()
        if kind == "serial":
            if self.serial_box.currentData() is None:
                raise ValueError("シリアルポートを選んでください。")
            return {"kind": kind, "port": self.serial_box.currentData(),
                    "baud": self.serial_baud.currentData()}
        host = self.host_edit.text().strip()
        if not host:
            raise ValueError("ホストを入力してください。")
        config = {"kind": kind, "host": host, "port": self.port_spin.value()}
        if kind == "ntrip":
            mount = self.mount_box.currentText().strip()
            if not mount:
                raise ValueError("マウントポイントを入力してください。")
            config.update({"mount": mount, "tls": self.tls_check.isChecked(),
                           "version": self.version_box.currentData(),
                           "user": self.user_edit.text(), "password": self.password_edit.text(),
                           "gga_interval": self.gga_spin.value() if self.gga_check.isChecked()
                           else 0})
        return config

    def set_running(self, running):
        """受信中かどうかで入力欄とボタンを切り替える。"""
        self.connect_button.setText("補正情報の受信を停止" if running else "補正情報の受信を開始")
        for key, (_title, widgets) in self._rows.items():
            for widget in widgets:
                widget.setEnabled(not running)
        if not running:
            self.mount_button.setEnabled(self._fetch is None)

    # ---- マウントポイントの一覧 ----

    def _fetch_mounts(self):
        """ソーステーブルの取得を別スレッドで始める。"""
        host = self.host_edit.text().strip()
        if not host:
            self.message_label.setText("ホストを入力してください。")
            return
        result = {}
        args = (host, self.port_spin.value(), self.user_edit.text(), self.password_edit.text(),
                self.version_box.currentData(), self.tls_check.isChecked())

        def work():
            try:
                result["mounts"] = rtcm.fetch_sourcetable(*args)
            except OSError as exc:
                result["error"] = str(exc)

        self._fetch = (threading.Thread(target=work, daemon=True), result)
        self._fetch[0].start()
        self.mount_button.setEnabled(False)
        self.message_label.setText("マウントポイントの一覧を取得しています。")

    def _check_fetch(self):
        """ソーステーブルの取得が終わっていれば一覧に反映する。"""
        if self._fetch is None or self._fetch[0].is_alive():
            return
        result = self._fetch[1]
        self._fetch = None
        self.mount_button.setEnabled(True)
        if "error" in result:
            self.message_label.setText(f"一覧を取得できません: {result['error']}")
            return
        self._mounts = result["mounts"]
        current = self.mount_box.currentText()
        self.mount_box.clear()
        for mount in self._mounts:
            self.mount_box.addItem(mount["mount"])
            tip = (f"{mount['id']}  形式 {mount['format']} {mount['details']}\n"
                   f"衛星系 {mount['nav']}  位置 {mount['lat']}, {mount['lon']}"
                   + ("\nGGA の送信が必要" if mount["nmea"] else ""))
            self.mount_box.setItemData(self.mount_box.count() - 1, tip,
                                       Qt.ItemDataRole.ToolTipRole)
        self.mount_box.setCurrentText(current)
        self.message_label.setText(f"マウントポイントを {len(self._mounts)} 件取得しました。")

    # ---- 表示 ----

    def update_view(self, snap, pvt, baseline):
        """受信状況を表示する。snap は CorrectionSource.snapshot()(停止中は None)。"""
        self._check_fetch()
        self.status["fix"].setText(pvt.fix_label if pvt else "—")
        self.status["corr_age"].setText(pvt.correction_age_text if pvt else "—")
        if snap is None:
            return
        self.status["state"].setText(snap["status"] + (f"({snap['error']})" if snap["error"]
                                                       else ""))
        self.status["bytes"].setText(format_bytes(snap["bytes"]))
        self.status["rate"].setText(f"{snap['rate'] * 8 / 1000:.1f} kbps")
        self.status["age"].setText("—" if snap["age"] is None else f"{snap['age']:.1f} 秒")
        self.status["crc"].setText(str(snap["crc_errors"]))
        station = snap["station"]
        self.status["station"].setText(
            "—" if station is None else f"ID {station['id']}  {station['lat']:.7f}°, "
            f"{station['lon']:.7f}°  {station['height']:.2f} m")
        self.status["baseline"].setText(
            "—" if baseline is None else f"{baseline / 1000:.3f} km")
        rows = sorted(snap["messages"].items())
        self.table.setRowCount(len(rows))
        for row, (msg_type, (count, age)) in enumerate(rows):
            for col, value in enumerate((msg_type, rtcm.message_name(msg_type), count,
                                         f"{age:.1f}")):
                item = QTableWidgetItem(str(value))
                item.setTextAlignment(Qt.AlignmentFlag.AlignCenter)
                self.table.setItem(row, col, item)

    # ---- 設定の保存(パスワードは保存しない) ----

    def _load(self):
        """前回の入力を読み込む。"""
        store = QSettings()
        store.beginGroup("correction")
        values = {key: store.value(key) for key in SETTINGS_KEYS}
        store.endGroup()
        if values["kind"] is not None:
            self.kind_box.setCurrentIndex(max(0, self.kind_box.findData(values["kind"])))
        self.host_edit.setText(values["host"] or "")
        if values["port"]:
            self.port_spin.setValue(int(values["port"]))
        self.tls_check.setChecked(values["tls"] in (True, "true"))
        if values["version"]:
            self.version_box.setCurrentIndex(max(0, self.version_box.findData(
                int(values["version"]))))
        self.mount_box.setCurrentText(values["mount"] or "")
        self.user_edit.setText(values["user"] or "")
        self.gga_check.setChecked(values["gga"] in (True, "true"))
        if values["gga_interval"]:
            self.gga_spin.setValue(int(values["gga_interval"]))
        if values["serial_baud"]:
            self.serial_baud.setCurrentText(str(values["serial_baud"]))
        index = self.serial_box.findData(values["serial_port"])
        if index >= 0:
            self.serial_box.setCurrentIndex(index)
        if values["forward"] is not None:
            self.forward_check.setChecked(values["forward"] in (True, "true"))

    def save(self):
        """入力を保存する(パスワード以外)。"""
        store = QSettings()
        store.beginGroup("correction")
        for key, value in (("kind", self.kind_box.currentData()), ("host", self.host_edit.text()),
                           ("port", self.port_spin.value()), ("tls", self.tls_check.isChecked()),
                           ("version", self.version_box.currentData()),
                           ("mount", self.mount_box.currentText()),
                           ("user", self.user_edit.text()), ("gga", self.gga_check.isChecked()),
                           ("gga_interval", self.gga_spin.value()),
                           ("serial_port", self.serial_box.currentData()),
                           ("serial_baud", self.serial_baud.currentData()),
                           ("forward", self.forward_check.isChecked())):
            store.setValue(key, value)
        store.endGroup()
