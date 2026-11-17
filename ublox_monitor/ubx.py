"""UBX プロトコルの分離(チェックサム検査)、デコード、送信パケットの組み立て。"""
import struct
from dataclasses import dataclass

SYNC = b"\xB5\x62"
MAX_PAYLOAD = 8192

# (クラス, ID)
NAV_PVT = (0x01, 0x07)
NAV_SAT = (0x01, 0x35)
RXM_SFRBX = (0x02, 0x13)
RXM_MEASX = (0x02, 0x14)
RXM_RAWX = (0x02, 0x15)
ACK_NAK = (0x05, 0x00)
ACK_ACK = (0x05, 0x01)
CFG_MSG = (0x06, 0x01)
CFG_RATE = (0x06, 0x08)
CFG_NAV5 = (0x06, 0x24)
CFG_VALSET = (0x06, 0x8A)
CFG_VALGET = (0x06, 0x8B)
MON_VER = (0x0A, 0x04)
MON_SPAN = (0x0A, 0x31)

MESSAGE_NAMES = {
    MON_SPAN: "MON-SPAN",
    NAV_PVT: "NAV-PVT", NAV_SAT: "NAV-SAT", RXM_SFRBX: "RXM-SFRBX", RXM_MEASX: "RXM-MEASX",
    RXM_RAWX: "RXM-RAWX", ACK_NAK: "ACK-NAK", ACK_ACK: "ACK-ACK", CFG_MSG: "CFG-MSG",
    CFG_RATE: "CFG-RATE", CFG_NAV5: "CFG-NAV5", CFG_VALSET: "CFG-VALSET",
    CFG_VALGET: "CFG-VALGET", MON_VER: "MON-VER",
}

# gnssId → (名称, 衛星名の接頭辞)
GNSS = {0: ("GPS", "G"), 1: ("SBAS", "S"), 2: ("GALILEO", "E"), 3: ("BEIDOU", "C"),
        4: ("IMES", "I"), 5: ("QZSS", "J"), 6: ("GLONASS", "R"), 7: ("NAVIC", "N")}

# (gnssId, sigId) → 信号名
SIGNALS = {
    (0, 0): "L1CA", (0, 3): "L2CL", (0, 4): "L2CM", (0, 6): "L5I", (0, 7): "L5Q",
    (1, 0): "L1CA",
    (2, 0): "E1C", (2, 1): "E1B", (2, 3): "E5aI", (2, 4): "E5aQ", (2, 5): "E5bI",
    (2, 6): "E5bQ", (2, 8): "E6B", (2, 9): "E6C", (2, 10): "E6A",
    (3, 0): "B1ID1", (3, 1): "B1ID2", (3, 2): "B2ID1", (3, 3): "B2ID2", (3, 4): "B3ID1",
    (3, 10): "B3ID2", (3, 5): "B1Cp", (3, 6): "B1Cd", (3, 7): "B2ap", (3, 8): "B2ad",
    (5, 0): "L1CA", (5, 1): "L1S", (5, 4): "L2CM", (5, 5): "L2CL", (5, 8): "L5I",
    (5, 9): "L5Q", (5, 10): "L6D", (5, 11): "L6E", (5, 12): "L1CB",
    (6, 0): "L1OF", (6, 2): "L2OF",
    (7, 0): "L5A",
}

# (gnssId, sigId) → 帯域(L1 / L2 / L5 / L6)
BANDS = ("L1", "L2", "L5", "L6")
SIGNAL_BANDS = {
    (0, 0): "L1", (0, 3): "L2", (0, 4): "L2", (0, 6): "L5", (0, 7): "L5",
    (1, 0): "L1",
    (2, 0): "L1", (2, 1): "L1", (2, 3): "L5", (2, 4): "L5", (2, 5): "L5", (2, 6): "L5",
    (2, 8): "L6", (2, 9): "L6", (2, 10): "L6",
    (3, 0): "L1", (3, 1): "L1", (3, 2): "L5", (3, 3): "L5", (3, 4): "L6", (3, 10): "L6",
    (3, 5): "L1", (3, 6): "L1", (3, 7): "L5", (3, 8): "L5",
    (5, 0): "L1", (5, 1): "L1", (5, 4): "L2", (5, 5): "L2", (5, 8): "L5", (5, 9): "L5",
    (5, 10): "L6", (5, 11): "L6", (5, 12): "L1",
    (6, 0): "L1", (6, 2): "L2",
    (7, 0): "L5",
}

