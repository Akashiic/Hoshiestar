# Conferência de `folia-regioes.md` (Folia: regiões e como ticam)

- Conferente: agente diferente do autor do relatório.
- Clone conferido: Folia, commit `eca40f918928e78c2c5f9c944cfb083d7d9851d4` (2026-11-18). Clone raso.
- Método: abri cada patch citado e li o código (linhas com `+`). Nada compilado, nenhum servidor ligado, nada enviado para fora.
- Abreviações (iguais às do relatório): **A4** = `folia-server/minecraft-patches/features/0004-Region-Threading-Base.patch`; **B2** = `folia-server/paper-patches/features/0002-Region-Threading-Base.patch`; **A7** = `.../minecraft-patches/features/0007-Prevent-block-updates-in-non-loaded-or-non-owned-chu.patch`; **A11** = `.../minecraft-patches/features/0011-Add-watchdog-thread.patch`. "linha N" = linha dentro do `.patch`.

## Veredito geral

O relatório é bom e, no geral, fiel ao código. Os números de linha batem quase sempre. Conferi 56 afirmações: **50 confirmadas** (algumas com detalhe a acrescentar), **5 corrigidas**, **1 refutada** (uma conclusão da seção 8, não um fato do Folia). Os erros mais importantes:

1. **Mudar o número de threads depois de ligado não faz nada no modo padrão (EDF).** `setThreads` só age no modo `WORK_STEALING`.
2. **"Região ticando não cresce" vale para seções, não para chunks.** Uma região ticando continua ganhando chunks dentro das seções que já são dela.
3. **A margem real no padrão é de 16 chunks, não 8.** O "8" é só o mínimo que a fórmula garante para qualquer tamanho de seção.
4. **A conclusão "divisão perde item de máquina" (seção 8.4) não se sustenta.** O que a divisão descarta são entradas de listas de chunks que já não têm holder (já descarregados). Os ticks agendados nunca são descartados.

---

## 1. Tabela: afirmação → veredito → evidência

