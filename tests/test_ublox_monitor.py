"""ublox_monitor のテスト。実行: python -m pytest tests"""
import os
import struct

import pytest

from ublox_monitor import serial_link as link
from ublox_monitor import settings, ubx
from ublox_monitor.map_view import thin
from ublox_monitor.state import State

from . import ubx_samples as samples

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")


def replay(data, chunk=97):
    """data を再生用の時計で Pipeline に流す。"""
    clock = link.ReplayClock()
    pipeline = link.Pipeline(clock)
    pipeline.on_epoch = lambda step: setattr(clock, "value", clock.value + step)
    for pos in range(0, len(data), chunk):
        pipeline.feed(data[pos:pos + chunk])
    return pipeline


# ---- ubx ----

def test_build_matches_known_packet():
    assert ubx.build(ubx.MON_VER) == bytes.fromhex("b5620a0400000e34")


def test_stream_splits_frames_across_chunks_and_skips_garbage():
    packets = [ubx.build(ubx.NAV_PVT, bytes(92)), ubx.build(ubx.MON_VER), samples.nav_sat(1)]
    data = b"$GNGGA,junk\r\n" + packets[0] + b"\xb5\x00\xb5" + packets[1] + packets[2]
    stream = ubx.UbxStream()
    frames = []
    for pos in range(0, len(data), 7):
        frames += stream.feed(data[pos:pos + 7])
    assert [msg for msg, _payload in frames] == [ubx.NAV_PVT, ubx.MON_VER, ubx.NAV_SAT]
    assert stream.checksum_errors == 0


def test_stream_rejects_bad_checksum_and_recovers():
    broken = bytearray(ubx.build(ubx.NAV_PVT, bytes(92)))
    broken[20] ^= 0xFF
    stream = ubx.UbxStream()
    frames = stream.feed(bytes(broken) + ubx.build(ubx.MON_VER))
    assert [msg for msg, _payload in frames] == [ubx.MON_VER]
    assert stream.checksum_errors == 1


def test_nav_pvt_fields():
    (_msg, payload), = ubx.UbxStream().feed(samples.nav_pvt(123000, 35.5, 139.25, carrier=2))
    pvt = ubx.parse_nav_pvt(payload)
    assert pvt.fix_label == "RTK Fixed"
    assert pvt.latitude == pytest.approx(35.5)
    assert pvt.longitude == pytest.approx(139.25)
    assert pvt.height == pytest.approx(45.123)
    assert pvt.height_msl == pytest.approx(8.456)
    assert pvt.hacc == pytest.approx(0.014)
    assert pvt.speed == pytest.approx(1.25)
    assert pvt.heading == pytest.approx(90.0)
    assert pvt.pdop == pytest.approx(1.2)
    assert pvt.num_sv == 12 and pvt.time_valid


@pytest.mark.parametrize("carrier, flags, label", [
    (1, 0x03, "RTK Float"), (0, 0x03, "DGNSS"), (0, 0x01, "3D"), (2, 0x00, "No Fix")])
def test_nav_pvt_fix_label(carrier, flags, label):
    payload = bytearray(ubx.UbxStream().feed(samples.nav_pvt(0))[0][1])
    payload[21] = flags | (carrier << 6)
    pvt = ubx.parse_nav_pvt(bytes(payload))
    assert pvt.fix_label == label
    assert (pvt.latitude is None) == (label == "No Fix")


def test_sfrbx_subframe_only_for_lnav():
    frame = ubx.parse_rxm_sfrbx(ubx.UbxStream().feed(samples.rxm_sfrbx(0, 5, 4))[0][1])
    assert frame == {"gnss": 0, "sv": 5, "sig": 0, "subframe": 4}
    galileo = ubx.parse_rxm_sfrbx(ubx.UbxStream().feed(samples.rxm_sfrbx(2, 5, 4))[0][1])
    assert galileo["subframe"] == 0


@pytest.mark.parametrize("module, protver, hardware, gen", [
    ("ZED-F9P", "27.50", "00190000", "F9"), ("ZED-X20P", "50.10", "000B0000", "X20"),
    ("NEO-M8T", "22.00", "00080000", "M8")])
