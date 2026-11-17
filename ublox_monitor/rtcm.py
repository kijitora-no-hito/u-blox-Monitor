"""RTCM 補正情報の受信(NTRIP / TCP / シリアル)と、RTCM3 の解析。

受信したバイト列は on_data に渡す(アプリが受信機へ転送する)。
"""
import base64
import math
import socket
import ssl
import threading
import time
from collections import deque

import serial

from . import __version__

USER_AGENT = f"NTRIP ublox_monitor/{__version__}"
CONNECT_TIMEOUT = 10.0
READ_TIMEOUT = 1.0
NO_DATA_TIMEOUT = 30.0
RATE_WINDOW = 10.0
MAX_HEADER = 16384
WGS84_A = 6378137.0
WGS84_F = 1 / 298.257223563

MESSAGE_NAMES = {
    1001: "GPS L1 観測量", 1002: "GPS L1 観測量", 1003: "GPS L1/L2 観測量",
    1004: "GPS L1/L2 観測量", 1005: "基準局の位置", 1006: "基準局の位置 + アンテナ高",
    1007: "アンテナ情報", 1008: "アンテナ情報", 1009: "GLONASS L1 観測量",
    1010: "GLONASS L1 観測量", 1011: "GLONASS L1/L2 観測量", 1012: "GLONASS L1/L2 観測量",
    1013: "システムパラメータ", 1019: "GPS エフェメリス", 1020: "GLONASS エフェメリス",
    1033: "受信機・アンテナ情報", 1042: "BeiDou エフェメリス", 1044: "QZSS エフェメリス",
    1045: "Galileo F/NAV エフェメリス", 1046: "Galileo I/NAV エフェメリス",
    1230: "GLONASS コードバイアス", 4072: "u-blox 独自(移動基準局)",
}
_MSM_SYSTEMS = {107: "GPS", 108: "GLONASS", 109: "Galileo", 110: "SBAS", 111: "QZSS",
                112: "BeiDou", 113: "NavIC"}


