"""受信機設定の項目定義、設定パケットの組み立て、設定画面。

F9 / X20 は設定キー(CFG-VALGET / VALSET)、M8 は CFG-NAV5 / CFG-RATE で読み書きする。
M8 は衛星系・信号の変更に対応しない。
"""
from dataclasses import dataclass

from PySide6.QtCore import Qt
from PySide6.QtWidgets import (
    QCheckBox, QComboBox, QGridLayout, QGroupBox, QHBoxLayout, QLabel, QPushButton,
    QScrollArea, QSpinBox, QVBoxLayout, QWidget)

from . import ubx

CHECK_COLUMNS = 5
GENERATIONS = (("自動判別", None), ("F9 / X20", "F9"), ("M8", "M8"))
LAYERS = (("RAM のみ(電源を切ると戻る)", ubx.LAYER_RAM),
          ("RAM + BBR + Flash(保存する)", ubx.LAYER_RAM | ubx.LAYER_BBR | ubx.LAYER_FLASH))


@dataclass(frozen=True)
class Item:  # pylint: disable=too-many-instance-attributes
    """設定項目。legacy は M8 での (メッセージ, フィールド)。"""
    ident: str
    label: str
    key: int
    choices: tuple = ()
    minimum: int = 0
    maximum: int = 1
    unit: str = ""
    signed: bool = False
    legacy: tuple | None = None


NAV_ITEMS = (
    Item("dynmodel", "ダイナミクスモデル", 0x20110021, legacy=("nav5", "dynmodel"), choices=(
        (0, "Portable"), (2, "Stationary"), (3, "Pedestrian"), (4, "Automotive"), (5, "Sea"),
        (6, "Airborne <1g"), (7, "Airborne <2g"), (8, "Airborne <4g"), (9, "Wrist"),
        (10, "Bike"), (11, "Lawn mower"), (12, "E-scooter"))),
    Item("fixmode", "測位モード", 0x20110011, legacy=("nav5", "fixmode"),
         choices=((1, "2D のみ"), (2, "3D のみ"), (3, "自動 2D/3D"))),
    Item("dgnssmode", "RTK モード", 0x20140011,
         choices=((2, "RTK Float まで"), (3, "RTK Fixed"))),
    Item("minelev", "仰角マスク", 0x201100A4, minimum=-90, maximum=90, unit="°",
         signed=True, legacy=("nav5", "minelev")),
    Item("mincno", "最小 C/N0", 0x201100A3, maximum=63, unit="dB-Hz",
         legacy=("nav5", "mincno")),
    Item("minsvs", "最小衛星数", 0x201100A1, maximum=32, unit="機",
         legacy=("nav5", "minsvs")),
    Item("measrate", "測定周期", 0x30210001, minimum=25, maximum=65535, unit="ms",
         legacy=("rate", "meas")),
    Item("navrate", "測位の間引き(測定 N 回に 1 回)", 0x30210002, minimum=1, maximum=127,
         unit="回", legacy=("rate", "nav")),
)
SYSTEM_ITEMS = (
    Item("GPS", "GPS", 0x1031001F), Item("SBAS", "SBAS", 0x10310020),
    Item("GAL", "Galileo", 0x10310021), Item("BDS", "BeiDou", 0x10310022),
    Item("QZSS", "QZSS", 0x10310024), Item("GLO", "GLONASS", 0x10310025),
    Item("NAVIC", "NavIC", 0x10310026),
)
SIGNAL_ITEMS = (
    Item("GPS_L1CA", "GPS L1C/A", 0x10310001), Item("GPS_L2C", "GPS L2C", 0x10310003),
    Item("GPS_L5", "GPS L5", 0x10310004), Item("SBAS_L1CA", "SBAS L1C/A", 0x10310005),
    Item("GAL_E1", "Galileo E1", 0x10310007), Item("GAL_E5A", "Galileo E5a", 0x10310009),
    Item("GAL_E5B", "Galileo E5b", 0x1031000A), Item("GAL_E6", "Galileo E6", 0x1031000B),
    Item("BDS_B1", "BeiDou B1I", 0x1031000D), Item("BDS_B2", "BeiDou B2I", 0x1031000E),
    Item("BDS_B1C", "BeiDou B1C", 0x1031000F), Item("BDS_B2A", "BeiDou B2a", 0x10310028),
    Item("BDS_B3", "BeiDou B3I", 0x10310010), Item("QZSS_L1CA", "QZSS L1C/A", 0x10310012),
    Item("QZSS_L1S", "QZSS L1S", 0x10310014), Item("QZSS_L2C", "QZSS L2C", 0x10310015),
    Item("QZSS_L5", "QZSS L5", 0x10310017), Item("QZSS_L1CB", "QZSS L1C/B", 0x10310039),
    Item("GLO_L1", "GLONASS L1", 0x10310018), Item("GLO_L2", "GLONASS L2", 0x1031001A),
    Item("NAVIC_L5", "NavIC L5", 0x1031001D),
)
ITEMS = NAV_ITEMS + SYSTEM_ITEMS + SIGNAL_ITEMS
ITEM_BY_IDENT = {item.ident: item for item in ITEMS}
_TRACKING_IDENTS = {item.ident for item in SYSTEM_ITEMS + SIGNAL_ITEMS}


