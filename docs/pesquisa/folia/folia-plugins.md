# Folia: como trata plugins

- Projeto lido: Folia, https://github.com/papermc/folia
- Commit lido: `eca40f918928e78c2c5f9c944cfb083d7d9851d4` (2026-11-18 21:24:54 -0700). Clone raso. Minecraft alvo 26.3.
- Método: só leitura dos arquivos `.patch` (código novo = linhas que começam com `+`). Nada compilado. Nenhum servidor ligado.
- O Paper (base do Folia) **não está no clone**. Tudo que é código do Paper e não patch do Folia está marcado "não verificado no clone".
- Documentação usada como fonte secundária (baixada em 2026-11-22, texto em `docs-baixados/`): `README.md` do Folia, https://docs.papermc.io/folia/reference/overview, https://docs.papermc.io/folia/faq, https://docs.papermc.io/paper/dev/folia-support. O que vem só delas está marcado "da documentação, não conferido no código".

Abreviações (caminhos dentro de `Folia/`; "linha N" = linha dentro do arquivo `.patch`):
- **A4** = `folia-server/minecraft-patches/features/0004-Region-Threading-Base.patch`
- **B2** = `folia-server/paper-patches/features/0002-Region-Threading-Base.patch`
- **C1** = `folia-api/paper-patches/features/0001-Force-disable-timings.patch`
- **C2** = `folia-api/paper-patches/features/0002-Region-scheduler-API.patch`
- **C3** = `folia-api/paper-patches/features/0003-Require-plugins-to-be-explicitly-marked-as-Folia-sup.patch`

---

## 1. Resumo (10 linhas)

1. Plugin sem `folia-supported: true` no `plugin.yml` (ou `paper-plugin.yml`) **não carrega**: o servidor lança exceção na hora de montar o plugin (B2 735-739, 751-755; B2 4126-4130).
2. Não há camada de compatibilidade: nenhuma trava global para plugins, nenhum modo "em série", nenhuma chave para liberar plugin não marcado (buscas na seção 9). A resposta do Folia é "não roda".
3. O `BukkitScheduler` antigo foi marcado `@Deprecated` (C2 33-41) e **todo agendamento por ele lança `UnsupportedOperationException`** (B2 4065). O "batimento" que rodava as tarefas foi desligado (A4 8564).
4. A troca são quatro agendadores: de região (por local), de entidade (segue a entidade), global e assíncrono. Só o de região foi escrito no Folia (A4 6752); os outros três vêm do Paper e o Folia só muda onde eles ticam.
5. Não existe mais thread principal. Cada região tica numa thread do pool; a região global também (A4 1720-1760).
6. Eventos disparados por um jogador rodam na região dona do jogador, porque os pacotes dele são processados no tick dessa região (A4 8397-8401). `ServerTickStartEvent`/`ServerTickEndEvent` disparam **uma vez por região por tick**, em paralelo (A4 8394, 8461).
7. O evento de chat síncrono antigo (`PlayerChatEvent`/`ChatEvent`) **deixou de ser disparado** (B2 289, 298).
8. A API checa a thread dona: ler/escrever bloco, carregar chunk, explodir, mexer em entidade lança erro fora da região dona; hora, clima, metadados de mundo e limites de spawn só na thread global (B2 994-1281, 1300-1518, 166 e mais 168 classes de entidade).
9. Algumas APIs lançam erro sempre: `teleport` síncrono, criar/descarregar mundo, placar (scoreboard), conversas (B2 2205, 3142, 892, 900, 4077-4113; A4 13557).
10. Comando de console roda na thread global (A4 1816); comando de entidade/jogador roda na região dona dela (B2 873-883). Vários comandos vanilla foram desregistrados (A4 7334-7425).

---

## 2. Termos (primeira vez)