| # | Afirmação do relatório | Veredito | Evidência (Folia `eca40f9`) |
|---|---|---|---|
| 1 | Seção = 2^`gridExponent` chunks de lado; padrão 4 → 16×16 chunks | Confirmada | B2 linhas 433-438 (`public int gridExponent = 4;`); A4 linhas 5811-5814 |
| 2 | `gridExponent` é lido uma vez e preso entre 0 e 31; "depois de iniciado, mudar o config só muda o número de threads" | **Corrigida** | A primeira parte está certa (A4 5802-5817). A segunda está errada no modo padrão: `TickRegions.init` chama `scheduler.setThreads(...)` (A4 5804-5808), mas `TickRegionScheduler.setThreads` só faz algo se o pool for `StealingScheduledThreadPool` (A4 5264-5290). No `EDF` (o padrão, B2 438) o número de threads fica o do construtor `new EDFSchedulerThreadPool(initialThreads, ...)` (A4 5241-5243) e não muda depois. |
| 3 | Parâmetros do regionizador: margem vazia 1 seção, raio de junção 1, `minSectionRecalcCount` 8 (nunca < 2), `maxDeadRegionPercent` 1/6 | Confirmada | A4 11507-11519 (fórmulas); construtor A4 3815-3836 (`Math.max(2, minSectionRecalcCount)`). Conta: 2048 / 2^8 = 8; max(1, 8/16) = 1. |
| 4 | Resumo, item 2: "regiões a até **2 seções dessa margem** se juntam" | **Corrigida** | O raio de junção é **1 seção a partir da margem**. A busca do `addChunk` é de `createRadius + mergeRadius` = 2 seções **a partir da seção que ganhou chunk**, não da margem (A4 4066-4068). A tabela da seção 3.2 do relatório está certa; só o resumo está errado. |
| 5 | Duas seções com chunk a até 3 seções de distância (Chebyshev) acabam na mesma região | Confirmada (por leitura) | `addChunk` olha 2 seções em volta e acha a margem da outra (A4 4069-4107); a divisão faz busca com raio 1 sobre seções vivas + margem (A4 4336-4377). As duas regras dão o mesmo limite de 3 seções. Em chunks, com o padrão: chunks a 33 a 63 chunks de distância (depende da posição dentro da seção). Conta minha. |
| 6 | O regionizador só reage a criação/remoção de chunk holder | Confirmada | Únicas chamadas a `regioniser.addChunk/removeChunk`: A4 linhas 64 e 72 (`BaseChunkSystemHooks.onChunkHolderCreate/Delete`). |
| 7 | Caminho rápido do `addChunk` sem trava quando a seção já tem chunk | Confirmada | A4 4042-4048. Falta dizer que `removeChunk` também tem caminho sem trava quando a seção tem mais de um chunk (A4 4219-4224). |
| 8 | Com vizinhas, o alvo é "a primeira que não está ticando"; se todas ticam, cria uma nova | Confirmada | A4 4129-4146. Detalhe: "primeira" é a ordem de um `ReferenceOpenHashSet`, ou seja, arbitrária. |
| 9 | As outras regiões: não ticando → mortas e absorvidas na hora; ticando → `mergeIntoLater` | Confirmada | A4 4165-4174, `killAndMergeInto` 4591-4603. Falta dizer a direção: quando há região ticando, **ela é a sobrevivente**. No fim do tick ela absorve as transientes que esperavam (`expectingMergeFrom`, A4 4266-4274). |
| 10 | "Região que está ticando não cresce" (resumo item 4 e seção 3.3) | **Corrigida** | Vale para **seções novas**: elas vão para outra região (A4 4131-4150). Mas a região ticando **ganha chunks** em seções que já são dela: pelo caminho rápido (seção não vazia, A4 4042-4048) e quando uma seção vazia dela vira não vazia sem criar seção nova (`newSections.isEmpty()` → `return`, A4 4109-4112). |
| 11 | Quatro estados (`TRANSIENT=0, READY=1, TICKING=2, DEAD=3`); toda região nasce transiente | Confirmada | A4 4437-4440, construtor 4459 |
| 12 | Tabela de transições de estado (quem muda cada uma) | Confirmada | `addChunk` 4188-4194 e 4176-4182; `tryMarkTicking` 4718-4734; `onRegionRelease` 4279-4285, 4317-4334, 4381-4430; `tryKill` 4664-4686 |
| 13 | Região ticando nunca morre direto (`tryKill` devolve `false`) | Confirmada | A4 4676-4678. Observação: dentro de `onRegionRelease` a região ainda está em `TICKING` e vai direto para `DEAD` (A4 4329-4334, 4397-4399); isso o relatório já diz na tabela. |
| 14 | `onRegionActive` agenda; `onRegionInactive` cancela e copia o cabo de agendamento | Confirmada | A4 5850-5865 |
| 15 | Junção adiada e divisão em `onRegionRelease`, chamado por `markNotTicking` no fim de `runTick` **e** de `runTasks` | Confirmada | `markNotTicking` A4 4736-4753; chamadas em 5561 e 5651 |
| 16 | Critério de limpeza de seções mortas (junção, nenhuma seção viva, ou ≥ 8 seções e ≥ 1/6 mortas) | Confirmada | A4 4290-4291 |
| 17 | Busca de divisão com raio `max(mergeRadius, createRadius)` = 1 | Confirmada | A4 4336-4377 |
| 18 | Todo trabalho de junção/divisão roda com a trava de escrita do regionizador | Confirmada | A4 4737. Falta o custo maior: a mesma trava de escrita por mundo é pega **duas vezes a cada tick de cada região** (`tryMarkTicking` 4718-4719 e `markNotTicking` 4737), e também a cada lote de tarefas. Ver "o que faltou". |
| 19 | `removeChunk` só apaga o bit; seção vazia sem vizinho não vazio vira morta; não apaga seção | Confirmada | A4 4210-4255; `markDead` A4 4910-4912, `decrementNonEmptyNeighbours` A4 4924-4931 (seção começa em 4782) |
| 20 | `TickRegionData` guarda id (nunca 0), dados por região, cabo de agendamento, fila de tarefas, dados de holder, estatísticas | Confirmada | A4 5910-5941; `RegionStats` com `AtomicInteger` A4 5881-5908 |
| 21 | `RegionizedData.get()` devolve o dado da região da thread atual e cria sob demanda; callbacks rodam com a trava e não podem lançar exceção | Confirmada | A4 1469-1482 (devolve `null` se não há região), javadoc 1429-1436 e 1508-1516 |
| 22 | Na junção, listas somadas e prazos corrigidos por `updateTicks`, `LevelTicks.merge` e `offsetTime` | Confirmada | A4 2952-3033. Falta: `updateTicks` só corrige campos escolhidos à mão; o `BlockEntity` base não faz nada (ver "o que faltou"). |
| 23 | `fromTickOffset = currentTickTo − currentTickFrom`; deslocamento de redstone = `into.redstoneTime − from.redstoneTime` | Confirmada | A4 6117 e 2959; javadoc 1495-1504 |
| 24 | Na junção, a agenda fica com o horário **mais tardio**; comentário admite que "vai ficar bagunçado" | Confirmada | A4 6106-6110, `updateSchedulingToMax` 6155-6166 |
| 25 | Filas de tarefas da origem entram na frente da fila destino | Confirmada | A4 2363-2381 (`descendingIterator` + `addFirst`) |
| 26 | Fila de autosave: prazos somados com o deslocamento | Confirmada | A4 376-387 (`holder.lastAutoSave += tickOffset`) |
| 27 | Na divisão, block events, tile entities, tochas e holders a transmitir podem ser descartados se não acharem região | Confirmada (fato do código) | A4 3085-3093, 3111-3132, 3163-3172, 3186-3194. Ver item 50 para a conclusão errada tirada disso. |
| 28 | Na divisão, todas as novas herdam o mesmo `redstoneTime`, o mesmo tick e a mesma agenda | Confirmada | A4 3134-3137; `copyDeadlineAndTickCount` 6168-6177, chamado em 6037-6044. (O resumo item 7 cita 6100-6133 para isso; essas linhas são da junção. Citação certa: 6037-6044.) |
| 29 | Tick atual é por região; `RegionizedServer.getCurrentTick()` lança exceção sem região | Confirmada | A4 1693-1703. Exceção: na thread de parada devolve 0 (linha 1697-1699). |
| 30 | `ServerLevel.tickTime()` soma 1 no relógio de redstone da região; ticks agendados usam esse relógio | Confirmada | A4 11760-11766 e 11643-11647 |
| 31 | Game time do mundo só avança na região global, somando `tickCount` | Confirmada | `RegionizedServer.tickTime` A4 1981-1985; chamado de `globalTick(world, tickCount)` A4 1942 |
| 32 | Threads: config `-1`; automático = núcleos/2, se ≤ 4 usa 1, senão divide por 4 (16 núcleos → 2) | Confirmada | B2 436; A4 5786-5800. A conta "16 → 2" é do relatório e está certa. |
| 33 | Threads "Folia Region Scheduler Thread #N", classe `TickThreadRunner`; região atual guardada em campo, não `ThreadLocal` | Confirmada | A4 5225-5238, 5431-5442 |
| 34 | Escalonadores `EDF` (padrão) e `WORK_STEALING` (limiar 3 ms, fatia 2 ms, NUMA opcional) | Confirmada | A4 5219-5256, 5264-5290; padrão em B2 438 |
| 35 | A região global é um cabo de agendamento no mesmo pool, sem thread própria | Confirmada | `GlobalTickTickHandle` A4 1719-1798; `RegionizedServer.init` 1635-1643; `isGlobalTickThread` 1705-1707 |
| 36 | Ciclo do `runTick`: `tickCount = max(1, períodos atrasados)`, marca ticando, tica, avança agenda por `tickCount`, próximo início = max(agora, prazo ideal) | Confirmada | A4 5565-5652 (linhas 5576, 5578-5590, 5622, 5634-5635) |
| 37 | `ServerTickStartEvent` disparado uma vez por região, com o tick da região | Confirmada | A4 8394 |
| 38 | Região normal tica uma vez só, mesmo atrasada (não recupera ticks) | Confirmada | `ConcreteRegionTickHandle.tickRegion` ignora `tickCount` (A4 6196-6198); `currentTick` sobe 1 por tick (`incrementTickCount`, chamado em A4 5595, definido em 5718-5720) |
| 39 | TPS próprio por região (médias 5 s a 15 min) e API `getRegionTPS(Location)` / `getRegionTPS(Chunk)` | Confirmada | A4 5449-5453, 5466-5472; `folia-api/paper-patches/features/0004-Add-TPS-From-Region.patch` 23-35. Há também uma terceira forma, `getRegionTPS(World, chunkX, chunkZ)` (mesmo patch, linhas 47-48 e 90). |
| 40 | `haveTime` compara `10` com nanossegundos: folga real de 10 ns | Confirmada (leitura) | A4 6197 passa `TimeUnit.MILLISECONDS.toMillis(10L)` = 10; A4 8383-8385 compara com `scheduledEnd − System.nanoTime()`. Se é intencional: não verificado. |
| 41 | O vigia do Folia só escreve a pilha no log a cada 5 s; não mata nada | Confirmada, incompleta | `FoliaWatchdogThread`, A11 59-105. Mas o vigia antigo do Spigot continua sendo chamado em todo tick de região (`org.spigotmc.WatchdogThread.tick()`, A4 8359). O que ele faz no Folia (se reinicia/derruba) está no Paper, fora do clone: **não verificado**. |
| 42 | Exceção no tick de qualquer região para o servidor inteiro (`regionFailed` → `stopServer`) | Confirmada, com detalhe | A4 5416-5429 e 5406-5414. É uma **parada ordenada**: `stopServer` só dispara a `RegionShutdownThread` (A4 8193-8206, 8177-8185), que espera o pool até 60 s e salva **região por região** (A4 1243-1305). A região que falhou não é liberada (fica em "ticando", A4 5622-5626), e o estado dela, do jeito que ficou, também é salvo. |
| 43 | Seção 6: ganchos `onChunkHolderCreate/Delete` em "A4 linhas 76-88" | **Corrigida** (só a citação) | As chamadas estão nas linhas 64 e 72 (o próprio resumo cita certo). 76-88 são os ganchos de chunk carregado/em tick. |
| 44 | Listas de chunks carregados/em tick viraram listas da região atual | Confirmada | A4 95-126 (`getCurrentWorldData().addChunk/addTickingChunk/...`) |
| 45 | Mudança de status "full": se o holder é da região atual vai para a fila dela; senão vira tarefa na região dona | Confirmada | `addChangedStatuses`, A4 617-659 |
| 46 | Fila de tarefas "da thread principal" do sistema de chunks removida (`UnsupportedOperationException`) | Confirmada | A4 743-759, 786-792; tarefas por coordenada 797-810; `scheduleChunkTaskEventually` 812-827 |
| 47 | Ticket `task_queue_ticket` no canto da seção, nível máximo, enquanto houver tarefa | Confirmada | A4 2031, 2138-2211; canto inferior esquerdo calculado em 2515-2518. Detalhe: `MAX_TICKET_LEVEL` é o número mais alto, isto é, o ticket **mais fraco** (só garante que o holder exista). O valor da constante fica no Paper: **não verificado**. Mas o comentário da linha 13540 ("so that the region is not lost") é coerente com essa leitura. |
| 48 | Expiração de tickets, descarregamento, autosave e "salvar tudo" percorrem só as seções da região atual | Confirmada | A4 583-600; 288-317 e 673-674; 475-519; 527-551 |
| 49 | Carregamento síncrono: dentro de região usa `managedBlock`; fora, gira com `parkNanos(100 µs)` | Confirmada | A4 766-782 (`parkNanos(100_000L)`) |
| 50 | Seção 8.4: "o Folia aceita perder block events e tile entities sem dono na divisão… com mods isso seria **perda de item**" | **Refutada** (conclusão exagerada) | Só some o que cai numa seção que **deixou de existir**. Seção só é apagada quando está morta: sem chunk e sem vizinha com chunk (A4 4295-4312, 4924-4931), ou seja, os chunks dali **não têm holder**. O comentário diz que a lista de tile entities guarda entradas de tile entities **já descarregados** (marcados removidos) e por isso elas podem ser jogadas fora (A4 3118-3122, 3129-3132). O dado da máquina fica no chunk salvo, não nessa lista. Os ticks agendados de bloco/fluido **nunca** são descartados: `LevelTicks.split` põe cada chunk na região nova e o comentário diz que nunca é nulo (A4 20376-20405). O que pode se perder de fato: block events e tochas de redstone pendentes em chunk já descarregado. O receio do relatório só valeria se o nosso desenho apagasse seções que ainda têm chunk carregado. |
| 51 | Seção 8.2: "Margem de 8 chunks é pouca… O Folia confia que nada lê além de ~8 chunks" | **Corrigida** | O "8" é o **mínimo** que a fórmula `max(1, 8 >> shift)` seções garante para qualquer `gridExponent`. Com o padrão (shift 4), a margem é 1 seção inteira = **16 chunks além da borda da seção** do chunk (A4 11513; posse por seção em `isTickThreadFor`, B2 ~147-230). O README fala em "aproximadamente 8 chunks", "não garantido" (README 133-137). A ideia do relatório (redes de mod cruzam mais que isso) continua válida, mas o número no padrão é 16. |
| 52 | Fronteira: várias atualizações de bloco checam "sou dono num raio de 25 blocos?" e, se não, não fazem nada | Confirmada | A7 (ex.: `Level.updateNeighbourForOutputSignal`, `CollectingNeighborUpdater`); raio em blocos conferido em B2 (`isTickThreadFor(Level, BlockPos, int blockRadius)`). Detalhe: no fio de tripwire o raio é 4 e o laço só para (A7, `TripWireBlock`/`TripWireHookBlock`). |
| 53 | Folia força `preventMovingIntoUnloadedChunks = true` | Confirmada | B2 463 |
| 54 | Desligados no tick: `pauseWhenEmptySeconds`, `getFunctions().tick()`, `scheduledEvents`, `processQueue`, `tickRateManager.tick()`; mundo sempre ticado | Confirmada | A4 8365, 8593, 11769, 8614, 8416; `isActive = true ||` em 11661 |
| 55 | Citações do README (16 núcleos, compatibilidade zero, `folia-supported`, "paralelo, não concorrente") | Confirmada | `Folia/README.md` 40-41, 77-85, 107-127 |
| 56 | Documentação: escalonador "parecido com EDF por horário de início", 20 TPS para região ≤ 50 ms enquanto o pool não lota, "nem NUMA nem núcleo" | Confirmada (da documentação) | `docs-baixados/overview.txt` linhas 96-104 |

