# Folia: como o mundo é dividido em regiões e como as regiões ticam

- Projeto lido: Folia, https://github.com/papermc/folia
- Commit lido: `eca40f918928e78c2c5f9c944cfb083d7d9851d4` (2026-11-18 21:24:54 -0700, "Use thread-safe random sources"). Clone raso.
- Minecraft alvo do Folia: 26.3 (`Folia/gradle.properties`, linha 2). Paper de base: `paperRef=5184e9c0...` (o Paper não está no clone).
- Método: só leitura dos arquivos `.patch` (código novo = linhas com `+`). Nada compilado. Nenhum servidor ligado.
- Documentação oficial lida como fonte secundária: https://docs.papermc.io/folia/reference/region-logic e https://docs.papermc.io/folia/reference/overview (baixadas em 2026-11-22; texto extraído em `docs-baixados/` nesta pasta). Tudo que vem só delas está marcado "da documentação, não conferido no código".

Abreviações de arquivo usadas abaixo (caminhos dentro de `Folia/`):
- **A4** = `folia-server/minecraft-patches/features/0004-Region-Threading-Base.patch` (20470 linhas)
- **B2** = `folia-server/paper-patches/features/0002-Region-Threading-Base.patch`
- **A7** = `folia-server/minecraft-patches/features/0007-Prevent-block-updates-in-non-loaded-or-non-owned-chu.patch`
- **A11** = `folia-server/minecraft-patches/features/0011-Add-watchdog-thread.patch`
- "linha N" = linha dentro do arquivo `.patch`.

---

## 1. Resumo (10 linhas)

1. O mundo é cortado em **seções** de 2^`gridExponent` × 2^`gridExponent` chunks; o padrão é 4, ou seja 16×16 chunks (B2 linha 437; A4 linha 5811).
2. Uma **região** é um conjunto de seções vizinhas. Cada seção com chunk ganha uma margem de seções vazias em volta (raio 1 seção no padrão) e regiões a até 2 seções dessa margem se juntam (A4 linhas 4066-4068, 11510-11518).
3. O regionizador só reage a **criação e remoção de chunk holder** (o "registro" de um chunk com ticket), não ao chunk ficar pronto (A4 linhas 64 e 72).
4. Quatro estados: transiente, pronta, ticando, morta (A4 linhas 4437-4440). Região que está ticando **não cresce**; o vizinho vira transiente e espera.
5. Junção acontece na hora, se nenhuma das regiões está ticando; senão fica marcada e é feita **no fim do tick** (ou do lote de tarefas) da região que estava ticando. Divisão só acontece no fim do tick (A4 `onRegionRelease`, linha 4258).
6. Cada região tem seus próprios dados: listas de entidades, tile entities, ticks agendados de bloco e fluido, chunks, conexões, fila de tarefas, fila de autosave e o **relógio da região** (tick atual e "redstone time") (A4 linhas 2951-3318, 5910-5941).
7. Na junção, prazos absolutos são somados por um deslocamento (`tick destino − tick origem`); na divisão, cada pedaço herda o mesmo tick (A4 linhas 1495-1504, 6100-6133).
8. As regiões ticam num **pool de threads** com escalonador EDF (padrão) ou de roubo de trabalho; a região global é só mais uma tarefa nesse pool (A4 linhas 5219-5256, 1640-1642).
9. Cada região tem agenda e TPS próprios (médias de 5 s a 15 min). Região atrasada **não recupera** ticks perdidos: tica uma vez e a agenda pula para frente (A4 linhas 5576, 5631-5635, 6196-6198).
10. Exceção não tratada dentro do tick de qualquer região **para o servidor inteiro** (A4 `regionFailed`, linhas 5416-5429).

---

## 2. Termos (primeira vez)

- **chunk**: pedaço de 16×16 blocos do mundo.
- **chunk holder**: objeto que o servidor cria para um chunk que tem pelo menos um ticket. Existe antes do chunk estar carregado por completo.
- **ticket**: pedido de "mantenha este chunk carregado até este nível". Jogadores, portais e tarefas criam tickets.
- **seção (region section)**: bloco quadrado de N×N chunks (N = potência de 2). É a unidade que o regionizador usa.
- **região**: conjunto de seções vizinhas que tica junto, numa thread só por vez.
- **regionizador** (`ThreadedRegionizer`): a classe que diz a qual região cada seção pertence e que junta e divide regiões. Há um por mundo (dimensão).
- **seção morta**: seção vazia (sem chunk) e sem seção não vazia por perto. Continua pertencendo à região até uma limpeza.
- **junção (merge)**: duas regiões viram uma.
- **divisão (split)**: uma região vira várias.
- **região global**: tarefa única que cuida do que não pertence a lugar nenhum (hora do mundo, clima, console, conexões ainda sem mundo).
- **EDF (earliest deadline first)**: escalonador que sempre escolhe a tarefa com o prazo mais próximo.
- **trava (lock)**: mecanismo que impede duas threads de mexer na mesma estrutura ao mesmo tempo.