- **plugin**: programa Bukkit/Paper carregado de `plugins/`. Diferente de mod Forge.
- **`plugin.yml` / `paper-plugin.yml`**: arquivo de descrição do plugin (nome, versão, comandos). O Folia lê uma chave nova nele.
- **região**: conjunto de chunks vizinhos que tica junto, numa thread só por vez.
- **região global**: tarefa única que cuida do que não pertence a lugar nenhum (hora, clima, console). Não é dona de nenhum chunk.
- **thread dona**: a thread que, naquele momento, é a única autorizada a mexer num chunk, bloco ou entidade.
- **agendador (scheduler)**: serviço onde o plugin pede "rode isto daqui a N ticks" ou "rode isto agora em outra thread".
- **tarefa aposentada (retired)**: tarefa do agendador de entidade cuja entidade saiu do mundo; ela não roda, e um "callback de aposentadoria" é chamado no lugar.
- **ticket**: pedido de "mantenha este chunk carregado".
- **checagem de thread** (`ensureTickThread`): linha que testa se a thread atual é a dona do dado e lança exceção se não for.

---

## 3. Pergunta 1: como um plugin declara suporte, e o que acontece com quem não declara

### Resposta

- **Declaração**: chave `folia-supported` no `plugin.yml`. Só vale se o texto do valor for `true` (sem diferenciar maiúscula). Qualquer outra coisa, ou ausência, conta como "não suportado".
- **Plugin não marcado**: o servidor **recusa carregar**. Lança `RuntimeException("Could not load plugin '...' as it is not marked as supporting Folia!")` nas duas fábricas de plugin (formato Paper e formato Spigot) e `InvalidPluginException("Plugin ... is not marked as supporting regionised multithreading")` numa terceira checagem.
- **Não há escape**: não achei propriedade de sistema, opção de config ou lista de exceções que libere plugin não marcado.

### Evidência

`plugin.yml` (API, C3):

- C3 linha 32: nova função na interface `PluginMeta`: `boolean isFoliaSupported();`
- C3 linha 45: `private static final String FOLIA_SUPPORTED_KEY = "folia-supported";` em `PluginDescriptionFile`.
- C3 linhas 52-54 (`PluginDescriptionFile.isFoliaSupported`):
  ```java
  return this.foliaSupported != null && this.foliaSupported.equalsIgnoreCase("true");
  ```
- C3 linhas 64-66 (leitura do YAML em `loadMap`): `foliaSupported = map.get(FOLIA_SUPPORTED_KEY).toString();`
- C3 linhas 6-14 (mensagem do commit do patch, texto do autor, não código): "the server will refuse to load them ... and provide a log indicating why".

`paper-plugin.yml` (servidor, B2):

- B2 709: `private boolean foliaSupported = false;` em `PaperPluginMeta`; getter B2 717-721.
- Como a chave do YAML vira esse campo: **não verificado** (o leitor de config do Paper está fora do clone). A documentação diz que vale para os dois arquivos (`folia-support.txt` linha 9, da documentação).

Onde o plugin é barrado (B2):

- B2 735-739, `PaperPluginProviderFactory.build`: `if (!configuration.isFoliaSupported()) throw new RuntimeException(...)`.
- B2 751-755, `SpigotPluginProviderFactory.build`: mesma checagem para plugin de `plugin.yml`.
- B2 4126-4130, `CraftMagicNumbers` (no método que valida `api-version`; o nome do método não aparece no trecho): `throw new InvalidPluginException("Plugin ... is not marked as supporting regionised multithreading")`.
- Quem captura essas exceções e o que vai para o log: **não verificado** (código do Paper, fora do clone).

Documentação (não conferido no código): README linhas 109-113 ("only plugins that have been explicitly marked ... will be loaded"); `folia-support.txt` linhas 13-15 ("Marking a plugin as supported on Folia is not nearly enough").

---

## 4. Pergunta 2: a API de agendadores nova e o que acontece com o BukkitScheduler antigo

### 4.1 De onde vêm os agendadores

- As **interfaces** `RegionScheduler`, `GlobalRegionScheduler`, `AsyncScheduler`, `EntityScheduler` e `ScheduledTask` **não estão no clone**. Aparecem só como links de javadoc em C2 linhas 35-38. Ficam no Paper.
- O próprio Paper já cria os agendadores. B2 828-832 mostra, em linhas de contexto (código do Paper, sem `+`), o bloco `// Paper start - Folia region threading API` em `CraftServer` com `FoliaAsyncScheduler` e `FoliaGlobalRegionScheduler`, e um `FallbackRegionScheduler` para o agendador de região.
- O Folia troca **só o agendador de região**: B2 829-830 substitui `FallbackRegionScheduler` por `FoliaRegionScheduler`.
- Conclusão: o plugin escrito para essa API roda no Paper comum também. A documentação confirma: "If you use these schedulers when running Paper, they will be internally handled" (`folia-support.txt` linhas 43-44, da documentação).

