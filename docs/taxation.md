# Tributação e documentos fiscais

* **Cálculo de impostos**: delegado ao **Stripe Tax** (`automatic_tax` no Checkout e nas assinaturas por fatura;
  `STRIPE_AUTOMATIC_TAX=true`). Exige registros fiscais configurados no Stripe para cada jurisdição onde o vendedor tem
  obrigação (estados dos EUA com nexus, OSS/IVA na UE, etc.). Não existe alíquota global inventada no código.
* **IVA UE**: VAT ID validado por formato e consultado no **VIES** (`POST /organizations/current/tax-profile/verify`);
  indisponibilidade do VIES resulta em `VERIFICATION_UNAVAILABLE`, nunca em "válido". O reverse charge vem do Stripe Tax
  (`taxability_reason = reverse_charge`) e é exibido na fatura.
* **EUA**: EIN validado por formato; sales tax pelo Stripe Tax conforme registros do vendedor.
* **Brasil — NFS-e**: quando o vendedor é brasileiro (`SELLER_COUNTRY=BR`) e o comprador é do Brasil, toda fatura paga
  gera uma obrigação em `tax_documents`. A emissão pela **Focus NFe** (job `fiscal.issue`, somente faturas `PAID` em BRL,
  referência idempotente `vd-<invoiceId>`, consulta assíncrona até `autorizado`) só acontece com a emissão automática
  ligada e o provedor selecionado e configurado; caso contrário fica `AWAITING_MANUAL` ou `NOT_CONFIGURED` (nunca número
  fictício). O módulo é acionado por eventos do Billing, não pelo Billing — ver
  [environments-billing-fiscal.md](environments-billing-fiscal.md).
  CNPJ alfanumérico (IN RFB 2.229/2024) suportado na validação.
* **UE e-invoicing nacional** (ex.: Itália SDI, França 2026+): **pendente** — registrar provedor por país.

## Configuração
`SELLER_LEGAL_NAME`, `SELLER_COUNTRY`, `SELLER_TAX_ID`, `SELLER_MUNICIPAL_REGISTRATION`, `SELLER_NFSE_SERVICE_CODE`,
`SELLER_MUNICIPALITY_CODE`, `FOCUS_NFE_TOKEN`, `FOCUS_NFE_ENVIRONMENT` (`homologacao` primeiro).

> O enquadramento tributário (entidade vendedora, códigos de serviço, regime, ISS, retenções) deve ser definido com contador
> e validado em homologação municipal antes do uso comercial.
