# SMS / MMS (Twilio)

**Implementado**: subconta Twilio por organização, busca e compra de números, webhooks de SMS/voz apontando para a
plataforma, envio via número ou Messaging Service, `StatusCallback` assinado (sent/delivered/failed/undelivered),
recebimento com `X-Twilio-Signature`, STOP/descadastro (erro 21610 e palavras-chave), segmentação GSM-7/UCS-2 para custo,
MMS onde a matriz permite (EUA).

## EUA — A2P 10DLC
O número comprado fica `PENDING_REGISTRATION` e o canal `PENDING_APPROVAL` até a campanha A2P ser **verificada pela Twilio**
(`POST /channels/sms/a2p/refresh` consulta `messaging.twilio.com/v1/Services/{sid}/Compliance/Usa2p`). O registro de marca
e campanha é feito no Console/TrustHub da Twilio (dados empresariais, caso de uso, amostras, fluxo de opt-in). **Pendente**:
automatizar a submissão de marca/campanha via API TrustHub.

## Brasil / UE
Números exigem *regulatory bundles* na Twilio conforme o país. Remetente alfanumérico: desabilitado na matriz até
verificação país a país. Falsificação de remetente nunca é permitida (o remetente é sempre um número/serviço da própria organização).

## Configuração
`TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN` (conta mãe), `API_PUBLIC_URL` público (usado para validar assinaturas),
tarifas de SMS/MMS em Admin → Usage rates.

## Validado?
Assinatura testada automaticamente. **Não validado em sandbox/produção** (exige conta Twilio).
