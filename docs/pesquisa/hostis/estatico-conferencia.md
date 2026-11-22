# Tarefa 0.8: conferência do mapa "estatico" (estado estático e global em vanilla, Forge e FML)

Conferente cético; não escrevi `estatico.md` e não o editei. Estudo só de leitura: nada foi compilado, iniciado ou alterado.
Todo `arquivo:linha` abaixo foi aberto por mim nesta conferência. "Não verificado em execução" vale para todo cenário de falha
(é leitura de código, não teste).

Convenções (as mesmas do mapa):
- `MC/` = `.../scratchpad/build/Crucible/eclipse/cauldron/src/main/java/` (Minecraft + Forge + FML com os patches do Crucible, nomes MCP).
- `CR/` = `.../scratchpad/build/Crucible/src/main/java/` (Crucible, CraftBukkit, Spigot, Thermos, Aikar).
- **B** = Degrau B (fase paralela sincronizada: 3-4 threads de região ticam TEs e entidades; o resto espera). **C** = Degrau C (regiões soltas).
- `[Mnn]` = `estudo/hostis/mundo.md`, `[Hnn]` = `chunks.md`, `[Knn]` = `bukkit.md`, `[Snn]` = `estatico.md` (o mapa conferido).

## 0. Um fato de ordem de tick que muda severidades (verificado por mim)

`MinecraftServer` chama, por mundo, `worldserver.tick()` e só depois `worldserver.updateEntities()` e o rastreador de entidades
(`MC/net/minecraft/server/MinecraftServer.java:963`, `:986`, `:1010`). Dentro de `tick()` ficam os **ticks agendados**
(`MC/net/minecraft/world/WorldServer.java:279` `tickUpdates`), os **ticks aleatórios** (`WorldServer.java:283` → `:564`) e os
**block events** (`WorldServer.java:304`). Se o Degrau B paraleliza só TEs e entidades, todo código alcançado **apenas** por
`Block.updateTick` agendado/aleatório continua serial no B. Isso rebaixa S09 e confirma as notas "B baixo" de S19/S20/S29.
Duas exceções reais, que o mapa não citou:
1. Geração de mundo chama `updateTick` direto (`MC/net/minecraft/world/gen/feature/WorldGenLiquids.java:83`,
   `WorldGenHellLava.java:91`, `StructureNetherBridgePieces.java:809`) e `WorldServer.java:600-612` roda `updateTick` na hora
   quando `scheduledUpdatesAreImmediate` está ligado. Isso roda na thread de região quando ela gera um chunk (S15).
2. Mods "aceleradores" (que chamam `block.updateTick(...)` de dentro do tick de uma TE) levam essa lógica para a thread de região.
   No código base não há chamador desse tipo (rg `\.updateTick\(` em `MC/net`: só os de cima, `WorldServer` e chamadas `super`);
   quantos mods do pack fazem isso: **não verificado** (precisa de varredura de bytecode).

---

## 1. Tabela de veredictos

Legenda: **confirmado** = estrutura, linhas, quem escreve e modo de falha conferem (pode ter acréscimo menor);
**corrigido** = algo estava errado (linha, quem escreve, modo de falha, severidade ou tratamento incompleto);
**refutado** = não é hostil no tick. Nenhum item foi refutado.

