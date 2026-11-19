# Implantação

## Desenvolvimento local
```bash
cp .env.example .env            # preencha JWT_SECRET e CREDENTIALS_ENCRYPTION_KEY (openssl rand)
./scripts/dev.sh                # Postgres+Redis em Docker, API (perfil dev: e-mails no log) e web
```
Web em http://localhost:3000 (proxy de `/api/v1` para a API em :8080). Swagger em http://localhost:8080/swagger-ui.html.
Sem JDK 25 local: `mvn -Djava.version=21 …` (o artefato de produção é compilado com Java 25 no Docker/CI).

## Produção em VPS Linux (baseline)
1. Docker + Docker Compose; DNS `APP_DOMAIN` e `API_DOMAIN` apontando para o servidor; portas 80/443.
2. `/opt/vendocas/.env` com todas as variáveis de `.env.example` (segredos fora do git; preferir um secret manager e
   injetar no boot). **Obrigatórias**: `JWT_SECRET`, `CREDENTIALS_ENCRYPTION_KEY`, `DB_PASSWORD`, `APP_PUBLIC_URL`, `API_PUBLIC_URL`,
   `MAIL_MODE=ses` + AWS/SES. Use `SPRING_PROFILES_ACTIVE=prod`.
3. `docker compose --profile app up -d` (Caddy obtém certificados automaticamente). Com imagens do GHCR: `IMAGE_TAG=<sha>`.
4. Pós-deploy: `curl https://API_DOMAIN/actuator/health/readiness`; webhooks dos provedores apontando para `API_DOMAIN`.
5. Monitoramento: `--profile monitoring` (Prometheus + Grafana na rede interna; alertas em `infrastructure/monitoring/alerts.yml`).
6. Primeiro administrador da plataforma: crie a conta normalmente, ative MFA e rode
   `UPDATE users SET platform_role = 'PLATFORM_ADMIN' WHERE email = '…';` (ação manual, auditável no banco).

Escala: rode várias réplicas da API (workers seguros com `SKIP LOCKED`) ou separe papéis com `WORKERS_ENABLED=false` nas
réplicas web. Migração futura para serviços gerenciados (RDS/Cloud SQL, ElastiCache, ECS/Kubernetes) não exige mudança de código.

## CI/CD
`.github/workflows/ci.yml`: testes da API em Java 25 com Testcontainers, typecheck/unit/build da web, E2E Playwright com
Postgres real, imagens Docker no `main`. `deploy.yml`: implantação manual via SSH com backup prévio.
