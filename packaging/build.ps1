# ublox_monitor.exe を PyInstaller で作り、Inno Setup でインストーラを作る。
# 実行: powershell -ExecutionPolicy Bypass -File packaging\build.ps1
# 必要なもの: PyInstaller(pip install pyinstaller)、Inno Setup 6
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$root = Split-Path $here -Parent

# リポジトリの .venv があればそれを使う
$python = Join-Path $root '.venv\Scripts\python.exe'
if (-not (Test-Path $python)) { $python = 'python' }

function Find-Iscc {
    $cmd = Get-Command ISCC.exe -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $keys = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*',
            'HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*',
            'HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*'
    $installed = Get-ItemProperty $keys -ErrorAction SilentlyContinue |
        Where-Object { $_.DisplayName -like 'Inno Setup*' -and $_.InstallLocation }
    $candidates = @($installed | ForEach-Object { Join-Path $_.InstallLocation 'ISCC.exe' }) +
        "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe",
        "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe",
        "$env:ProgramFiles\Inno Setup 6\ISCC.exe"
    foreach ($path in $candidates) { if ($path -and (Test-Path $path)) { return $path } }
    throw 'Inno Setup 6 (ISCC.exe) が見つかりません。https://jrsoftware.org/isinfo.php から入れてください。'
}

$version = & $python -c "import sys; sys.path.insert(0, r'$root'); import ublox_monitor; print(ublox_monitor.__version__)"
if ($LASTEXITCODE -ne 0) { throw 'バージョンを読めません' }
Write-Host "u-blox Monitor $version"

& $python -m PyInstaller --noconfirm --clean --distpath "$here\dist" --workpath "$here\build" "$here\ublox_monitor.spec"
if ($LASTEXITCODE -ne 0) { throw 'PyInstaller が失敗しました' }

$iscc = Find-Iscc
& $iscc "/DAppVersion=$version" "$here\ublox_monitor.iss"
if ($LASTEXITCODE -ne 0) { throw 'ISCC が失敗しました' }
Write-Host "インストーラ: $here\Output\ublox_monitor_Setup_$version.exe"