Contagem: 56 linhas. Corrigidas: 2, 4, 10, 43 e 51. Refutada: 50. As outras 50 confirmadas; nas linhas 7, 9, 18, 22, 28, 29, 39, 41, 42 e 47 o fato está certo, mas há detalhe ou citação a ajustar (escrito na própria linha).

---

## 2. O que faltou (fatos que o leitor não viu)

1. **A trava de escrita do regionizador é pega a cada tick de cada região.** `tryMarkTicking` (A4 4718-4719) e `markNotTicking` (A4 4737) pegam a trava de escrita do mundo inteiro (um `StampedLock`, A4 3803). Isso acontece no começo e no fim de **todo** tick e de todo lote de tarefas, de toda região do mundo. A seção 7 do relatório só cita criação de seção, junção e divisão como quem disputa essa trava. Para nós: com muitas regiões pequenas, essa trava única por mundo é um ponto de disputa que o Folia aceita.

2. **Região nova começa com relógio zerado, não com o tempo do mundo.** O cabo de agendamento nasce com `currentTick = 0` (A4 5466-5467) e o `RegionizedWorldData` nasce com `redstoneTime = 1` (A4 3240). Só a junção e a divisão copiam ou corrigem relógios. Ou seja: duas regiões vizinhas podem ter relógios de tick muito diferentes, e o "tempo de redstone" que os ticks agendados usam recomeça em 1 numa região nascida do zero. Isso reforça o ponto 8.5 do relatório: em 1.7.10, mod que guarda prazo em `world.getTotalWorldTime()` quebraria com relógio por região.

