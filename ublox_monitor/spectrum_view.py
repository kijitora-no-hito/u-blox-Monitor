"""スペクトル表示(UBX-MON-SPAN)。

受信機の RF ブロックごとのスペクトルを表示する。表示中は 1 秒ごとに MON-SPAN を要求する
(Poll なので受信機の設定は変えない)。MON-SPAN に対応するのは F9 世代の比較的新しい
ファームウェアなどで、対応していない受信機は応答しない。
"""
from PySide6.QtCore import QPointF, QRectF, Qt
from PySide6.QtGui import QColor, QFont, QPainter, QPen, QPolygonF
from PySide6.QtWidgets import (
    QCheckBox, QHBoxLayout, QLabel, QPushButton, QVBoxLayout, QWidget)

from . import ubx

NO_REPLY_POLLS = 3
# 表示する信号の中心周波数 [MHz]
BAND_MARKERS = (
    ("GPS/QZSS L1, Galileo E1", 1575.42), ("BeiDou B1I", 1561.098), ("GLONASS G1", 1602.0),
    ("GPS/QZSS L2", 1227.60), ("GLONASS G2", 1246.0), ("GPS/QZSS L5, Galileo E5a", 1176.45),
    ("Galileo E5b, BeiDou B2I", 1207.14), ("BeiDou B3I", 1268.52),
    ("Galileo E6, QZSS L6", 1278.75),
)
COLORS = {"current": "#1f77b4", "max": "#d35400", "mean": "#7f8c8d", "marker": "#27ae60"}
MARGIN_LEFT, MARGIN_RIGHT, MARGIN_TOP, MARGIN_BOTTOM = 52, 12, 24, 34


def nice_ticks(low, high, count=5):
    """low〜high に収まる、切りのよい目盛りの値を返す。"""
    span = max(high - low, 1e-9)
    raw = span / count
    power = 10 ** len(str(int(raw))) / 10 if raw >= 1 else 1.0
    while power > raw:
        power /= 10
    step = next(m * power for m in (1, 2, 5, 10) if m * power >= raw)
    first = int(low / step) * step
    if first < low:
        first += step
    ticks = []
    value = first
    while value <= high + 1e-9:
        ticks.append(round(value, 6))
        value += step
    return ticks


class SpectrumTrace:
    """1 つの RF ブロックの、現在値・最大値・平均値の集計。"""

    def __init__(self):
        self.current = None
        self.maximum = None
        self.total = None
        self.count = 0
        self.block = None

    def add(self, block):
        """新しいスペクトルを加える。周波数の設定が変わったら集計をやり直す。"""
        same = self.block is not None and all(
            self.block[key] == block[key] for key in ("center", "span"))
        values = block["spectrum"]
        if not same:
            self.maximum, self.total, self.count = list(values), [0.0] * len(values), 0
        self.block = block
        self.current = values
        self.maximum = [max(a, b) for a, b in zip(self.maximum, values)]
        self.total = [a + b for a, b in zip(self.total, values)]
        self.count += 1

    @property
    def mean(self):
        """平均値。"""
        return [v / self.count for v in self.total] if self.count else None