# 測位状態の順位(グラフの縦軸と色分けに使う)
FIX_LABELS = ("No Fix", "DR", "Time", "2D", "3D", "GNSS+DR", "DGNSS", "RTK Float",
              "RTK Fixed")
_FIX_TYPE_RANKS = {0: 0, 1: 1, 2: 3, 3: 4, 4: 5, 5: 2}
# NAV-PVT flags3 の lastCorrectionAge(bit 1-4)
CORRECTION_AGES = {1: "1 秒未満", 2: "1〜2 秒", 3: "2〜5 秒", 4: "5〜10 秒", 5: "10〜15 秒",
                   6: "15〜20 秒", 7: "20〜30 秒", 8: "30〜45 秒", 9: "45〜60 秒",
                   10: "60〜90 秒", 11: "90〜120 秒", 12: "120 秒以上"}


def sat_name(gnss_id, sv_id):
    """衛星名(G05、J02 など)を返す。"""
    prefix = GNSS.get(gnss_id, ("?", "?"))[1]
    if gnss_id == 1 and sv_id >= 100:
        sv_id -= 100
    return f"{prefix}{sv_id:02d}"


def system_name(gnss_id):
    """衛星系の名称を返す。"""
    return GNSS.get(gnss_id, ("OTHER", "?"))[0]


def signal_name(gnss_id, sig_id):
    """信号名を返す。未知の信号は sigN とする。"""
    return SIGNALS.get((gnss_id, sig_id), f"sig{sig_id}")


def checksum(body):
    """クラスからペイロード末尾までの 8 ビット Fletcher チェックサムを返す。"""
    ck_a = ck_b = 0
    for byte in body:
        ck_a = (ck_a + byte) & 0xFF
        ck_b = (ck_b + ck_a) & 0xFF
    return bytes((ck_a, ck_b))


def build(msg, payload=b""):
    """UBX パケットを組み立てる。msg は (クラス, ID)。"""
    body = bytes(msg) + struct.pack("<H", len(payload)) + payload
    return SYNC + body + checksum(body)


class UbxStream:
    """バイト列から UBX フレームを取り出す。チェックサムが合わないものは捨てる。"""

    def __init__(self):
        self._buffer = bytearray()
        self.checksum_errors = 0

    def feed(self, data):
        """data を追加し、完成したフレームを [((クラス, ID), ペイロード)] で返す。"""
        self._buffer += data
        frames = []
        buf = self._buffer
        pos = 0
        while True:
            start = buf.find(SYNC, pos)
            if start < 0:
                # 末尾が 0xB5 なら次のデータと繋がる可能性がある
                pos = len(buf) - 1 if buf.endswith(SYNC[:1]) else len(buf)
                break
            if len(buf) - start < 6:
                pos = start
                break
            length = buf[start + 4] | (buf[start + 5] << 8)
            if length > MAX_PAYLOAD:
                pos = start + 2
                continue
            end = start + 8 + length
            if len(buf) < end:
                pos = start
                break
            if checksum(buf[start + 2:end - 2]) == buf[end - 2:end]:
                frames.append(((buf[start + 2], buf[start + 3]),
                               bytes(buf[start + 6:end - 2])))
                pos = end
            else:
                self.checksum_errors += 1
                pos = start + 2
        del buf[:pos]
        return frames