---

## 3. Pergunta 1: como uma região nasce, cresce, se junta e se divide

### 3.1 Unidade de área

- A unidade é a seção. Tamanho = `1 << sectionChunkShift` chunks de lado (A4, `ThreadedRegionizer` construtor, linhas 3825-3826).
- O valor vem do config global `threaded-regions.grid-exponent`, padrão **4** (B2 linhas 433-438: `public int gridExponent = 4;`). É lido uma vez só, na inicialização, e preso entre 0 e 31 (A4 `TickRegions.init`, linhas 5802-5817). Depois de iniciado, mudar o config só muda o número de threads (linhas 5804-5808).
- Converter chunk em seção é só deslocar bits: `chunkX >> sectionChunkShift` (A4 linhas 3931-3933).
- A seção guarda quais chunks dela existem num mapa de bits (`chunksBitset`) e um contador (A4 linhas 4782-4834).

### 3.2 Os parâmetros (raio de junção, margem vazia, limpeza)

Cada mundo cria seu regionizador no `ServerLevel` (A4 linhas 11507-11519):

```java
this.regioniser = new ThreadedRegionizer<>(
    (int)Math.max(1L, (8L * 16L * 16L) / (1L << (2 * shift))), // minSectionRecalcCount
    (1.0 / 6.0),                                               // maxDeadRegionPercent
    Math.max(1, 8 / (1 << shift)),                             // emptySectionCreateRadius
    1,                                                         // regionSectionMergeRadius
    shift, this, this.tickRegions);
```

Com o padrão `shift = 4` (conta minha a partir dessas fórmulas):

| Parâmetro | Valor | O que faz |
|---|---|---|
| tamanho da seção | 16×16 chunks (256×256 blocos) | unidade de posse |
| `emptySectionCreateRadius` (margem vazia) | max(1, 8/16) = **1 seção** | toda seção com chunk cria seções vazias num raio de 1 em volta |
| `regionSectionMergeRadius` (raio de junção) | **1 seção** | regiões cujas seções ficam a até 1 seção da margem precisam se juntar |
| `minSectionRecalcCount` | 2048/256 = **8 seções** (e nunca menos de 2, linha 3827) | região menor que isso não tenta se dividir por causa de seções mortas |
| `maxDeadRegionPercent` | **1/6** | só tenta dividir quando ≥ 1/6 das seções estão mortas |

- A fórmula da margem mira "8 chunks de folga" (`8 / (1 << shift)`). O README fala o mesmo: "é seguro supor que uma região é dona de cerca de 8 chunks em volta da origem de um evento, mas não é garantido" (`Folia/README.md`, linhas 133-136; da documentação).
- Na busca do `addChunk`, o raio olhado é `createRadius + mergeRadius` = 2 seções (A4 linhas 4067-4068). Conta minha: duas seções com chunk a até 3 seções de distância (distância de Chebyshev, em coordenada de seção) acabam na mesma região, porque a margem de uma (1 seção) fica a até 2 da outra.

### 3.3 Nascimento e crescimento (`addChunk`, A4 linhas 4035-4208)

Caminho rápido: se a seção já existe e não está vazia, só marca o bit do chunk e sai, **sem trava** (linhas 4042-4048). O comentário diz que isso vale porque, para uma mesma seção, nunca há `addChunk`/`removeChunk` em paralelo (linhas 4027-4033, 4040-4041). Quem garante isso não está no clone (ver "não verificado").

Caminho lento (seção nova ou vazia), com a trava de escrita do regionizador (`StampedLock`, linha 3803):
1. Cria a seção, se não existir (linhas 4056-4063).
2. Para cada vizinha no raio 2: se existir, anota a região dela no conjunto `nearbyRegions`; se estiver no raio 1 e não existir, cria seção vazia (linhas 4069-4107).
3. Se nada foi criado, sai (linhas 4109-4112).
4. Sem região por perto: cria região nova, põe as seções, chama `onCreate` e marca **pronta** (`STATE_READY`), chamando `onRegionActive` (linhas 4116-4126, 4188-4194).
5. Com regiões por perto: escolhe a primeira que **não está ticando** como alvo; se todas estão ticando, cria uma região nova (linhas 4129-4146). As seções novas vão para esse alvo (linhas 4148-4150). Isso respeita a regra "região ticando não cresce".
6. As outras regiões do conjunto: se não estão ticando, são mortas e absorvidas na hora (`killAndMergeInto`, linhas 4591-4603); se estão ticando, o alvo é marcado para se juntar a elas depois (`mergeIntoLater`, linhas 4165-4174, 4654-4662).
7. Se o alvo ficou com junção pendente, vira **transiente** e sai da agenda (`onRegionInactive`, linhas 4176-4182).

