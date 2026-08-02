; Handoff plugin installer -- see issue #34.
;
; Serves two purposes with the same compiled EXE:
;  - First-time manual install: pilot downloads Handoff-Setup-vX.Y.Z.exe and double-clicks it.
;  - Auto-update: the plugin (PluginUpdateModel.cs) downloads and sha256-verifies this same EXE,
;    then launches it with /SILENT /SUPPRESSMSGBOXES /NORESTART -- /SILENT (not /VERYSILENT) so the
;    pilot sees Inno's install progress window, while every wizard page/button stays suppressed
;    (issue #85).
;
; No options to pick (no components/tasks pages, no directory page -- the install location is
; resolved from the registry, not chosen by the user) and no admin rights required: both the
; HKCU\Software\vPilot registry key and the per-user Plugins folder it points at are per-user,
; see CLAUDE.md's "vPilot install location is user-configurable" note.
;
; {app} is our own %LOCALAPPDATA%\Handoff folder (holding just the uninstaller + icon), NOT the
; vPilot Plugins folder -- the plugin files themselves are placed into the Plugins folder directly
; (DestDir: {code:GetPluginsDir}) so it stays clean of unins000.exe/.dat (issue #79).
;
; Built via: ISCC.exe /DMyAppVersion=<version> /DSourceDir=<path to plugin\publish\plugin>
;   [/DChangelogFile=<path to a .txt/.rtf shown on the install page>] Handoff-Setup.iss
; (release.yml passes MyAppVersion+SourceDir+ChangelogFile -- an RTF pandoc-rendered from this
; version's release notes; a local double-click compiles from the IDE defaults below instead,
; falling back to the plain-text changelog-fallback.txt for the install page. Inno's InfoBeforeFile
; auto-detects RTF vs plain text from the content, so either works.)

#ifndef MyAppVersion
  #define MyAppVersion "0.0.0-dev"
#endif
#ifndef SourceDir
  #define SourceDir "..\publish\plugin"
#endif
#ifndef ChangelogFile
  #define ChangelogFile "changelog-fallback.txt"
#endif

[Setup]
AppId={{2072C121-51A1-4693-AACE-32D25495E96F}
AppName=Handoff Plugin
AppVersion={#MyAppVersion}
AppPublisher=sushi.at
; {app} is our own folder for the uninstaller + icon, not the vPilot Plugins dir (see header and
; the [Files] section, which places the plugin itself into {code:GetPluginsDir} directly).
DefaultDirName={localappdata}\Handoff
; The install location is fixed (our own folder); never reuse a previous install's dir -- a 0.1.0
; install used the Plugins folder as {app}, and we deliberately move off it here (issue #79).
UsePreviousAppDir=no
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
; Icon for the setup .exe itself and for the Windows Apps/Add-Remove-Programs entry (issue #79).
SetupIconFile=..\Assets\handoff.ico
UninstallDisplayIcon={app}\handoff.ico
; Shown on the one visible wizard page in non-silent mode -- the current version's changelog
; (release.yml passes the extracted release notes; local builds get changelog-fallback.txt).
; Silent installs skip this page entirely -- both /VERYSILENT and the auto-updater's /SILENT
; suppress all wizard pages (the InfoBeforeFile page included); /SILENT only adds back the
; install *progress* window, not any page the pilot has to click through (issue #85).
InfoBeforeFile={#ChangelogFile}
WizardStyle=modern
SetupLogging=yes

[Files]
; The plugin itself goes into the vPilot Plugins folder (resolved from the registry), NOT {app} --
; that's what vPilot loads. Inno still records these in {app}'s unins000.dat regardless of DestDir,
; so uninstall removes them from the Plugins folder correctly.
Source: "{#SourceDir}\Handoff.Plugin.dll"; DestDir: "{code:GetPluginsDir}"; Flags: ignoreversion
Source: "{#SourceDir}\Newtonsoft.Json.dll"; DestDir: "{code:GetPluginsDir}"; Flags: ignoreversion
Source: "{#SourceDir}\Fleck.dll"; DestDir: "{code:GetPluginsDir}"; Flags: ignoreversion
Source: "{#SourceDir}\RadioHost\*"; DestDir: "{code:GetPluginsDir}\RadioHost"; Flags: ignoreversion recursesubdirs createallsubdirs
; Into {app} (our own folder) alongside the uninstaller -- referenced by UninstallDisplayIcon.
Source: "..\Assets\handoff.ico"; DestDir: "{app}"; Flags: ignoreversion

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

// A 0.1.0 install used the Plugins folder as {app}, so its unins000.exe/.dat landed there. We now
// install to our own {app} (%LOCALAPPDATA%\Handoff) and leave the Plugins folder holding only the
// plugin files, so those orphaned uninstaller files would just sit there forever -- clean them up
// once, on upgrade. Best-effort: a leftover file isn't worth failing the install over.
procedure RemoveStalePluginsUninstaller();
var
  PluginsDir: String;
begin
  PluginsDir := GetPluginsDir('');
  if PluginsDir = '' then Exit;
  DeleteFile(AddBackslash(PluginsDir) + 'unins000.exe');
  DeleteFile(AddBackslash(PluginsDir) + 'unins000.dat');
end;

// One-shot marker PluginUpdateModel.cs reads on next plugin load to report the update through
// operationProgress -- see docs/protocol.md. Only written on an upgrade, not a fresh install
// (nothing to report "updated from" in that case). Written into the Plugins folder (next to the
// plugin DLL), where CheckMarker reads it from -- {app} is our own folder now, not the Plugins dir.
procedure CurStepChanged(CurStep: TSetupStep);
var
  MarkerPath: String;
  JsonContent: String;
begin
  if CurStep = ssPostInstall then
  begin
    RemoveStalePluginsUninstaller();

    if WasAlreadyInstalled then
    begin
      JsonContent := '{"version":"' + '{#MyAppVersion}' + '","installedAt":"' +
        GetDateTimeString('yyyy-mm-dd"T"hh:nn:ss', #0, #0) + '"}';
      MarkerPath := AddBackslash(GetPluginsDir('')) + 'update-applied.json';
      SaveStringToFile(MarkerPath, JsonContent, False);
    end;
  end;
end;
