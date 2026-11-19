# Roadmap

## Curto prazo (antes do lançamento comercial)
1. Validar cada integração em sandbox real (Stripe test mode, SES sandbox, Meta test WABA, Twilio test credentials, Focus NFe homologação).
2. Revisão jurídica: termos, privacidade, DPA, políticas por país; definir preços BRL/EUR e tarifas de uso.
3. Testes de carga (k6) do dispatcher e do motor; pgBouncer; índices conforme planos de execução reais.
4. Expurgo automático por `data_retention_days`; job de reconciliação de custos reais (`provider_costs.actual_cost_micros`).
5. Automatizar registro A2P 10DLC (TrustHub API) e templates SMS por país.

## Médio prazo
* Pix Automático (PSP brasileiro) como segundo `PaymentProvider`; e-invoicing nacional na UE.
* Respostas de e-mail (SES inbound), rastreamento de cliques (`tracked_links` já modelado), atribuição configurável.
* Integrações oficiais com plataformas de e-commerce (Shopify, Nuvemshop, WooCommerce) sobre a API de eventos.
* Várias unidades por empresa (assinaturas adicionais por estabelecimento já suportadas no modelo: 1 assinatura viva por organização).
* Implantação multi-região efetiva conforme `data_region`; RLS no PostgreSQL como defesa em profundidade.
* Traduções completas fr/de; editor de e-mail visual; testes de acessibilidade automatizados.
