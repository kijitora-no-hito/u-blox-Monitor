# PyInstaller の設定。build.ps1 から使う。
import os

ROOT = os.path.abspath(os.path.join(SPECPATH, ".."))

analysis = Analysis(
    [os.path.join(SPECPATH, "launcher.py")],
    pathex=[ROOT],
    # 地図(QtWebEngine)は地図タブを開いたときに読み込むので、明示的に含める
    hiddenimports=["PySide6.QtWebEngineWidgets", "PySide6.QtWebEngineCore"],
    excludes=["tkinter", "matplotlib", "numpy", "pandas", "scipy", "PIL", "pytest"],
)


def keep(dest):
    """翻訳ファイルは日本語と英語だけ残す(QtWebEngine の言語ファイルだけで 40 MB を超えるため)。"""
    name = os.path.basename(dest).lower()
    parts = dest.replace("\\", "/").lower().split("/")
    if "qtwebengine_locales" in parts:
        return name in ("en-us.pak", "ja.pak")
    if "translations" in parts and name.endswith(".qm"):
        return "_ja" in name or "_en" in name
    return True


analysis.datas = [entry for entry in analysis.datas if keep(entry[0])]
pyz = PYZ(analysis.pure)
exe = EXE(
    pyz,
    analysis.scripts,
    [],
    exclude_binaries=True,
    name="ublox_monitor",
    console=False,
)
coll = COLLECT(exe, analysis.binaries, analysis.datas, name="ublox_monitor")
