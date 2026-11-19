# Privacidade e conformidade

Funcionalidades implementadas (não substituem revisão jurídica):

| Requisito | Implementação |
|---|---|
| Consentimento por canal e finalidade | `contact_consents` + `consent_evidence` (origem, texto, IP, UA, URL, data) |
| Negação por padrão | `PolicyEngine` + `compliance_policies` versionada (BR, US, UE-27) |
| Descadastro | link assinado (GET confirma, POST executa, one-click RFC 8058), STOP em SMS/WhatsApp, reclamações SES |
| Supressão | `suppression_list` (escopo MARKETING ou ALL), respeitada em todo envio e reverificada no despacho |
| Acesso/portabilidade | `GET /privacy/contacts/{id}/export` e solicitações `ACCESS/EXPORT` (JSON) |
| Retificação | edição do contato (auditada) |
| Exclusão | anonimização + supressão (`DELETE /contacts/{id}`, solicitações `ERASURE`) |
| Oposição/restrição | retirada de consentimentos promocionais |
| Registro de solicitações | `privacy_requests` com prazo (30 dias por padrão) |
| Dados sensíveis | campos personalizados `is_sensitive` não podem ser usados em mensagens promocionais |
| Rastreamento web | SDK não envia nem grava nada antes de `setConsent(true)`; visitante anônimo nunca é identificado por nós |
| Controle de acesso / auditoria | RBAC, MFA, `audit_logs` |
| Região de dados | `organizations.data_region` (us/eu/br) registrada; **pendente**: implantação multi-região efetiva |

Separação de finalidades: `TRANSACTIONAL`, `SERVICE_REMINDER`, `CUSTOMER_CARE`, `MARKETING`, `REACTIVATION`.

## Políticas v1 (resumo)
* Não promocionais: consentimento, contrato ou legítimo interesse (WhatsApp: consentimento).
* Promocionais: e-mail = consentimento ou soft opt-in de cliente existente; SMS = consentimento (TCPA); WhatsApp = opt-in;
  WhatsApp marketing para EUA = bloqueado.
* Horário de silêncio padrão 21h–08h no fuso do destinatário (exceto transacionais).

## Pendências jurídicas
DPA/termos finais, cláusulas de transferência internacional, base legal por país revisada por advogado, HIPAA (não há BAA;
**não usar com PHI de saúde nos EUA**), regras nacionais de telemarketing/listas "não perturbe" (ex.: Brasil "Não Me Perturbe",
listas Robinson na UE) — não integradas.
