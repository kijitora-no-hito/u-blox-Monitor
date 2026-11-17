"""受信したメッセージの集計。受信スレッドが更新し、GUI がスナップショットを読む。"""
import threading
import time
from collections import deque

from . import ubx

STALE_SECONDS = 2.5
TREND_SECONDS = 300
MAX_PVT_HISTORY = 36000

# u-blox モニター表示の衛星系の並び。max は表示する衛星数の上限
MONITOR_SYSTEMS = {
    "GPS":     {"row": 0, "max": 12, "id": 0, "color": "#2ecc71"},
    "GLONASS": {"row": 0, "max": 12, "id": 6, "color": "#3498db"},
    "GALILEO": {"row": 0, "max": 12, "id": 2, "color": "#9b59b6"},
    "BEIDOU":  {"row": 1, "max": 24, "id": 3, "color": "#f1c40f"},
    "QZSS":    {"row": 1, "max": 6,  "id": 5, "color": "#e67e22"},
    "OTHER":   {"row": 1, "max": 6,  "id": -1, "color": "#95a5a6"},
}
_GROUPS = {cfg["id"]: name for name, cfg in MONITOR_SYSTEMS.items()}


def monitor_group(gnss_id):
    """gnssId を u-blox モニター表示の衛星系名にする。"""
    return _GROUPS.get(gnss_id, "OTHER")


