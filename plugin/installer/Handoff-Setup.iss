; Handoff plugin installer -- see issue #34.
;
; Serves two purposes with the same compiled EXE:
;  - First-time manual install: pilot downloads Handoff-Setup-vX.Y.Z.exe and double-clicks it.
;  - Auto-update: the plugin (PluginUpdateModel.cs) downloads and sha256-verifies this same EXE,
;    then launches it with /VERYSILENT /SUPPRESSMSGBOXES /NORESTART.
;
; No options to pick (no components/tasks pages, no directory page -- the install location is
; resolved from the registry, not chosen by the user) and no admin rights required: both the
; HKCU\Software\vPilot registry key and the per-user Plugins folder it points at are per-user,
; see CLAUDE.md's "vPilot install location is user-configurable" note.
;
; Built via: ISCC.exe /DMyAppVersion=<version> /DSourceDir=<path to plugin\publish\plugin> Handoff-Setup.iss
; (release.yml passes both; local double-click compiles from the IDE default below instead.)

#ifndef MyAppVersion
  #define MyAppVersion "0.0.0-dev"
#endif
#ifndef SourceDir
  #define SourceDir "..\publish\plugin"
#endif

[Setup]
AppId={{2072C121-51A1-4693-AACE-32D25495E96F}
AppName=Handoff Plugin
AppVersion={#MyAppVersion}
AppPublisher=sushi.at
DefaultDirName={code:GetPluginsDir}
DisableDirPage=yes
DisableWelcomePage=yes
DisableReadyPage=yes
DisableProgramGroupPage=yes
DisableFinishedPage=yes
PrivilegesRequired=lowest
OutputDir=.
OutputBaseFilename=Handoff-Setup-v{#MyAppVersion}
Compression=lzma
SolidCompression=yes
ArchitecturesInstallIn64BitMode=x64compatible
UninstallDisplayName=Handoff Plugin
WizardStyle=modern
SetupLogging=yes

[Files]
Source: "{#SourceDir}\Handoff.Plugin.dll"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#SourceDir}\Newtonsoft.Json.dll"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#SourceDir}\Fleck.dll"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#SourceDir}\RadioHost\*"; DestDir: "{app}\RadioHost"; Flags: ignoreversion recursesubdirs createallsubdirs

[Code]
var
  VPilotInstallDir: String;
  PluginsDirCache: String;
  WasAlreadyInstalled: Boolean;

// Reads HKCU\Software\vPilot\Install_Dir -- the only authoritative source for where vPilot (and
// therefore its Plugins folder) actually lives; the installer's default %LOCALAPPDATA%\vPilot is
// not something this project may assume, see CLAUDE.md's Resolved section.
function GetVPilotInstallDir(): String;
begin
  if VPilotInstallDir = '' then
  begin
    if not RegQueryStringValue(HKCU, 'Software\vPilot', 'Install_Dir', VPilotInstallDir) then
      VPilotInstallDir := '';
  end;
  Result := VPilotInstallDir;
end;

function GetPluginsDir(Param: String): String;
begin
  if PluginsDirCache = '' then
  begin
    if GetVPilotInstallDir() <> '' then
      PluginsDirCache := AddBackslash(VPilotInstallDir) + 'Plugins';
  end;
  Result := PluginsDirCache;
end;

// Standard Inno Setup WMI recipe for "is this process running" -- no external tasklist parsing,
// and WMI process queries don't need admin rights either.
function IsAppRunning(const FileName: string): Boolean;
var
  FSWbemLocator: Variant;
  FWMIService: Variant;
  FWbemObjectSet: Variant;
begin
  Result := False;
  try
    FSWbemLocator := CreateOleObject('WbemScripting.SWbemLocator');
    FWMIService := FSWbemLocator.ConnectServer('', 'root\CIMV2', '', '');
    FWbemObjectSet := FWMIService.ExecQuery(Format('SELECT Name FROM Win32_Process WHERE Name="%s"', [FileName]));
    Result := (FWbemObjectSet.Count > 0);
  except
    Result := False; // best-effort -- if WMI itself is unavailable, don't block install forever
  end;
end;

// The plugin DLL can't be overwritten while vPilot has it loaded -- wait rather than fail, since
// this runs unattended (either double-clicked in the background or launched silently by the
// auto-updater while vPilot is still open).
procedure WaitForVPilotToExit();
begin
  while IsAppRunning('vPilot.exe') do
    Sleep(2000);
end;

function InitializeSetup(): Boolean;
begin
  Result := True;
  if GetVPilotInstallDir() = '' then
  begin
    MsgBox('vPilot installation not found (HKCU\Software\vPilot\Install_Dir is missing). ' +
      'Install vPilot first, then run this installer again.', mbCriticalError, MB_OK);
    Result := False;
    Exit;
  end;

  WasAlreadyInstalled := FileExists(AddBackslash(GetPluginsDir('')) + 'Handoff.Plugin.dll');
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  NeedsRestart := False;
  WaitForVPilotToExit();
  Result := '';
end;

// One-shot marker PluginUpdateModel.cs reads on next plugin load to report the update through
// operationProgress -- see docs/protocol.md. Only written on an upgrade, not a fresh install
// (nothing to report "updated from" in that case).
procedure CurStepChanged(CurStep: TSetupStep);
var
  MarkerPath: String;
  JsonContent: String;
begin
  if (CurStep = ssPostInstall) and WasAlreadyInstalled then
  begin
    JsonContent := '{"version":"' + '{#MyAppVersion}' + '","installedAt":"' +
      GetDateTimeString('yyyy-mm-dd"T"hh:nn:ss', #0, #0) + '"}';
    MarkerPath := ExpandConstant('{app}') + '\update-applied.json';
    SaveStringToFile(MarkerPath, JsonContent, False);
  end;
end;
