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
Negações geram auditoria `authz.denied`. O repositório controlável é definido pelo OWNER em Configurações (reautenticação
+ verificação no GitHub) e guardado no banco; a cada operação o servidor usa esse valor — nunca um parâmetro do
navegador. O nome do codespace é validado por regex e confrontado com a lista real do GitHub.

## CSRF

Mutação exige: `Origin` igual à origem do próprio site (ou às URLs automáticas da Netlify `URL`/`DEPLOY_PRIME_URL`) **e**
cabeçalho `X-CSRF-Token` igual ao token da sessão (comparação em tempo constante). Somado a SameSite=Strict. As etapas
da instalação usam a mesma checagem de `Origin` com o cookie de instalação SameSite=Strict.

## Chave de instalação

`CONTROL_SETUP_KEY` (≥ 32 caracteres aleatórios) é a **raiz de confiança**. Dela derivam, por HKDF-SHA256 com contextos
distintos, a chave de autenticação da instalação e as chaves AES-256-GCM da configuração e dos dados (detalhes em
[credentials-vault.md](credentials-vault.md)). Consequências:

* **Perda** da chave = perda definitiva do que foi cifrado (connection string, cofre, MFA). Guarde-a num gerenciador de
  senhas.
* **Vazamento** da chave: com ela e o e-mail, alguém só consegue abrir uma sessão de instalação **se a instalação ainda
  não foi concluída**; depois, as etapas de instalação ficam bloqueadas. Mas ela permite decifrar dados se o atacante
  também obtiver o banco/Blobs — rotacione.
* **Rotação**: exige recriptografia (Segurança → Recriptografar), com a chave antiga temporariamente em
  `CONTROL_SETUP_KEY_PREVIOUS`.

## Instalação (bootstrap do primeiro dono)

Pré-requisito: só `CONTROL_SETUP_EMAIL` e `CONTROL_SETUP_KEY` na Netlify.

1. **E-mail + chave** (`POST /api/bootstrap/session`). A página carrega sem consultar banco ou cofre
   (`GET /api/bootstrap` não faz E/S). No servidor: limite de tentativas por IP (por instância e, com melhor esforço,
   global no Blobs), comparação em tempo constante do e-mail e da chave (via HMAC com a chave de contexto
   `install-auth`), resposta idêntica para e-mail ou chave errados, atraso fixo em falhas, nada disso em logs (só o IP).
   O e-mail sozinho não basta. Sucesso → cookie `__Host-vcc_setup` (HttpOnly, Secure, SameSite=Strict, 30 min), assinado
   com HMAC, sem estado no servidor; invalidado se a chave ou o e-mail mudarem.
2. **Banco** — **Testar conexão** (`/api/bootstrap/database/test`, somente leitura) e **Salvar e continuar**
   (`/api/bootstrap/database`). O servidor:
   * valida o formato sem nunca devolvê-lo: `postgres(ql)://`, um host, usuário, senha, banco, `sslmode=require`
     (ou `verify-ca`/`verify-full`); recusa `host=`/`hostaddr=`, parâmetros que leem arquivos (`sslrootcert`, `sslcert`,
     `sslkey`, `passfile`, `service`…), portas fora de 5432/6432/6543;
   * **anti-SSRF**: resolve o DNS uma vez (timeout 3 s), recusa se **qualquer** endereço for loopback, privado,
     link-local (metadados de nuvem), CGNAT, multicast ou reservado (IPv4/IPv6, inclusive mapeados), e conecta ao IP
     verificado — sem janela para *DNS rebinding* — enquanto o TLS valida o certificado e envia SNI com o nome original;
   * **TLS** com verificação de certificado; conexão em até 6 s e cada consulta em até 6 s;
   * no salvar: prepara o esquema (migrações numa transação com `lock_timeout`/`statement_timeout`), cifra a URL com
     AES-256-GCM (nonce novo, AAD `cc_database_url`, chave `config-encryption`) e grava no Netlify Blobs;
   * erros viram códigos fixos (`DB_AUTH_FAILED`, `DB_NOT_FOUND`, `DB_UNREACHABLE`, `DB_TLS_FAILED`…), sem o texto do
     driver. A URL não aparece em respostas, HTML, logs nem auditoria (só uma impressão digital curta).