| Item | Veredicto | Nota curta |
|---|---|---|
| S01 limites do bloco no singleton | **corrigido** | Estrutura confirmada (`Block.java:114-119,528-536`; `BlockCauldron.java:53-65`; `BlockStairs.java:29-30,45-49,489-503`; `World.java:1461,1633,1971,2035`). O tratamento "trava nos dois pontos do `World`" não cobre outros leitores no tick: `EntityArrow.java:194-195` (toda flecha cravada, todo tick), `World.java:1459,1629` (`getCollisionBoundingBoxFromPool` fora do `collisionRayTrace`), `World.java:3287,4081`, `ItemSlab.java:67,174`, `ItemSnow.java:36`. Custo e deadlock em §3. |
| S02 `BlockFurnace.field_149934_M` | **corrigido** | Linhas confirmadas (`BlockFurnace.java:27,118-140,180`; `TileEntityFurnace.java:322`). O caso (b) não é duplicação limpa: `breakBlock` zera os stacks da própria TE ao dropar (`:196-206`), e depois a TE volta com stacks de tamanho 0. O alternativo "synchronized em volta de `updateFurnaceBlockState`" não cobre o caso (a). Severidade B: médio (janela curta). |
| S03 canais de rede do FML | **corrigido** | Corrida confirmada (`SimpleNetworkWrapper.java:192-197,206-211`; `FMLEmbeddedChannel.java:45-52`). Errado: o erro de tipo é `RuntimeException` de `validateArgs` (`FMLOutboundHandler.java:98-104,180-186`), não `ClassCastException`; `EntityTrackerEntry.java:569` roda no rastreador (fase global, `MinecraftServer.java:1010`). Tratamento incompleto: mods que mexem no `attr` do canal direto escapam da trava. |
| S04 posts no `EVENT_BUS` vindos da região | confirmado | `ForgeHooks.java:297-320`, `EntityLivingBase.java:847,1900`, `World.java:1831`, `EventBus.java:151-198`, `TimingHandler.java:102,114`. Faltaram posts frequentes: `EntityEvent.EnteringChunk` em toda troca de chunk (`Chunk.java:859`, já em `[H]`), `EntityEvent.CanUpdate` (`World.java:2661-2664`), `ChunkEvent.Load` (`Chunk.java:1050`), `PopulateChunkEvent` (`ChunkProviderGenerate.java:382,453`). Tratamento tem risco de deadlock (§3). |
| S05 internos do EventBus em registro dinâmico | confirmado | `ListenerList.java:9-10,28,119-121,181-229`; `EventBus.java:29-31,100-122`; `ASMEventHandler.java:19,23,73-80,133-135`; `EventSubscriptionTransformer.java:167-210`. |
| S06 `FakePlayerFactory` | confirmado | `FakePlayerFactory.java:19-57` (busca só por nome `:41-47`); `ExplosionEvent.java:36,41,64-69`. Acréscimo: `ItemDye.java:136` (farinha de osso sem jogador usa `getMinecraft`). Ressalva ao tratamento em §3. |
| S07 `Entity.nextEntityID` | confirmado | `Entity.java:93,201,2705`; `ForgeChunkManager.java:908-925`; correção do Thermos `EntityTracker.java:232-237` remove pela id a entrada da outra entidade (`:304`). |
| S08 `BlockRedstoneWire` flag e conjunto | confirmado | `BlockRedstoneWire.java:25-26,74-85,87-94,176-182,349-358,405-408`. Frequência alta: toda máquina que lê redstone passa por `isProvidingWeakPower`. |
| S09 `BlockRedstoneTorch` mapa de queima | **corrigido** | Estrutura confirmada (`BlockRedstoneTorch.java:24,27-58,123-135`). Errado: o mapa só é tocado dentro de `updateTick` (`:163,177`), que é tick agendado (global no B). `onNeighborBlockChange` só agenda (`:204`). Severidade B: baixo, não médio. |
| S10 `EnchantmentHelper` objetos estáticos | **corrigido (menor)** | Confirmado (`EnchantmentHelper.java:24-28,174-222`). Errado: `ItemStack.java:662` está em `getTooltip`, que é `@SideOnly(CLIENT)` (`:556-557`); não é chamador de tick. |
| S11 `ForgeChunkManager` mapas | confirmado | `ForgeChunkManager.java:79-99,355,367,401-402,727-751,777-789`. Nota de tratamento em §3 (não segurar a trava durante o post do evento). |
| S12 `DimensionManager` e listas de mundos | confirmado | `DimensionManager.java:49-60,219-270,488-505`; `MinecraftServer.java:141,159,180,1016-1020,1069-1083`; `ChunkProviderServer.java:572` (descarga de chunk é fase global). Problema de API no tratamento (§3). |
| S13 `ItemStack.currentPlayer`, `BlockSapling.treeType` | confirmado | `ItemStack.java:61,154`; `ForgeHooks.java:533,546,559-560`; `World.java:4870-4875`; `BlockSapling.java:39,62-63,116-204`; `BlockMushroom.java:125,130`. Já é `[K]` também. |
| S14 `OreDictionary` | confirmado | `OreDictionary.java:41-45,268-280,393-396,459-475`. |
| S15 geração de mundo na thread de região | **corrigido (escopo)** | Tudo confere (`IntCache.java:8-73`; `BiomeDecorator.java:71,101-134`; `BlockFalling.java:12,50`; `ChunkProviderServer.java:431,456`; `GameRegistry.java:72,109-142`; `StructureStrongholdPieces.java:36-38`). Faltou: a cadeia `GenLayer` de cada mundo guarda `chunkSeed` mutável (`GenLayer.java:17,119-134`), e o caminho de bioma no tick (`World.java:268`) a usa. A trava "de quem gera ou popula" precisa cobrir também os métodos de `WorldChunkManager`; `ThreadLocal` no `IntCache` sozinho não basta. |
| S16 estado estático na carga de chunk | confirmado | `MinecraftServer.java:209`; `AnvilChunkLoader.java:530-548`; `SideTransformer.java:35,45`; `AsynchronousExecutor.java:220,242-249,338-346`. Acréscimo: a mesma carga chama `EnumHelper.addEnum` (item novo A02). |
| S17 relógios do `MinecraftServer` | confirmado | `MinecraftServer.java:145,159,186,208,683,827-832,1016`; `PathNavigate.java:106,121`; `ChunkProviderServer.java:618-622`. |
| S18 `Profiler` e Timings | confirmado | `Profiler.java:17-21,31-46` (só com `profilingEnabled`, ligado em `MinecraftServer.java:837`); `TimingHandler.java:43,102,114`. |
| S19 vetores de rascunho de folhas e líquido | confirmado | `BlockLeaves.java:25,100-116`; `BlockDynamicLiquid.java:17-19,33,56,185,299-358`. Acréscimo: também alcançados pela geração de mundo (§0) e o mesmo padrão existe no Forge (A03). |
| S20 `BlockFire.failures` | **corrigido (menor)** | Confirmado (`BlockFire.java:244-300`, `pXYZ` sem `equals`). Precisão: o ponto só é gravado quando o `BlockBurnEvent` é cancelado (`:281-283`); o vazamento cresce só com plugin que cancela queima. |
| S21 `RandomPositionGenerator.staticVector` | confirmado | `RandomPositionGenerator.java:10,19-31`; `PathFinder` por chamada (`World.java:4107,4126`). |
| S22 `NextTickListEntry.nextTickEntryID` | confirmado | `NextTickListEntry.java:7,19`; `compareTo` usa a id como desempate (`:55-58`). Sozinho, perder um agendamento exige mesmo mundo, mesmo tick, mesma prioridade e corrida no `++`; o problema maior é o `TreeSet` por mundo (`[M09]`). Também criado na carga de chunk (`AnvilChunkLoader.java:567`). |
| S23 `ItemPotion.effectCache` | confirmado | `ItemPotion.java:32,72-91`. Nota sobre `PotionHelper.field_77925_n` (`PotionHelper.java:28,113-120`, só `ItemPotion.java:190-193` cliente) confere. |
| S24 `CraftingManager.lastCraftView` | confirmado | `CraftingManager.java:24-25,353,369`; `ContainerWorkbench.java:74`; `ContainerPlayer.java:93`. `lastRecipe` (`:24`) não tem uso (rg). |
| S25 registros com escrita preguiçosa | confirmado | `BiomeDictionary.java:421-427`; `ChestGenHooks.java:89-96`; `FluidRegistry.java:258-273`. |
| S26 `ThreadedFileIOBase.queueIO` | confirmado | Linhas reais `ThreadedFileIOBase.java:65-72` (mapa disse 64-71), `waitForFinish` `:74-81`, `++savedIOCounter` só na thread de I/O `:39`. |
| S27 caches do `CauldronHooks` | **corrigido** | Errado "chamado em todo tick de TE": o mapa só é tocado se `skip-tileentity-ticks` / `skip-entity-ticks` estiverem ligados (`CauldronHooks.java:224,270`); o padrão é ligado (`CR/.../TileEntityConfig.java:11`, `SushchestvoConfig.java:11`; config de produção não conferida). O tratamento `computeIfAbsent` não basta: a falha de cache também escreve nos defaults do YAML (`ConfigBase.java:100-114`, já `[M]` para `WorldConfig`). |
| S28 `EntityTNTPrimed.random` | confirmado | `EntityTNTPrimed.java:25,39`; `ThermiteRandom.java:43,120-128`. |
| S29 `BlockEndPortal.field_149948_a` | confirmado | `BlockEndPortal.java:22,94-102`; `EntityDragon.java:699,778`. |

