# Segurança — Control Center, Codespace e CI/CD

## Autenticação

* E-mail + senha (scrypt N=32768, r=8, p=1; mínimo 14 caracteres) **e MFA TOTP obrigatório** para todos os papéis.
* 10 códigos de recuperação de uso único (armazenados como hash), exibidos uma única vez.
* Sessão: token opaco aleatório, só o hash no banco; cookie `__Host-vcc_session` (HttpOnly, Secure, SameSite=Strict,
  Path=/); expiração absoluta 12 h e por inatividade 30 min; logout revoga no servidor.
* **Reautenticação** (senha + código) para ações sensíveis, válida por 10 min: salvar/revogar credenciais, criar ou
  desativar usuários, trocar senha, selecionar codespace, alterar limites, rotacionar chave.
* Bloqueio da conta após 8 falhas (15 min); rate limit por IP e por conta (login, MFA, bootstrap, ligar/desligar).
* Mensagens de login não revelam se o e-mail existe (hash fictício em tempo constante).

## Autorização

Verificada **no servidor** em cada route handler (`requireAuth`): papel mínimo, sessão válida, CSRF, reautenticação.
Negações geram auditoria `authz.denied`. O repositório controlável vem de `CONTROL_GITHUB_REPOSITORY` (servidor); o
nome do codespace é validado por regex e confrontado com a lista real do GitHub — parâmetros do navegador não decidem
qual conta/servidor é controlado.

## CSRF

Mutação exige: `Origin` igual a `CONTROL_PUBLIC_ORIGIN` **e** cabeçalho `X-CSRF-Token` igual ao token da sessão
(comparação em tempo constante). Somado a SameSite=Strict.

## Bootstrap do primeiro dono

1. Defina na Netlify: `CONTROL_DATABASE_URL`, `CONTROL_MASTER_KEYS`, `CONTROL_ACTIVE_KEY_VERSION`,
   `CONTROL_BOOTSTRAP_TOKEN` (≥ 32 caracteres, gerado localmente) e `CONTROL_BOOTSTRAP_EMAIL`.
2. Acesse `/bootstrap`. O servidor verifica o banco (migrações) e a chave mestra (cifra/decifra um teste) antes de
   aceitar qualquer dado.
3. Informe o token, o e-mail autorizado, nome e senha.
4. Escaneie o QR (ou digite o segredo) num app autenticador e confirme um código → conta ativa, códigos de recuperação
   exibidos **uma vez** (guarde-os offline).
5. O bootstrap é **trancado permanentemente** (`cc_system_state.bootstrap_completed_at`); novas tentativas retornam
   `BOOTSTRAP_LOCKED` e são auditadas.
6. Remova `CONTROL_BOOTSTRAP_TOKEN` da Netlify.

Administradores adicionais: Segurança → *Novo administrador* (OWNER + reautenticação) gera uma senha temporária; no
primeiro login a pessoa é obrigada a cadastrar MFA.

### Recuperação

* **Perdeu o autenticador (ADMIN/VIEWER)**: entre com um código de recuperação, se tiver; em qualquer caso o OWNER usa
  Configurações → *Redefinir MFA* (com reautenticação). Isso apaga o segredo TOTP e os códigos, encerra as sessões e
  obriga a pessoa a cadastrar um novo autenticador no próximo login (a senha continua exigida). Auditado como
  `user.mfa_reset`.
* **Perdeu a senha (ADMIN/VIEWER)**: o OWNER desativa a conta e cria uma nova (senha temporária + MFA obrigatório).
* **OWNER perdeu o autenticador**: use um dos 10 códigos de recuperação. O MFA do OWNER não pode ser redefinido pelo painel.
* **OWNER perdeu autenticador e códigos**: recuperação manual de emergência, fora do sistema e com acesso direto ao
  banco: (1) gere um novo `CONTROL_BOOTSTRAP_TOKEN` na Netlify; (2) no banco, em transação,
  `DELETE FROM cc_sessions WHERE user_id = '<id>'; DELETE FROM cc_recovery_codes WHERE user_id = '<id>';
  UPDATE cc_users SET status = 'DISABLED' WHERE status = 'ACTIVE'; UPDATE cc_system_state SET bootstrap_completed_at = NULL,
  bootstrap_owner_id = NULL WHERE id = 1;` (3) refaça o bootstrap com um novo e-mail em `CONTROL_BOOTSTRAP_EMAIL` (o bootstrap exige que nenhuma conta esteja ativa; depois reative os administradores em Configurações);
  (4) remova o token. O cofre continua legível (depende só da chave mestra). Registre o incidente.

## Criptografia

AES-256-GCM, nonce de 96 bits por gravação, AAD ligado ao registro, chaves versionadas fora do banco. Ver
[credentials-vault.md](credentials-vault.md). Criptografia **não** substitui autorização: decifrar só ocorre dentro de
operações autorizadas.

## Cabeçalhos

CSP restritiva, HSTS, `X-Frame-Options: DENY`, `noindex`, `Referrer-Policy: no-referrer`, `Cache-Control: no-store` em
todas as respostas de API.

## Logs e erros

Logs operacionais e auditoria nunca contêm segredos; mensagens de provedores passam por `redact()`. Erros inesperados
retornam mensagem genérica (o detalhe fica só no log do servidor, também higienizado).

## Codespace

* Homologação apenas; segredos gerados localmente em `/workspaces/.vendocas/secrets.env` (fora do Git).
* `/internal/status` exige `X-Control-Token` (comparação em tempo constante), não existe sem a variável, não retorna dados
  de clientes nem credenciais; outros caminhos `/internal/**` são negados.
* Scripts nunca executam `reset`, `clean`, `stash`, `push --force`, `DROP` ou remoção de volumes.

## Separação de ambientes

Control Center, app web e API têm domínios, cookies, bancos e variáveis distintos. Credenciais TEST e LIVE são registros
separados; chaves Stripe com prefixo incompatível com o ambiente são rejeitadas.

## Limitações conhecidas

* Rate limit em janela fixa no Postgres (adequado ao volume de um painel administrativo).
* O agendador age em intervalos de 5 min.
* Não há WebAuthn/passkeys ainda (TOTP é o segundo fator).
