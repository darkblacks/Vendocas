# Banco de dados

PostgreSQL 16/17, migrações Flyway em `apps/api/src/main/resources/db/migration`:

| Migração | Conteúdo |
|---|---|
| V1 | `users`, `user_tokens`, `refresh_tokens`, `organizations`, `organization_members`, `member_invitations`, `role_permissions`, `organization_settings`, `tax_profiles` |
| V2 | `plans`, `plan_features`, `prices`, `billing_customers`, `subscriptions`, `subscription_events`, `payment_methods`, `invoices`, `invoice_items`, `payments`, `payment_attempts`, `tax_documents`, `credit_wallets`, `credit_transactions`, `provider_costs`, `usage_rates` |
| V3 | `contacts`, `contact_identifiers`, `tags`, `contact_tags`, `contact_custom_fields`, `contact_events`, `contact_consents`, `consent_evidence`, `suppression_list`, `contact_imports` |
| V4 | `channel_connections`, `channel_credentials`, `sender_identities`, `phone_numbers`, `channel_capabilities`, `message_templates`, `template_approvals`, `conversations`, `messages`, `message_attempts`, `message_events` |
| V5 | `automation_definitions`, `automation_versions`, `automation_triggers`, `automation_instances`, `automation_executions` |
| V6 | `media_assets`, `campaign_audiences`, `campaigns`, `campaign_schedules`, `campaign_runs`, `campaign_recipients`, `tracked_links`, `call_events`, `callback_tasks`, `missed_calls`, `orders`, `appointments`, `opportunities`, `conversion_events` |
| V7 | `outbox_events`, `inbox_deduplication`, `scheduled_jobs`, `external_event_receipts`, `integration_connections`, `audit_logs`, `privacy_requests`, `system_notifications`, `compliance_policies`, `support_tickets`, `abuse_flags` |
| V8 | dados de referência: planos/recursos/preços USD, matriz de papéis, países suportados (BR, US, UE-27), política de conformidade v1, matriz de capacidades por país/canal |

## Ajustes em relação à lista sugerida (com justificativa)
* `roles`/`permissions` → enum `Role` no código + `role_permissions` (espelho para auditoria): conjunto fechado e versionado com o código.
* `conversation_messages` → `messages.conversation_id`: uma única tabela de mensagens evita duplicar estados de entrega.
* `purchases` → `orders` (status `COMPLETED` = compra).
* `scheduled_jobs` existe para trabalhos genéricos; as esperas das automações ficam em `automation_instances.next_run_at`.
* `automation_triggers/conditions/actions`: o grafo de cada versão é um documento JSON validado e imutável; o gatilho é
  projetado relacionalmente em `automation_triggers` (usado para casar eventos e agendar varreduras).
* JSONB só onde o conteúdo é documento: campos personalizados, definição de automação, mapeamento de importação, payloads de outbox, metadados de renderização.

## Integridade e concorrência
* Toda tabela de tenant tem `organization_id NOT NULL` com FK; índices únicos por escopo
  (ex.: `ux_contacts_email (organization_id, email)`, `messages (organization_id, idempotency_key)`, `automation_instances (automation_id, idempotency_key)`).
* Uma assinatura "viva" por organização: índice único parcial `ux_subscription_live`.
* `CHECK` para estados, valores não negativos e `reserved <= balance` na carteira.
* Versionamento otimista (`version`) nos agregados JPA; `SELECT … FOR UPDATE` na carteira e assinaturas em webhooks.

## Retenção
`organization_settings.data_retention_days` (padrão 730) e `import_file_retention_days` (padrão 7 — o arquivo bruto de
importação é apagado diariamente por `ImportService.purgeExpiredFiles`). Exclusão de contato anonimiza e mantém somente a
supressão. **Pendente**: job de expurgo automático de mensagens/eventos antigos conforme `data_retention_days`.

## Backup
Ver `disaster-recovery.md` (`scripts/backup.sh` com criptografia `age`, `scripts/restore-test.sh`).
