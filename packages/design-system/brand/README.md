# Marca Vendocas

**Slogan:** *Toda venda é o começo da próxima.*
(es: *Cada venta es el comienzo de la próxima.* · en: *Every sale is the start of the next one.*)

A comunicação gira em torno de **continuidade e relacionamento**: a venda não termina no caixa — o Vendocas lembra,
agradece e traz o cliente de volta. O símbolo mostra isso: o **V laranja** (a venda) com a **seta que volta** (o retorno
do cliente) e as **setas azuis** subindo (crescimento).

> Textos sobre objetivo de conexão com o cliente e tom de voz: a definir pelo dono da marca (este arquivo é o lugar).

## Arquivos
| Arquivo | Uso |
|---|---|
| `originais/vendocas-simbolo.png` | símbolo original (1254 px, fundo transparente) |
| `originais/vendocas-banner.png` | banner com nome e slogan (2172 × 724) |
| `originais/vendocas-fundo-animado.gif` | fundo animado com brilhos azul e laranja |
| `vendocas-simbolo-512/192/64.png` | símbolo recortado e otimizado (ícones, app, favicon) |
| `vendocas-banner-1200.jpg` | banner otimizado para web e redes |

Cópias usadas pelos apps: `apps/web/public/brand/`, `apps/control/public/brand/`, `apps/web/src/app/icon.png`.

## Cores (medidas nas artes)
| Nome | Hex | Onde |
|---|---|---|
| Laranja Vendocas | `#FB6203` | V, ponto do "vendocas.", botões |
| Laranja claro | `#FD9401` | ponta da seta, "próxima.", fim do degradê |
| Laranja profundo | `#E64301` | sombra/dobra do V |
| Azul Vendocas | `#014DD8` | setas, sublinhado |
| Azul claro | `#017DF6` | brilho das setas, foco, links no escuro |
| Azul-noite | `#03050F` | fundo da marca |

Degradê principal: `#FB6203 → #FD9401` (135°). Degradê azul: `#014DD8 → #017DF6`.

### Regras de contraste (acessibilidade)
- **Nunca texto branco sobre o laranja vivo** (contraste 3:1, ilegível para muita gente). Sobre laranja, texto **azul-noite**.
- No tema claro, o laranja de texto e botão é **`#C24100`** (branco por cima: 5,3:1).
- Laranja `#FB6203` como texto só sobre fundo escuro (6,6:1). Azul claro `#017DF6` sobre azul-noite: 5:1.

## Tipografia
**Poppins** (Google Fonts): 700 para títulos e o nome, 400–600 para texto; itálico 500 no slogan, com a última palavra
em laranja ("**próxima.**").

## Fundo e brilho
O fundo da marca é azul-noite com dois arcos de luz: **azul no canto superior esquerdo** e **laranja no inferior
direito** (o GIF). Nos apps isso é a classe `brand-glow` (CSS), usada no topo do site e no onboarding — não em telas de
trabalho, para não atrapalhar a leitura.

## Nome
Sempre em minúsculas com o ponto laranja: **vendocas.** — o ponto é parte da marca.
