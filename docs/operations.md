# Operação

## Workers (no processo da API)
| Worker | Intervalo | Função |
|---|---|---|
| `OutboxRelay` | 0,5 s | entrega eventos de domínio (gatilhos de automação) |
| `JobWorker` | 1 s | jobs duráveis (varreduras, campanhas, NFS-e, importação, downgrade) |
| `AutomationWorker` | 1 s | avança instâncias de automação |
| `MessageDispatcher` | 0,5 s (+ recuperação a cada 60 s) | envia mensagens e trata resultados |
| `SweepJobs` | 60 s | garante jobs de inatividade/aniversário/agenda/recorrência |
| `CampaignRunner` | 30 s | materializa execuções de campanhas |
| `BillingJobs` | 10 min | inadimplência (PAST_DUE → GRACE → SUSPENDED) |

## Métricas (Prometheus `/actuator/prometheus`, só na rede interna)
`jobs_completed/retried/dead_total`, `automation_instances_advanced/retry/dead_total`, `messages_submitted/failed/canceled_total`,
`messages_provider_latency_seconds`, métricas HTTP/JVM/Hikari. Tracing OpenTelemetry (OTLP) com amostragem `TRACING_SAMPLING`.
Logs estruturados ECS em JSON (`LOG_FORMAT`), sem tokens nem conteúdo de mensagens.

## Dead letters
Admin → Dead letters: jobs `DEAD`, instâncias `DEAD_LETTER`, eventos de outbox com 12 tentativas. Botões de reprocessar
(auditados). Investigar `last_error` antes de reprocessar.

## Resiliência
Timeouts explícitos em todos os clientes HTTP, circuit breaker por provedor (10 falhas → aberto 60 s; mensagens permanecem
`QUEUED`), retentativas com backoff exponencial (5 tentativas por mensagem), um provedor fora do ar não afeta outros canais.

## Rotinas
* Diário: backup criptografado (`scripts/backup.sh`); revisar alertas de canais (`DEGRADED`/`ERROR`).
* Semanal: teste de restauração (`scripts/restore-test.sh`).
* Mensal: revisão de `channel_capabilities`/`compliance_policies` e tarifas `usage_rates` vs. custos reais (`provider_costs`).
