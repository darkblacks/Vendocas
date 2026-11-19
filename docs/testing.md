# Testes

## Executar
```bash
cd apps/api && mvn verify                         # Java 25 + Docker (Testcontainers)
TEST_DB_URL=jdbc:postgresql://localhost:5432/vendocas_test mvn -Djava.version=21 verify   # Postgres existente, sem Docker
pnpm --filter @vendocas/web test                  # Vitest
E2E_API_LOG=/caminho/api.log pnpm --filter @vendocas/web e2e   # Playwright contra API + web rodando
```

## Cobertura atual (resultado da última execução: todos passando)
| Suite | O que prova |
|---|---|
| `DomainRulesTest` (12) | meses de calendário ≠ 90 dias, fim de mês, 29/fev, DST (dias × horas, gap), dias úteis, recorrência dia 31, horário de silêncio cruzando meia-noite, CNPJ (inclusive alfanumérico), CPF, EIN, VAT (inclusive EL/GR), segmentos SMS, assinatura Twilio, máquina de estados da assinatura, detecção de laço |
| `SecurityPrimitivesTest` (3) | TOTP RFC 6238, AES-GCM com detecção de adulteração, upload disfarçado rejeitado por magic bytes |
| `TenantIsolationIT` (2) | **Teste H**: token de A com organização B → 403 em 9 módulos; recurso de B com org A → 404; somente-leitura não escreve |
| `WebhookSecurityIT` (3) | assinatura falsa, replay (timestamp antigo), fatura emitida não ativa, duplicidade, PAST_DUE→ACTIVE, reverse charge, checkout pago, verify token Meta |
| `AuthFlowIT` (1) | cadastro sem enumeração, e-mail não verificado, credenciais inválidas, rotação de refresh, detecção de reuso, X-Requested-With |
| `AutomationEngineIT` (4) | **Teste A (lado motor)**: inatividade 3 meses → 1 envio, sem consentimento → SKIPPED, idempotência ao reexecutar; compra durante a espera cancela; automação pausada não envia; descadastrado bloqueado; assinatura suspensa bloqueia |
| `CrashRecoveryIT` (2) | **Teste I**: 2.000 instâncias, 500 com lease expirado de worker morto, 4 workers concorrentes → 2.000 concluídas, 2.000 mensagens, 2.000 chamadas ao provedor, 0 duplicatas; resultado desconhecido → FAILED sem reenvio |
| Vitest (4) | formatação monetária, modelo linear do editor, paridade de chaves pt/es/en |
| Playwright (2) | site em pt com preços vindos da API; cadastro → verificação → login → empresa BR com CNPJ → financeiro → cliente → simulação |

Os testes usam um provedor de mensagens **apenas de teste** (`TestProviders`, só no classpath de teste). Nenhum teste
dispara mensagens reais.

## Ainda não coberto por testes automatizados
Importação CSV/XLSX ponta a ponta, campanhas (runner), webhooks SES/Twilio com payloads reais, ligações perdidas via TwiML,
NFS-e, MFA no login, teste de carga (k6/Gatling — pendente), testes de acessibilidade.
