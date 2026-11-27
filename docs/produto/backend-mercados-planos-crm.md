# Backend: mercados, planos, cotas, CRM e Registro de Vendas

Implementa no `apps/api` as regras de [`projeto-vendocas.md`](projeto-vendocas.md) §3–§7. Migração: `V10__markets_plans_crm.sql`.
Testes: `MarketsPlansCrmIT` (10 cenários, Postgres real).

## Mercados
| Mercado | Países | Moeda | Idioma padrão | Ordem dos canais |
|---|---|---|---|---|
| `BR` | Brasil | BRL | pt-BR | WhatsApp → e-mail → SMS |
| `LATAM` | MX, GT, SV, HN, NI, CR, PA, CU, DO, PR, CO, VE, EC, PE, BO, CL, AR, UY, PY | USD | es-419 | WhatsApp → e-mail → SMS |
| `US` | Estados Unidos | USD | en-US | e-mail → SMS → WhatsApp |
| `CA` | Canadá | USD (CAD possível depois) | en-CA, fr-CA | e-mail → SMS → WhatsApp |
| `EU` | UE-27 + Reino Unido + **Guiana Francesa** | EUR | en-GB (es-ES, pt-PT, fr-FR, en-IE, it-IT, de-DE) | e-mail → WhatsApp → SMS |

- O lojista entra no mercado do **país de cobrança** no cadastro (`organizations.market_code`).
- Mercado desligado no Control → cadastro recusado ("not open in this market yet") e vitrine some (404).
- Novos países LATAM/CA/UK/GF receberam a mesma política de consentimento conservadora do V8 (Canadá: consentimento expresso, CASL).

## Planos
| Código | Recursos | Preço de referência |
|---|---|---|
| `ALERTA` | alertas, CRM, relatórios, e-mail/WhatsApp/SMS, **ALERTS_ONLY** | US$ 19 (LATAM, NA) |
| `ALERTA_VENDAS` | + `SALES_REGISTRY` | US$ 29 (LATAM, NA) |
| `ILIMITADO` | + campanhas, campanhas diárias, segmentos, conversas (`INBOX`), voz | US$ 110 (LATAM, NA) |

- Os planos antigos (`EMAIL_STARTER`…`DAILY_CAMPAIGNS`) saíram de venda; assinaturas existentes continuam funcionando.
- **BRL e EUR não vêm pré-cadastrados** (decisão comercial em aberto): o plano só fica à venda no Brasil e na Europa
  depois que o preço for definido no Control.
- `ALERTS_ONLY` (planos de 19 e 29):
  - envio com origem `CAMPAIGN` → bloqueado `PLAN_CAMPAIGNS_REQUIRED`; resposta manual (`MANUAL`) → `PLAN_INBOX_REQUIRED`;
  - toda mensagem leva o rodapé no idioma do cliente: *"Esta mensagem é apenas um alerta. Para falar com a loja: {contato}"*
    (e-mail texto e HTML, SMS, WhatsApp texto livre);
  - modelos de WhatsApp desses lojistas são enviados à Meta com o rodapé curto (≤ 60 caracteres, sem variáveis);
  - contato do rodapé: `organization_settings.alert_contact` (configurável em `PUT /organizations/current/settings`),
    senão telefone/e-mail da empresa.

## Cotas inclusas ("tudo incluso")
`plan_quotas` por plano × mercado × canal (definidas no Control; **nada pré-cadastrado**).

| Situação | Resultado do envio |
|---|---|
| Dentro da cota | enviado, **sem cobrança** na carteira (`messages.quota_included = true`) |
| Cota sem limite (`monthlyLimit: null`) | sempre incluso (uso justo, acompanhado no Control) |
| Cota esgotada e existe tarifa de uso | cobrado da carteira de créditos (pacote extra) |
| Cota esgotada e sem tarifa | bloqueado `PLAN_QUOTA_EXCEEDED` |
| Canal sem cota cadastrada | comportamento anterior (tarifa de uso + carteira) |

Janela = período atual da assinatura (mês do calendário se não houver). Envios simultâneos do mesmo lojista/canal são
serializados (advisory lock) para ninguém ultrapassar a cota.

## CRM — kanban B2C (V11)
Cada cliente tem a sua **janela**: o intervalo em que costuma voltar (média dos intervalos dele; enquanto só comprou
uma vez, o intervalo típico da loja). Com `dias` = tempo desde a última compra:

| Estágio | Regra |
|---|---|
| `VISITOR` | nunca comprou (fora do quadro) |
| `FIRST_SALE` — Primeira venda | 1 compra, dentro da janela |
| `RECURRING` — Recorrente | 2+ compras, primeira metade da janela (acabou de voltar) |
| `IN_WINDOW` — Dentro da janela | segunda metade da janela: a época em que costuma voltar |
| `CHURN_RISK` — Risco de churn | passou da janela dele |
| `CHURNED` — Churn | passou 3× a janela (ou 1 ano) |

- VIP é marcador (top 10% do gasto em 12 meses, com 10+ compradores), não estágio.
- Recalcula a cada venda (PDV, integrações, API) e todo dia; mudança de estágio grava `STAGE_CHANGED`.
- **Kanban B2B** (`/crm/deals`): negociações arrastáveis Lead → Proposta → Negociação → Ganho / Perdido.
- **Personas** (`/crm/personas`): perfis ligados a produtos e clientes, com clientes, ticket médio, dia da semana e
  produtos mais comprados.
- Kanbans, churn e Personas: plano **Alerta + Vendas** e acima (recurso `PIPELINE`, `PERSONAS`).

## PDV — Vendocas Vendas (`SALES_REGISTRY`, todos os planos)
- **Alerta:** "venda seca" — cliente + valor pago (`amountMinor`), suficiente para ticket médio e janela.
- **Alerta + Vendas e acima (`PRODUCT_CATALOG`):** produtos e itens.

## Onboarding obrigatório (`/onboarding`)
Loja (nome, segmento, contato do rodapé) → WhatsApp (conectar o próprio / Vendocas providencia número) → e-mail (próprio /
endereço do Vendocas). Sem "pular": `POST /onboarding/complete` recusa enquanto faltar resposta; o app fica no onboarding.

## Registro de Vendas — detalhes
- Produtos com preço do servidor: o cliente não consegue mudar o preço de um item do catálogo.
- A venda vira pedido `COMPLETED` do cliente → evento `PURCHASE_COMPLETED` (dispara "Obrigado pela compra"), estágio e
  atribuição como uma compra online. Itens avulsos aceitos (descrição + valor).
- Cliente novo pelo telefone/e-mail; caixa "aceita alertas" grava consentimento (prova: Registro de Vendas + usuário).

## Endpoints
**Público (site de cada mercado)**
- `GET /api/v1/public/markets` · `GET /api/v1/public/markets/{code}/plans` · `GET /api/v1/public/countries/{cc}`

**App do lojista (web e celular)**
- `GET /api/v1/billing/usage` (barras de cota) · `GET /api/v1/billing/market-plans`
- `GET /api/v1/crm/customers` (planilha: `stage`, `q`, `channel`, `tag`, `sort`, `page`) · `GET /api/v1/crm/kanban`
- `GET /api/v1/crm/customers/{id}` (métricas da ficha) · `GET /api/v1/crm/summary` (Início) · `POST /api/v1/crm/recompute`
- `GET|POST /api/v1/products` · `PUT /api/v1/products/{id}`
- `POST /api/v1/sales` · `GET /api/v1/sales?from&to` · `GET /api/v1/sales/summary`

**Control Center** (header `X-Control-Admin-Token` = `CONTROL_ADMIN_TOKEN`; `X-Control-Actor` vai para a auditoria;
404 se o token não estiver configurado)
- `GET /internal/commercial/markets` · `PUT /internal/commercial/markets/{code}`
- `GET /internal/commercial/pricing` · `PUT /internal/commercial/prices/{market}/{plan}` · `PUT /internal/commercial/quotas/{market}/{plan}/{channel}`
- `GET /internal/merchants` (planilha de lojistas com canais, envios do mês, custo e alertas de saúde) · `GET /internal/merchants/{id}`

## Ainda não feito
- Telas (web, celular, Control) consumindo esses endpoints.
- Teste grátis (`trialDays`) ainda não é repassado ao checkout do Stripe.
- Resposta automática com o contato da loja quando o cliente responde num plano de alerta (precisa de regra de
  consentimento para atendimento revisada).
- Pré-cadastro de preços BRL/EUR e das cotas — decisões em aberto (§16 do projeto).
