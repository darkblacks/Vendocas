# Recuperação de desastres

* **RPO alvo**: 24 h com backup lógico diário (`scripts/backup.sh`, `pg_dump -Fc` criptografado com `age`, enviado a bucket
  S3/R2 com versionamento e retenção). Para RPO de minutos: PostgreSQL gerenciado com PITR ou WAL archiving (pgBackRest) — recomendado antes de escalar.
* **RTO alvo**: < 2 h: provisionar VPS, `docker compose --profile app up -d`, restaurar dump, apontar DNS.
* **Teste de restauração**: `scripts/restore-test.sh` restaura no banco descartável e confere versão do Flyway, organizações
  e instâncias pendentes. Executar semanalmente e registrar o resultado.
* **Chaves**: `CREDENTIALS_ENCRYPTION_KEY` e `JWT_SECRET` devem estar no cofre de segredos com backup próprio — sem a chave
  de criptografia os tokens de provedores restaurados são ilegíveis (os clientes precisariam reconectar canais).
* **Após restaurar**: workers retomam do estado no banco; instâncias e jobs com lease vencido são reprocessados; mensagens
  `SUBMITTED` sem id do provedor são marcadas `FAILED (UNKNOWN_OUTCOME)` — nunca reenviadas automaticamente. Reconciliar
  assinaturas reenviando eventos do Stripe (Dashboard → Webhooks → reenviar) — o processamento é idempotente.
* Redis não precisa de backup (somente rate limiting).
