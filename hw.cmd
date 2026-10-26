@echo off
REM Windows. From the homework2 folder:
REM   hw Blocking
REM   hw RestartBlocking
REM   hw NoLiveLocks
REM   hw all
cd /d "%~dp0"
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\run.ps1" %*
exit /b %ERRORLEVEL%
