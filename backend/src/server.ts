import express from 'express';
import cors from 'cors';
import ExcelJS from 'exceljs';
import { z } from 'zod';
import { db, initializeDatabase } from './db.js';

initializeDatabase();
const app = express();
app.use(cors());
app.use(express.json());

const transactionSchema = z.object({
  date: z.string().min(1),
  type: z.enum(['Entrada','Saída']),
  amount: z.coerce.number().nonnegative(),
  event: z.string().optional().default(''),
  reason: z.string().min(1),
  description: z.string().optional().default(''),
  recurring: z.boolean().optional().default(false),
  periodicity: z.string().optional().default(''),
  person: z.string().optional().default('')
});

const debtSchema = z.object({
  date: z.string().min(1),
  debtor: z.string().min(1),
  account: z.string().min(1),
  amount: z.coerce.number().positive(),
  reason: z.string().min(1),
  event: z.string().optional().default(''),
  due_date: z.string().optional().default(''),
  notes: z.string().optional().default('')
});

app.get('/api/health', (_req,res) => res.json({ ok: true }));

app.get('/api/dashboard', (_req,res) => {
  const totals = db.prepare(`SELECT
    COALESCE(SUM(CASE WHEN type='Entrada' THEN amount END),0) entries,
    COALESCE(SUM(CASE WHEN type='Saída' THEN amount END),0) expenses
    FROM transactions`).get() as any;
  const receivable = db.prepare(`SELECT COALESCE(SUM(amount),0) total FROM debts WHERE status='Pendente'`).get() as any;
  const recurring = db.prepare(`SELECT COALESCE(SUM(amount),0) total FROM transactions WHERE recurring=1 AND type='Saída'`).get() as any;
  const byReason = db.prepare(`SELECT reason, ROUND(SUM(amount),2) total FROM transactions WHERE type='Saída' GROUP BY reason ORDER BY total DESC LIMIT 8`).all();
  const recent = db.prepare(`SELECT * FROM transactions ORDER BY date DESC, id DESC LIMIT 8`).all();
  res.json({
    entries: totals.entries,
    expenses: totals.expenses,
    balance: totals.entries - totals.expenses,
    receivable: receivable.total,
    projected: totals.entries - totals.expenses + receivable.total,
    recurring: recurring.total,
    byReason,
    recent
  });
});

app.get('/api/transactions', (req,res) => {
  const search = String(req.query.search ?? '').trim();
  const type = String(req.query.type ?? '');
  let sql = 'SELECT * FROM transactions WHERE 1=1';
  const params: unknown[] = [];
  if (search) { sql += ' AND (description LIKE ? OR reason LIKE ? OR event LIKE ? OR person LIKE ?)'; params.push(...Array(4).fill(`%${search}%`)); }
  if (type) { sql += ' AND type=?'; params.push(type); }
  sql += ' ORDER BY date DESC, id DESC';
  res.json(db.prepare(sql).all(...params));
});

app.post('/api/transactions', (req,res) => {
  const data = transactionSchema.parse(req.body);
  const result = db.prepare(`INSERT INTO transactions (date,type,amount,event,reason,description,recurring,periodicity,person) VALUES (?,?,?,?,?,?,?,?,?)`)
    .run(data.date,data.type,data.amount,data.event,data.reason,data.description,data.recurring?1:0,data.periodicity,data.person);
  res.status(201).json({ id: result.lastInsertRowid });
});

app.put('/api/transactions/:id', (req,res) => {
  const data = transactionSchema.parse(req.body);
  db.prepare(`UPDATE transactions SET date=?,type=?,amount=?,event=?,reason=?,description=?,recurring=?,periodicity=?,person=? WHERE id=?`)
    .run(data.date,data.type,data.amount,data.event,data.reason,data.description,data.recurring?1:0,data.periodicity,data.person,req.params.id);
  res.json({ ok:true });
});

app.delete('/api/transactions/:id', (req,res) => {
  db.prepare('DELETE FROM transactions WHERE id=?').run(req.params.id);
  res.json({ ok:true });
});

app.get('/api/debts', (_req,res) => {
  db.prepare(`UPDATE debts SET status='Vencido' WHERE status='Pendente' AND due_date<>'' AND due_date < date('now')`).run();
  res.json(db.prepare('SELECT * FROM debts ORDER BY status DESC, due_date ASC, id DESC').all());
});

app.post('/api/debts', (req,res) => {
  const data = debtSchema.parse(req.body);
  const result = db.prepare(`INSERT INTO debts (date,debtor,account,amount,reason,event,due_date,notes) VALUES (?,?,?,?,?,?,?,?)`)
    .run(data.date,data.debtor,data.account,data.amount,data.reason,data.event,data.due_date,data.notes);
  res.status(201).json({ id: result.lastInsertRowid });
});

