# PROGRESSO: etapas, pendências e decisões

Atualizado em 2026-11-22. Leia este arquivo antes de continuar o trabalho.

## Onde estamos

| Etapa | Estado |
|---|---|
| Entrega inicial: (a) avaliação da base, (b) arquitetura, (c) plano das Etapas 0 e 1 | ✅ **aprovada pelo admin** (base e ordem em degraus, 2026-11-21) |
| Etapa 0 – Medição | 🔄 falta só a distribuição da carga pelo mapa (tarefa 0.9) |
| Etapa 1 (revista) – Ganhos baratos medidos, "Degrau 0" | ⏳ não iniciada |
| Etapa 2 – Observador | ⏳ |
| Portão "vai / não vai" | ⏳ |
| Etapa 2.5 – Orçamento de tick por região | ⏳ |
| Etapa 3 – Regiões, thread global, correio | ⏳ |
| Etapa 4 – Adapter de plugins | ⏳ |
| Etapa 5 – Módulos por mod | ⏳ |

Nenhum código de etapa futura foi escrito.

## Etapa 0

| Tarefa | Estado | Evidência |
|---|---|---|
| 0.1 Ambiente de teste reproduzível | ✅ | scripts com as flags de produção; servidor chega a "Done" com 292 mods |
| 0.2 Inventário estático | ✅ | `docs/dados/inventario-mods.csv`, `DIAGNOSTICO.md` §2 |
| 0.3 Linha de base ociosa | ✅ com ressalva | `DIAGNOSTICO.md` §4 |
| 0.4 Perfil sob carga real | ✅ | perfil do spark de produção analisado e revisado (`DIAGNOSTICO.md` §6) |
| 0.5 Mundo de teste com carga | 🧑 | mundo de 150 GB não pode ser copiado; alternativa: arquivos de região de poucas bases |
| 0.6 Bots de teste | ⏳ | não bloqueia |
| 0.7 Compilar a base do zero | ✅ | jar reconstruído igual ao de produção em 5.317 de 5.318 entradas; ligou e parou limpo |
| 0.8 Estruturas hostis a threads | 🔄 | tick, threads, estado estático e campos públicos mapeados; falta a lista vanilla item a item |
| 0.9 Medidor de carga por região | 🔄 | novo; em construção |

## Pendências

### Dependem do admin
1. **Aplicar `B:dontSleepOnThreadedIO=false`** no `config/hodgepodge.cfg` (linha 1293) num reinício. Devolve um núcleo inteiro (D-18). Decisão do admin; testado só em servidor ocioso.
2. **Saída de `/forge tps` no pico.** Dá o tempo por dimensão, que o spark não dá.
3. **Arquivos de produção que não vieram no pacote**: `spigot.yml`, `cauldron.yml`, `Crucible.yml`, `tileentities.yml`, `entities.yml`, `bukkit.yml`, `server.properties`.
4. **Rodar o medidor (0.9) em produção** quando o jar estiver pronto e testado.
5. **Aceitar que base pesada tenha máquinas mais lentas** (Degrau A) como forma de isolar o lag.
6. Opcional: arquivos de região de 3 a 5 bases pesadas e o `level.dat`; código-fonte dos mods `Akashic-*` de desempenho; um segundo perfil do spark com mais jogadores.
7. Conferir o agente `Akashic-nChat-ChainColor`: ele se desliga no boot por esperar o nChat 2.0.4 (D-28).

### Técnicas
8. Bots com handshake do Forge na 1.7.10: não verificado (0.6).
9. Lista vanilla de estruturas hostis a threads, item a item (0.8).
10. Trocar o objeto de `world.loadedTileEntityList` por uma lista por região: hipótese, precisa de protótipo na Etapa 2.
11. Plugin que lê bloco de outra região no modo de regiões independentes: sem solução geral ainda.
12. O spark 1.10 da 1.7.10 aceita `--save-to-file`? Não verificado.
13. Itens marcados "não verificado" nos relatórios de `docs/pesquisa/` (cada um tem a lista no fim).

## Decisões tomadas (reversíveis; registradas para não rediscutir)

| # | Decisão | Motivo |
|---|---|---|
| 1 | Etapas 0 a 2 saem como **mod com mixins**, não como fork | roda no `server.jar` atual; adia a decisão cara; mixin sobre Crucible já provado (D-02) |
| 2 | Perfil no projeto é feito com **Java Flight Recorder**, não com o upload do spark | o spark publica o resultado em site externo (D-11) |
| 3 | Servidor de teste sempre para com `stop` | SIGTERM derruba o laço de tick nesta base (D-07, causa em D-24) |
| 4 | Nada de jar, mundo ou config de produção no Git | segredos (D-04) e licença |
| 5 | Repositório em português simples; nomes de script em inglês | pedido do prompt |
| 6 | Branch de trabalho: `claude/modest-lovelace-rx5r81` | é a branch designada da sessão |
| 7 | **Etapa 1 muda de foco**: de "tirar I/O da thread principal" para "ganhos baratos medidos" | I/O vale 2,9% do tick; quatro pontos sem threads valem até ~20% (D-16, D-17) |
| 8 | **Novo Degrau 0** antes do orçamento por região | nenhum degrau chega a 20 TPS sozinho neste perfil (D-14, D-15) |
| 9 | Grupo de **3 a 4 threads de região** atendendo uma fila, não uma thread por região | máquina de 7 vCores; 208 threads do Distant Horizons mostram o custo do outro modelo (D-22) |
| 10 | Nova tarefa 0.9: medidor de carga por região, rodado em produção pelo admin | o mundo não pode ser copiado e o spark não divide por lugar |
| 11 | Seguir os hábitos do upstream (patch gerado, `// Hoshiestar`, opções em `Crucible.yml`, JDK 8) e mandar correções genéricas como pull request ao Crucible | manter o fork fácil de atualizar (`docs/04-estudo-crucible.md` §5) |

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
| 2026-11-22 | Estudo do Crucible: história, arquitetura de patches, tick e threads (subagentes sonnet e opus) | dois dos três revisados por agente independente: 23 afirmações confirmadas, 3 imprecisas; correções anexadas a cada relatório |
| 2026-11-22 | Build da base do zero (subagente sonnet) | revisor confirmou 9 de 10 afirmações e fez o teste de boot: "Done", 292 mods, parada limpa |
| 2026-11-22 | Análise do perfil de produção (subagente opus) | revisor recalculou do arquivo bruto: 10 confirmadas, 2 imprecisas (I/O é 2,9% e não 3,45%; plugins ~5% e não 2,9%); já corrigido no `DIAGNOSTICO.md` |
| 2026-11-22 | Análise do stall (subagente sonnet) | conferida pelo coordenador contra o dump (pilha, trava, contagem de threads) |
| 2026-11-22 | Medição da "File IO Thread" (coordenador) | primeira medição descartada por erro do script (leu log antigo); refeita: 101,4% contra 0,1% de um núcleo |
