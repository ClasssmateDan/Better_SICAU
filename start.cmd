@echo off
setlocal
if not exist "%~dp0index.html" (
  echo Missing index.html. Run npm run build in this folder first.
  pause
  exit /b 1
)
start "" "%~dp0index.html"
