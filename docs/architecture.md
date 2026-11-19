# Arquitetura

## Visão geral

```
            Browser ──► Next.js 16 (apps/web) ──/api/v1/* (rewrite, mesma origem)──┐
Sites dos clientes ──► SDK (vendocas.js) ──► api.<domínio>/api/v1/track ─────────┤
Provedores (Stripe, SES/SNS, Meta, Twilio) ──► api.<domínio>/api/v1/webhooks/* ──┤
Sistemas dos clientes (API key) ──► api.<domínio>/api/v1/integrations/inbound/* ─┤
                                                                                  ▼
                                   Spring Boot 4.1 / Java 25 — monólito modular (apps/api)
                                   ├─ módulos de domínio (com.platform.*)
                                   ├─ workers no mesmo processo (desligáveis: WORKERS_ENABLED)
                                   └─ PostgreSQL (fonte da verdade) · Redis (rate limit auxiliar)
                                       · S3/R2 (mídia) · provedores externos
```

* **Monólito modular**: um deployable, pacotes por domínio (`identity`, `tenants`, `billing`, `taxation`, `contacts`, `consent`,
  `channels`, `messaging`, `automation`, `campaigns`, `conversations`, `telephony`, `commerce`, `integrations`, `notifications`,
  `analytics`, `audit`, `privacy`, `administration`, `storage`, `shared`). Regras de negócio só no backend; o frontend apenas
  exibe e chama a API.
* **Assíncrono durável sobre PostgreSQL** (sem fila externa obrigatória):
  * `outbox_events` (transactional outbox) → `OutboxRelay` entrega aos `OutboxHandler` com deduplicação em `inbox_deduplication`.
  * `scheduled_jobs` → `JobWorker` (FOR UPDATE SKIP LOCKED + lease) para varreduras, campanhas, NFS-e, downgrade, importações.
  * `automation_instances` é a própria fila do motor (`AutomationWorker`), com lease e reprocessamento de leases expirados.
  * `messages` (status `QUEUED`) é a fila do `MessageDispatcher`.
  * Redis só é usado para rate limiting; se cair, o limitador degrada para janela local e nada durável é perdido.
* **Vários processos** podem rodar ao mesmo tempo: todos os workers usam `SKIP LOCKED`, chaves únicas e idempotência.

## Decisões técnicas

| Tema | Decisão | Motivo |
|---|---|---|
| Java | Alvo 25 LTS (`java.version=25`); compila em 21+ com `-Djava.version=21` | O container de desenvolvimento só tinha JDK 21; CI/Docker usam Temurin 25 |
| Spring Boot | 4.1.2 (última estável em 2026-11) | 4.2 ainda em RC |
| JSON | Jackson 3 (`tools.jackson`) | padrão do Boot 4 |
| Persistência | JPA para agregados com versão otimista (User, Organization, Subscription); `JdbcTemplate` para filas, relatórios e consultas dinâmicas | Controle fino de SQL em filas (`SKIP LOCKED`) e jsonb |
| IDs | UUIDv7 gerados na aplicação | inserções quase sequenciais nos índices |
| Dinheiro | inteiros em unidade mínima (`*_minor`) + moeda ISO 4217 | sem erros de ponto flutuante |
| Tempo | `timestamptz` (UTC) no banco; fusos IANA; regras de calendário em `TimeRules` | meses de calendário ≠ 90 dias, DST explícito |
| Auth | JWT HS256 de 15 min em memória + refresh token rotativo em cookie httpOnly SameSite=Strict | sem token em localStorage; detecção de reuso |
| Senhas | Argon2id (`Argon2PasswordEncoder`) | OWASP |
| Segredos de provedores | AES-256-GCM (`SecretCipher`), chave fora do banco | tokens Meta/Twilio nunca saem do backend |
| Multiempresa | `X-Organization-Id` + verificação de associação a cada requisição (`TenantInterceptor`) e filtro por `organization_id` em todas as consultas | testado em `TenantIsolationIT` |

## Garantias de entrega

* **Ações de automação**: cada instância avança numa única transação (ação + log + nova posição). Reexecução após crash
  encontra o log `EXECUTED` e não repete; envios usam a chave `auto:<instância>:<passo>` única por organização.
* **Mensagens**: semântica *at-most-once* por mensagem. O registro passa a `SUBMITTED` e é confirmado **antes** da chamada ao
  provedor; se o processo morrer durante a chamada, a recuperação marca `FAILED (UNKNOWN_OUTCOME_NOT_RETRIED)` e libera o
  crédito reservado — preferimos perder um envio a duplicar uma mensagem para um consumidor.
* **Webhooks**: assinatura verificada antes de tudo; recibo único por `(provedor, event_id)` dentro da mesma transação do
  processamento (falha = rollback = o provedor reenvia).

## Frontend

Next.js App Router com `next-intl` (rotas `/{locale}/…`; pt/en/es completos, fr/de estruturais com fallback em inglês),
Tailwind 4 + componentes próprios sobre Radix (padrão shadcn), TanStack Query, React Hook Form + Zod, React Flow (editor
avançado), Recharts (dashboard). O cliente tipado `@vendocas/api-client` é gerado do contrato OpenAPI
(`packages/contracts/openapi.json`) com `openapi-typescript`.
