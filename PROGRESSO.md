# PROGRESSO: etapas, pendências e decisões

Atualizado em 2026-11-22 (depois da entrega do medidor). Leia este arquivo antes de continuar o trabalho.

## Onde estamos

| Etapa | Estado |
|---|---|
| Entrega inicial: (a) avaliação da base, (b) arquitetura, (c) plano das Etapas 0 e 1 | ✅ **aprovada pelo admin** (base e ordem em degraus, 2026-11-21) |
| Etapa 0 – Medição | 🔄 falta só o admin rodar o medidor em produção (0.9) |
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
| 0.6 Bots de teste | 🔄 | bot mínimo em Python entra com o handshake do Forge (D-30, `tools/test_bot.py`); falta bot que anda e gera carga; não bloqueia |
| 0.7 Compilar a base do zero | ✅ | jar reconstruído igual ao de produção em 5.317 de 5.318 entradas; ligou e parou limpo |
| 0.8 Estruturas hostis a threads | ✅ | `docs/pesquisa/estruturas-hostis.md`: 138 estruturas com arquivo e linha, tratamento previsto e conferência independente (`DIAGNOSTICO.md` §8c). Só leitura de código |
| 0.9 Medidor de carga por região | ✅ construído, revisado e testado; 🧑 falta rodar em produção | `medidor/` (versão 1.0.4, sha256 do jar `7a414409…9b9f`); roteiro em `medidor/ROTEIRO-ADMIN.md` |

## Pendências

### Dependem do admin
1. **Aplicar `B:dontSleepOnThreadedIO=false`** no `config/hodgepodge.cfg` (linha 1293) num reinício. Devolve um núcleo inteiro (D-18). Decisão do admin; testado só em servidor ocioso.
2. **Saída de `/forge tps` no pico.** Dá o tempo por dimensão, que o spark não dá.
3. **Arquivos de produção que não vieram no pacote**: `spigot.yml`, `cauldron.yml`, `Crucible.yml`, `tileentities.yml`, `entities.yml`, `bukkit.yml`, `server.properties`.
4. **Rodar o medidor (0.9) em produção**: jar `Hoshiestar-Medidor-1.0.4-serveronly.jar` entregue; seguir `medidor/ROTEIRO-ADMIN.md` (backup, conferir relógio, aquecimento de 10 s, medição de 600 s no pico) e mandar os dois arquivos.
5. **Aceitar que base pesada tenha máquinas mais lentas** (Degrau A) como forma de isolar o lag.
6. Opcional: arquivos de região de 3 a 5 bases pesadas e o `level.dat`; código-fonte dos mods `Akashic-*` de desempenho; um segundo perfil do spark com mais jogadores.
7. Conferir o agente `Akashic-nChat-ChainColor`: ele se desliga no boot por esperar o nChat 2.0.4 (D-28).
7b. **Regra para servidor de teste que trava antes de ligar** (D-29): hoje a regra é "nunca `kill`". O admin autorizou encerrar um processo travado uma vez (2026-11-22), mas o caso se resolveu sozinho. Proposta para virar regra: se a cópia de teste não chega a "Done" em 12 minutos e o `stop` fica 5 minutos sem efeito, pode-se encerrar o processo, depois de guardar dois dumps de thread. Aguarda resposta do admin.

### Técnicas
8. Bots de carga (que andam e carregam chunks): não feito. O handshake do Forge funciona com o bot mínimo (D-30).
8b. Medidor, para uma próxima versão (achados menores, não mudam o jar testado): recusar argumento desconhecido no `hoshie medir`; mensagem de erro da pasta reserva com o motivo; criar a pasta reserva já fechada para o dono. Lista em `medidor/README.md`.
8c. Medidor, não testado: operador de nível 1 a 3, relógio lento de verdade, cliente de verdade, carga de produção. O limite de custo da válvula depende da calibração na máquina de produção.
9. Lacunas do estudo 0.8, para o observador da Etapa 2: varrer os jars dos mods atrás dos mesmos padrões; singletons de IC2, AE2, EnderIO, CoFH e BuildCraft; frequência de carga de chunk dentro do tick; reproduzir os três defeitos antigos do D-37.
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
| 12 | Semente fixa no mundo de teste (`HOSHIE_SEED` troca) | semente aleatória travou um boot na geração de árvore do Thaumcraft (D-29) |
| 13 | Medidor: a válvula de custo usa 10% do tick de antes de ligar, com piso de 5 ms e teto de 10 ms, e margem de 2x sobre o custo calibrado | decisão do implementador depois que o revisor mostrou que 5 ms fixos parariam a medição de pico; são constantes, fácil de mudar |
| 14 | Medidor entregue na versão 1.0.4 com achados menores em aberto | corrigi-los mudaria o jar já testado; nenhum afeta a segurança do tick |
| 15 | O Degrau B começa só com tile entities e entidades; ticks de bloco ficam em série no início | é onde está o custo (47% contra 14% do tick) e tira dez estruturas de risco do primeiro passo; estimativa cai para ~54 a 57 ms com 4 threads (conta, não medição) |
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
| 2026-11-22 | Medidor 1.0.0 a 1.0.2 (construtor opus; revisor opus e testador sonnet diferentes, 3 rodadas) | rodadas 1 e 2 com achados sérios corrigidos; na rodada 3 a revisão só teve achados menores, mas o teste independente não terminou (boot travou na geração de mundo, D-29) |
| 2026-11-22 | Medidor 1.0.3 (correção dos 8 achados menores; revisor diferente, 2 rodadas) | revisor achou 1 importante (regra de custo pararia a medição de pico), corrigido; segunda revisão só com menores |
| 2026-11-22 | Medidor 1.0.4 (3 ajustes fora do caminho quente) | revisão: aprovado com ressalvas menores. Teste independente no modpack real: **aprovado**, T1 a T6 passaram: boot com e sem o firewall, 104 funis contados exatos (62.504 = 104 × 601 ticks), nenhum nome/UUID/IP nos arquivos, parada e desligamento no meio gravam os arquivos, custo ligado dentro do ruído com 2.088 funis e 400 porcos (estimado 0,21 ms por tick) |
| 2026-11-22 | Conferência do coordenador | jar confere com o sha256 testado; contém só classes do medidor; nenhum jar no Git; o commit de um subagente com a assinatura errada foi corrigido antes do envio |
| 2026-11-22 | Estudo das estruturas hostis a threads, tarefa 0.8 (4 mapeadores opus, 4 conferentes opus, redator sonnet) | conferentes: 95 confirmados, 40 corrigidos, 1 refutado, 23 acrescentados; o coordenador conferiu 4 itens no código (cache do mapa de chunks, lista de colisão, ordem do tick, contador de ticks agendados) |