Conferência da checagem que o mapa fez do `arte-previa.md` §4 (itens da área): confere. #6 IntCache "parcialmente errado"
(métodos `synchronized`, `IntCache.java:15,57,75`, mas `resetIntCache` recicla vetores em uso) está certo; acrescento que o
`GenLayer` também tem estado (S15). #7 `MathHelper` não tem `Random` (`MathHelper.java:9-11`): certo. #11 "não há flag
`shutdown`" e "registro não é thread-safe" (`ListenerList.java:119-121,216-229`): certo. #11 `PlayerTickEvent` fica na fase
de rede: certo (`EntityPlayer.java:244,377`; `EntityPlayerMP.onUpdate` não chama `super.onUpdate`; `onUpdateEntity` é chamado
de `NetHandlerPlayServer.java:416,445,488`). #12 FakePlayer "não é por mundo": certo (`FakePlayerFactory.java:41-47`).

---

## 2. Correções por extenso

### S01 (corrigido): tratamento incompleto, caro e com risco de deadlock
- O que confere: os seis limites são campos de instância do singleton do bloco (`MC/net/minecraft/block/Block.java:114-119`),
  `setBlockBounds` escreve os seis (`:528-536`), `getCollisionBoundingBoxFromPool` lê sem chamar nada antes (`:593-596`),
  `collisionRayTrace` chama `setBlockBoundsBasedOnState` e depois lê (`:728-737`). `BlockCauldron.addCollisionBoxesToList` faz
  cinco pares "define limites, lê" (`BlockCauldron.java:53-65`). `BlockStairs` tem dois campos extras de rascunho
  (`BlockStairs.java:29-30`, escritos em `:496,503`, lidos em `:45-49`).