def uses_keys(gen):
    """設定キーで読み書きする世代か。"""
    return gen != "M8"


def read_packets(gen):
    """現在の設定を読み出すパケットを返す。

    設定キーは 1 つずつ問い合わせる(未対応のキーが混ざると要求全体が NAK になるため)。
    """
    packets = []
    if gen in ("F9", "X20", "Unknown"):
        packets += [ubx.build_valget([item.key]) for item in ITEMS]
    if gen in ("M8", "Unknown"):
        packets += [ubx.build(ubx.CFG_NAV5), ubx.build(ubx.CFG_RATE)]
    return packets


def current_values(gen, cfg_values, nav5, rate):
    """受信機から読んだ内容を {項目 ID: 整数値} にする。読めていない項目は含めない。"""
    values = {}
    if uses_keys(gen):
        for item in ITEMS:
            raw = cfg_values.get(item.key)
            if raw is not None:
                values[item.ident] = int.from_bytes(raw, "little", signed=item.signed)
        return values
    sources = {"nav5": ubx.parse_nav5(nav5) if nav5 else None, "rate": rate}
    for item in NAV_ITEMS:
        if item.legacy and sources[item.legacy[0]]:
            values[item.ident] = sources[item.legacy[0]][item.legacy[1]]
    return values


def changed(current, chosen):
    """画面の入力 chosen のうち、現在値 current と違うものだけを返す。"""
    return {ident: value for ident, value in chosen.items()
            if ident in current and current[ident] != value}


def write_packets(gen, changes, layers, nav5, rate):
    """changes({項目 ID: 値})を書き込むパケットを返す。"""
    if not changes:
        return []
    if uses_keys(gen):
        values = {ITEM_BY_IDENT[ident].key: value for ident, value in changes.items()}
        signed = {item.key for item in ITEMS if item.signed}
        return [ubx.build_valset(values, layers, signed)]
    packets = []
    nav5_changes = {ITEM_BY_IDENT[i].legacy[1]: v for i, v in changes.items()
                    if (ITEM_BY_IDENT[i].legacy or ("",))[0] == "nav5"}
    if nav5_changes and nav5:
        packets.append(ubx.build_nav5(nav5, nav5_changes))
    if rate and ("measrate" in changes or "navrate" in changes):
        packets.append(ubx.build_rate(changes.get("measrate", rate["meas"]),
                                      changes.get("navrate", rate["nav"]), rate["timeref"]))
    return packets


def describe(item, value):
    """値の表示文字列を返す。"""
    if value is None:
        return "—"
    if item.choices:
        return dict(item.choices).get(value, f"値 {value}")
    if item in NAV_ITEMS:
        return f"{value} {item.unit}".strip()
    return "有効" if value else "無効"


def change_lines(changes):
    """確認画面に出す変更内容の行を返す。"""
    return [f"{ITEM_BY_IDENT[ident].label}: {describe(ITEM_BY_IDENT[ident], value)}"
            for ident, value in changes.items()]


def restarts_tracking(changes):
    """衛星系・信号の変更を含むか(GNSS 部が再起動して再捕捉になる)。"""
    return any(ident in _TRACKING_IDENTS for ident in changes)