class SpectrumPlot(QWidget):
    """RF ブロックごとのスペクトルを縦に並べて描く。マウスの位置の周波数とレベルを表示する。"""

    def __init__(self):
        super().__init__()
        self.setMinimumSize(400, 260)
        self.setMouseTracking(True)
        self.traces = []
        self.show_max = True
        self.show_mean = False
        self._mouse = None

    def set_traces(self, traces):
        """表示する集計を設定して再描画する。"""
        self.traces = traces
        self.update()

    def mouseMoveEvent(self, event):  # pylint: disable=invalid-name
        """マウスの位置を覚えて再描画する。"""
        self._mouse = event.position()
        self.update()

    def leaveEvent(self, _event):  # pylint: disable=invalid-name
        """マウスが外れたら表示を消す。"""
        self._mouse = None
        self.update()

    def _areas(self):
        """ブロックごとの描画範囲を返す。"""
        count = len(self.traces)
        height = self.height() / count
        return [QRectF(MARGIN_LEFT, index * height + MARGIN_TOP,
                       self.width() - MARGIN_LEFT - MARGIN_RIGHT,
                       height - MARGIN_TOP - MARGIN_BOTTOM) for index in range(count)]

    def paintEvent(self, _event):  # pylint: disable=invalid-name
        """スペクトルを描く。"""
        painter = QPainter(self)
        painter.setRenderHint(QPainter.RenderHint.Antialiasing)
        painter.fillRect(self.rect(), self.palette().base())
        text = self.palette().text().color()
        if not self.traces:
            painter.setPen(text)
            painter.drawText(self.rect(), Qt.AlignmentFlag.AlignCenter,
                             "スペクトルのデータがありません")
            return
        painter.setFont(QFont(self.font().family(), 8))
        for trace, area in zip(self.traces, self._areas()):
            if area.height() > 20 and area.width() > 20:
                self._draw_block(painter, trace, area, text)

    def _draw_block(self, painter, trace, area, text):
        """1 つの RF ブロックを描く。"""
        block = trace.block
        freqs = ubx.span_frequencies(block)
        f_low, f_high = freqs[0], freqs[-1]
        series = [trace.current]
        if self.show_max:
            series.append(trace.maximum)
        if self.show_mean:
            series.append(trace.mean)
        values = [v for s in series if s for v in s]
        y_low = min(values) - 2
        y_high = max(values) + 2

        def point(freq, level):
            return QPointF(area.left() + (freq - f_low) / (f_high - f_low) * area.width(),
                           area.bottom() - (level - y_low) / (y_high - y_low) * area.height())

        # 目盛りと枠
        grid = QPen(QColor("#d0d0d0"))
        for level in nice_ticks(y_low, y_high, 4):
            y = point(f_low, level).y()
            painter.setPen(grid)
            painter.drawLine(QPointF(area.left(), y), QPointF(area.right(), y))
            painter.setPen(text)
            painter.drawText(QRectF(0, y - 8, MARGIN_LEFT - 6, 16),
                             Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignVCenter,
                             f"{level:g}")
        for freq in nice_ticks(f_low, f_high, 8):
            x = point(freq, y_low).x()
            painter.setPen(grid)
            painter.drawLine(QPointF(x, area.top()), QPointF(x, area.bottom()))
            painter.setPen(text)
            painter.drawText(QRectF(x - 40, area.bottom() + 2, 80, 14),
                             Qt.AlignmentFlag.AlignCenter, f"{freq:g}")
        painter.drawText(QRectF(area.left(), area.bottom() + 16, area.width(), 14),
                         Qt.AlignmentFlag.AlignCenter, "周波数 [MHz]")
        painter.setPen(QPen(text))
        painter.setBrush(Qt.BrushStyle.NoBrush)
        painter.drawRect(area)
        title = (f"中心 {block['center'] / 1e6:.3f} MHz  幅 {block['span'] / 1e6:g} MHz  "
                 f"分解能 {block['res'] / 1e3:g} kHz  PGA {block['pga']} dB    縦軸: レベル [dB]")
        painter.drawText(QRectF(area.left(), area.top() - 20, area.width(), 18),
                         Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter, title)
        # 信号の中心周波数
        for name, freq in BAND_MARKERS:
            if f_low <= freq <= f_high:
                x = point(freq, y_low).x()
                pen = QPen(QColor(COLORS["marker"]), 1, Qt.PenStyle.DashLine)
                painter.setPen(pen)
                painter.drawLine(QPointF(x, area.top()), QPointF(x, area.bottom()))
                painter.save()
                painter.translate(x, area.top() + 4)
                painter.rotate(90)
                # 回転後の -y が画面の右。線に重ならないよう少し右に置く
                painter.drawText(QRectF(0, -24, area.height(), 14),
                                 Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter, name)
                painter.restore()
        # スペクトル
        for key, data in (("mean", trace.mean if self.show_mean else None),
                          ("max", trace.maximum if self.show_max else None),
                          ("current", trace.current)):
            if data:
                painter.setPen(QPen(QColor(COLORS[key]), 1.6 if key == "current" else 1.0))
                painter.drawPolyline(QPolygonF([point(f, v) for f, v in zip(freqs, data)]))
        self._draw_cursor(painter, trace, area, freqs, text)

    def _draw_cursor(self, painter, trace, area, freqs, text):
        """マウスの位置の周波数とレベルを描く。"""
        # pylint: disable=too-many-arguments,too-many-positional-arguments
        if self._mouse is None or not area.contains(self._mouse):
            return
        ratio = (self._mouse.x() - area.left()) / area.width()
        index = min(len(freqs) - 1, max(0, round(ratio * (len(freqs) - 1))))
        x = area.left() + index / (len(freqs) - 1) * area.width()
        painter.setPen(QPen(text, 1, Qt.PenStyle.DotLine))
        painter.drawLine(QPointF(x, area.top()), QPointF(x, area.bottom()))
        label = f"{freqs[index]:.3f} MHz  {trace.current[index]:.2f} dB"
        if self.show_max:
            label += f"(最大 {trace.maximum[index]:.2f})"
        width = painter.fontMetrics().horizontalAdvance(label) + 10
        left = x + 6 if x + 6 + width < area.right() else x - 6 - width
        box = QRectF(left, area.top() + 4, width, 18)
        painter.fillRect(box, self.palette().base())
        painter.setPen(text)
        painter.drawRect(box)
        painter.drawText(box, Qt.AlignmentFlag.AlignCenter, label)


