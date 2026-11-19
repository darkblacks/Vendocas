# WhatsApp (Business Platform — Cloud API)

**Implementado**: Embedded Signup (Tech Provider), troca de código por token de negócio, assinatura do app na WABA,
registro do número na Cloud API (exceto coexistência), leitura de status/qualidade/limite do número, envio de templates
(com cabeçalho de imagem e parâmetros posicionais) e texto/imagem dentro da janela de 24 h, webhooks assinados de status,
mensagens recebidas, atualização de templates e qualidade, submissão de templates para aprovação, descadastro por palavra-chave.

**Não usado**: bibliotecas não oficiais, WhatsApp Web, scraping, número compartilhado da plataforma.

## Configuração necessária (externa)
1. App Meta (tipo Business) com produto WhatsApp; verificação do negócio; status de **Tech Provider** (ou usar um BSP —
   o adaptador `MetaWhatsAppProvider` concentra a chamada à API, substituível por um adaptador BSP).
2. Configuração de *Embedded Signup* (Facebook Login for Business) → `META_EMBEDDED_SIGNUP_CONFIG_ID`.
3. `META_APP_ID`, `META_APP_SECRET`, `META_GRAPH_VERSION` (padrão `v23.0`), `META_WEBHOOK_VERIFY_TOKEN`.
4. Webhook do app: `https://api.<domínio>/api/v1/webhooks/whatsapp`, campos `messages`, `message_template_status_update`,
   `phone_number_quality_update`.
5. Domínio do painel na lista de domínios permitidos do Facebook Login.

## Regras aplicadas
* Mensagens iniciadas pela empresa exigem template `APPROVED`; texto livre só se o cliente escreveu nas últimas 24 h
  (`conversations.last_inbound_at`). Categoria `MARKETING` só com finalidade promocional.
* Opt-in (consentimento `WHATSAPP`) obrigatório em todas as finalidades (política da Meta).
* Marketing para números dos EUA: **bloqueado** na política v1 e na matriz de capacidades (restrição da Meta). Reavaliar
  antes de habilitar (Admin → Capabilities / nova versão de `compliance_policies`).
* Coexistência com o WhatsApp Business App: detectada pelo evento `FINISH_WHATSAPP_BUSINESS_APP_ONBOARDING`; não se promete
  elegibilidade universal.
* Erros de token (190) colocam o canal em `ERROR` com ação "reconectar"; qualidade `RED` → `DEGRADED`.

## Validado?
Somente com testes automatizados de assinatura/verificação. **Não validado em sandbox/produção** (exige app Meta real).
Validar no ambiente de testes da Meta: Embedded Signup → template → aprovação → envio → webhook de entrega.