3. **A correção de prazos na junção é feita à mão, campo por campo.** `BlockEntity.updateTicks` é **vazio** na classe base (A4 18851-18856). Só alguns tipos corrigem: `Entity` (`activatedTick`, `activatedImmunityTick`, `pistonDeltasGameTime`, A4 14685-14696), `LivingEntity` (`lastDamageStamp`, A4 15883-15889), funil (`tickedGameTime`, A4 18929-18935) e pistão (`lastTicked`, A4 19377-19383). Qualquer tile entity que guarde tempo absoluto e não esteja nessa lista **não** é corrigida. Para mods, isso quer dizer que o modelo do Folia não protege dado de mod: confirma a sugestão de relógio único no começo.

4. **Agendar tick de bloco em chunk de outra região lança exceção.** `LevelTicks.schedule` chama `ensureTickThread(..., "Cannot schedule tick for another region!")` (A4 20437). Para mods 1.7.10 que fazem `scheduleBlockUpdate` em blocos vizinhos sem checar, isso viraria exceção (e, pelo item 42, parada do servidor) se copiássemos a regra.

5. **`LevelTicks.split/merge`** (o relatório marcou como "não li"): a junção desloca cada contêiner de ticks por chunk com `offsetTicks(tickOffset)` e soma o deslocamento em `nextTickForContainer`; a divisão manda cada chunk para a região dona e nunca descarta (A4 20357-20405). O deslocamento usado é o do relógio de redstone (`fromRedstoneTimeOffset`, A4 2986-2987), com o comentário "ticklists use game time".

