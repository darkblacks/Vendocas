@echo off
chcp 65001 > nul
cd /d "%~dp0"
title ERP Financeiro Local

where node >nul 2>nul
if errorlevel 1 (
  echo Node.js nao foi encontrado.
  echo Instale a versao LTS em https://nodejs.org e execute este arquivo novamente.
  pause
  exit /b 1
)

if not exist "node_modules" (
  echo Instalando gerenciador do projeto...
  call npm install
  if errorlevel 1 goto erro
)

if not exist "backend\node_modules" (
  echo Instalando backend...
  call npm install --prefix backend
  if errorlevel 1 goto erro
)

if not exist "frontend\node_modules" (
  echo Instalando frontend...
  call npm install --prefix frontend
  if errorlevel 1 goto erro
)

echo.
echo Abrindo ERP Financeiro Local...
start "" cmd /c "timeout /t 4 /nobreak > nul && start http://localhost:5173"
call npm run dev
goto fim

:erro
echo.
echo Ocorreu um erro durante a instalacao.
pause

:fim