Trecho que mostra a regra de não crescer região ticando (A4 linhas 4131-4141):

```java
for (final ThreadedRegion<R, S> region : nearbyRegions) {
    if (region.isTicking()) {
        continue;
    }
    firstUnlockedRegion = region;
    ...
}
```

### 3.4 Remoção de chunk (`removeChunk`, A4 linhas 4210-4255)

- Só apaga o bit e atualiza contadores de vizinhança. Se a seção ficou vazia e sem vizinho não vazio, ela é marcada **morta** (linhas 4955-4966, 4924-4931).
- Não muda estado de região e não apaga seção. A documentação diz o mesmo ("It will not update any region state, and nor will it purge region sections"; da documentação, conferido no código).

### 3.5 Quando acontecem junção e divisão, em relação ao tick

- **Junção imediata**: dentro de `addChunk`, na thread que criou o chunk holder, se a região vizinha não está ticando (linhas 4165-4174).
- **Junção adiada**: quando a vizinha está ticando. Ela é feita em `onRegionRelease` (linhas 4258-4287), chamado por `markNotTicking` (linhas 4736-4753).
- `markNotTicking` é chamado no fim de `runTick` (linha 5651) **e** no fim de `runTasks` (linha 5561), que roda tarefas entre ticks. Então a junção adiada e a divisão podem acontecer depois de um tick ou depois de um lote de tarefas.
- **Divisão**: só em `onRegionRelease`. Passos:
  1. Faz as junções pendentes. Se depois disso a região ainda precisa se juntar a outra que está ticando, vira transiente e para (linhas 4266-4287).
  2. Decide se limpa seções mortas: se teve junção, ou se não há seção viva, ou se tem ≥ 8 seções e ≥ 1/6 mortas (linhas 4290-4291).
  3. Se não limpou nada, volta a **pronta** sem tentar dividir (linhas 4317-4324).
  4. Se limpou, faz uma busca em largura pelas seções que sobraram, com raio `max(mergeRadius, createRadius)` = 1, e acha os grupos separados (linhas 4336-4377).
  5. Um grupo só: volta a pronta (linhas 4381-4388). Vários: mata a região velha, cria uma por grupo, chama `split` dos dados e marca cada nova como pronta e ativa (linhas 4390-4430).
- Todo esse trabalho roda com a trava de escrita do regionizador (`markNotTicking` pega a trava, linha 4737).

### 3.6 Estados e quem muda cada um

Estados (A4 linhas 4437-4440): `TRANSIENT = 0`, `READY = 1`, `TICKING = 2`, `DEAD = 3`. Toda região nasce transiente (construtor, linha 4459).

| De → para | Quem muda | Onde |
|---|---|---|
| transiente → pronta | `addChunk` (região criada sem pendência) ou `onRegionRelease` (região criada numa divisão) | linhas 4188-4194, 4423-4430 |
| pronta → ticando | `tryMarkTicking`, chamado pelo escalonador no começo do tick ou do lote de tarefas | linhas 4718-4734, 5521, 5578 |
| ticando → pronta | `markNotTicking` → `onRegionRelease`, sem pendência | linhas 4319, 4383 |
| ticando → transiente | `onRegionRelease`, quando ainda precisa se juntar a uma região que está ticando | linhas 4279-4285 |
| pronta → transiente | `addChunk`, quando a região alvo ganhou junção pendente | linhas 4176-4182 |
| transiente/pronta → morta | `tryKill`, na junção (a região absorvida morre) | linhas 4664-4686 |
| ticando/pronta → morta | `onRegionRelease`, quando todas as seções eram mortas ou na divisão (a velha morre) | linhas 4329-4334, 4397-4399 |

- Ticando nunca morre direto: `tryKill` devolve `false` se está ticando (linhas 4676-4678).
- Os estados ligam e desligam a agenda: `onRegionActive` agenda a região no escalonador; `onRegionInactive` cancela a agenda e copia o "cabo" de agendamento para um novo (A4 `TickRegions`, linhas 5850-5865).

---

## 4. Pergunta 2: o que é dado da região e como é repartido e somado

### 4.1 Onde ficam os dados

`TickRegions.TickRegionData` (A4 linhas 5910-5941) guarda, por região:
- `id` (nunca 0; 0 é a global, linha 5913);
- `regionizedData`: um mapa de "dado por região" genérico (linha 5920);
- `tickHandle`: o cabo de agendamento, com o tick atual e a agenda (linha 5923);
- `taskQueueData`: a fila de tarefas da região (linha 5926);
- `holderManagerRegionData`: fila de autosave e de mudanças de status de chunk (linha 5929);
- `regionStats`: contagem de entidades, jogadores e chunks, legível de qualquer thread (linhas 5881-5908).