6. **O dreno de tarefas no começo do tick tem limite.** `RegionTaskQueueData.drainTasks` só executa o número de tarefas que havia na fila quando começou (A4 2288-2309). Tarefas que chegam durante o dreno ficam para o próximo dreno. Cada região também drena as tarefas **globais** de chunk do seu mundo antes das suas (`executeChunkTask`, A4 2274-2276).

7. **Como a divisão encaixa no tamanho real.** Com o padrão, uma seção tem 16×16 chunks e a margem é outra seção inteira. Uma região com um único jogador parado já ocupa, no mínimo, 3×3 seções (48×48 chunks, 768×768 blocos), contando a margem. Conta minha a partir de A4 4069-4107.

8. **A parada do servidor é feita por uma thread só, região por região.** `RegionShutdownThread` espera o pool parar (até 60 s), para o sistema de chunks, termina teleportes pendentes e salva os chunks de cada região em sequência (A4 1243-1305). Isso interessa ao nosso "parada limpa": o Folia não salva regiões em paralelo na parada.

9. **Região que falha não é liberada.** Em `runTick`/`runTasks`, se der exceção, o código chama `regionFailed` e devolve `false` sem chamar `markNotTicking` (A4 5622-5627 e 5541-5545). A região fica "ticando" para sempre; as vizinhas que quiserem se juntar a ela ficam transientes esperando. Como o servidor vai parar, isso não importa no Folia, mas importaria num modo "brando" como o sugerido no ponto 8.8.

