@echo off
echo Stopping Knowledge Server...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8080') do (
  if not "%%a"=="" (
    taskkill /f /pid %%a >nul 2>&1
  )
)
echo Done.
