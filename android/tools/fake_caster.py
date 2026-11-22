"""確認用の偽 NTRIP キャスター(Python 3、標準ライブラリだけ)。

起動: python tools/fake_caster.py --port 2101
エミュレータからはホスト 10.0.2.2、ポート 2101 でつなぐ。

- ソーステーブル: GET / で 3 件(TOKYO / OSAKA / VRS。VRS は GGA の送信が必要)
- Basic 認証: ユーザー test / パスワード test(--user / --password で変更)。違えば 401
- NTRIP 1.0: 「ICY 200 OK」のあとに RTCM3 をそのまま流す
- NTRIP 2.0: 要求に「Ntrip-Version: Ntrip/2.0」があれば HTTP/1.1 200 + chunked で流す
- 1 秒ごとに RTCM3 フレーム(1005 と、中身がダミーの 1074 / 1084 / 1094 / 1124。CRC は正しい)を送る
- 受け取った GGA を標準出力に表示する
- --drop-after N: N 秒ごとに接続を切る(アプリの自動再接続の確認用)
- 無いマウントポイント: NTRIP 1.0 はソーステーブル、2.0 は 404 を返す
"""
import argparse
import base64
import datetime
import socket
import socketserver
import threading
import time

STATION_ECEF = (-3961904.0123, 3348993.7654, 3698211.4321)   # 東京付近 [m]
MOUNTS = (
    ("TOKYO", "Tokyo", "RTCM 3.3", "1005(10),1074(1),1084(1),1094(1),1124(1)", "GPS+GLO+GAL+BDS",
     "35.68", "139.77", 0),
    ("OSAKA", "Osaka", "RTCM 3.2", "1005(10),1074(1)", "GPS", "34.69", "135.50", 0),
    ("VRS", "Virtual", "RTCM 3.3", "1005(10),1074(1),1084(1)", "GPS+GLO", "35.00", "137.00", 1),
)


def sourcetable():
    """ソーステーブルの本文。"""
    lines = [f"STR;{m};{ident};{fmt};{details};2;{nav};NET;JPN;{lat};{lon};{nmea};0;"
             "fake_caster;none;B;N;9600;" for m, ident, fmt, details, nav, lat, lon, nmea in MOUNTS]
    lines.append("ENDSOURCETABLE")
    return ("\r\n".join(lines) + "\r\n").encode()


# ---- RTCM3(ublox_monitor/rtcm.py・tests/test_rtcm.py と同じ作り方) ----

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
    crc = 0
    for byte in data:
        crc = ((crc << 8) & 0xFFFFFF) ^ _CRC_TABLE[(crc >> 16) ^ byte]
    return crc


def build_frame(payload):
    head = bytes((0xD3, (len(payload) >> 8) & 0x03, len(payload) & 0xFF)) + payload
    return head + crc24q(head).to_bytes(3, "big")


