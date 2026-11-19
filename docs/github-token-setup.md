# Token do GitHub para o Control Center

O Control Center usa um token do GitHub **guardado no cofre** (nunca em variável de ambiente, nunca no Git, nunca no
navegador) para listar, ligar e desligar o Codespace e ler o estado do CI.

## Recomendado: fine-grained personal access token

GitHub → Settings → Developer settings → **Fine-grained tokens → Generate new token**

* **Resource owner**: a conta dona do repositório; **Repository access**: *Only select repositories* → `Vendocas`.
* **Expiration**: 90 dias ou menos (rotacione pelo cofre).
* **Repository permissions** (mínimas):
  * Metadata — Read (obrigatória)
  * Actions — Read (execuções de CI na página Deploys)
  * Contents — Read (último commit de `main`)
  * Codespaces — Read (listar codespaces do repositório)
  * Codespaces lifecycle admin — Read and write (ligar/desligar)
* **Account permissions**:
  * Codespaces — Read and write (endpoints `/user/codespaces/*`)
  * Plan — Read (opcional: uso/cobrança de Codespaces; sem ela o painel mostra “indisponível pela API”)

Não conceda: Administration, Secrets, Workflows, Contents write, Codespaces secrets, Pull requests.

> Os nomes das permissões podem mudar na interface do GitHub. Se o teste do cofre retornar `PERMISSION_DENIED` (403),
> o painel indica qual chamada falhou.

## Alternativa: token clássico

Escopo `codespace` (e `repo` se o repositório for privado, para ler Actions). É mais amplo — prefira o fine-grained.

## Cadastrar

1. Control Center → **Credenciais** → *GitHub* → ambiente **LIVE** → cole o token → **Salvar** (pede reautenticação).
2. **Testar**: chama `GET /user` e `GET /user/codespaces`; mostra o login, sem nunca devolver o token.
3. **Servidor** → selecione o codespace do repositório `CONTROL_GITHUB_REPOSITORY` (somente esse repositório é aceito,
   validado no servidor).

## Revogar / trocar

* **Substituir**: cadastre o novo token no mesmo lugar (o antigo é sobrescrito e o histórico registra quem trocou).
* **Revogar**: botão *Revogar* chama a API de revogação de credenciais do GitHub (`POST /credentials/revoke`) e destrói o
  valor local. Confirme também em GitHub → Settings → Tokens.
* Vazamento suspeito: revogue no GitHub imediatamente, depois cadastre um novo.