`RegionizedData<T>` (A4 linhas 1423-1561) é o mecanismo genérico: "use para dado que precisa ser por região". `get()` devolve o valor da região que está ticando na thread atual e cria sob demanda (linhas 1469-1482). Cada tipo traz uma função `merge` e uma `split` (linhas 1490-1560). O comentário avisa que essas funções rodam com a trava do regionizador e não podem bloquear nem lançar exceção, senão "o servidor fica em estado irrecuperável" (linhas 1508-1516).

### 4.2 `RegionizedWorldData`: os dados do mundo por região

Instância criada por mundo em `Level.worldRegionData` (A4 linhas 17807-17812). Campos (A4 linhas 3198-3318), entre outros:
- conexões de jogadores (`connections`);
- `localPlayers`, `allEntities`, `loadedEntities`, `entityTickList`, `navigatingMobs`, `trackerEntities`;
- `blockEvents`, `blockLevelTicks`, `fluidLevelTicks` (ticks agendados de bloco e fluido);
- `pendingBlockEntityTickers` e `blockEntityTickers` (tile entities que ticam);
- `redstoneTime` (relógio de "redstone" da região);
- `entityTickingChunks`, `tickingChunks`, `chunks`;
- estado de spawn (gato, patrulha, fantasma, cerco de vila), redstone (`wireHandler`, `turbo`), caches de explosão e de caminho, captura de blocos para eventos do Bukkit.

Os campos que antes eram do `Level`/`ServerLevel` foram comentados lá e movidos para cá (ex.: A4 linhas 17795-17798; `ServerLevel` linhas 11365-11372).

### 4.3 Junção (`merge`, A4 linhas 2952-3033)

- Listas de entidades, jogadores, chunks, conexões e tile entities: tudo é **somado** na região destino.
- Prazos absolutos são corrigidos. Para entidades e tile entities: `updateTicks(fromTickOffset, fromRedstoneTimeOffset)` (linhas 2968, 2994, 3001). Para ticks agendados: `blockLevelTicks.merge(..., fromRedstoneTimeOffset)` (linhas 2986-2987). Para tochas de redstone: `offsetTime` (linha 3021).
- O deslocamento é calculado assim: `fromTickOffset = currentTickTo − currentTickFrom` (A4 `TickRegionData.mergeInto`, linha 6117), e o de redstone `into.redstoneTime − from.redstoneTime` (linha 2959). A explicação está no javadoc (linhas 1495-1504).
- Spawners de gato/patrulha/fantasma: fica o maior dos dois prazos (linhas 3026-3028).
- A agenda de tick: fica o **maior** dos dois horários de próximo tick, "para o TPS não passar de 20". O comentário admite: "não há solução boa para o problema do tick, de qualquer jeito vai ficar bagunçado" (linhas 6106-6110; `updateSchedulingToMax`, linhas 6155-6166).
- Filas de tarefas: as da origem entram **na frente** da fila destino, para manter a ordem (linhas 2363-2381).
- Fila de autosave: prazos somados com o deslocamento (A4 linhas 376-387).

### 4.4 Divisão (`split`, A4 linhas 3036-3195)

- Cada objeto vai para a região dona da sua posição: `regionToData.get(chave da seção)`.
- Jogadores, entidades e chunks em tick sempre têm região (o comentário diz que é impossível uma entidade estar fora de um chunk com holder; linhas 3043-3045, 3058-3062, 3143).
- **Block events, tile entities, tochas e holders a transmitir podem ser descartados** se não acharem região: "se descarregou, acho que se perde" (linhas 3088-3092); tile entity sem seção "provavelmente foi removida" (linhas 3118-3122, 3129-3132).
- Relógio: todas as novas herdam o mesmo `redstoneTime` (linhas 3134-3137) e o mesmo tick atual e agenda (`copyDeadlineAndTickCount`, linhas 6037-6044, 6168-6177). O javadoc explica: na divisão não precisa deslocamento porque todos começam no mesmo tick (linhas 1547-1549).
- Cerco de vila: zerado nas novas regiões (linha 3183).
- Ticks agendados: `LevelTicks.split(shift, mapa)` (linhas 3108-3109). A implementação de `LevelTicks.split/merge` está em outro trecho do A4, não li em detalhe.

### 4.5 Relógios: o que é da região e o que é global