class State:  # pylint: disable=too-many-instance-attributes
    """集計結果。clock は現在時刻 [s] を返す関数(再生時はデータの時刻で進める)。"""

    def __init__(self, clock=time.time):
        self.clock = clock
        self._lock = threading.Lock()
        self.pvt = None
        self.pvt_history = deque(maxlen=MAX_PVT_HISTORY)
        self.counts = {}
        self._sats = {}      # (gnss, sv) → NAV-SAT
        self._signals = {}   # (gnss, sv) → {sig: {cno, pr, cp, t}}
        self._tracks = {name: {} for name in MONITOR_SYSTEMS}   # 系 → {sv: MEASX/RAWX}
        self._seen = {name: [] for name in MONITOR_SYSTEMS}
        self._sfrbx = {name: {} for name in MONITOR_SYSTEMS}
        self._history = {name: {} for name in MONITOR_SYSTEMS}

    def count(self, msg):
        """受信メッセージ数を数える。"""
        with self._lock:
            self.counts[msg] = self.counts.get(msg, 0) + 1

    def update_pvt(self, pvt):
        """NAV-PVT を反映する。"""
        with self._lock:
            self.pvt = pvt
            self.pvt_history.append((self.clock(), pvt))

    def update_sat(self, sats):
        """NAV-SAT を反映する。"""
        now = self.clock()
        with self._lock:
            self._sats = {(s["gnss"], s["sv"]): {**s, "t": now} for s in sats}

    def _track(self, gnss_id, sv_id, now):
        """u-blox モニター表示用の衛星のエントリを返す。表示枠が無ければ None。"""
        group = monitor_group(gnss_id)
        seen = self._seen[group]
        if sv_id not in seen:
            if len(seen) >= MONITOR_SYSTEMS[group]["max"]:
                return group, None
            seen.append(sv_id)
            seen.sort()
        entry = self._tracks[group].setdefault(sv_id, {"signals": {}})
        entry["last_seen"] = now
        return group, entry

    def _add_history(self, group, sv_id, item):
        """トレンド表示用の履歴に 1 点追加し、古い点を捨てる。"""
        history = self._history[group].setdefault(sv_id, deque())
        history.append(item)
        while history and history[0][0] < item[0] - TREND_SECONDS:
            history.popleft()

    def update_rawx(self, meas):
        """RXM-RAWX を反映する。"""
        now = self.clock()
        with self._lock:
            for m in meas:
                signal = {"cno": m["cno"], "pr": m["pr_valid"], "cp": m["cp_valid"], "t": now}
                self._signals.setdefault((m["gnss"], m["sv"]), {})[m["sig"]] = signal
                group, entry = self._track(m["gnss"], m["sv"], now)
                if entry is None:
                    continue
                entry["signals"][m["sig"]] = signal
                self._add_history(group, m["sv"], (
                    now, m["cno"], entry.get("mpath", 0), entry.get("rmse", 0),
                    int(m["pr_valid"]), int(m["cp_valid"]), (m["gnss"], m["sig"])))

    def update_measx(self, meas):
        """RXM-MEASX を反映する。"""
        now = self.clock()
        with self._lock:
            for m in meas:
                group, entry = self._track(m["gnss"], m["sv"], now)
                if entry is None:
                    continue
                entry.update({key: m[key] for key in (
                    "cno", "mpath", "rmse", "wChips", "fChips", "cPhase", "iPhase")})
                self._add_history(group, m["sv"],
                                  (now, m["cno"], m["mpath"], m["rmse"], 1, 1, None))

    def update_sfrbx(self, frame):
        """RXM-SFRBX を反映する。"""
        with self._lock:
            entry = self._sfrbx[monitor_group(frame["gnss"])].setdefault(
                frame["sv"], {"count": 0, "subframes": set()})
            entry["count"] += 1
            if 1 <= frame["subframe"] <= 5:
                entry["subframes"].add(frame["subframe"])

    def snapshot(self):
        """メイン画面用の集計を返す。"""
        now = self.clock()
        with self._lock:
            satellites = {}
            for (gnss_id, sv_id), sat in self._sats.items():
                if now - sat["t"] > STALE_SECONDS * 2 or (sat["cno"] == 0
                                                          and sat["elev"] is None):
                    continue
                satellites[ubx.sat_name(gnss_id, sv_id)] = {
                    "system": ubx.system_name(gnss_id), "cno": sat["cno"],
                    "elev": sat["elev"], "azim": sat["azim"], "used": sat["used"],
                    "bands": {}}
            by_signal = {}
            for (gnss_id, sv_id), signals in self._signals.items():
                for sig_id, signal in signals.items():
                    if now - signal["t"] > STALE_SECONDS or signal["cno"] == 0:
                        continue
                    name = ubx.sat_name(gnss_id, sv_id)
                    sat = satellites.setdefault(name, {
                        "system": ubx.system_name(gnss_id), "cno": 0, "elev": None,
                        "azim": None, "used": False, "bands": {}})
                    band = ubx.SIGNAL_BANDS.get((gnss_id, sig_id))
                    if band:
                        sat["bands"][band] = max(sat["bands"].get(band, 0), signal["cno"])
                    sat["cno"] = max(sat["cno"], signal["cno"])
                    label = f"{ubx.system_name(gnss_id)} {ubx.signal_name(gnss_id, sig_id)}"
                    by_signal.setdefault(label, []).append(signal["cno"])
            if not by_signal:
                for sat in satellites.values():
                    if sat["cno"] > 0:
                        by_signal.setdefault(sat["system"], []).append(sat["cno"])
            return {"now": now, "pvt": self.pvt, "pvt_history": list(self.pvt_history),
                    "satellites": satellites, "signals": by_signal,
                    "counts": dict(self.counts)}

    def monitor_snapshot(self):
        """u-blox モニター表示(信号・SFRBX)用の集計を返す。"""
        with self._lock:
            return {
                "now": self.clock(),
                "seen": {name: list(svs) for name, svs in self._seen.items()},
                "tracks": {name: {sv: {**entry, "signals": dict(entry["signals"])}
                                  for sv, entry in tracks.items()}
                           for name, tracks in self._tracks.items()},
                "sfrbx": {name: {sv: {"count": e["count"], "subframes": set(e["subframes"])}
                                 for sv, e in frames.items()}
                          for name, frames in self._sfrbx.items()},
            }

    def trend_snapshot(self):
        """トレンド表示用の履歴を返す。"""
        with self._lock:
            return {"now": self.clock(),
                    "seen": {name: list(svs) for name, svs in self._seen.items()},
                    "history": {name: {sv: list(items) for sv, items in history.items()}
                                for name, history in self._history.items()}}