### 4.2 Agendador de região (`RegionScheduler`) — código no Folia

Implementação: A4, classe `FoliaRegionScheduler`, linha 6752.

O que o código garante:

- **Roda na thread da região dona do chunk pedido.** `execute(plugin, world, chunkX, chunkZ, run)` põe a tarefa na fila de tarefas da região dona (A4 6786-6794: `RegionizedServer.getInstance().taskQueue.queueTickTaskQueue(...)`). A documentação diz que essa fila "cria a região se ela não existir" (overview linhas 210-213, da documentação; não conferi no código da fila).
- **Atraso em ticks da região**, não do servidor. `runDelayed` e `runAtFixedRate` (A4 6802 e 6832) recusam atraso `<= 0` com `IllegalArgumentException` e recusam plugin desligado com `IllegalPluginAccessException`.
- Se a thread atual já é dona do local, enfileira direto; senão, manda um recado para a região dona enfileirar lá (A4 6817-6821, 6850-6854; `scheduleInternalOffRegion` 6772-6783).
- **Mantém o chunk carregado** enquanto há tarefa pendente na seção: põe ticket `REGION_SCHEDULER_API_HOLD` (A4 6920-6940, `addTicket`/`removeTicket`).
- Na junção de regiões, os prazos são corrigidos por um deslocamento; na divisão, cada seção leva suas tarefas (A4, `REGIONISER_CALLBACK.merge`/`split`, logo depois de 6880).
- Exceção dentro da tarefa é **capturada e registrada** no logger do plugin; não derruba a região (A4 6754-6762 `wrap`; `LocationScheduledTask.run`).
- Tarefa repetida volta para a mesma região (`scheduleInternalOnRegion(this, this.repeatDelay)` no `finally` de `run`).
- Onde tica: no início do tick de cada região, `((FoliaRegionScheduler)Bukkit.getRegionScheduler()).tick()` (A4 8403).

### 4.3 Agendador global (`GlobalRegionScheduler`)

- Implementação `FoliaGlobalRegionScheduler`: no Paper, **não verificado no clone**. O Folia só corrige uma leitura volátil (B2 799-806).
- Onde tica: uma vez por tick da região global, em `RegionizedServer.globalTick` (A4 1810). Foi tirado do tick do servidor (A4 8566 sai, 8575 comentado).
- O que a tarefa global **não pode** fazer: mexer em bloco, chunk ou entidade. Motivo no código:
  - A tarefa global não tem região: `GlobalTickTickHandle` nasce com região `null` (A4 1727) e o agendador só marca a região da thread se ela não for nula (A4 5530-5532).
  - Sem região, `TickThread.isTickThreadFor(world, chunk)` devolve falso (B2 83-91, 125-131).
  - Para entidade, na thread global só vale jogador ainda em fase de configuração/login (B2 163-184).

### 4.4 Agendador de entidade (`EntityScheduler`)

- A classe base é do Paper (**não verificado no clone**). O Folia só acrescenta `isRetiredOffThread()` (B2 764-778) e muda onde ela tica.
- A lista de agendadores de entidade passa a ser **por região** (A4 3221, campo `entitySchedulerTickList` em `RegionizedWorldData`). Na junção/divisão de regiões, cada entidade se registra na lista nova (A4 2969, 3079). Ao entrar no mundo, a entidade se registra na lista da região atual (A4 15740-15741).
- Onde tica: no início do tick da região, só para entidades que a thread atual é dona e que não foram aposentadas (A4 8405-8411):
  ```java
  if (!TickThread.isTickThreadFor(handle) || scheduler.isRetired()) { continue; }
  scheduler.executeTick();
  ```
- Aposentadoria: quando o jogador sai, `player.retireScheduler()` (A4 14072, contexto). Quem agenda passa um callback para esse caso; exemplo em `CraftEntity.teleportAsync`, que completa com `FALSE` quando a entidade foi aposentada (B2 2289-2296).
- Garantias exatas (ordem, o que acontece com tarefa pendente na aposentadoria): **não verificado** (código do Paper).