- **Tick atual** (`currentTick`): por região, no cabo de agendamento (A4 linhas 5446, 5718-5731). `RegionizedServer.getCurrentTick()` lança exceção se não há região ticando (linhas 1693-1703). Paper/Folia trocou `MinecraftServer.currentTick` por isso em vários lugares (ex.: `ActivationRange`, A4 linhas 886-1048).
- **Redstone time**: por região; `ServerLevel.tickTime()` soma 1 no relógio da região (A4 linhas 11760-11766). Ticks agendados usam esse relógio (linhas 11643-11647).
- **Game time do mundo**: avançado só pela região global, somando `tickCount` (A4 `RegionizedServer.tickTime`, linhas 1981-1985). A global copia para `world.tickData` e cada região copia no começo do seu tick (`updateTickData`, linhas 1944, 3350-3357, 8379-8380). A documentação explica que é para duas leituras no mesmo tick darem o mesmo número (da documentação; a cópia em si foi conferida no código).
- **Agendamentos de funções** (`scheduledEvents`): desligados, com comentário "TODO" (A4 linha 11769).

---

## 5. Pergunta 3: como as regiões são distribuídas nas threads

### 5.1 Quantas threads

- Config `threaded-regions.threads`, padrão `-1` (B2 linha 436).
- Com valor ≤ 0, o Folia calcula (A4 `getTickThreads`, linhas 5786-5800): `núcleos/2`; se isso der ≤ 4, usa **1** thread; senão divide por 4. Exemplo (conta minha): 16 núcleos → 8 → 2 threads. O que `getTotalCores()` conta (núcleo físico ou lógico) está fora do clone.
- O README recomenda pelo menos 16 núcleos e deixar as threads de tick em até 80% dos núcleos depois de reservar rede, I/O de chunk, geração e GC (README linhas 41-75; da documentação).
- As threads se chamam "Folia Region Scheduler Thread #N" e são `TickThreadRunner`, filhas de `TickThread` (A4 linhas 5225-5238, 5433-5442). Elas guardam a região atual num campo, não em `ThreadLocal`, porque é mais rápido (comentário linhas 5431-5432).

### 5.2 Política de escolha

- Dois escalonadores (A4 linhas 5219-5256): `EDF` (padrão, B2 linha 438) e `WORK_STEALING`.
- `EDF` = `EDFSchedulerThreadPool`; `WORK_STEALING` = `StealingScheduledThreadPool`, com opção NUMA, limiar de roubo de 3 ms e fatia de 2 ms (linhas 5264-5290). As duas classes vêm da biblioteca `concurrentutil`, fora do clone: o algoritmo interno não foi conferido.
- Da documentação (não conferido no código): o algoritmo é "como EDF, mas por horário de início"; como o prazo é início + 50 ms, dá no mesmo; enquanto o pool não está lotado, toda região que leva ≤ 50 ms mantém 20 TPS; não fixa região em núcleo.
- Uma região só tica numa thread por vez: `tryMarkTicking` exige estado pronta (A4 linhas 4718-4734). Uma região pode trocar de thread entre ticks (não há afinidade no código lido).
- A **região global** é um `RegionScheduleHandle` como os outros (`GlobalTickTickHandle`, A4 linhas 1719-1798), agendado no mesmo pool (`RegionizedServer.init`, linhas 1635-1643). Não é uma thread dedicada. "Sou a global?" = "a tarefa atual é o cabo global" (linhas 1705-1707).

### 5.3 Ciclo de um tick (`RegionScheduleHandle.runTick`, A4 linhas 5565-5652)

1. Calcula `tickCount = max(1, períodos atrasados)` (linha 5576).
2. Marca ticando (linhas 5578-5590) e registra a região na thread (linhas 5591-5594).
3. Chama `tickRegion(tickCount, início, fimPrevisto)` (linha 5622). Para região normal isso vira `MinecraftServer.tickServer(start, scheduledEnd, 10, region)` (A4 linha 6197).
4. No `finally`: avança a agenda em `tickCount` períodos e marca o próximo início como o maior entre "agora" e o prazo ideal (linhas 5634-5635); grava o tempo do tick nas médias (linha 5643).
5. Marca não ticando (aqui podem acontecer junções e divisões, seção 3.5) (linha 5651).

Dentro do `tickServer` da região (A4 linhas 8356-8417): drena a fila de tarefas da região, processa pacotes dos jogadores locais, roda o agendador de região e os agendadores de entidade dos plugins, e chama `tickChildren` só para o mundo da região (linhas 8618-8638). O evento `ServerTickStartEvent` é disparado **uma vez por região**, com o tick da região (linha 8394).

Entre ticks, se a região tem tarefas pendentes, o escalonador chama `runTasks` (linhas 5512-5562), que executa tarefas de tick, de chunk e pacotes enquanto houver tempo (`runRegionTasks`, linhas 6200-6221).

