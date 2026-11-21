# PROGRESSO: etapas, pendências e decisões

Atualizado em 2026-11-21. Leia este arquivo antes de continuar o trabalho.

## Onde estamos

| Etapa | Estado |
|---|---|
| Entrega inicial: (a) avaliação da base, (b) arquitetura, (c) plano das Etapas 0 e 1 | ✅ entregue, **aguardando aprovação do admin** |
| Etapa 0 – Medição | 🔄 parcial (ver abaixo) |
| Etapa 1 – Fora da thread principal | ⏳ não iniciada |
| Etapa 2 – Observador | ⏳ |
| Portão "vai / não vai" | ⏳ |
| Etapa 2.5 – Orçamento de tick por região | ⏳ |
| Etapa 3 – Regiões, thread global, correio | ⏳ |
| Etapa 4 – Adapter de plugins | ⏳ |
| Etapa 5 – Módulos por mod | ⏳ |

Nenhum código de etapa futura foi escrito.

## Etapa 0: o que já está feito

| Tarefa | Estado | Evidência |
|---|---|---|
| 0.1 Ambiente de teste reproduzível | ✅ | `scripts/prepare-test-env.sh`, `scripts/run-test-server.sh`; servidor chega a "Done" com 292 mods |
| 0.2 Inventário estático | ✅ | `docs/dados/inventario-mods.csv`, `DIAGNOSTICO.md` §2 |
| 0.3 Linha de base ociosa | ✅ com ressalva | `DIAGNOSTICO.md` §4 (mundo novo contamina, D-10) |
| 0.4 Perfil sob carga real | 🧑 depende do admin | roteiro em `docs/03-plano-etapa-0-1.md` |
| 0.5 Mundo de teste com carga | 🧑 depende do admin | |
| 0.6 Bots de teste | ⏳ | |
| 0.7 Compilar a base do zero | ⏳ | precisa de JDK 8 |
| 0.8 Conferir estruturas hostis a threads | ⏳ | depende de 0.7 |

## Pendências

### Dependem do admin
1. **Aprovar a base**: Crucible `staging` em `67b53034` (`docs/01-avaliacao-base.md`).
2. **Aprovar a ordem em degraus** (orçamento por região antes de threads) e o portão "vai / não vai" depois da Etapa 2 (`docs/02-arquitetura.md` §3 e §7).
3. **Perfil de produção no pico** (roteiro na tarefa 0.4).
4. **Cópia do mundo** ou das regiões de 3 a 5 bases pesadas (tarefa 0.5).
5. **Arquivos que não vieram no pacote**: script de inicialização (flags da JVM e `-javaagent`), `server.properties`, `bukkit.yml`, `spigot.yml`, `cauldron.yml`, `Crucible.yml`.
6. **Máquina real**: quantos núcleos e quanta RAM. Define o número de threads de região.
7. **Código-fonte dos mods `Akashic-*` de desempenho** (SaveRelief, PlayerChunkAsync, TickRelief, MekThrottle), se puder compartilhar.
8. **Revisar a lista de plugins desligados no teste** (abaixo). Se algum faz falta para o teste valer, me diga.

### Técnicas
9. Bots com handshake do Forge na 1.7.10: não verificado (0.6).
10. Compilar o Crucible do zero: não verificado (0.7).
11. A lista de estruturas hostis a threads é conhecimento geral; falta conferir no código (0.8).
12. Trocar o objeto de `world.loadedTileEntityList` por uma lista por região: hipótese, precisa de protótipo na Etapa 2.
13. Plugin que lê bloco de outra região no modo de regiões independentes: sem solução geral ainda (`docs/02-arquitetura.md` §4, item 7).
14. O spark 1.10 da 1.7.10 aceita `--save-to-file`? Não verificado.

## Decisões tomadas (reversíveis; registradas para não rediscutir)

| # | Decisão | Motivo |
|---|---|---|
| 1 | Etapas 0 a 2 saem como **mod com mixins**, não como fork | roda no `server.jar` atual; adia a decisão cara; mixin sobre Crucible já provado (D-02) |
| 2 | Perfil no projeto é feito com **Java Flight Recorder**, não com o upload do spark | o spark publica o resultado em site externo (D-11) |
| 3 | Servidor de teste sempre para com `stop` | SIGTERM derruba o laço de tick nesta base (D-07) |
| 4 | Nada de jar, mundo ou config de produção no Git | segredos (D-04) e licença |
| 5 | Repositório em português simples; nomes de script em inglês | pedido do prompt |
| 6 | Branch de trabalho desta sessão: `claude/modest-lovelace-rx5r81` | é a branch designada. A regra "uma branch por etapa" vale a partir da Etapa 1 |

## Plugins desligados na cópia de teste

Movidos para `plugins/!DISABLED-HOSHIESTAR/` (nada é apagado). O motivo de cada um está no script.

- Pedidos pelo admin: **CastleOfStone**, **BungeeGuard**.
- Falam com serviços reais: DiscordSRV e Akashic-DiscordSRV-Legacy-NMS-Fix, LiteBans, NuVotifier, PixCraft, PixelVip, mRedeem, StatsAPI, pvpstats, ProtocolObelisk.
- Escondem log: FinalConsoleFilter.
- Mudam o resultado dos testes (voltam com `--keep-noise`): ClearLag, Broadcast, LiteAnnouncer.

Ajustes de config na cópia: LuckPerms de MySQL para H2 local; gateway de voz
apontado para `127.0.0.1`; `akashicfm` sem instalar ferramentas sozinho.
O mod `IronDome` (par do CastleOfStone) **ficou**, porque o cliente também o tem;
se ele barrar bots na tarefa 0.6, sai também.

## Registro de entregas

| Data | Entrega | Conferência |
|---|---|---|
| 2026-11-21 | Avaliação da base (subagente sonnet) | conferida contra o `server.jar` real: versão, Forge, libraries e `java9args.txt` batem |
| 2026-11-21 | Pesquisa de arte prévia (subagente sonnet) | aceita com ressalvas: itens marcados "não verificado" viraram pendências 9, 11 e 14 |
| 2026-11-21 | Ambiente de teste e linha de base (coordenador) | executado: boot até "Done", `stop` limpo, perfil JFR |