### 4.5 Agendador assíncrono (`AsyncScheduler`)

- Implementação `FoliaAsyncScheduler`: no Paper, **não verificado no clone**.
- O Folia passa a cancelar as tarefas assíncronas do plugin quando ele desliga: `server.getAsyncScheduler().cancelTasks(plugin)` no lugar de `server.getScheduler().cancelTasks(plugin)` (C2 17-18).
- Código que roda ali **não tem região**, então qualquer checagem de thread de mundo falha (B2 87-88: sem região, só passa a thread de parada).
- Documentação: "running tasks independent of the server tick process" (`folia-support.txt` linha 79, da documentação).

### 4.6 BukkitScheduler antigo

- Interface marcada `@Deprecated`, com javadoc apontando os quatro novos (C2 33-41).
- `CraftScheduler.handle(task, delay)` começa com `if (true) throw new UnsupportedOperationException();` (B2 4065).
- O batimento que rodava tarefas síncronas foi comentado: `//this.server.getScheduler().mainThreadHeartbeat();` (A4 8556 sai, 8564 entra).
- Ao desligar plugin, o servidor não chama mais `getScheduler().cancelTasks` (B2 691-697; C2 17).
- Quais métodos públicos passam por `handle`: no CraftBukkit original, `runTask*`, `runTask*Asynchronously`, `scheduleSync*`, `callSyncMethod` passam por ele. Isso é de memória do CraftBukkit e **não verificado no clone**. Se for assim, **qualquer** agendamento pelo `BukkitScheduler`, até o assíncrono, lança erro.

Documentação (não conferido no código): README linhas 93-102 (o BukkitScheduler "inherently relies on a single main thread") e 160-163.

---

## 5. Pergunta 3: em que thread roda cada tipo de evento, e o que o handler pode tocar

### 5.1 O que o código mostra

| Evento / caso | Thread | Evidência |
|---|---|---|
| Ações do jogador (quebrar/colocar bloco, interagir, comandos digitados) | Região dona do jogador. Os pacotes do jogador são processados no tick da região, só enquanto a thread é dona dele | A4 8397-8401 (`while (TickThread.isTickThreadFor(player) && packetProcessor.executeSinglePacket())`) |
| `ServerTickStartEvent` / `ServerTickEndEvent` | **Uma vez por região, por tick**, na thread da região, com o número de tick da região | A4 8394, 8461; `tickServer` é chamado por região em A4 6197 |
| `RegionizedServerInitEvent` | Antes de agendar qualquer região (na thread que chama `init`) | A4 1636-1637 |
| `AsyncPlayerChatEvent` | Continua sendo disparado | B2 286-287 |
| `PlayerChatEvent` e `ChatEvent` (chat síncrono antigo) | **Não é mais disparado**: `if (false && listenersOnSyncEvent)` | B2 289, 298 |
| Parte síncrona do chat que sobrou | Agendador da entidade jogador (região dona) | A4 13549 |
| Completar comando no console (`TabCompleteEvent` e afins) | Região global | B2 1531, 1540 |
| `RemoteServerCommandEvent` (RCON) | Região global; a thread do RCON espera (`join`) | A4 10525 e linhas seguintes |
| Envio da árvore de comandos ao jogador (`runSync`) | Agendador da entidade jogador | A4 7448-7452 |

Mudanças para o código de evento aguentar threads paralelas:

- Variáveis estáticas do `CraftEventFactory` viraram `ThreadLocal` (um valor por thread): `sourceBlockOverrideRT` (B2 3824) e `LAST_BLOCK_LOCK_CHECK_EVENT` (B2 3842).
- O barramento de eventos (`PaperEventManager`) **não foi alterado** pelo Folia: busca por `PaperEventManager`, `isPrimaryThread`, `isAsynchronous` nos patches não achou mudança nele. Como ele decide se um evento "síncrono" pode ser disparado numa thread de região depende do Paper: **não verificado**.

### 5.2 O que um handler pode e não pode tocar

Pelo código:

