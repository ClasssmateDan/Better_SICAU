@echo off
if not exist "%~dp0KeJianReminders.exe" (
  echo Missing KeJianReminders.exe. Run npm run build:desktop first.
  pause
  exit /b 1
)
start "" "%~dp0KeJianReminders.exe"
