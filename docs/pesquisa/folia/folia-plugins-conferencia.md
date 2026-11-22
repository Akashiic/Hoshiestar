# Conferência: "Folia: como trata plugins" (folia-plugins.md)

- Conferente: agente diferente do autor. Só leitura de patches. Nada compilado, nenhum servidor ligado, nenhum commit.
- Clone conferido: Folia `eca40f918928e78c2c5f9c944cfb083d7d9851d4` (2026-11-18). NeoFolia `fa76006de0c01f5cd0c3290f0994898c39e8441f` (só para a linha 8 da seção 10).
- Abreviações iguais às do relatório (linha N = linha dentro do arquivo `.patch`):
  - **A4** = `folia-server/minecraft-patches/features/0004-Region-Threading-Base.patch`
  - **B2** = `folia-server/paper-patches/features/0002-Region-Threading-Base.patch`
  - **C2** = `folia-api/paper-patches/features/0002-Region-scheduler-API.patch`
  - **C3** = `folia-api/paper-patches/features/0003-Require-plugins-to-be-explicitly-marked-as-Folia-sup.patch`
  - **C4** = `folia-api/paper-patches/features/0004-Add-TPS-From-Region.patch` (o relatório não usou este)
  - **B7** = `folia-server/paper-patches/features/0007-Add-TPS-From-Region.patch` (idem)

Resultado: 65 afirmações conferidas. 54 confirmadas, 9 corrigidas, 1 refutada, 1 não verificável.
O relatório é, no geral, bom e honesto (marca bem o que é do Paper e não está no clone). Os erros são de número de linha, de contagem e de três frases fortes demais. Um erro é de fato (TPS na thread global).

---

## 1. Tabela: afirmação -> veredito -> evidência

