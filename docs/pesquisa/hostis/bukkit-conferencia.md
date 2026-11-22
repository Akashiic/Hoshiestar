# Conferência do relatório "bukkit" (tarefa 0.8, camada Bukkit / CraftBukkit / Spigot / Crucible)

Conferente: agente diferente do autor. Só leitura: nada foi rodado, compilado ou editado fora deste arquivo.
O relatório conferido (`estudo/hostis/bukkit.md`) não foi alterado.
Todo `arquivo:linha` abaixo foi aberto nesta conferência. Toda falha descrita vem da leitura do código, **não foi
verificada em execução**.

## 0. Convenções e método

- `CR/` = `scratchpad/build/Crucible/src/main/java/`; `MC/` = `scratchpad/build/Crucible/eclipse/cauldron/src/main/java/`
  (mesmos atalhos do relatório). Atalhos de classe iguais aos do relatório: `SPM`, `CS`, `CW`, `CEF`, `SCHED`, `CH`, `W`,
  `MS`, `E`, `FH`. Itens do relatório: `[Knn]`. Itens dos estudos irmãos: `[Mnn]` (mundo), `[Hnn]` (chunks), `[Snn]`
  (estático), `[Ann]` (conferência do mundo).
- **B** = fase paralela sincronizada; **C** = regiões independentes.
- Termos: *monitor* = a trava embutida de todo objeto Java (`synchronized`); *thread-local* = variável com um valor
  separado por thread; *deadlock* = duas threads paradas para sempre, cada uma esperando a outra.
- Método:
  1. Abri cada `arquivo:linha` citado nos itens K01 a K28, na tabela do K02, na tabela do K27, numa amostra da "segunda
     passada" (§2 do relatório) e na conferência da arte-prévia (§3.2).
  2. Recontei os números de jars na cópia de teste (`unzip -p jar '*.class' | grep -a`): `isPrimaryThread` 16/49,
     `callSyncMethod` 3/49, `kickPlayer` 6/49, `getNewScoreboard` 1/49, `EntityDamageByBlockEvent` 2/49,
     `CrucibleEventBus` 1/49, `getEffectiveSide` 72/224 mods; `BlockPhysicsEvent` em AncientGates, ChestShop, Skript,
     worldguard-6.2; `InventoryMoveItemEvent` em ChestShop e worldguard-6.2. **Todos batem.** (É presença da string, não
     prova de que o plugin ouve o evento.)
  3. Completude por um método diferente do grep por palavra: (a) listei campos estáticos não finais de todo `CR/` e os
     estáticos acrescentados pelos patches (`grep '^+.*static'` em `patches/`), e as coleções/flags de `CraftServer`,
     `CraftWorld`, `CraftChunk`, `CraftPlayer`/`CraftHumanEntity`, `CraftEntity`, `CraftEventFactory`,
     `SimplePluginManager`, `CraftScheduler`, `CraftScoreboardManager`, `CraftMapView`, `TimingHandler`/`TimingsManager`;
     (b) segui os dois caminhos de tick pedidos olhando só o que eles tocam **na camada Bukkit** (§3.1). Os caminhos
     vanilla/Forge já foram seguidos em `mundo-conferencia.md` §3 e não repito.

---

## 1. Tabela de veredictos