app.post('/api/debts/:id/pay', (req,res) => {
  const debt = db.prepare('SELECT * FROM debts WHERE id=?').get(req.params.id) as any;
  if (!debt) return res.status(404).json({ error:'Conta não encontrada' });
  if (debt.status === 'Pago') return res.status(409).json({ error:'Conta já está paga' });
  const paymentDate = req.body.payment_date || new Date().toISOString().slice(0,10);
  db.exec('BEGIN');
  try {
    const entry = db.prepare(`INSERT INTO transactions (date,type,amount,event,reason,description,person,debt_id) VALUES (?,?,?,?,?,?,?,?)`)
      .run(paymentDate,'Entrada',debt.amount,debt.event || '',`Pagamento de dívida — ${debt.debtor}`,debt.account,debt.debtor,debt.id);
    db.prepare(`UPDATE debts SET status='Pago', payment_date=?, transaction_entry_id=? WHERE id=?`).run(paymentDate,entry.lastInsertRowid,debt.id);
    db.exec('COMMIT');
    res.json({ ok:true, transactionId: entry.lastInsertRowid });
  } catch (error) {
    db.exec('ROLLBACK');
    throw error;
  }
});

app.get('/api/events', (_req,res) => {
  const rows = db.prepare(`SELECT event,
    ROUND(SUM(CASE WHEN type='Entrada' THEN amount ELSE 0 END),2) entries,
    ROUND(SUM(CASE WHEN type='Saída' THEN amount ELSE 0 END),2) expenses
    FROM transactions WHERE event IS NOT NULL AND event<>'' GROUP BY event ORDER BY MAX(date) DESC`).all() as any[];
  res.json(rows.map(r => ({...r, net: r.entries-r.expenses})));
});

app.get('/api/recurring', (_req,res) => res.json(db.prepare(`SELECT * FROM transactions WHERE recurring=1 ORDER BY date DESC`).all()));

app.get('/api/export.xlsx', async (_req,res) => {
  const workbook = new ExcelJS.Workbook();
  workbook.creator = 'ERP Financeiro Local';
  const money = 'R$ #,##0.00';
  const txSheet = workbook.addWorksheet('Movimentações');
  txSheet.columns = [
    {header:'ID',key:'id',width:8},{header:'Data',key:'date',width:14},{header:'Movimento',key:'type',width:14},
    {header:'Valor',key:'amount',width:15,style:{numFmt:money}},{header:'Evento',key:'event',width:22},
    {header:'Motivo',key:'reason',width:25},{header:'Descrição',key:'description',width:45},
    {header:'Conta recorrente',key:'recurring',width:18},{header:'Periodicidade',key:'periodicity',width:16},{header:'Pessoa / Conta',key:'person',width:20}
  ];
  const txs = db.prepare('SELECT * FROM transactions ORDER BY date,id').all() as any[];
  txs.forEach(t => txSheet.addRow({...t, recurring:t.recurring?'Sim':'Não'}));
  const debtSheet = workbook.addWorksheet('Contas a receber');
  debtSheet.columns = [
    {header:'ID',key:'id',width:8},{header:'Data',key:'date',width:14},{header:'Devedor',key:'debtor',width:20},
    {header:'Conta',key:'account',width:28},{header:'Valor',key:'amount',width:15,style:{numFmt:money}},
    {header:'Motivo',key:'reason',width:28},{header:'Evento',key:'event',width:22},{header:'Data limite',key:'due_date',width:15},
    {header:'Status',key:'status',width:14},{header:'Data pagamento',key:'payment_date',width:18},{header:'Observações',key:'notes',width:45}
  ];
  (db.prepare('SELECT * FROM debts ORDER BY date,id').all() as any[]).forEach(d => debtSheet.addRow(d));
  [txSheet,debtSheet].forEach(sheet => {
    sheet.views = [{state:'frozen',ySplit:1}];
    sheet.getRow(1).font = {bold:true,color:{argb:'FFFFFFFF'}};
    sheet.getRow(1).fill = {type:'pattern',pattern:'solid',fgColor:{argb:'FF1E293B'}};
    sheet.autoFilter = {from:{row:1,column:1},to:{row:1,column:sheet.columnCount}};
  });
  const buffer = await workbook.xlsx.writeBuffer();
  res.setHeader('Content-Type','application/vnd.openxmlformats-officedocument.spreadsheetml.sheet');
  res.setHeader('Content-Disposition','attachment; filename="relatorio-financeiro.xlsx"');
  res.send(Buffer.from(buffer));
});

app.use((err:any,_req:any,res:any,_next:any) => {
  console.error(err);
  res.status(400).json({ error: err?.issues?.[0]?.message || err.message || 'Erro inesperado' });
});

app.listen(3333, () => console.log('API local em http://localhost:3333'));