| # | Afirmação do relatório | Veredito | Evidência (commit eca40f9) |
|---|---|---|---|
| 1 | Plugin sem `folia-supported: true` não carrega: `RuntimeException` nas fábricas Paper e Spigot | Confirmada | B2 735-739 (`PaperPluginProviderFactory.build`), B2 751-755 (`SpigotPluginProviderFactory.build`) |
| 2 | Terceira checagem lança `InvalidPluginException("... not marked as supporting regionised multithreading")` | Confirmada | B2 4126-4130, `CraftMagicNumbers` (logo depois da checagem de `api-version`) |
| 3 | No `plugin.yml`, só vale se o texto for `true`, sem diferenciar maiúscula | Confirmada | C3 52-54 (`equalsIgnoreCase("true")`), C3 64-66 (lê `map.get(...).toString()`) |
| 4 | `PaperPluginMeta` ganhou `boolean foliaSupported = false` | Confirmada | B2 709, getter B2 717-721 |
| 5 | Não há chave escondida para liberar plugin não marcado | Confirmada (nos patches) | Busca por `Boolean.getBoolean`, `System.getProperty`, `Integer.getInteger` em linhas `+` de todos os `.patch`: zero resultados |
| 6 | Não há camada de compatibilidade: nem trava global, nem modo em série, nem "thread principal emulada" | Confirmada (nos patches) | Nada disso nas linhas `+`. Ressalva: o Paper não está no clone |
| 7 | `BukkitScheduler` marcado `@Deprecated`, javadoc aponta os 4 novos | Confirmada | C2 33-41 |
| 8 | `CraftScheduler.handle` começa com `throw new UnsupportedOperationException()` em B2 4065 | Corrigida (linha) | O `throw` está em B2 **4066**; 4065 é a assinatura do método |
| 9 | Resumo item 3: "**todo** agendamento pelo `BukkitScheduler` lança `UnsupportedOperationException`" | Corrigida | Afirmado como fato no resumo, mas a seção 4.6 do próprio relatório diz que quais métodos passam por `handle` é "de memória, não verificado". O certo: `handle` lança sempre (B2 4066); que todo `runTask*`/`scheduleSync*` passe por `handle` **não foi verificado** (corpo no Paper, fora do clone) |
| 10 | O "batimento" do agendador antigo foi desligado | Confirmada | A4 8556 sai `mainThreadHeartbeat()`, A4 8564 entra comentado |
| 11 | Ao desligar plugin, cancela tarefas do agendador assíncrono, não mais do `BukkitScheduler` | Confirmada | C2 17-18 (`SimplePluginManager`); B2 691-697 (`PaperPluginInstanceManager`, bloco removido) |
| 12 | Só o agendador de região é do Folia; global, assíncrono e de entidade já existem no Paper | Confirmada | B2 828-832: linhas de contexto (sem `+`) já têm `FoliaAsyncScheduler` e `FoliaGlobalRegionScheduler`; só `FallbackRegionScheduler` -> `FoliaRegionScheduler` muda (B2 829-830). `FoliaEntityScheduler` também é arquivo do Paper (B2 783-794, só ajuste volátil) |
| 13 | `RegionScheduler.execute` põe a tarefa na fila de tarefas da região dona | Confirmada | A4 6786-6794 (`taskQueue.queueTickTaskQueue`) |
| 14 | `runDelayed`/`runAtFixedRate` recusam atraso `<= 0` e plugin desligado | Confirmada | A4 6807-6813, 6837-6846. Falta dizer: `execute` **não** faz essas checagens, e `run` é `runDelayed(..., 1)`, ou seja, roda no próximo tick, não na hora (A4 6797-6799) |
| 15 | "**Mantém o chunk carregado** enquanto há tarefa pendente" (ticket `REGION_SCHEDULER_API_HOLD`) | Corrigida | O ticket é posto com nível `ChunkHolderManager.MAX_TICKET_LEVEL` só no chunk do canto da seção (A4 6920-6929). O comentário do código diz o motivo: "need to keep the scheduler loaded for this location in order for tick() to be called" (A4 6963). Ou seja, o objetivo é manter a **região viva** para o agendador ticar. Se esse nível de ticket deixa o chunk carregado por completo: **não verificado** (valor de `MAX_TICKET_LEVEL` está no Moonrise/Paper) |
| 16 | Na junção, prazos corrigidos por deslocamento; na divisão, cada seção leva suas tarefas | Confirmada | A4 6872-6913 (`merge` soma `fromTickOffset`; `split` copia `tickCount` e move por seção) |
| 17 | Exceção dentro da tarefa é capturada e registrada no logger do plugin | Confirmada | A4 6754-6762 (`wrap`), A4 7056-7060 (`LocationScheduledTask.run`) |
| 18 | Tarefa repetida volta para a mesma região | Confirmada | A4 7061-7076 (`scheduleInternalOnRegion(this, this.repeatDelay)` no `finally`) |
| 19 | Agendador de região tica no início do tick de cada região | Confirmada | A4 8403 |
| 20 | Agendador global tica em `RegionizedServer.globalTick`; saiu do tick do servidor | Confirmada | A4 1810; A4 8566 sai, 8575 comentado |
| 21 | Tarefa global não tem região, então não pode mexer em bloco, chunk ou entidade (só jogador em login/config) | Confirmada | A4 1727 (`super(null, ...)`), A4 5530-5532; B2 83-91, 125-131; B2 163-184. Detalhe: jogador com `connection == null` também passa (B2 169-171) |
| 22 | Lista de agendadores de entidade passou a ser por região | Confirmada | A4 3221 (campo em `RegionizedWorldData`), A4 2969 e 3079 (junção/divisão), A4 15740-15741 (registro) |
| 23 | Agendador de entidade só roda se a thread é dona da entidade e ela não foi aposentada | Confirmada | A4 8405-8411 |
| 24 | Jogador que sai tem o agendador aposentado; `teleportAsync` completa com `FALSE` nesse caso | Confirmada | A4 14072 (contexto, `retireScheduler`); B2 2290-2302 |
| 25 | Resumo item 5: "**Não existe mais thread principal**" | Corrigida | A frase é do README (linha 79), não do código citado. No código a thread principal do servidor continua existindo: depois de `RegionizedServer.init()` ela entra em `for(;;) Thread.sleep(Long.MAX_VALUE)` (A4 8266-8274). Ela não tica nada. O tick acontece no pool de threads `TickThreadRunner` (A4 5225-5242, 5433), e a região global é agendada no mesmo pool (A4 1641) |
| 26 | Ações do jogador rodam na região dona, porque os pacotes dele são processados no tick da região | Confirmada | A4 8397-8401; pacotes entram pela fila própria do jogador (B2 3080, 3097-3106) |
| 27 | `ServerTickStartEvent`/`ServerTickEndEvent` disparam uma vez por região por tick | Confirmada | A4 8394, 8461; `tickServer` só é chamado de A4 6197 (tick de região). A região global **não** dispara esses eventos (`globalTick`, A4 1803-1830, não chama `tickServer`) |
| 28 | `PlayerChatEvent` e `ChatEvent` (chat síncrono) deixaram de ser disparados | Confirmada | B2 289, 298 (`if (false && listenersOnSyncEvent)`) |
| 29 | `AsyncPlayerChatEvent` continua | Confirmada | B2 286-287 |
| 30 | Parte síncrona do chat vai para o agendador da entidade jogador | Confirmada | A4 13549 |
| 31 | Completar comando no console roda na região global | Confirmada | B2 1531, 1540 (`RegionizedServer.getInstance().addTask`) |
| 32 | RCON: comando vira tarefa na fila global e a thread do RCON espera (`join`) | Confirmada | A4 10514-10531 |
| 33 | Envio da árvore de comandos (`runSync`) vai para o agendador da entidade jogador | Confirmada | A4 7448-7452 |
| 34 | Estáticos do `CraftEventFactory` viraram `ThreadLocal` | Confirmada | B2 3824, 3842 |
| 35 | `PaperEventManager` não foi alterado pelo Folia | Confirmada | Nenhum diff de `PaperEventManager` nos patches; `isPrimaryThread`/`AsyncCatcher` só aparecem em linhas removidas ou comentadas (B2 1002, 1015, 1032, 3124-3132; A4 13180, 13666) |
| 36 | `CraftBlock.getBlockState` "e cerca de **20** outros métodos" checam a thread dona | Corrigida | São **15** checagens no total dentro do diff de `CraftBlock` (B2 1296-1500, contagem de `ensureTickThread` em linhas `+`) |
| 37 | `CraftEntity.getHandle()` checa a thread dona (**B2 166**) e "mais **168** subclasses" | Corrigida | `CraftEntity.getHandle` está em **B2 2195** (B2 166 é código do `TickThread`). A mensagem "Accessing entity state off owning region's thread" aparece **168 vezes, em 168 arquivos, todas em `getHandle()`**, contando o próprio `CraftEntity` e `PaperSchoolableFish` (B2 488). Logo são 166 subclasses de `CraftEntity` mais esses dois, não 168 a mais |
| 38 | `CraftPlayer.getHandle()` não tem checagem ("total mess") | Confirmada | B2 3116 |
| 39 | Resumo item 8: "**hora, clima**, metadados de mundo e limites de spawn só na thread global" | Corrigida | Para hora e clima só **mudar** é checado: mensagens "Cannot modify time off of the global region" (B2 1098, 1106) e "Cannot modify weather" (B2 1155-1187). Ler o tempo de jogo não é checado (B2 1115). Chunks forçados e metadados de mundo são checados para ler e para mudar (B2 1050-1063, 1209-1227). Ajustes de spawn: só mudar ("Cannot modify server settings", B2 1195, 1201, 1235, 1261, 1269) |
| 40 | `RegionizedServer.ensureGlobalTickThread` lança `IllegalStateException` | Confirmada | A4 1709-1712 |
| 41 | `teleport` síncrono (`teleport0`) lança `UnsupportedOperationException("Must use teleportAsync ...")` | Confirmada | B2 2204-2206 (entidade), B2 3141-3143 (jogador) |
| 42 | `createWorld`/`unloadWorld` lançam sempre | Confirmada | B2 892, 900 |
| 43 | `getTickableTileEntityCount` lança sempre | Confirmada | B2 977 |
| 44 | Resumo item 9: "**placar (scoreboard)**" lança erro sempre | Corrigida | A tabela 6.4 está certa, o resumo exagera. No código só 5 métodos lançam: `registerNewObjective`, `registerNewTeam`, `clearSlot`, `getNewScoreboard`, `setPlayerBoard` (B2 4078, 4086, 4094, 4106, 4114). "Toda a API de placar quebrada" é do README (linha 189), não do código |
| 45 | `MCUtil.ensureMain` fora da thread principal lança | Confirmada | B2 817 (o que `isMainThread` significa no Folia: não verificado) |
| 46 | "Entrada de conversa (`Conversation` API)" lança sempre | Corrigida | O `throw` está no tratamento do pacote de chat, quando o jogador **já está em conversa** (`isConversing()`), A4 13556-13557. Não está na chamada da API de conversa em si |
| 47 | `MinecraftServer` como executor (`wrapRunnable`, `shouldRun`, `pollTask`, `pollTaskInternal`, `doRunTask`) lança | Confirmada | A4 8306, 8326, 8334, 8338, 8346. Faltou: `executeIfPossible` também lança (A4 8750) |
| 48 | `SparksFly.executeMainThreadTasks` lança | Confirmada | B2 258 |
| 49 | `getTPS()`/`getAverageTickTime()` lançam "Not on any region"; "**logo, falham na thread global** e no agendador assíncrono" | **Refutada** (parte da thread global) | A tarefa global é `GlobalTickTickHandle extends TickRegionScheduler.RegionScheduleHandle` (A4 1719), e o agendador marca a tarefa atual também para ela (`TickRegionScheduler.setTickTask(this)`, A4 5529, antes do teste de região nula em 5530). Então, na thread global, `task instanceof RegionScheduleHandle` é verdadeiro (B2 919, 944) e os dois métodos **funcionam**, devolvendo os números do tick global. Falham, sim, no agendador assíncrono e em threads sem tarefa |
| 50 | `getCurrentTick()` lança `IllegalStateException` sem região | Confirmada | B2 964 -> A4 1693-1702. Isso inclui a thread global (que não tem região); na thread de parada devolve 0 |
| 51 | `kickPlayer`/`kick` ficaram livres de checagem | Confirmada | B2 3124-3132 |
| 52 | Permissões com `synchronized`; mapas do `CraftPlayer` concorrentes; `CraftMapView` com `synchronized` | Confirmada | B2 513-680; B2 3070-3072; B2 3889-3898 em diante |
| 53 | Timings e spark embutido desligados | Confirmada | `folia-api/.../0001-Force-disable-timings.patch` (`enabled = false`); B2 267, 276 |
| 54 | Console roda na thread global | Confirmada | A4 10504 sai; A4 1816 entra |
| 55 | `dispatchCommand`: entidade exige thread dona; console/RCON/remoto exigem global; outro remetente lança | Confirmada | B2 873-884. Detalhe: `AsyncCatcher.catchOp` do Spigot continua rodando antes (B2 872) |
| 56 | `CraftServer.dispatchCmdAsync` existe e não está na API | Confirmada | B2 848-865; único arquivo com o nome é B2 (nada em `folia-api`). Detalhe: ele não aceita remetente RCON (cai no `throw`, B2 861-864) |
| 57 | Encadeamento interno de comandos (`TaskChainer`) usa a fila global | Confirmada | A4 7327 |
| 58 | `/paper heap`, `/paper reload`, `/spigot reload` vão para a fila global | Confirmada | B2 403, 417, 4161 |
| 59 | `/tps` trocado pelo comando de saúde; `/restart` e `/tps` do Spigot desregistrados | Confirmada | B2 369; B2 4182, 4191. Faltou: o `/mspt` do Paper também foi desregistrado (B2 378) |
| 60 | 25 comandos vanilla desligados (lista do relatório) | Confirmada | A4 7339-7437, contei os 25: bossbar, clone, compute, data, datapack, debug, dialog, function, item, loot, reload, return, schedule, scoreboard, spectate, spreadplayers, tag, team, teammsg, test, tick, trigger, waypoint, perf, save-all |
| 61 | O FAQ diz que `ride` e `rotate` estão desligados, mas o código mantém | Confirmada | A4 7390-7391 registram os dois; `docs-baixados/faq.txt` cita "Ride" e "Rotate" |
| 62 | `Bukkit#isOwnedByCurrentRegion` usado em 4 lugares | Confirmada | A4 6817, 6850; B2 1278, 2286 |
| 63 | NeoFolia: "This is not a Paper/Bukkit plugin" (README linha 9) e interruptores por recurso (linhas 25-28) | Confirmada | NeoFolia `README.md` linha 9 e 25-28, commit `fa76006`. Detalhe: os quatro interruptores vêm **ligados** por padrão (`true`) |
| 64 | Tipo de exceção de `TickThread.ensureTickThread` não verificado | Não verificável (concordo) | O hunk de `TickThread` em B2 7-48 e 51-219 não mostra o corpo de `ensureTickThread` |
| 65 | `RegionizedServerInitEvent` é chamado antes de agendar qualquer região | Confirmada | A4 1636-1641 |