def test_mon_ver_generation(module, protver, hardware, gen):
    packet = samples.mon_ver(module, protver, hardware)
    version = ubx.parse_mon_ver(ubx.UbxStream().feed(packet)[0][1])
    assert version["gen"] == gen and version["module"] == module


def test_valget_values_have_key_dependent_sizes():
    payload = bytes(4) + struct.pack("<IB", 0x201100A4, 0xF6) + struct.pack(
        "<IH", 0x30210001, 200) + struct.pack("<IB", 0x1031001F, 1) + struct.pack(
            "<II", 0x40520001, 460800)
    values = ubx.parse_valget(payload)
    assert values == {0x201100A4: b"\xf6", 0x30210001: b"\xc8\x00", 0x1031001F: b"\x01",
                      0x40520001: struct.pack("<I", 460800)}


def test_valset_round_trips_through_valget_parser():
    packet = ubx.build_valset({0x201100A4: -10, 0x30210001: 200}, ubx.LAYER_RAM,
                              signed={0x201100A4})
    (msg, payload), = ubx.UbxStream().feed(packet)
    assert msg == ubx.CFG_VALSET and payload[:4] == bytes((0, 1, 0, 0))
    assert ubx.parse_valget(payload) == {0x201100A4: b"\xf6", 0x30210001: b"\xc8\x00"}


def test_nav5_sets_only_the_masks_of_changed_fields():
    base = bytearray(36)
    base[2], base[3], base[12], base[24], base[25] = 0, 3, 5, 0, 0
    packet = ubx.build_nav5(bytes(base), {"minelev": -5, "mincno": 20})
    payload = ubx.UbxStream().feed(packet)[0][1]
    assert struct.unpack_from("<H", payload)[0] == 0x0102
    assert ubx.parse_nav5(payload) == {"dynmodel": 0, "fixmode": 3, "minelev": -5,
                                       "minsvs": 0, "mincno": 20}


def test_mon_span_blocks_and_frequencies():
    (msg, payload), = ubx.UbxStream().feed(samples.mon_span())
    assert msg == ubx.MON_SPAN
    blocks = ubx.parse_mon_span(payload)
    assert len(blocks) == 2
    block = blocks[0]
    assert (block["span"], block["res"], block["center"], block["pga"]) == (
        128_000_000, 500_000, 1_583_400_000, 54)
    assert len(block["spectrum"]) == 256
    assert block["spectrum"][0] == payload[4] * 0.25
    freqs = ubx.span_frequencies(block)
    assert freqs[128] == pytest.approx(1583.4)
    assert freqs[0] == pytest.approx(1583.4 - 64)
    assert freqs[1] - freqs[0] == pytest.approx(0.5)
    # 山は L1 帯の信号の中心周波数のどれかにある
    peak = max(range(256), key=lambda i: block["spectrum"][i])
    assert min(abs(freqs[peak] - f) for f in (1575.42, 1561.098, 1602.0)) < 2


def test_spectrum_trace_holds_max_and_mean():
    from ublox_monitor.spectrum_view import SpectrumTrace
    base = {"center": 1, "span": 2, "res": 1, "pga": 0}
    trace = SpectrumTrace()
    trace.add({**base, "spectrum": [1.0, 4.0]})
    trace.add({**base, "spectrum": [3.0, 2.0]})
    assert trace.current == [3.0, 2.0] and trace.maximum == [3.0, 4.0]
    assert trace.mean == [2.0, 3.0] and trace.count == 2
    trace.add({**base, "center": 5, "spectrum": [0.0, 0.0]})   # 周波数が変われば集計し直す
    assert trace.maximum == [0.0, 0.0] and trace.count == 1


# ---- settings ----

def test_current_values_from_keys_and_changed_only():
    cfg = {0x201100A4: b"\xf6", 0x20110021: b"\x04", 0x1031001F: b"\x01"}
    values = settings.current_values("F9", cfg, None, None)
    assert values == {"minelev": -10, "dynmodel": 4, "GPS": 1}
    chosen = {"minelev": 15, "dynmodel": 4, "GPS": 1, "mincno": 30}
    assert settings.changed(values, chosen) == {"minelev": 15}