- O que falta: a proposta põe `synchronized (block)` só em `World.java:1971/2035` (`addCollisionBoxesToList`) e
  `:1461/1633` (`collisionRayTrace`). Outros leitores no tick ficam fora:
  - `MC/net/minecraft/entity/projectile/EntityArrow.java:194-195`: `setBlockBoundsBasedOnState` e depois
    `getCollisionBoundingBoxFromPool`, em **todo** tick de toda flecha que está cravada num bloco.
  - `World.java:1459` e `:1629`: `getCollisionBoundingBoxFromPool` é chamado **antes** e fora do `collisionRayTrace`.
  - `World.java:3287` (`func_147469_q`) e `World.java:4081` (`canPlaceEntityOnSide`, usado por máquinas que colocam blocos).
  - `ItemSlab.java:67,174`, `ItemSnow.java:36` (colocação por FakePlayer em máquina), `ItemDye.java:194` (farinha de osso).
- Custo: a trava entra para **cada bloco** dentro da caixa de cada entidade que se move, inclusive pedra, terra e grama. Com 3-4
  threads, os monitores de `Blocks.stone` etc. ficam disputados o tempo todo (uma trava disputada vira trava "pesada" no JVM e
  o cabeçalho do objeto fica pulando entre núcleos). Isso é o laço mais quente do tick de entidades.
- Deadlock: blocos "camuflados" de mods (fachadas, blocos que imitam outro) chamam `addCollisionBoxesToList` do bloco imitado
  dentro do próprio. Thread 1 segura A e pede B; thread 2 segura B e pede A. **Não verificado** no pack, mas o padrão existe.
- Proposta corrigida: no B, travar só para classes de bloco que chamam `setBlockBounds` fora do construtor (lista feita por
  varredura de bytecode, vanilla + mods); blocos de cubo inteiro não precisam de trava. A saída completa continua sendo
  redirecionar os seis campos para um objeto por thread (ASM), medir antes.

### S02 (corrigido): o caso (b) derrama em vez de duplicar; alternativa "synchronized" incompleta
- `breakBlock` só dropa se a flag for `false` (`BlockFurnace.java:180`) e, ao dropar, **diminui** o próprio stack da TE até 0
  (`itemstack.stackSize -= j1`, `:196-206`). No caso (b) a TE é recolocada (`:136-139`) com stacks de tamanho 0, não com os itens.
  Resultado: o conteúdo de uma fornalha que está funcionando cai no chão, e a fornalha fica com "stacks fantasma" de tamanho 0
  (que ainda podem render um item de saída: possível dupe pequeno, **não verificado**). O caso (a) (perda) confere.
- "Static synchronized em volta de `updateFurnaceBlockState`" não protege o caso (a): quem quebra outra fornalha não passa por
  esse método. Só `ThreadLocal` resolve os dois casos.
- Severidade B: médio (a janela é só a duração de um `setBlock`).

### S03 (corrigido): tipo de erro, um local errado e cobertura da trava
- `FMLOutboundHandler.write` lê alvo e argumento do canal (`FMLOutboundHandler.java:258-262`) e chama `validateArgs`, que lança
  `RuntimeException` ("PLAYER target expects a Player arg", `:98-104`; "ALLAROUNDPOINT expects a TargetPoint", `:180-186`;
  "DIMENSION expects an integer", `:146-150`). Não é `ClassCastException`. Se o par trocado "passar" na validação, o pacote vai
  para o destino errado, como o mapa disse.
- `FMLNetworkHandler.getEntitySpawningPacket` é chamado de `EntityTrackerEntry.java:569`, que roda em
  `updateTrackedEntities` depois de `updateEntities` (`MinecraftServer.java:1010`): fase global no B. Não conta como trabalho de região.
- A trava proposta (mixin em `SimpleNetworkWrapper.send*`, `FMLEventChannel.send*`, `generatePacketFrom`) não cobre mods que
  fazem `channel.attr(FML_MESSAGETARGET).set(...)` e `writeAndFlush` no próprio código (padrão de "PacketPipeline" de 1.7).
  Quantos mods fazem isso: **não verificado**. Alternativa que cobre todos: tornar os dois atributos (`FML_MESSAGETARGET`,
  `FML_MESSAGETARGETARGS`) valores por thread dentro de `FMLEmbeddedChannel` (sobrescrever `attr()` para essas duas chaves),
  mais uma trava curta no `writeOutbound/poll` de `generatePacketFrom`. Se `attr()` do Netty 4.0 desta versão pode ser
  sobrescrito: **não verificado**.
- Também lê `ServerConfigurationManager.playerEntityList` (`FMLOutboundHandler.java:130,159,191`): só leitura no B (a lista
  muda na fase de rede); área de rede.

