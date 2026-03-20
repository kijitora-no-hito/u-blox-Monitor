import tkinter as tk
from tkinter import filedialog, messagebox, ttk
import serial
import serial.tools.list_ports
import struct
import threading
import time
import os
from collections import deque

# --- GNSS Configuration ---
GNSS_CONFIG = {
    "GPS":     {"row": 0, "max": 12, "id": 0, "color": "#2ecc71"},
    "GLONASS": {"row": 0, "max": 12, "id": 6, "color": "#3498db"},
    "GALILEO": {"row": 0, "max": 12, "id": 2, "color": "#9b59b6"},
    "BEIDOU":  {"row": 1, "max": 24, "id": 3, "color": "#f1c40f"},
    "QZSS":    {"row": 1, "max": 6,  "id": 5, "color": "#e67e22"},
    "OTHER":   {"row": 1, "max": 6,  "id": -1, "color": "#95a5a6"}
}

SIG_CONF = {
    (0, 0): "L1", (0, 3): "L2", (0, 4): "L2", (0, 6): "L5", (0, 7): "L5",
    (1, 0): "L1", 
    (2, 0): "E1(L1)", (2, 1): "E1(L1)", (2, 3): "E5a(L5)", (2, 4): "E5a(L5)", (2, 5): "E5b", (2, 6): "E5b",
    (3, 0): "B1I(L1)", (3, 1): "B1I(L1)", (3, 2): "B2I", (3, 3): "B2I",
    (3, 4): "B3I", (3, 10): "B3I", (3, 5): "B1C", (3, 6): "B1C",
    (3, 7): "B2a(L5)", (3, 8): "B2a(L5)",
    (5, 0): "L1", (5, 1): "L1S", (5, 4): "L2", (5, 5): "L2",
    (5, 8): "L5", (5, 9): "L5", (5, 10): "L6", (5, 11): "L6", (5, 12): "L1C/B",
    (6, 0): "L1", (6, 2): "L2",
}