---

## 2. O que faltou (achado pelo conferente no código)

1. **API nova de TPS por região, que funciona de qualquer thread.** O relatório não usou os patches C4/B7. Eles acrescentam `Bukkit.getRegionTPS(Location)`, `getRegionTPS(Chunk)` e `getRegionTPS(World, x, z)` (C4 linhas 15-50; implementação B7 24-78). A implementação lê a região com `getRegionAtSynchronised` e devolve 5 médias (5s, 15s, 1m, 5m, 15m) ou `null` se ninguém é dono. É a troca do Folia para `getTPS()`, que depende da thread.

2. **Mais eventos antigos desligados, além do chat.**
   - `PlayerPreLoginEvent` (versão síncrona antiga do pré-login): `if (false && ...)` em A4 13724 (`ServerLoginPacketListenerImpl`). O `AsyncPlayerPreLoginEvent` segue.
   - `PlayerSpawnLocationEvent` (Spigot): `if (false && ...)` em A4 13810 (`PrepareSpawnTask`).
   - Isso reforça a seção 9: o Folia não "adapta" evento antigo, ele deixa de disparar.

3. **Bloco de comando não executa nada.** `BaseCommandBlock.performCommand` começa com `if (true) return false;` (A4 17647). O relatório só diz que `dispatchCommand` com remetente "bloco de comando" lançaria erro; na prática o bloco nem chega lá.