### 5.4 Região atrasada, TPS próprio e recuperação

- **Agenda própria**: cada região tem seu `tickSchedule` e seu próximo início (A4 linhas 5462, 5476-5477). A documentação dá um exemplo em que a região B mantém 20 TPS enquanto A atrasa (da documentação; a agenda separada foi conferida no código).
- **Sem recuperação**: para região normal, o `tickCount` não é usado; o mundo tica uma vez (A4 linhas 6196-6198). A agenda avança `tickCount` períodos (linha 5634). Ou seja, ticks perdidos são pulados, não repetidos. O comentário diz que isso é para região lenta não ser sempre priorizada sobre as outras (linhas 5631-5633). A semântica exata de `Schedule.getPeriodsAhead/advanceBy` está na biblioteca fora do clone.
- **Exceção**: a região global usa `tickCount` para avançar o game time do mundo (A4 linhas 1754, 1981-1985). Então, se a global atrasa, a hora do mundo dá um salto em vez de ficar para trás.
- **Junção mexe na agenda**: a região resultante fica com o próximo início mais tardio dos dois (seção 4.3).
- **TPS próprio**: sim. Cada cabo tem médias de 5 s, 15 s, 1 min, 5 min e 15 min (A4 linhas 5449-5453, 5685-5713). Há API `getRegionTPS(Location)` / `getRegionTPS(Chunk)` (`folia-api/paper-patches/features/0004-Add-TPS-From-Region.patch` linhas 15-35; implementação em `folia-server/paper-patches/features/0007-Add-TPS-From-Region.patch` linhas 16-53).
- **Tempo restante no tick** (`haveTime`): `scheduledEnd − System.nanoTime() > targetBuffer` (A4 linhas 8383-8385), com `targetBuffer = TimeUnit.MILLISECONDS.toMillis(10L)` = 10 (linha 6197). Observação minha: o valor 10 é comparado com nanossegundos, então a folga real é de 10 ns, não 10 ms. Não sei se é intencional.
- **Região que trava**: o vigia do Folia só escreve no log a cada 5 s a pilha da thread ("has not responded in Xs"); não mata nada (A11 linhas 59-105).
- **Região que lança exceção**: `regionFailed` para o escalonador inteiro e chama `stopServer()` (A4 linhas 5416-5429). Exceção solta numa thread de tick faz o mesmo (linhas 5406-5414). Não há isolamento por região.

---

## 6. Pergunta 4: chunk loading/unloading e tickets em relação às regiões

- **Entrada e saída do regionizador**: `onChunkHolderCreate` chama `regioniser.addChunk`; `onChunkHolderDelete` chama `regioniser.removeChunk` (A4 `BaseChunkSystemHooks`, linhas 76-88). Então a região é dona de todo chunk que tem holder, inclusive os que ainda estão carregando. A documentação chama isso de primeira invariante: "todo chunk holder que existe tem uma, e só uma, região" (da documentação; o gancho foi conferido no código).
- **Listas de chunks carregados/em tick**: viraram listas da região atual: `getCurrentWorldData().addChunk/removeChunk/addTickingChunk/removeTickingChunk` (A4 linhas 95-126). Por isso essas mudanças de status precisam rodar na thread da região dona.
- **Mudança de status "full"**: se o holder é da região atual, entra na fila dela; senão vira tarefa na região dona (`scheduleChunkTaskEventually`) (A4 `ChunkHolderManager.addChangedStatuses`, linhas 617-659).
- **"Fila da thread principal" de chunk**: foi removida. `scheduleChunkTask(Runnable)` sem coordenada e `executeMainThreadTask()` agora lançam `UnsupportedOperationException` (A4 linhas 743-759, 786-792). Toda tarefa de chunk vai para a fila da região dona da coordenada (linhas 797-810). `scheduleChunkTaskEventually` passa pela fila global de chunk primeiro, para evitar travamento entre a trava de ticket e a do agendador (comentário, linhas 812-827).
- **Ticket de fila de tarefas**: quando uma tarefa é posta na fila de uma coordenada, o Folia põe um ticket `task_queue_ticket` no canto da seção, no nível máximo, e tira quando a contagem de tarefas zera (A4 linhas 2031, 2138-2211, 2607-2617, 2480). Isso garante que a seção (e a região) exista até a tarefa rodar. A documentação diz que a fila "cria a região se ela não existir" (da documentação; o ticket foi conferido no código).
- **Expiração de tickets**: cada região só percorre as **suas** seções (`ChunkHolderManager.tick`, A4 linhas 583-600), e exige estar numa região (linhas 586-590).
- **Descarregamento**: cada região só descarrega chunks das suas seções (`retrieveForCurrentRegion`, A4 linhas 288-317, 673-674).
- **Autosave**: fila por região, com prazos no tick da região (A4 linhas 348-387, 475-519). Salvar tudo também percorre só as seções da região atual (linhas 527-551).
- **Carregamento por jogador**: o carregador de chunks por jogador tica só os jogadores locais da região (A4 linhas 271-276).
- **Carregamento síncrono** de dentro de uma região: espera com `managedBlock` (rodando tarefas enquanto espera); fora de região, espera girando com `parkNanos(100 µs)` (A4 linhas 766-782).
- **Região ticando que carrega chunk perto de outra**: as seções novas vão para uma região que não está ticando (ou uma nova), que fica transiente até a ticando terminar e absorver (seção 3.3). A documentação explica que isso evita "briga" entre regiões que ticam por chunks vizinhos (terceira invariante; da documentação, conferido no código).
- **Fronteira**: várias atualizações de bloco checam "sou dono num raio de 25 blocos?" e, se não, simplesmente **não fazem** a atualização (A7, ex.: `Level.updateNeighbourForOutputSignal` linha 20; `CollectingNeighborUpdater` linhas 102-132). O texto do patch admite que isso "adiciona técnicas explícitas de supressão de atualização de bloco, mas é melhor que o servidor cair" (A7 linhas 6-8). Colisão de entidade em chunk de outro dono vira caixa sólida (A4 `CollisionUtil`, linhas 862-872).
- **Mover para chunk não carregado**: o Folia força `preventMovingIntoUnloadedChunks = true` (B2 linha 463).