class UBXVisualizer:
    def __init__(self, root):
        self.root = root
        self.root.title("u-blox Monitor")
        
        self.running = False
        self.bytes_read = 0
        self.file_size = 0
        self.active_data = {sys: {} for sys in GNSS_CONFIG}
        self.seen_svs = {sys: [] for sys in GNSS_CONFIG}
        self.sfrbx_data = {sys: {} for sys in GNSS_CONFIG}
        self.sfrbx_win = None
        self.sfrbx_canvas = None
        self.trend_win = None
        
        self.history = {sys: {} for sys in GNSS_CONFIG}
        self.max_history_time = 300 
        self.start_time = time.time()

        self.setup_ui()
        self.update_gui_loop()

    def setup_ui(self):
        ctrl_frame = tk.Frame(self.root, bg="#2c3e50")
        ctrl_frame.pack(side=tk.TOP, fill=tk.X)
        
        # --- File Section ---
        tk.Button(ctrl_frame, text="Select File", command=self.select_file).pack(side=tk.LEFT, padx=5, pady=5)
        self.btn_play = tk.Button(ctrl_frame, text="▶ PLAY", command=self.start_file_playback, state=tk.DISABLED, bg="#27ae60", fg="white")
        self.btn_play.pack(side=tk.LEFT, padx=5)

        # --- Serial Port Section ---
        tk.Label(ctrl_frame, text="Port:", fg="white", bg="#2c3e50").pack(side=tk.LEFT, padx=5)
        self.port_var = tk.StringVar()
        self.combo_port = ttk.Combobox(ctrl_frame, textvariable=self.port_var, width=10, state="readonly")
        self.combo_port.pack(side=tk.LEFT, padx=2)
        tk.Button(ctrl_frame, text="↻", command=self.refresh_ports).pack(side=tk.LEFT)

        # --- BaudRate Section (New) ---
        tk.Label(ctrl_frame, text="Baud:", fg="white", bg="#2c3e50").pack(side=tk.LEFT, padx=5)
        self.baud_var = tk.IntVar(value=921600)
        self.combo_baud = ttk.Combobox(ctrl_frame, textvariable=self.baud_var, width=8, state="readonly")
        self.combo_baud['values'] = [9600, 19200, 38400, 57600, 115200, 230400, 460800, 921600]
        self.combo_baud.pack(side=tk.LEFT, padx=2)

        # Save UBX チェックボックス
        self.save_ubx_var = tk.BooleanVar(value=False)
        self.save_chk = tk.Checkbutton(
            ctrl_frame, 
            text="Save UBX", 
            variable=self.save_ubx_var,
            fg="#ecf0f1",           # 文字の色（白に近いグレー）
            bg="#2c3e50",           # 背景色（パネルの色）
            selectcolor="#1a1a1a",  # チェック時の「箱の中」の色（黒っぽくして白レ点を浮かせる）
            activebackground="#2c3e50", 
            activeforeground="#ecf0f1"
        )
        self.save_chk.pack(side=tk.LEFT, padx=10) 

        tk.Button(ctrl_frame, text="Connect", command=self.start_serial, bg="#2980b9", fg="white").pack(side=tk.LEFT, padx=5)

        # --- Monitor Buttons ---
        tk.Button(ctrl_frame, text="SFRBX Monitor", command=self.open_sfrbx_window, bg="#f39c12", fg="white").pack(side=tk.LEFT, padx=5)
        tk.Button(ctrl_frame, text="Trend Monitor", command=self.open_trend_window, bg="#8e44ad", fg="white").pack(side=tk.LEFT, padx=5)
        
        # --- Navigation Config Section ---
        nav_fm = tk.LabelFrame(ctrl_frame, text="Navigation Settings", fg="white", bg="#2c3e50")
        nav_fm.pack(side=tk.LEFT, fill=tk.X, padx=5, pady=2)
        # 世代選択ラジオボタン
        self.nav_gen_var = tk.StringVar(value="F9X20")
        tk.Radiobutton(nav_fm, text="M8", variable=self.nav_gen_var, value="M8", 
                       fg="white", bg="#2c3e50", selectcolor="#1a1a1a").pack(side=tk.LEFT, padx=5)
        tk.Radiobutton(nav_fm, text="F9/X20", variable=self.nav_gen_var, value="F9X20", 
                       fg="white", bg="#2c3e50", selectcolor="#1a1a1a").pack(side=tk.LEFT, padx=5)

        # 入力スピンボックス (仰角, C/N0, 衛星数)
        tk.Label(nav_fm, text=" ElevMask:", fg="white", bg="#2c3e50").pack(side=tk.LEFT)
        self.spin_elev = tk.Spinbox(nav_fm, from_=-90, to=90, width=5)
        self.spin_elev.pack(side=tk.LEFT, padx=2)

        tk.Label(nav_fm, text=" MinCNo:", fg="white", bg="#2c3e50").pack(side=tk.LEFT)
        self.spin_cno = tk.Spinbox(nav_fm, from_=0, to=60, width=5)
        self.spin_cno.pack(side=tk.LEFT, padx=2)

        tk.Label(nav_fm, text=" MinSVs:", fg="white", bg="#2c3e50").pack(side=tk.LEFT)
        self.spin_svs = tk.Spinbox(nav_fm, from_=0, to=64, width=5)
        self.spin_svs.pack(side=tk.LEFT, padx=2)

        # 取得・更新ボタン
        tk.Button(nav_fm, text="Load", command=self.load_nav_settings, bg="#16a085", fg="white").pack(side=tk.LEFT, padx=5)
        tk.Button(nav_fm, text="Apply", command=self.apply_nav_settings, bg="#e67e22", fg="white").pack(side=tk.LEFT, padx=5)

        # M8用のペイロード保持用
        self.m8_nav5_payload = None

        tk.Button(ctrl_frame, text="STOP", command=self.stop, bg="#c0392b", fg="white", width=8).pack(side=tk.RIGHT, padx=10)

        self.refresh_ports()

        # バージョン表示用のラベル（PVTバーの右端など）
        self.lbl_ver = tk.Label(ctrl_frame, text="Ver: ---", fg="#bdc3c7", bg="#2c3e50", font=("Arial", 8))
        self.lbl_ver.pack(side=tk.RIGHT, padx=10)

        self.progress_var = tk.DoubleVar()
        ttk.Progressbar(self.root, variable=self.progress_var, maximum=100).pack(fill=tk.X)

        self.canvas = tk.Canvas(self.root, width=1800, height=880, bg="#121212", highlightthickness=0)
        self.canvas.pack(fill=tk.BOTH, expand=True)

    def refresh_ports(self):
        ports = serial.tools.list_ports.comports()
        port_list = [p.device for p in ports]
        self.combo_port['values'] = port_list
        if port_list:
            if "COM3" in port_list: self.combo_port.set("COM3")
            else: self.combo_port.current(0)
        else:
            self.combo_port.set("No Port Found")

    def start_serial(self):
        port = self.port_var.get()
        baud = self.baud_var.get()
        if not port or port == "No Port Found":
            messagebox.showwarning("Warning", "有効なシリアルポートを選択してください。")
            return
        
        try:
            # 一時的にポートを開いて設定を送信
            temp_ser = serial.Serial(port, baud, timeout=1)
            # 受信機の種類に応じて切り替え
            self.enable_messages(temp_ser, receiver_type="F9P_UART") 
            
            # --- ここに追加: バージョン情報の要求パケットを直接送信 ---
            # UBX-MON-VER (0x0A 0x04) Poll Request
            header = b"\xB5\x62\x0A\x04\x00\x00\x0E\x34" # パケット固定値(Header+ID+Len+CK)
            temp_ser.write(header)
            
            temp_ser.close()
        except Exception as e:
            print(f"Config Error: {e}")

        self.stop()
        self.running = True
        threading.Thread(target=self.run_worker, args=("serial", port, baud), daemon=True).start()

    def select_file(self):
        path = filedialog.askopenfilename(filetypes=[("UBX files", "*.ubx")])
        if path:
            self.selected_file = path
            self.file_size = os.path.getsize(path)
            self.btn_play.config(state=tk.NORMAL)
            self.bytes_read = 0

    def start_file_playback(self):
        self.stop()
        self.running = True
        threading.Thread(target=self.run_worker, args=("file", self.selected_file, 0), daemon=True).start()

    def stop(self):
        self.running = False
        self.active_data = {sys: {} for sys in GNSS_CONFIG}
        self.sfrbx_data = {sys: {} for sys in GNSS_CONFIG}

    def open_sfrbx_window(self):
        if self.sfrbx_win is None or not self.sfrbx_win.winfo_exists():
            self.sfrbx_win = tk.Toplevel(self.root)
            self.sfrbx_win.title("UBX-RXM-SFRBX: Navigation Message Monitor")
            self.sfrbx_win.geometry("1800x600")
            self.sfrbx_canvas = tk.Canvas(self.sfrbx_win, bg="#1a1a1a", highlightthickness=0)
            self.sfrbx_canvas.pack(fill=tk.BOTH, expand=True)
        else:
            self.sfrbx_win.lift()

    def open_trend_window(self):
        if self.trend_win is None or not self.trend_win.winfo_exists():
            self.trend_win = tk.Toplevel(self.root)
            self.trend_win.title("5-Minute Trend Monitor (Matrix View)")
            self.trend_win.geometry("1750x950")
            
            container = tk.Frame(self.trend_win, bg="#1a1a1a")
            container.pack(fill=tk.BOTH, expand=True)
            
            self.trend_canvas = tk.Canvas(container, bg="#1a1a1a", highlightthickness=0)
            scrollbar = ttk.Scrollbar(container, orient="vertical", command=self.trend_canvas.yview)
            self.scroll_frame = tk.Frame(self.trend_canvas, bg="#1a1a1a")
            self.trend_canvas.create_window((0, 0), window=self.scroll_frame, anchor="nw")
            self.trend_canvas.configure(yscrollcommand=scrollbar.set)
            self.trend_canvas.pack(side="left", fill="both", expand=True)
            scrollbar.pack(side="right", fill="y")
            
            self.trend_plots = {} 
            self.trend_selectors = {} # プルダウン保持用
            
            bands = ["L1", "L2", "L5", "L6"]
            metrics = ["C/N0", "MPath", "RMSE", "TrackStatus"]
            
            for sys in GNSS_CONFIG:
                if sys == "OTHER": continue
                sys_outer = tk.Frame(self.scroll_frame, bg="#34495e", bd=2, relief=tk.RIDGE)
                sys_outer.pack(fill=tk.X, padx=10, pady=2)
                
                left_col = tk.Frame(sys_outer, bg="#2c3e50", width=120)
                left_col.pack(side=tk.LEFT, fill=tk.Y)
                left_col.pack_propagate(False)
                tk.Label(left_col, text=sys, fg=GNSS_CONFIG[sys]['color'], bg="#2c3e50", font=("Arial", 12, "bold")).pack(pady=5)
                
                for m in metrics:
                    lbl_fm = tk.Frame(left_col, bg="#2c3e50", height=32)
                    lbl_fm.pack(fill=tk.X)
                    lbl_fm.pack_propagate(False)
                    tk.Label(lbl_fm, text=m, fg="#ecf0f1", bg="#2c3e50", font=("Arial", 9)).pack(expand=True)
                
                right_grid = tk.Frame(sys_outer, bg="#1a1a1a")
                right_grid.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
                
                for b_label in bands:
                    band_fm = tk.Frame(right_grid, bg="#121212", highlightthickness=1, highlightbackground="#444")
                    band_fm.pack(side=tk.LEFT, fill=tk.BOTH, expand=True, padx=1)
                    
                    # --- プルダウンメニューの追加 ---
                    top_fm = tk.Frame(band_fm, bg="#2c3e50")
                    top_fm.pack(fill=tk.X)
                    
                    tk.Label(top_fm, text=b_label, fg="#bdc3c7", bg="#2c3e50", font=("Arial", 9, "bold")).pack(side=tk.LEFT, padx=5)
                    
                    sel_var = tk.StringVar(value="All")
                    combo = ttk.Combobox(top_fm, textvariable=sel_var, width=5, state="readonly", font=("Arial", 8))
                    combo['values'] = ["All"]
                    combo.set("All") # 明示的にセット
                    combo.current(0)
                    combo.pack(side=tk.RIGHT, padx=2, pady=2)
                    
                    self.trend_selectors[(sys, b_label)] = combo
                    
                    # グラフ本体
                    c = tk.Canvas(band_fm, height=150, bg="#121212", highlightthickness=0)
                    c.pack(fill=tk.X)
                    self.trend_plots[(sys, b_label)] = c
            
            self.scroll_frame.bind("<Configure>", lambda e: self.trend_canvas.configure(scrollregion=self.trend_canvas.bbox("all")))
        else:
            self.trend_win.lift()

    def run_worker(self, mode, target, baud):
        # ファイル保存用のファイルハンドルを初期化
        log_file = None
        if self.save_ubx_var.get() and mode == "serial":
            log_filename = f"log_{time.strftime('%Y%m%d_%H%M%S')}.ubx"
            log_file = open(log_filename, "wb")
            print(f"Logging to {log_filename}")

        try:
            stream = serial.Serial(target, baud, timeout=0.1) if mode == "serial" else open(target, "rb")
            print(f"Started {mode} mode at {baud if baud > 0 else 'N/A'} baud...")
            
            #送信メソッドから参照できるように保持
            if mode == "serial":
                self.serial_stream = stream
                self.root.after(500, self.poll_version)

            with stream:
                while self.running:
                    header_sync = stream.read(1)
                    if not header_sync or header_sync[0] != 0xB5: continue
                    
                    # 0x62を確認
                    header_62 = stream.read(1)
                    if not header_62 or header_62[0] != 0x62: continue
                    
                    # クラス、ID、レングスを取得
                    header = stream.read(4)
                    if len(header) < 4: continue
                    cls, msg_id, length = struct.unpack("<BBH", header)
                    
                    # ペイロードを読み込み
                    payload = b""
                    while len(payload) < length:
                        chunk = stream.read(length - len(payload))
                        if not chunk: break
                        payload += chunk
                    
                    # チェックサムを読み込み
                    ck = stream.read(2)
                    if len(ck) < 2: continue

                    # チェックボックスがON、かつシリアル通信モードの場合のみ保存
                    if log_file and self.save_ubx_var.get():
                        # UBXパケットを再構成して書き込み
                        full_packet = b"\xB5\x62" + header + payload + ck
                        log_file.write(full_packet)
                        
                    self.bytes_read += (6 + length + 2)
                    
                    # 各種パース処理
                    if cls == 0x02 and msg_id == 0x14:
                        self.parse_measx(payload)
                        if mode == "file": time.sleep(0.01)
                    elif cls == 0x02 and msg_id == 0x15:
                        self.parse_rawx(payload)
                    elif cls == 0x02 and msg_id == 0x13:
                        self.parse_sfrbx(payload)
                    elif cls == 0x01 and msg_id == 0x07:
                        self.parse_nav_pvt(payload)
                    elif cls == 0x0A and msg_id == 0x04:
                        self.parse_mon_ver(payload)
                    elif cls == 0x06 and msg_id == 0x8B:
                        self.parse_valget(payload)
                    elif cls == 0x06 and msg_id == 0x24: # NAV5 (M8)
                        self.parse_nav5(payload)
                        
        except Exception as e:
            print(f"Worker Error: {e}")
        finally:
            self.serial_stream = None # 終了時にクリア
            # スレッド終了時にファイルを確実に閉じる
            if log_file:
                log_file.close()
                print("Log file closed.")

    def get_sys_name(self, gnss_id):
        for name, cfg in GNSS_CONFIG.items():
            if gnss_id == cfg['id']: return name
        return "OTHER"

    def parse_valget(self, payload):
        """UBX-CFG-VALGET (0x06 0x8b) の応答パース"""
        # ペイロード構造: version(1), layer(1), position(2), cfgData(KeyID+Value)...
        if len(payload) < 9: 
            return # 最小でもヘッダー4 + 1ペア5 = 9バイト必要

        # デバッグ用：受信したデータを確認
        # print(f"VALGET RX: Layer={payload[1]}, DataLen={len(payload)-4}")

        pos = 4 # cfgDataの開始位置
        while pos + 5 <= len(payload):
            try:
                # KeyID (4バイト) を抽出
                key = struct.unpack("<I", payload[pos:pos+4])[0]
                # Value (1バイト) を抽出
                val_byte = payload[pos+4:pos+5]
                
                if key == 0x201100a4: # Elevation Mask (I1: 符号あり)
                    val = struct.unpack("b", val_byte)[0]
                    self.set_spin_value(self.spin_elev, val)
                elif key == 0x201100a3: # Min C/N0 (U1: 符号なし)
                    val = struct.unpack("B", val_byte)[0]
                    self.set_spin_value(self.spin_cno, val)
                elif key == 0x201100a1: # Min SVs (U1: 符号なし)
                    val = struct.unpack("B", val_byte)[0]
                    self.set_spin_value(self.spin_svs, val)
                
                pos += 5 # 次のペアへ (Key 4b + Val 1b)
            except Exception as e:
                print(f"VALGET Parse Loop Error: {e}")
                break

    def parse_nav5(self, payload):
        """M8の設定レスポンスを解析"""
        if len(payload) != 36: return
        self.m8_nav5_payload = payload # 更新用に保存
        elev = struct.unpack("b", payload[12:13])[0]
        svs = payload[24]
        cno = payload[25]
        self.set_spin_value(self.spin_elev, elev)
        self.set_spin_value(self.spin_svs, svs)
        self.set_spin_value(self.spin_cno, cno)

    def set_spin_value(self, spin, val):
        """Tkinter Spinboxの値を安全に更新"""
        spin.delete(0, tk.END)
        spin.insert(0, str(val))

    def poll_version(self):
        """UBX-MON-VER (0x0a 0x04) をリクエスト送信"""
        # ペイロードなし(0バイト)で送信
        port = self.port_var.get()
        if not self.running:
            messagebox.showwarning("Warning", "先にConnectしてください。")
            return
        
        # シリアルポートへ直接書き込み（既存のsend_ubxを利用）
        # クラス: 0x0A, ID: 0x04, ペイロード: 空
        self.send_ubx_raw(0x0A, 0x04, b"")
        print("Poll Request: UBX-MON-VER sent.")

    def send_ubx(self, serial_port, cls, msg_id, payload):
        """UBXパケットを組み立てて送信"""
        header = bytes([0xB5, 0x62, cls, msg_id])
        length = struct.pack("<H", len(payload))
        packet_no_ck = bytes([cls, msg_id]) + length + payload
        checksum = self.calculate_checksum(packet_no_ck)
        
        full_packet = header + length + payload + checksum
        serial_port.write(full_packet)
        time.sleep(0.1) # 受信機の処理待ち

    def send_ubx_raw(self, cls, msg_id, payload):
        """UBXパケットを組み立てて現在のシリアルストリームへ送信"""
        # 実行中のシリアルインスタンスが必要なため、run_worker内のstreamを変数保持しておく必要があります
        if hasattr(self, 'serial_stream') and self.serial_stream:
            header = bytes([0xB5, 0x62, cls, msg_id])
            length = struct.pack("<H", len(payload))
            packet_no_ck = bytes([cls, msg_id]) + length + payload
            ck_a = 0
            ck_b = 0
            for b in packet_no_ck:
                ck_a = (ck_a + b) & 0xFF
                ck_b = (ck_b + ck_a) & 0xFF
            full_packet = header + length + payload + bytes([ck_a, ck_b])
            self.serial_stream.write(full_packet)

    def parse_mon_ver(self, payload):
        """UBX-MON-VER のパースと世代判定"""
        if len(payload) < 40: return

        # 1. 固定長部分の抽出
        sw_ver_raw = payload[0:30].decode('ascii', errors='ignore').split('\x00')[0]
        hw_ver_raw = payload[30:40].decode('ascii', errors='ignore').split('\x00')[0]
        
        # 2. 拡張データの抽出 (30バイトずつ)
        extensions = []
        num_ext = (len(payload) - 40) // 30
        for i in range(num_ext):
            start = 40 + (i * 30)
            ext_str = payload[start:start+30].decode('ascii', errors='ignore').split('\x00')[0]
            if ext_str:
                extensions.append(ext_str)

        # 3. 世代判定ロジック
        generation = "Unknown"
        prot_ver = ""
        module_name = ""

        # 拡張情報から PROTVER と MOD を抽出
        for ext in extensions:
            if "PROTVER=" in ext:
                prot_ver = ext.split('=')[1]
            if "MOD=" in ext:
                module_name = ext.split('=')[1]

        # 判定
        try:
            pv_float = float(prot_ver) if prot_ver else 0.0
            
            # X20判定
            if "X20" in module_name or (50.0 <= pv_float < 60.0):
                generation = "X20"
            # M8判定
            elif "00080000" in hw_ver_raw or "M8" in module_name or (15.0 <= pv_float <= 23.01):
                generation = "M8"
            # F9判定
            elif "F9" in module_name or (27.0 <= pv_float < 40.0):
                generation = "F9"
        except:
            pass

        # GUI表示用変数へ格納
        self.ver_info = {
            "gen": generation,
            "sw": sw_ver_raw,
            "hw": hw_ver_raw,
            "prot": prot_ver
        }
        print(f"Detected: {generation} (HW:{hw_ver_raw}, SW:{sw_ver_raw}, Prot:{prot_ver})")

    def load_nav_settings(self):
        """受信機から現在の設定を取得(Poll)"""
        if not self.running: return
        gen = self.nav_gen_var.get()

        if gen == "F9X20":
            # UBX-CFG-VALGET (0x06 0x8b)
            # version(0), layer(0:RAM), position(0,0), KeyID...
            payload = bytes([0x00, 0x00, 0x00, 0x00])
            keys = [0x201100a4, 0x201100a3, 0x201100a1]
            for k in keys: payload += struct.pack("<I", k)
            self.send_ubx_raw(0x06, 0x8b, payload)
        else:
            # UBX-CFG-NAV5 (0x06 0x24) Poll
            self.send_ubx_raw(0x06, 0x24, b"")
        print(f"Nav settings poll sent ({gen})")
    
    def apply_nav_settings(self):
        """GUIの値を書き込み(Set)"""
        if not self.running: return
        gen = self.nav_gen_var.get()
        
        elev = int(self.spin_elev.get())
        cno = int(self.spin_cno.get())
        svs = int(self.spin_svs.get())

        if gen == "F9X20":
            # UBX-CFG-VALSET (0x06 0x8a)
            payload = bytes([0x00, 0x01, 0x00, 0x00]) # ver, layer(RAM), res
            payload += struct.pack("<I", 0x201100a4) + struct.pack("b", elev)
            payload += struct.pack("<I", 0x201100a3) + struct.pack("B", cno)
            payload += struct.pack("<I", 0x201100a1) + struct.pack("B", svs)
            self.send_ubx_raw(0x06, 0x8a, payload)
        else:
            # M8: UBX-CFG-NAV5 (0x06 0x24)
            if self.m8_nav5_payload is None:
                messagebox.showwarning("Warning", "先にLoadボタンを押して現在の設定を取得してください。")
                return
            
            p = bytearray(self.m8_nav5_payload)
            # マスクビットをセット (0x0005: minEl(bit0) と cnoThreshold(bit2) を有効化)
            p[0:2] = struct.pack("<H", 0x0005)
            p[12] = struct.pack("b", elev)[0] # minElev
            p[24] = svs # cnoThreshNumSVs
            p[25] = cno # cnoThresh
            self.send_ubx_raw(0x06, 0x24, bytes(p))
        print(f"Nav settings apply sent ({gen})")

    def enable_messages(self, serial_port, receiver_type="F9P_UART"):
        """
        受信機の種類に応じて必要なメッセージを有効化する
        """
        print(f"Configuring receiver ({receiver_type})...")
        
        if receiver_type == "M8T":
            # UBX-CFG-MSG (0x06 0x01) を個別に送信
            # ペイロード: msgClass(1), msgID(1), rate(1)
            target_msgs = [
                (0x02, 0x14), # MEASX
                (0x02, 0x15), # RAWX
                (0x02, 0x13), # SFRBX
                (0x01, 0x07)  # PVT
            ]
            for cls, mid in target_msgs:
                payload = bytes([cls, mid, 0x01])
                self.send_ubx(serial_port, 0x06, 0x01, payload)

        elif receiver_type == "F9P_UART":
            # UBX-CFG-VALSET (0x06 0x8A) を1つのパケットで送信
            # 構造: version(0), layers(1:RAM), reserved(0,0), [KeyID(4) + Value(1)] * N
            keys = [
                0x20910205, # CFG-MSGOUT-UBX_RXM_MEASX_UART1
                0x209102a5, # CFG-MSGOUT-UBX_RXM_RAWX_UART1
                0x20910232, # CFG-MSGOUT-UBX_RXM_SFRBX_UART1
                0x20910007  # CFG-MSGOUT-UBX_NAV_PVT_UART1
            ]
            
            # ヘッダー部分 (version:0, layers:1, reserved:0,0)
            payload = bytes([0x00, 0x01, 0x00, 0x00])
            
            # 各Key ID(リトルエンディアン)と値(0x01)を追加
            for key in keys:
                payload += struct.pack("<I", key) + bytes([0x01])
            
            self.send_ubx(serial_port, 0x06, 0x8A, payload)
            
        print("Configuration sent.")

    def calculate_checksum(self, payload):
        """UBX形式のチェックサム(8-bit Fletcher)を計算"""
        ck_a = 0
        ck_b = 0
        for b in payload:
            ck_a = (ck_a + b) & 0xFF
            ck_b = (ck_b + ck_a) & 0xFF
        return bytes([ck_a, ck_b])

    def parse_measx(self, payload):
        if len(payload) < 44: return
        num_sv = payload[34]
        now = time.time()
        for i in range(num_sv):
            off = 44 + (i * 24)
            if len(payload) < off + 24: break
            g_id, sv_id, cno, mpath = struct.unpack("<BBBB", payload[off:off+4])
            rmse = payload[off+21]
            sys_name = self.get_sys_name(g_id)
            if sv_id not in self.seen_svs[sys_name]:
                if len(self.seen_svs[sys_name]) < GNSS_CONFIG[sys_name]['max']:
                    self.seen_svs[sys_name].append(sv_id)
                    self.seen_svs[sys_name].sort()
            if sv_id not in self.active_data[sys_name]:
                self.active_data[sys_name][sv_id] = {'signals': {}}
            d = self.active_data[sys_name][sv_id]
            d.update({
                'cNo': cno, 'mpath': mpath, 'rmse': rmse, 
                'last_seen': now,
                'wChips': struct.unpack("<H", payload[off+12:off+14])[0],
                'fChips': struct.unpack("<H", payload[off+14:off+16])[0],
                'cPhase': struct.unpack("<I", payload[off+16:off+20])[0],
                'iPhase': payload[off+20]
            })
            self.add_history(sys_name, sv_id, now, cno, mpath, rmse, 1, 1, None)

    def parse_nav_pvt(self, payload):
        """UBX-NAV-PVT (0x01 0x07) の安全なパース"""
        # ペイロードが規定の92バイトに満たない場合は即座にリターン
        if len(payload) < 92:
            print(f"NAV-PVT Error: Payload too short ({len(payload)} bytes)")
            return

        try:
            # 1. 前半部分を一括でアンパック (Offset 0から23までの24バイト分)
            # iTOW(I), year(H), month(B), day(B), hour(B), min(B), sec(B), valid(B), 
            # tAcc(I), nano(i), fixType(b), flags(B), flags2(B), numSV(B)
            # 合計: 4+2+1+1+1+1+1+1 + 4+4+1+1+1+1 = 24バイト
            base_data = struct.unpack("<IHBBBBBBIibBBB", payload[0:24])
            
            itow   = base_data[0]
            year   = base_data[1]
            month  = base_data[2]
            day    = base_data[3]
            hour   = base_data[4]
            minute = base_data[5]
            second = base_data[6]
            valid  = base_data[7]
            t_acc  = base_data[8]
            nano   = base_data[9]
            fix_type = base_data[10]
            flags    = base_data[11]
            flags2   = base_data[12]
            num_sv   = base_data[13]

            # 2. 位置情報部分 (Offset 24から47までの24バイト分)
            # lon(i), lat(i), height(i), hMSL(i), hAcc(I), vAcc(I)
            pos_data = struct.unpack("<iiiiII", payload[24:48])
            lon_raw, lat_raw, height_raw, h_msl_raw, h_acc, v_acc = pos_data

            # 3. pDOP (Offset 76から77)
            p_dop = struct.unpack("<H", payload[76:78])[0]

            # --- ステータス判定 ---
            gnss_fix_ok = flags & 0x01
            carr_soln = (flags >> 6) & 0x03 
            
            if not gnss_fix_ok:
                fix_status = "No Fix"
                fix_color = "#e74c3c"
            else:
                if carr_soln == 2:
                    fix_status = "RTK Fixed"
                    fix_color = "#2ecc71"
                elif carr_soln == 1:
                    fix_status = "RTK Float"
                    fix_color = "#f1c40f"
                else:
                    fix_dict = {0:"No Fix", 1:"DR", 2:"2D", 3:"3D", 4:"GNSS+DR", 5:"Time"}
                    fix_status = fix_dict.get(fix_type, f"Fix({fix_type})")
                    fix_color = "#3498db"

            # --- 表示用データの更新 ---
            self.pvt_data = {
                "time": f"{year:04}/{month:02}/{day:02} {hour:02}:{minute:02}:{second:02}",
                "lat": f"{lat_raw * 1e-7:.8f}",
                "lon": f"{lon_raw * 1e-7:.8f}",
                "height": f"{height_raw/1000.0:.3f}m (MSL:{h_msl_raw/1000.0:.3f}m)",
                "status": fix_status,
                "status_color": fix_color,
                "numSV": num_sv,
                "hAcc": f"{h_acc/1000.0:.3f} m",
                "vAcc": f"{v_acc/1000.0:.3f} m",
                "tAcc": f"{t_acc} ns",
                "pDOP": f"{p_dop * 0.01:.2f}"
            }
        except Exception as e:
            print(f"NAV-PVT Parse Error: {e}")


    def parse_rawx(self, payload):
        if len(payload) < 16: return
        num_meas = payload[11]
        now = time.time()
        for i in range(num_meas):
            off = 16 + (i * 32)
            if len(payload) < off + 32: break
            g_id, sv_id, sig_id, cno_raw = payload[off+20], payload[off+21], payload[off+22], payload[off+26]
            trk_stat = payload[off+30]
            sys_name = self.get_sys_name(g_id)
            if sv_id not in self.seen_svs[sys_name]:
                if len(self.seen_svs[sys_name]) < GNSS_CONFIG[sys_name]['max']:
                    self.seen_svs[sys_name].append(sv_id)
                    self.seen_svs[sys_name].sort()
            if sv_id not in self.active_data[sys_name]:
                self.active_data[sys_name][sv_id] = {'signals': {}}
            d = self.active_data[sys_name][sv_id]
            pr_v = 1 if trk_stat & 0x01 else 0
            cp_v = 1 if trk_stat & 0x02 else 0
            if 'signals' not in d: d['signals'] = {}
            d['signals'][sig_id] = {'cno': cno_raw, 'pr': bool(pr_v), 'cp': bool(cp_v), 'ts': now}
            d['last_seen'] = now
            self.add_history(sys_name, sv_id, now, cno_raw, d.get('mpath', 0), d.get('rmse', 0), pr_v, cp_v, sig_id)

    def parse_sfrbx(self, payload):
        if len(payload) < 8: return
        g_id, sv_id, sig_id, _, num_words = struct.unpack("<BBBB B", payload[0:5])
        sys_name = self.get_sys_name(g_id)
        subframe_id = 0
        if num_words > 0 and len(payload) >= 12:
            first_word = struct.unpack("<I", payload[8:12])[0]
            subframe_id = (first_word >> 2) & 0x07 
        if sv_id not in self.sfrbx_data[sys_name]:
            self.sfrbx_data[sys_name][sv_id] = {'count': 0, 'subframes': {i:False for i in range(1,6)}, 'last_ts': 0}
        target = self.sfrbx_data[sys_name][sv_id]
        target['count'] += 1
        target['last_ts'] = time.time()
        if 1 <= subframe_id <= 5: target['subframes'][subframe_id] = True

    def draw_main_gui(self):
        self.canvas.delete("all")
        now = time.time()
        for sys_name, cfg in GNSS_CONFIG.items():
            row_idx = cfg['row']
            try:
                current_sv_keys = list(self.active_data[sys_name].keys())
            except RuntimeError: continue
            prev_sys = [n for n in GNSS_CONFIG if GNSS_CONFIG[n]['row'] == row_idx and list(GNSS_CONFIG.keys()).index(n) < list(GNSS_CONFIG.keys()).index(sys_name)]
            offset_x = 20 + sum([(GNSS_CONFIG[n]['max'] * 48 + 35) for n in prev_sys])
            y_base = 400 + (row_idx * 430)
            self.canvas.create_text(offset_x, y_base-335, text=sys_name, fill=cfg['color'], font=("Arial", 12, "bold"), anchor="nw")
            for idx in range(cfg['max']):
                x_slot = offset_x + (idx * 48)
                self.canvas.create_rectangle(x_slot, y_base-280, x_slot+44, y_base, outline="#222")
                if idx < len(self.seen_svs[sys_name]):
                    sv_id = self.seen_svs[sys_name][idx]
                    data = self.active_data[sys_name].get(sv_id, {})
                    if not data: continue
                    is_active = (now - data.get('last_seen', 0)) < 2.5
                    sv_col = cfg['color'] if is_active else "#444"
                    self.canvas.create_text(x_slot+22, y_base+15, text=f"{sv_id}", fill=sv_col, font=("Consolas", 9, "bold"))
                    if is_active:
                        sig_x, sig_y = x_slot + 2, y_base - 300
                        signals_snap = dict(data.get('signals', {}))
                        for band in ["L1", "L2", "L5", "L6"]:
                            is_on, has_cp = False, False
                            for s_id, s_info in signals_snap.items():
                                if band in SIG_CONF.get((cfg['id'], s_id), ""):
                                    if (now - s_info['ts']) < 2.5:
                                        is_on, has_cp = True, s_info['cp']
                                        break
                            bg = "#1B5E20" if is_on else "#1a1a1a"; fg = "#00FF00" if has_cp else "#555"
                            self.canvas.create_rectangle(sig_x, sig_y, sig_x+10, sig_y+15, fill=bg, outline="#333")
                            self.canvas.create_text(sig_x+5, sig_y+7, text=band[1], fill=fg, font=("Arial", 6, "bold"))
                            sig_x += 11
                        cno = data.get('cNo', 0); cno_h = (cno / 63) * 240
                        m_col = {0: "#7f8c8d", 1: "#2ecc71", 2: "#f1c40f", 3: "#e74c3c"}.get(data.get('mpath', 0), "#7f8c8d")
                        self.canvas.create_rectangle(x_slot+12, y_base-cno_h, x_slot+40, y_base, fill=m_col, outline="white")
                        self.canvas.create_text(x_slot+26, y_base-cno_h-10, text=str(cno), fill="white", font=("Consolas", 8))
                        params = [data.get('wChips','-'), data.get('fChips','-'), data.get('cPhase','-'), data.get('iPhase','-'), data.get('rmse','-')]
                        for j, val in enumerate(params):
                            p_col = "#e74c3c" if j==4 and isinstance(val, int) and val > 30 else "#aaa"
                            self.canvas.create_text(x_slot+22, y_base+35+(j*12), text=str(val), fill=p_col, font=("Consolas", 7))
        self.draw_pvt_info()

    def draw_pvt_info(self):
        """メイン画面上部にPVT情報を横一行で表示"""
        if not hasattr(self, 'pvt_data'): return

        d = self.pvt_data
        
        # 配置の基本設定
        x_start = 20
        y_pos = 25  # グラフエリアの最上部付近
        spacing = 200 # 項目間の間隔
        
        # 背景の黒い帯（視認性を高めるため、少し暗い領域を作る）
        self.canvas.create_rectangle(0, y_pos-20, 1800, y_pos+20, fill="#1a1a1a", outline="#333")
        
        # 表示項目のリスト（ラベル, 値, 色）
        items = [
            ("TIME", d['time'], "#bdc3c7"),
            ("STATUS", d['status'], d['status_color']),
            ("LAT", d['lat'], "#ecf0f1"),
            ("LON", d['lon'], "#ecf0f1"),
            ("HEIGHT", d['height'].split(' ')[0], "#ecf0f1"), # 短縮表示
            ("SATS", d['numSV'], "#ecf0f1"),
            ("hAcc", d['hAcc'], "#bdc3c7"),
            ("pDOP", d['pDOP'], "#bdc3c7")
        ]

        f_label = ("Arial", 8, "bold")
        f_val = ("Consolas", 10, "bold")

        for i, (label, value, color) in enumerate(items):
            cur_x = x_start + (i * spacing)
            # 項目ラベル
            self.canvas.create_text(cur_x, y_pos, text=label, fill="#7f8c8d", font=f_label, anchor="w")
            # 値 (ラベルのすぐ右または下に配置)
            self.canvas.create_text(cur_x + 50, y_pos, text=str(value), fill=color, font=f_val, anchor="w")

    def draw_sfrbx_gui(self):
        if not self.sfrbx_win or not self.sfrbx_win.winfo_exists(): return
        c = self.sfrbx_canvas
        c.delete("all")
        now = time.time()
        for sys_name, cfg in GNSS_CONFIG.items():
            row_idx = cfg['row']
            prev_sys = [n for n in GNSS_CONFIG if GNSS_CONFIG[n]['row'] == row_idx and list(GNSS_CONFIG.keys()).index(n) < list(GNSS_CONFIG.keys()).index(sys_name)]
            offset_x = 20 + sum([(GNSS_CONFIG[n]['max'] * 48 + 35) for n in prev_sys])
            y_base = 250 + (row_idx * 280)
            c.create_text(offset_x, y_base-215, text=f"{sys_name} Navigation Subframes", fill=cfg['color'], font=("Arial", 11, "bold"), anchor="nw")
            for idx in range(cfg['max']):
                x_slot = offset_x + (idx * 48)
                c.create_rectangle(x_slot, y_base-180, x_slot+44, y_base, outline="#333")
                if idx < len(self.seen_svs[sys_name]):
                    sv_id = self.seen_svs[sys_name][idx]
                    c.create_text(x_slot+22, y_base+15, text=f"{sv_id}", fill=cfg['color'], font=("Consolas", 9, "bold"))
                    data = self.sfrbx_data[sys_name].get(sv_id)
                    if data:
                        h = min(120, (data['count'] % 100) * 1.2) 
                        c.create_rectangle(x_slot+15, y_base-h, x_slot+42, y_base, fill="#f39c12", outline="white")
                        for sf_num in range(1, 6):
                            box_y = y_base - 170 + (sf_num-1) * 25
                            is_received = data['subframes'].get(sf_num, False)
                            bg_color = ("#27ae60" if sf_num <= 3 else "#2980b9") if is_received else "#1a1a1a"
                            c.create_rectangle(x_slot+2, box_y, x_slot+12, box_y+20, fill=bg_color, outline="#333")
                            c.create_text(x_slot+7, box_y+10, text=str(sf_num), fill="white" if is_received else "#444", font=("Arial", 7, "bold"))

    def draw_trend_gui(self):
        if not self.trend_win or not self.trend_win.winfo_exists(): return
        now = time.time()
        
        for (sys, b_label), canvas in self.trend_plots.items():
            canvas.delete("all")
            w, h = canvas.winfo_width(), canvas.winfo_height()
            if w < 10: continue
            
            # プルダウンの更新（新しい衛星が見つかっていたらリストに追加）
            combo = self.trend_selectors[(sys, b_label)]
            #current_vals = list(combo['values'])
            current_selection = combo.get() # 現在選ばれている文字列を記憶
            new_vals = ["All"] + [str(s) for s in self.seen_svs[sys]]

            # リストの内容が変わった時だけ更新
            if list(combo['values']) != new_vals:
                combo['values'] = new_vals
                # 更新後に、以前選んでいた値がまだリストにあれば再セット
                if current_selection in new_vals:
                    combo.set(current_selection)
                else:
                    combo.current(0) # なければ All に戻す
            
            selected_sv = combo.get()
            h_sub = h / 4
            for i in range(1, 4): canvas.create_line(0, i*h_sub, w, i*h_sub, fill="#222")
            
            if sys not in self.history: continue
            current_sv_ids = list(self.history[sys].keys())
            
            for svid in current_sv_ids:
                # 選択フィルタリング
                if selected_sv != "All" and str(svid) != selected_sv:
                    continue
                
                q = self.history[sys][svid]
                pts = {'cno':[], 'mp':[], 'rmse':[], 'stat':[]}
                data_snapshot = list(q)
                
                for item in data_snapshot:
                    if len(item) < 7: continue
                    t, cno, mp, rmse, pr, cp, sid = item
                    
                    if sid is not None:
                        if b_label not in SIG_CONF.get((GNSS_CONFIG[sys]['id'], sid), ""): continue
                    elif b_label != "L1": continue
                    
                    x = w - (now - t) * (w / self.max_history_time)
                    if x < 0: continue
                    
                    pts['cno'].extend([x, (1 - cno/60) * h_sub])
                    pts['mp'].extend([x, h_sub + (1 - mp/3) * h_sub])
                    pts['rmse'].extend([x, 2*h_sub + (1 - rmse/63) * h_sub])
                    pts['stat'].extend([x, 3*h_sub + (0.8 - (pr*0.3 + cp*0.4)) * h_sub])
                
                color = self.get_sv_color(svid)
                for k in pts:
                    if len(pts[k]) >= 4: canvas.create_line(pts[k], fill=color, width=1)

    def get_sv_color(self, svid):
        colors = ["#e74c3c", "#3498db", "#2ecc71", "#f1c40f", "#9b59b6", "#1abc9c", "#e67e22"]
        return colors[svid % len(colors)]

    def add_history(self, sys, svid, t, cno, mp, rmse, pr, cp, sid):
        if svid not in self.history[sys]: self.history[sys][svid] = deque()
        self.history[sys][svid].append((t, cno, mp, rmse, pr, cp, sid))
        while self.history[sys][svid] and self.history[sys][svid][0][0] < t - self.max_history_time:
            self.history[sys][svid].popleft()

    def update_gui_loop(self):
        if self.file_size > 0: self.progress_var.set((self.bytes_read / self.file_size) * 100)

        if hasattr(self, 'ver_info'):
            v = self.ver_info
            self.lbl_ver.config(text=f"GEN: {v['gen']} | HW: {v['hw']} | SW: {v['sw']}")
        self.draw_main_gui()
        self.draw_sfrbx_gui()
        self.draw_trend_gui()
        self.root.after(100, self.update_gui_loop)

if __name__ == "__main__":
    root = tk.Tk()
    root.geometry("1850x920")
    root.configure(bg="#121212")
    app = UBXVisualizer(root)
    root.mainloop()