### S09 (corrigido): severidade B
- `func_150111_a` (que mexe no `WeakHashMap` estático) só é chamado de `updateTick` (`BlockRedstoneTorch.java:163,177`), e a
  limpeza também está em `updateTick` (`:125-135`). `onNeighborBlockChange` só agenda (`:196-204`). Tick agendado é serial no B (§0).
- Severidade: **B baixo | C alto** (no C, duas regiões do mesmo mundo dividem a mesma lista).

### S10 (corrigido, menor): um chamador listado é de cliente
- `ItemStack.java:662` fica dentro de `getTooltip`, anotado `@SideOnly(Side.CLIENT)` (`ItemStack.java:556-557`). Os outros
  chamadores conferem (`EntityLivingBase.java:1254`, `EntityMob.java:141,165,168`, `EntityArrow.java:355-356`,
  `EntityPlayer.java:1368,1427,1430`; os de `EntityPlayer` rodam no tick de região quando o "jogador" é um FakePlayer de máquina).
- Nota: espinhos via flecha aplicam dano no atirador (`EntityArrow.java:355`), que pode estar em outra região; isso cruza região
  mesmo com o campo estático corrigido. É assunto do roteamento, não deste item.

### S15 (corrigido, escopo): a trava de geração precisa cobrir consultas de bioma; `GenLayer` tem estado
- A cadeia de camadas de bioma (`GenLayer`) é por mundo e guarda a semente do chunk num campo de instância
  (`MC/net/minecraft/world/gen/layer/GenLayer.java:17` `chunkSeed`; escrita em `initChunkSeed` `:119-122`; lida em `nextInt`
  `:132-134` e `nextLong` `:207-209`). Duas threads pedindo bioma no mesmo mundo trocam sementes uma da outra: biomas errados e
  não determinísticos, gravados para sempre no chunk novo.
- Esse caminho roda **fora** da geração: `World.getBiomeGenForCoordsBody` para coordenada sem chunk carregado chama
  `worldChunkMgr.getBiomeGenAt` (`World.java:268-270`) → `BiomeCache` → `GenLayer` + `IntCache.resetIntCache`
  (`WorldChunkManager.java:73,121,160,189,228`). Mods que consultam bioma longe (scanners, clima, aura) fazem isso no tick.
- Correção do tratamento: a "trava de geração" tem que envolver também os métodos públicos de `WorldChunkManager`
  (por mundo basta para o `GenLayer`, mas o `IntCache` é global, então a trava única global é o mais simples). O
  `ThreadLocal` no `IntCache` (citado em §3.2 do mapa) sozinho não resolve, por causa do `GenLayer`.
- Bom efeito colateral: com uma trava global tomada antes, a trava do `BiomeDecorator` nunca é disputada, e o caminho que
  dorme 100 ms em laço e derruba o servidor depois de 10 s (`BiomeDecorator.java:104-114`) deixa de ser alcançável.

### S20 (corrigido, menor): quando o mapa cresce
- O único `put` de ponto é `lavie.put(lapoint, ...)` dentro do `if (event.isCancelled())` (`BlockFire.java:281-283`). O vazamento
  só cresce quando um plugin cancela `BlockBurnEvent`. Resto confirmado (sem `equals`, `:244-252`; nunca acerta a busca).

### S27 (corrigido): condição de configuração e tratamento insuficiente
- `canTileEntityTick` só lê/escreve `tileEntityCache` dentro de `if (skipTileEntityTicks)` (`CR/net/minecraftforge/cauldron/CauldronHooks.java:270-277`);
  `canSushchestvoTick` sai antes se `skipEntityTicks` estiver desligado (`:224-226`). Os dois vêm **ligados** por padrão
  (`CR/net/minecraftforge/cauldron/configuration/TileEntityConfig.java:11`, `SushchestvoConfig.java:11`); a configuração real
  de produção não foi conferida aqui.
- Na falha de cache, o código chama `world.tileentityConfig.getBoolean/getInt(...)` (`CauldronHooks.java:275`, `:234`), que
  faz `config.addDefault(...)` (`CR/net/minecraftforge/cauldron/configuration/ConfigBase.java:100-114`): escrita num mapa do
  YAML compartilhado. `ConcurrentHashMap.computeIfAbsent` só impede duas criações para a **mesma** classe; duas classes novas ao
  mesmo tempo ainda escrevem juntas no YAML. Tratamento correto: travar o ramo de falha (é raro: uma vez por classe).
- Severidade B: baixo-médio (só no primeiro encontro de cada classe de TE/entidade).

---

## 3. Itens acrescentados (o mapa não tinha)