| Item | Veredicto | Nota |
|---|---|---|
| K01 `callEvent` com um monitor global | **confirmado** | `SPM:482-496` (síncrono `synchronized(this)` `:492-494`; assíncrono lança `:484-489`). `HandlerList` seguro (`HandlerList.java:18,107,175`). Contagens de ouvintes conferidas. A regra proposta ("monitor de plugin é a trava mais externa") está mal formulada: ver §4. |
| K02 API Bukkit alcançando outra região | **confirmado** | Amostra da tabela conferida: `CW:114-117,151-154,700-716` (itera `loadedEntityList` e chama `getBukkitEntity()` em cada uma), `CraftBlock.java:289,367-379`, `CraftBlockState.java:33-50,188`, `CraftInventory.java:41-56`, `CraftEntity.java:579-596,606-614`, `CraftPlayer.java:531-540`. Tratamento com ressalvas (§4). |
| K03 `CraftEventFactory.blockDamage/entityDamage` | **corrigido** | Estáticos e leitores confirmados (`CEF:64-65,402-403,465-468,502-505`; escritores `E:1069-1071,2282-2284`, `Explosion.java:162,166`, `EntityEnderPearl.java:81,83`, `EntityFallingBlock.java:207,209`, `EntityDragon.java:374,376`). Errado: a criação do `CraftBlock` em `E:1069` não é "só custo". Ela passa por `ChunkProviderServer.loadChunk`, que **sempre** grava `chunksToUnload` e o cache MRU do mapa de chunks: uma escrita compartilhada por bloco tocado, por entidade em movimento, por tick ([A02], [H01], [H05]). Precisão: o NPE só existe no caminho de entidade (`CEF:502-504`); o de bloco copia para local e aceita `null` (`CEF:465-468`). |
| K04 `ItemStack.currentPlayer` | **confirmado** | `ItemStack.java:61`; `FH:533,546`; leitor `W:4869-4885`. |
| K05 `BlockSapling.treeType` | **confirmado** | `BlockSapling.java:39`, leitura/limpeza `:62-63`, `FH:559-560`. O tique aleatório roda em `WorldServer.tick`, mas o desenho (§3 Degrau B) põe "blocos" na fase paralela, então a severidade B segue essa decisão. |
| K06 captura de colocação / árvore / drops | **confirmado** | `FH:516-552` (flags e `finally`), `FH:555-585` (`StructureGrowEvent` `:572`); `WorldChangeHolder.java:30-39` (lança se já há captura); `CW:495-553` (captura `:546-552`), `CW:438-493` sem captura com `CraftWorld.rand` estático (`CW:46`). |
| K07 mapas de assinatura de permissão | **confirmado** | `SPM:50-51`, métodos sem trava `:653-720`. Caminho conferido: `FakePlayer extends EntityPlayerMP` (`FakePlayer.java:18`) → ramo `new CraftPlayer` (`CraftEntity.java:51-52`) → `PermissibleBase` no campo (`CraftHumanEntity.java:22`) → `recalculatePermissions()` no construtor (`PermissibleBase.java:29,148-160`). Acontece em `CEF:119` e `CEF:975`, antes do `callEvent`. Detalhe que reforça: um `WeakHashMap` se altera até na leitura (limpa entradas mortas), então a trava tem de cobrir os `get*` também (o relatório já inclui). |
| K08 `getEffectiveSide()` pelo nome da thread | **confirmado** | `FMLCommonHandler.java:155-164`; nome `MS:227,287`; 72/224 recontado. Dentro do servidor só é usado em `FMLCommonHandler.java:388` (carga de dados do mundo), `StartupQuery.java:124`, `GameData.java:439`: o risco é todo de código de mod. Ressalva no tratamento (§4). |
| K09 `isPrimaryThread()` / `primaryThread` fixos | **corrigido** | (1) `UnsafeTimingHandler` (`co/aikar/timings/UnsafeTimingHandler.java:36-37`) **nunca é instanciado** (grep por `new UnsafeTimingHandler`/`UnsafeTimingHandler::new` em `CR/` e `MC/`: nada; `TimingsManager.java:44-46` cria sempre `TimingHandler::new`). Não lança nada hoje. (2) `kickPlayer` (`CraftPlayer.java:320`) e `getNewScoreboard` (`CraftScoreboardManager.java:28`) comparam com `MinecraftServer.getServer().primaryThread` **diretamente**, não chamam `isPrimaryThread()`; mudar `isPrimaryThread()` não muda os dois. (3) Faltou um local: `Entity.setRotation` chama `kickPlayer("Nope")` para yaw/pitch infinito de um `EntityPlayerMP` (`E:342-374`). Resto confirmado (`CS:1599-1601`, `W:4943`, `WatchdogThread.java:164,199`, `TimingHandler.java:102,114`, `TimedEventExecutor.java:75`). |
| K10 `CraftScheduler` | **corrigido** | Estruturas e fluxo confirmados (`SCHED:50-83,159-164,166-207,343-388,390-403`; `CraftFuture.java:35-74`). Severidade exagerada como está escrita: chamar `callSyncMethod(...).get()` da thread principal **já trava hoje** (o batimento nunca roda enquanto ela espera), então nenhum handler síncrono faz isso sem antes testar `!isPrimaryThread()`. O deadlock no B só acontece porque a thread de região responde "não sou a principal". Com o tratamento do K09 cai para quase zero. O tratamento "rodar o callable inline" é inseguro (§4). |
| K11 command blocks | **corrigido** | (1) A corrida em `vanillaConsoleSender` não existe no B: o campo só é lido quando o remetente é `ConsoleCommandSender` (`CraftSimpleCommandMap.java:47-48`); o remetente do command block é outro (`CommandBlockLogic.java:35,220`); a única escrita possível na fase paralela grava sempre o mesmo valor `this.console` (`CS:713-715`); a outra escrita (`CS:691`) é da fase global. (2) Command blocks só rodam com `enable-command-block` ligado (`CommandBlockLogic.java:107`), e a cópia de teste tem `enable-command-block=false` (`server-test3/server.properties:7`); produção não conferida. (3) O caminho real de risco ficou de fora: mods que rodam comandos de TE pelo `CommandHandler` vanilla, sem evento Bukkit e sem checagem de permissão (item N01). Severidade B dos command blocks: **baixo** se produção também estiver desligada. |
| K12 ouvintes de plugin no barramento Forge | **confirmado** | `CrucibleEventBus.java:37-99` (registro `:91-96`); `EventBus.java:151-175`. 1/49 recontado. Ressalva de ordem de travas no tratamento (§4). |
| K13 `CraftServer.worlds` e carga de mundo | **confirmado** | `CS:121`, `addWorld` `CS:1031-1038` chamado em `W:493`; `getWorlds` `CS:665-667`; `unloadWorld` remove em `CS:1005`; `craftWorldLoading` `CH:35`, escrito `CS:933,961`, lido `DimensionManager.java:282`; enum `DimensionManager.java:594-603` → `BukkitEnumHelper.java:27-30`. Acréscimo pequeno: `Environment.registerEnvironment` grava o `HashMap` estático `lookup` (`MC/org/bukkit/World.java:1334`). |
| K14 `CauldronHooks.tileEntityCache/sushchestvoCache` | **confirmado** | `CH:38-39`, `CH:220-303`, chamados em `W:2542,2672`. Precisão: o mapa só é tocado com `skip-tileentity-ticks`/`skip-entity-ticks` ligados (`CH:224,270`); o padrão é `true` (`TileEntityConfig.java:11`, `SushchestvoConfig.java:11`) e a cópia de teste está `true` (`tileentities.yml:16`, `entities.yml:14`). Os mesmos métodos leem `chunksToUnload.contains` (`CH:255,292`, [H05]). |
| K15 `recentWarnings` | **confirmado** | `CH:41,193-211`, `W:1982`, opção `CrucibleConfigs.java:91` = false. |
| K16 `ActivationRange` | **confirmado** | `ActivationRange.java:40-43,114-121,129-163,170-197`; chamada `W:2416`; `else return true` em `:76-80` confirmado. |
| K17 `MinecraftServer.currentTick` | **corrigido (menor)** | Não é relógio de parede: é um contador de ticks do servidor, somado uma vez por volta do laço (`MS:683`), só a partir de um valor inicial tirado da hora (`MS:186`). A consequência para o Degrau A (fornalha, alambique, despawn de item e conversão de zumbi "alcançam" os ticks pulados, `TileEntityFurnace.java:240-241` etc.) continua válida. |
| K18 Timings v2 fora da thread principal | **corrigido (menor)** | Mesmo erro do K09 sobre `UnsafeTimingHandler` (código morto). O resto confere: `TimingHandler.java:42-43,102,114`; criação via `ConcurrentHashMap` (`LoadingMap.java:269-274`) e `synchronizedList` (`TimingIdentifier.java:97`); `TimingHistory` (`FullServerTickHandler.java:74-76`, `TimingHistory.java:104-130`). Faltou o custo do item N06. |
| K19 `TickLimiter` | **confirmado** | `TickLimiter.java:3-26`; campos `W:285-286`, criação `W:4894-4897`, uso `W:2422,2518`. |
| K20 `WatchdogThread` | **confirmado** | `WatchdogThread.java:24-31,48-50,114-121,164-171,188-200`. Precisão: o despejo do aviso só sai com `cauldron_logging_dumpThreadsOnWarn` (`CrucibleConfigs.java:88`, padrão false). |
| K21 iluminação assíncrona | **confirmado** | `W:4920-4952`, `SpigotWorldConfig.java:276`. |
| K22 criação preguiçosa do `CraftEntity` | **confirmado** | `E:1969-1977`; `CraftEntity.java:53-58`; `FakePlayerFactory.java:19,36-57`. Mais frequente do que "raro" sugere: `CW:700-716` (`getEntities`) embrulha **toda** entidade do mundo a partir da thread de quem chamou. Severidade baixa mantida (perde só `lastDamageEvent`, `CraftEntity.java:37`; metadados ficam fora do objeto). |
| K23 enums Bukkit em tempo de execução | **confirmado** | `BukkitEnumHelper.java:22-75`; `AnvilChunkLoader.java:547`. |
| K24 consultas de jogador no servidor | **corrigido** | O "não verificado" do relatório se resolve, e é pior: com o nome fora do cache, `getOfflinePlayer(name)` (`CS:1303`) faz uma consulta de rede que bloqueia (`PlayerProfileCache.java:179` → `:93` `findProfilesByNames`), e **toda** chamada, com ou sem acerto no cache, regrava `usercache.json` no disco (`:187` → `func_152658_c` `:273-296`). Os dois mapas do cache são `HashMap` sem trava (`:44-45`). Outros caminhos: `CraftSkull.java:154`, `CraftProfileBanList.java:27,44,79,91`, e colocação de crânio com nome (`ItemSkull.java:114` → `TileEntitySkull.java:96,104`, que ainda chama o servidor de sessão). Severidade B **médio** mantida; o tratamento "só documentar" não basta (§4). |
| K25 `JavaPluginLoader.classes` | **confirmado** | `JavaPluginLoader.java:64,218-250`; `PluginClassLoader.java:63`. Acréscimo: `setClass` também grava o `HashMap` estático `ConfigurationSerialization.aliases` (item N05). |
| K26 `processQueue` / `Waitable` | **confirmado** | `MS:188,909-914`; produtores `MS:1834`, `NetHandlerPlayServer.java:1086,1185,1283`, `ThreadPlayerLookupUUID.java:95`, `ConsoleCommandCompleter.java:25`. Nenhum na fase de região. |
| K27 recursos do Crucible.yml / bukkit.yml / spigot.yml | **confirmado** | Amostra: `CrucibleConfigs.java:19,22,25,55,58,91,144,154,167,183,198,210,228`; `EntityPlayerMP.java:347`; `BlockRedstoneDiode.java:50-54`; `BlockRedstoneTorch.java:122-126`; `BlockGrass.java:81`; `SpigotWorldConfig.java:276`. Nota: `loadChunkOnRequest` é `true` por padrão (`CrucibleConfigs.java:19`), o que faz `provideChunk` carregar chunks de dentro da região ([H07]). |
| K28 scoreboard (lado Bukkit) | **confirmado** | `CraftScoreboardManager.java:14-15,27-33`; estado em [M29]. |
| §2 "segunda passada" (lista de inofensivos) | **corrigido (1 linha)** | `CraftTravelAgent.DEFAULT` **é** atribuído: no construtor, para a dimensão 0 (`CraftTravelAgent.java:15-19`, criado em `WorldServer.java:181`). Não é sempre `null`. Ver item N04. Amostra conferida e certa: `SimpleCommandMap.java:22,138,206`; `MetadataStoreBase.java:33,56,74,90,113`; `HandlerList.java:18,107`; `AntiXray` nunca chamado (só `SpigotWorldConfig.java:222`); `BlockDispenser.eventFired` só recebe `false` (`BlockDispenser.java:33,150`); `CraftPotionBrewer` só via `CraftThrownPotion.java:23` e `Potion.getBrewer()`. |
| §3.2 conferência da arte-prévia | **confirmado** | Itens 8-12 e o parágrafo de fronteiras conferem com o código citado. `AsyncCatcher` ausente: grep sem resultado em `CR/`, `MC/` e `patches/`. `org/bukkit/craftbukkit/v1_7_R4/chunkio` não existe. |

