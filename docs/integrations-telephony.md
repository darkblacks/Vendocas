# Telefonia (ligações perdidas)

Dois modos por número Twilio (`/calls/numbers/{id}/routing`):
1. **FORWARD_TO_BUSINESS** — clientes ligam no número Vendocas; respondemos TwiML `<Dial timeout=20>` para o telefone da
   empresa; `DialCallStatus` = no-answer/busy/failed/canceled ⇒ ligação perdida.
2. **CONDITIONAL_FORWARD_IN** — a linha da empresa encaminha (desvio condicional da operadora) chamadas não atendidas para o
   número Vendocas; toda chamada recebida é perdida; tocamos uma mensagem curta localizada e desligamos.

PBX/VoIP próprio: `POST /api/v1/integrations/inbound/calls` com chave `calls:write`.

Garantias: número digitado não é considerado integrado — o status fica `TEST_PENDING` até uma chamada real ser observada
(→ `MONITORING`). Eventos idempotentes por `(provedor, call_id)`; uma tarefa de retorno aberta por chamador. **Sem gravação**
(nenhum verbo `<Record>` é emitido). Chamadas automatizadas de saída não são implementadas.

## Validado?
Lógica de ligação perdida coberta indiretamente; **não validado com chamadas reais** (exige número Twilio).