class SpectrumPanel(QWidget):
    """スペクトルのタブ。"""

    def __init__(self):
        super().__init__()
        self._traces = []
        self._seen = 0
        self.polls = 0
        self.poll_check = QCheckBox("自動更新(1 秒ごとに MON-SPAN を要求)")
        self.poll_check.setChecked(True)
        self.poll_check.setToolTip("受信機に MON-SPAN を問い合わせる(Poll)。受信機の設定は変えない")
        self.max_check = QCheckBox("最大値ホールド")
        self.max_check.setChecked(True)
        self.mean_check = QCheckBox("平均")
        self.clear_button = QPushButton("クリア")
        self.clear_button.clicked.connect(self.clear)
        self.status_label = QLabel("")
        self.status_label.setWordWrap(True)
        self.plot = SpectrumPlot()
        self.max_check.toggled.connect(self._update_options)
        self.mean_check.toggled.connect(self._update_options)
        bar = QHBoxLayout()
        for widget in (self.poll_check, self.max_check, self.mean_check, self.clear_button):
            bar.addWidget(widget)
        legend = QLabel(f"<span style='color:{COLORS['current']}'>■ 現在値</span>　"
                        f"<span style='color:{COLORS['max']}'>■ 最大値</span>　"
                        f"<span style='color:{COLORS['mean']}'>■ 平均</span>　"
                        f"<span style='color:{COLORS['marker']}'>┊ 信号の中心周波数</span>")
        bar.addWidget(legend)
        bar.addWidget(self.status_label, 1)
        layout = QVBoxLayout(self)
        layout.addLayout(bar)
        layout.addWidget(self.plot, 1)

    def _update_options(self):
        """表示する線を切り替える。"""
        self.plot.show_max = self.max_check.isChecked()
        self.plot.show_mean = self.mean_check.isChecked()
        self.plot.update()

    def clear(self):
        """集計を消す。"""
        self._traces = []
        self.polls = 0
        self.plot.set_traces([])

    def reset(self):
        """データ源が変わったときに、集計と受信数を消す。"""
        self.clear()
        self._seen = 0
        self.status_label.setText("")

    def update_from(self, blocks, count, connected):
        """受信した MON-SPAN を反映する。count は受信数。"""
        if blocks and count != self._seen:
            self._seen = count
            if len(self._traces) != len(blocks):
                self._traces = [SpectrumTrace() for _ in blocks]
            for trace, block in zip(self._traces, blocks):
                trace.add(block)
            self.plot.set_traces(self._traces)
        if count:
            self.status_label.setText(f"MON-SPAN 受信 {count} 回")
        elif connected and self.polls >= NO_REPLY_POLLS:
            self.status_label.setText(
                "MON-SPAN の応答がありません。この受信機・ファームウェアはスペクトル表示に"
                "対応していない可能性があります(F9 世代の比較的新しいファームウェアが必要です)。")
        elif connected:
            self.status_label.setText("MON-SPAN を要求しています。")
        else:
            self.status_label.setText("接続すると、受信機にスペクトルを要求します。"
                                      "記録済みのログに MON-SPAN があれば再生時にも表示します。")