---

## 7. Pergunta 5: custos e limites que o próprio projeto documenta

Do código (comentários e desligamentos):
- Funções de junção/divisão e callbacks do regionizador rodam com a trava global do regionizador do mundo e **não podem bloquear nem tocar no mundo** (A4 linhas 5014-5016, 5031-5033 e repetido em cada callback; `RegionizedData` linhas 1433-1436, 1508-1516). Por isso a API para plugins não oferece `RegionizedData` (documentação: "o provedor de dados segura travas críticas e é muito suscetível a erro fatal de plugin"; da documentação).
- Toda criação de seção nova, junção e divisão disputam essa mesma trava de escrita por mundo (`acquireWriteLock`, A4 linhas 3903-3910). O caminho rápido do `addChunk` evita a trava só quando a seção já tem chunk.
- Dados descartados na divisão quando não acham dono (block events, tile entities, tochas) (seção 4.4).
- Junção piora a agenda (fica o horário mais tardio) e o comentário admite que "vai ficar bagunçado" (A4 linhas 6106-6110).
- Desligados ou adiados no tick: `pauseWhenEmptySeconds` (`if (false && ...)`, linha 8365), `getFunctions().tick()` ("TODO Purge functions", linha 8593), `scheduledEvents` (linha 11769), `processQueue` do CraftBukkit (linha 8614), `tickRateManager.tick()` (linha 8416).
- Exceção em qualquer região derruba o servidor (seção 5.4).
- O mundo é sempre ticado, mesmo vazio (`isActive = true || ...`, A4 linha 11661).

Da documentação (README e docs.papermc.io, não conferido no código além do indicado):
- Ganha quem tem jogadores espalhados (skyblock, SMP) e muitos jogadores; ideal 16 núcleos; mundo pré-gerado (README linhas 36-49).
- "Espere compatibilidade zero" de plugins; só carrega plugin com `folia-supported: true` (README linhas 76-112).
- "Regiões ticam em paralelo, não concorrentemente. Não compartilham dados." Acesso a dado de outra região deve falhar "com força na origem" (README linhas 115-126).
- API quebrada: portais/respawn/parte do login, todo scoreboard, carregar/descarregar mundo, `Entity#teleport` (README linha 185 em diante).
- O escalonador não é ciente de NUMA nem de núcleo (overview; nota minha: o código atual tem o modo `WORK_STEALING` com NUMA, então essa frase da documentação parece mais velha que o código).

---

## 8. O que isso sugere para um servidor 1.7.10 com mods

**Opinião minha, não fato dos projetos.** Serve para discutir o desenho das Etapas 2 e 3, não para decidir sozinho.

