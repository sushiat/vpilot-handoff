@echo off
REM Builds and runs Handoff.ReplayTool's --random-test batch mode (issue #9) from the repo
REM root, regardless of where it's invoked from. Output goes to ReplayTests\<timestamp>\
REM (see .gitignore -- not tracked).
REM
REM Usage:
REM   run-replay-tests.bat                  -- 100 random flights, fresh random seed
REM   run-replay-tests.bat 25               -- 25 random flights
REM   run-replay-tests.bat 25 --seed 42      -- 25 flights, reproducible seed
REM   run-replay-tests.bat 25 --seed 42 --out SomeOtherFolder

setlocal
cd /d "%~dp0"

echo Building Handoff.ReplayTool...
dotnet build plugin\Handoff.ReplayTool\Handoff.ReplayTool.csproj -v q
if errorlevel 1 (
    echo Build failed.
    exit /b 1
)

if "%~1"=="" (
    plugin\Handoff.ReplayTool\bin\Debug\net48\Handoff.ReplayTool.exe --random-test 100 --out ReplayTests
) else (
    plugin\Handoff.ReplayTool\bin\Debug\net48\Handoff.ReplayTool.exe --random-test %* --out ReplayTests
)