- **Blocos e chunks**: só dentro da região dona. `CraftBlock.getBlockState` e cerca de 20 outros métodos de `CraftBlock` checam a thread dona (B2 1300-1495); `CraftBlockState.update` e `getDrops` também (B2 1509, 1518).
- **Entidades**: `CraftEntity.getHandle()` checa a thread dona (B2 166). O mesmo teste foi posto em `getHandle()` de 168 subclasses de entidade (contagem da mensagem "Accessing entity state off owning region's thread" em B2).
- **Exceção importante**: `CraftPlayer.getHandle()` **não tem checagem**: "no checks for players, as it's a total mess" (B2 3116). Erro de thread com jogador passa calado.
- **Coisas do mundo inteiro** (hora, clima, metadados de mundo, limites de spawn, chunks forçados): só na thread global (B2 1050-1063, 1098-1106, 1155-1269).
- **Dados do próprio plugin**: nenhuma proteção. O README diz que eventos e comandos são chamados em paralelo e que o plugin tem de cuidar dos próprios dados (README 145-153, da documentação).

Documentação (não conferido no código):

- README 177-179: evento que envolve uma entidade roda na região dona da entidade; ação sobre uma entidade (ex.: dano) roda na região dona do **alvo**. Não conferi caso a caso (ex.: `EntityDamageEvent`).
- README 181-183: o modificador "async" de evento estaria obsoleto. **Não achei código** que mude `isAsynchronous` (busca vazia nos patches).
- README 133-137: em geral a região é dona de uns 8 chunks em volta da origem do evento, "mas isso não é garantido".

---

## 6. Pergunta 4: que métodos da API passam a lançar erro fora da thread dona

### 6.1 Como a checagem funciona

- `TickThread.isTickThreadFor(world, chunkX, chunkZ)`: pega a região da thread atual; sem região, só a thread de parada passa; com região, compara com a dona do chunk (B2 83-91).
- Versão por área (vários chunks): exige que **todas** as seções da área sejam da região atual (B2 125-151).
- Versão por entidade: B2 158-219 (regras para jogador em login, entidade ainda fora do mundo, entidade removida).
- `RegionizedServer.ensureGlobalTickThread(motivo)` lança `IllegalStateException(motivo)` se não for a thread global (A4 1709-1712).
- `TickThread.ensureTickThread(...)`: a função em si é do Paper. O tipo de exceção que ela lança: **não verificado no clone**. O Folia só muda a mensagem de contexto (B2 38-48).

### 6.2 Checados contra a thread dona da região (lista do que achei em B2)

- `CraftWorld`: `getChunkAt` (994), `unloadChunk` (1016), `refreshChunk` (1024), carga síncrona de chunk (1033), `generateTree` (1073), `createExplosion` (1123, 1131), pegar chunk por bloco (1139, 1147), `sendGameEvent` (1277-1281, este com `IllegalStateException` explícita).
- `CraftBlock`: leitura e escrita de bloco (1300-1495).
- `CraftBlockState`: `update` (1509) e `getDrops` (1518).
- `CraftEntity.getHandle()` (166) e mais 168 subclasses; `PaperSchoolableFish.getHandle` (488).
- `CraftServer.dispatchCommand` com entidade como remetente (875).

### 6.3 Checados contra a thread global (B2)

- Chunks forçados: ler e mudar (1050, 1056, 1063).
- Hora do mundo (1098, 1106).
- Clima (1155-1187).
- Ajustes do mundo, como limites e tempo entre spawns (1195, 1201, 1235, 1261, 1269; também ~1201 em `setTicksPerSpawns`).
- Metadados de mundo `setMetadata/getMetadata/hasMetadata/removeMetadata` (1209-1227).
- `CraftServer.dispatchCommand` com console, RCON ou remetente remoto (876-879).

### 6.4 Lançam erro sempre (em qualquer thread)