def message_name(msg_type):
    """RTCM3 メッセージ番号の説明を返す。"""
    if msg_type in MESSAGE_NAMES:
        return MESSAGE_NAMES[msg_type]
    system = _MSM_SYSTEMS.get(msg_type // 10)
    if system and 1 <= msg_type % 10 <= 7:
        return f"{system} MSM{msg_type % 10}"
    return ""


# ---- RTCM3 フレーム ----

def _crc24q_table():
    table = []
    for byte in range(256):
        crc = byte << 16
        for _ in range(8):
            crc <<= 1
            if crc & 0x1000000:
                crc ^= 0x1864CFB
        table.append(crc & 0xFFFFFF)
    return table


_CRC_TABLE = _crc24q_table()


def crc24q(data):
    """RTCM3 の CRC-24Q を返す。"""
    crc = 0
    for byte in data:
        crc = ((crc << 8) & 0xFFFFFF) ^ _CRC_TABLE[(crc >> 16) ^ byte]
    return crc


def build_frame(payload):
    """RTCM3 フレームを組み立てる(テスト用)。"""
    head = bytes((0xD3, (len(payload) >> 8) & 0x03, len(payload) & 0xFF)) + payload
    return head + crc24q(head).to_bytes(3, "big")


def get_bits(data, start, length, signed=False):
    """data の start ビット目から length ビットを整数で返す(MSB が先)。"""
    value = int.from_bytes(data, "big") >> (len(data) * 8 - start - length)
    value &= (1 << length) - 1
    if signed and value >> (length - 1):
        value -= 1 << length
    return value


class RtcmStream:
    """バイト列から RTCM3 フレームを取り出す。CRC が合わないものは捨てる。"""

    def __init__(self):
        self._buffer = bytearray()
        self.crc_errors = 0

    def feed(self, data):
        """data を追加し、完成したフレームのペイロードのリストを返す。"""
        self._buffer += data
        buf = self._buffer
        frames = []
        pos = 0
        while True:
            start = buf.find(b"\xD3", pos)
            if start < 0:
                pos = len(buf)
                break
            if len(buf) - start < 3:
                pos = start
                break
            length = ((buf[start + 1] & 0x03) << 8) | buf[start + 2]
            end = start + 3 + length + 3
            if len(buf) < end:
                pos = start
                break
            if crc24q(buf[start:end - 3]) == int.from_bytes(buf[end - 3:end], "big"):
                frames.append(bytes(buf[start + 3:end - 3]))
                pos = end
            else:
                self.crc_errors += 1
                pos = start + 1
        del buf[:pos]
        return frames


def message_type(payload):
    """RTCM3 のメッセージ番号を返す。"""
    return get_bits(payload, 0, 12) if len(payload) >= 2 else None


def parse_station(payload):
    """1005 / 1006 から基準局の情報を返す。ECEF は m。"""
    if message_type(payload) not in (1005, 1006) or len(payload) < 19:
        return None
    station = {"id": get_bits(payload, 12, 12),
               "x": get_bits(payload, 34, 38, True) * 1e-4,
               "y": get_bits(payload, 74, 38, True) * 1e-4,
               "z": get_bits(payload, 114, 38, True) * 1e-4}
    station.update(zip(("lat", "lon", "height"), ecef_to_llh(
        station["x"], station["y"], station["z"])))
    return station


def ecef_to_llh(x, y, z):
    """WGS84 の ECEF [m] を (緯度 [°], 経度 [°], 楕円体高 [m]) にする。"""
    e2 = WGS84_F * (2 - WGS84_F)
    lon = math.atan2(y, x)
    p = math.hypot(x, y)
    lat = math.atan2(z, p * (1 - e2))
    for _ in range(6):
        n = WGS84_A / math.sqrt(1 - e2 * math.sin(lat) ** 2)
        height = p / math.cos(lat) - n
        lat = math.atan2(z, p * (1 - e2 * n / (n + height)))
    n = WGS84_A / math.sqrt(1 - e2 * math.sin(lat) ** 2)
    height = p / math.cos(lat) - n
    return math.degrees(lat), math.degrees(lon), height


def llh_to_ecef(lat, lon, height):
    """(緯度 [°], 経度 [°], 楕円体高 [m]) を WGS84 の ECEF [m] にする。"""
    e2 = WGS84_F * (2 - WGS84_F)
    lat, lon = math.radians(lat), math.radians(lon)
    n = WGS84_A / math.sqrt(1 - e2 * math.sin(lat) ** 2)
    return ((n + height) * math.cos(lat) * math.cos(lon),
            (n + height) * math.cos(lat) * math.sin(lon),
            (n * (1 - e2) + height) * math.sin(lat))


def baseline(station, pvt):
    """基準局から現在位置までの距離 [m] を返す。分からなければ None。"""
    if station is None or pvt is None or pvt.latitude is None:
        return None
    rover = llh_to_ecef(pvt.latitude, pvt.longitude, pvt.height)
    return math.dist(rover, (station["x"], station["y"], station["z"]))


# ---- NMEA GGA(VRS などのキャスターへ現在位置を送る) ----

def _nmea_angle(value, width):
    """度を NMEA の ddmm.mmmmm 形式にする。"""
    value = abs(value)
    degrees = int(value)
    minutes = (value - degrees) * 60
    return f"{degrees:0{width}d}{minutes:08.5f}"


def build_gga(pvt):
    """NAV-PVT から GGA 文を作る。位置が無ければ None。"""
    if pvt is None or pvt.latitude is None:
        return None
    quality = {8: 4, 7: 5, 6: 2}.get(pvt.fix_rank, 1)
    clock = pvt.utc.split(" ")[1].replace(":", "")
    separation = pvt.height - pvt.height_msl
    body = (f"GPGGA,{clock}.00,{_nmea_angle(pvt.latitude, 2)},"
            f"{'N' if pvt.latitude >= 0 else 'S'},{_nmea_angle(pvt.longitude, 3)},"
            f"{'E' if pvt.longitude >= 0 else 'W'},{quality},{min(pvt.num_sv, 99):02d},"
            f"{pvt.pdop:.1f},{pvt.height_msl:.3f},M,{separation:.3f},M,,")
    checksum = 0
    for char in body.encode("ascii"):
        checksum ^= char
    return f"${body}*{checksum:02X}\r\n"


# ---- NTRIP ----

def ntrip_request(host, port, mountpoint, user, password, version):
    """NTRIP の要求を作る。version は 1 か 2。"""
    lines = [f"GET /{mountpoint} HTTP/{'1.1' if version == 2 else '1.0'}",
             f"User-Agent: {USER_AGENT}"]
    if version == 2:
        lines += [f"Host: {host}:{port}", "Ntrip-Version: Ntrip/2.0", "Connection: close"]
    if user:
        token = base64.b64encode(f"{user}:{password}".encode()).decode()
        lines.append(f"Authorization: Basic {token}")
    return ("\r\n".join(lines) + "\r\n\r\n").encode()


def parse_sourcetable(text):
    """ソーステーブルの STR 行を [dict] にする。"""
    mounts = []
    for line in text.splitlines():
        fields = line.split(";")
        if fields[0] != "STR" or len(fields) < 12:
            continue
        mounts.append({"mount": fields[1], "id": fields[2], "format": fields[3],
                       "details": fields[4], "nav": fields[6], "country": fields[8],
                       "lat": fields[9], "lon": fields[10], "nmea": fields[11] == "1"})
    return mounts


class ChunkedDecoder:
    """HTTP の chunked 転送を解く。"""

    def __init__(self):
        self._buffer = bytearray()
        self._remaining = None

    def feed(self, data):
        """data を追加し、取り出せた本体のバイト列を返す。"""
        self._buffer += data
        out = bytearray()
        while True:
            if self._remaining is None:
                end = self._buffer.find(b"\r\n")
                if end < 0:
                    break
                size_text = bytes(self._buffer[:end]).split(b";")[0].strip() or b"0"
                self._remaining = int(size_text, 16)
                del self._buffer[:end + 2]
            if len(self._buffer) < self._remaining + 2:
                take = min(self._remaining, len(self._buffer))
                out += self._buffer[:take]
                del self._buffer[:take]
                self._remaining -= take
                break
            out += self._buffer[:self._remaining]
            del self._buffer[:self._remaining + 2]
            self._remaining = None
        return bytes(out)


def _open_socket(host, port, use_tls):
    """TCP(必要なら TLS)で接続する。"""
    sock = socket.create_connection((host, port), timeout=CONNECT_TIMEOUT)
    if use_tls:
        sock = ssl.create_default_context().wrap_socket(sock, server_hostname=host)
    sock.settimeout(READ_TIMEOUT)
    return sock


def _read_header(sock):
    """応答のヘッダーと、ヘッダーの後に続いて届いた本体を返す。"""
    data = b""
    deadline = time.monotonic() + CONNECT_TIMEOUT
    # NTRIP 1.0 の「ICY 200 OK」は 1 行だけで、すぐ後に RTCM が続く。
    # それ以外(HTTP、SOURCETABLE)は空行までがヘッダー
    while time.monotonic() < deadline and len(data) < MAX_HEADER:
        if data.startswith(b"ICY") and b"\r\n" in data:
            break
        if b"\r\n\r\n" in data:
            break
        try:
            chunk = sock.recv(4096)
        except socket.timeout:
            continue
        if not chunk:
            break
        data += chunk
    if data.startswith(b"ICY"):
        header, _sep, rest = data.partition(b"\r\n")
        if rest.startswith(b"\r\n"):
            rest = rest[2:]
        return header.decode("latin-1"), rest
    header, _sep, rest = data.partition(b"\r\n\r\n")
    return header.decode("latin-1"), rest


def fetch_sourcetable(host, port, user="", password="", version=2, use_tls=False):
    """キャスターのソーステーブルを取得して [dict] で返す。"""
    # pylint: disable=too-many-arguments,too-many-positional-arguments
    with _open_socket(host, port, use_tls) as sock:
        sock.sendall(ntrip_request(host, port, "", user, password, version))
        header, body = _read_header(sock)
        if not (header.startswith("SOURCETABLE 200") or " 200" in header.split("\r\n")[0]):
            raise OSError(f"キャスターの応答: {header.splitlines()[0] if header else '応答なし'}")
        decoder = ChunkedDecoder() if "chunked" in header.lower() else None
        text = decoder.feed(body) if decoder else body
        deadline = time.monotonic() + CONNECT_TIMEOUT
        while b"ENDSOURCETABLE" not in text and time.monotonic() < deadline:
            try:
                chunk = sock.recv(65536)
            except socket.timeout:
                continue
            if not chunk:
                break
            text += decoder.feed(chunk) if decoder else chunk
    return parse_sourcetable(text.decode("latin-1"))


# ---- 補正情報の受信スレッド ----

class CorrectionSource(threading.Thread):  # pylint: disable=too-many-instance-attributes
    """補正情報を受信するスレッド。

    config の kind は "ntrip" / "tcp" / "serial"。受信したバイト列は on_data に渡す。
    gga() は送る GGA 文(または None)を返す関数で、NTRIP のときに gga_interval 秒ごとに送る。
    """

    def __init__(self, config, on_data, gga=None):
        super().__init__(daemon=True)
        self.config = config
        self.on_data = on_data
        self.gga = gga
        self.error = None
        self.status = "接続中"
        self.bytes = 0
        self.connected_at = None
        self.last_data = None
        self.messages = {}       # 番号 → [受信数, 最終受信時刻]
        self.station = None
        self.stream = RtcmStream()
        self._recent = deque()
        self._lock = threading.Lock()
        self._stop_event = threading.Event()

    def stop(self):
        """スレッドを止めて終了を待つ。"""
        self._stop_event.set()
        if self.is_alive():
            self.join(timeout=3.0)

    def _received(self, data):
        """受信したバイト列を集計して転送する。"""
        if not data:
            return
        now = time.monotonic()
        with self._lock:
            self.bytes += len(data)
            self.last_data = now
            self._recent.append((now, len(data)))
            for payload in self.stream.feed(data):
                msg_type = message_type(payload)
                entry = self.messages.setdefault(msg_type, [0, now])
                entry[0] += 1
                entry[1] = now
                station = parse_station(payload)
                if station is not None:
                    self.station = station
        self.on_data(data)

    def snapshot(self):
        """表示用の集計を返す。"""
        now = time.monotonic()
        with self._lock:
            while self._recent and self._recent[0][0] < now - RATE_WINDOW:
                self._recent.popleft()
            rate = sum(size for _t, size in self._recent) / RATE_WINDOW
            return {"status": self.status, "error": self.error, "bytes": self.bytes,
                    "rate": rate, "crc_errors": self.stream.crc_errors,
                    "age": None if self.last_data is None else now - self.last_data,
                    "messages": {k: (v[0], now - v[1]) for k, v in self.messages.items()},
                    "station": self.station}

    def run(self):
        try:
            kind = self.config["kind"]
            if kind == "serial":
                self._run_serial()
            else:
                self._run_socket(kind == "ntrip")
        except (OSError, ValueError, serial.SerialException) as exc:
            self.error = str(exc)
        self.status = "停止"

    def _run_serial(self):
        """シリアルポート(無線機など)から受信する。"""
        cfg = self.config
        with serial.Serial(cfg["port"], cfg["baud"], timeout=0.2) as port:
            self.status = f"受信中({cfg['port']})"
            self.connected_at = time.monotonic()
            while not self._stop_event.is_set():
                self._received(port.read(max(1, port.in_waiting)))

    def _run_socket(self, ntrip):
        """NTRIP キャスターまたは TCP サーバから受信する。"""
        cfg = self.config
        with _open_socket(cfg["host"], cfg["port"], cfg.get("tls", False)) as sock:
            decoder = None
            if ntrip:
                sock.sendall(ntrip_request(cfg["host"], cfg["port"], cfg["mount"],
                                           cfg.get("user", ""), cfg.get("password", ""),
                                           cfg.get("version", 2)))
                header, rest = _read_header(sock)
                first = header.splitlines()[0] if header else "応答なし"
                if first.startswith("SOURCETABLE"):
                    raise OSError("マウントポイントが見つかりません(ソーステーブルが返りました)")
                if not (first.startswith("ICY 200") or " 200" in first):
                    raise OSError(f"キャスターの応答: {first}")
                if "chunked" in header.lower():
                    decoder = ChunkedDecoder()
                    rest = decoder.feed(rest)
                self._received(rest)
            self.status = "受信中"
            self.connected_at = time.monotonic()
            next_gga = 0.0
            while not self._stop_event.is_set():
                now = time.monotonic()
                if ntrip and self.gga and cfg.get("gga_interval") and now >= next_gga:
                    sentence = self.gga()
                    if sentence:
                        sock.sendall(sentence.encode("ascii"))
                        next_gga = now + cfg["gga_interval"]
                try:
                    data = sock.recv(65536)
                except socket.timeout:
                    if now - (self.last_data or self.connected_at) > NO_DATA_TIMEOUT:
                        raise OSError(f"{NO_DATA_TIMEOUT:.0f} 秒間データが届きません") from None
                    continue
                if not data:
                    raise OSError("相手が接続を切りました")
                self._received(decoder.feed(data) if decoder else data)
