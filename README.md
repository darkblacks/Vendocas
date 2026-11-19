# Vendocas

Plataforma SaaS B2B multiempresa para **recuperação de vendas, relacionamento e automações de comunicação**
(e-mail, WhatsApp, SMS/MMS e ligações perdidas) — Brasil, Estados Unidos e União Europeia.

O dono do negócio define **QUANDO → ESPERAR → VERIFICAR → ENVIAR**, conecta os próprios canais, paga uma assinatura mensal
e o sistema executa sozinho, respeitando consentimentos, descadastros, limites de frequência e orçamento.

## Monorepo
```
apps/api            Spring Boot 4.1 · Java 25 · PostgreSQL · Flyway — monólito modular (com.platform.*)
apps/control        Control Center privado (Next.js · Netlify Functions · Postgres próprio): Codespace, cofre, deploys, auditoria
apps/web            Next.js 16 · React 19 · next-intl (pt/en/es + fr/de estruturais) · Tailwind 4 · Radix · React Flow · Recharts
packages/contracts  contrato OpenAPI exportado da API
packages/api-client cliente TypeScript tipado gerado do contrato
packages/design-system  tokens (claro/escuro) e utilitários
packages/tracking-sdk   SDK de site (~3 KB) com consentimento e identidade assinada
infrastructure/     Dockerfiles, Caddy (HTTPS), Prometheus/alertas, scripts do Codespace (codespaces/)
.devcontainer/      ambiente de homologação reproduzível (GitHub Codespaces)
scripts/            dev, backup/restore, exportação do contrato
docs/               arquitetura, regras, banco, API, integrações, faturamento, tributação, privacidade, operação, testes, DR, países, roadmap, status
legacy/             app anterior (ERP financeiro local) preservado sem alterações
```

## Começar
```bash
cp .env.example .env
./scripts/dev.sh
```
Detalhes em [docs/deployment.md](docs/deployment.md). Testes em [docs/testing.md](docs/testing.md).

## Control Center, Codespace e CI/CD
[docs/control-center.md](docs/control-center.md) · [codespaces-setup](docs/codespaces-setup.md) ·
[github-token-setup](docs/github-token-setup.md) · [credentials-vault](docs/credentials-vault.md) ·
[netlify-web-setup](docs/netlify-web-setup.md) · [netlify-control-setup](docs/netlify-control-setup.md) ·
[github-actions](docs/github-actions.md) · [environment-variables](docs/environment-variables.md) ·
[security](docs/security.md) · [deployment-checklist](docs/deployment-checklist.md)

## Estado
Veja **[docs/status.md](docs/status.md)** — separa o que está implementado, testado automaticamente, validado em sandbox
e em produção, e o que depende de credenciais/aprovações externas ([docs/external-setup.md](docs/external-setup.md)).
Nenhuma integração é apresentada como conectada sem confirmação real do provedor.
