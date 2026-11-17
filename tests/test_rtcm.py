"""RTCM 補正の受信のテスト。ローカルに偽の NTRIP キャスターを立てて確かめる。"""
import socket
import struct
import threading
import time

import pytest

from ublox_monitor import rtcm, settings, ubx

from . import ubx_samples as samples

STATION = (-3961904.0123, 3348993.7654, 3698211.4321)   # 東京付近の ECEF [m]


def set_bits(bits, value, length):
    """value を length ビットの 2 の補数で bits(文字列)に足す。"""
    return bits + format(value & ((1 << length) - 1), f"0{length}b")


def msg_1005(station_id=1234, ecef=STATION):
    """1005 のペイロードを作る。"""
    bits = ""
    for value, length in ((1005, 12), (station_id, 12), (0, 6), (1, 1), (1, 1), (1, 1), (0, 1),
                          (round(ecef[0] * 1e4), 38), (0, 1), (0, 1), (round(ecef[1] * 1e4), 38),
                          (0, 2), (round(ecef[2] * 1e4), 38)):
        bits = set_bits(bits, value, length)
    bits += "0" * (-len(bits) % 8)
    return int(bits, 2).to_bytes(len(bits) // 8, "big")


def msm(msg_type, size=40):
    """中身はダミーの MSM ペイロードを作る。"""
    return (msg_type << 4).to_bytes(2, "big") + bytes(size)


def test_crc24q_check_value():
    assert rtcm.crc24q(b"123456789") == 0xCDE703


def test_stream_splits_frames_and_rejects_bad_crc():
    frames = [rtcm.build_frame(msm(1077)), rtcm.build_frame(msg_1005())]
    broken = bytearray(rtcm.build_frame(msm(1087)))
    broken[10] ^= 0xFF
    data = b"\x00junk" + frames[0] + bytes(broken) + frames[1]
    stream = rtcm.RtcmStream()
    payloads = []
    for pos in range(0, len(data), 5):
        payloads += stream.feed(data[pos:pos + 5])
    assert [rtcm.message_type(p) for p in payloads] == [1077, 1005]
    assert stream.crc_errors == 1


def test_station_position_and_baseline():
    station = rtcm.parse_station(msg_1005())
    assert station["id"] == 1234
    assert (station["x"], station["y"], station["z"]) == pytest.approx(STATION, abs=1e-4)
    back = rtcm.llh_to_ecef(station["lat"], station["lon"], station["height"])
    assert back == pytest.approx(STATION, abs=1e-3)
    (_m, payload), = ubx.UbxStream().feed(samples.nav_pvt(0, station["lat"], station["lon"]))
    pvt = ubx.parse_nav_pvt(payload)
    # サンプルの楕円体高は 45.123 m なので、距離はほぼ高さの差になる
    assert rtcm.baseline(station, pvt) == pytest.approx(abs(station["height"] - 45.123), abs=0.01)


def test_message_names():
    assert rtcm.message_name(1077) == "GPS MSM7"
    assert rtcm.message_name(1127) == "BeiDou MSM7"
    assert rtcm.message_name(1005) == "基準局の位置"
    assert rtcm.message_name(9999) == ""


def test_gga_sentence():
    (_m, payload), = ubx.UbxStream().feed(samples.nav_pvt(123000, 35.5, 139.25, carrier=2))
    sentence = rtcm.build_gga(ubx.parse_nav_pvt(payload))
    body, checksum = sentence.strip()[1:].split("*")
    fields = body.split(",")
    assert fields[:7] == ["GPGGA", "030203.00", "3530.00000", "N", "13915.00000", "E", "4"]
    assert fields[9] == "8.456" and fields[11] == "36.667"
    expected = 0
    for char in body.encode():
        expected ^= char
    assert int(checksum, 16) == expected
    assert rtcm.build_gga(None) is None


def test_nav_pvt_correction_age():
    payload = bytearray(ubx.UbxStream().feed(samples.nav_pvt(0))[0][1])
    struct.pack_into("<H", payload, 78, 4 << 1)
    pvt = ubx.parse_nav_pvt(bytes(payload))
    assert pvt.correction_age == 4 and pvt.correction_age_text == "5〜10 秒"
    assert pvt.diff_soln


def test_chunked_decoder_across_pieces():
    body = b"5\r\nhello\r\n6;ext=1\r\n world\r\n0\r\n\r\n"
    decoder = rtcm.ChunkedDecoder()
    out = b"".join(decoder.feed(body[i:i + 3]) for i in range(0, len(body), 3))
    assert out == b"hello world"


def test_rtcm3_input_keys_cover_each_receiver_port():
    keys = {settings.ITEM_BY_IDENT[ident].key for ident in settings.RTCM3_INPUT.values()}
    assert keys == {0x10770004, 0x10730004, 0x10750004}


# ---- 偽の NTRIP キャスター ----

SOURCETABLE = ("STR;TOKYO;Tokyo;RTCM 3.3;1005(10),1077(1);2;GPS+GLO;NET;JPN;35.68;139.77;1;0;"
               "gen;none;B;N;9600;\r\nSTR;OSAKA;Osaka;RTCM 3.2;1074(1);2;GPS;NET;JPN;34.69;135.50;"
               "0;0;gen;none;B;N;9600;\r\nENDSOURCETABLE\r\n")


class FakeCaster(threading.Thread):
    """1 接続だけ受け付ける偽のキャスター。"""

    def __init__(self, mode):
        super().__init__(daemon=True)
        self.mode = mode
        self.server = socket.create_server(("127.0.0.1", 0))
        self.port = self.server.getsockname()[1]
        self.request = b""
        self.received = b""
        self.frames = [rtcm.build_frame(msg_1005()), rtcm.build_frame(msm(1077)),
                       rtcm.build_frame(msm(1127))]

    def run(self):
        conn, _addr = self.server.accept()
        with conn:
            conn.settimeout(3)
            while b"\r\n\r\n" not in self.request:
                self.request += conn.recv(4096)
            data = b"".join(self.frames)
            if self.mode == "v1":
                conn.sendall(b"ICY 200 OK\r\n" + data[:20])
                time.sleep(0.1)
                conn.sendall(data[20:])
            elif self.mode == "v2":
                conn.sendall(b"HTTP/1.1 200 OK\r\nNtrip-Version: Ntrip/2.0\r\n"
                             b"Transfer-Encoding: chunked\r\n\r\n")
                for piece in (data[:30], data[30:]):
                    conn.sendall(f"{len(piece):x}\r\n".encode() + piece + b"\r\n")
            elif self.mode == "table":
                body = SOURCETABLE.encode()
                conn.sendall(b"SOURCETABLE 200 OK\r\nContent-Type: text/plain\r\n"
                             + f"Content-Length: {len(body)}\r\n\r\n".encode() + body)
                return
            elif self.mode == "denied":
                conn.sendall(b"HTTP/1.1 401 Unauthorized\r\n\r\n")
                return
            conn.settimeout(0.5)
            deadline = time.monotonic() + 2
            while time.monotonic() < deadline and b"GGA" not in self.received:
                try:
                    self.received += conn.recv(4096)
                except socket.timeout:
                    pass


def run_client(mode, **extra):
    """偽のキャスターに接続して、転送されたバイト列とスレッドを返す。"""
    caster = FakeCaster(mode)
    caster.start()
    forwarded = []
    config = {"kind": "ntrip", "host": "127.0.0.1", "port": caster.port, "mount": "TOKYO",
              "user": "user", "password": "pass", "version": 2 if mode == "v2" else 1,
              "gga_interval": 1, **extra}
    gga = "$GPGGA,000000.00,3540.00000,N,13946.00000,E,1,08,1.0,40.0,M,36.7,M,,*00\r\n"
    client = rtcm.CorrectionSource(config, forwarded.append, lambda: gga)
    client.start()
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline and client.is_alive() and (
            sum(map(len, forwarded)) < sum(map(len, caster.frames))):
        time.sleep(0.05)
    caster.join(timeout=5)
    client.stop()
    return caster, client, b"".join(forwarded)


@pytest.mark.parametrize("mode", ["v1", "v2"])
def test_ntrip_client_forwards_rtcm_and_sends_gga(mode):
    caster, client, forwarded = run_client(mode)
    assert forwarded == b"".join(caster.frames)
    request = caster.request.decode()
    assert request.startswith("GET /TOKYO HTTP/1.")
    assert "Authorization: Basic dXNlcjpwYXNz" in request
    assert ("Ntrip-Version: Ntrip/2.0" in request) == (mode == "v2")
    assert b"$GPGGA" in caster.received
    snap = client.snapshot()
    assert set(snap["messages"]) == {1005, 1077, 1127}
    assert snap["station"]["id"] == 1234 and snap["crc_errors"] == 0


def test_ntrip_client_reports_rejection():
    _caster, client, forwarded = run_client("denied")
    assert forwarded == b"" and "401" in client.error


def test_sourcetable():
    caster = FakeCaster("table")
    caster.start()
    mounts = rtcm.fetch_sourcetable("127.0.0.1", caster.port, version=1)
    caster.join(timeout=5)
    assert [m["mount"] for m in mounts] == ["TOKYO", "OSAKA"]
    assert mounts[0]["nmea"] and not mounts[1]["nmea"]
    assert caster.request.startswith(b"GET / HTTP/1.0")
