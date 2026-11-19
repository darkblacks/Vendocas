# Países suportados

Modelo de dados e interface: **Brasil, Estados Unidos e os 27 membros da UE** (`supported_countries`). Separados:
país de constituição, país de faturamento, país do destinatário (pelo telefone), país do número, região de dados, idioma
(BCP 47), fuso (IANA) e moeda (ISO 4217; BRL/USD/EUR na carteira e nos preços).

Matriz de capacidades (`channel_capabilities`, revisada em 2026-11-19; desconhecido = indisponível):

| Canal / capacidade | BR | US | UE |
|---|---|---|---|
| E-mail transacional/marketing | ✓ | ✓ | ✓ |
| WhatsApp transacional | ✓ | ✓ | ✓ |
| WhatsApp marketing | ✓ | ✕ (restrição Meta) | ✓ |
| SMS | ✓ (bundle regulatório) | ✓ (A2P 10DLC / toll-free verificado) | ✓ (bundle conforme país) |
| MMS | ✕ (usar link) | ✓ | ✕ (usar link) |
| Remetente alfanumérico | ✕ (pendente verificação) | ✕ | ✕ (pendente verificação por país) |
| Ligações perdidas | ✓ | ✓ | ✓ |

Admins ajustam a matriz em Admin → Capabilities (auditado). A interface mostra apenas o que está disponível para o país da
organização, com o requisito de cada item.
