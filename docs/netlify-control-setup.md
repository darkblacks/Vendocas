# Netlify — Control Center

Nada é publicado automaticamente por este repositório. Estes são os passos para quando você decidir publicar.

## Resumo: apenas duas variáveis

| Variável | Secreta | Valor |
|---|---|---|
| `CONTROL_SETUP_EMAIL` | não | seu e-mail (dono da instalação) |
| `CONTROL_SETUP_KEY` | **sim** | chave de instalação, **≥ 32 caracteres aleatórios** |

Gere a chave na sua máquina, por exemplo:

```bash
node -e "console.log(require('crypto').randomBytes(32).toString('base64url'))"
```

Guarde uma cópia em um gerenciador de senhas. **Ela é a raiz de confiança da instalação**: todas as chaves de
criptografia são derivadas dela (HKDF-SHA256). Se ela se perder, a connection string do banco, as credenciais do cofre e
os segredos de MFA **não poderão ser decifrados** — não existe recuperação. Veja [security.md](security.md#chave-de-instalação).

Todo o resto — banco Neon, repositório GitHub, links, token do GitHub, Stripe, Meta, Twilio, SES, Focus NFe, R2 — é
configurado pela interface, depois do login. Não edite `.env` nem crie outras variáveis.

## 1. Banco Neon

Crie um projeto no [Neon](https://neon.tech) (PostgreSQL 14+), **separado** do banco do Vendocas. Você só vai precisar
da connection string (painel do Neon → **Connect**), por exemplo
`postgresql://neondb_owner:<senha>@ep-xxxx.us-east-2.aws.neon.tech/neondb?sslmode=require&channel_binding=require`.
URL direta ou *pooled* (`-pooler`) funcionam. Ela será colada na instalação (passo 4), não em variável.

## 2. Projeto na Netlify

**Add new project → Import from Git** → `darkblacks/Vendocas`:

| Campo | Valor |
|---|---|
| Base directory | `apps/control` |
| Build command / Publish | lidos de `apps/control/netlify.toml` |
| Branch de produção | `main` |

Em **Site configuration → Environment variables**, crie `CONTROL_SETUP_EMAIL` e `CONTROL_SETUP_KEY` (marque a chave como
*secret*, escopo *Functions*). Publique (deploy).

Desative *Deploy Previews* deste site, ou proteja-os com senha: um preview usaria as mesmas variáveis.

O armazenamento da configuração usa **Netlify Blobs**, que já vem habilitado em todo site da Netlify — não há nada a
configurar. Store: `vendocas-control-config`.

## 3. Domínio

Use um subdomínio dedicado (ex.: `control.…`). Cookies (`__Host-vcc_session`, `__Host-vcc_setup`) e sessões ficam
isolados do app web. HTTPS é automático; o app envia HSTS. As origens aceitas para requisições que alteram dados são a
do próprio site e as URLs que a Netlify fornece automaticamente (`URL`, `DEPLOY_PRIME_URL`).

## 4. Instalação (no navegador)

Abra `https://<seu-domínio>/` — sem instalação você vai para `/bootstrap`:

1. **Instalação**: e-mail + chave de instalação → **Continuar**. A página abre sem consultar banco ou cofre; a validação é
   feita só no servidor (comparação em tempo constante, limite de tentativas, nada disso vai para logs).
2. **Banco**: cole a **Connection String PostgreSQL** → **Testar conexão** (só leitura) → **Salvar e continuar**
   (prepara as tabelas e grava a URL criptografada).
3. **Proprietário**: nome e senha (o e-mail é o de `CONTROL_SETUP_EMAIL`).
4. **MFA**: escaneie o QR code e confirme um código; guarde os 10 códigos de recuperação.

Só após o MFA a conta ganha privilégios e a instalação é concluída e **bloqueada** (no banco e no Blobs).

## 5. Depois do login

1. **Credenciais** → *GitHub Codespaces*: cadastre e teste o token ([github-token-setup.md](github-token-setup.md)).
2. **Configurações** → *Repositório GitHub*: `darkblacks/Vendocas` (verificado no GitHub com o token) e, se quiser, a URL
   do app web.
3. **Servidor** → selecione o Codespace; defina o limite de sessão (≤ 6 h).
4. **Credenciais** → demais provedores, quando for usar.

## 6. Função agendada

`netlify/functions/codespace-scheduler.mts` roda a cada 5 minutos (configurado no `netlify.toml`) e aplica o limite de
sessão do Codespace. Enquanto a instalação não termina, ela não faz nada. Confira em **Logs → Functions**.

## 7. Diagnóstico

| Sintoma | Causa provável |
|---|---|
| “CONTROL_SETUP_KEY não está configurada” | variável ausente ou com menos de 32 caracteres; crie e publique de novo |
| “armazenamento persistente (Netlify Blobs) não respondeu” | site rodando fora da Netlify, ou falha temporária do Blobs; a resposta chega em segundos, tente de novo |
| “A operação demorou demais” | o projeto Neon estava suspenso e demorou a acordar; tente de novo |
| “Os dados salvos foram criptografados com outra CONTROL_SETUP_KEY” | a chave mudou; restaure a anterior ou siga a rotação |

Toda resposta de `/api/*` é JSON e chega em menos de 10 s (prazo interno de 9,5 s; Blobs com 2,5 s por requisição e 4 s
por operação; banco com 6 s para conectar e 6 s por consulta).

## 8. Deploy por GitHub Actions (opcional)

Veja [github-actions.md](github-actions.md) (`ENABLE_NETLIFY_CONTROL_DEPLOY`). Use a integração Git da Netlify **ou** o
Actions, não os dois.
