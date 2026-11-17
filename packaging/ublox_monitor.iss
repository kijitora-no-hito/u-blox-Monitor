; u-blox Monitor のインストーラ。build.ps1 から使う(AppVersion は build.ps1 が渡す)。
#ifndef AppVersion
  #define AppVersion "0.0.0"
#endif

[Setup]
AppId={{9AE26938-FE8D-4B5B-88D1-430540F8F118}
AppName=u-blox Monitor
AppVersion={#AppVersion}
AppPublisher=Rei Furukawa (kijitora-no-hito)
AppPublisherURL=https://github.com/kijitora-no-hito/u-blox-Monitor
DefaultDirName={autopf}\ublox_monitor
DefaultGroupName=u-blox Monitor
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir=Output
OutputBaseFilename=ublox_monitor_Setup_{#AppVersion}
Compression=lzma2
SolidCompression=yes
LicenseFile=..\LICENSE
UninstallDisplayIcon={app}\ublox_monitor.exe
UninstallDisplayName=u-blox Monitor

[Languages]
Name: "japanese"; MessagesFile: "compiler:Languages\Japanese.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
Source: "dist\ublox_monitor\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "..\LICENSE"; DestDir: "{app}"; DestName: "LICENSE.txt"; Flags: ignoreversion

[InstallDelete]
; 古い版のファイルが残って新しい版と混ざらないよう、上書き前に消す
Type: filesandordirs; Name: "{app}\_internal"

[Icons]
Name: "{autoprograms}\u-blox Monitor"; Filename: "{app}\ublox_monitor.exe"
Name: "{autodesktop}\u-blox Monitor"; Filename: "{app}\ublox_monitor.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\ublox_monitor.exe"; Description: "{cm:LaunchProgram,u-blox Monitor}"; Flags: nowait postinstall skipifsilent
