# Codespace de homologação

O Codespace é **apenas homologação**. Produção não roda nele.

## Criar

1. GitHub → repositório `darkblacks/Vendocas` → **Code → Codespaces → ⋯ → New with options**.
2. Branch `main`, configuração `.devcontainer/devcontainer.json`, máquina com **4 núcleos / 8 GB** ou mais.
3. Defina o **idle timeout** da sua conta (Settings → Codespaces → Default idle timeout). O Control Center respeita
   esse limite; se o GitHub parar antes das 6 h, a sessão aparece como “encerrada pelo GitHub”.
4. (Opcional) Settings → Codespaces → **Secrets**: `CONTROL_STATUS_TOKEN` (≥ 32 caracteres) com acesso ao repositório.
   Sem ele, um token é gerado automaticamente na primeira criação.
5. Anote o **nome** do codespace (ex.: `vendocas-main-abc123`) — ele aparece no Control Center para seleção.

## O que acontece ao criar (`postCreateCommand` → `infrastructure/codespaces/setup.sh`)

* verifica Java 25, Maven, Node 22, pnpm; instala `postgresql-client`/`redis-tools` se faltarem;
* gera segredos de homologação em `/workspaces/.vendocas/secrets.env` (fora do Git, `chmod 600`):
  `JWT_SECRET`, `CREDENTIALS_ENCRYPTION_KEY`, `CONTROL_STATUS_TOKEN`;
* `pnpm install --frozen-lockfile` e build da API.

## O que acontece a cada início (`postStartCommand` → `infrastructure/codespaces/start.sh`)

1. **Valida** ferramentas e segredos.
2. **Identifica** branch e commit.
3. **Atualiza com segurança**: só se estiver em `main`, sem alterações locais, sem commits não enviados, e apenas
   por `git merge --ff-only`. Qualquer conflito → para e registra; nada é descartado (sem `reset`, `clean`, `stash`).
   Desative com `VENDOCAS_AUTO_UPDATE=false`.
4. **Dependências**: reinstala só se o `pnpm-lock.yaml` mudou; recompila a API só se o commit mudou.
5. **Infra**: espera Postgres e Redis (serviços do `docker-compose.yml` do devcontainer).
6. **API + workers** (os workers rodam dentro da API; `WORKERS_ENABLED=true`). O Flyway aplica migrações pendentes
   compatíveis; uma migração incompatível impede a subida (nada é apagado).
7. **Health check** em `/actuator/health` (até 180 s) e consulta a `/internal/status`.
8. Grava `/workspaces/.vendocas/status.json`.

Logs: `/workspaces/.vendocas/logs/{startup,build,api}.log` (logs antigos da API com mais de 7 dias são removidos;
dados nunca).

## Ligar ao Control Center

1. No terminal do Codespace: `bash infrastructure/codespaces/show-status-token.sh` → copie o valor.
2. Aba **Ports**: porta 8080 → copie o endereço (`https://<nome>-8080.app.github.dev`).
3. Control Center → **Credenciais** → *Vendocas API (homologation)* → cole URL e token → **Testar**.

A porta 8080 é **privada** por padrão; o Control Center envia o token do GitHub (cabeçalho `X-Github-Token`) para
atravessar o encaminhamento privado. Para o app web na Netlify acessar a API, a porta precisa ser **pública**
(`gh codespace ports visibility 8080:public -c <nome>`) — isso expõe a API de homologação na internet (ela continua
exigindo autenticação própria). Decida conscientemente.

## Persistência

| Ação | Banco/Redis (volumes Docker) | `/workspaces` (código, `.vendocas`, backups) |
|---|---|---|
| Parar / iniciar | preservado | preservado |
| Rebuild do container | normalmente preservado (volumes nomeados) | preservado |
| *Full rebuild* | **pode ser perdido** | preservado |
| Excluir o Codespace (manual ou por retenção do GitHub) | **perdido** | **perdido** |

O GitHub exclui codespaces parados após o período de retenção da conta (padrão 30 dias). Faça backups.

## Backup e restauração

```bash
bash infrastructure/codespaces/backup.sh                        # pg_dump → /workspaces/.vendocas/backups (mantém 14)
gh codespace cp -c <nome> 'remote:/workspaces/.vendocas/backups/*' ./backups/   # copiar para fora (na sua máquina)
bash infrastructure/codespaces/restore.sh /workspaces/.vendocas/backups/vendocas-XXXX.dump
```

`restore.sh` restaura **em um banco novo** (`vendocas_restore_<data>`); o banco `vendocas` nunca é sobrescrito. Para
usar o restaurado, ajuste `DB_URL` e reinicie a API (`bash infrastructure/codespaces/stop-services.sh && bash
infrastructure/codespaces/start.sh`).

## Recuperação

* API não sobe: veja `/workspaces/.vendocas/logs/api.log`; corrija e rode `start.sh` de novo.
* Atualização bloqueada por alterações locais: faça commit/push ou descarte manualmente — o script nunca descarta.
* Segredos perdidos (`secrets.env` apagado): rode `setup.sh` (gera novos; recadastre o token no Control Center).
  Credenciais de provedores cadastradas na API ficam ilegíveis se `CREDENTIALS_ENCRYPTION_KEY` mudar — guarde uma cópia
  segura de `secrets.env` se tiver dados importantes.
