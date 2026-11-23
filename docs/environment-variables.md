# Variáveis de ambiente

Nenhum valor real fica no Git. `.env` é ignorado; use `.env.example` como modelo.

## Control Center (`apps/control`, Netlify)

**Obrigatórias — e as únicas a configurar manualmente:**

| Variável | Secreta | Descrição |
|---|---|---|
| `CONTROL_SETUP_EMAIL` | não | e-mail do dono da instalação |
| `CONTROL_SETUP_KEY` | sim | chave de instalação (≥ 32 caracteres). Raiz de confiança: as chaves de autenticação da instalação e de criptografia são derivadas dela com HKDF-SHA256 |

Todo o resto é configurado na interface e guardado criptografado: connection string do Neon (Netlify Blobs), repositório
GitHub e links (banco), tokens e chaves de provedores (cofre).

**Opcionais / situacionais (não exigidas):**

| Variável | Quando |
|---|---|
| `CONTROL_SETUP_KEY_PREVIOUS` | só durante a rotação da chave de instalação (leitura dos dados antigos); remova depois |
| `CONTROL_DATABASE_URL` | override de emergência do banco (tem prioridade sobre a URL salva); também usada por testes e scripts |
| `CONTROL_CONFIG_STORE`, `CONTROL_STATE_DIR` | hospedagem fora da Netlify / desenvolvimento local (`file`) |
| `CONTROL_DB_POOL_MAX` | ajuste fino (padrão 3) |
| `TEST_DATABASE_URL` | testes automatizados |

Lidas automaticamente quando a Netlify as fornece: `URL`, `DEPLOY_PRIME_URL`, `NODE_ENV`.

**Removidas** (modelo anterior, não use): `CONTROL_MASTER_KEYS`, `CONTROL_ACTIVE_KEY_VERSION`, `CONTROL_BOOTSTRAP_TOKEN`,
`CONTROL_BOOTSTRAP_EMAIL`, `CONTROL_GITHUB_REPOSITORY`, `CONTROL_PUBLIC_ORIGIN`, `CONTROL_WEB_APP_URL`,
`CONTROL_NETLIFY_WEB_URL`. Se `CONTROL_MASTER_KEYS` ainda existir numa instalação antiga, ela só é lida para decifrar dados
antigos; use “Recriptografar” em Segurança e depois apague a variável.

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
| `CONTROL_ADMIN_TOKEN` | opcional, ≥ 32 caracteres: permite ao Control Center alterar ambiente/Stripe/emissão fiscal (`PUT /internal/platform-settings`); sem ela, 404 |
| `VENDOCAS_GIT_COMMIT` | commit em execução (preenchido por `start.sh`) |
| `WORKERS_ENABLED` | liga/desliga workers dentro da API |
| `VENDOCAS_AUTO_UPDATE` | `false` desativa o fast-forward automático de `main` no início do Codespace |

## GitHub Actions

Variáveis `ENABLE_NETLIFY_WEB_DEPLOY`, `ENABLE_NETLIFY_CONTROL_DEPLOY`, `ENABLE_API_HOMOLOG_DEPLOY` (ausentes = desativado)
e secrets listados em [github-actions.md](github-actions.md).
