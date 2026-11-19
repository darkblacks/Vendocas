# Faturamento

## Cartão (Stripe Billing + Checkout)
`POST /billing/subscription {planCode, method:"CARD"}` → cliente Stripe (com tax ID) → Checkout `mode=subscription`,
`automatic_tax`, coleta de tax ID, idempotency key → redireciona. Ativa somente no webhook (`checkout.session.completed`
pago ou `invoice.paid`). Cartão/CVV nunca tocam nossos servidores. Atualização de cartão: Billing Portal.

## Fatura empresarial (sem cartão)
`method:"INVOICE"` (exige identificação fiscal de pessoa jurídica) → assinatura Stripe `collection_method=send_invoice`,
`days_until_due` (padrão 10). Os meios oferecidos na fatura hospedada (transferência, boleto, ACH, SEPA…) são os habilitados
na conta Stripe do vendedor. Separação explícita:

| Conceito | Onde |
|---|---|
| Documento de cobrança | `invoices` (espelho da fatura Stripe, `hosted_url`/`pdf_url`) |
| Meio de pagamento | definido no Stripe / `payments.method_type` |
| Comprovante de pagamento | `payments` (`SUCCEEDED`, `settled_at`) |
| Documento fiscal | `tax_documents` (NFS-e etc.) — ver taxation.md |
| Conciliação | `external_event_receipts` + `subscription_events` + razão de créditos |
| Ativação/renovação | transição para `ACTIVE` apenas em `invoice.paid` |

Fatura emitida não ativa; fatura vencida (`invoice.overdue` ou `due_at` passado) inicia a inadimplência.

## Webhooks tratados
`checkout.session.completed|async_payment_succeeded|expired`, `invoice.created|finalized|updated|voided|paid|payment_failed|overdue|marked_uncollectible`,
`customer.subscription.updated|deleted`, `charge.dispute.created`, `charge.refunded`. Parser tolerante às formas de payload
antigas e novas da API (ex.: `parent.subscription_details`), deduplicação por event id, verificação de assinatura com
tolerância de 300 s (anti-replay).

## Brasil (Pix/boleto)
Boleto via Stripe só é possível se a conta vendedora for brasileira. **Pix Automático**: não implementado — exige PSP
brasileiro compatível; a interface `PaymentProvider` permite um segundo adaptador.

## Testado
`WebhookSecurityIT`: assinatura inválida/replay, fatura emitida ≠ pago, idempotência, PAST_DUE→ACTIVE, reverse charge, checkout.
**Não validado contra o Stripe real** (exige `STRIPE_SECRET_KEY` de teste e `stripe listen`).
