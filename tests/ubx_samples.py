"""テストと動作確認用の UBX メッセージを作る。"""
import math
import struct

from ublox_monitor import ubx

# (gnssId, svId, 方位, 仰角, [(sigId, C/N0)])
SATELLITES = (
    (0, 5, 40, 62, [(0, 45), (3, 40)]), (0, 13, 120, 35, [(0, 41), (3, 36)]),
    (0, 15, 210, 48, [(0, 43)]), (0, 24, 300, 20, [(0, 33), (3, 28)]),
    (2, 4, 80, 55, [(0, 42), (5, 39)]), (2, 11, 170, 28, [(0, 37), (5, 34)]),
    (2, 19, 260, 70, [(0, 46), (5, 43)]), (3, 21, 20, 44, [(0, 40), (2, 38)]),
    (3, 35, 150, 66, [(0, 44), (2, 41)]), (5, 2, 190, 78, [(0, 44), (5, 41)]),
    (5, 7, 200, 47, [(0, 40), (5, 37)]), (6, 8, 330, 31, [(0, 38), (2, 33)]),
)


def nav_pvt(itow, lat=35.6812, lon=139.7671, carrier=2, num_sv=12):
    """NAV-PVT を作る。carrier は 0: なし、1: Float、2: Fixed。"""
    second = itow // 1000 % 60
    payload = struct.pack(
        "<IHBBBBBBIiBBBBiiiiIIiiiiiIIH", itow, 2026, 11, 17, 3, itow // 60000 % 60, second,
        0x07, 20, 0, 3, 0x01 | 0x02 | (carrier << 6), 0, num_sv, round(lon * 1e7),
        round(lat * 1e7), 45123, 8456, 14, 21, 0, 0, 0, 1250, 9000000, 50, 100000, 120)
    return ubx.build(ubx.NAV_PVT, payload.ljust(92, b"\x00"))


def nav_sat(itow):
    """NAV-SAT を作る。"""
    payload = struct.pack("<IBBH", itow, 1, len(SATELLITES), 0)
    for gnss_id, sv_id, azim, elev, signals in SATELLITES:
        payload += struct.pack("<BBBbhhI", gnss_id, sv_id, signals[0][1], elev, azim, 0,
                               0x0F if elev >= 25 else 0x04)
    return ubx.build(ubx.NAV_SAT, payload)


def rxm_rawx(tow):
    """RXM-RAWX を作る。"""
    meas = b""
    count = 0
    for gnss_id, sv_id, _azim, _elev, signals in SATELLITES:
        for sig_id, cno in signals:
            wobble = round(2 * math.sin(tow / 7 + sv_id))
            meas += struct.pack("<ddfBBBBHBBBBBB", 2.1e7, 1.1e8, -500.0, gnss_id, sv_id, sig_id,
                                0, 64500, cno + wobble, 5, 2, 8, 0x03, 0)
            count += 1
    header = struct.pack("<dHbBBBBB", tow, 2393, 18, count, 0x01, 1, 0, 0)
    return ubx.build(ubx.RXM_RAWX, header + meas)


def rxm_measx(itow):
    """RXM-MEASX を作る。"""
    payload = bytearray(44)
    payload[34] = len(SATELLITES)
    for index, (gnss_id, sv_id, _azim, _elev, signals) in enumerate(SATELLITES):
        payload += struct.pack("<BBBBiiHHIBBH", gnss_id, sv_id, signals[0][1], index % 4,
                               1000, 1000, 500 + index, 300, 123456, 7, 10 + index * 3, 0)
    struct.pack_into("<I", payload, 4, itow)
    return ubx.build(ubx.RXM_MEASX, bytes(payload))


def rxm_sfrbx(gnss_id, sv_id, subframe):
    """GPS / QZSS L1C/A の RXM-SFRBX を作る。"""
    words = [0x22C00000, subframe << 8] + [0] * 8
    payload = struct.pack("<BBBBBBBB", gnss_id, sv_id, 0, 0, len(words), 0, 2, 0)
    return ubx.build(ubx.RXM_SFRBX, payload + struct.pack("<10I", *words))


def mon_ver(module="ZED-F9P", protver="27.50", hardware="00190000"):
    """MON-VER を作る。"""
    payload = b"EXT CORE 1.00 (abcdef)".ljust(30, b"\x00") + hardware.encode().ljust(10, b"\x00")
    for ext in ("ROM BASE 0x118B2060", "FWVER=HPG 1.50", f"PROTVER={protver}",
                f"MOD={module}"):
        payload += ext.encode().ljust(30, b"\x00")
    return ubx.build(ubx.MON_VER, payload)


def log(seconds=30, start_itow=100000):
    """seconds 秒分の 1 Hz のログを作る。位置は少しずつ北東へ動く。"""
    data = mon_ver()
    for index in range(seconds):
        itow = start_itow + index * 1000
        carrier = 2 if index % 10 < 7 else 1
        data += nav_pvt(itow, 35.6812 + index * 2e-6, 139.7671 + index * 3e-6, carrier)
        data += nav_sat(itow) + rxm_rawx(itow / 1000) + rxm_measx(itow)
        data += rxm_sfrbx(0, 5, index % 5 + 1) + rxm_sfrbx(5, 2, index % 3 + 1)
    return data