Contagem dos 28 itens: 21 confirmados, 7 corrigidos (K03, K09, K10, K11, K17, K18, K24), 0 refutados.
Três afirmações internas foram refutadas sem derrubar o item: "`UnsafeTimingHandler` lança" (K09/K18), "corrida em
`vanillaConsoleSender`" (K11) e "`CraftTravelAgent.DEFAULT` nunca é atribuído" (§2).

---

## 2. Correções por extenso

### K03: a escrita escondida pesa mais que o estático

O relatório trata o `CraftBlock` criado a cada colisão como custo. Não é só custo. `E:1069` chama
`this.worldObj.getWorld().getBlockAt(k1,l1,i2)`, que vai para `CW:114-116` → `getChunkAt` `CW:151-153` →
`ChunkProviderServer.loadChunk(x,z)` → `loadChunk(x,z,null)` (`ChunkProviderServer.java:169-177`), que começa com
`this.chunksToUnload.remove(par1, par2)` (`:176`) e lê `loadedChunkHashMap_KC.rawThermos().get(...)` (`:177`), o
`ChunkBlockHashMap` cujo `get` grava o cache MRU ([H01]). Isso roda para **cada bloco** dentro da caixa de **cada entidade
em movimento**, a cada tick, mesmo quando ninguém leva dano (`E:1045-1075`). É a escrita compartilhada de maior
frequência da camada, maior que a de `BlockPhysicsEvent` que [A02] achou. A remoção no `LongHashSet` só escreve quando a
chave está lá (chunk na fila de descarga), mas o cache MRU é escrito sempre.