class SettingsPanel(QWidget):  # pylint: disable=too-many-instance-attributes
    """受信機設定の画面。on_read() と on_send(変更, レイヤー) を呼び出す。"""

    def __init__(self, on_read, on_send):
        super().__init__()
        self._on_send = on_send
        self._values = {}
        self._shown = None
        self._labels = {}
        self._inputs = {}
        self._checks = {}

        self.read_button = QPushButton("現在値を読み込む")
        self.read_button.clicked.connect(on_read)
        self.send_button = QPushButton("変更を送信")
        self.send_button.clicked.connect(self._send)
        self.gen_box = QComboBox()
        for label, gen in GENERATIONS:
            self.gen_box.addItem(label, gen)
        self.gen_box.setToolTip("受信機の世代。自動判別は MON-VER の応答で決める")
        self.layer_box = QComboBox()
        for label, layers in LAYERS:
            self.layer_box.addItem(label, layers)
        self.status_label = QLabel("接続すると現在値を読み出します。")
        self.status_label.setWordWrap(True)
        top = QHBoxLayout()
        for widget in (self.read_button, self.send_button, QLabel("世代:"), self.gen_box,
                       QLabel("保存先:"), self.layer_box):
            top.addWidget(widget)
        top.addWidget(self.status_label, 1)

        body = QWidget()
        body_layout = QVBoxLayout(body)
        body_layout.addWidget(self._build_nav())
        self.system_group, self._system_grid = self._build_checks("衛星系", SYSTEM_ITEMS)
        self.signal_group, self._signal_grid = self._build_checks(
            "信号(受信機が対応するものだけ表示)", SIGNAL_ITEMS)
        body_layout.addWidget(self.system_group)
        body_layout.addWidget(self.signal_group)
        body_layout.addStretch(1)
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setWidget(body)
        layout = QVBoxLayout(self)
        layout.addLayout(top)
        layout.addWidget(scroll)

    def _build_nav(self):
        """測位関係の項目の欄を作る。"""
        group = QGroupBox("測位と追尾")
        grid = QGridLayout(group)
        for col, title in enumerate(("項目", "現在値", "変更後")):
            grid.addWidget(QLabel(title), 0, col)
        for row, item in enumerate(NAV_ITEMS, start=1):
            grid.addWidget(QLabel(item.label), row, 0)
            self._labels[item.ident] = QLabel("—")
            grid.addWidget(self._labels[item.ident], row, 1)
            if item.choices:
                widget = QComboBox()
                for value, label in item.choices:
                    widget.addItem(label, value)
            else:
                widget = QSpinBox()
                widget.setRange(item.minimum, item.maximum)
                widget.setSuffix(f" {item.unit}")
            widget.setEnabled(False)
            self._inputs[item.ident] = widget
            grid.addWidget(widget, row, 2)
        grid.setColumnMinimumWidth(1, 160)
        grid.setColumnStretch(2, 1)
        return group

    def _build_checks(self, title, items):
        """チェックボックスの欄を作る。"""
        group = QGroupBox(title)
        grid = QGridLayout(group)
        for item in items:
            box = QCheckBox(item.label)
            box.setEnabled(False)
            self._checks[item.ident] = box
        self._place(grid, [self._checks[item.ident] for item in items])
        return group, grid

    @staticmethod
    def _place(grid, boxes):
        """チェックボックスを左上から詰めて並べる。"""
        for index, box in enumerate(boxes):
            grid.addWidget(box, index // CHECK_COLUMNS, index % CHECK_COLUMNS,
                           Qt.AlignmentFlag.AlignLeft)

    def generation(self, detected):
        """使う世代を返す。手動で選んでいればそれを、なければ判別結果を使う。"""
        return self.gen_box.currentData() or detected or "Unknown"

    def update_from(self, values, gen):
        """受信機から読んだ値を画面に反映する。値が変わったときだけ入力を置き換える。"""
        if self._shown == (values, gen):
            return
        self._shown = (dict(values), gen)
        self._values = dict(values)
        for item in NAV_ITEMS:
            value = values.get(item.ident)
            widget = self._inputs[item.ident]
            self._labels[item.ident].setText(describe(item, value))
            widget.setEnabled(value is not None)
            if value is None:
                continue
            if item.choices:
                if widget.findData(value) < 0:
                    widget.addItem(describe(item, value), value)
                widget.setCurrentIndex(widget.findData(value))
            else:
                widget.setValue(value)
        keyed = uses_keys(gen)
        self.system_group.setVisible(keyed)
        self.signal_group.setVisible(keyed)
        for items, grid, hide_unknown in ((SYSTEM_ITEMS, self._system_grid, False),
                                          (SIGNAL_ITEMS, self._signal_grid, True)):
            visible = []
            for item in items:
                box = self._checks[item.ident]
                known = item.ident in values
                box.setEnabled(known)
                box.setChecked(bool(values.get(item.ident)))
                box.setVisible(known or not hide_unknown)
                if known or not hide_unknown:
                    visible.append(box)
            self._place(grid, visible)

    def chosen(self):
        """画面の入力を {項目 ID: 値} で返す。"""
        result = {}
        for item in NAV_ITEMS:
            widget = self._inputs[item.ident]
            result[item.ident] = widget.currentData() if item.choices else widget.value()
        for ident, box in self._checks.items():
            result[ident] = int(box.isChecked())
        return result

    def _send(self):
        """変更があれば送信を依頼する。"""
        changes = changed(self._values, self.chosen())
        if changes:
            self._on_send(changes, self.layer_box.currentData())
        else:
            self.status_label.setText("変更はありません。")