3. **Proprietário** (`/api/bootstrap/owner`): nome e senha; o e-mail é o de `CONTROL_SETUP_EMAIL`. A conta nasce
   `PENDING_MFA`, **sem privilégios**.
4. **MFA** (`/api/auth/enroll`): QR code + código → conta ativa, 10 códigos de recuperação exibidos uma vez, sessão
   normal criada e cookie de instalação apagado.
5. **Bloqueio**: a conclusão é gravada no banco (`cc_system_state.bootstrap_completed_at`) dentro de uma transação que
   trava a linha de estado (`FOR UPDATE`), serializando instalações concorrentes — só um proprietário pode concluir — e
   também no Blobs (`lockedAt`). Depois disso, nova sessão de instalação → `INSTALLATION_COMPLETE`; trocar o banco ou
   criar outro proprietário (mesmo com um cookie antigo) → `BOOTSTRAP_LOCKED`.

Prazo: toda resposta de `/api/*` é JSON e chega em menos de 10 s (prazo interno de 9,5 s). O Blobs tem 2,5 s por
requisição e 4 s por operação, sem as retentativas internas de 5 s do SDK (que, somadas, causavam o HTTP 504 de 30 s).

Administradores adicionais: Segurança → *Novo administrador* (OWNER + reautenticação) gera uma senha temporária; no
primeiro login a pessoa é obrigada a cadastrar MFA.

### Recuperação

* **Trocar o banco depois da instalação** (senha do Neon rotacionada, migração de projeto): defina `CONTROL_DATABASE_URL`
  como variável **secreta** na Netlify (tem prioridade sobre a URL guardada) e publique de novo. É a única situação em
  que uma variável a mais é usada, e é opcional.
* **Configuração do Netlify Blobs perdida**: abra `/bootstrap`, informe e-mail e chave e cole a URL do mesmo banco; como
  ele já concluiu a instalação, é religado e bloqueado na hora, sem criar um novo proprietário.
* **Banco salvo inacessível antes de concluir** (URL errada, projeto apagado): a etapa de banco reaparece para colar uma
  URL corrigida.

* **Perdeu o autenticador (ADMIN/VIEWER)**: entre com um código de recuperação, se tiver; em qualquer caso o OWNER usa
  Configurações → *Redefinir MFA* (com reautenticação). Isso apaga o segredo TOTP e os códigos, encerra as sessões e
  obriga a pessoa a cadastrar um novo autenticador no próximo login (a senha continua exigida). Auditado como
  `user.mfa_reset`.
* **Perdeu a senha (ADMIN/VIEWER)**: o OWNER desativa a conta e cria uma nova (senha temporária + MFA obrigatório).
* **OWNER perdeu o autenticador**: use um dos 10 códigos de recuperação. O MFA do OWNER não pode ser redefinido pelo painel.
* **OWNER perdeu autenticador e códigos**: recuperação manual de emergência, fora do sistema e com acesso direto ao
  banco: (1) no banco, em transação, `DELETE FROM cc_sessions WHERE user_id = '<id>'; DELETE FROM cc_recovery_codes
  WHERE user_id = '<id>'; UPDATE cc_users SET status = 'DISABLED' WHERE status = 'ACTIVE'; UPDATE cc_system_state SET
  bootstrap_completed_at = NULL, bootstrap_owner_id = NULL WHERE id = 1;` (2) no Netlify Blobs (store
  `vendocas-control-config`, chave `database`), remova o campo `lockedAt` — ou apague a chave e cole a URL de novo;
  (3) se o e-mail do dono mudar, ajuste `CONTROL_SETUP_EMAIL` (um e-mail ainda não usado) e publique; (4) refaça a
  instalação e reative os administradores em Configurações. O cofre continua legível (depende só da chave de
  instalação). Registre o incidente.

## Criptografia

AES-256-GCM, nonce de 96 bits por gravação, AAD ligado ao registro, chaves derivadas de `CONTROL_SETUP_KEY` (HKDF-SHA256)
e identificadas por impressão digital, nunca guardadas. Ver
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
