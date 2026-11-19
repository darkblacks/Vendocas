# GitHub Actions

| Workflow | Papel |
|---|---|
| `ci.yml` | ponto de entrada (push em `main`/`claude/**`, PRs, manual). Detecta mudanças e chama só o necessário. |
| `web.yml` | app público: install, lint (tsc), testes, build, checagem do `netlify.toml`; deploy Netlify **desativado** |
| `control.yml` | Control Center: install, lint, testes (Postgres de serviço, GitHub simulado), build; deploy **desativado** |
| `api.yml` | Java 25: checagem de migrações, `mvn verify` (Testcontainers), jar, imagem Docker `ghcr.io/…/api:<sha>`; deploy de homologação **desativado** |
| `deploy.yml` | deploy manual para VPS (pré-existente, `workflow_dispatch`) |

## Detecção de mudanças (`scripts/ci/detect-changes.mjs`)

| Caminho | Executa |
|---|---|
| `apps/web/**` | web |
| `apps/control/**` | control |
| `apps/api/**` | api |
| `packages/design-system/**` | web + control |
| `packages/api-client/**`, `packages/tracking-sdk/**` | web |
| `packages/contracts/**` | api + web |
| `pnpm-lock.yaml`, `package.json`, `pnpm-workspace.yaml` | web + control |
| `infrastructure/**`, `.devcontainer/**`, `scripts/**`, `docker-compose*.yml` | infra |
| `.github/workflows/**` | tudo |
| `docs/**`, `*.md` | nada |
| `legacy/**` | **nada** (somente a verificação de integridade) |

Testado em `scripts/ci/detect-changes.test.mjs` (`node --test scripts/ci/*.test.mjs`), que também garante que todo job
de deploy está protegido pela variável correspondente e que os YAML não contêm segredos.

Outros jobs do `ci.yml`: **Legacy ERP untouched** (checksums de `legacy/` em `infrastructure/ci/legacy.sha256`),
**Infrastructure validation** (`docker compose config`, JSON do devcontainer, `bash -n` + shellcheck, varredura de
segredos), **End-to-end** (quando web ou api mudam) e **CI result** (agregador para proteção de branch).

Nenhum teste precisa de credenciais Stripe/Meta/Twilio/Netlify.

## Ativar publicação (quando autorizado)

Settings → Secrets and variables → Actions:

| Deploy | Variável (Variables) | Secrets |
|---|---|---|
| Web → Netlify | `ENABLE_NETLIFY_WEB_DEPLOY=true` | `NETLIFY_AUTH_TOKEN`, `NETLIFY_SITE_ID_WEB` |
| Control → Netlify | `ENABLE_NETLIFY_CONTROL_DEPLOY=true` | `NETLIFY_AUTH_TOKEN`, `NETLIFY_SITE_ID_CONTROL` |
| API → homologação dedicada | `ENABLE_API_HOMOLOG_DEPLOY=true` | `HOMOLOG_HOST`, `HOMOLOG_USER`, `HOMOLOG_SSH_KEY` |

Crie também os *environments* `netlify-web`, `netlify-control`, `homologation` (Settings → Environments) com revisores
obrigatórios, se quiser aprovação manual por deploy. Deploys só ocorrem em push para `main`.

Se a Netlify já publica pela integração Git, **não** ative o deploy pelo Actions (evite deploy duplo).

## Proteção da branch `main`

Recomendado: exigir o check **CI result**, proibir force push e deleção.