Consequência para o tratamento: guardar só as coordenadas (ou o par mundo+coordenadas) num contexto da thread e criar o
`CraftBlock` apenas dentro de `handleEntityDamageEvent`, quando o dano de fato acontece. Isso deixa de ser otimização e
passa a ser parte da correção de segurança. O mesmo vale para os outros `getBlockAt` internos (item N02).

Precisão sobre o NPE: só no caminho de entidade (`CEF:502` testa, `:504` desreferencia). O de bloco (`CEF:465-468`)
copia para uma local; se outra thread zerar no meio, sai um `EntityDamageByBlockEvent` com bloco `null`, sem exceção.

### K09: o tratamento não alcança dois dos três pontos que lançam

1. `UnsafeTimingHandler` é código morto: não há instância. Retirar dos pontos que "lançam".
2. `kickPlayer` e `getNewScoreboard` não usam `isPrimaryThread()`; comparam `Thread.currentThread()` com
   `MinecraftServer.getServer().primaryThread` (`CraftPlayer.java:320`, `CraftScoreboardManager.java:28`). Fazer
   `isPrimaryThread()` responder "sim" na thread de região não muda nada para eles: continuam lançando. Se a decisão for
   adiá-los para a junção, isso é um patch próprio nesses dois métodos, e o texto do tratamento deve dizer.
3. Local novo: `Entity.setRotation` (`E:342-374`) chama `kickPlayer("Nope")` quando um `EntityPlayerMP` recebe yaw ou
   pitch infinito. Se isso acontecer na região (teleporte de mod ou plugin com rotação inválida, movimento de jogador se o
   tick do jogador for para a região), a chamada lança `IllegalStateException("Asynchronous player kick!")` dentro do
   tick. Raro, mas é exceção em trabalho de região. Entra na mesma lista de "adiar para a junção".

### K10: o deadlock depende do K09

Hoje, `callSyncMethod(...).get()` chamado na própria thread principal fica esperando para sempre (`CraftFuture.java:43-50`
espera; quem acorda é `run()` `:79-96`, que só roda no batimento `SCHED:343-388`). Por isso todo plugin que usa esse
padrão já o protege com `if (!Bukkit.isPrimaryThread())`. Na thread de região o teste dá "não sou a principal", o plugin
entra no ramo assíncrono e espera a thread principal, que está parada na junção esperando a região: deadlock do servidor.
Então: **alto enquanto `isPrimaryThread()` for falso na região; quase zero depois do tratamento do K09.** A ordem de
implementação importa: o K09 tem que vir antes ou junto da primeira fase paralela.

### K11: o risco está noutro lugar

- `vanillaConsoleSender`: ver tabela. Sem corrida real no B.
- Command blocks: atrás de `isCommandBlockEnabled()` (`CommandBlockLogic.java:107`); desligados na cópia de teste.
  Carrinho com command block segue a mesma regra.
- O que o relatório não viu: `CommandHandler.executeCommand` vanilla (`MC/net/minecraft/command/CommandHandler.java:33-80`)
  **não** dispara evento Bukkit nenhum, só o `CommandEvent` do Forge (`:59-60`), e a checagem de permissão foi desligada
  pelo Cauldron (`:57`, `if (true || icommand.canCommandSenderUseCommand(...))`, "we handle it on Bukkit side"). Mods que
  rodam comando de dentro de um TE chamam esse método direto e o comando roda na thread de região, fora do monitor de
  plugin. Virou o item N01. (Fora do assunto de threads, mas vale registrar: qualquer remetente de mod que não seja
  jogador executa comando vanilla sem checagem de permissão.)

