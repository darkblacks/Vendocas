# Checklist de ativação

Marque na ordem. Nada abaixo foi executado automaticamente.

## Codespace de homologação
- [ ] Definir idle timeout e retenção da conta (GitHub → Settings → Codespaces)
- [ ] (Opcional) Codespaces secret `CONTROL_STATUS_TOKEN`
- [ ] Criar o Codespace em `main` ([codespaces-setup.md](codespaces-setup.md)) e conferir `/workspaces/.vendocas/logs/startup.log`
- [ ] `bash infrastructure/codespaces/backup.sh` funcionando

## Control Center
- [ ] Projeto Neon criado; connection string copiada (não vai para variável de ambiente)
- [ ] Chave mestra gerada localmente e guardada offline
- [ ] Projeto Netlify `apps/control` + variáveis ([netlify-control-setup.md](netlify-control-setup.md))
- [ ] Domínio dedicado e `CONTROL_PUBLIC_ORIGIN`
- [ ] `/bootstrap`: token + connection string do Neon → **Testar e conectar**
- [ ] Bootstrap do dono + MFA + códigos de recuperação guardados ([security.md](security.md))
- [ ] Remover `CONTROL_BOOTSTRAP_TOKEN`
- [ ] Token GitHub fine-grained cadastrado e **testado** ([github-token-setup.md](github-token-setup.md))
- [ ] Codespace selecionado; limite de sessão definido (≤ 6 h)
- [ ] Credencial *Vendocas API (homologation)* cadastrada e testada
- [ ] Ligar → acompanhar `STARTING → RUNNING`; Desligar → `STOPPED` confirmado pelo GitHub
- [ ] Ver execuções do `codespace-scheduler` nos logs da Netlify
- [ ] Teste do prazo: limite temporário de 1 h, confirmar desligamento automático e auditoria `session.deadline_stop`

## App web
- [ ] Projeto Netlify `apps/web` + `API_INTERNAL_URL` ([netlify-web-setup.md](netlify-web-setup.md))
- [ ] Porta 8080 pública (decisão consciente) e CORS/`APP_PUBLIC_URL` na API
- [ ] Com o Codespace desligado, conferir o aviso de indisponibilidade

## CI/CD
- [ ] Proteção da branch `main` exigindo **CI result**
- [ ] (Quando autorizado) variáveis `ENABLE_*` e secrets ([github-actions.md](github-actions.md))

## Provedores (somente quando for testar de verdade)
- [ ] Stripe TEST, Meta, Twilio, SES, Focus NFe, R2/S3 cadastrados no cofre e testados
- [ ] Nenhuma chave LIVE antes de revisão; nenhuma campanha ou cobrança real em homologação

## Atualizar os apps
- Web/Control: merge em `main` → CI → (Netlify publica pela integração Git ou pelo job habilitado).
- API homologação: ao iniciar o Codespace, `start.sh` faz fast-forward de `main`, recompila e reinicia. Com o Codespace
  já ligado: `bash infrastructure/codespaces/start.sh`.
