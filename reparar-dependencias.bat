@echo off
setlocal
title Reparar ERP Financeiro

cd /d "%~dp0"

echo ==========================================
echo   REPARANDO DEPENDENCIAS DO ERP
echo ==========================================
echo.

where node >nul 2>nul
if errorlevel 1 (
    echo ERRO: Node.js nao foi encontrado.
    echo Instale o Node.js LTS e tente novamente.
    pause
    exit /b 1
)

call npm config delete proxy >nul 2>nul
call npm config delete https-proxy >nul 2>nul
call npm config set registry https://registry.npmjs.org/

echo.
echo [1/2] Instalando backend...
cd /d "%~dp0backend"

if exist package-lock.json del /f /q package-lock.json
if exist node_modules rmdir /s /q node_modules

call npm install
if errorlevel 1 (
    echo.
    echo ERRO AO INSTALAR O BACKEND.
    pause
    exit /b 1
)

call npm install --save-dev tsx typescript @types/node
if errorlevel 1 (
    echo.
    echo ERRO AO INSTALAR TSX NO BACKEND.
    pause
    exit /b 1
)

echo.
echo [2/2] Instalando frontend...
cd /d "%~dp0frontend"

if exist package-lock.json del /f /q package-lock.json
if exist node_modules rmdir /s /q node_modules

call npm install
if errorlevel 1 (
    echo.
    echo ERRO AO INSTALAR O FRONTEND.
    pause
    exit /b 1
)

call npm install --save-dev browserslist
if errorlevel 1 (
    echo.
    echo ERRO AO INSTALAR BROWSERSLIST.
    pause
    exit /b 1
)

echo.
echo ==========================================
echo   DEPENDENCIAS REPARADAS COM SUCESSO
echo ==========================================
echo.
echo Agora execute: abrir-servidores.bat
echo.
pause
