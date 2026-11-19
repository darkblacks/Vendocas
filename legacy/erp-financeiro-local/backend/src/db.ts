import { DatabaseSync } from 'node:sqlite';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const dataDir = path.resolve(here, '../data');
fs.mkdirSync(dataDir, { recursive: true });

export const db = new DatabaseSync(path.join(dataDir, 'financeiro.db'));
db.exec('PRAGMA journal_mode = WAL;');
db.exec('PRAGMA foreign_keys = ON;');

export function initializeDatabase() {
  db.exec(`
    CREATE TABLE IF NOT EXISTS transactions (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      date TEXT NOT NULL,
      type TEXT NOT NULL CHECK(type IN ('Entrada','Saída')),
      amount REAL NOT NULL CHECK(amount >= 0),
      event TEXT,
      reason TEXT NOT NULL,
      description TEXT,
      recurring INTEGER NOT NULL DEFAULT 0,
      periodicity TEXT,
      person TEXT,
      debt_id INTEGER,
      created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
    );

    CREATE TABLE IF NOT EXISTS debts (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      date TEXT NOT NULL,
      debtor TEXT NOT NULL,
      account TEXT NOT NULL,
      amount REAL NOT NULL CHECK(amount >= 0),
      reason TEXT NOT NULL,
      event TEXT,
      due_date TEXT,
      status TEXT NOT NULL DEFAULT 'Pendente' CHECK(status IN ('Pendente','Pago','Vencido','Cancelado')),
      payment_date TEXT,
      transaction_entry_id INTEGER,
      notes TEXT,
      created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
      FOREIGN KEY(transaction_entry_id) REFERENCES transactions(id)
    );

    CREATE INDEX IF NOT EXISTS idx_transactions_date ON transactions(date);
    CREATE INDEX IF NOT EXISTS idx_transactions_event ON transactions(event);
    CREATE INDEX IF NOT EXISTS idx_debts_status ON debts(status);
  `);

  const count = db.prepare('SELECT COUNT(*) AS total FROM transactions').get() as { total: number };
  if (count.total === 0) seed();
}

function seed() {
  const transactions = [
    ['2026-07-12','Entrada',500,'','Saldo inicial','Saldo disponível no início do controle',0,'',''],
    ['2026-07-12','Saída',5.40,'','Transporte','Transporte',0,'',''],
    ['2026-07-12','Saída',5.90,'','Transporte','Transporte',0,'',''],
    ['2026-07-12','Saída',34,'Minha festa','Mercado','Frutas para fazer drinks',0,'',''],
    ['2026-07-12','Saída',3.24,'Minha festa','Mercado','Produtos para a festa',0,'',''],
    ['2026-07-12','Saída',53.99,'Minha festa','Mercado','Compras para a festa',0,'',''],
    ['2026-07-12','Saída',25.98,'Minha festa','Mercado','Compras para a festa',0,'',''],
    ['2026-07-12','Saída',64.90,'','Alimentação','Fast-food antes da festa',0,'',''],
    ['2026-07-12','Saída',19,'Minha festa','Mercado','Compras para a festa',0,'',''],
    ['2026-07-12','Saída',14,'Minha festa','Mercado','Gelo para a festa',0,'',''],
    ['2026-07-12','Saída',33.37,'Minha festa','Mercado','Compras para a festa',0,'',''],
    ['2026-07-12','Saída',25.99,'Minha festa','Roupa / item pessoal','Cobertor comprado e depois vendido para Drielly',0,'',''],
    ['2026-07-12','Saída',39.95,'Minha festa','Transporte','Volta da festa',0,'',''],
    ['2026-07-13','Entrada',60,'Minha festa','Recebimento','PIX Drielly: R$ 25,99 cobertor + R$ 25,00 festa + R$ 9,01 cortesia',0,'','Drielly'],
    ['2026-07-13','Saída',25,'Venda da moto','Manutenção da moto','Manutenção relacionada à venda da moto',0,'',''],
    ['2026-07-13','Entrada',60,'Minha festa','Recebimento','PIX Murilo: R$ 25,00 festa + R$ 32,45 fast-food + R$ 2,55 cortesia',0,'','Murilo'],
    ['2026-07-13','Saída',50,'Venda da moto','Combustível','Combustível relacionado à venda da moto',0,'',''],
    ['2026-07-13','Entrada',7500,'Venda da moto','Venda de bem','Recebimento pela venda da moto',0,'',''],
    ['2026-07-13','Saída',4000,'Venda da moto','Divisão de bens','Repasse para Drielly',0,'','Drielly'],
    ['2026-07-13','Saída',151.56,'Minha festa','Roupa','Presente de aniversário para mim mesmo',0,'',''],
    ['2026-07-13','Saída',59.99,'','Roupa','Compra na C&A',0,'',''],
    ['2026-07-13','Saída',31.28,'','Transporte','Transporte para ver a Drielly',0,'','Drielly'],
    ['2026-07-13','Saída',60,'','Alimentação','PIX para Drielly — restaurante juntos',0,'','Drielly'],
    ['2026-07-13','Entrada',25,'Minha festa','Recebimento','Pagamento do rateio da festa pelo Igor',0,'','Igor'],
    ['2026-07-13','Saída',54.78,'','Transporte','Uber de volta para casa',0,'',''],
    ['2026-07-14','Saída',103.92,'','Saúde / Academia','TotalPass para voltar a treinar',1,'Mensal','TotalPass'],
    ['2026-07-14','Saída',150,'','Internet','Conta de internet',1,'Mensal','Internet']
  ];

  const insertTransaction = db.prepare(`
    INSERT INTO transactions (date,type,amount,event,reason,description,recurring,periodicity,person)
    VALUES (?,?,?,?,?,?,?,?,?)
  `);
  db.exec('BEGIN');
  try { transactions.forEach(t => insertTransaction.run(...t)); db.exec('COMMIT'); }
  catch (error) { db.exec('ROLLBACK'); throw error; }

  const debts = [
    ['2026-07-12','Drielly','Cobertor',25.99,'Venda de item','Minha festa','2026-07-13','Pago','2026-07-13',14,'Parte do PIX de R$ 60,00'],
    ['2026-07-12','Drielly','Rateio da festa',25,'Divisão de despesas','Minha festa','2026-07-13','Pago','2026-07-13',14,'R$ 9,01 restantes foram cortesia'],
    ['2026-07-12','Murilo','Rateio da festa',25,'Divisão de despesas','Minha festa','2026-07-13','Pago','2026-07-13',16,'Parte do PIX de R$ 60,00'],
    ['2026-07-12','Murilo','Metade do fast-food',32.45,'Alimentação compartilhada','','2026-07-13','Pago','2026-07-13',16,'R$ 2,55 restantes foram cortesia'],
    ['2026-07-12','Igor','Rateio da festa',25,'Divisão de despesas','Minha festa','2026-07-13','Pago','2026-07-13',24,'Pagamento integral']
  ];
  const insertDebt = db.prepare(`
    INSERT INTO debts (date,debtor,account,amount,reason,event,due_date,status,payment_date,transaction_entry_id,notes)
    VALUES (?,?,?,?,?,?,?,?,?,?,?)
  `);
  db.exec('BEGIN');
  try { debts.forEach(d => insertDebt.run(...d)); db.exec('COMMIT'); }
  catch (error) { db.exec('ROLLBACK'); throw error; }
}
