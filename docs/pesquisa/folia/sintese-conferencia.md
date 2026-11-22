# Conferência final da síntese Folia x NeoFolia

- Conferido: `docs/pesquisa/folia-neofolia.md` (a síntese) e as mudanças em `docs/02-arquitetura.md` (`git diff`).
- Conferente: agente diferente do autor da síntese e dos relatórios de tema. Só leitura de código e edição de texto. Nada compilado, nenhum servidor ligado, nenhum commit.
- Clones usados (mesmos commits da síntese):
  - Folia `eca40f918928e78c2c5f9c944cfb083d7d9851d4` (2026-11-18). A4 = `folia-server/minecraft-patches/features/0004-Region-Threading-Base.patch`; B2 = `folia-server/paper-patches/features/0002-Region-Threading-Base.patch`; A7, A11, A12 = patches 0007, 0011, 0012 da pasta de A4. "Linha N" = linha dentro do `.patch`.
  - NeoFolia `fa76006de0c01f5cd0c3290f0994898c39e8441f` (2026-06-04). `Guard`, `Disp`, `Settings` = `RegionizedWorldGuard`, `RegionizedTaskDispatcher`, `RegionizedServerSettings` em `src/main/java/net/neoforged/neoforge/server/region/`.
  - Crucible `staging@67b530340b080f94079793a15e5588c2dd6fc94b`, árvore com patches em `eclipse/cauldron/src/main/java/` (`MC` no estudo de estruturas hostis). Siglas iguais às da síntese, mais `WS` = `WorldServer.java`.

## Veredito

A síntese é boa: fiel às conferências de tema (usa a versão corrigida em todos os pontos que conferi), cuidadosa com o que não foi verificado e escrita em português simples. **Achei um erro de conta que se espalhou por seis lugares** (a distância em que duas áreas viram uma região), uma fórmula de threads mal resumida, uma contagem de tabela que não bate com a conferência e alguns "fatos" que dependem de código do Paper fora do clone e estavam escritos sem ressalva. Todos foram corrigidos no texto. No `02-arquitetura.md` nenhuma decisão registrada foi apagada; corrigi quatro frases de fato e uma contradição interna com o Degrau C, e completei a lista de "propostas que ainda não são decisão".

Números: 74 afirmações conferidas, 61 confirmadas, 11 corrigidas (em 13 trechos de texto), 0 removidas, 2 não verificáveis.

---

## 1. Fatos centrais abertos no código

Termo: **seção** = quadrado fixo de 16×16 chunks; **região** = conjunto de seções que tica junto, numa thread por vez.

### Folia (`eca40f9`)

