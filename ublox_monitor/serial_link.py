"""COM ポートの入出力、UBX ログの再生と記録、受信データの振り分け。"""
import os
import queue
import threading
import time

import serial
from serial.tools import list_ports

from . import ubx
from .state import State

BAUD_RATES = (9600, 19200, 38400, 57600, 115200, 230400, 460800, 921600)
DEFAULT_BAUD = 921600
UBLOX_USB_VID = 0x1546
READ_CHUNK = 4096
MAX_EPOCH_GAP = 5.0
SENDS_PER_LOOP = 4
# 接続時に出力させるメッセージ
OUTPUT_MESSAGES = (ubx.NAV_PVT, ubx.NAV_SAT, ubx.RXM_RAWX, ubx.RXM_SFRBX, ubx.RXM_MEASX)
# CFG-MSGOUT のキー(I2C 用)。ポートごとのキーは PORT_OFFSETS を足す
MSGOUT_KEYS = {ubx.NAV_PVT: 0x20910006, ubx.NAV_SAT: 0x20910015, ubx.RXM_RAWX: 0x209102A4,
               ubx.RXM_SFRBX: 0x20910231, ubx.RXM_MEASX: 0x20910204}
PORT_OFFSETS = {"UART1": 1, "UART2": 2, "USB": 3}


def available_ports():
    """[(デバイス名, 説明, 受信機側のポート名の推定)] を返す。"""
    ports = []
    for port in sorted(list_ports.comports(), key=lambda p: p.device):
        ports.append((port.device, port.description or "",
                      "USB" if port.vid == UBLOX_USB_VID else "UART1"))
    return ports


def output_packets(gen, receiver_port, rate=1):
    """表示に使うメッセージの出力を有効(rate=0 で無効)にするパケットを返す。RAM のみ変更する。

    未対応のメッセージが 1 つあっても他が通るよう、メッセージごとに分けて送る。
    """
    packets = []
    if gen in ("F9", "X20", "Unknown"):
        offset = PORT_OFFSETS[receiver_port]
        packets += [ubx.build_valset({MSGOUT_KEYS[msg] + offset: rate})
                    for msg in OUTPUT_MESSAGES]
    if gen in ("M8", "Unknown"):
        packets += [ubx.build_cfg_msg(msg, rate) for msg in OUTPUT_MESSAGES]
    return packets


class UbxLogger:
    """受信したバイト列をそのまま .ubx に保存する。既存のファイルは上書きしない。"""

    def __init__(self, folder):
        os.makedirs(folder, exist_ok=True)
        self.path = os.path.join(folder, f"ublox_{time.strftime('%Y%m%d_%H%M%S')}.ubx")
        self._file = open(self.path, "xb")  # pylint: disable=consider-using-with
        self.size = 0

    def write(self, data):
        """data を書き込む。"""
        self._file.write(data)
        self.size += len(data)

    def close(self):
        """ファイルを閉じる。"""
        self._file.close()