### K17: contador de ticks, não relógio

`MS:683` (`if (MinecraftServer.currentTick++ % 100 == 0)`) roda uma vez por tick, no ramo que tica. O valor inicial vem de
`System.currentTimeMillis()/50` (`MS:186`), mas depois é um contador de ticks do servidor. O efeito "alcançar ticks"
descrito para o Degrau A continua certo, porque o servidor tica mesmo quando a região é pulada.

### K18: Timings

Retirar `UnsafeTimingHandler`. Acrescentar o custo de N06.

### K24: consulta de perfil

A dúvida do relatório se resolve. `PlayerProfileCache.func_152655_a` (`PlayerProfileCache.java:144-188`):
- sem entrada no cache: `func_152650_a` (`:179`) → `findProfilesByNames` (`:93`), chamada HTTP que bloqueia;
- com entrada vencida: remove dos dois `HashMap` (`:151-152`) sem trava;
- sempre: `this.func_152658_c()` (`:187`) serializa até 1000 entradas e grava `usercache.json` no disco (`:273-296`).

Na região, isso roda dentro do handler, portanto **com o monitor global de plugin seguro** (K01): todas as regiões que
dispararem evento param enquanto a rede ou o disco respondem. Os `HashMap` também são tocados pelas threads de login
(`ServerConfigurationManager.java:153-155`) ao mesmo tempo: corrida que já existe hoje e que o B não piora muito.
Caminho de região sem plugin: crânio com nome colocado por FakePlayer (`ItemSkull.java:114` → `TileEntitySkull.java:96`,
e `:104` chama o servidor de sessão).

### §2 do relatório: `CraftTravelAgent.DEFAULT`

É o `TravelAgent` do overworld (`CraftTravelAgent.java:17-18`, `WorldServer.java:181`) e é usado por qualquer portal cujo
destino não tenha `TravelAgent` próprio (`E:2485`, `ServerConfigurationManager.java:713,994`). Não é inofensivo em
princípio: item N04.

---

## 3. Itens acrescentados

### 3.1 Os dois caminhos, vistos pela camada Bukkit

**Caminho 1: TE que move itens para o vizinho e atualiza vizinhos** (funil vanilla; a parte vanilla está em
`mundo-conferencia.md` §3). O que a camada Bukkit acrescenta, por transferência:
`TileEntityHopper.java:337` cria um `CraftItemStack` → `:350-351` chama `iinventory.getOwner()` **duas vezes** (teste de
`null` e uso) → `TileEntity.getOwner` (`TileEntity.java:302-312`) → `CW:114-116` `getBlockAt` → `loadChunk` (escrita em
`chunksToUnload` e no cache MRU, N02) → `CraftBlock.getState` (`CraftBlock.java:367-388`) → `new CraftChest(...)` →
`CraftBlockState(Block)` (`CraftBlockState.java:33-50`): `getTileEntity` e `te.writeToNBT(nbt)` (`:43-47`) do TE de
destino (`block.getChunk()` em `:40` só devolve o campo, `CraftBlock.java:261-263`) → `:371` o mesmo para o próprio funil → `InventoryMoveItemEvent` →
`callEvent` (K01, WorldGuard e ChestShop ouvem). O lado "puxar de cima" repete o padrão (`:545-547,563`) e a coleta de
item solto também (`:617-619`, `InventoryPickupItemEvent`, com `getBukkitEntity()` do item, K22). Vira o item N03.

**Caminho 2: entidade que cruza borda de chunk e é rastreada por jogador.** Camada Bukkit:
`canSushchestvoTick` (`CH:220-266`: K14 e `chunksToUnload.contains`) → `ActivationRange.checkIfActive`
(`ActivationRange.java:248-275`: lê o estático `currentTick`, K17, sem escrita compartilhada) → `entity.tickTimer`
(`TimingHandler.java:102`, nada fora da thread principal, K18) → `moveEntity` → `getBukkitEntity() instanceof Vehicle` em
colisão horizontal (`E:911-934`: cria o embrulho se faltar, K22; `getBlockAt` → `loadChunk`, N02; `VehicleBlockCollisionEvent`
→ K01) → `func_145775_I` (`E:1045-1075`: K03 e N02) → troca de chunk e rastreador (vanilla, [H16], [H26]). Para jogador,
`EntityPlayerMP.onUpdate` só toca Crucible/Spigot em `EntityPlayerMP.java:344-406` (envio de chunks, [H30]). Mapas
(`CraftMapView.render`, `CraftMapView.java:16-17,116-154`, `HashMap`s por mapa) só são chamados de
`EntityPlayerMP.onUpdateEntity` (`EntityPlayerMP.java:429`, chamado da rede, `NetHandlerPlayServer.java:416,445,488`) e do
rastreador (`EntityTrackerEntry.java:155`): fora da fase paralela no B; anotar para o C.

### N01. Comandos vanilla disparados por mods a partir de TEs, sem Bukkit e sem permissão

- Onde: `MC/net/minecraft/command/CommandHandler.java:33-80`. Checagem de permissão desligada em `:57`; só o
  `CommandEvent` do Forge em `:59-60` (que chega a ouvintes de plugin registrados pelo `CrucibleEventBus`, K12).