Método: enumerei por regex todos os campos `static` não-`final` de `MC/net/minecraft`, `MC/net/minecraftforge`, `MC/cpw`
(sem cliente/GUI/launcher: 337 declarações; batem com a lista do mapa, nenhum estático relevante faltou), todos os `static final`
de tipo mutável nos pacotes de tick, e os campos de instância escritos fora do construtor em todas as classes de
`net/minecraft/block`, `net/minecraft/item` e `net/minecraftforge/fluids/Block*`. Segui também os dois caminhos pedidos (abaixo,
§4). As faltas que achei estão em classes "fora da lista de suspeitos" (FML `FMLCommonHandler`, Forge `EnumHelper`,
Forge `BlockFluidClassic`) e na cadeia de bioma.

### A01. `FMLCommonHandler.getEffectiveSide()` decide "servidor" pelo **nome** da thread
- Onde: `MC/cpw/mods/fml/common/FMLCommonHandler.java:156-165`: retorna `SERVER` só se o nome da thread atual for exatamente
  `"Server thread"`; qualquer outra thread recebe `CLIENT`. A thread principal recebe esse nome em `MinecraftServer.java:227,287`.
- Tipo: suposição de identidade de thread (estado global implícito).
- Quem: código de mod dentro de tick de TE/entidade que pergunta "estou no servidor?". O mapa irmão `[K08]` contou 72 de 224
  jars que referenciam `getEffectiveSide` (contagem dele, não reconferida por mim).
- O que quebra: numa thread de região os mods acham que estão no **cliente**: pulam lógica de servidor (não sincronizam, não
  salvam, não aplicam efeito) ou chamam o proxy/classe de cliente e dão `NoClassDefFoundError` no servidor dedicado.
- Severidade: **B alto | C alto**.
- Tratamento: mixin cedo em `getEffectiveSide` que responde `SERVER` para toda thread marcada como "thread de tick do servidor"
  (principal e trabalhadoras de região), por uma flag `ThreadLocal`. Mesmo tratamento de `[K08]`; os dois relatórios devem
  virar um item só na consolidação.

### A02. `EnumHelper.addEnum` chamado da carga de chunk (via `BukkitEnumHelper.addInventoryType`)
- Onde: `MC/net/minecraft/world/chunk/storage/AnvilChunkLoader.java:530-548` chama `BukkitEnumHelper.addInventoryType`
  (`CR/io/github/crucible/util/BukkitEnumHelper.java:55-75`) na primeira TE de cada classe **por carregador** (`checkedTileEntities`
  é campo de instância, um por mundo, `AnvilChunkLoader.java:51`), que chama `EnumHelper.addEnum(InventoryType.class, ...)`
  (`:64`, `:73`). Em `MC/net/minecraftforge/common/util/EnumHelper.java:263-272`: lê `$VALUES`, copia, cria a constante com
  `ordinal = values.size()`, grava o vetor novo por reflexão e limpa o cache do enum. `setup()` preguiçoso sem trava (`:112-133`).
- Tipo: estático mutável (o vetor de constantes de um enum) com leitura-cópia-escrita.
- Quem: carga síncrona de chunk feita por thread de região (TE/entidade tocando chunk não carregado).
- O que quebra: duas cargas ao mesmo tempo com classes novas: uma constante some de `values()`, duas constantes com o mesmo
  `ordinal` (`EnumMap`/`switch` sobre `InventoryType` erram), exceção na reflexão.
- Severidade: **B médio | C médio** (raro depois do aquecimento, mas cada mundo refaz a checagem).
- Tratamento: trava (`synchronized` em `EnumHelper.class`, mixin em `addEnum`). Sobreposição com `[K23]`/`[H22]`.

### A03. `BlockFluidClassic`: vetores de rascunho no singleton (base dos fluidos de mod)
- Onde: `MC/net/minecraftforge/fluids/BlockFluidClassic.java:21-22` (`isOptimalFlowDirection` `boolean[4]`, `flowCost` `int[4]`,
  campos de instância); escritos e o próprio vetor devolvido em `getOptimalFlowDirections` `:216-261`, chamado de `updateTick` `:84`.
- Tipo: rascunho no singleton, igual a `BlockDynamicLiquid` (S19). Todo fluido de mod que estende `BlockFluidClassic` tem o seu.
- Quem: tick agendado do fluido (serial no B, §0), geração de mundo de mods que colocam fluido e chamam update, aceleradores.
- O que quebra: fluido de mod escorrendo para o lado errado.
- Severidade: **B baixo | C médio**. Tratamento: igual a S19 (vetor por thread, ou local).

### A04. Cadeia `GenLayer` por mundo com `chunkSeed` mutável
- Descrita em §2 (S15). Fica registrado como item próprio porque não é estático (é por mundo) e não aparece em `[M31]`/`[H14]`,
  que tratam só do `BiomeCache`. `GenLayer.java:17,119-134,207-209`; alcançado no tick por `World.java:268-270`.
