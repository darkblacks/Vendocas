# Projeto Vendocas — especificação de produto e engenharia

> Documento de referência para construir e lançar o Vendocas. Define **o que** o produto é, **como se comporta**, **o que
> cada tela tem** e **o que é preciso para rodar e começar a vender**. Escrito antes da implementação; cada decisão
> marcada com ❓ ainda depende do dono do produto.
>
> Preços de provedores (Meta, Twilio, AWS) citados aqui são **ordem de grandeza** para dimensionar o negócio e precisam
> ser confirmados nas tabelas oficiais antes de fixar preços.

## Sumário
1. [Visão](#1-visão)
2. [Os dois apps](#2-os-dois-apps)
3. [Mercados](#3-mercados)
4. [Planos e preços](#4-planos-e-preços)
5. [Canais e credenciais](#5-canais-e-credenciais)
6. [Comportamento do CRM](#6-comportamento-do-crm)
7. [Modelo de dados do CRM](#7-modelo-de-dados-do-crm)
8. [Telas — Vendocas (lojista)](#8-telas--vendocas-lojista)
9. [Telas — Vendocas Control (operador)](#9-telas--vendocas-control-operador)
10. [Arquitetura](#10-arquitetura)
11. [Servidor (4 GB / 50 GB)](#11-servidor-4-gb--50-gb)
12. [Custos e uso justo](#12-custos-e-uso-justo)
13. [O que é preciso para começar a vender](#13-o-que-é-preciso-para-começar-a-vender)
14. [O que já existe × o que falta](#14-o-que-já-existe--o-que-falta)
15. [Fases de entrega](#15-fases-de-entrega)
16. [Decisões em aberto](#16-decisões-em-aberto)

---

## 1. Visão

**O Vendocas é um CRM de automação de relacionamento com o cliente para lojas.** Ele sabe quem visitou, quem comprou,
quem é recorrente e quem sumiu — e fala com cada cliente da loja no momento certo, pelo canal certo, sem o lojista
precisar operar nada no dia a dia.

* Não comprou → lembra.
* Comprou → agradece.
* Faz tempo que não compra → pede feedback e convida a voltar.
* Tem promoção → avisa quem importa (plano Ilimitado).
* Todo dia → campanha programada (plano Ilimitado).

**Não é** um sistema de atendimento/call center nem um CRM de vendas B2B com funil de negociação. A inspiração vem de
CRMs abertos e comerciais, mas só no que serve a uma loja:

| Referência | O que aproveitamos | O que deixamos de fora |
|---|---|---|
| **Twenty** | visões configuráveis (tabela tipo planilha e kanban), campos personalizados, linha do tempo por pessoa, interface limpa | modelagem genérica de objetos |
| **Bitrix24** | "robôs": regras gatilho → condição → ação ligadas a cada estágio; kanban que muda sozinho conforme o estágio | intranet, tarefas de equipe, telefonia complexa |
| **EspoCRM / SuiteCRM** | importação/exportação robusta, permissões por papel, auditoria | configuração pesada de módulos, funis B2B |

**Diferencial:** pensado para vender no **mundo** — Brasil, América Latina, EUA/Canadá e Europa — com tela, idioma,
preço e regras próprias de cada mercado, e com **tudo incluso**: o lojista não precisa contratar provedor de e-mail,
WhatsApp ou SMS para começar.

---

## 2. Os dois apps

| | **Vendocas** (app do lojista) | **Vendocas Control** (app do operador = você) |
|---|---|---|
| Quem usa | o lojista e a equipe dele | você (e quem você autorizar) |
| CRM de quem | os **clientes da loja** | os **lojistas** (seus clientes) |
| Onde roda | web (Netlify) + API (seu servidor) | Netlify, independente do servidor |
| Exemplo | "Maria comprou 3 vezes, está em risco" | "Transmassa paga o Ilimitado, usou 80% do WhatsApp, está em risco de cancelar" |

O Control é **só seu**: preços, mercados, credenciais da plataforma, provisionamento de canais dos lojistas, saúde e
custo de cada lojista, seu próprio funil de vendas.

---

## 3. Mercados

Mercado ≠ idioma. O **mercado** define preço, moeda, meios de pagamento, regras legais e canais padrão; o **idioma**
define o texto. Um lojista pertence a **um** mercado (escolhido no cadastro; troca só pelo suporte).

| Mercado | Países | Idioma padrão | Moeda | Pagamento | Canal principal | Regras legais |
|---|---|---|---|---|---|---|
| **Brasil** (`BR`) | Brasil | pt-BR | BRL | cartão, Pix, boleto | WhatsApp | LGPD, NFS-e do próprio Vendocas |
| **LATAM** (`LATAM`) | todos os países hispanofalantes das Américas (México, Argentina, Chile, Colômbia, Peru, Uruguai, Paraguai, Bolívia, Equador, Venezuela, América Central, Caribe hispano) | es (latino-americano) | USD | cartão | WhatsApp | leis de proteção de dados locais; opt-in |
| **EUA/Canadá** (`NA`) | EUA, Canadá | en (fr-CA futuro) | USD | cartão, ACH | e-mail + SMS | CAN-SPAM, TCPA, CASL; registro de SMS (10DLC / toll-free) |
| **Europa** (`EU`) | Espanha, Portugal, França (inclui **Guiana Francesa**), Reino Unido, Irlanda, Itália, Alemanha | do país: es-ES, pt-PT, fr-FR, en-GB, en-IE, it-IT, de-DE | EUR (GBP exibido no Reino Unido ❓) | cartão, SEPA | e-mail + WhatsApp | GDPR, ePrivacy (double opt-in na Alemanha), IVA OSS / reverse charge |

### "Telas diferentes para cada mercado"
Cada mercado tem a sua **vitrine** e o seu **cadastro**:

* **Site público**: endereço por mercado (`/br`, `/latam`, `/us`, `/eu/es`, `/eu/fr`…), oferta da página inicial,
  preços na moeda, depoimentos e exemplos de mensagem do mercado, páginas legais do mercado.
* **Cadastro**: campos fiscais do mercado (CPF/CNPJ no Brasil; RFC/CUIT/RUT/NIT… opcional na LATAM; EIN nos EUA;
  NIF/VAT na Europa), meios de pagamento, passo de registro de SMS nos EUA, consentimento duplo onde a lei exige.
* **App do lojista**: os mesmos módulos, com modelos de mensagem, horários permitidos, canal padrão e textos legais do
  mercado.

Implementação: **configuração por mercado** (no banco, editável no Control) para preços, ofertas, canais padrão e
textos; **componentes específicos** só onde a estrutura muda (Pix/boleto, registro 10DLC, VAT). Mercados podem ser
**ligados/desligados** no Control — o produto lança em um mercado e abre os outros sem novo deploy.

---

## 4. Planos e preços

| | **Alerta** | **Alerta + Vendas** | **Ilimitado** |
|---|---|---|---|
| Preço base | **US$ 19/mês** | **US$ 29/mês** | **US$ 110/mês** |
| Alertas automáticos (não comprou, comprou, recompra, aniversário) | ✅ | ✅ | ✅ |
| E-mail, WhatsApp, SMS (do Vendocas ou do lojista) | ✅ com cota | ✅ com cota | ✅ uso justo (ver §12) |
| Planilha de clientes, estágios, linha do tempo | ✅ | ✅ | ✅ |
| **Registro de Vendas** (lançar vendas, produtos, relatório) | — | ✅ | ✅ |
| Promoções e campanhas para segmentos | — | — | ✅ |
| Campanhas programadas todo dia (calendário) | — | — | ✅ |
| Conversas (responder clientes pelo Vendocas) | — | — | ✅ ❓ |
| Segmentos e campos personalizados | básicos | básicos | ✅ |

### Regra dos planos de alerta (19 e 29)
* Só mensagens de **alerta** (relacionamento e transação); nada de promoção em massa.
* **Sem conversa**: o Vendocas não recebe atendimento. Toda mensagem termina com o rodapé fixo, no idioma do cliente:
  > *Esta mensagem é apenas um alerta. Para falar com a loja: {contato da loja}.*
* Se o cliente final responder no WhatsApp/SMS, recebe **uma** resposta automática com o contato da loja (e a
  resposta fica registrada na linha do tempo, para o lojista ver).

### Preços por mercado — controlados no Control
* Cada plano tem **um preço por mercado e moeda** (ex.: Alerta = R$ X no Brasil, US$ 19 na LATAM e EUA/Canadá,
  € Y na Europa). O valor em dólar é só a referência.
* O Control edita: preço, moeda, cotas por canal, **ofertas** (ex.: "1º mês por US$ 9", "anual com 2 meses grátis"),
  período de teste, e se o plano está à venda naquele mercado.
* Cada preço está ligado ao **Price do Stripe** correspondente; o Control cria/atualiza no Stripe ou recebe o id ❓.
* Mudanças de preço valem para **novas** assinaturas; quem já assina mantém o preço até você decidir migrar.

---

## 5. Canais e credenciais

### Princípio
* **O lojista cadastra o negócio dele no Vendocas, e o Vendocas guarda as credenciais dos canais dele** (criptografadas,
  por lojista).
* Você, no Control, **vê o estado de cada credencial e opera** (testar, reconectar, refazer, trocar) — sem precisar ver
  segredos em texto.
* As **suas** credenciais (da plataforma) ficam só no Control e a API as usa.

### Três jeitos para cada canal
| Canal | Usar o do lojista | Usar o do Vendocas (padrão) | Comprar pelo Vendocas |
|---|---|---|---|
| **E-mail** | domínio dele (`@transmassa.com`): o Vendocas mostra os registros DNS e verifica | endereço personalizado no domínio do Vendocas: `claudinhotransmassa@…` com nome de remetente "Transmassa" e respostas indo para o e-mail da loja | o Vendocas compra `transmassa.com`, aponta o DNS e configura o envio sozinho |
| **WhatsApp** | número dele, conectado pela janela oficial da Meta (Cadastro Incorporado) | **não permitido pela Meta** (um número não pode falar por várias empresas) | o Vendocas compra um número e o registra na conta comercial do lojista, já configurado |
| **SMS** | número próprio (raro) | remetente padrão do Vendocas onde o país permite (nome da loja na Europa/LATAM; no Brasil, remetente compartilhado) | o Vendocas compra um número para o lojista (obrigatório nos EUA/Canadá, com registro da marca) |

#### Domínio de e-mail do Vendocas
* Recomendação de engenharia: enviar os e-mails dos lojistas por um **subdomínio dedicado** (ex.:
  `claudinhotransmassa@lojas.vendocas.com`), separado de `vendocas.com`, que fica para os e-mails do próprio Vendocas.
  Se um lojista causar denúncias de spam, a reputação do domínio principal não é afetada. ❓ (`@vendocas.com` direto
  também é tecnicamente possível, com mais risco.)
* O endereço é gerado do nome da loja (`claudinho` + `transmassa`) e pode ser editado; deve ser único.

#### WhatsApp: o que o Vendocas faz pelo lojista
1. No cadastro, botão **Conectar WhatsApp** com guia passo a passo (o que é uma conta comercial da Meta, o que é
   verificação da empresa, nome de exibição, que número usar).
2. Janela oficial da Meta: o lojista entra com a conta dele, escolhe ou cria o número. Pode continuar usando o app
   WhatsApp Business no celular ao mesmo tempo (coexistência).
3. O Vendocas guarda o acesso, assina os eventos do número, cria os **modelos de mensagem** do mercado e os envia para
   aprovação da Meta.
4. Opção "comprar número": o Vendocas compra um número virtual, recebe o código de verificação automaticamente e
   conclui o registro.

#### Quem paga as mensagens de WhatsApp ❓
* **Incluso**: a conta do lojista usa a forma de pagamento do Vendocas (exige linha de crédito de parceiro da Meta) e
  o Vendocas paga — a cota protege a margem.
* **Direto**: o lojista cadastra o cartão dele na Meta e a Meta cobra dele; o Vendocas não tem custo de WhatsApp e o
  "ilimitado" fica real.
* Recomendação: **incluso nos planos de 19/29 com cota pequena de alertas**; no Ilimitado, **direto** (ou franquia alta
  + uso justo). Ver §12.

### Credenciais da plataforma (só no Control)
Amazon SES (e-mail), aplicativo Vendocas na Meta (Cadastro Incorporado), conta principal de SMS/telefonia (Twilio),
Stripe, fornecedor de domínios, emissor de NF. A API **lê essas credenciais do Control** (hoje só lê variáveis de
ambiente — ver §14).

---

## 6. Comportamento do CRM

### 6.1 Fontes de dados
* **Script do site** (uma linha no site da loja): visitas, produtos vistos, carrinho, início de checkout, identificação
  quando o cliente informa e-mail/telefone.
* **Integração com plataformas** (um clique): pedidos, clientes, carrinhos abandonados — Nuvemshop/Tiendanube,
  Shopify, WooCommerce primeiro; Tray, VTEX depois.
* **Registro de Vendas** (plano 29+): vendas lançadas no próprio Vendocas (loja física, vendas por WhatsApp…).
* **Importação de planilha**.
* **API e webhooks** para quem tem sistema próprio.

### 6.2 Identidade
Uma pessoa = um registro, unificado por e-mail, telefone ou id da plataforma. Visitas anônimas são ligadas à pessoa
quando ela se identifica.

### 6.3 Estágio automático (o "funil" do cliente final)
Recalculado a cada compra e diariamente:

| Estágio | Regra (padrão, ajustável) |
|---|---|
| **Visitante** | interagiu, nunca comprou |
| **Novo** | 1 compra |
| **Recorrente** | 2+ compras e dentro do intervalo normal |
| **VIP** | top 10% em valor gasto nos últimos 12 meses |
| **Em risco** | passou 1,5× o intervalo médio de recompra da loja sem comprar |
| **Inativo** | passou 3× o intervalo (ou 180 dias) sem comprar |

Além do estágio: **recência, frequência e valor** (pontuação RFM 1–5), valor total gasto, ticket médio, data da última
compra, produtos favoritos.

### 6.4 Alertas prontos (ligados por padrão; todos os planos)
| Alerta | Gatilho | Quando | Conteúdo padrão |
|---|---|---|---|
| Carrinho abandonado | checkout iniciado sem compra | 1 h depois (1 lembrete) | "Você deixou itens no carrinho" + link |
| Viu e não comprou | viu produto, saiu, sem carrinho | 24 h | lembrete do produto |
| Obrigado pela compra | compra | na hora | agradecimento |
| Pedido de avaliação | compra | 7 dias | pede feedback |
| Recompra / feedback | estágio → "em risco" | ao mudar | "Sentimos sua falta — tem algum feedback?" + convite |
| Aniversário | data de aniversário | 9 h do dia | parabéns |

### 6.5 Campanhas (Ilimitado)
* **Promoção**: mensagem única para um segmento (todos, VIP, em risco, quem comprou o produto X…).
* **Calendário diário**: sequência programada de mensagens (ex.: segunda novidades, quarta oferta, sábado frete).

### 6.6 Regras de envio (sempre ativas)
* Só envia a quem **consentiu** naquele canal (prova do consentimento guardada); descadastro em um clique.
* **Horário permitido** no fuso do cliente final (padrão por mercado, ex.: 8 h–21 h).
* **Limite por pessoa** (padrão: 1 mensagem/dia, 4/semana somando canais).
* **Ordem de canais** por mercado (Brasil/LATAM: WhatsApp → e-mail → SMS; EUA: e-mail → SMS; Europa: e-mail →
  WhatsApp), respeitando o consentimento de cada canal.
* **Cota do plano**: ao atingir, o canal pausa, o lojista é avisado (e-mail + aviso no painel) e pode comprar pacote
  extra ou trocar de plano. Nada é cobrado sem aviso.
* Todo envio bloqueado fica registrado com o **motivo** (sem consentimento, fora do horário, cota, canal desconectado).

### 6.7 Mensagens e idiomas
Modelos prontos por mercado e idioma, com variáveis (`{nome}`, `{loja}`, `{produto}`, `{link}`). No WhatsApp, os
modelos são aprovados pela Meta antes do uso; o Vendocas mantém o estado de aprovação.

---

## 7. Modelo de dados do CRM

Entidades principais (multi-empresa; cada registro pertence a um lojista):

| Entidade | Campos principais |
|---|---|
| **Pessoa** (cliente final) | nome, e-mails, telefones, país, idioma, fuso, aniversário, estágio, RFM, valor total, nº de compras, última compra, etiquetas, campos personalizados, origem |
| **Consentimento** | pessoa, canal, finalidade, status, data, prova (onde/como), descadastro |
| **Evento** (linha do tempo) | pessoa, tipo (visita, produto visto, carrinho, compra, mensagem enviada/entregue/lida/clicada/respondida, descadastro…), dados, data |
| **Pedido / Venda** | pessoa, origem (loja online, registro manual), itens, valor, moeda, status, data |
| **Produto** | nome, SKU, preço, categoria (para Registro de Vendas e recomendações) |
| **Segmento** | filtro salvo (estágio, RFM, etiquetas, produtos, datas) |
| **Alerta / Automação** | gatilho, condições, espera, ação por canal, modelo, ativo |
| **Campanha** | segmento, mensagens, agenda, resultados |
| **Mensagem** | pessoa, canal, modelo, status, custo, motivo de bloqueio, atribuição de venda |
| **Canal** | tipo, modo (do lojista / do Vendocas / comprado), credencial criptografada, status, cota usada |
| **Visão** | colunas, filtros, ordenação, modo (tabela/kanban) salvos por usuário |

**Atribuição de venda**: uma compra é atribuída a uma mensagem se acontecer até 5 dias depois do clique (ou 1 dia
depois da entrega, sem clique) — base do relatório "vendas recuperadas pelo Vendocas".

---

## 8. Telas — Vendocas (lojista)

| # | Tela | Conteúdo | Planos |
|---|---|---|---|
| 1 | **Site do mercado** | oferta do mercado, como funciona, exemplos de alerta, preços na moeda, perguntas, "Criar conta" | — |
| 2 | **Cadastro guiado** | conta → loja (nome, contato, dados fiscais do mercado) → **canais** (para cada: usar o meu / usar o do Vendocas / comprar) → conectar a loja (script ou plataforma) → importar clientes → revisar os alertas → escolher plano e pagar | todos |
| 3 | **Início** | vendas recuperadas no mês, mensagens enviadas por canal, **barras de cota**, próximos envios, alertas de problema (canal desconectado, modelo reprovado), atalhos | todos |
| 4 | **Clientes** (planilha) | tabela com colunas escolhíveis: nome, contato, estágio, RFM, última compra, total gasto, nº compras, canais permitidos, etiquetas; filtros, busca, ações em lote, importar/exportar Excel/CSV; alternar para **kanban por estágio** | todos |
| 5 | **Ficha do cliente** | dados, estágio e RFM, linha do tempo completa, pedidos, mensagens e respostas, consentimentos, anotações | todos |
| 6 | **Alertas** | os alertas prontos: liga/desliga, texto por canal e idioma, prazos, resultado de cada um | todos |
| 7 | **Registro de Vendas** | lançar venda (cliente, produtos, valor, pagamento), lista de vendas, produtos, relatório do período | 29+ |
| 8 | **Campanhas** | promoções para segmentos; **calendário** de campanhas diárias; resultados | Ilimitado |
| 9 | **Conversas** | respostas dos clientes no WhatsApp/SMS/e-mail, responder | Ilimitado ❓ |
| 10 | **Segmentos** | criar e salvar filtros | Ilimitado (básicos em todos) |
| 11 | **Relatórios** | vendas recuperadas por alerta/canal, entregas, cliques, respostas, clientes por estágio ao longo do tempo | todos |
| 12 | **Canais** | e-mail, WhatsApp, SMS: modo, status, DNS, modelos aprovados, número; trocar ou comprar | todos |
| 13 | **Integrações** | script do site, plataformas de loja, chave de API | todos |
| 14 | **Plano e cobrança** | plano, consumo e cotas, comprar pacote, trocar plano, faturas, forma de pagamento | todos |
| 15 | **Configurações** | empresa, equipe e papéis, horários de envio, limites por pessoa, privacidade (exportar/apagar dados) | todos |

Recursos fora do plano aparecem **visíveis e bloqueados** com o botão de upgrade (nunca escondidos sem explicação).

---

## 9. Telas — Vendocas Control (operador)

| # | Tela | Conteúdo |
|---|---|---|
| 1 | **Painel do negócio** | receita mensal recorrente por mercado e moeda, lojistas por situação (teste, pagante, inadimplente, cancelado), novos e cancelamentos, **custo de provedores × receita = margem** por mercado e por plano |
| 2 | **Lojistas** (sua planilha) | nome, mercado, plano, situação do pagamento, canais (✅/⚠️/❌), consumo do mês por canal, custo, último acesso, **saúde** (uso, alertas ativos, vendas recuperadas); filtros, exportar; **kanban** Lead → Teste → Pagante → Em risco → Cancelado |
| 3 | **Ficha do lojista** | dados, assinatura e faturas, canais e credenciais (status, testar, reconectar, refazer), consumo e custo, mensagens bloqueadas e motivos, anotações; ações: suspender, liberar, dar créditos, estender teste, trocar plano |
| 4 | **Provisionamento** | fila de domínios e números a comprar/configurar para lojistas, andamento, custo, falhas |
| 5 | **Mercados** | ligar/desligar mercado, idiomas, moeda, canais padrão, horários, textos legais |
| 6 | **Planos, preços e ofertas** | por mercado: preço, moeda, Price do Stripe, cotas por canal, ofertas da página inicial, teste grátis |
| 7 | **Credenciais da plataforma** | SES, aplicativo Meta, Twilio, Stripe, fornecedor de domínios, emissor de NF — com teste de conexão |
| 8 | **Saúde de entrega** | e-mail: devoluções e denúncias por lojista; WhatsApp: qualidade e limites por número; SMS: registros; lojistas a revisar |
| 9 | **Seu funil e automações** | leads e testes, alertas para lojistas (boas-vindas, teste acabando, pagamento falhou, sem uso há 7 dias) |
| 10 | **Financeiro e fiscal** | pagamentos, reembolsos, notas fiscais (já existe) |
| 11 | **Servidor, segurança e auditoria** | já existe |

Para não ter dois motores de CRM: os alertas para lojistas (tela 9) usam **o próprio motor do Vendocas**, com o
Vendocas como uma empresa interna cujos "clientes" são os lojistas. O Control apresenta e administra.

---

## 10. Arquitetura

Mantém a base atual (já testada) e acrescenta o que falta:

```
                         Netlify                                   Seu servidor (4 GB)
 lojista ──► Vendocas web (Next.js) ──proxy /api/v1──►  API Vendocas (Spring Boot, Java 25)
                                                          ├─ Postgres (dados de todos os lojistas)
 você    ──► Vendocas Control (Next.js) ──admin──────►    ├─ Redis (limites, cache)
             └─ Postgres próprio (Neon) + cofre             └─ workers (envios, automações, jobs)
                                                          ▲
 lojas (script / Nuvemshop / Shopify / Woo) ──eventos─────┘
 provedores (SES, Meta, Twilio, Stripe) ◄──── envio / webhooks ────► API
```

### Módulos da API (novos ou alterados)
| Módulo | Responsabilidade |
|---|---|
| **markets** (novo) | mercados, idiomas, moeda, canais padrão, horários, textos legais; ligado/desligado |
| **catalog/billing** (alterar) | planos Alerta / Alerta+Vendas / Ilimitado; preços por mercado e moeda; ofertas; cotas por plano×mercado×canal; uso justo |
| **entitlements** (alterar) | recursos por plano (`ALERTS`, `SALES_REGISTRY`, `CAMPAIGNS`, `CAMPAIGNS_DAILY`, `INBOX`, `CUSTOM_SEGMENTS`) e cotas consumidas |
| **platform-credentials** (novo) | credenciais da plataforma gravadas pelo Control (criptografadas); variáveis de ambiente continuam como alternativa |
| **channels** (alterar) | modo por canal (do lojista / do Vendocas / comprado); endereço personalizado no domínio do Vendocas; compra de número; fila de provisionamento |
| **crm** (novo sobre `contacts`) | estágios, RFM, atribuição de vendas, visões salvas (tabela/kanban), segmentos |
| **alerts** (sobre `automation`) | alertas prontos por mercado; rodapé "apenas alerta" nos planos 19/29; resposta automática com contato da loja |
| **sales-registry** (novo) | vendas, itens, produtos, relatório |
| **commerce-connectors** (novo) | Nuvemshop/Tiendanube, Shopify, WooCommerce (webhooks + importação inicial) |
| **operator** (novo, para o Control) | visão dos lojistas, consumo e custo, saúde, ações administrativas |

Comunicação Control → API: os endpoints internos protegidos (`/internal/*`, token de administração separado do token
de leitura) já usados para ambiente/Stripe/fiscal são estendidos para preços, mercados, credenciais da plataforma e
operações nos lojistas.

### Segurança das credenciais dos lojistas
Criptografia por registro (AES-256-GCM, chave fora do banco), acesso só pelo serviço que envia, auditoria de cada uso
administrativo. O Control mostra estado, máscara e histórico; não mostra o segredo.

---

## 11. Servidor (4 GB / 50 GB)

Suficiente para lançar e para os primeiros algumas centenas de lojistas, com estes limites:

| Serviço | Memória |
|---|---|
| API (Java) | 1–1,2 GB (`-Xmx1g`) |
| Postgres | ~0,8 GB (`shared_buffers` 512 MB, até 50 conexões) |
| Redis | 128 MB |
| Caddy (HTTPS) | ~50 MB |
| Sistema + folga | ~1 GB |
| Swap | 2 GB (segurança) |

* O **site** e o **Control** ficam na Netlify (não ocupam o servidor).
* Monitoramento leve (métricas da própria API + alerta de disco/memória); Grafana/Prometheus só se sobrar memória.
* **Backup diário** do Postgres para fora do servidor (R2/S3) e teste de restauração mensal (scripts já existem).
* Disco: 50 GB cobrem anos de eventos se houver **expurgo** de eventos de navegação antigos (ex.: 13 meses).
* Crescimento: primeiro passo é levar o Postgres para um serviço gerenciado; depois, mais memória para a API.

---

## 12. Custos e uso justo

Ordem de grandeza (confirmar nas tabelas oficiais):

| Canal | Custo típico | Observação |
|---|---|---|
| E-mail (SES) | ~US$ 0,10 por **mil** | praticamente ilimitável |
| WhatsApp — utilidade (pedido, agradecimento) | frações de centavo a ~1 centavo de dólar por mensagem, conforme o país | |
| WhatsApp — marketing (carrinho abandonado conta como marketing para a Meta) | ~US$ 0,02 a 0,14 por mensagem conforme o país (Brasil ~0,06; Alemanha mais alto) | é o custo que mais pesa |
| WhatsApp — resposta dentro de 24 h após o cliente escrever | grátis | |
| SMS | ~US$ 0,01 (EUA) a 0,05–0,10 (Brasil/Europa) por mensagem + registros | caro fora dos EUA |

**Por que "ilimitado" precisa de uso justo:** uma loja com 20 mil clientes mandando 1 WhatsApp de marketing por dia
gasta ~US$ 36 mil/mês só com a Meta — 300 vezes o preço do plano. Por isso:

* **E-mail**: ilimitado de verdade (com regras anti-spam).
* **WhatsApp no Ilimitado**: o lojista paga a Meta direto (recomendado), **ou** franquia alta + uso justo.
* **SMS**: sempre com franquia por plano e pacotes extras.
* **Planos 19/29**: cotas mensais calculadas para custo máximo ≤ ~30% do preço do plano no mercado.

O Control mostra **custo real × receita** por lojista e por mercado para ajustar as cotas com dados.

---

## 13. O que é preciso para começar a vender

### Negócio e jurídico
- [ ] Empresa e conta bancária que recebam do Stripe nas moedas dos mercados
- [ ] Termos de uso, política de privacidade, contrato de tratamento de dados (DPA) e política de uso aceitável por
      mercado (LGPD, GDPR, CAN-SPAM/TCPA/CASL)
- [ ] Preços e cotas de cada plano por mercado (§4, §12)

### Domínio e e-mail
- [ ] Comprar `vendocas.com` (e o subdomínio de envio dos lojistas)
- [ ] Amazon SES em produção (fora do "sandbox"), domínio verificado (DKIM, SPF, DMARC), endereço de suporte

### WhatsApp
- [ ] Conta comercial do Vendocas na Meta **verificada**
- [ ] Aplicativo Vendocas aprovado como **Tech Provider**, com Cadastro Incorporado e as permissões de gestão e envio
- [ ] Modelos de mensagem por idioma aprovados
- [ ] Decisão de quem paga as mensagens (§5)

### SMS
- [ ] Conta Twilio com cadastros regulatórios por país; registro de marca/campanha nos EUA

### Pagamentos e fiscal
- [ ] Stripe com produtos e preços de cada plano por mercado; impostos (Stripe Tax)
- [ ] Brasil: emissão das notas do próprio Vendocas (manual no início; Focus NFe depois)

### Infraestrutura
- [ ] Servidor de 4 GB configurado (Docker, HTTPS, firewall, swap, backup diário, monitoramento)
- [ ] API em `api.vendocas.com`, site em `vendocas.com`, Control em `control.vendocas.com`

---

## 14. O que já existe × o que falta

| Área | Já existe | Falta |
|---|---|---|
| Multiempresa, login, MFA, papéis | ✅ | — |
| Contatos, eventos, etiquetas, campos, consentimento, descadastro | ✅ | estágios automáticos, RFM, atribuição, visões tabela/kanban salvas |
| Automações (motor, gatilhos, esperas, condições) | ✅ | alertas prontos por mercado, rodapé "apenas alerta", resposta automática |
| Campanhas e agendamento diário | ✅ | calendário visual |
| E-mail (SES, domínio próprio, remetente da plataforma) | ✅ | endereço personalizado no domínio do Vendocas, compra de domínio |
| WhatsApp (Cadastro Incorporado, coexistência) | ✅ | guia no cadastro, modelos por mercado, compra de número |
| SMS (subconta e número por lojista) | ✅ | remetente compartilhado por país, registro nos EUA guiado |
| Cobrança Stripe, créditos, tarifas de uso | ✅ | novos planos, preços por mercado, cotas, ofertas |
| Fiscal desacoplado | ✅ | — |
| Idiomas pt/en/es (fr/de estruturais) | ✅ | conceito de **mercado**, vitrines e cadastros por mercado, it/pt-PT/en-GB |
| Registro de Vendas | — | módulo completo |
| Integrações com plataformas de loja | API de eventos + script | Nuvemshop/Tiendanube, Shopify, WooCommerce |
| Control: servidor, cofre, auditoria, ambiente/Stripe/fiscal | ✅ | lojistas, consumo e custo, preços/mercados, provisionamento, credenciais da plataforma lidas pela API, seu funil |
| Implantação | Docker Compose, scripts de backup | configurar no servidor novo |

---

## 15. Fases de entrega

Cada fase termina com algo **utilizável** e testado.

| Fase | Entrega | Critério de pronto |
|---|---|---|
| **0. Base no ar** | API no servidor novo com HTTPS e backup; site e Control apontando para ele; credenciais da plataforma no Control lidas pela API | site no ar 24 h; backup restaurado com sucesso |
| **1. Planos e mercados** | mercados (BR, LATAM, NA, EU); planos Alerta / Alerta+Vendas / Ilimitado; preços, cotas e ofertas editáveis no Control; vitrine por mercado | cadastro e pagamento em teste nos 4 mercados |
| **2. Cadastro guiado e canais** | passo de canais (meu / do Vendocas / comprar) para e-mail, WhatsApp, SMS; endereço personalizado no domínio do Vendocas; guia do WhatsApp | lojista de teste envia alerta por e-mail e WhatsApp no mesmo dia do cadastro |
| **3. CRM** | planilha de clientes (tabela + kanban), estágios automáticos, RFM, ficha com linha do tempo, alertas prontos com rodapé, relatório de vendas recuperadas | fluxo completo: visita → carrinho → alerta → compra atribuída |
| **4. Control operador** | lojistas (planilha + kanban), ficha, consumo × custo, saúde, provisionamento, seu funil | você enxerga margem por lojista |
| **5. Registro de Vendas** | plano de US$ 29 completo | venda lançada vira compra no CRM |
| **6. Integrações de loja** | Nuvemshop/Tiendanube, depois Shopify e WooCommerce | conectar em 1 clique e importar pedidos |
| **7. Ilimitado** | campanhas por segmento, calendário diário, conversas ❓, segmentos personalizados, uso justo | campanha diária rodando com controle de cota |
| **8. Compras automáticas** | compra de domínio e de número pelo Vendocas | pedido → domínio/número pronto sem intervenção |

Sugestão de lançamento: **vender a partir da fase 3** em um mercado (Brasil ou LATAM), com os outros ligados no
Control conforme os requisitos do §13 de cada um ficarem prontos.

---

## 16. Decisões em aberto

1. **Cotas** dos planos de US$ 19 e 29 por canal e por mercado.
2. **WhatsApp no Ilimitado**: lojista paga a Meta direto ou franquia + uso justo? E nos planos de alerta: incluso?
3. **Conversas** entram no Ilimitado?
4. **Domínio de envio**: `@vendocas.com` ou subdomínio dedicado (recomendado)?
5. **Moeda da Europa**: só EUR ou GBP no Reino Unido? **Canadá**: USD ou CAD?
6. **Revenda de domínio e número**: com margem ou a custo?
7. **Primeiro mercado** para vender.
8. **Registro de Vendas**: caixa simples ou com estoque?
9. **Teste grátis**: tem? quantos dias? em que plano?
