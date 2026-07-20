@echo off
chcp 65001 >nul
title Fechar ERP Financeiro Local
echo Encerrando os processos Node.js do ERP...
taskkill /F /IM node.exe >nul 2>nul
if errorlevel 1 (
  echo Nenhum processo Node.js estava aberto.
) else (
  echo Servidores encerrados.
)
pause
