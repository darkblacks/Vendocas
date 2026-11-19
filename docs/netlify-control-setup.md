# Netlify — Control Center (preparado, **não publicado**)

Nada foi criado na Netlify. Siga estes passos somente quando decidir publicar.

## 1. Banco externo persistente

Crie um Postgres gerenciado (Neon, Supabase, RDS…) **separado** do banco do Vendocas. Use um usuário dedicado com
permissão apenas nesse banco e TLS obrigatório:
`postgres://cc_user:<senha>@<host>/control?sslmode=require`. As tabelas são criadas automaticamente na primeira
requisição (ou rode `CONTROL_DATABASE_URL=… pnpm --filter @vendocas/control db:migrate`).

## 2. Projeto

Netlify → **Add new project → Import from Git** → `darkblacks/Vendocas`:

| Campo | Valor |
|---|---|
| Base directory | `apps/control` |
| Build command / Publish | lidos de `apps/control/netlify.toml` |
| Branch de produção | `main` |
| Node | 22 (definido no `netlify.toml`) |

Desative *Deploy Previews* para este site (ou proteja-os com senha da Netlify): um preview usaria as mesmas variáveis.

## 3. Variáveis de ambiente (Site configuration → Environment variables, marcar **Secret** quando indicado)

| Variável | Secreta | Exemplo / observação |
|---|---|---|
| `CONTROL_DATABASE_URL` | sim | URL do passo 1 |
| `CONTROL_MASTER_KEYS` | sim | `v1:<base64>` — ver [credentials-vault.md](credentials-vault.md) |
| `CONTROL_ACTIVE_KEY_VERSION` | não | `v1` |
| `CONTROL_BOOTSTRAP_TOKEN` | sim | `pnpm --filter @vendocas/control bootstrap:token`; remova após o bootstrap |
| `CONTROL_BOOTSTRAP_EMAIL` | não | e-mail do dono |
| `CONTROL_GITHUB_REPOSITORY` | não | `darkblacks/Vendocas` (único repositório controlável) |
| `CONTROL_PUBLIC_ORIGIN` | não | `https://control.seudominio.com` (verificação de Origin/CSRF) |
| `CONTROL_WEB_APP_URL` | não | link para o app público |
| `CONTROL_NETLIFY_WEB_URL` | não | URL do site web na Netlify (informativo) |
| `CONTROL_DB_POOL_MAX` | não | padrão 3 |

Escopo: *Builds* não precisa das secretas; marque-as para *Functions* (runtime).

## 4. Função agendada

`apps/control/netlify/functions/codespace-scheduler.mts` roda a cada 5 minutos (configurado no `netlify.toml`). Confira
em **Logs → Functions → codespace-scheduler** após o primeiro deploy. Funções agendadas só rodam em deploys de produção.

## 5. Domínio

Use um subdomínio dedicado (ex.: `control.…`), diferente do app web: cookies (`__Host-vcc_session`) e sessões ficam
isolados. HTTPS é automático na Netlify; o app envia HSTS.

## 6. Primeiro acesso

Siga o bootstrap em [security.md](security.md#bootstrap-do-primeiro-dono).

## 7. Deploy por GitHub Actions (opcional)

Se preferir publicar pelo Actions em vez da integração Git da Netlify, veja [github-actions.md](github-actions.md)
(`ENABLE_NETLIFY_CONTROL_DEPLOY`). Use **um** dos dois mecanismos, não ambos.
