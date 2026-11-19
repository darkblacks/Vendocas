# Netlify — Control Center (preparado, **não publicado**)

Nada foi criado na Netlify. Siga estes passos somente quando decidir publicar.

## 1. Banco externo persistente (Neon)

Crie um projeto no [Neon](https://neon.tech) **separado** do banco do Vendocas (PostgreSQL 14+). No painel do Neon,
**Connect → Connection string**, copie a URL completa, por exemplo
`postgresql://neondb_owner:<senha>@ep-xxxx.us-east-2.aws.neon.tech/neondb?sslmode=require&channel_binding=require`.
Funcionam tanto a URL direta quanto a *pooled* (`-pooler`).

**Não** coloque essa URL em variável de ambiente nem no Git: ela é colada uma única vez em `/bootstrap` (passo 6).
O Control Center testa a conexão, aplica as tabelas e guarda a URL **criptografada** (AES-256-GCM com a chave mestra)
no **Netlify Blobs** (store `vendocas-control-config`, consistência forte) — armazenamento persistente da Netlify,
fora do Codespace e fora do sistema de arquivos temporário das funções.

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
| `CONTROL_DATABASE_URL` | — | **não defina** no fluxo normal (a URL é colada em `/bootstrap`). Só como override de emergência — se definida, tem prioridade e o passo de banco do bootstrap fica desativado |
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

Abra `https://<seu-domínio>/` — sem configuração, você é levado a `/bootstrap`:

1. **Banco de dados**: token de bootstrap + connection string do Neon → **Testar e conectar**.
2. **Proprietário**: e-mail autorizado, nome, senha.
3. **MFA**: QR code + código; guarde os códigos de recuperação.

Depois disso o bootstrap fica bloqueado (inclusive o passo do banco). Detalhes em
[security.md](security.md#bootstrap-do-primeiro-dono).

## 7. Deploy por GitHub Actions (opcional)

Se preferir publicar pelo Actions em vez da integração Git da Netlify, veja [github-actions.md](github-actions.md)
(`ENABLE_NETLIFY_CONTROL_DEPLOY`). Use **um** dos dois mecanismos, não ambos.