- Severidade: **B médio | C alto** (bioma errado permanente em chunk novo). Tratamento: dentro da trava única de geração/bioma.

### A05. Posts de evento do Forge que o S04 não listou (frequência alta)
- `EntityEvent.EnteringChunk` em toda troca de chunk de toda entidade (`MC/net/minecraft/world/chunk/Chunk.java:859`, já em `[H]`).
- `EntityEvent.CanUpdate` para entidade cujo chunk não tem vizinhos carregados (`MC/net/minecraft/world/World.java:2661-2664`).
- `ChunkEvent.Load` (`Chunk.java:1050`) e `PopulateChunkEvent.Pre/Post` (`ChunkProviderGenerate.java:382,453`) dentro de carga e
  geração síncronas na thread de região.
- Mesma classe de risco e mesmo tratamento do S04; o ponto novo é a ordem de travas (§5).

---

## 4. Os dois caminhos de tick seguidos (o que tocam de estado compartilhado desta área)

**Caminho 1: funil (hopper) empurra item para o vizinho e atualiza vizinhos.**
`TileEntityHopper.updateEntity` (`MC/net/minecraft/tileentity/TileEntityHopper.java:224-236`) → `func_145887_i` (`:238-270`) →
`func_145883_k` (`:310-...`): cria `CraftItemStack`, inventário Bukkit e dispara `InventoryMoveItemEvent` (plugins: trava global
do Bukkit, `[K]`); lê `getSpigotConfig().hopperTransfer` (só leitura). Depois `markDirty` (`TileEntity.java:160-172`) →
`World.func_147453_f` (`World.java:4661-4684`) → `onNeighborChange` dos 6 vizinhos (comparador agenda tick: **S22** contador
estático). Se um vizinho for TNT/fio/tocha via `notifyBlocksOfNeighborChange`: **S08** (fio), **S07**+**S28** (TNT nova).
Estado desta área tocado: S08, S22, S07, S28, S04 (eventos de mod), e nada estático dentro do funil (os métodos estáticos do
funil não têm estado; confere com o mapa). Campos do funil (`TileEntityHopper.java:36-42`) são por instância.

**Caminho 2: entidade cruza borda de chunk e é rastreada por jogador.**
`EntityLivingBase.onUpdate` → `ForgeHooks.onLivingUpdate` (**S04**, `EntityLivingBase.java:1900`) → movimento com colisão
(**S01**, `World.java:1971`) → `World.updateEntityWithOptionalForce`: `getPersistentChunks()` (leitura de `forcedChunks`, mapa
concorrente com valores imutáveis: seguro), `EntityEvent.CanUpdate` (**A05**, `World.java:2663`), `CauldronHooks.canSushchestvoTick`
(**S27**, `World.java:2672`), Spigot `ActivationRange` (`[K]`); troca de chunk → `Chunk.addEntity` → `EnteringChunk` (**A05**,
`Chunk.java:859`). IA: `RandomPositionGenerator` (**S21**), `PathNavigate` lê `MinecraftServer.currentTick` (**S17**). Dano:
`EnchantmentHelper` (**S10**). Portal: `worldServerForDimension` → `initDimension` (**S12**). O rastreamento em si
(`EntityTracker.updateTrackedEntities`, `MinecraftServer.java:1010`) roda depois de `updateEntities` (fase global); mas o
**registro** no rastreador acontece no spawn, dentro da região, e a id duplicada (**S07**) aciona a "correção" do Thermos
(`EntityTracker.java:232-237,304`).

---

## 5. Julgamento dos tratamentos propostos

Seguros e baratos, sem objeção: S07 e S22 (contador atômico), S08 e S10 e S21 e S19/A03 (`ThreadLocal` ou objeto local),
S23 (`ConcurrentHashMap`), S26 (`synchronized queueIO`), S17/S18 (nada no B).

Com problema:
1. **S01, trava por bloco nos pontos do `World`**: incompleta (§2) e cara se aplicada a todo bloco. Restringir a classes que
   mudam limites; cuidado com fachadas (deadlock).
2. **S04, "trava global de listeners de mod"**: (a) **deadlock** se for uma trava separada das outras. Exemplo concreto:
   thread A segura a trava de listeners, o listener de um mod carrega um chunk e pede a trava de geração (S15/S16); thread B
   segura a trava de geração e, dentro de `populate`, posta `PopulateChunkEvent`/`ChunkEvent.Load` (`ChunkProviderGenerate.java:382`,
   `Chunk.java:1050`) e pede a trava de listeners. Mesmo risco com a trava global do Bukkit (um handler de plugin que dispara
   evento Forge, e um listener Forge que dispara evento Bukkit), já apontado em `[K]`. Saída: **uma única trava reentrante
   "serial"** para listeners não liberados, geração/bioma, carga síncrona de chunk e carga de dimensão; ou uma ordem fixa e
   verificada, o que não é possível quando código de mod pode pedir qualquer uma. (b) Custo: `LivingUpdateEvent` sai para toda
   entidade viva todo tick; se **um** listener não liberado existir (provável num pack de 227 mods, **não verificado**), toda
   entidade viva passa pela trava global, e a parte de listeners do tick vira serial. Medir a quantidade e o custo dos listeners
   antes de decidir.