4. **`PlayerMoveEvent` alterado por plugin vira teleporte assíncrono.** Se o plugin cancela o movimento ou muda o destino, o servidor agora chama `teleportAsync` em vez do teleporte direto (A4 13414, 13423, 13474, 13483). O efeito deixa de ser imediato.

5. **Algumas APIs esperam (bloqueiam) em vez de lançar.** `CraftWorld.isChunkGenerated` fora da região dona põe uma tarefa na fila de chunk da região e espera com `.join()` (B2 1000-1007). E `RegionizedServer.blockOn` faz o mesmo com a fila global (A4 1677-1687, usado em A4 13658). Então "fora da thread dona = erro" não é regra absoluta.

6. **Thread assíncrona pode mexer em entidade que ainda não entrou no mundo.** Na checagem por entidade, sem região e fora da global, passa `entity.hasNullCallback() && !entity.isRemoved()` (B2 192-197). Jogador nunca passa nesse caso (B2 188-191).

7. **Pacotes de conexões fora do jogo vão para a região global.** `RegionizedServer` tem seu próprio `packetProcessor` drenado no tick global (A4 1617, 1671-1675, 1773-1775). Isso indica que parte do login/configuração roda na global. Quais eventos de login caem ali: **não verificado** (não segui cada chamada).