| Método | Exceção | Evidência |
|---|---|---|
| `Entity.teleport(...)` síncrono (`teleport0`) | `UnsupportedOperationException("Must use teleportAsync while in region threading")` | B2 2205 (entidade), 3142 (jogador) |
| `Server.createWorld` / `unloadWorld` | `UnsupportedOperationException` | B2 892, 900 |
| `World.getTickableTileEntityCount` | `UnsupportedOperationException` | B2 977 |
| Placar: `registerNewObjective`, `registerNewTeam`, `clearSlot`, `getNewScoreboard`, `setPlayerBoard` | `UnsupportedOperationException` | B2 4077-4113 |
| Qualquer agendamento pelo `BukkitScheduler` (via `handle`) | `UnsupportedOperationException` | B2 4065 |
| `MCUtil.ensureMain` fora da "thread principal" | `UnsupportedOperationException` | B2 817 |
| Entrada de conversa (`Conversation` API, jogador conversando) | `UnsupportedOperationException` | A4 13557 |
| `MinecraftServer` como executor (`wrapRunnable`, `shouldRun`, `pollTask`, `pollTaskInternal`, `doRunTask`) | `UnsupportedOperationException` | A4 8306, 8326, 8334, 8338, 8346 |
| `SparksFly.executeMainThreadTasks` | `UnsupportedOperationException` | B2 258 |

### 6.5 Lançam erro se não estiverem numa região

- `Server.getTPS()` e `getAverageTickTime()`: `UnsupportedOperationException("Not on any region")` (B2 919-921, 944-946). Logo, falham na thread global e no agendador assíncrono.
- `Server.getCurrentTick()` chama `RegionizedServer.getCurrentTick()` (B2 964), que lança `IllegalStateException("No currently ticking region")` sem região (A4 1693-1702).

### 6.6 Coisas que ficaram seguras para várias threads (não lançam)

- `kickPlayer`/`kick`: a checagem antiga foi tirada, "it will simply delay the kick" (B2 3124-3132).
- Gerenciador de permissões com `synchronized` (B2 513-680).
- Mapas de visibilidade do `CraftPlayer` viraram `ConcurrentHashMap` ou mapa sincronizado (B2 3070-3072).
- `CraftMapView` com `synchronized` (B2 3881 em diante).
- Timings desligado à força (C1); spark embutido desligado (B2 267, 276).

---

## 7. Pergunta 5: comandos, em que thread rodam

### Resposta

- **Console**: na thread global. `handleConsoleInputs()` passou do tick do servidor para o tick global (A4 10504 sai; A4 1816 entra).
- **RCON**: o comando vira tarefa na fila global e a thread do RCON espera terminar (A4 10525).
- **Jogador digitando comando**: chega como pacote e é processado no tick da região dona do jogador (A4 8397-8401).
- **Plugin chamando `Bukkit.dispatchCommand`**: o remetente decide a thread exigida (B2 873-883):
  ```java
  if (rawSender instanceof Entity entity) {
      TickThread.ensureTickThread(((CraftEntity)entity).getHandle(), "Dispatching command async");
  } else if (rawSender instanceof ConsoleCommandSender || ... RconConsoleSource || ... CraftRemoteConsoleCommandSender || ... FeedbackForwardingSender) {
      RegionizedServer.ensureGlobalTickThread("Dispatching command async");
  } else {
      throw new UnsupportedOperationException("Dispatching command for " + rawSender);
  }
  ```
  Remetente de outro tipo (ex.: bloco de comando ou remetente feito pelo plugin) lança erro.
- Ajudante novo `CraftServer.dispatchCmdAsync` (B2 848-865): entidade vai para o agendador da entidade com atraso 1; console vai para a fila global. É método público de `CraftServer`; se está na API Bukkit: **não verificado** (não achei em `folia-api`).
- Encadeamento interno de comandos (`TaskChainer`) usa a fila global (A4 7327).
- Comandos do Paper e Spigot que mexem no servidor todo passam a rodar na fila global: `/paper heap` (B2 403), `/paper reload` (B2 417), `/spigot reload` (B2 4161).
- `/tps` foi trocado pelo comando de saúde do Folia (B2 369). `/restart` e `/tps` do Spigot foram desregistrados (B2 4182, 4191).

### Comandos vanilla desligados (código)

A4 7334-7425 (`Commands.java`) comenta o registro de: bossbar, clone, compute, data, datapack, debug, dialog, function, item, loot, reload, return, schedule, scoreboard, spectate, spreadplayers, tag, team, teammsg, test, tick, trigger, waypoint, perf, save-all.

