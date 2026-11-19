# Regras de negócio

## Planos (tabela `plans`/`plan_features`/`prices`)
| Código | Preço base | Recursos |
|---|---|---|
| `EMAIL_STARTER` | US$ 10 | e-mail, automações, relatórios |
| `EMAIL_WHATSAPP` | US$ 15 | + WhatsApp |
| `COMPLETE_TRIO` (promocional) | US$ 18 | + SMS (onde disponível) e telefonia (ligações perdidas) |
| `DAILY_CAMPAIGNS` | US$ 120 | tudo acima + campanhas diárias com imagem |

* Preços BRL/EUR **não** foram semeados (decisão comercial): o admin cadastra em *Admin → Prices* junto com o `price_…` do Stripe.
  Sem preço na moeda da organização, usa-se o preço em USD.
* Nenhum plano inclui envios ilimitados. E-mail tem custo de uso 0 (incluído); WhatsApp/SMS/MMS exigem **tarifa de uso**
  configurada pelo admin (`usage_rates`) — sem tarifa, o envio é bloqueado com `USAGE_RATE_NOT_CONFIGURED`.

## Assinatura (máquina de estados — `SubscriptionStatus`)
`DRAFT → CHECKOUT_PENDING | PAYMENT_PENDING → ACTIVE ⇄ PAST_DUE → GRACE_PERIOD → SUSPENDED → (ACTIVE | CANCELED | EXPIRED)`
* Serviço pago liberado em `ACTIVE`, `PAST_DUE` e `GRACE_PERIOD` (tolerância). `SUSPENDED` bloqueia todo envio (verificado no
  enfileiramento **e** de novo no despacho).
* `ACTIVE` só após confirmação do provedor (`invoice.paid` ou checkout pago). Fatura emitida (`invoice.finalized`) não ativa.
* Inadimplência: falha → `PAST_DUE` (grace_until = +7 dias); 3 dias em `PAST_DUE` → `GRACE_PERIOD`; vencido o grace → `SUSPENDED`.
* Upgrade: imediato com cobrança proporcional (cartão: se a cobrança falhar, nada muda). Downgrade: no fim do período (job).
* Cancelamento: no fim do período; reversível até lá; dados mantidos pelo prazo de retenção.

## Créditos de comunicação (`CreditWalletService`)
reserva antes do envio → débito do valor real quando o provedor aceita → liberação se bloqueado/falhou/cancelado.
Cada operação é idempotente por chave única no razão `credit_transactions`. Limites diário/mensal da organização são
verificados na reserva (no fuso da organização). Compra de créditos via Checkout; o crédito só entra após webhook pago.

## Consentimento e política (`PolicyEngine`)
Avaliado em **toda** mensagem: contato ativo → endereço do canal → número móvel (SMS/WhatsApp) → lista de supressão →
país do destinatário (do telefone; e-mail: país do contato ou da organização) → política versionada
`compliance_policies (país × canal × finalidade)` → consentimento `GRANTED` com base legal aceita pela política.
**Negado por padrão** quando qualquer item é desconhecido. Finalidades: `TRANSACTIONAL`, `SERVICE_REMINDER`, `CUSTOMER_CARE`,
`MARKETING`, `REACTIVATION`. Importação nunca concede consentimento sem declaração explícita com evidência.

## Envio (`MessageService.enqueue`)
Ordem dos portões: assinatura/plano → canal conectado → política/consentimento/supressão → regras WhatsApp (template aprovado;
texto livre só na janela de 24 h; categoria MARKETING só para finalidade promocional) → dados sensíveis em promoção →
limite de frequência (padrão 3 promocionais / 7 dias / contato; campanhas têm limite próprio em dias) → tarifa e reserva de
crédito → horário de silêncio (adia, não bloqueia). Bloqueios ficam registrados como mensagem `BLOCKED` com motivo.
Promocionais de e-mail recebem `List-Unsubscribe` + `List-Unsubscribe-Post` (RFC 8058); SMS promocional recebe "Reply STOP".

## Motor de automações
* Definição = documento imutável por versão (`automation_versions.definition`): gatilho, passos, configurações.
* Gatilhos de evento (via outbox), de varredura (inatividade, aniversário, data personalizada, agendamento próximo) e
  recorrência (diária/semanal/mensal). Reentrada: `ONCE`, `ONCE_PER_EVENT` (padrão) ou `ONCE_PER_DAY`.
* Inatividade: uma instância por **episódio** (chave inclui a data-âncora), então uma nova compra reinicia a contagem.
* Regras de tempo (`TimeRules`): minutos/horas absolutos; dias/semanas mantêm o horário de parede; meses de calendário
  (31/jan + 1 mês = 28/fev); 29/fev + 1 ano = 28/fev; dias úteis pulam sáb/dom (feriados não considerados); horário inexistente
  por DST avança pela duração do salto; em sobreposição usa o primeiro offset.
* Execução atrasada: passo de envio atrasado mais que `lateToleranceHours` (padrão 24 h) é **pulado** (`LATE_EXECUTION_WINDOW_EXCEEDED`).
* Eventos de saída (`exitOn`) cancelam instâncias em espera e são reverificados imediatamente antes de cada execução.
* Automação pausada: instâncias não são processadas; ao retomar, valem as regras de atraso.
* Nova versão: `KEEP_ON_OLD` (execuções seguem na versão antiga) ou `CANCEL_RUNNING`.
* Ativação exige: grafo válido, assinatura ativa com `AUTOMATIONS`, canal incluído no plano **e** conectado, template WhatsApp aprovado.

## Campanhas diárias
Plano `DAILY_CAMPAIGNS`; somente contatos com consentimento `MARKETING` no canal; teto de destinatários por envio, orçamento
por envio e mensal, limite de frequência por destinatário, pausa automática por saldo/limite, idempotência por
`(campanha, horário)` e `(execução, contato)`. MMS só onde a matriz de capacidade permite (EUA); fora disso use link.

## Ligações perdidas
Detectadas apenas por eventos do provedor (`no-answer`, `busy`, `failed`, `canceled`) ou da integração PBX autenticada.
Gera uma tarefa de retorno aberta por chamador (repetições são agregadas), oportunidade e evento `MISSED_CALL`.
Monitoramento só fica `MONITORING` após uma chamada real ser observada. Sem gravação.

## Atribuição de receita
Somente pedidos `COMPLETED` de fontes confiáveis (API com chave, SDK com pedido assinado, registro manual). Modelo
`LAST_TOUCH_7D` exibido separadamente da receita confirmada.