8. **Outros pontos do servidor que viraram "lança sempre"** e afetam mod/plugin que usa código interno: `ServerChunkCache.getChunkFuture` (A4 11045), o executor da thread principal de chunk (`submit`/`doRunTask`, A4 11176-11225), `ServerPlayer.adjustSpawnLocation` (A4 12543). O caminho antigo de tick do servidor também lança (A4 8483).

9. **Pista para a pergunta do barramento de eventos (não verificada).** As threads de região são `TickThreadRunner extends TickThread` (A4 5433). Se o `AsyncCatcher` e o `isPrimaryThread` do Paper testam "é `TickThread`?", então evento síncrono disparado numa thread de região passa na checagem. O corpo desses métodos está no Paper, fora do clone, então isso fica como pista, não como fato.

10. **Marca "folia" para detecção.** O relatório marcou como só documentação a detecção por `isBrandCompatible(Key.key("papermc","folia"))`. Metade disso está no clone: o Folia muda a marca padrão do servidor para `papermc:folia` / "Folia" (`folia-server/paper-patches/features/0003-Build-changes.patch`, `ServerBuildInfoImpl`, linhas ~85-88). A função `isBrandCompatible` em si continua no Paper (não verificada).

11. **Inconsistência interna do relatório.** O resumo (itens 3, 5, 8 e 9) afirma como fato coisas que o corpo do relatório marca como "não verificado" ou que são mais estreitas no código (ver linhas 9, 25, 39, 44 da tabela). O corpo está mais certo que o resumo.