3. **S11, trava nos mutadores do `ForgeChunkManager`**: certo, mas `forceChunk`/`unforceChunk` postam evento no meio
   (`ForgeChunkManager.java:743,784`). A trava deve cobrir só a cópia/troca do mapa (`:745-746`, `:785-788`), nunca o post,
   senão volta o problema de ordem de travas do item 2.
4. **S12, adiar carga de dimensão para o fim da fase**: `worldServerForDimension` hoje sempre devolve um mundo
   (`MinecraftServer.java:1069-1083`). Se a carga for adiada, quem chamou recebe `null` e mods de teleporte quebram com NPE.
   O tratamento precisa cancelar a viagem neste tick (a entidade fica e tenta de novo) em vez de devolver `null`.
5. **S06, um FakePlayer por região**: melhor que por thread (uma thread atende regiões diferentes a cada tick, e TEs guardam o
   FakePlayer num campo). Mas quando uma região se **divide**, TEs das duas metades continuam com o mesmo objeto guardado.
   Precisa de regra na divisão (trocar a chave ou impedir divisão com FakePlayer em uso). Mods com FakePlayer estático próprio
   continuam fora.
6. **S03, trava por canal**: incompleta (§2); preferir atributos por thread no `FMLEmbeddedChannel`.
7. **S13, `ThreadLocal` em `ItemStack.currentPlayer`**: o campo é `public static`; se algum mod o lê direto (além de
   `World.canPlaceMultipart`), um mixin só nas classes base não basta: precisa de um transformador que redirecione o acesso ao
   campo em todas as classes. Se há mod que lê direto: **não verificado**.
8. **S15, trava de geração**: certa, desde que (a) cubra `WorldChunkManager` (§2), (b) seja a mesma trava do item 2, e
   (c) nunca espere a thread global dentro do B (o mapa já disse). O tempo segurando a trava pode ser 10-100 ms por chunk; com
   pré-geração do mapa isso fica raro.
9. **S27, `computeIfAbsent`**: insuficiente (§2); travar o ramo de falha.
10. **S02, alternativa `synchronized`**: insuficiente (§2); usar `ThreadLocal`.
11. **S14, coleções concorrentes**: certo para `nameToId`/`stackToId`; as listas internas devolvidas aos mods
    (`UnmodifiableArrayList` sobre o `ArrayList` interno, `OreDictionary.java:277`) devem continuar as mesmas, e o "procura, se não
    achou cria" de `getOreID` (`:270-279`) tem que ficar inteiro sob a trava.

---

## 6. Lacunas que continuam

- **Varredura de bytecode dos jars (não feita por ninguém)**: mods que copiam os padrões de S02 (flag estática em volta de
  `setBlock`), S08 (flag de "fornece energia"), S01 (blocos que mudam limites fora do construtor), S19/A03 (rascunho em campo do
  bloco); mods que chamam `updateTick` de TE (aceleradores, §0); mods que mexem no `attr` de `FMLEmbeddedChannel` direto (S03);
  mods que leem `ItemStack.currentPlayer` direto (S13); quantos listeners têm `LivingUpdateEvent`, `EnteringChunk`,
  `EntityJoinWorldEvent`, `LivingAttackEvent`; em quais caminhos de tick os 72 jars chamam `getEffectiveSide` (A01).
- **Não verificado**: se `DefaultAttributeMap.attr` do Netty desta versão pode ser sobrescrito (alternativa de S03); se o JVM de
  produção usa trava enviesada (afeta o custo de S01); a configuração real de produção de `skip-tileentity-ticks` (S27).
- **Não conferido por mim**: as linhas de §2 do mapa ("julgados inofensivos") além das amostras abertas
  (`BlockDispenser.eventFired` só `false`, `BlockDispenser.java:33,150`; `ForgeTimeTracker` sem chamador de `trackStart`;
  `MathHelper`; `Block.harvesters`/`captureDrops` `ThreadLocal`, `Block.java:1200-1201,2272-2291`; pools de AABB/Vec3 inexistentes,
  `AxisAlignedBB.java:13-16`, `Vec3.java:10-13`). As amostras conferem.
- **Outras áreas** (não repetido aqui): envio de pacote vanilla por thread de região (`NetworkManager`); registro no
  `EntityTracker` vindo da região; `checkedTileEntities` por mundo (`AnvilChunkLoader.java:51`, área de chunks); estado do
  Bukkit/CraftBukkit.
- **Custo das travas** (S01, S03, S04, S15) sob carga real: só medindo.
