"""u-blox モニター表示(RAWX / MEASX の信号、SFRBX、5 分間のトレンド)。

従来の ubxMonitor.py の画面を、タブに収まる部品として描き直したもの。
"""
from PySide6.QtCore import QPointF, QRectF, Qt
from PySide6.QtGui import QColor, QFont, QPainter, QPen, QPolygonF
from PySide6.QtWidgets import (
    QComboBox, QFrame, QGridLayout, QHBoxLayout, QLabel, QScrollArea, QTabWidget,
    QVBoxLayout, QWidget)

from .state import MONITOR_SYSTEMS, STALE_SECONDS, TREND_SECONDS
from .ubx import BANDS, SIGNAL_BANDS

BACKGROUND = "#121212"
SLOT = 48
MPATH_COLORS = {0: "#7f8c8d", 1: "#2ecc71", 2: "#f1c40f", 3: "#e74c3c"}
SV_COLORS = ("#e74c3c", "#3498db", "#2ecc71", "#f1c40f", "#9b59b6", "#1abc9c", "#e67e22")
TREND_METRICS = ("C/N0", "MPath", "RMSE", "TrackStatus")
MEASX_FIELDS = ("wChips", "fChips", "cPhase", "iPhase", "rmse")


def system_offset(name):
    """同じ段で左にある衛星系の幅を足して、衛星系の描画開始位置を返す。"""
    names = list(MONITOR_SYSTEMS)
    row = MONITOR_SYSTEMS[name]["row"]
    previous = [n for n in names[:names.index(name)] if MONITOR_SYSTEMS[n]["row"] == row]
    return 20 + sum(MONITOR_SYSTEMS[n]["max"] * SLOT + 35 for n in previous)


def band_status(gnss_id, signals, now):
    """{帯域: (追尾中か, 搬送波位相が有効か)} を返す。"""
    status = {}
    for sig_id, info in signals.items():
        band = SIGNAL_BANDS.get((gnss_id, sig_id))
        if band and now - info["t"] < STALE_SECONDS:
            tracked, carrier = status.get(band, (False, False))
            status[band] = (True, carrier or info["cp"])
    return status


