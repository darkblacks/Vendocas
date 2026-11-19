# Cofre de credenciais

## Modelo

Tabela `integration_credentials` (banco do Control Center):

| Coluna | Conteúdo |
|---|---|
| `id` | UUID único do registro |
| `provider`, `environment` | ex.: `STRIPE` / `TEST` ou `LIVE` (um registro ativo por par) |
| `encrypted_payload` | JSON dos campos, cifrado com **AES-256-GCM** |
| `nonce` | 96 bits aleatórios, **novo a cada gravação** |
| `encryption_key_version` | versão da chave mestra usada (`v1`, `v2`, …) |
| `masked_hint` | apenas pistas não secretas (ex.: `••••4f2a`, account SID público) |
| `status` | `UNTESTED`, `VALID`, `INVALID`, `DISABLED`, `REVOKED` |
| `last_tested_at`, `last_error_code`, `last_error_message` | resultado do último teste (mensagem higienizada) |
| `created_by`, `updated_by`, `version` | autoria e versão |

`integration_credential_history` guarda cada ação (CREATED, REPLACED, TESTED, DISABLED, ENABLED, REVOKED, ROTATED) com
quem/quando — **sem** valores.

O AAD (dados autenticados adicionais) do GCM é `vault:<id>:<provider>:<env>`: um ciphertext copiado para outro
registro não decifra. Segredos de MFA dos usuários usam o mesmo esquema (`cc_user_mfa:<userId>`).

## Chave mestra

* Variável **secreta** da Netlify `CONTROL_MASTER_KEYS="v1:<base64 32 bytes>"` e `CONTROL_ACTIVE_KEY_VERSION=v1`.
* Gere com `pnpm --filter @vendocas/control keys:generate` (localmente, nunca em CI) e guarde uma cópia offline
  (gerenciador de senhas). **Sem a chave, nada no cofre pode ser lido** — não existe recuperação.
* Nunca no Git, nunca no banco, nunca no armazenamento temporário de funções.
* Ausente/ inválida → o cofre responde `503 CONFIG_MISSING` (falha fechada).

### Rotação

1. Gere `v2` e defina `CONTROL_MASTER_KEYS="v1:…,v2:…"`, `CONTROL_ACTIVE_KEY_VERSION=v2`; publique.
2. Segurança → **Rotacionar chave** (OWNER + reautenticação): recifra todas as credenciais e segredos de MFA com `v2`.
3. Confirme que o relatório mostra 0 registros em `v1`; remova `v1` da variável e publique.

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
