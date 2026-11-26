# Plano de apps e telas do Vendocas

Complementa [`projeto-vendocas.md`](projeto-vendocas.md). Aqui fica **como o código se divide** (backend × frontend) e
**a lista de telas de cada app** (web do lojista, celular do lojista, Control). Os desenhos das telas ficam no canvas
"Vendocas — Telas" (https://claude.ai/artifact/PVqSEkUoRcEb6DVMmkujZX, privado até ser compartilhado).

## 1. Divisão dos apps

```
Vendocas/
├─ apps/
│  ├─ api/        BACKEND  — Spring Boot (Java 25). Único dono dos dados e das regras. Roda no servidor.
│  ├─ web/        FRONTEND — Next.js. Site público por mercado + app web do lojista. Netlify.
│  ├─ mobile/     FRONTEND — NOVO. Expo / React Native. App do lojista para Android e iPhone.
│  └─ control/    FRONTEND + BFF — Next.js. Seu painel. Netlify (funções próprias + banco próprio).
└─ packages/
   ├─ contracts/      OpenAPI da API (fonte da verdade dos contratos)
   ├─ api-client/     cliente TypeScript gerado do OpenAPI — usado por web, mobile e control
   ├─ design-system/  tokens (cores, espaçamento, raios) — web usa CSS, mobile usa o mesmo JSON
   ├─ i18n/           NOVO. Textos por idioma e configuração de mercado compartilhados por web e mobile
   └─ tracking-sdk/   script que a loja instala no site
```

**Regras da divisão**
- Toda regra de negócio (planos, cotas, alertas, estágios, envio, cobrança) fica **só no backend**. Os frontends só
  mostram e pedem.
- Os três frontends falam com a API **somente** pelo `api-client` gerado — nenhum frontend monta URL na mão.
- O **web** e o **mobile** usam a mesma API pública (`/api/v1`, autenticação do lojista).
- O **Control** usa endpoints internos (`/internal/*`) com token de administração, chamados pelo servidor do Control
  (nunca pelo navegador).
- As pastas atuais **não mudam de lugar** (`apps/api` já é o backend; mover quebraria a Netlify e o CI). Só entra
  `apps/mobile` e `packages/i18n`.

### Backend (`apps/api`) — módulos
| Módulo | Atende |
|---|---|
| `identity` (existe) | login, MFA, equipe, papéis |
| `markets` (novo) | mercados, idiomas, moeda, horários, textos legais |
| `billing` (alterar) | planos por mercado, preços, ofertas, cotas, Stripe |
| `channels` (alterar) | e-mail/WhatsApp/SMS por lojista, modos, provisionamento |
| `crm` (novo, sobre `contacts`) | clientes, estágios, RFM, visões salvas, segmentos |
| `alerts` (sobre `automation`) | alertas prontos, rodapé de alerta, resposta automática |
| `campaigns` (existe) | promoções e calendário diário |
| `sales` (novo) | Registro de Vendas, produtos |
| `connectors` (novo) | Nuvemshop/Tiendanube, Shopify, WooCommerce |
| `operator` (novo) | dados que o Control precisa: lojistas, consumo, custo, saúde |
| `notifications` (novo) | push para o app de celular (Expo/FCM/APNs) |

## 2. Telas — app do lojista (web)
Rota base: `/{mercado}/app/...`

| Tela | Rota | Desenho |
|---|---|---|
| Site do mercado (preços) | `/{mercado}` | ✅ |
| Cadastro guiado — canais | `/{mercado}/cadastro/canais` | ✅ |
| Início | `/app` | ✅ |
| Clientes (planilha / kanban) | `/app/clientes` | ✅ |
| Ficha do cliente | `/app/clientes/{id}` | ✅ |
| Alertas | `/app/alertas` | ✅ |
| Registro de Vendas | `/app/vendas` | ✅ |
| Canais | `/app/canais` | ✅ |
| Campanhas e calendário | `/app/campanhas` | fase 7 |
| Conversas | `/app/conversas` | fase 7 |
| Segmentos, Relatórios, Integrações, Plano e cobrança, Configurações | `/app/...` | seguem o mesmo padrão |

## 3. Telas — app do lojista (celular)
O celular é para **acompanhar e agir rápido**, não para configurar. Configurações pesadas (canais, DNS, integrações)
abrem o web.

| Tela | O que faz | Desenho |
|---|---|---|
| Início | vendas recuperadas, cotas, avisos | ✅ |
| Clientes | busca e lista por estágio | ✅ |
| Ficha do cliente | linha do tempo, ligar/abrir WhatsApp da loja | ✅ |
| Registrar venda | lançar venda no balcão em segundos (plano 29+) | ✅ |
| Alertas | ligar/desligar e ver resultados | ✅ |
| Notificações push | "Maria comprou após o alerta", "cota de WhatsApp em 80%", "canal desconectado" | — |

Barra inferior: Início · Clientes · **+ Venda** · Alertas · Mais.

## 4. Telas — Vendocas Control (web, responsivo)
| Tela | Desenho |
|---|---|
| Painel do negócio | ✅ |
| Lojistas (planilha / kanban) | ✅ |
| Ficha do lojista | ✅ |
| Mercados, planos e preços | ✅ |
| Provisionamento | ✅ |
| Saúde de entrega, Seu funil, Financeiro e fiscal, Servidor, Segurança | seguem o mesmo padrão (as últimas já existem) |

## 5. Ordem de construção das telas
1. Componentes base no `design-system` (tabela, kanban, ficha, barra de cota, seletor de canal).
2. Web: Início, Clientes, Ficha, Alertas, Canais, Cadastro.
3. Control: Lojistas, Ficha do lojista, Preços.
4. Mobile: Início, Clientes, Ficha, Registrar venda, Alertas, push.
5. Web: Registro de Vendas, Campanhas, Conversas.
