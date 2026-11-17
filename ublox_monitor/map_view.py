"""OpenStreetMap のタイル地図に現在位置と軌跡を表示する。

MapPanel の [地図を表示] を押した時点で初めて地図サーバへ接続する。
表示する範囲のタイルを取得するので、位置の概略が地図サーバに伝わる。
"""
import json

from PySide6.QtCore import Qt, QUrl
from PySide6.QtWidgets import (
    QCheckBox, QHBoxLayout, QLabel, QPushButton, QVBoxLayout, QWidget)

# OSM のタイルサーバは Referer の無い要求を拒否するので、基底 URL を与える
BASE_URL = "http://localhost/ublox_monitor/"
MAX_TRACK_POINTS = 2000
NOTICE = ("地図を表示すると、Leaflet(unpkg.com)と地図タイル(tile.openstreetmap.org)を\n"
          "インターネットから取得します。表示している場所の概略が地図サーバに伝わります。")
PAGE = """<!doctype html>
<html><head><meta charset="utf-8">
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<style>html, body, #map { height: 100%; margin: 0; }</style>
</head><body><div id="map"></div>
<script>
var map = L.map('map').setView([36, 138], 5);
L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
  maxZoom: 19, attribution: '&copy; OpenStreetMap contributors'
}).addTo(map);
L.control.scale({imperial: false}).addTo(map);
var track = L.polyline([], {color: '#2980b9', weight: 3}).addTo(map);
var marker = null;
var follow = true;
function setFollow(value) { follow = value; }
function clearTrack() {
  track.setLatLngs([]);
  if (marker !== null) { map.removeLayer(marker); marker = null; }
}
function updatePosition(points, color) {
  if (!points.length) { clearTrack(); return; }
  var last = points[points.length - 1];
  track.setLatLngs(points);
  if (marker === null) {
    marker = L.circleMarker(last, {radius: 7, color: color, fillOpacity: 0.9}).addTo(map);
    map.setView(last, 17);
  } else {
    marker.setLatLng(last);
    marker.setStyle({color: color});
    if (follow && !map.getBounds().contains(last)) { map.panTo(last); }
  }
}
</script></body></html>
"""


def thin(fixes):
    """[(緯度, 経度)] を MAX_TRACK_POINTS 点以下に間引き、末尾は残す。"""
    if not fixes:
        return []
    step = max(1, len(fixes) // MAX_TRACK_POINTS)
    sampled = fixes[::step]
    if sampled[-1] != fixes[-1]:
        sampled.append(fixes[-1])
    return [[round(lat, 7), round(lon, 7)] for lat, lon in sampled]


def track_points(points):
    """測位履歴 [(時刻, Pvt)] を [緯度, 経度] のリストにする。多すぎるときは間引く。"""
    return thin([(p.latitude, p.longitude) for _t, p in points if p.latitude is not None])


class MapPanel(QWidget):
    """地図のタブ。[地図を表示] を押すまで地図のビューを作らない。"""

    def __init__(self):
        super().__init__()
        self._view = None
        self._ready = False
        self._last = None
        self.show_button = QPushButton("地図を表示(OpenStreetMap に接続)")
        self.show_button.setCheckable(True)
        self.show_button.toggled.connect(self.set_map_visible)
        self.follow_check = QCheckBox("現在位置に追従")
        self.follow_check.setChecked(True)
        self.follow_check.toggled.connect(self._set_follow)
        self.position_label = QLabel("")
        bar = QHBoxLayout()
        bar.addWidget(self.show_button)
        bar.addWidget(self.follow_check)
        bar.addWidget(self.position_label, 1)
        self.notice = QLabel(NOTICE)
        self.notice.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self._layout = QVBoxLayout(self)
        self._layout.addLayout(bar)
        self._layout.addWidget(self.notice, 1)

    def set_map_visible(self, visible):
        """地図を表示/破棄する。破棄すると地図サーバへの接続も止まる。"""
        if visible and self._view is None:
            # QtWebEngine は読み込みが重いので、地図を使うときだけ読み込む
            # pylint: disable-next=import-outside-toplevel
            from PySide6.QtWebEngineWidgets import QWebEngineView
            self._view = QWebEngineView()
            self._view.loadFinished.connect(self._on_loaded)  # pylint: disable=no-member
            self._view.setHtml(PAGE, QUrl(BASE_URL))
            self.notice.hide()
            self._layout.addWidget(self._view, 1)
        elif not visible and self._view is not None:
            self._layout.removeWidget(self._view)
            self._view.deleteLater()
            self._view = None
            self._ready = False
            self._last = None
            self.notice.show()
        self.show_button.setText("地図を閉じる" if visible else "地図を表示(OpenStreetMap に接続)")

    def _on_loaded(self, success):
        """ページの読み込み完了を記録する。"""
        self._ready = success
        self._set_follow(self.follow_check.isChecked())

    def _run(self, script):
        """地図のページで JavaScript を実行する。"""
        if self._ready and self._view is not None:
            self._view.page().runJavaScript(script)

    def _set_follow(self, enabled):
        """現在位置への追従を切り替える。"""
        self._run(f"setFollow({json.dumps(bool(enabled))});")

    def set_track(self, points, color="#c0392b"):
        """測位履歴を地図へ反映する。color は現在位置の印の色。"""
        fixes = track_points(points)
        if fixes:
            self.position_label.setText(f"現在位置: {fixes[-1][0]:.7f}°, {fixes[-1][1]:.7f}°"
                                        f"(軌跡 {len(fixes)} 点)")
        else:
            self.position_label.setText("")
        state = (len(points), fixes[-1] if fixes else None, color)
        if self._ready and state != self._last:
            self._last = state
            self._run(f"updatePosition({json.dumps(fixes)}, {json.dumps(color)});")