Diferença com a documentação: o FAQ diz que `ride` e `rotate` estão desligados (`faq.txt` linhas 81-83), mas no código eles continuam registrados (A4 7390-7391). O FAQ não cita compute, dialog, test e waypoint. **O código vale**; a documentação está desatualizada nesse ponto.

Documentação (não conferido no código): README 174-175 ("Commands for entities/players are called on the region which owns the entity/player. Console commands are executed on the global region"). Bate com o código acima.

---

## 8. Pergunta 6: o que a documentação diz sobre o que quebra e como migrar

Tudo desta seção é **da documentação, não conferido no código**, salvo onde indico.

O que quebra (README):

- "I expect _every_ single plugin that exists to require _some_ level of modification" e "have your expectations for compatibility at 0" (README 77-85).
- Regiões "tick in parallel"; "sharing of data _will_ cause data corruption" (README 115-117).
- As checagens de thread vão aumentar, mesmo custando desempenho: "make bad accesses fail _hard_" (README 122-127, 207-209).
- Lista de API quebrada (README 185-194): portais, respawn e parte do login; **toda** a API de placar; carregar/descarregar mundo; `Entity#teleport` ("will NEVER ... come back, use teleportAsync"). Conferido no código: placar (B2 4077-4113), mundos (B2 892, 900), teleporte (B2 2205, 3142).

Como migrar:

- Marcar `folia-supported: true` não basta; é preciso desenvolver pensando no Folia (`folia-support.txt` 13-15).
- Detectar Folia com `ServerBuildInfo.buildInfo().isBrandCompatible(Key.key("papermc", "folia"))` (`folia-support.txt` 23-27). Não conferi essa API (é do Paper).
- Trocar o `BukkitScheduler` pelos quatro novos: global para o que não é de região; região para mexer em local; entidade para mexer em entidade ("will follow the entity"); assíncrono para trabalho fora do tick (`folia-support.txt` 29-89).
- Testar posse com `Bukkit#isOwnedByCurrentRegion` (README 164-165). A implementação não está no clone; o Folia usa essa função em 4 lugares (A4 6817, 6850; B2 1278, 2286).
- Planos futuros: eventos assíncronos de verdade, carregar mundo (README 196-203).

---

## 9. Pergunta 7: existe camada de compatibilidade para plugin antigo?

### Resposta: não. A resposta do Folia é "não roda".

O que encontrei no código é o contrário de compatibilidade:

- Plugin não marcado é barrado no carregamento (seção 3).
- O agendador antigo lança erro em vez de cair num modo em série (B2 4065).
- O chat síncrono antigo simplesmente deixa de ser chamado (B2 289, 298).
- Não há fila única, trava global ou "thread principal emulada" para plugins.

O que busquei (todas nos patches do commit `eca40f9`, só linhas novas com `+`):

- `Boolean.getBoolean`, `System.getProperty`, `Integer.getInteger` (chave escondida para liberar plugin): **nenhum resultado**.
- `legacy`, `compat` em `folia-api` e `paper-patches`: só o javadoc de `isFoliaSupported` (C3 27 e 48).
- `synchronized`, `ReentrantLock`, `globalLock` em `paper-patches`: só travas pontuais (permissões B2 513-680, `CraftMapView` B2 3881+). Nenhuma trava em volta de plugin ou de evento.
- `PaperEventManager`, `isPrimaryThread`, `AsyncCatcher` (barramento de eventos): sem mudança para serializar plugins.

O README explica a escolha: chamar eventos de forma síncrona "opens up deadlock issues and would handicap performance" (README 145-149, da documentação).

---

## 10. O que isso sugere para um servidor 1.7.10 com mods (OPINIÃO MINHA, não fato dos projetos)