10. **Quem roda `addChunk`.** O holder é criado durante o processamento de tickets (`processTicketUpdates`), que é chamado de várias threads: regiões (A4 6219, 2307), a global (A4 1946) e tarefas de fila. Então `addChunk` (e a junção imediata) roda na thread de quem processou o ticket, inclusive enquanto outra região tica. O relatório disse "na thread que criou o chunk holder", o que está certo, mas não deixa claro que pode ser qualquer thread de tick.

---

## 3. Não verificado (nem pelo relatório, nem por mim)

- **O que o vigia do Spigot (`org.spigotmc.WatchdogThread`) faz no Folia.** Ele é chamado em todo tick de região (A4 8359), mas a classe está no Paper, fora do clone. Não sei se ele ainda pode reiniciar/derrubar o servidor quando uma região trava.
- **Valor e significado exato de `ChunkHolderManager.MAX_TICKET_LEVEL`** (fica no Paper/Moonrise). Minha leitura "ticket mais fraco, só mantém o holder" vem do nome, do uso e do comentário da linha 13540, não da constante.
- **Quando o pool chama `runTasks` entre ticks e como escolhe a próxima região.** O gancho existe (A4 5512-5562, `hasIntermediateTasks` 6224-6227), mas o laço do pool está em `concurrentutil` (`EDFSchedulerThreadPool`, `StealingScheduledThreadPool`), fora do clone. O mesmo vale para "região pode trocar de thread entre ticks": no `WORK_STEALING` o nome sugere que sim; no `EDF` não conferi.
- **Semântica de `Schedule.getPeriodsAhead/advanceBy/getDeadline`** (`ca.spottedleaf.common.time`, fora do clone). A conclusão "não recupera ticks" se apoia no uso, no comentário (A4 5631-5633) e no fato de a região tocar o mundo uma vez só por chamada (A4 6196-6198), o que é suficiente para o comportamento visível.
- **Quem garante que `addChunk`/`removeChunk` nunca rodam em paralelo para a mesma seção** (o relatório já marcou; provavelmente a trava de ticket do Moonrise, fora do clone).
- **O que `OSNuma.getTotalCores()` conta** (núcleo físico ou lógico): fora do clone. O README pede "16 núcleos (não threads)" (README 41).
- **Como uma entidade muda de região andando** (o relatório já marcou). Não segui o código de movimento.
- **Efeito prático da folga de 10 ns no `haveTime`**: é leitura do código; nada medido.
- **Comportamento em produção** (tamanho típico de região, frequência de junção/divisão, custo da trava): nada medido; nenhum servidor ligado.