def test_write_packets_for_f9_use_one_valset():
    packets = settings.write_packets("F9", {"minelev": -3, "GPS_L5": 1}, ubx.LAYER_RAM,
                                     None, None)
    (msg, payload), = ubx.UbxStream().feed(b"".join(packets))
    assert msg == ubx.CFG_VALSET
    assert ubx.parse_valget(payload) == {0x201100A4: b"\xfd", 0x10310004: b"\x01"}
    assert settings.restarts_tracking({"GPS_L5": 1})
    assert not settings.restarts_tracking({"minelev": -3})


def test_m8_reads_and_writes_legacy_messages():
    nav5 = bytearray(36)
    nav5[2], nav5[3], nav5[12], nav5[24], nav5[25] = 4, 3, 10, 3, 25
    rate = {"meas": 1000, "nav": 1, "timeref": 1}
    values = settings.current_values("M8", {}, bytes(nav5), rate)
    assert values == {"dynmodel": 4, "fixmode": 3, "minelev": 10, "mincno": 25, "minsvs": 3,
                      "measrate": 1000, "navrate": 1}
    packets = settings.write_packets("M8", {"dynmodel": 2, "measrate": 200}, ubx.LAYER_RAM,
                                     bytes(nav5), rate)
    frames = ubx.UbxStream().feed(b"".join(packets))
    assert [msg for msg, _p in frames] == [ubx.CFG_NAV5, ubx.CFG_RATE]
    assert frames[0][1][2] == 2 and struct.unpack_from("<H", frames[0][1])[0] == 0x0001
    assert ubx.parse_rate(frames[1][1]) == {"meas": 200, "nav": 1, "timeref": 1}


def test_read_packets_poll_each_key_separately():
    packets = settings.read_packets("F9")
    assert len(packets) == len(settings.ITEMS)
    frames = ubx.UbxStream().feed(b"".join(packets))
    assert all(msg == ubx.CFG_VALGET and len(payload) == 8 for msg, payload in frames)
    assert len({item.key for item in settings.ITEMS}) == len(settings.ITEMS)


# ---- serial_link / state ----

def test_output_packets_target_the_connected_port():
    usb = ubx.UbxStream().feed(b"".join(link.output_packets("F9", "USB")))
    assert len(usb) == len(link.OUTPUT_MESSAGES)
    assert ubx.parse_valget(usb[0][1]) == {0x20910009: b"\x01"}     # NAV-PVT USB
    uart = ubx.UbxStream().feed(b"".join(link.output_packets("F9", "UART1")))
    assert ubx.parse_valget(uart[2][1]) == {0x209102A5: b"\x01"}    # RXM-RAWX UART1
    m8 = ubx.UbxStream().feed(b"".join(link.output_packets("M8", "USB")))
    assert m8[0] == (ubx.CFG_MSG, bytes((0x01, 0x07, 1)))


def test_pipeline_aggregates_a_log():
    pipeline = replay(samples.log(seconds=20))
    snap = pipeline.state.snapshot()
    assert pipeline.version["gen"] == "F9"
    assert pipeline.stream.checksum_errors == 0
    assert len(snap["pvt_history"]) == 20
    assert snap["now"] == pytest.approx(19.0)
    assert len(snap["satellites"]) == len(samples.SATELLITES)
    g05 = snap["satellites"]["G05"]
    assert g05["azim"] == 40 and g05["elev"] == 62 and g05["used"]
    assert set(g05["bands"]) == {"L1", "L2"}
    assert len(snap["signals"]["GPS L1CA"]) == 4
    assert snap["counts"][ubx.NAV_PVT] == 20
    monitor = pipeline.state.monitor_snapshot()
    assert monitor["seen"]["GPS"] == [5, 13, 15, 24]
    assert monitor["tracks"]["QZSS"][2]["rmse"] == 10 + 9 * 3
    assert monitor["sfrbx"]["GPS"][5] == {"count": 20, "subframes": {1, 2, 3, 4, 5}}
    trend = pipeline.state.trend_snapshot()
    assert len(trend["history"]["GPS"][5]) == 20 * 3    # RAWX 2 信号 + MEASX


def test_state_limits_monitor_slots_and_drops_stale_signals():
    clock = link.ReplayClock()
    state = State(clock)
    state.update_rawx([{"gnss": 5, "sv": sv, "sig": 0, "cno": 40, "pr_valid": True,
                        "cp_valid": True} for sv in range(1, 10)])
    assert state.monitor_snapshot()["seen"]["QZSS"] == [1, 2, 3, 4, 5, 6]
    assert len(state.snapshot()["satellites"]) == 9
    clock.value = 10.0
    assert state.snapshot()["satellites"] == {}


