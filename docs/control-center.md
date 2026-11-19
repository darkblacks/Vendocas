# Vendocas Control Center (`apps/control`)

Painel privado para o dono da plataforma e administradores expressamente autorizados. É um app Next.js
independente do app público (`apps/web`) e do backend Java (`apps/api`): tem banco próprio, autenticação própria e
funciona com o Codespace desligado.

## O que faz

| Página | Função | Origem dos dados |
|---|---|---|
| Dashboard | estado real do Codespace, Ligar/Desligar, tempo de sessão, contagem regressiva, horário previsto de desligamento, saúde da API/Postgres/Redis/workers, último commit, links, falhas recentes, uso do GitHub | GitHub API + `/internal/status` da API + banco do Control Center |
| Servidor / Codespaces | listar codespaces do repositório permitido, selecionar, ligar, desligar, portas, limite de sessão | GitHub API |
| Credenciais e integrações | cofre: cadastrar, substituir, testar, desativar, revogar, histórico; TEST/LIVE | banco do Control Center (criptografado) |
| Deploys | execuções do GitHub Actions, commit de `main`, estado dos deploys Netlify (desativados) | GitHub API |
| Logs | log operacional do agendador e das operações | banco |
| Histórico de sessões | cada sessão do Codespace: pedido, início, prazo, fim, motivo | banco |
| Configurações | limite de sessão (≤ 6 h), links de ambientes | banco + variáveis |
| Segurança e auditoria | usuários, MFA, sessões, eventos de auditoria, rotação de chave | banco |

## Arquitetura

```
navegador ──HTTPS──► Netlify (Next.js: páginas + route handlers /api/*)  ──► Postgres externo (Neon/Supabase/RDS)
                         │                                                    (usuários, sessões, cofre, auditoria)
                         ├──► api.github.com  (token lido do cofre, só no servidor)
                         └──► API Vendocas no Codespace  /internal/status (token próprio)
Netlify Scheduled Function "codespace-scheduler" (a cada 5 min) ──► mesmo código: aplica o limite de 6 h
```

* O navegador **nunca** fala com o GitHub nem recebe tokens. Toda chamada passa pelos route handlers.
* Configuração manual: **só** `CONTROL_SETUP_EMAIL` e `CONTROL_SETUP_KEY` na Netlify. Todas as chaves de criptografia são
  derivadas da chave de instalação (HKDF-SHA256); nenhuma chave fica no Git, no banco ou no navegador.
* A connection string do banco (Neon) é colada uma vez em `/bootstrap`, testada e guardada criptografada no
  **Netlify Blobs** (persistente; fora do Codespace e das funções). `CONTROL_DATABASE_URL`, se definida, tem prioridade
  (override opcional).
* Repositório GitHub e links ficam em Configurações (banco); tokens e chaves de provedores no cofre.
* As migrações rodam numa única transação com lock consultivo de transação (compatível com o pooler do Neon): durante o
  bootstrap, na primeira requisição de cada instância, ou com `pnpm --filter @vendocas/control db:migrate`.

## Ciclo de uma sessão (limite de 6 h)

1. Admin clica **Ligar** (com `Idempotency-Key`; o botão fica bloqueado enquanto a operação está em andamento).
2. Servidor confere permissão (ADMIN+), CSRF, rate limit, e trava o codespace (`pg_try_advisory_xact_lock`).
3. Consulta o estado real no GitHub. Se já está `Available`, apenas registra/reaproveita a sessão.
4. Se está parado, chama `POST /user/codespaces/{name}/start` e grava a sessão `STARTING` com
   `deadline_at = agora + limite` (máx. 6 h).
5. O agendador (a cada 5 min, sem depender do navegador ou de visitas) confere o estado real:
   `STARTING → RUNNING` quando o GitHub reporta `Available`; se o GitHub parou antes (inatividade, cota),
   registra `ENDED_EXTERNALLY` — **nunca religa**.
6. No prazo, pede `stop`, marca `STOP_REQUESTED` e só marca `STOPPED` quando o GitHub confirma `Shutdown`.
7. Falha ao parar: registra alerta (log `ERROR` após 3 tentativas), auditoria `FAILURE` e tenta de novo com backoff
   exponencial (até 30 min). Um `stop` pendente há mais de 10 min é reenviado.

Execuções simultâneas do agendador não duplicam ações: cada sessão é “arrendada” com `FOR UPDATE SKIP LOCKED` e
`lease_until`; se uma execução cair, o arrendamento expira e a próxima continua.

### O que **não** é prometido

* Disponibilidade ininterrupta: o GitHub pode parar o Codespace antes (tempo de inatividade configurado na sua conta,
  cota, manutenção). O painel mostra o estado real.
* O Control Center **não** contorna o timeout de inatividade do Codespaces (não gera atividade artificial).
* Granularidade: o desligamento acontece até ~5 min após o prazo (intervalo do agendador).

## Papéis

| Papel | Pode |
|---|---|
| VIEWER | ver dashboard, sessões, status |
| ADMIN | + ligar/desligar, ver/gerenciar cofre, logs, auditoria |
| OWNER | + criar/desativar administradores, selecionar codespace, alterar limite, definir repositório/links, rotacionar a chave de instalação |

Usuários do Vendocas (app público) não existem aqui e não podem obter acesso.

## Desenvolvimento local

```bash
createdb control_dev   # ou use o Postgres do docker compose
export CONTROL_SETUP_EMAIL=voce@exemplo.com
export CONTROL_SETUP_KEY="$(pnpm --silent --filter @vendocas/control setup:key)"
pnpm --filter @vendocas/control dev    # http://localhost:3100 → /bootstrap
# Em desenvolvimento a URL salva fica criptografada em apps/control/.control-state/ (ignorado pelo Git);
# o modo local aceita postgres://…@localhost sem TLS. Em produção TLS e host público são obrigatórios.
```

Testes: `TEST_DATABASE_URL=postgres://…/control_test pnpm --filter @vendocas/control test` (o GitHub e os provedores
são simulados; nenhuma chamada real é feita).

Veja também: [credentials-vault.md](credentials-vault.md), [security.md](security.md),
[netlify-control-setup.md](netlify-control-setup.md), [codespaces-setup.md](codespaces-setup.md).
