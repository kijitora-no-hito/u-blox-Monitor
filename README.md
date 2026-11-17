# u-blox-Monitor **Version:** 0.2.0 
**Author:** Rei Furukawa (kijitora-no-hito) 

This software is a logging, visualization, configuration, and analysis tool for u-blox receivers (M8, F9, and X20 series), implemented in Python. It shows the positioning status, satellites, and the track on a map, lets you change the receiver configuration, and records RAW data (e.g., MEASX, SFRBX, RAWX) so that receiver performance can be analyzed through graphical visualization.

**License:**
This software is released under the [MIT License](LICENSE).

**Contribution Policy**
This is a personal project and is not open for contributions.
Issues are disabled.

- Pull requests will be closed without review.
- No support or feature requests will be provided.

Feel free to use the code as you like, but please do not expect any response or maintenance.

**Exception**
I may occasionally respond to requests from personal contacts (e.g., discussion through at academic conferences in Japan related to navigation), but this is strictly on a private and case-by-case basis.

## How to Use

### 1. Requirements
- Windows 11
- Python 3.11 or later

Install the required packages:

```
pip install -r requirements.txt
```

(PySide6 and pyserial)

---

### 2. Setup

- Install the appropriate u-blox USB driver on your Windows PC
- Connect your u-blox receiver via USB

---

### 3. Run

Start the application:

```
python ubxMonitor.py
```

(`python -m ublox_monitor` also works.)

---

### 4. Usage

1. Select the serial port and baud rate, then click **接続** (Connect).
   - The receiver generation (M8 / F9 / X20) is detected from UBX-MON-VER.
   - With **接続時に設定** checked (default), the output of NAV-PVT, NAV-SAT, RXM-RAWX,
     RXM-SFRBX and RXM-MEASX is enabled on the connected port. Only RAM is changed, so the
     receiver returns to its saved configuration after a power cycle.
     Uncheck it if you do not want the receiver to be touched on connect;
     **出力メッセージを設定** sends the same configuration manually.
   - **受信機側** is the receiver port the PC is connected to (USB / UART1 / UART2).
     "自動" assumes USB for a native u-blox USB port and UART1 for a USB-serial adapter.
2. Click **記録開始** to record the received bytes to `logs/ublox_YYYYMMDD_HHMMSS.ubx`
   (the folder can be changed with **参照**). Existing files are never overwritten.
3. **UBX再生** replays a recorded `.ubx` file at 1x / 10x / 60x / maximum speed.

### 5. Tabs

| Tab | Contents |
|---|---|
| **メイン** | General receiver view: fix status (No Fix / 3D / DGNSS / RTK Float / RTK Fixed), time, position, accuracy, DOP, speed; sky plot; C/N0 bar chart; tracked signals; history charts (fix status, position offset from the mean, accuracy, number of satellites); all-satellite table; log |
| **地図** | Current position and track on OpenStreetMap |
| **受信機設定** | Read and change the receiver configuration |
| **u-blox モニター** | The original u-blox Monitor views: signal status from RAWX / MEASX, SFRBX monitor, 5-minute trend |

#### Map (地図)

The map is not loaded until you press **地図を表示**. When it is shown, Leaflet (unpkg.com) and
map tiles (tile.openstreetmap.org) are downloaded from the internet, so the approximate
location you are viewing is disclosed to the map server. **地図を閉じる** stops the connection.

#### Receiver settings (受信機設定)

The current values are read automatically after connecting (**現在値を読み込む** reads them again).
Change the items you want and press **変更を送信**; only the changed items are sent, after a
confirmation dialog, and the values are read back. The result (ACK / NAK) is shown in the tab.

- 測位と追尾: dynamic model, fix mode, RTK mode, elevation mask, minimum C/N0,
  minimum number of satellites, measurement period, navigation rate
- 衛星系 / 信号: enable or disable constellations and signals. Only the signals supported by
  the receiver are shown. Changing them restarts the GNSS subsystem (re-acquisition).
- 保存先: "RAM のみ" (lost at power-off, default) or "RAM + BBR + Flash" (saved)
- 世代: normally "自動判別"; select manually if MON-VER is not answered

F9 / X20 use the configuration interface (UBX-CFG-VALGET / VALSET).
M8 uses UBX-CFG-NAV5 / CFG-RATE; constellations and signals cannot be changed on M8, and
changes are applied to the current configuration only.

#### u-blox Monitor (u-blox モニター)

**信号(RAWX / MEASX)** shows the signal reception status from RAWX and MEASX.
<img width="1684" height="492" alt="image" src="https://github.com/user-attachments/assets/ec67d8be-2819-4a2c-8d26-3dac30c72d1d" />

**SFRBX モニター** shows the reception status of navigation data.
<img width="1757" height="555" alt="image" src="https://github.com/user-attachments/assets/e2bdf38b-ffdd-4f4e-992d-975ebb54b54d" />

**トレンド(5 分)** shows the 5-minute trend of RXM-MEASX / RAWX.
<img width="1282" height="559" alt="image" src="https://github.com/user-attachments/assets/2f913845-e0ca-4060-b2a4-051fcc84b7d8" />

---

### Structure

| File | Role |
|---|---|
| `ublox_monitor/ubx.py` | UBX framing (checksum), decoding, packet building |
| `ublox_monitor/state.py` | Aggregation of received messages |
| `ublox_monitor/serial_link.py` | Serial I/O, replay, recording |
| `ublox_monitor/settings.py` | Receiver settings: items, packets, settings tab |
| `ublox_monitor/charts.py` | Charts, sky plot, C/N0 bars |
| `ublox_monitor/map_view.py` | Map tab |
| `ublox_monitor/monitor_view.py` | u-blox Monitor tab |
| `ublox_monitor/app.py` | Main window |

Tests: `python -m pytest tests`

---

### Notes

This software is still under development.  
You may encounter bugs or incomplete features.

Feel free to explore and use it as you like.  
Use at your own risk :)
