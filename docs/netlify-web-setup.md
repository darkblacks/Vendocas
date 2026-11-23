# Netlify — app web público

## Projeto

Netlify → **Add new project → Import from Git** → `darkblacks/Vendocas`, **Base directory** `apps/web`
(build/publish vêm de `apps/web/netlify.toml`). Projeto separado do Control Center.

No build da Netlify (`NETLIFY=true`) o `next.config.ts` não usa `output: "standalone"` (usado só na imagem Docker).

## A API precisa estar rodando em outro lugar

A Netlify hospeda **só o site** (Next.js). A API Java (login, cadastro, preços, automações, cobrança) roda fora dela:

| Onde | Para quê | Observações |
|---|---|---|
| Codespace (homologação) | testes | ligue pelo Control Center; desliga sozinho (até 6 h e por inatividade); a porta 8080 precisa ser **pública** |
| Servidor próprio (VPS com `docker compose`, ver [deployment.md](deployment.md)) ou serviço de containers | produção | endereço fixo e sempre ligado |

Sem API alcançável, o site abre mas mostra “Serviço temporariamente indisponível” e nada que dependa da API funciona.
WhatsApp Business, emissão de NF, Twilio etc. **não** impedem o funcionamento: sem eles a API roda e esses recursos
aparecem como “não configurado”.

## Variáveis

| Variável | Exemplo | Observação |
|---|---|---|
| `API_INTERNAL_URL` | `https://<codespace>-8080.app.github.dev` (homologação) ou `https://api.seudominio.com` | destino do proxy `/api/v1/*` e do `/api/health`. Lido **a cada requisição**: mudar o valor não exige novo build (basta salvar; a Netlify aplica no próximo deploy de funções — em caso de dúvida, “Trigger deploy”). Escopo: *Functions* (ou *All*). |

O navegador fala apenas com o domínio do site; `/api/v1/*` é repassado no servidor (route handler) para a API, com corpo,
cabeçalhos e cookies intactos — o cookie de refresh fica na mesma origem. Não há credenciais de provedores no frontend.

Na API, inclua o domínio da Netlify em `CORS_ALLOWED_ORIGINS` e `APP_PUBLIC_URL`.

## Diagnóstico

Abra `https://<site>/api/health`:

| Resposta | Significa | O que fazer |
|---|---|---|
| `{"up":true}` | tudo certo | — |
| `API_NOT_CONFIGURED` | `API_INTERNAL_URL` não existe neste site | criar a variável |
| `API_INVALID_URL` | valor não é um endereço http(s) | corrigir |
| `API_UNREACHABLE` | ninguém respondeu nesse endereço | ligar o Codespace / servidor; conferir o endereço |
| `API_PRIVATE_PORT` | porta do Codespace privada (redireciona para o login do GitHub) | `gh codespace ports visibility 8080:public -c <nome>` ou aba Ports → Public |
| `API_TIMEOUT` | demorou demais | a API pode estar iniciando; tente de novo |
| `API_UNHEALTHY` | API respondeu mas está com problema (ex.: banco) | ver logs da API |

O endereço da API nunca é exibido nessas respostas.

## Com a API desligada

Quando a API não responde, o proxy devolve 502 com o motivo (tabela acima). O app:

* converte isso em `BACKEND_UNAVAILABLE` e mostra um aviso (“Serviço temporariamente indisponível”);
* **para de enviar requisições** (as chamadas falham localmente) e faz uma única verificação em `/api/health` com
  backoff exponencial (10 s → 2 min); volta sozinho quando a API responde;
* nunca liga o Codespace e não exibe dados fictícios.

## Contextos

Use variáveis diferentes por contexto (Production / Deploy previews) se houver uma API de produção no futuro; a
homologação do Codespace não deve ser o destino de produção.

## Deploy por Actions (opcional)

`ENABLE_NETLIFY_WEB_DEPLOY` — ver [github-actions.md](github-actions.md).
