# Configurações externas necessárias

| Serviço | O que providenciar | Variáveis |
|---|---|---|
| Servidor | VPS Linux com Docker, DNS para app e API | `APP_DOMAIN`, `API_DOMAIN`, `APP_PUBLIC_URL`, `API_PUBLIC_URL` |
| Segredos | gerar e guardar em cofre | `JWT_SECRET` (≥32 bytes base64), `CREDENTIALS_ENCRYPTION_KEY` (32 bytes base64), `DB_PASSWORD` |
| Stripe | conta no país da entidade vendedora; Products/Prices para os 4 planos (USD e, se desejar, BRL/EUR) cadastrados em Admin → Prices; Stripe Tax com registros; Billing Portal; meios de pagamento de fatura; webhook para `/api/v1/webhooks/stripe` | `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET`, `STRIPE_AUTOMATIC_TAX` |
| AWS SES | domínio da plataforma verificado, saída do sandbox, Configuration Set com destino SNS, tópico SNS inscrito em `/api/v1/webhooks/ses` | `AWS_REGION`, credenciais/IAM, `SES_CONFIGURATION_SET`, `SES_SNS_TOPIC_ARN`, `PLATFORM_EMAIL_DOMAIN`, `MAIL_FROM_ADDRESS` |
| Meta | app Business, verificação, Tech Provider, Embedded Signup config, webhook `/api/v1/webhooks/whatsapp` | `META_APP_ID`, `META_APP_SECRET`, `META_EMBEDDED_SIGNUP_CONFIG_ID`, `META_WEBHOOK_VERIFY_TOKEN`, `META_GRAPH_VERSION` |
| Twilio | conta mãe, bundles regulatórios por país, A2P 10DLC nos EUA | `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN` |
| Focus NFe (BR) | conta, certificado da empresa, homologação no município | `FOCUS_NFE_TOKEN`, `FOCUS_NFE_ENVIRONMENT`, `SELLER_*` |
| Storage | bucket privado R2/S3 | `STORAGE_ENDPOINT`, `STORAGE_REGION`, `STORAGE_BUCKET`, `STORAGE_ACCESS_KEY`, `STORAGE_SECRET_KEY` |
| Backups | bucket separado + chave `age` | `BACKUP_BUCKET`, `AGE_RECIPIENT` |
| Plataforma | tarifas de WhatsApp/SMS/MMS por país em Admin → Usage rates; primeiro `PLATFORM_ADMIN` | — |