1. **O Folia não tem o que precisamos para os 65 plugins.** Ele resolve plugin proibindo. Nosso desenho em `docs/02-arquitetura.md` (adapter de plugins com trava única, D-26) é uma ideia **nossa**, sem modelo no Folia para copiar. Isso confirma a linha 17 da tabela de fatos do `02-arquitetura.md`.
2. **Copiar a marca de "suportado", mas ao contrário.** No Folia a lista é de quem pode rodar. Para nós faz mais sentido o padrão "roda em série, sob trava" e uma lista de liberados para paralelo, como já está no Degrau C. A ideia de declarar explicitamente vale; o padrão de bloquear não.
3. **`ServerTickStartEvent` por região é um alerta.** No Folia um handler de "início de tick" roda N vezes por tick, em paralelo. No Forge 1.7.10, `ServerTickEvent` e `WorldTickEvent` são usados como "uma vez por tick" por muitos mods. Devem continuar na fase global em série, uma vez só. Não devemos imitar o Folia aqui.
4. **Interceptar o `BukkitScheduler` em vez de quebrá-lo.** O Folia quebra (B2 4065). Para nós, a tarefa sem alvo conhecido deve ir para a fase global em série, como hoje, e não lançar erro.
5. **Checagem de thread vale como ferramenta de teste.** O padrão do Folia (`ensureTickThread` nos pontos de entrada da API) pode virar um modo "só registrar" no Hoshiestar: não lança, só anota quem acessou dado de outra região. Serve para medir quais plugins e mods tocam dado fora da região, antes de ligar qualquer paralelismo.
6. **O "buraco" do jogador é instrutivo.** Nem o Folia conseguiu checar `CraftPlayer.getHandle()` (B2 3116). Jogador é estado compartilhado em toda parte. No nosso caso (mods que guardam `EntityPlayer` em estáticos), esperar o mesmo problema, maior.
7. **APIs que o Folia desligou são candidatas a ficar na fase global** no nosso servidor: placar, criar/descarregar mundo, teleporte síncrono, conversas, comandos de servidor inteiro. Em vez de desligar, rodá-las só em série.
8. **NeoFolia não ajuda com plugins.** O README dele diz "This is not a Paper/Bukkit plugin" e não tem camada Bukkit (NeoFolia `README.md` linha 9, commit `fa76006`). Mas ele tem interruptor por recurso (`regionized_entity_ticks` etc., README linhas 25-28), o que combina com nossa regra de "um interruptor por recurso".

---

## 11. Não verificado

- **Interfaces da API de agendadores** (`RegionScheduler`, `GlobalRegionScheduler`, `AsyncScheduler`, `EntityScheduler`, `ScheduledTask`): estão no Paper, fora do clone. O que cada método promete pelo javadoc não foi lido.
- **Implementações** `FoliaGlobalRegionScheduler`, `FoliaAsyncScheduler`, `FallbackRegionScheduler`, `FoliaEntityScheduler` e a classe base `EntityScheduler`: Paper, fora do clone. Só vi os pequenos ajustes do Folia (B2 764-806).
- **Se todos os métodos do `BukkitScheduler` passam por `CraftScheduler.handle`**: de memória do CraftBukkit; o corpo de `runTask*` está no Paper.
- **Quem captura a exceção de "plugin não marcado" e o texto exato do log**: Paper.
- **Como a chave `folia-supported` do `paper-plugin.yml` chega ao campo `foliaSupported`**: leitor de config do Paper.
- **Tipo de exceção de `TickThread.ensureTickThread`**: a função é do Paper/Moonrise; só vi a mudança de mensagem.
- **Comportamento do barramento de eventos** (`PaperEventManager`) com eventos "síncronos" disparados em threads de região: não alterado pelo Folia; depende de `isPrimaryThread` do Paper.
- **Thread de eventos específicos** além dos da tabela da seção 5 (ex.: `EntityDamageEvent` na região do alvo, `PlayerJoinEvent`, `PlayerTeleportEvent`, eventos de portal/respawn): só a regra geral do README; não segui cada chamada.
- **"Modificador async de evento obsoleto"** (README 181-183): não achei código que faça isso.
- **Implementação de `Bukkit#isOwnedByCurrentRegion`** e se `dispatchCmdAsync` está exposto na API: não achei no clone.
- **Detecção `ServerBuildInfo...isBrandCompatible`**: só documentação.
- **Se a fila de tarefas da região (`queueTickTaskQueue`) cria a região quando o local está descarregado**: só documentação (overview 210-213).
- Não compilei nem rodei nada; nenhuma afirmação aqui foi testada em servidor.