@dataclass
class Pvt:  # pylint: disable=too-many-instance-attributes
    """NAV-PVT の測位結果。"""
    itow: int
    utc: str
    time_valid: bool
    fix_rank: int
    num_sv: int
    latitude: float | None
    longitude: float | None
    height: float | None
    height_msl: float | None
    hacc: float | None
    vacc: float | None
    speed: float | None
    heading: float | None
    pdop: float
    tacc: int
    diff_soln: bool = False
    correction_age: int = 0     # flags3 の lastCorrectionAge(0 は情報なし)

    @property
    def correction_age_text(self):
        """補正情報の経過時間の表示文字列。"""
        return CORRECTION_AGES.get(self.correction_age, "—")

    @property
    def fix_label(self):
        """測位状態の表示名。"""
        return FIX_LABELS[self.fix_rank]


def parse_nav_pvt(payload):
    """NAV-PVT をデコードする。短すぎる場合は None。"""
    if len(payload) < 84:
        return None
    (itow, year, month, day, hour, minute, second, valid, tacc, _nano, fix_type, flags,
     _flags2, num_sv, lon, lat, height, hmsl, hacc, vacc, _vel_n, _vel_e, _vel_d, gspeed,
     head_mot, _sacc, _head_acc, pdop) = struct.unpack_from("<IHBBBBBBIiBBBBiiiiIIiiiiiIIH",
                                                            payload)
    fix_ok = bool(flags & 0x01)
    carrier = (flags >> 6) & 0x03
    if not fix_ok:
        rank = 0
    elif carrier == 2:
        rank = 8
    elif carrier == 1:
        rank = 7
    elif flags & 0x02 and fix_type in (2, 3, 4):
        rank = 6
    else:
        rank = _FIX_TYPE_RANKS.get(fix_type, 0)
    has_pos = fix_ok and fix_type in (1, 2, 3, 4)
    flags3 = struct.unpack_from("<H", payload, 78)[0]
    return Pvt(
        diff_soln=bool(flags & 0x02), correction_age=(flags3 >> 1) & 0x0F,
        itow=itow,
        utc=f"{year:04d}/{month:02d}/{day:02d} {hour:02d}:{minute:02d}:{second:02d}",
        time_valid=(valid & 0x03) == 0x03,
        fix_rank=rank, num_sv=num_sv,
        latitude=lat * 1e-7 if has_pos else None,
        longitude=lon * 1e-7 if has_pos else None,
        height=height / 1000 if has_pos else None,
        height_msl=hmsl / 1000 if has_pos else None,
        hacc=hacc / 1000 if has_pos else None,
        vacc=vacc / 1000 if has_pos else None,
        speed=gspeed / 1000 if has_pos else None,
        heading=head_mot * 1e-5 if has_pos else None,
        pdop=pdop * 0.01, tacc=tacc)


