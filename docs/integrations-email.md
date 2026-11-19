# E-mail (Amazon SES v2)

**Implementado**
* Domínio próprio: `CreateEmailIdentity` com Easy DKIM → registros CNAME exibidos no painel (+ DMARC recomendado);
  "Verificar" consulta `GetEmailIdentity` e ativa somente com `VerifiedForSendingStatus = true`.
* Remetente gerenciado: `<loja>-<id>@PLATFORM_EMAIL_DOMAIN` com `Reply-To` da empresa (requer o domínio da plataforma verificado no SES).
* Envio com `ConfigurationSet`, tag `vd_message`, cabeçalhos `List-Unsubscribe` + one-click (RFC 8058) para promocionais.
* Eventos via SNS (assinatura SNS verificada, tópico conferido): Send, Delivery, Open, Bounce (permanente ⇒ supressão total),
  Complaint (⇒ supressão de marketing + retirada de consentimento), Reject.
* E-mails da própria plataforma (verificação, senha, convites, avisos financeiros) via `PlatformMailer` (`MAIL_MODE=ses`).
  `MAIL_MODE=log` existe **apenas** para desenvolvimento e é recusado no perfil `prod`.

**Pendente**: recebimento de respostas por e-mail (SES inbound → S3/SNS) e SPF/MAIL FROM customizado automatizado.

## Configuração
`AWS_REGION`, credenciais (ou IAM role), `SES_CONFIGURATION_SET` com destino SNS, `SES_SNS_TOPIC_ARN`, inscrição HTTPS do tópico em
`https://api.<domínio>/api/v1/webhooks/ses`, `PLATFORM_EMAIL_DOMAIN` verificado, saída do sandbox do SES.

## Validado?
Fluxo de envio validado com provedor de teste nos testes de integração; **não validado contra SES real**.