- Tipo: suposição de thread única (código arbitrário de comando roda como trabalho de região).
- Quem: mods com computadores ou blocos de comando próprios que chamam
  `MinecraftServer.getServer().getCommandManager().executeCommand(...)` no tick do TE. Não conferi quais mods do pack fazem
  isso.
- O que quebra: comandos vanilla e de mod (`/time`, `/weather`, `/tp`, `/give`, `/kill`, comandos de mod) rodam numa thread
  de região, fora do monitor de plugin, mexendo em estado global e de outras regiões; `PlayerSelector` itera a lista de
  jogadores.
- Severidade: **B médio** (depende de existir esse uso no pack; não verificado) | **C alto**.
- Tratamento: não dá para adiar em geral, porque `executeCommand` devolve o resultado na hora. No B: mixin em
  `CommandHandler.executeCommand` que, fora da thread principal, toma uma trava global de "comandos" (não o monitor de
  plugin, para não misturar ordens de trava) e registra o mod que chamou (modo observador). No C: comandos só na região
  global.

### N02. Toda construção interna de `CraftBlock`/`CraftChunk` grava estado compartilhado do mundo

- Onde: `CW:114-116` → `CW:151-153` → `ChunkProviderServer.java:169-177`. Chamadores dentro de trabalho de região
  (lista conferida): `E:1069` (colisão, K03), `E:914` (veículo), `W:1100` (`BlockPhysicsEvent`, [A02]), `W:4086`
  (`BlockCanBuildEvent`), `TileEntity.java:304` (`getOwner`, N03), `CEF:99,121,151,185,238,650,694,731,811,817,830,845,869,878,884,975`
  (construção dos eventos de colocação, quebra, ignição, redstone, nota).
- Tipo: leitura que escreve, numa coleção por mundo (`chunksToUnload`) e no cache MRU do mapa de chunks.
- Quem: praticamente todo evento Bukkit montado em trabalho de região, mais a colisão de toda entidade em movimento.
- O que quebra: o que [A02] e [H01]/[H05] descrevem, só que com frequência muito maior: perda de atualização de
  `elements`/`modCount` no `LongHashSet`, cache MRU com par chave/valor trocado ([H01]); e, se o chunk não estiver
  carregado, carga síncrona de dentro da região ([H07]).
- Severidade: **B alto** (frequência: por bloco tocado por entidade, por tick) | **C alto**.
- Tratamento: um único ponto de correção. Fazer o CraftBukkit interno montar `CraftBlock` sem `loadChunk`: um
  `CraftWorld.getBlockAtIfLoaded` (lê `getChunkIfLoaded`, que não grava `chunksToUnload`; o cache MRU continua sendo
  [H01]) usado por `E:1069`, `E:914`, `W:1100`, `W:4086`, `TileEntity.getOwner` e os `CEF` acima; e criar o bloco só quando
  há ouvinte (K03). A API pública `World.getBlockAt` chamada por plugin continua carregando (comportamento Bukkit), e esse
  caso fica com K02.

### N03. `TileEntity.getOwner()`: três fotos em NBT por transferência de funil

- Onde: `TileEntity.java:302-312`; `CraftBlock.java:367-388`; `CraftBlockState.java:33-50`; chamadores
  `TileEntityHopper.java:350-351,371,545-547,563,617-619`, `TileEntityBrewingStand.java:188-190`.
- Tipo: leitura de estado de outro TE; custo alto escondido.
- Quem: todo funil, a cada transferência, e todo funil com item solto em cima; alambiques ao terminar.
- O que quebra: (a) `te.writeToNBT` completo do funil e do TE vizinho (que pode ser máquina de mod com NBT enorme) na
  thread de região, **antes** do monitor; se o vizinho estiver em outra região, foto rasgada de um TE que está ticando
  ao mesmo tempo. O raio de junção deve impedir vizinho em outra região, então no B isso é **custo**, não corrida. (b) Cada
  foto passa uma vez por `loadChunk` (N02), e o destino é fotografado duas vezes. (c) Mais tempo por transferência, e esse tempo conta para a região mais
  lenta.
- Severidade: **B baixo** como corrida, **médio** como custo | **C médio**.
- Tratamento: cachear o `getOwner()` dentro da chamada (hoje é chamado duas vezes seguidas, `:350-351`, `:545-547`,
  `:617-619`); montar o `InventoryHolder` sem foto NBT quando só `getInventory()` é usado. Medir antes de mexer (regra do
  projeto).

### N04. `CraftTravelAgent.DEFAULT`: um `TravelAgent` compartilhado por todas as dimensões

- Onde: `CraftTravelAgent.java:9,15-19` (estático público, atribuído ao criar o overworld); campos mutáveis
  `searchRadius`, `creationRadius`, `canCreatePortal` (`:11-13`, setters `:51-75`); usado em `E:2485`,
  `ServerConfigurationManager.java:713,994`. `findOrCreate` liga e restaura `loadChunkOnProvideRequest` do mundo de destino
  (`CraftTravelAgent.java:24-36`, padrão salvar/trocar/restaurar de [H12]).
- Tipo: estático mutável.
- Quem: portal de entidade ou jogador em dimensão de mod sem `TravelAgent` próprio; handlers de `EntityPortalEvent`/
  `PlayerPortalEvent` que ajustam o agente.
