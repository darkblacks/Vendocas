# Netlify — app web público (preparado, **não publicado**)

## Projeto

Netlify → **Add new project → Import from Git** → `darkblacks/Vendocas`, **Base directory** `apps/web`
(build/publish vêm de `apps/web/netlify.toml`). Projeto separado do Control Center.

No build da Netlify (`NETLIFY=true`) o `next.config.ts` não usa `output: "standalone"` (usado só na imagem Docker).

## Variáveis

| Variável | Exemplo | Observação |
|---|---|---|
| `API_INTERNAL_URL` | `https://<codespace>-8080.app.github.dev` (homologação) | destino do proxy `/api/v1/*` e do `/api/health` |

O navegador fala apenas com o domínio do site; `/api/v1/*` é reescrito no servidor para a API, mantendo o cookie de
refresh na mesma origem. Não há credenciais de provedores no frontend.

Para homologação via Codespace, a porta 8080 precisa estar **pública** (ver [codespaces-setup.md](codespaces-setup.md)).
Na API, inclua o domínio da Netlify em `CORS_ALLOWED_ORIGINS` e `APP_PUBLIC_URL`.

## Com a API desligada

Quando o Codespace está parado o proxy responde 502/504. O app:

* converte isso em `BACKEND_UNAVAILABLE` e mostra um aviso (“Serviço temporariamente indisponível”);
* **para de enviar requisições** (as chamadas falham localmente) e faz uma única verificação em `/api/health` com
  backoff exponencial (10 s → 2 min); volta sozinho quando a API responde;
* nunca liga o Codespace e não exibe dados fictícios.

## Contextos

Use variáveis diferentes por contexto (Production / Deploy previews) se houver uma API de produção no futuro; a
homologação do Codespace não deve ser o destino de produção.

## Deploy por Actions (opcional)

`ENABLE_NETLIFY_WEB_DEPLOY` — ver [github-actions.md](github-actions.md).