class Pipeline:  # pylint: disable=too-many-instance-attributes
    """受信データを分離・デコードして集計と記録へ流す。"""

    def __init__(self, clock=time.time):
        self.stream = ubx.UbxStream()
        self.state = State(clock)
        self.events = queue.Queue()
        self.version = None
        self.cfg_values = {}    # 設定キー → 値のバイト列(CFG-VALGET)
        self.nav5 = None        # CFG-NAV5 のペイロード(M8)
        self.rate = None        # CFG-RATE(M8)
        self.acks = []          # [(成功か, (クラス, ID))]
        self.spectrum = None    # 最新の MON-SPAN [ブロック]
        self.spectrum_count = 0
        self.on_epoch = None    # エポックが進んだときに呼ぶ関数(引数は進んだ秒数)
        self._epoch_msg = None
        self._epoch_tow = None
        self._logger = None
        self._log_lock = threading.Lock()

    def log(self, text):
        """ログ欄に出す 1 行を積む。"""
        self.events.put(f"{time.strftime('%H:%M:%S')}  {text}")

    def start_logging(self, folder):
        """記録を始める。"""
        with self._log_lock:
            if self._logger is None:
                self._logger = UbxLogger(folder)
                self.log(f"記録開始: {self._logger.path}")

    def stop_logging(self):
        """記録を止める。"""
        with self._log_lock:
            if self._logger is not None:
                self._logger.close()
                self.log(f"記録停止: {self._logger.path}")
                self._logger = None

    def log_status(self):
        """記録中なら (パス, バイト数)、そうでなければ None。"""
        with self._log_lock:
            return (self._logger.path, self._logger.size) if self._logger else None

    def feed(self, data):
        """受信したバイト列を処理する。"""
        with self._log_lock:
            if self._logger is not None:
                self._logger.write(data)
        for msg, payload in self.stream.feed(data):
            self.state.count(msg)
            self._handle(msg, payload)

    def _epoch(self, msg, tow):
        """エポックの進みを on_epoch に知らせる。最初に届いた種類のメッセージを基準にする。"""
        if self._epoch_msg is None:
            self._epoch_msg = msg
        if msg != self._epoch_msg:
            return
        previous, self._epoch_tow = self._epoch_tow, tow
        if previous is not None and self.on_epoch is not None:
            step = tow - previous
            self.on_epoch(step if 0 < step <= MAX_EPOCH_GAP else 0.0)

    def _handle(self, msg, payload):  # pylint: disable=too-many-branches
        """1 メッセージを処理する。"""
        if msg == ubx.NAV_PVT:
            pvt = ubx.parse_nav_pvt(payload)
            if pvt is not None:
                self._epoch(msg, pvt.itow / 1000)
                self.state.update_pvt(pvt)
        elif msg == ubx.NAV_SAT:
            self.state.update_sat(ubx.parse_nav_sat(payload))
        elif msg == ubx.RXM_RAWX:
            tow, meas = ubx.parse_rxm_rawx(payload)
            if tow is not None:
                self._epoch(msg, tow)
            self.state.update_rawx(meas)
        elif msg == ubx.RXM_MEASX:
            self.state.update_measx(ubx.parse_rxm_measx(payload))
        elif msg == ubx.RXM_SFRBX:
            frame = ubx.parse_rxm_sfrbx(payload)
            if frame is not None:
                self.state.update_sfrbx(frame)
        elif msg == ubx.MON_VER:
            version = ubx.parse_mon_ver(payload)
            if version is not None:
                self.version = version
                self.log(f"受信機: {version['gen']} {version['module']} HW {version['hw']} "
                         f"SW {version['sw']} PROTVER {version['protver']}")
        elif msg == ubx.MON_SPAN:
            blocks = ubx.parse_mon_span(payload)
            if blocks:
                self.spectrum = blocks
                self.spectrum_count += 1
        elif msg == ubx.CFG_VALGET:
            self.cfg_values.update(ubx.parse_valget(payload))
        elif msg == ubx.CFG_NAV5 and len(payload) >= 36:
            self.nav5 = bytes(payload)
        elif msg == ubx.CFG_RATE:
            self.rate = ubx.parse_rate(payload) or self.rate
        elif msg in (ubx.ACK_ACK, ubx.ACK_NAK) and len(payload) >= 2:
            target = (payload[0], payload[1])
            self.acks.append((msg == ubx.ACK_ACK, target))
            # 未対応キーの読み出しは NAK になるのが普通なので、ログには出さない
            if target != ubx.CFG_VALGET:
                name = ubx.MESSAGE_NAMES.get(target, f"{target[0]:02X}-{target[1]:02X}")
                self.log(f"{'ACK' if msg == ubx.ACK_ACK else 'NAK(拒否)'}: {name}")


class SerialSource(threading.Thread):
    """COM ポートから受信し、送信待ちのパケットを送るスレッド。"""

    def __init__(self, pipeline, port, baud):
        super().__init__(daemon=True)
        self.pipeline = pipeline
        self.port = port
        self.baud = baud
        self.error = None
        self._outbox = queue.Queue()
        self._stop_event = threading.Event()

    def send(self, packets):
        """送信するパケットを積む。"""
        for packet in packets:
            self._outbox.put(packet)

    def stop(self):
        """スレッドを止めて終了を待つ。"""
        self._stop_event.set()
        if self.is_alive():
            self.join(timeout=2.0)

    def run(self):
        try:
            with serial.Serial(self.port, self.baud, timeout=0.05) as port:
                self.pipeline.log(f"接続: {self.port} {self.baud} bps")
                while not self._stop_event.is_set():
                    data = port.read(max(1, port.in_waiting))
                    if data:
                        self.pipeline.feed(data)
                    for _ in range(SENDS_PER_LOOP):
                        try:
                            port.write(self._outbox.get_nowait())
                        except queue.Empty:
                            break
        except (OSError, serial.SerialException) as exc:
            self.error = str(exc)


class ReplayClock:
    """再生用の時計。データの時刻の進みに合わせて進める。"""

    def __init__(self):
        self.value = 0.0

    def __call__(self):
        return self.value


class ReplaySource(threading.Thread):
    """UBX ログを再生するスレッド。speed は再生速度の倍率で、0 は待たずに読む。"""

    def __init__(self, pipeline, path, speed, clock):
        super().__init__(daemon=True)
        self.pipeline = pipeline
        self.path = path
        self.speed = speed
        self.clock = clock
        self.error = None
        self.size = os.path.getsize(path)
        self.position = 0
        self._stop_event = threading.Event()
        pipeline.on_epoch = self._on_epoch

    def _on_epoch(self, step):
        """エポックが進んだ分だけ時計を進め、再生速度に合わせて待つ。"""
        self.clock.value += step
        if self.speed > 0 and step > 0:
            self._stop_event.wait(step / self.speed)

    def send(self, _packets):
        """再生中は送信しない。"""

    def stop(self):
        """スレッドを止めて終了を待つ。"""
        self._stop_event.set()
        if self.is_alive():
            self.join(timeout=2.0)

    def run(self):
        try:
            with open(self.path, "rb") as file:
                # エポックごとに待てるよう、小さく区切って読む
                while not self._stop_event.is_set():
                    data = file.read(READ_CHUNK if self.speed == 0 else 256)
                    if not data:
                        break
                    self.position += len(data)
                    self.pipeline.feed(data)
        except OSError as exc:
            self.error = str(exc)