def msg_1005(station_id=1234, ecef=STATION_ECEF):
    bits = ""
    for value, length in ((1005, 12), (station_id, 12), (0, 6), (1, 1), (1, 1), (1, 1), (0, 1),
                          (round(ecef[0] * 1e4), 38), (0, 1), (0, 1), (round(ecef[1] * 1e4), 38),
                          (0, 2), (round(ecef[2] * 1e4), 38)):
        bits += format(value & ((1 << length) - 1), f"0{length}b")
    bits += "0" * (-len(bits) % 8)
    return int(bits, 2).to_bytes(len(bits) // 8, "big")


def msm(msg_type, size=40):
    """中身はダミーの MSM ペイロード(先頭 12 ビットだけ正しい)。"""
    return (msg_type << 4).to_bytes(2, "big") + bytes(size)


def epoch_frames():
    return b"".join(build_frame(p) for p in (msg_1005(), msm(1074), msm(1084), msm(1094), msm(1124)))


# ---- サーバ ----

def log(text):
    print(f"{datetime.datetime.now():%H:%M:%S}  {text}", flush=True)


class Handler(socketserver.BaseRequestHandler):
    """1 接続ぶん。"""
    server: "Caster"

    def handle(self):
        conn = self.request
        conn.settimeout(10)
        peer = f"{self.client_address[0]}:{self.client_address[1]}"
        data = b""
        try:
            while b"\r\n\r\n" not in data and len(data) < 16384:
                chunk = conn.recv(4096)
                if not chunk:
                    return
                data += chunk
        except socket.timeout:
            log(f"{peer} 要求が届きません")
            return
        head, _sep, rest = data.partition(b"\r\n\r\n")
        lines = head.decode("latin-1").split("\r\n")
        request = lines[0]
        headers = {}
        for line in lines[1:]:
            key, _c, value = line.partition(":")
            headers[key.strip().lower()] = value.strip()
        parts = request.split(" ")
        mount = parts[1].lstrip("/") if len(parts) >= 2 else ""
        v2 = headers.get("ntrip-version", "").lower() == "ntrip/2.0"
        log(f"{peer} {request}  ({'NTRIP 2.0' if v2 else 'NTRIP 1.0'}, UA: {headers.get('user-agent', '')})")

        if mount == "":
            self.send_table(conn, v2)
            return
        if mount not in {m[0] for m in MOUNTS}:
            log(f"{peer} マウントポイント {mount} はありません")
            if v2:
                conn.sendall(b"HTTP/1.1 404 Not Found\r\nNtrip-Version: Ntrip/2.0\r\nConnection: close\r\n\r\n")
            else:
                self.send_table(conn, v2)
            return
        if not self.authorized(headers.get("authorization", "")):
            log(f"{peer} 認証に失敗(401)")
            conn.sendall(b"HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Basic realm=\"fake\"\r\n"
                         + (b"Ntrip-Version: Ntrip/2.0\r\n" if v2 else b"") + b"Connection: close\r\n\r\n")
            return
        if v2:
            conn.sendall(b"HTTP/1.1 200 OK\r\nNtrip-Version: Ntrip/2.0\r\nContent-Type: gnss/data\r\n"
                         b"Cache-Control: no-store\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n")
        else:
            conn.sendall(b"ICY 200 OK\r\n")
        log(f"{peer} {mount} の配信を開始")
        self.stream(conn, peer, v2, rest)

    def authorized(self, value):
        if not value.lower().startswith("basic "):
            return False
        try:
            text = base64.b64decode(value[6:].strip()).decode("utf-8")
        except (ValueError, UnicodeDecodeError):
            return False
        return text == f"{self.server.user}:{self.server.password}"

    def send_table(self, conn, v2):
        body = sourcetable()
        if v2:
            conn.sendall(b"HTTP/1.1 200 OK\r\nNtrip-Version: Ntrip/2.0\r\nContent-Type: gnss/sourcetable\r\n"
                         + f"Content-Length: {len(body)}\r\nConnection: close\r\n\r\n".encode() + body)
        else:
            conn.sendall(b"SOURCETABLE 200 OK\r\nServer: fake_caster\r\nContent-Type: text/plain\r\n"
                         + f"Content-Length: {len(body)}\r\n\r\n".encode() + body)
        log("ソーステーブルを送りました")

    def stream(self, conn, peer, v2, pending):
        """1 秒ごとに RTCM3 を送り、届いた GGA を表示する。"""
        conn.settimeout(0.1)
        started = time.monotonic()
        next_send = started
        received = pending
        sent = 0
        try:
            while not self.server.stopping:
                now = time.monotonic()
                if self.server.drop_after and now - started >= self.server.drop_after:
                    log(f"{peer} --drop-after {self.server.drop_after} 秒なので切ります")
                    return
                if now >= next_send:
                    data = epoch_frames()
                    if v2:
                        data = f"{len(data):x}\r\n".encode() + data + b"\r\n"
                    conn.sendall(data)
                    sent += 1
                    next_send += 1.0
                try:
                    chunk = conn.recv(4096)
                    if not chunk:
                        log(f"{peer} 相手が切りました(送信 {sent} 回)")
                        return
                    received += chunk
                except socket.timeout:
                    pass
                while b"\n" in received:
                    line, _n, received = received.partition(b"\n")
                    text = line.decode("latin-1").strip()
                    if text:
                        log(f"{peer} 受信: {text}")
        except OSError as exc:
            log(f"{peer} 切断: {exc}(送信 {sent} 回)")


class Caster(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

    def __init__(self, address, user, password, drop_after):
        super().__init__(address, Handler)
        self.user = user
        self.password = password
        self.drop_after = drop_after
        self.stopping = False


def main():
    parser = argparse.ArgumentParser(description="確認用の偽 NTRIP キャスター")
    parser.add_argument("--host", default="0.0.0.0", help="待ち受けるアドレス(既定 0.0.0.0)")
    parser.add_argument("--port", type=int, default=2101)
    parser.add_argument("--user", default="test")
    parser.add_argument("--password", default="test")
    parser.add_argument("--drop-after", type=float, default=0, help="N 秒ごとに接続を切る(0 は切らない)")
    args = parser.parse_args()
    server = Caster((args.host, args.port), args.user, args.password, args.drop_after)
    log(f"偽 NTRIP キャスター {args.host}:{args.port}  ユーザー {args.user} / パスワード {args.password}  "
        f"マウントポイント {', '.join(m[0] for m in MOUNTS)}(Ctrl+C で終了)")
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        while thread.is_alive():
            thread.join(0.5)
    except KeyboardInterrupt:
        log("終了します")
    finally:
        server.stopping = True
        server.shutdown()
        server.server_close()


if __name__ == "__main__":
    main()