- O que quebra: um plugin que muda o raio ou desliga a criação de portal para um evento muda para todos os seguintes
  (já é assim hoje, em série); no paralelo, uma região lê os campos enquanto o handler de outra os escreve, e duas
  regiões fazendo `findOrCreate` no mesmo mundo de destino podem deixar `loadChunkOnProvideRequest` no valor errado.
- Severidade: **B baixo** (o desenho §5 já adia teleporte e portal para fora da fase paralela) | **C médio**.
- Tratamento: nenhum além do adiamento do teleporte já previsto; no C, um `TravelAgent` por chamada.

### N05. `ConfigurationSerialization.aliases` escrito na primeira carga de classe de plugin

- Onde: `MC/org/bukkit/configuration/serialization/ConfigurationSerialization.java:27,200-218` (`HashMap` estático);
  escrito por `JavaPluginLoader.setClass` (`JavaPluginLoader.java:238-246`) quando uma classe de plugin que implementa
  `ConfigurationSerializable` é carregada pela primeira vez; lido por toda desserialização (`:250`).
- Tipo: estático mutável, escrito de forma preguiçosa.
- Quem: a primeira vez que código de plugin é executado numa thread de região sem o monitor (caminhos K12, N01, ou
  handler que carrega uma classe nova), ao mesmo tempo que uma thread assíncrona de plugin desserializa config.
- O que quebra: `HashMap.put` concorrente com `get`: entrada perdida ou mapa corrompido. Raro; já possível hoje com
  tarefas assíncronas.
- Severidade: **B baixo** | **C baixo**.
- Tratamento: junto com K25 (mapa concorrente) ou nenhum.

### N06. Com Timings ligado, todo evento Forge postado da região monta timers por ouvinte

- Onde: `MC/cpw/mods/fml/common/eventhandler/EventBus.java:151-166`; `CR/io/github/crucible/CrucibleTimings.java:20-22`
  (`Timings.ofSafe("## Listener - " + nome, ...)` para cada ouvinte, a cada `post`).
- Tipo: custo; estado concorrente seguro (`ConcurrentHashMap`, K18).
- Quem: todo evento Forge de trabalho de região, só com Timings ligado (`timings_enabledSinceServerStartup` padrão false,
  `CrucibleConfigs.java:228`; ou `/timings on`).
- O que quebra: nada de correção. Custo: concatenação de string e busca no mapa por ouvinte por evento nas threads de
  região, sem medir nada (os timers não fazem nada fora da thread principal). Com Timings ligado, a fase paralela fica
  mais lenta e o relatório de Timings fica vazio para ela.
- Severidade: **B baixo** | **C baixo**.
- Tratamento: deixar Timings desligado durante os testes do B (já é a regra do projeto usar JFR); se for preciso, pular o
  ramo com Timings fora da thread principal.

---

## 4. Julgamento dos tratamentos propostos

Inseguros ou caros demais para a fase paralela sincronizada:

1. **K10, "rodar o callable inline sob o monitor de plugin quando `get()` for chamado na região".** Inseguro. (a) A tarefa
   já está na fila (`SCHED:159-164` → `handle`); se o callable rodar inline, o batimento roda de novo na fase global,
   porque `CraftFuture.run` (`CraftFuture.java:79-85`) só desiste se o período for `-2` (cancelada). Execução dupla, a não
   ser que se cancele atomicamente antes. (b) O callable foi escrito para a thread principal fora de qualquer região;
   rodá-lo na região expõe tudo do K02. Recomendo: fazer o K09 antes (o caso some quase todo) e, para o resto, **falhar
   rápido** (exceção com log do plugin), nunca esperar.

2. **K01, "o monitor de plugin é a trava mais externa".** A regra como está escrita não é cumprível: a thread de região
   entra no `callEvent` no meio do próprio trabalho, e se ela estiver segurando qualquer trava nossa nesse momento
   (rastreador [H26], `entityIdMap` [M18], travas de mundo propostas no estudo do mundo), o monitor fica **dentro**, não
   fora. A regra certa tem duas metades: (i) nenhuma thread de região segura trava nossa quando dispara evento Bukkit ou
   Forge (mesma regra que `mundo-conferencia.md` §4 item 5 pediu); (ii) código que roda sob o monitor nunca espera por
   recurso de região nem pela thread principal. Também falta dizer que o monitor é um limite de vazão do B: com
   WorldGuard ouvindo `BlockPhysicsEvent`, toda notificação de vizinho de todas as regiões passa por ele. Precisa entrar na
   estimativa de ganho do Degrau B, com medida.

3. **K02, "fotografar `getEntities*` no começo da fase e adiar escritas fora do dono para a junção".** A foto por mundo por
   tick custa O(entidades) mesmo quando ninguém chama; se for feita, tem que ser preguiçosa (na primeira chamada do tick).
   E pode ser desnecessária: o desenho (§3 Degrau B) diz que as listas do mundo não mudam de tamanho na fase paralela; se
   isso for cumprido, iterar `loadedEntityList` de um handler já não dá CME. Adiar escrita (`setType`, `BlockState.update`)
   muda o comportamento: o plugin lê de volta o que escreveu e não vê, e decisões de cancelamento ficam erradas (mesmo
   argumento de `mundo-conferencia.md` §4 item 3 para explosões). Para o B recomendo começar pelo modo observador
   (registrar plugin, região de origem e destino) e só decidir o tratamento com os dados.