class ScaledCanvas(QWidget):
    """固定の論理サイズで描いた図を、ウィジェットの大きさに合わせて拡大縮小する。"""

    def __init__(self, width, height):
        super().__init__()
        self._size = (width, height)
        self.setMinimumSize(width // 3, height // 3)
        self.data = None

    def set_data(self, data):
        """描画する集計を設定して再描画する。"""
        self.data = data
        self.update()

    def paintEvent(self, _event):  # pylint: disable=invalid-name
        """背景を塗り、拡大率を設定して draw() を呼ぶ。"""
        painter = QPainter(self)
        painter.fillRect(self.rect(), QColor(BACKGROUND))
        if self.data is None:
            return
        scale = min(self.width() / self._size[0], self.height() / self._size[1])
        painter.setRenderHint(QPainter.RenderHint.TextAntialiasing)
        painter.scale(scale, scale)
        self.draw(painter)

    def draw(self, painter):
        """論理座標で図を描く。"""
        raise NotImplementedError

    @staticmethod
    def text(painter, x, y, text, color, size, bold=False, family="Arial"):
        """(x, y) を中心に文字を描く。"""
        # pylint: disable=too-many-arguments,too-many-positional-arguments
        font = QFont(family)
        font.setPixelSize(size)
        font.setBold(bold)
        painter.setFont(font)
        painter.setPen(QColor(color))
        painter.drawText(QRectF(x - 100, y - 20, 200, 40), Qt.AlignmentFlag.AlignCenter,
                         str(text))

    @staticmethod
    def left_text(painter, x, y, text, color, size):
        """(x, y) を左上にして太字の文字を描く。"""
        # pylint: disable=too-many-arguments,too-many-positional-arguments
        font = QFont("Arial")
        font.setPixelSize(size)
        font.setBold(True)
        painter.setFont(font)
        painter.setPen(QColor(color))
        painter.drawText(QRectF(x, y, 600, 30), Qt.AlignmentFlag.AlignLeft, text)


class SignalCanvas(ScaledCanvas):
    """衛星ごとの C/N0、帯域ごとの追尾状態、MEASX の値。"""

    def __init__(self):
        super().__init__(1840, 880)

    def draw(self, painter):
        now = self.data["now"]
        for name, cfg in MONITOR_SYSTEMS.items():
            offset_x = system_offset(name)
            y_base = 360 + cfg["row"] * 430
            self.left_text(painter, offset_x, y_base - 335, name, cfg["color"], 16)
            seen = self.data["seen"][name]
            for index in range(cfg["max"]):
                x_slot = offset_x + index * SLOT
                painter.setPen(QColor("#222222"))
                painter.setBrush(Qt.BrushStyle.NoBrush)
                painter.drawRect(QRectF(x_slot, y_base - 280, 44, 280))
                if index >= len(seen):
                    continue
                sv_id = seen[index]
                entry = self.data["tracks"][name].get(sv_id)
                if not entry:
                    continue
                active = now - entry.get("last_seen", 0) < STALE_SECONDS
                self.text(painter, x_slot + 22, y_base + 15, sv_id,
                          cfg["color"] if active else "#444444", 12, True, "Consolas")
                if active:
                    self._draw_satellite(painter, x_slot, y_base, cfg["id"], entry, now)

    def _draw_satellite(self, painter, x_slot, y_base, gnss_id, entry, now):
        """追尾中の 1 衛星を描く。"""
        # pylint: disable=too-many-arguments,too-many-positional-arguments
        status = band_status(gnss_id, entry["signals"], now)
        for index, band in enumerate(BANDS):
            tracked, carrier = status.get(band, (False, False))
            sig_x = x_slot + 2 + index * 11
            painter.setPen(QColor("#333333"))
            painter.setBrush(QColor("#1B5E20" if tracked else "#1a1a1a"))
            painter.drawRect(QRectF(sig_x, y_base - 300, 10, 15))
            self.text(painter, sig_x + 5, y_base - 293, band[1],
                      "#00FF00" if carrier else "#555555", 9, True)
        cno = entry.get("cno")
        if cno is None:
            cno = max((s["cno"] for s in entry["signals"].values()), default=0)
        height = cno / 63 * 240
        painter.setPen(QColor("white"))
        painter.setBrush(QColor(MPATH_COLORS.get(entry.get("mpath", 0), "#7f8c8d")))
        painter.drawRect(QRectF(x_slot + 12, y_base - height, 28, height))
        self.text(painter, x_slot + 26, y_base - height - 10, cno, "white", 11,
                  family="Consolas")
        for index, field in enumerate(MEASX_FIELDS):
            value = entry.get(field, "-")
            warn = field == "rmse" and isinstance(value, int) and value > 30
            self.text(painter, x_slot + 22, y_base + 35 + index * 12, value,
                      "#e74c3c" if warn else "#aaaaaa", 9, family="Consolas")


class SfrbxCanvas(ScaledCanvas):
    """航法メッセージ(SFRBX)の受信状況。"""

    def __init__(self):
        super().__init__(1840, 600)

    def draw(self, painter):
        for name, cfg in MONITOR_SYSTEMS.items():
            offset_x = system_offset(name)
            y_base = 250 + cfg["row"] * 280
            self.left_text(painter, offset_x, y_base - 215, f"{name} Navigation Subframes",
                           cfg["color"], 15)
            seen = self.data["seen"][name]
            for index in range(cfg["max"]):
                x_slot = offset_x + index * SLOT
                painter.setPen(QColor("#333333"))
                painter.setBrush(Qt.BrushStyle.NoBrush)
                painter.drawRect(QRectF(x_slot, y_base - 180, 44, 180))
                if index >= len(seen):
                    continue
                sv_id = seen[index]
                self.text(painter, x_slot + 22, y_base + 15, sv_id, cfg["color"], 12, True,
                          "Consolas")
                frames = self.data["sfrbx"][name].get(sv_id)
                if not frames:
                    continue
                height = min(120.0, frames["count"] % 100 * 1.2)
                painter.setPen(QColor("white"))
                painter.setBrush(QColor("#f39c12"))
                painter.drawRect(QRectF(x_slot + 15, y_base - height, 27, height))
                for number in range(1, 6):
                    box_y = y_base - 170 + (number - 1) * 25
                    received = number in frames["subframes"]
                    color = ("#27ae60" if number <= 3 else "#2980b9") if received else "#1a1a1a"
                    painter.setPen(QColor("#333333"))
                    painter.setBrush(QColor(color))
                    painter.drawRect(QRectF(x_slot + 2, box_y, 10, 20))
                    self.text(painter, x_slot + 7, box_y + 10, number,
                              "white" if received else "#444444", 9, True)


def trend_points(items, band, now, width, height):
    """履歴を 4 段(C/N0、マルチパス、RMSE、追尾状態)の折れ線の座標にする。"""
    sub = height / 4
    lines = ([], [], [], [])
    for t, cno, mpath, rmse, pr_valid, cp_valid, signal in items:
        if (SIGNAL_BANDS.get(signal) if signal is not None else "L1") != band:
            continue
        x = width - (now - t) * width / TREND_SECONDS
        if x < 0:
            continue
        lines[0].append(QPointF(x, (1 - cno / 60) * sub))
        lines[1].append(QPointF(x, sub + (1 - mpath / 3) * sub))
        lines[2].append(QPointF(x, 2 * sub + (1 - rmse / 63) * sub))
        lines[3].append(QPointF(x, 3 * sub + (0.8 - (pr_valid * 0.3 + cp_valid * 0.4)) * sub))
    return lines


class TrendPlot(QWidget):
    """1 つの衛星系・帯域のトレンド。"""

    def __init__(self, band):
        super().__init__()
        self.band = band
        self.selected = "All"
        self.setMinimumHeight(150)
        self._now = 0.0
        self._history = {}

    def set_data(self, now, history):
        """{衛星番号: 履歴} を設定して再描画する。"""
        self._now, self._history = now, history
        self.update()

    def paintEvent(self, _event):  # pylint: disable=invalid-name
        """トレンドを描く。"""
        painter = QPainter(self)
        painter.fillRect(self.rect(), QColor(BACKGROUND))
        width, height = self.width(), self.height()
        painter.setPen(QColor("#222222"))
        for index in range(1, 4):
            painter.drawLine(QPointF(0, index * height / 4), QPointF(width, index * height / 4))
        for sv_id, items in self._history.items():
            if self.selected not in ("All", str(sv_id)):
                continue
            painter.setPen(QPen(QColor(SV_COLORS[sv_id % len(SV_COLORS)]), 1))
            for line in trend_points(items, self.band, self._now, width, height):
                if len(line) >= 2:
                    painter.drawPolyline(QPolygonF(line))


class TrendPanel(QScrollArea):
    """衛星系 × 帯域のトレンドを並べた画面。"""

    def __init__(self):
        super().__init__()
        self.setWidgetResizable(True)
        self._plots = {}
        self._selectors = {}
        body = QWidget()
        body.setStyleSheet(f"background-color: {BACKGROUND}; color: #ecf0f1;")
        layout = QVBoxLayout(body)
        for name, cfg in MONITOR_SYSTEMS.items():
            if name == "OTHER":
                continue
            layout.addWidget(self._build_row(name, cfg["color"]))
        self.setWidget(body)

    def _build_row(self, name, color):
        """1 つの衛星系の段を作る。"""
        frame = QFrame()
        frame.setFrameShape(QFrame.Shape.Box)
        frame.setStyleSheet("QFrame { border: 1px solid #34495e; } QLabel { border: none; }")
        grid = QGridLayout(frame)
        grid.setSpacing(2)
        title = QLabel(name)
        title.setStyleSheet(f"color: {color}; font-weight: bold; font-size: 14px;")
        grid.addWidget(title, 0, 0)
        legend = QVBoxLayout()
        for metric in TREND_METRICS:
            legend.addWidget(QLabel(metric), 1, Qt.AlignmentFlag.AlignCenter)
        grid.addLayout(legend, 1, 0)
        for col, band in enumerate(BANDS, start=1):
            selector = QComboBox()
            selector.addItem("All")
            selector.setStyleSheet("color: black; background-color: white;")
            plot = TrendPlot(band)
            selector.currentTextChanged.connect(
                lambda text, target=plot: self._select(target, text))
            head = QHBoxLayout()
            head.addWidget(QLabel(band))
            head.addStretch(1)
            head.addWidget(selector)
            grid.addLayout(head, 0, col)
            grid.addWidget(plot, 1, col)
            grid.setColumnStretch(col, 1)
            self._plots[(name, band)] = plot
            self._selectors[(name, band)] = selector
        return frame

    @staticmethod
    def _select(plot, text):
        """表示する衛星を切り替える。"""
        plot.selected = text or "All"
        plot.update()

    def set_data(self, data):
        """履歴でグラフを描き直し、衛星の選択肢を更新する。"""
        for (name, _band), plot in self._plots.items():
            plot.set_data(data["now"], data["history"][name])
        for (name, _band), selector in self._selectors.items():
            choices = ["All"] + [str(sv) for sv in data["seen"][name]]
            if [selector.itemText(i) for i in range(selector.count())] != choices:
                current = selector.currentText()
                selector.blockSignals(True)
                selector.clear()
                selector.addItems(choices)
                selector.setCurrentText(current if current in choices else "All")
                selector.blockSignals(False)
                self._select(self._plots[(name, _band)], selector.currentText())


class MonitorPanel(QTabWidget):
    """u-blox モニターのタブ。表示中の画面だけ更新する。"""

    def __init__(self):
        super().__init__()
        self.signal_canvas = SignalCanvas()
        self.sfrbx_canvas = SfrbxCanvas()
        self.trend_panel = TrendPanel()
        self.addTab(self.signal_canvas, "信号(RAWX / MEASX)")
        self.addTab(self.sfrbx_canvas, "SFRBX モニター")
        self.addTab(self.trend_panel, "トレンド(5 分)")

    def refresh(self, state, slow_tick):
        """表示中の画面を更新する。トレンドは重いので slow_tick のときだけ描き直す。"""
        current = self.currentWidget()
        if current is self.trend_panel:
            if slow_tick:
                self.trend_panel.set_data(state.trend_snapshot())
        else:
            current.set_data(state.monitor_snapshot())
