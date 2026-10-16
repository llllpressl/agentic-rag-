@echo off
setlocal
cd /d "%~dp0"
set "AGENTIC_NODE_DIR=%USERPROFILE%\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin"
if exist "%AGENTIC_NODE_DIR%\node.exe" (
  set "PATH=%AGENTIC_NODE_DIR%;%PATH%"
)
set "AGENTIC_PNPM=%USERPROFILE%\.cache\codex-runtimes\codex-primary-runtime\dependencies\bin\fallback\pnpm.cmd"
where pnpm >nul 2>nul
if not errorlevel 1 (
  set "AGENTIC_PNPM=pnpm"
)
if not exist "node_modules\.bin\vite.cmd" (
  call "%AGENTIC_PNPM%" install
  if errorlevel 1 (
    echo Dependency installation failed. Please check Node.js and pnpm.
    pause
    exit /b 1
  )
)
call "%AGENTIC_PNPM%" dev
pause
