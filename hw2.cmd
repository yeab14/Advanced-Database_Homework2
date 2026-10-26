@echo off
REM Short command for the three homework demos.
REM   hw2 Blocking
REM   hw2 RestartBlocking
REM   hw2 NoLiveLocks
REM   hw2 all
cd /d "%~dp0"
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\run.ps1" %*
exit /b %ERRORLEVEL%
