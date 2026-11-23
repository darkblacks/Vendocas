# Ambiente, pagamentos e emissão fiscal

Quatro opções **independentes**, guardadas no banco da API (`platform_settings`), versionadas e auditadas:

| Opção | Valores | Padrão |
|---|---|---|
| Ambiente | Homologação · Produção | Homologação |
| Stripe | Teste · Produção | Teste |
| Emissão fiscal automática | Ativada · Desativada | Desativada |
| Provedor fiscal | Nenhum · Focus NFe | Nenhum |

Nenhuma implica outra: a homologação pode usar o Stripe em produção (testes de integração com dinheiro real), a
produção pode rodar com emissão automática desligada, e o provedor pode ser escolhido antes de ligar a automação. Não
existem planos, usuários ou regras comerciais especiais por ambiente.

## Onde configurar

* **Control Center → Pagamentos e fiscal** (OWNER, com reautenticação). Mostra, para a API de homologação e para a de
  produção, quais serviços estão ativos, desativados ou incompletos, os avisos e a contagem de documentos fiscais.
  Para alterar daqui, cadastre em *Credenciais → API Vendocas* o `CONTROL_ADMIN_TOKEN` daquela API (no Codespace:
  `bash infrastructure/codespaces/show-admin-token.sh`). Sem ele a página é somente leitura. O token de status continua
  sendo só de leitura.
* **API de administração** (operador da plataforma com MFA): `GET/PUT /api/v1/admin/platform-settings`
  (`expectedVersion` evita sobrescrever a alteração de outra pessoa).

Toda alteração vai para `audit_logs` (`platform.settings.updated`, com antes → depois e quem alterou) e, quando feita
pelo Control Center, também para a auditoria dele.

## Stripe

* O modo selecionado precisa bater com a chave `STRIPE_SECRET_KEY` da API (`sk_/rk_test_` = Teste, `sk_/rk_live_` =
  Produção). Se não bater, **toda chamada de saída ao Stripe é bloqueada** (`PROVIDER_MODE_MISMATCH`): checkout, novas
  assinaturas, mudança de plano, cancelamento, portal, compra de créditos. Assim uma chave de produção nunca é usada com
  “Teste” selecionado, nem o contrário.
* **Webhooks são sempre processados**, em qualquer combinação, para nunca perder um pagamento real.
* Cada pagamento e fatura guarda `livemode` (do Stripe) e o pagamento guarda o `environment` em que foi registrado, para
  separar na conciliação os testes com dinheiro real feitos em homologação.
* Trocar de modo também exige preços (`prices.stripe_price_id`) e webhooks do modo correspondente no painel do Stripe.

Fluxos mantidos sem mudança de contrato: checkout, criação de assinaturas, cobranças recorrentes, confirmação
(`invoice.paid`), falhas (`invoice.payment_failed`, `overdue`, `uncollectible`), cancelamentos, webhooks e reembolsos
(`charge.refunded`).

## Billing e Fiscal separados

```
Stripe ──webhook──► Billing (StripeEventProcessor)
                     • registra fatura, pagamento, assinatura (uma transação)
                     • publica no outbox: billing.invoice.paid / billing.payment.refunded
                                │  (mesma transação; entregue depois, no máximo uma vez por consumidor)
                                ▼
                    Fiscal (FiscalEventHandler → FiscalDocuments → job fiscal.issue → FocusNfeProvider)
```

O Billing não conhece o módulo fiscal. Falha, lentidão ou ausência do provedor fiscal **nunca** bloqueia pagamentos,
assinaturas ou renovações: o evento do outbox é reprocessado depois, sem afetar o que já foi gravado.

### O que o módulo fiscal faz com uma fatura paga

A obrigação é decidida pela regra legal (vendedor brasileiro vendendo a cliente no Brasil → NFS-e), não por uma chave
de configuração. Depois:

| Emissão automática | Provedor | Resultado (`tax_documents.status`) | Chama a Focus NFe? |
|---|---|---|---|
| Desativada | qualquer | `AWAITING_MANUAL` — aguardando emissão manual | não |
| Ativada | Nenhum | `NOT_CONFIGURED` | não |
| Ativada | Focus NFe sem `FOCUS_NFE_TOKEN` | `NOT_CONFIGURED` | não |
| Ativada | Focus NFe configurada | `PENDING` → `PROCESSING` → `ISSUED`/`REJECTED` | sim |

**Desativar a automação não dispensa a obrigação fiscal**: o registro fica pendente até ser emitido. Se a automação for
desligada com emissões já na fila, elas voltam para `AWAITING_MANUAL` sem chamar o provedor.

Reembolso (`billing.payment.refunded`) **não cancela nota automaticamente**: marca o documento com `needs_review`
e o motivo, para decisão do operador (cancelamento ou nota de ajuste, conforme o município).

### Emissão manual ou posterior (operador da plataforma)

| Ação | Endpoint |
|---|---|
| Listar documentos (ex.: pendentes) | `GET /api/v1/admin/fiscal/documents?status=AWAITING_MANUAL` |
| Emitir agora pela Focus NFe (mesmo com a automação desligada) | `POST /api/v1/admin/fiscal/invoices/{invoiceId}/issue` |
| Registrar nota emitida fora da plataforma (ex.: portal da prefeitura) | `POST /api/v1/admin/fiscal/invoices/{invoiceId}/record-external` `{number, issuedAt?, notes?}` |

### Consulta, exportação e conciliação

`GET /api/v1/admin/billing/payments?from=&to=&status=&fiscalStatus=&format=json|csv` — pagamentos com fatura,
`livemode`, ambiente e situação fiscal. O CSV protege contra injeção de fórmulas em planilhas. Nenhum registro de
cobrança ou transação é apagado ou alterado pela configuração fiscal.

## Ativar a Focus NFe depois

1. Na API: `FOCUS_NFE_TOKEN`, `FOCUS_NFE_ENVIRONMENT=homologacao` e os dados `SELLER_*` (ver [taxation.md](taxation.md)).
2. Control Center: Provedor fiscal = Focus NFe; confira “Provedor fiscal: configurado”.
3. Teste com algumas faturas pendentes usando “emitir agora”.
4. Ligue a emissão automática. Em produção, use `FOCUS_NFE_ENVIRONMENT=producao`; o painel avisa se a produção estiver
   apontando para a homologação da Focus.

## Testes

`BillingFiscalIT` (Postgres real, Stripe e Focus simulados): as **24 combinações** ambiente × Stripe × automação ×
provedor (nenhum / Focus configurada / Focus sem credencial) para uma venda brasileira, verificando em todas que a
assinatura é ativada, o pagamento é registrado com ambiente e `livemode` corretos e a Focus só é chamada quando deve;
mais venda fora do Brasil, queda do provedor fiscal, automação desligada com emissão na fila, reembolso + renovação com
fiscal desligado, emissão manual e externa, exportação CSV, API de configuração (versão, auditoria, permissão),
bloqueio por modo do Stripe e escrita pelo Control Center só com o token de administração. No Control Center,
`tests/platform.test.ts` cobre leitura das duas APIs, alteração com auditoria, conflito de versão, permissões e validação.
