# Relatório de status (2026-11-19)

Legenda: **Implementado** = código presente e compilando · **Testado** = coberto por testes automatizados que passam
(Postgres real; provedores externos substituídos por um provedor de teste) · **Sandbox** = validado contra o ambiente de
testes do provedor · **Produção** = validado em produção · **Depende** = credenciais/aprovações externas.

| Área | Implementado | Testado | Sandbox | Produção | Depende de |
|---|---|---|---|---|---|
| Cadastro, verificação de e-mail, login, refresh rotativo, reset de senha | ✓ | ✓ (IT + E2E) | — | ✕ | SES para e-mails reais |
| MFA (TOTP) | ✓ | parcial (TOTP RFC) | — | ✕ | — |
| Multiempresa, RBAC, isolamento | ✓ | ✓ (Teste H) | — | ✕ | — |
| Empresa + identificação fiscal (CNPJ/CPF/EIN/VAT) + VIES | ✓ | ✓ formato; VIES ✕ | ✕ | ✕ | acesso ao VIES |
| Planos e preços (USD) | ✓ | ✓ (E2E) | — | ✕ | preços BRL/EUR + Stripe Price IDs |
| Assinatura por cartão (Checkout) | ✓ | webhooks ✓ | ✕ | ✕ | conta Stripe |
| Fatura empresarial (send_invoice) | ✓ | webhooks ✓ | ✕ | ✕ | conta Stripe + meios habilitados |
| Inadimplência, upgrade/downgrade, cancelamento | ✓ | estados ✓ | ✕ | ✕ | Stripe |
| Créditos (reserva/débito/liberação, limites) | ✓ | ✓ (via motor) | — | ✕ | tarifas de uso |
| Stripe Tax / reverse charge | ✓ (delegado) | parcial | ✕ | ✕ | registros fiscais no Stripe |
| NFS-e (Focus NFe) | ✓ | ✕ | ✕ | ✕ | token + homologação municipal |
| Contatos, tags, campos, timeline | ✓ | ✓ (E2E criação) | — | ✕ | — |
| Importação CSV/XLSX com mapeamento, prévia, reversão | ✓ | ✕ | — | ✕ | — |
| Consentimento, evidência, supressão, política por país | ✓ | ✓ | — | ✕ | revisão jurídica |
| Motor de automações (gatilhos, esperas, condições, ações) | ✓ | ✓ | — | ✕ | — |
| Crash recovery sem perda/duplicidade | ✓ | ✓ (Teste I) | — | ✕ | — |
| Editor simples + visual (React Flow), simulação, estimativa, teste | ✓ | simulação ✓ (E2E) | — | ✕ | — |
| E-mail (SES): domínio DKIM, remetente gerenciado, envio, eventos SNS | ✓ | envio via provedor de teste | ✕ | ✕ | AWS SES |
| WhatsApp (Embedded Signup, templates, envio, webhooks) | ✓ | assinatura ✓ | ✕ | ✕ | app Meta / Tech Provider |
| SMS/MMS Twilio (números, envio, STOP, A2P) | ✓ | assinatura ✓ | ✕ | ✕ | conta Twilio, registros |
| Ligações perdidas (Twilio Voice, PBX API) | ✓ | ✕ | ✕ | ✕ | número Twilio |
| Campanhas diárias com foto | ✓ | ✕ | — | ✕ | storage S3/R2 + canal |
| Conversas (caixa de entrada, janela 24 h) | ✓ | ✕ | — | ✕ | canal |
| SDK de rastreamento + abandono | ✓ | ✕ | — | ✕ | — |
| Dashboard, relatórios, atribuição | ✓ | ✕ | — | ✕ | — |
| Privacidade (exportação, exclusão, solicitações) | ✓ | ✕ | — | ✕ | — |
| Painel administrativo da plataforma | ✓ | ✕ | — | ✕ | — |
| Docker, Compose, Caddy, CI/CD, backup/restore, alertas | ✓ | CI não executado ainda | — | ✕ | servidor, registry, bucket |

## Testes de aceite (seção 32 do escopo)
| Teste | Situação |
|---|---|
| A — empresa BR, US$ 10, e-mail, inatividade | Fluxo de UI e motor testados; pagamento e envio reais dependem de Stripe + SES |
| B — empresa EUA com SMS | Implementado; depende de Twilio + A2P aprovado |
| C — empresa UE com VAT | Implementado (VIES, reverse charge via Stripe Tax); depende de Stripe Tax |
| D — WhatsApp real | Implementado; depende de app Meta aprovado |
| E — telefonia | Implementado; depende de número Twilio |
| F — campanha diária | Implementado; depende de storage + canal + créditos |
| G — pagamento por fatura | Implementado e testado via webhooks; depende de Stripe |
| H — segurança entre empresas | **Testado (passa)** |
| I — recuperação de falha | **Testado (passa)** com 2.000 instâncias |

## Limitações conhecidas
* JDK 25 não estava disponível no ambiente de desenvolvimento; o código foi compilado/testado com JDK 21 (`-Djava.version=21`)
  e não usa APIs exclusivas do 25. CI e Docker usam Java 25 — **a primeira execução do CI confirmará**.
* Não há fila de e-mails de resposta, expurgo por retenção, teste de carga nem RLS no banco (ver roadmap).
* O app legado (ERP financeiro local em Node/SQLite) foi preservado em `legacy/erp-financeiro-local/` sem alterações.