def test_logger_writes_raw_bytes(tmp_path):
    pipeline = link.Pipeline()
    pipeline.start_logging(str(tmp_path))
    data = samples.log(seconds=2)
    pipeline.feed(data)
    path, size = pipeline.log_status()
    pipeline.stop_logging()
    assert size == len(data) and pipeline.log_status() is None
    with open(path, "rb") as file:
        assert file.read() == data


def test_thin_keeps_last_point():
    fixes = [(35.0 + i * 1e-6, 139.0) for i in range(5001)]
    points = thin(fixes)
    assert len(points) <= 2501 and points[-1] == [round(fixes[-1][0], 7), 139.0]


# ---- GUI ----

@pytest.fixture(scope="module")
def qt_app():
    from PySide6.QtCore import QSettings
    from PySide6.QtWidgets import QApplication
    app = QApplication.instance() or QApplication([])
    # 利用者の設定に書き込まないよう、テスト用の名前にする
    app.setOrganizationName("ublox_monitor_test")
    app.setApplicationName("ublox_monitor_test")
    yield app
    QSettings().clear()


def test_main_window_shows_a_replayed_log(qt_app, tmp_path):
    from ublox_monitor.app import MainWindow
    path = tmp_path / "sample.ubx"
    path.write_bytes(samples.log(seconds=20))
    window = MainWindow()
    window.timer.stop()
    window.start_replay(str(path), 0.0)
    window.source.join(timeout=10)
    for index in range(window.tabs.count()):
        window.tabs.setCurrentIndex(index)
        for _ in range(4):
            window.refresh()
        qt_app.processEvents()
    window.tabs.setCurrentIndex(0)
    for _ in range(4):
        window.refresh()
    assert window.mode_label.text() in ("RTK Fixed", "RTK Float")
    assert window.status["lat"].text().startswith("35.68")
    assert window.signal_table.rowCount() > 0
    assert "F9" in window.console.toPlainText()
    assert window.pipeline.spectrum_count == 20
    assert len(window.spectrum_panel.plot.traces) == 2
    window.close()


class FakeSerial(link.SerialSource):
    """送信内容を覚えるだけの、接続中に見えるデータ源。"""

    def __init__(self):
        super().__init__(link.Pipeline(), "COM99", 115200)
        self.sent = []

    def is_alive(self):
        return True

    def send(self, packets):
        self.sent += packets

    def stop(self):
        pass


def test_corrections_are_forwarded_and_rtcm_input_enabled(qt_app):
    from ublox_monitor.app import MainWindow
    window = MainWindow()
    window.timer.stop()
    fake = FakeSerial()
    window.source = fake
    window.receiver_port_box.setCurrentIndex(window.receiver_port_box.findData("UART2"))
    window._forward_correction(b"\xd3\x00\x01")
    assert fake.sent == [b"\xd3\x00\x01"]
    window.correction_panel.forward_check.setChecked(False)
    window._update_correction()
    window._forward_correction(b"\xd3")
    assert fake.sent == [b"\xd3\x00\x01"]
    window.pipeline.version = {"gen": "F9"}
    window.enable_rtcm_input()
    (msg, payload), = ubx.UbxStream().feed(fake.sent[-1])
    assert msg == ubx.CFG_VALSET
    assert ubx.parse_valget(payload) == {0x10750004: b"\x01"}     # UART2 の RTCM3 入力
    window.source = None
    window.close()


def test_settings_panel_reports_only_changes(qt_app):
    sent = []
    panel = settings.SettingsPanel(lambda: None, lambda changes, layers: sent.append(
        (changes, layers)))
    values = {"minelev": 10, "dynmodel": 4, "GPS": 1, "GPS_L1CA": 1, "GAL": 1}
    panel.update_from(values, "F9")
    panel._send()
    assert not sent and panel.status_label.text() == "変更はありません。"
    panel._inputs["minelev"].setValue(15)
    panel._checks["GAL"].setChecked(False)
    panel._send()
    assert sent == [({"minelev": 15, "GAL": 0}, ubx.LAYER_RAM)]
    assert not panel._checks["GPS_L5"].isEnabled()
