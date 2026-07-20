# ERP Financeiro Local

Sistema totalmente local feito com React, TypeScript, Node.js e SQLite.

## Como iniciar no Windows

1. Instale o Node.js LTS.
2. Extraia o ZIP em uma pasta fora do OneDrive, se possível.
3. Clique duas vezes em `iniciar.bat`.
4. Na primeira execução, as dependências serão instaladas.
5. O sistema abrirá em `http://localhost:5173`.

O banco fica em `backend/data/financeiro.db` e é criado automaticamente com os dados iniciais.

## Funcionalidades

- Dashboard com saldo, entradas, saídas e valores a receber.
- Cadastro, edição e exclusão de lançamentos.
- Controle de contas a receber.
- Botão "Marcar como pago", que cria automaticamente uma entrada.
- Eventos e visão de custo líquido.
- Contas recorrentes.
- Exportação de movimentações e contas a receber para XLSX.
- Funcionamento local, sem conta e sem servidor externo.