def parse_nav_sat(payload):
    """NAV-SAT を [dict] にする。仰角・方位が無効な衛星は None を入れる。"""
    if len(payload) < 8:
        return []
    count = min(payload[5], (len(payload) - 8) // 12)
    sats = []
    for index in range(count):
        gnss_id, sv_id, cno, elev, azim, _res, flags = struct.unpack_from(
            "<BBBbhhI", payload, 8 + index * 12)
        known = -90 <= elev <= 90 and (elev != 0 or azim != 0 or (flags & 0x07) >= 4)
        sats.append({"gnss": gnss_id, "sv": sv_id, "cno": cno,
                     "elev": elev if known else None, "azim": azim if known else None,
                     "used": bool(flags & 0x08), "quality": flags & 0x07})
    return sats


def parse_rxm_rawx(payload):
    """RXM-RAWX を (受信機時刻 TOW, [dict]) にする。"""
    if len(payload) < 16:
        return None, []
    rcv_tow = struct.unpack_from("<d", payload)[0]
    count = min(payload[11], (len(payload) - 16) // 32)
    meas = []
    for index in range(count):
        off = 16 + index * 32
        meas.append({"gnss": payload[off + 20], "sv": payload[off + 21],
                     "sig": payload[off + 22], "cno": payload[off + 26],
                     "pr_valid": bool(payload[off + 30] & 0x01),
                     "cp_valid": bool(payload[off + 30] & 0x02)})
    return rcv_tow, meas


def parse_rxm_measx(payload):
    """RXM-MEASX を [dict] にする。"""
    if len(payload) < 44:
        return []
    count = min(payload[34], (len(payload) - 44) // 24)
    meas = []
    for index in range(count):
        off = 44 + index * 24
        gnss_id, sv_id, cno, mpath = struct.unpack_from("<BBBB", payload, off)
        whole, frac, code_phase, int_phase, rmse = struct.unpack_from("<HHIBB", payload,
                                                                      off + 12)
        meas.append({"gnss": gnss_id, "sv": sv_id, "cno": cno, "mpath": mpath,
                     "wChips": whole, "fChips": frac, "cPhase": code_phase,
                     "iPhase": int_phase, "rmse": rmse})
    return meas


def parse_rxm_sfrbx(payload):
    """RXM-SFRBX を dict にする。subframe は先頭ワードから得たサブフレーム番号(GPS 系)。"""
    if len(payload) < 8:
        return None
    gnss_id, sv_id, sig_id, _freq, num_words = struct.unpack_from("<BBBBB", payload)
    subframe = 0
    if num_words >= 2 and len(payload) >= 16 and gnss_id in (0, 5) and sig_id == 0:
        # GPS / QZSS L1C/A の LNAV: 2 ワード目(HOW)の bit 8-10 がサブフレーム番号
        subframe = (struct.unpack_from("<I", payload, 12)[0] >> 8) & 0x07
    return {"gnss": gnss_id, "sv": sv_id, "sig": sig_id, "subframe": subframe}


def parse_mon_ver(payload):
    """MON-VER を dict にする。gen は M8 / F9 / X20 / Unknown。"""
    if len(payload) < 40:
        return None

    def text(raw):
        return raw.split(b"\x00")[0].decode("ascii", errors="ignore")

    software, hardware = text(payload[:30]), text(payload[30:40])
    extensions = [text(payload[i:i + 30]) for i in range(40, len(payload) - 29, 30)]
    fields = dict(ext.split("=", 1) for ext in extensions if "=" in ext)
    module = fields.get("MOD", "")
    try:
        protver = float(fields.get("PROTVER", "0").strip())
    except ValueError:
        protver = 0.0
    if "X20" in module or 50.0 <= protver < 60.0:
        gen = "X20"
    elif "00080000" in hardware or "M8" in module or 15.0 <= protver <= 23.01:
        gen = "M8"
    elif "F9" in module or 27.0 <= protver < 50.0:
        gen = "F9"
    else:
        gen = "Unknown"
    return {"gen": gen, "sw": software, "hw": hardware, "module": module,
            "protver": fields.get("PROTVER", "").strip(), "extensions": extensions}


SPAN_BINS = 256
SPAN_BLOCK_SIZE = 272
SPAN_DB_SCALE = 0.25    # spectrum の 1 単位 [dB]


def parse_mon_span(payload):
    """MON-SPAN(スペクトラムアナライザ)を RF ブロックごとの dict のリストにする。

    周波数 [Hz] は center + span * (i - 128) / 256。レベルは 0.25 dB 単位。
    """
    if len(payload) < 4:
        return []
    count = min(payload[1], (len(payload) - 4) // SPAN_BLOCK_SIZE)
    blocks = []
    for index in range(count):
        off = 4 + index * SPAN_BLOCK_SIZE
        span, res, center, pga = struct.unpack_from("<IIIB", payload, off + SPAN_BINS)
        blocks.append({
            "spectrum": [v * SPAN_DB_SCALE for v in payload[off:off + SPAN_BINS]],
            "span": span, "res": res, "center": center, "pga": pga})
    return blocks


def span_frequencies(block):
    """MON-SPAN の 1 ブロックの各点の周波数 [MHz] を返す。"""
    return [(block["center"] + block["span"] * (i - SPAN_BINS // 2) / SPAN_BINS) / 1e6
            for i in range(SPAN_BINS)]


# ---- 設定(F9 / X20: CFG-VALGET / VALSET) ----

LAYER_RAM = 0x01
LAYER_BBR = 0x02
LAYER_FLASH = 0x04
_KEY_SIZES = {1: 1, 2: 1, 3: 2, 4: 4, 5: 8}


def key_size(key):
    """設定キーの値のバイト数を返す(キー ID の bit 28-30 で決まる)。"""
    return _KEY_SIZES.get((key >> 28) & 0x07, 0)


def build_valget(keys, layer=0):
    """CFG-VALGET の要求を作る。layer 0 は RAM。"""
    payload = bytes((0, layer, 0, 0)) + b"".join(struct.pack("<I", key) for key in keys)
    return build(CFG_VALGET, payload)


def build_valset(values, layers=LAYER_RAM, signed=()):
    """CFG-VALSET を作る。values は {キー: 整数値}、signed は符号付きで詰めるキー。"""
    payload = bytes((0, layers, 0, 0))
    for key, value in values.items():
        size = key_size(key)
        payload += struct.pack("<I", key)
        payload += int(value).to_bytes(size, "little", signed=key in signed)
    return build(CFG_VALSET, payload)


def parse_valget(payload):
    """CFG-VALGET の応答を {キー: 値のバイト列} にする。"""
    values = {}
    pos = 4
    while pos + 4 <= len(payload):
        key = struct.unpack_from("<I", payload, pos)[0]
        size = key_size(key)
        if size == 0 or pos + 4 + size > len(payload):
            break
        values[key] = bytes(payload[pos + 4:pos + 4 + size])
        pos += 4 + size
    return values


# ---- 設定(M8: CFG-NAV5 / CFG-RATE / CFG-MSG) ----

NAV5_MASKS = {"dynmodel": 0x0001, "minelev": 0x0002, "fixmode": 0x0004, "cno": 0x0100}


def parse_nav5(payload):
    """CFG-NAV5 を dict にする。"""
    if len(payload) < 36:
        return None
    return {"dynmodel": payload[2], "fixmode": payload[3],
            "minelev": struct.unpack_from("<b", payload, 12)[0],
            "minsvs": payload[24], "mincno": payload[25]}


def build_nav5(base, changes):
    """読み出した CFG-NAV5 のペイロード base に changes を反映したパケットを作る。"""
    payload = bytearray(base[:36])
    mask = 0
    if "dynmodel" in changes:
        payload[2] = changes["dynmodel"]
        mask |= NAV5_MASKS["dynmodel"]
    if "fixmode" in changes:
        payload[3] = changes["fixmode"]
        mask |= NAV5_MASKS["fixmode"]
    if "minelev" in changes:
        struct.pack_into("<b", payload, 12, changes["minelev"])
        mask |= NAV5_MASKS["minelev"]
    if "minsvs" in changes or "mincno" in changes:
        payload[24] = changes.get("minsvs", payload[24])
        payload[25] = changes.get("mincno", payload[25])
        mask |= NAV5_MASKS["cno"]
    struct.pack_into("<H", payload, 0, mask)
    return build(CFG_NAV5, bytes(payload))


def parse_rate(payload):
    """CFG-RATE を dict にする。"""
    if len(payload) < 6:
        return None
    meas, nav, timeref = struct.unpack_from("<HHH", payload)
    return {"meas": meas, "nav": nav, "timeref": timeref}


def build_rate(meas, nav, timeref):
    """CFG-RATE を作る。"""
    return build(CFG_RATE, struct.pack("<HHH", meas, nav, timeref))


def build_cfg_msg(msg, rate):
    """CFG-MSG(接続中のポートへの出力レート設定)を作る。"""
    return build(CFG_MSG, bytes((*msg, rate)))