4. **K08, "mixin early em `getEffectiveSide`".** Ideia certa, mas `FMLCommonHandler` é usado durante a própria carga do
   FML e dos coremods; é provável que a classe já esteja carregada quando os mixins early entram, e aí o mixin não se
   aplica (**não verificado**). Como o Degrau B já exige o fork (`docs/02-arquitetura.md` §4(1)), mudar o fonte de
   `FMLCommonHandler.java:155-164` no fork é mais simples e garantido. Manter as threads Netty respondendo `CLIENT`, como
   hoje.

5. **K09, "`isPrimaryThread()` verdadeiro na região".** Certo, desde que venham juntos, no mesmo patch: (a)
   `TimingHandler.java:102,114` e `TimedEventExecutor.java:75` passam a testar a thread principal **real**, senão a pilha
   estática `TIMING_STACK` (`TimingHandler.java:43`) é usada por várias threads e se corrompe; (b) `kickPlayer`,
   `getNewScoreboard` e o novo local `E:342-374` ganham patch próprio (§2); (c) o ramo de iluminação `W:4943` testa a
   thread real (o relatório já diz). Efeito colateral aceitável: evento marcado como assíncrono disparado da região
   passa a lançar em `SPM:487-489`, igual ao que acontece hoje na thread principal.

6. **K14, "pré-aquecer o cache para todas as classes registradas e chavear por (mundo, classe)".** O pré-aquecimento
   chama `getBoolean/getInt` do YAML para toda classe de TE e entidade de todo mundo, e essas chamadas **gravam** padrões
   no config ([M34], `WorldConfig.java:36-57`): o `tileentities.yml`/`entities.yml` ganharia milhares de entradas novas.
   Preferir `ConcurrentHashMap.computeIfAbsent` com a leitura do config dentro de uma trava curta (primeira vez de cada
   classe). Chavear por (mundo, classe) muda qual regra vale em cada mundo: é correção de comportamento, não de thread.
   Fazer separado e registrar em `PROGRESSO.md`.

7. **K24, "no B, só documentar".** Não basta: uma chamada de plugin pode parar todas as regiões pelo tempo de uma
   requisição HTTP e de uma escrita em disco, porque roda sob o monitor global. Proposta barata: mixin em
   `PlayerProfileCache.func_152655_a` que, fora da thread principal, só responde do cache (sem rede) e não regrava o
   arquivo; regravar no salvamento automático, na fase global.

8. **K12, "envolver o ouvinte gerado com o monitor de plugin".** Correto e barato (1 plugin). Vale a mesma regra do item 2:
   eventos Forge são postados de dentro de código de região; nenhuma trava nossa pode estar presa nesse momento.

9. **K07, "trava nos seis métodos".** Correto. Usar uma trava privada, não o monitor do `SimplePluginManager`: com o
   mesmo monitor, criar o embrulho de um FakePlayer passaria a esperar por qualquer handler de plugin em execução
   (disputa à toa). A trava tem de cobrir as leituras, porque o `WeakHashMap` muda ao ler.

10. **K03, "thread-local".** Correto, com a mudança de §2 (criar o `CraftBlock` só no dano). Um campo na própria classe da
    thread de região (`((RegionThread) Thread.currentThread()).ctx`) é mais barato que `ThreadLocal.get()`, e há escritas
    por bloco tocado.

Corretos, com nota:
- K04, K05, K06: thread-local serve; o custo de consultar a captura em todo `getBlock` já foi tratado em
  `mundo-conferencia.md` §4 item 1 (contador global barato antes do `ThreadLocal`).
- K11: adiar command block para a junção serve; vale para o pouco que resta depois da correção.
- K13: adiar carga e descarga de dimensão para a fase global, de acordo com o desenho §5.
- K16, K17, K19, K20, K21, K22, K23, K25, K26: de acordo. Em K22, o CAS no campo `bukkitEntity` é barato e evita o
  embrulho duplo que `getEntities` provoca; dá para fazer já no B.
- K18: de acordo (nada no B, medir com JFR). Lembrete da regra do projeto: `/timings paste` publica fora
  (`TimingsExport.java:323`).

---

## 5. Lacunas que continuam

- Nada foi rodado. NPE do K03, corrupção de mapas do K07/K14/N02 e o deadlock do K10 vêm da leitura do código.
- Produção não conferida: `enable-command-block`, `skip-*-ticks`, quais plugins ouvem `BlockPhysicsEvent`,
  `InventoryMoveItemEvent`, `EntityDamageByBlockEvent`, e quais usam `callSyncMethod`/`kickPlayer`/`getOfflinePlayer`.
  Todas as contagens são da cópia `server-test3`.
- Não conferi quais mods do pack chamam `CommandHandler.executeCommand` de TEs (N01) nem se os 72 jars chamam
  `getEffectiveSide` em caminho de tick (K08).
- Se um mixin early consegue mesmo transformar `FMLCommonHandler` (§4 item 4): **não verificado**.
- Quantos chunks costumam estar em `chunksToUnload` enquanto entidades andam perto deles (decide o peso real de N02 sobre o
  `LongHashSet`; o cache MRU é escrito de qualquer jeito).
- Custo real de N03 (fotos NBT por transferência de funil): não medido; medir antes de mexer.
- `IntCache` (arte-prévia §4 item 6): fora desta área, não conferido aqui.
- Comportamento exato de `HashMap`/`WeakHashMap` do Java 8 sob as corridas descritas (perda de entrada, laço, exceção):
  conhecimento geral, não testado.
