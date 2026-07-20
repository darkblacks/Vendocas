@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"
title Inicializador - ERP Financeiro Local

where node >nul 2>nul
if errorlevel 1 (
  echo [ERRO] Node.js nao foi encontrado.
  echo Instale o Node.js LTS e tente novamente.
  pause
  exit /b 1
)

if not exist "backend\node_modules" (
  echo [ERRO] As dependencias do backend ainda nao estao instaladas.
  echo Execute: npm install --prefix backend
  pause
  exit /b 1
)

if not exist "frontend\node_modules" (
  echo [ERRO] As dependencias do frontend ainda nao estao instaladas.
  echo Execute: npm install --prefix frontend
  pause
  exit /b 1
)

echo Iniciando API em http://localhost:3333 ...
start "ERP - Backend API" cmd /k "cd /d ""%~dp0backend"" && title ERP - Backend API && npm run dev"

timeout /t 2 /nobreak >nul

echo Iniciando interface em http://localhost:5173 ...
start "ERP - Frontend React" cmd /k "cd /d ""%~dp0frontend"" && title ERP - Frontend React && npm run dev"

timeout /t 4 /nobreak >nul
start "" "http://localhost:5173"

echo.
echo =====================================================
echo ERP iniciado.
echo.
echo Neste computador: http://localhost:5173
echo Na rede local: use o endereco Network exibido
 echo na janela do Frontend, por exemplo http://192.168.0.10:5173
echo.
echo Nao feche as duas janelas dos servidores enquanto usar o ERP.
echo =====================================================
echo.
pause
endlocal