---

## 3. Não verificado (nem pelo relator, nem pelo conferente)

- Corpo das interfaces `RegionScheduler`, `GlobalRegionScheduler`, `AsyncScheduler`, `EntityScheduler`, `ScheduledTask` e das implementações `FoliaGlobalRegionScheduler`, `FoliaAsyncScheduler`, `FoliaEntityScheduler`, `EntityScheduler`: estão no Paper, fora do clone. Só os ajustes do Folia (B2 764-806) foram lidos.
- Se **todos** os métodos públicos do `BukkitScheduler` passam por `CraftScheduler.handle` (e portanto lançam): o corpo de `runTask*` está no Paper.
- O que `ChunkHolderManager.MAX_TICKET_LEVEL` faz com o chunk (carrega por completo ou só mantém a região): valor no Moonrise/Paper.
- Tipo de exceção de `TickThread.ensureTickThread`, e corpo de `TickThread.isTickThread`, `AsyncCatcher.catchOp`, `CraftServer.isPrimaryThread`, `MCUtil.isMainThread` no Folia: Paper/Moonrise.
- Como a chave `folia-supported` do `paper-plugin.yml` vira o campo booleano: leitor de config do Paper.
- Quem captura a exceção de "plugin não marcado" e o texto final no log: Paper.
- Thread de eventos específicos (`EntityDamageEvent` na região do alvo, `PlayerJoinEvent`, `PlayerQuitEvent`, portal, respawn): só a regra geral do README (linhas 177-179).
- "O modificador async de evento está obsoleto" (README 181-183): nenhum código no clone faz isso.
- Se `queueTickTaskQueue` cria a região quando o local não tem região: o código põe ticket `TASK_QUEUE_TICKET` e chama `processTicketUpdates` (A4 2144-2160), o que aponta para "sim", mas não segui até a criação da região.
- Nada foi compilado nem testado em servidor.