| # | Fato da síntese | Veredito | Evidência |
|---|---|---|---|
| F1 | Seção padrão 16×16; margem de 1 seção; raio de junção 1 | Confirmado | A4 11510-11518 (`Math.max(1, 8 / (1 << shift))`, raio 1) |
| F2 | Threads automáticas | **Corrigido** | A4 5786-5800: `núcleos/2`; se `<= 4`, 1; senão divide por 4 (inteiro). Dá **1 thread até 15 núcleos**, 2 com 16. A síntese dizia "até 9" (veio da [FGc #5]), o que esconde a faixa 10-15. A conclusão N14 (7 vCores → 1 thread) continua certa |
| F3 | Região que tica não ganha seção nova, mas ganha chunk nas suas | Confirmado | A4 4042-4048 (caminho rápido), 4109-4112, 4130-4134 (pula região `isTicking()`) |
| F4 | Região vizinha que não tica é absorvida na hora | Confirmado | A4 4130-4150 (usa a primeira não ticando como alvo). Por isso corrigi o `02` (ver 2.B) |
| F5 | A thread principal liga o servidor e dorme | Confirmado | A4 8266-8275 (`for(;;) Thread.sleep(Long.MAX_VALUE)`) |
| F6 | Fila global drenada sem teto | Confirmado | A4 1756-1758 (`while (runOneTask() \|\| runOnePacketTask())`), `// TODO try catch?` em 1767 |
| F7 | Dreno da região roda só o que já estava na fila | Confirmado, com exceção | A4 2288-2303. Exceção: as tarefas **globais de chunk** do mundo são drenadas sem limite no mesmo laço (`executeGlobalTasks`), como a [FGc #23] já dizia; a síntese omite (pequeno) |
| F8 | Na junção, as tarefas da região que some vão para a frente, na ordem | Confirmado | A4 2363-2381 (`descendingIterator` + `addFirst`, comentário "ensure order is maintained") |
| F9 | Teleporte entre regiões troca a entidade por uma cópia | Confirmado | A4 15175-15186 (`getType().create` + `restoreFrom`) |
| F10 | Exceção que escapa do tick para o servidor de forma ordenada | Confirmado | A4 5416-5429 (`regionFailed` → `halt` → `stopServer`); também `uncaughtException` 5406-5414 |
| F11 | Parada numa thread própria, espera até 60 s, termina teleportes, salva região por região | Confirmado | A4 1243-1310 (`halt(true, 60 s)`, `finishTeleportations`, `saveRegionChunks` por região, `saveLevelData`) |
| F12 | Colisão vira parede até 4 chunks antes da borda | Confirmado | A4 863 (`isTickThreadFor(world, x, z, 4)` por chunk percorrido) |
| F13 | `ServerTickStartEvent` uma vez por região | Confirmado | A4 8394 |
| F14 | Junção forçada com tickets num caminho em L | Confirmado (o código); efeito não verificado | A12 84-113. Que o ticket crie o chunk holder (e junte as regiões) é regra do Paper/Moonrise, fora do clone. Ressalva acrescentada na síntese e no `02` |
| F15 | `CraftScheduler.handle` lança sempre | Confirmado | B2 4066. Que todo método do `BukkitScheduler` passe por ele: não verificado ([FPc #9]); ressalva acrescentada |
| F16 | Plugin sem `folia-supported` não carrega | Confirmado | B2 735-739 e 751-755 (`RuntimeException`) |
| F17 | Estáticos do `CraftEventFactory` viraram `ThreadLocal`; sorteio compartilhado por thread | Confirmado | B2 3824, 3842; A4 14658 (`ThreadLocalRandomSource`) |
| F18 | Vigia escreve a pilha da região travada a cada 5 s | Confirmado | A11 59-105 (acorda a cada 1 s, imprime se passou 5 s do último aviso; não mata nada) |
| F19 | Borda corta redstone, trilho e tripwire | Confirmado | A7: `DetectorRailBlock`, `PoweredRailBlock`, `RedstoneWireBlock`, `TripWire*`, `CollectingNeighborUpdater` |
| F20 | Hora do mundo avançada só pela global e copiada no começo do tick da região (D14) | Confirmado | Avanço: A4 1983. Cópia: A4 8382 (`getCurrentWorldData().updateTickData()`), que lê a foto `world.tickData` (A4 3350-3351) montada pela global com o `getGameTime()` (A4 1944, 11554-11555). A síntese citava só [FRc #31], que fala do avanço, não da cópia |
| F21 | `MinecraftServer.currentTick` deixou de andar | Confirmado (pela [FGc +8], A4 8285) | não reaberto |

### NeoFolia (`fa76006`)

| # | Fato da síntese | Veredito | Evidência |
|---|---|---|---|
| N1 | Grade fixa shift 4, criação 1, junção 1, busca 3 | Confirmado | `Guard` 45-48 |
| N2 | União de quadrados ativos a até 3; dono = menor X, depois menor Z; cada ativo cobre raio 1 | Confirmado | `Guard` 1060-1073, 1078-1088, 1122-1132 |
| N3 | Mapa de dados por região estático, nunca limpo | Confirmado | `Guard` 52 (`static final ConcurrentHashMap REGION_WORLD_DATA`) |
| N4 | Mapa refeito logo depois de `tickCount++` | Confirmado | `MinecraftServer.java.patch` 183-186 |
| N5 | `ServerTickEvent` e `LevelTickEvent` na thread principal | Confirmado | mesmo patch, 187, 217, 278, 286 |
| N6 | Fila global drenada no começo de `tickChildren`, teto 1.024 tarefas ou 10 ms | Confirmado | mesmo patch 263-265; `Settings` 62-63 |
| N7 | Fatia 256 tarefas ou 5 ms; tempo limite de espera 60 s | Confirmado | `Settings` 58-59, 66 |
| N8 | Despachante só liga depois do "servidor iniciado" | Confirmado | mesmo patch 123-124 (a síntese não usa; ver seção 4) |
| N9 | Parada fecha o pool sem esperar e cancela a fila antes de salvar | Confirmado | mesmo patch 149-150; `Disp` 929-958 (`shutdownNow`, `cancelPendingTasks`, cancela tarefas globais) |
| N10 | Quatro interruptores, todos ligados por padrão | Confirmado | `Settings` 26-29, 258-261 (`readBoolean(..., true)`) |
| N11 | Threads automáticas: 1 com 8 núcleos | Confirmado | `Settings` 168-193. Conta: 8 → floor(7,2)=7 − 6 reservadas = 1; com 7 vCores também dá 1 |
| N12 | Tarefa repetida descartada se a anterior está esperando **ou rodando** | Confirmado | `Disp` 152-176 (a marca só sai no `finally`) |
| N13 | Uma região nunca espera outra | Confirmado | `Disp` 573-586 (`ensureSafeBlockingAwait` lança) |
| N14 | `setBlock` em outra região: reagenda e devolve `false` | Confirmado | `Level.java.patch` 102-106 |
| N15 | `getBlockEntity` de outra região devolve `null` | Confirmado | `Level.java.patch` 486 |
| N16 | Sorteio por região | Confirmado | `Level.java.patch` 704-707 |
| N17 | Lista de tickers de tile entity esvaziada a cada tick | Confirmado | `Level.java.patch` 413-422 |
| N18 | Listas de jogadores filtradas por região e devolvidas como cópia | Confirmado | `PlayerList.java.patch` 990-1015 |
| N19 | Pacote de mod vai para a região do jogador | Confirmado | `ServerPayloadContext.java` 60-70 (`executeOnPlayerRegion`) |

### Crucible 1.7.10 (`67b53034`): fatos que sustentam "dá" e "não dá"

| # | Fato | Veredito | Evidência |
|---|---|---|---|
| C1 | Ganchos de entrada e saída de chunk | Confirmado | `CPS:261-262`, `CPS:567-568` |
| C2 | Chunks forçados tirados da fila de descarga | Confirmado | `CPS:512-520` |
| C3 | Listas públicas do mundo | Confirmado | `W:129,131,134` |
| C4 | `getTotalWorldTime` lê `worldInfo`; `currentTick` estático | Confirmado | `W:4358-4361`; `MS:186` |
| C5 | Eventos de tick do Forge, agendador do Bukkit e rede na thread do servidor | Confirmado | `MS:829, 894, 905, 957, 1006, 1023` |
| C6 | Troca de dimensão copia entidade; mesmo mundo não | Confirmado, com precisão | `E:2469, 2528, 2532-2533, 2540`. **O jogador não é copiado**: `EntityPlayerMP.travelToDimension` (linha 694) chama `transferPlayerToDimension` com o mesmo objeto. Precisão acrescentada na síntese (N6) e no `02` §5 |
| C7 | `transferPlayerToDimension` com o comentário do Twilight Forest | Confirmado | `SCM:940-955`, `SCM:945` |
| C8 | `getEffectiveSide` decide pelo nome da thread | Confirmado | `FMLCommonHandler.java:156-165` |
| C9 | Pacote sem prioridade vai para a fila; padrão é sem prioridade; `FMLProxyPacket` não muda | Confirmado, com exceções | `NetworkManager.java:135-148, 221-245`; `Packet.java:57-60`; `FMLProxyPacket` sem `hasPriority`. **Exceções que rodam no Netty**: pacotes com prioridade (`C00PacketKeepAlive`, `C01PacketChatMessage` sem "/", `C00Handshake`), o aperto de mão do FML (`NetworkDispatcher.java:309-334`) e o repasse do Cauldron do canal de mod para ouvintes de plugin (`NetworkDispatcher.java:339`). A síntese dizia "todo pacote"; corrigido |
| C10 | Forge 1.7.10 sem capability; `IFluidHandler` existe; `IEnergyHandler` não é do Forge | Confirmado | não há `net/minecraftforge/common/capabilities`; `net/minecraftforge/fluids/IFluidHandler.java` existe; nenhum `IEnergyHandler` na árvore |
| C11 | Balde do mapa de chunks = 16×16 chunks | Confirmado | `src/main/java/thermos/wrapper/ChunkBlockHashMap.java` (chave `(x>>4, z>>4)`, `new Chunk[16][16]`) |
| C12 | Contagens de jars (63, 34, 26, 46; 55 e 41) | Confirmado nas fontes | `DIAGNOSTICO.md` D-27; `crucible-tick-e-threads.md` tabela de campos e linhas 164, 168 |

### Contagens das conferências (tabela da §0 da síntese)

FR, FP, NA e NM batem com o texto de cada conferência. **FG não bate**: a tabela da [FGc] tem 72 "C", 3 "C em parte / NV", 7 "Cor", 1 "R", 2 "NV"; a síntese dizia 67/10/1/7. Corrigido, com nota explicando que o texto de abertura da [FGc] fala em "10 correções" contando ajustes fora da tabela. Total novo: 261 / 46 / 3 / 7.

---

## 2. Correções feitas no texto

### Em `docs/pesquisa/folia-neofolia.md`

1. **Distância de junção (erro de conta, o mais importante).** A síntese dizia "duas áreas carregadas a menos de 33 a 63 chunks viram uma só". A regra conferida é "seções a até 3 seções de distância" ([FRc #5], `Guard` 1060-1073, teste do NeoFolia "0 e 3 juntam, 0 e 4 não"). Em chunks, no eixo de maior distância: **a até 48 chunks (768 blocos) sempre juntam**; de 49 a 63 depende da grade; a 64 (1.024 blocos) ou mais, nunca, salvo por cadeia. "33 a 63" é só a faixa entre chunks de duas seções a exatamente 3 seções; lido como limite, faz pensar que bases a 40 chunks podem ficar separadas, o que é falso. Corrigido na §3, D1 e pergunta 3.
2. **Threads automáticas do Folia**: "1 thread até 9 núcleos" → "até 15 núcleos (16 → 2, 32 → 4)", na tabela e em N14.
3. **Contagem da linha FG e do total** (seção 1 acima).
4. **"O ticket faz a região existir"** escrito como fato no resumo (item 3), na tabela e em D5: ressalva "o elo ticket → chunk holder é do Paper, não verificado" ([FGc #18], [FGc +4]).
5. **"O agendador antigo do Bukkit lança"** no resumo (item 4) e na tabela: ressalva "o `handle` lança sempre; que todo método passe por ele não foi verificado" ([FPc #9]).
6. **D6 "todo pacote roda na thread do servidor"** → "quase todo", com as três exceções conferidas (C9).
7. **N6**: a cópia na troca de dimensão vale para entidade que não é jogador; o jogador continua o mesmo objeto (C6).
8. Notação: acrescentei a sigla `WS` (`WorldServer.java`), usada no acréscimo abaixo.

### Em `docs/02-arquitetura.md`

9. **§2, linha do Folia**: dizia "conferido no código" para a linha inteira, que inclui "reescreveu o sistema de chunks". O sistema de chunks novo (Moonrise) é do Paper, fora do clone. Agora diz que só a proibição de plugins foi conferida.
10. **(2) Regras fixas "copiadas do Folia": "junções e divisões só no fim do tick"**. No Folia a junção com região que **não** está ticando é imediata (F4, [FRc #9]). Acrescentei isso à correção existente e deixei claro que "o mapa só muda no ponto de junção" é regra nossa, mais estrita.
11. **Distância de junção** em (2) Geometria, risco 8 e §7 item 9 (mesmo erro do item 1).
12. **(5) Thread global: "ela só roda com as regiões paradas (barreira)"**, sem dizer o degrau. Contradiz o Degrau C aprovado ("regiões independentes", sem barreira; decisão 2 da §7). Qualifiquei: "no Degrau B"; no C, remete à última frase do item. Não mudei o desenho.
13. **(6) e (7)**: as mesmas ressalvas dos itens 4 e 5. **§5, teleporte**: a mesma precisão do item 7.
14. **§7, lista de propostas que ainda não são decisão**: faltavam três mudanças que o estudo escreveu no desenho como se já estivessem decididas: reação padrão "registrar e rebaixar" em (3), relógio único por mundo no Degrau C em (5) e teleporte no mesmo mundo sem cópia na §5. Acrescentei à lista.

### Acréscimos na síntese (fatos que o leitor não via)

- **D3**: o vanilla 1.7.10 já descarta tick agendado de chunk descarregado (`WS:764`, `WS:795-797`, `WS:622`) depois de gravá-lo no chunk (`AnvilChunkLoader.java:404`, `getPendingBlockUpdates(chunk, false)`). Descartar na divisão as entradas de seção sem chunk carregado repete o vanilla. Reforça D3.
- **N3**: no Folia, agendar tick de bloco em outra região lança exceção (A4 20437, [FRc +4], [FGc #44]). Exemplo concreto de por que "falhar com força" não serve para mods 1.7.10.
- **D9**: no Folia a região que falha não é liberada e as vizinhas ficam esperando junção ([FRc +9]). Importa para a opção "rebaixar e seguir" da pergunta 2.
- **§11**: duas lacunas de desenho (ver seção 3).

---

## 3. "Dá para trazer" e "não dá": o motivo se sustenta?

Abri o código do Crucible citado em cada item (seção 1, C1 a C12) e conferi os IDs do estudo de estruturas hostis (H02, H04, H07, H13, H48, M17, M24, M27, M30, K01, K03, K09, K10, S04, S06, S17, S30, T2 a T9): existem e dizem o que a síntese atribui a eles. **Não achei item classificado do lado errado.** Os "não dá" têm motivo real no código (N1: `MS:829-1023`; N2: `W:129-134`; N4: `SCM.playerEntityList`; N5: não existe pacote de capability; N6: `E:2469`, `SCM:945`; N8: `W:4358`, `MS:186`). Os "dá" que esbarram em estrutura hostil já dizem como contornar.

Pontos que ficam **otimistas demais ou incompletos** (apontados, não reescritos, porque são desenho):

1. **D2 "sem trava na fase paralela; chunk carregado na fase paralela entra no mapa só na junção".** Falta dizer de quem é esse chunk até a junção. A carga em si acontece na hora e já grava no mapa de chunks (`CPS:261-262`, [EH H02]). Sem dono no mapa de regiões, o observador veria todo acesso a ele como "fora da região". Precisa de regra (ex.: dono provisório = quem passou pela porta única de carga [EH H07]). Acrescentado em §11.
2. **D4 e N1 usam "fase serial" também para o Degrau C**, que por definição não tem barreira. "Posição sem região roda na fase serial" e "a global é a thread do servidor rodando a fase serial" só estão definidos para o B. Acrescentado em §11; no `02` qualifiquei a frase da barreira.
3. **D6 (pacotes na região do jogador no C)** já está marcado como hipótese. Reforço: além do chat, há mais trabalho de jogador fora da thread do servidor hoje (aperto de mão do FML, ouvintes de plugin em canal de mod, C9). Quem desenhar o C precisa dessa lista.
4. **D11 (interruptor por parte)** está certo e alinhado às decisões 18 e 23 do `PROGRESSO.md`.
5. **N8 x §3 do `02`**: o Degrau C diz "cada região tem o próprio relógio de tick" e o novo (5) diz "relógio único por mundo; a região só copia a hora". Não é contradição se "relógio de tick" for a agenda (TPS próprio) e "hora" for o tempo de jogo, como no Folia (F20). Vale escrever isso com todas as letras no `02` §3.

---

## 4. Mudanças no `02-arquitetura.md` x `PROGRESSO.md`

- **Nada foi apagado.** O diff só acrescenta texto e acrescenta ressalvas no fim de duas linhas de tabela. Decisões 9 (3 a 4 threads), 15 (B começa com tile entities e entidades), 18 (tudo desligado por padrão), 20 (1.G) e 23 (`server.jar`, `hoshiestar.yml`) seguem valendo e são citadas de forma coerente.
- Cada mudança decorre do estudo e está marcada "(estudo Folia x NeoFolia, 2026-11-22)".
- **Risco de confusão de numeração**: os itens novos da §7 do `02` se chamam 7, 8 e 9, mas o `PROGRESSO.md` já tem decisões 7, 8 e 9 com outro conteúdo, e a síntese cita números do `PROGRESSO` (D11: "decisões 18 e 23"; N14: "decisão 9"). Sugestão: chamar os novos de "P1 a P3" (perguntas ao admin). Não mudei.
- **(3) muda o sentido do modo "checagem"**: o texto original diz "avisa ou barra"; o novo diz "padrão = registrar e rebaixar". Agora está na lista de propostas (item 14 acima), mas é bom o admin ver.
- **Não registrado no `PROGRESSO.md`**: a entrega do estudo, as três perguntas ao admin e as propostas. Pela regra do projeto ("decidir e registrar em `PROGRESSO.md`"), o coordenador deve registrar. Não editei o `PROGRESSO.md` (fora do que me foi permitido).

---

## 5. O que ficou de fora da síntese (dos relatórios e conferências de tema)

Acrescentei os três mais relevantes (seção 2). Ficaram de fora, sem mudar conclusão:

- [FRc +10]: no Folia, `addChunk` (e a junção imediata) roda na thread de quem processou o ticket, inclusive enquanto outra região tica. Reforça por que o nosso D2 (mapa só muda na junção) é mais simples.
- [FGc #23] / F7: o dreno da região também roda as tarefas globais de chunk sem limite.
- [FGc +6]: muitos comandos viraram "dispara e esquece" (o valor devolvido deixa de dizer o resultado). Plugins 1.7.10 que leem o retorno de `dispatchCommand` notariam.
- [FGc +12]: no Folia a lista de tile entities que ticam também virou lista por região (o equivalente do nosso `loadedTileEntityList`).
- [FPc +4]: `PlayerMoveEvent` alterado por plugin vira teleporte assíncrono.
- [NAc 2.7] / N8: o NeoFolia só liga as regiões depois de o servidor terminar de iniciar (carga do mundo e spawn numa thread só). Ideia útil para o nosso B: ligar a fase paralela só depois do "Done".
- [NMc #9]: `BlockEntity.onLoad` continua na thread principal no NeoFolia.
- [NAc #43]: o corte de consultas de entidade no NeoFolia usa o retângulo do grupo, que num grupo em "L" pode cobrir quadrado de outro dono.

---

## 6. Linguagem e promessas

- Português simples, frases curtas, termos explicados na §0. Nenhuma promessa de ganho; o que é conta está marcado como conta; hipóteses estão em §11.
- Os três "fatos" que dependiam do Paper (ticket cria região, todo método do agendador lança, sistema de chunks reescrito) eram o único ponto em que o texto afirmava mais do que o código mostra; corrigidos.
- A §0 diz "esta página não traz fato novo sobre os dois projetos". Depois desta conferência isso continua quase verdade: os acréscimos sobre o Folia vêm das conferências de tema; os novos fatos de código são do Crucible e estão marcados "(conferência final)".

---

## 7. Não verificado (e por quê)

- Que o ticket `MAX_TICKET_LEVEL` crie o chunk holder sem carregar o chunk (e, portanto, que os tickets do A12 juntem regiões): regra do Paper/Moonrise, fora do clone. Herdado das conferências de tema.
- O que `OSNuma.getTotalCores()` conta (núcleo físico ou lógico): fora do clone. Muda o número exato da fórmula do Folia, não a conclusão para 7 vCores.
- Não reabri o código de cada um dos 317 itens das conferências de tema: conferi 40 deles no código (seção 1) e a coerência do resto com as tabelas de conferência.