1. **Copiar a forma do regionizador, não o código.** A lógica de seções, margem vazia, estados e "região ticando não cresce" é pequena (cerca de 1400 linhas) e não depende de Minecraft novo. O que depende é o gancho: o Folia conta com um "chunk holder" criado por ticket (Moonrise). Em 1.7.10 não existe isso. Teríamos que achar o ponto equivalente (criação/remoção de chunk no `ChunkProviderServer` do Crucible) e aceitar que a região só conhece chunks carregados de fato. Isso precisa de estudo próprio.
2. **Margem de 8 chunks é pouca para mods.** O Folia confia que nada lê além de ~8 chunks. Redes de energia, tubos e multiblocos de 1.7.10 cruzam distâncias maiores e não checam dono. A margem e a "junção forçada por rede de mod" do nosso desenho (`docs/02-arquitetura.md`, seção (2)) parecem mais importantes que no Folia.
3. **Dado por região exige que o código peça o dado.** O Folia trocou `world.campo` por `getCurrentWorldData().campo` em centenas de pontos (A4 tem 208 arquivos). Mods 1.7.10 leem `world.loadedTileEntityList` direto. Isso confirma a ideia já anotada no nosso desenho de trocar o **objeto** da lista por uma visão por região, e mostra o tamanho do trabalho.
4. **Divisão perde coisas.** O Folia aceita perder block events e tile entities sem dono na divisão. Com mods que guardam estado em tile entity (máquinas, ME), isso seria perda de item. Numa primeira versão, eu evitaria divisão automática e só juntaria, ou dividiria só com a região vazia de jogadores e em ponto seguro.
5. **Relógio por região quebra mods que usam `world.getTotalWorldTime()` como prazo.** O Folia separou "redstone time" (região) de "game time" (global) e corrigiu prazos na junção. Mods 1.7.10 guardam prazos em tempo absoluto sem nos avisar. Corrigir na junção não alcança dado de mod. Sugere manter um relógio único por mundo no início (Degrau B do nosso desenho) e só depois pensar em relógio por região.
6. **Sem recuperação de ticks** é bom para isolar região lenta, mas máquinas de mod que contam ticks vão "andar mais devagar" na região atrasada. Isso precisa ser dito ao admin como mudança de comportamento.
7. **Eventos de tick por região.** O Folia dispara `ServerTickStartEvent` uma vez por região. O equivalente em Forge 1.7.10 (`ServerTickEvent`, `WorldTickEvent`) chamado N vezes em paralelo quebraria quase todo mod. Para nós, esses eventos devem continuar uma vez por tick, em série, na fase global.
8. **Falha de região derruba tudo no Folia.** Com 292 mods, uma exceção rara viraria queda do servidor. Vale desenhar um modo mais brando (ex.: região que falha volta para o tick em série), com cuidado para não esconder corrupção.
9. **Região global no mesmo pool** é simples e evita uma thread parada. Mas, se o nosso desenho mantém uma fase global em série com muito trabalho de mod, talvez ela mereça thread própria. A decidir com medição.
10. **Supressão de atualização de bloco na fronteira** (A7) é uma troca que mods de redstone/automação vão sentir. Para nós, a regra "juntar em vez de suprimir" do desenho atual parece mais segura.

---

## 9. Não verificado

- **Quem garante que `addChunk`/`removeChunk` nunca rodam em paralelo para a mesma seção.** O comentário afirma (A4 linhas 4027-4033); o mais provável é a trava de ticket do Moonrise (`ThreadedTicketLevelPropagator`), mas essa classe está no Paper, fora do clone. Também não conferi a relação exigida entre `gridExponent` e o "shift" da trava de ticket (há só o comentário `sectionShift <= lock shift`, A4 linha 672).
- **Algoritmo interno dos escalonadores** `EDFSchedulerThreadPool` e `StealingScheduledThreadPool` (biblioteca `concurrentutil`, fora do clone): como escolhem entre região e tarefa, como intercalam `runTick` e `runTasks`, o que acontece com o pool lotado.
- **Semântica exata de `Schedule.getPeriodsAhead`, `advanceBy` e `getDeadline`** (`ca.spottedleaf.common.time`, fora do clone). A conclusão "pula ticks, não recupera" vem do uso e do comentário, não da classe.
- **O que `OSNuma.getTotalCores()` conta** (núcleo físico ou lógico): fora do clone.
- **`LevelTicks.split/merge`** (ticks agendados de bloco e fluido): existem no A4, mas não li a implementação.
- **`Entity.updateTicks` e `BlockEntity.updateTicks`**: vi as chamadas, não li o que cada uma corrige.
- **Como uma entidade passa de uma região para outra** andando (sem teleporte): pela invariante, ela fica sempre dentro da própria região, mas não segui o código de movimento.
- **`TickThread` completo**: só o diff (B2 linhas 1-140+); a classe base é do Paper.
- **Comportamento em produção** (tamanho típico de região, frequência de junção/divisão, custo da trava): nada medido; nenhum servidor foi ligado.
- **Efeito real da folga de 10 ns no `haveTime`** (seção 5.4): é leitura minha do código; não testei.
- Números "3 seções de distância" e "2 threads em 16 núcleos" são contas minhas a partir das fórmulas, não estão escritos no projeto.
