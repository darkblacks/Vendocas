# API

* Base: `/api/v1`. Especificação OpenAPI gerada pelo código: `GET /v3/api-docs` (Swagger UI em `/swagger-ui.html`).
  Cópia versionada em `packages/contracts/openapi.json` (121 paths), regenerável com `scripts/export-openapi.sh`.
* Erros: RFC 9457 `application/problem+json` com `code` estável (`VALIDATION_FAILED`, `FORBIDDEN`, `MFA_REQUIRED`,
  `PLAN_FEATURE_REQUIRED`, `SUBSCRIPTION_INACTIVE`, `PROVIDER_NOT_CONFIGURED`, `POLICY_BLOCKED`, `RATE_LIMITED`, …) e `fields`/`errors` quando aplicável.
* Paginação: `?page=&size=` (máx. 200) com `{items, page, size, totalItems, totalPages}` em listas grandes (contatos).

## Autenticação
| Contexto | Mecanismo |
|---|---|
| Painel | `Authorization: Bearer <JWT 15 min>` + `X-Organization-Id`; refresh via cookie `vd_rt` em `POST /auth/refresh` com `X-Requested-With: XMLHttpRequest` |
| Servidor→servidor | `Authorization: Bearer vk_<prefix>_<secret>` com escopos (`contacts:write`, `events:write`, `orders:write`, `appointments:write`, `calls:write`) |
| SDK de site | chave pública `pk_…` + identidade assinada (HMAC com o segredo de rastreamento) |
| Webhooks | assinatura do provedor (Stripe-Signature, X-Hub-Signature-256, X-Twilio-Signature, SNS) |
| Admin da plataforma | JWT com MFA + `users.platform_role` lido do banco |

## Grupos
`/auth`, `/organizations`, `/members`, `/contacts` (+ `/contacts/imports`, `/contacts/{id}/consents`), `/channels`, `/templates`,
`/media`, `/automations`, `/campaigns`, `/messages`, `/conversations`, `/calls`, `/orders`, `/appointments`, `/opportunities`,
`/billing`, `/reports`, `/integrations`, `/track`, `/privacy`, `/notifications`, `/support`, `/audit-logs`, `/public`
(descadastro e formulários), `/webhooks/*` (provedores), `/admin`.

Cada endpoint de tenant declara a permissão exigida com `@RequiresPermission` (veja `tenants/Role.java` para a matriz).

## Rate limiting
Por IP e minuto: `/auth/*` 30, `/track` 600, `/public/*` 60, demais 1200 (Redis; fallback local).

## Exemplos
```bash
# evento externo autenticado (dispara automações EXTERNAL_EVENT com eventType=QUOTE_CREATED)
curl -X POST https://api.example.com/api/v1/integrations/inbound/events \
  -H "Authorization: Bearer vk_xxxx_yyyy" -H "Content-Type: application/json" \
  -d '{"type":"QUOTE_CREATED","email":"ana@example.com","idempotencyKey":"quote-981"}'
```
```bash
# pedido concluído (receita confirmada + cancela fluxos de abandono)
curl -X POST https://api.example.com/api/v1/integrations/inbound/orders \
  -H "Authorization: Bearer vk_xxxx_yyyy" -H "Content-Type: application/json" \
  -d '{"externalId":"1001","email":"ana@example.com","status":"COMPLETED","amountMinor":4990,"currency":"BRL"}'
```
