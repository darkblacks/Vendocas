# Variáveis de ambiente

Nenhum valor real fica no Git. `.env` é ignorado; use `.env.example` como modelo.

## Control Center (`apps/control`, Netlify Functions)

| Variável | Obrigatória | Secreta | Descrição |
|---|---|---|---|
| `CONTROL_DATABASE_URL` | sim | sim | Postgres externo persistente (`sslmode=require`) |
| `CONTROL_MASTER_KEYS` | sim | sim | `v1:<b64 32 bytes>[,v2:…]` |
| `CONTROL_ACTIVE_KEY_VERSION` | sim | não | versão usada para novas gravações |
| `CONTROL_BOOTSTRAP_TOKEN` | só no bootstrap | sim | ≥ 32 caracteres; remover depois |
| `CONTROL_BOOTSTRAP_EMAIL` | só no bootstrap | não | único e-mail aceito para o primeiro dono |
| `CONTROL_GITHUB_REPOSITORY` | sim | não | `owner/repo` cujos codespaces podem ser controlados |
| `CONTROL_PUBLIC_ORIGIN` | produção | não | origem esperada no cabeçalho `Origin` |
| `CONTROL_WEB_APP_URL` | não | não | link para o app web |
| `CONTROL_NETLIFY_WEB_URL` | não | não | informativo (página Deploys) |
| `CONTROL_GITHUB_API_URL` | não | não | padrão `https://api.github.com` (testes usam um falso) |
| `CONTROL_DB_POOL_MAX` | não | não | padrão 3 |
| `TEST_DATABASE_URL` | testes | não | banco descartável dos testes |

O token do GitHub e as chaves de provedores **não** são variáveis: ficam no cofre.

## App web (`apps/web`)

| Variável | Descrição |
|---|---|
| `API_INTERNAL_URL` | URL da API usada pelo proxy `/api/v1/*` e por `/api/health` |

## API Java (`apps/api`) — no Codespace

Definidas por `.devcontainer/devcontainer.json` (`DB_URL`, `DB_USER`, `DB_PASSWORD`, `REDIS_HOST`,
`SPRING_PROFILES_ACTIVE=dev`) e por `/workspaces/.vendocas/secrets.env` (gerado, fora do Git): `JWT_SECRET`,
`CREDENTIALS_ENCRYPTION_KEY`, `CONTROL_STATUS_TOKEN`. Outras (Stripe, SES, Meta, Twilio, Focus NFe) estão em
`.env.example` e em [deployment.md](deployment.md); ficam vazias em homologação até serem configuradas.

| Variável | Descrição |
|---|---|
| `CONTROL_STATUS_TOKEN` | habilita `GET /internal/status` (≥ 32 caracteres; sem ela o endpoint retorna 404) |
| `VENDOCAS_GIT_COMMIT` | commit em execução (preenchido por `start.sh`) |
| `WORKERS_ENABLED` | liga/desliga workers dentro da API |
| `VENDOCAS_AUTO_UPDATE` | `false` desativa o fast-forward automático de `main` no início do Codespace |

## GitHub Actions

Variáveis `ENABLE_NETLIFY_WEB_DEPLOY`, `ENABLE_NETLIFY_CONTROL_DEPLOY`, `ENABLE_API_HOMOLOG_DEPLOY` (ausentes = desativado)
e secrets listados em [github-actions.md](github-actions.md).
