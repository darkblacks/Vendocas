# Cofre de credenciais

## Modelo

Tabela `integration_credentials` (banco do Control Center):

| Coluna | Conteúdo |
|---|---|
| `id` | UUID único do registro |
| `provider`, `environment` | ex.: `STRIPE` / `TEST` ou `LIVE` (um registro ativo por par) |
| `encrypted_payload` | JSON dos campos, cifrado com **AES-256-GCM** |
| `nonce` | 96 bits aleatórios, **novo a cada gravação** |
| `encryption_key_version` | impressão digital da chave usada (`sk-…`, derivada de `CONTROL_SETUP_KEY`) |
| `masked_hint` | apenas pistas não secretas (ex.: `••••4f2a`, account SID público) |
| `status` | `UNTESTED`, `VALID`, `INVALID`, `DISABLED`, `REVOKED` |
| `last_tested_at`, `last_error_code`, `last_error_message` | resultado do último teste (mensagem higienizada) |
| `created_by`, `updated_by`, `version` | autoria e versão |

`integration_credential_history` guarda cada ação (CREATED, REPLACED, TESTED, DISABLED, ENABLED, REVOKED, ROTATED) com
quem/quando — **sem** valores.

O AAD (dados autenticados adicionais) do GCM é `vault:<id>:<provider>:<env>`: um ciphertext copiado para outro
registro não decifra. Segredos de MFA dos usuários usam o mesmo esquema (`cc_user_mfa:<userId>`).

## Chaves de criptografia (derivadas de `CONTROL_SETUP_KEY`)

Não existe mais “chave mestra” separada. Da chave de instalação saem, por HKDF-SHA256 (sal fixo da aplicação, um
contexto `info` por uso):

| Contexto | Uso |
|---|---|
| `vendocas-control/v1/install-auth` | HMAC que assina a sessão de instalação e compara a chave apresentada |
| `vendocas-control/v1/config-encryption` | AES-256-GCM da configuração fora do banco (connection string do Neon) |
| `vendocas-control/v1/data-encryption` | AES-256-GCM dos dados no banco (cofre, segredos MFA, desafios) |

Cada registro guarda `keyVersion = sk-<impressão digital>` (derivada da chave, nunca a chave). Chave ausente ou curta →
`503 SETUP_NOT_CONFIGURED` (falha fechada). Chave diferente da usada na gravação → `SETUP_KEY_MISMATCH`.

### Rotação

1. Na Netlify, copie o valor atual de `CONTROL_SETUP_KEY` para `CONTROL_SETUP_KEY_PREVIOUS`, defina uma nova
   `CONTROL_SETUP_KEY` e publique. (A sessão de instalação e o login continuam funcionando; dados antigos seguem legíveis.)
2. Segurança → **Recriptografar com a chave ativa** (OWNER + reautenticação): recifra credenciais, segredos MFA e a
   connection string no Blobs, cada um com nonce novo.
3. Confira o relatório, remova `CONTROL_SETUP_KEY_PREVIOUS` e publique.

**Perda da chave**: sem ela (e sem a anterior, durante uma rotação) nada do que foi cifrado pode ser lido. Guarde-a em um
gerenciador de senhas. Para recomeçar do zero: nova chave, apague o blob `database` do store `vendocas-control-config` e
use um banco novo.

## Connection string do banco do Control Center

Não fica no banco (seria circular): é guardada no Netlify Blobs, chave `database` do store `vendocas-control-config`,
como `{ sealed: {ciphertext, nonce, keyVersion}, fingerprint, provider, serverVersion, configuredAt, lockedAt }`,
cifrada com a chave de contexto `config-encryption` (AAD `cc_database_url`). A rotação também a recifra.

## Provedores e campos

| Provedor | Campos | Teste de conexão |
|---|---|---|
| GitHub | token | `GET /user`, `GET /user/codespaces` |
| Vendocas API (homologação) | baseUrl, statusToken | `GET /internal/status` |
| Stripe | secretKey, webhookSecret, publishableKey | `GET /v1/balance`; prefixo `sk_test_`/`sk_live_` deve casar com TEST/LIVE |
| Meta WhatsApp | appId, appSecret, systemUserToken, embeddedSignupConfigId, webhookVerifyToken, graphVersion | `GET /debug_token` |
| Twilio | accountSid, authToken | `GET /Accounts/{sid}.json` |
| Amazon SES | region, accessKeyId, secretAccessKey, configurationSet, snsTopicArn | `GET /v2/email/account` (SigV4) |
| Focus NFe | token | consulta de referência inexistente (404 = autenticado) |
| Cloudflare R2 / S3 | endpoint, region, bucket, accessKeyId, secretAccessKey | `ListObjectsV2 max-keys=1` (SigV4) |
| Personalizado | rótulo + 2 valores | sem teste automático |

Nenhum teste envia mensagens, cria cobranças ou emite notas.

## Regras de exposição

* Respostas da API, HTML, logs, auditoria e mensagens de erro **nunca** contêm valores completos. Erros de provedores
  passam por `redact()` (padrões Stripe, GitHub, Meta, AWS, Bearer) antes de qualquer gravação/resposta.
* Após salvar, só a máscara (`••••` + 4 últimos) é exibida. Não existe endpoint de leitura do valor.
* Decifrar só acontece no servidor, dentro das operações que precisam (testar, chamar GitHub, revogar).

## Credenciais do backend Vendocas

O cofre **não copia** automaticamente credenciais para a API Java. A API de homologação usa as próprias variáveis
(`/workspaces/.vendocas/secrets.env` e Codespaces secrets). Um fluxo de entrega (ex.: endpoint autenticado mTLS ou
gerenciador de segredos) deve ser desenhado e documentado antes de existir.
