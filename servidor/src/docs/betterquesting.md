# Recurso `betterquesting` (item 1.D): filtro exato da checagem de inventário do BetterQuesting

Correção embutida no `server.jar` do Hoshiestar (nada vai em `mods/` nem em `plugins/`). Desligada por
padrão. Relatórios de origem: `docs/pesquisa/etapa1/D-betterquesting.md` (desenho) e
`D-betterquesting-conferencia.md` (conferência; onde divergem, vale a conferência). Os relatórios
falavam em Mixin; aqui o mecanismo é o transformador do fork (seção 3).

Termos: **tarefa de retrieval** = tarefa de quest do tipo "tenha estes itens no inventário".
**Gancho** = método nosso que a classe do mod passa a chamar depois de transformada. **Trava de versão** =
SHA-256 (impressão digital) dos bytes originais de uma classe; a correção só entra se bater.
**Ore dictionary** = lista do Forge que agrupa itens equivalentes sob um nome (ex.: "ingotCopper").
**Identidade** = "é o mesmo objeto na memória" (`==`), não "é igual" (`equals`). **Mutação** = estragar de
propósito uma condição do código para ver se algum teste percebe.

---

## 1. O que faz, em linguagem simples

Toda vez que o inventário de um jogador muda (pegou, guardou, a ferramenta gastou durabilidade...), o
bq_standard, no começo do tick seguinte, reconta o inventário do jogador (e dos membros online do grupo)
contra **todas** as tarefas de retrieval das quests "ativas". No livro de quests do pacote todas as
8.939 quests têm `lockedProgress=1`, então "ativa" quer dizer "qualquer quest não completa": cerca de
7.400 tarefas por checagem, cada uma percorrendo 40 espaços por jogador. No perfil de produção isso foi
**2,95 % do tick** (o evento `EventHandler_onServerTick` inteiro deu 4,62 %; o 1,66 % restante é o
núcleo do BQ, que este recurso não toca).

Quase todas essas tarefas pedem itens que o jogador não carrega, e aí a recontagem não muda nada. O
Hoshiestar põe, antes de cada uma dessas chamadas, um **filtro que só pula a chamada quando dá para
provar que ela não mudaria nada**: nenhum item pedido está nos inventários que o BQ varreria, a tarefa
não usa ore dictionary com itens, não há progresso parcial para o BQ zerar e nada ficaria completo. Na
dúvida, chama o original, com o mesmo objeto e os mesmos argumentos, no mesmo ponto do laço.

Ganho esperado (conferência C7): **0,8 % a 1,4 % do tick** (≈ 0,7 a 1,2 ms/tick a 84,2 ms/tick). É
estimativa a partir do perfil e do livro de quests, **não medição** (ver seção 7).

## 2. Chaves no `hoshiestar.yml`

```yaml
betterquesting:
  modo: desligado      # desligado | verificar | ligado
  relatorio: 0         # segundos entre linhas de contadores no log (0 = nunca)
```

| Chave | Padrão | O que faz |
|---|---|---|
| `betterquesting.modo` | `desligado` | `desligado`: o BetterQuesting fica exatamente como no jar (a correção nem entra no transformador; a classe não é alterada). `verificar`: a classe é alterada, mas **toda** chamada roda o original; nas que o filtro pularia, o Hoshiestar fotografa antes e depois o que o original poderia mudar e **conta** (e registra no log, no máximo 10 linhas por minuto) cada caso em que o original mudou alguma coisa ("divergência" = o filtro estaria errado). Custa **mais** que o original (é para teste). `ligado`: pula as chamadas comprovadamente inúteis. Valor desconhecido vira `desligado` com aviso no log. Trocar exige reiniciar. |
| `betterquesting.relatorio` | `0` | De quantos em quantos segundos escrever no log a linha de contadores (a mesma do `hoshie status`). Negativo vira 0 com aviso. |

A linha no `hoshie status` (também no fim do boot e no relatório):
`modo <m>; chamadas N, puladas P (consume a, completa b, sem item c), original O (classe, item invalido,
ore, item presente, parcial, satisfeita, inventario nulo, dado estranho, sem regra 3, erro);
reconstrucoes R; ticks com checagem T (media X us por tick); [verificar: comparadas C, divergencias D;]
outra thread K; erros E`. No modo verificar o texto diz "que seriam puladas (o original rodou)".
Logo depois do modo vem o estado da correção (revisão M3): `(correcao aplicada)`; `(correcao aguardando
a classe do BQ ser carregada: o filtro ainda NAO roda)`; ou, se a correção foi recusada (trava,
dependência, gancho ausente, erro), `(correcao NAO aplicada: <motivo curto>; o filtro NAO roda, o BQ
segue original)`. Com `modo desligado` não aparece nada (a classe nem é tocada).
Se aparecer `DESLIGADO POR ERRO: ...` ou `REGRA 3 DESLIGADA: ...`, ver seção 4.4.

## 3. O que muda na classe do BetterQuesting

Só com o modo diferente de `desligado` **e** todas as travas conferindo. Classe alvo:
`bq_standard.handlers.EventHandler`, método `onServerTick(Lcpw/mods/fml/common/gameevent/TickEvent$ServerTickEvent;)V`
(de instância). Três alterações, todas nesse método:

1. A **única** instrução `invokeinterface bq_standard/tasks/ITaskInventory.onInventoryChange(Ljava/util/Map$Entry;Lbetterquesting/api2/utils/ParticipantInfo;)V`
   vira `invokestatic GanchoBQ.aoMudarInventario(ITaskInventory, Map$Entry, ParticipantInfo)V`
   (formato "trocar chamada" da base; se houver 0 ou 2 chamadas, recusa).
2. Na entrada do método entra `GanchoBQ.inicioDoTick(this, evento)` (formato "gancho no início", sem
   retorno antecipado): limpa o cache do tick.
3. A **única** instrução `invokevirtual java/util/HashSet.clear()V` (o `playerInventoryUpdates.clear()` do
   fim do laço) vira `GanchoBQ.fimDoLaco(conjunto)`, que faz o **mesmo** `clear()` e depois limpa o cache
   e conta o tempo. Uma exceção no meio do laço pula este ponto; por isso o cache também é limpo na
   entrada (conferência C2).

O teste confere, instrução por instrução, que o método transformado é o original mais essas três
mudanças e nada mais, e que o resto da classe é idêntico.

**Convivência com o Akashic-BQRelief.** O `StandardLoopMixin` dele aplica, depois do nosso transformador
(o nosso é o 2º da cadeia, o Mixin o 6º; medido no boot da base), dois `@Redirect` neste mesmo método,
em outras chamadas: `ParticipantInfo.getSharedQuests()` (`require=10`, em 10 métodos da classe) e
`IQuestDatabase.filterKeys(Collection)` (um `require` por grupo de métodos, 10 no total). Conferido no
bytecode do jar do BQRelief (`javap -v` do `StandardLoopMixin`) e no teste: depois da nossa troca, as 10
chamadas de cada alvo continuam com a mesma instrução (opcode, dono, nome, descritor), e no
`onServerTick` cada alvo existe exatamente uma vez. O `StandardInventoryTickMixin` (HEAD cancelável, só
com `-Dakashic.bqrelief.inventoryInterval>1`, desligado em produção) entraria antes do nosso gancho de
entrada; se cancelar, nada nosso roda naquele tick (só reduz a frequência).

**Travas (conferência C3).** SHA-256 dos bytes originais, todos de `BetterQuesting-3.8.79-GTNH.jar`
(calculados com `unzip -p <jar> <classe>.class | sha256sum`):

| Classe | SHA-256 |
|---|---|
| `bq_standard.handlers.EventHandler` (alvo) | `5d72d0c354131ed839acffe744e300411fa3edb1cdf6ff2a5a99cdaeb5e01116` |
| `bq_standard.tasks.TaskRetrieval` | `aed46e60ac45cb3ed85cad35fd6a1280c5259121ec15cdcf8f761a8d9f2642b6` |
| `bq_standard.tasks.TaskRetrieval$Detector` | `ee40ecb6876490368d0417681df00ca044f2b549f1f46c02886dcb6cc7e133c4` |
| `bq_standard.tasks.base.TaskBase` | `b3e53f06e38878843a572b24dd72e386329f6f4ea5acc9ebb1dec419a8863fbe` |
| `bq_standard.tasks.base.TaskProgressableBase` | `d6fa2da434ca21c7e3e2c6164a5a65416f9268149c77160f60455aff49192274` |
| `betterquesting.api.utils.ItemComparison` | `521dc25c2977cde7c9cccd43f5ed7d208c1d278915c15ed29b29a2c542f0949e` |
| `betterquesting.api.utils.BigItemStack` | `2de8c55c349fef0a6f40ee76eec6670746615bc22b337f406c5070fc055e142d` |
| `betterquesting.api2.utils.OreIngredient` | `9ba755adf1674c9eeba9fa44332fe1e499ffd522a8883784798ed4e25d05d51c` |
| `betterquesting.api2.utils.ParticipantInfo` | `01e024694f9a440f56e770f262564c73775a653001b49e3e3f29fa34d70d1847` |

A do alvo é a trava normal da base (bytes que chegam ao transformador; a base mediu no boot que esta
classe chega igual ao arquivo do jar). As outras oito são lidas **como arquivo** (sem carregar a classe)
pelo class loader dos mods quando o alvo passa pelo transformador. Qualquer uma diferente ou ausente:
nada é aplicado, o motivo vai para o log e para o `hoshie status`. `ParticipantInfo` entrou a mais que a
lista da conferência porque o filtro lê os campos dela diretamente.

## 4. Como funciona

Código: registro e contadores em `src/main/java/hoshiestar/betterquesting/BetterQuesting.java` (roda na
fase de coremod, sem tocar em Minecraft/Forge/BQ); ganchos em
`src/mods/java/hoshiestar/betterquesting/filtro/GanchoBQ.java`; a lógica em
`src/mods/java/hoshiestar/betterquesting/filtro/FiltroInventario.java`. O gancho só delega e cuida das
exceções; os testes chamam o **mesmo** gancho que o servidor (tirado do jar construído).

### 4.1 A regra (desenho 5.1 + conferência C1 a C5)

A chamada `task.onInventoryChange(quest, pInfo)` só é pulada quando, nesta ordem (a mais barata primeiro):

1. a classe da tarefa é **exatamente** `TaskRetrieval` (`TaskOptionalRetrieval`, `TaskFluid` e tarefas de
   addons vão sempre para o original);
2. e então basta uma destas:
   - (a) `consume && !autoConsume`: nesse caso `onInventoryChange` não faz nada;
   - (b) a tarefa já está completa para `pInfo.UUID`: `detect` sai na primeira linha;
   - (c) **todas** estas valem:
     - a lista de itens exigidos não é vazia; cada item é não nulo, tem quantidade > 0, pilha base não
       nula e `Item` não nulo;
     - nenhum desses `Item`s aparece (por **identidade**, C1) em pilha com quantidade > 0 nos inventários
       de `ACTIVE_PLAYERS` **e** de `PLAYER` (o `PLAYER` pode ser um objeto velho, de antes de um respawn;
       a união é um superconjunto do que o BQ varre);
     - a lista **viva** do ore dictionary de cada item (`OreIngredient.ores`, campo privado, a mesma lista
       que o BQ consulta) está vazia. O atalho "olhar só `getOreDict().isEmpty()`" **não** é exato (se
       um mod registrar itens no nome `""`, o `NO_ORE` passa a casar) e não é usado;
     - para cada uuid de `ALL_UUIDS`, o progresso salvo (lido direto do `TreeMap userProgress`, sem
       alocar, C4; ausente ou de tamanho errado = zeros, que é exatamente o que `getUsersProgress`
       devolveria) não tem nada que o `Detector` zeraria (sem `groupDetect`: cada valor é `0` ou `>=`
       exigido, com `== 0` e não `<= 0`, porque negativo também é zerado; com `groupDetect`: todos `0`;
       isso só vale sem `consume`) **e** não satisfaz a conclusão (todos `>=` exigido, ou algum, com
       `requireOnlyOneItem`).

Jogador nulo, inventário nulo ou lista de jogadores nula em qualquer lugar do grupo: não pula nada pela
regra (c) para aquele `ParticipantInfo` (C5); o original segue, com o comportamento e a exceção dele.
Uuid nulo ou progresso de tipo inesperado: original.

**Por que pular é exato.** Nenhuma pilha passa no `ItemComparison.StackMatch` (exige o mesmo `Item`)
nem no `OreDictionaryMatch` (lista vazia), então `Detector.run` não muda nada. O construtor do
`Detector` não tem parcial para zerar (e o preenchimento com zero do `groupDetect`, que escreve direto no
array salvo, cai em arrays já zerados). Assim `updated` fica falso, `setBulkProgress` não roda e
`checkAndComplete` vê arrays que não satisfazem a conclusão: nada de `setComplete`, `markDirtyParty` ou
evento. `getBulkProgress`/`isComplete` só leem. O único efeito do original seria tempo de CPU e lixo.
Tudo isso depende do código exato dessas classes, por isso as travas.

### 4.2 Cache do tick (C2)

O conjunto de `Item`s presentes é um `IdentityHashMap` reaproveitado, montado na primeira decisão para
um `ParticipantInfo` (o BQ cria um novo por jogador por tick; a chave é por `==`). É descartado: depois de
qualquer chamada ao original que possa mexer em inventário (toda chamada que não seja um `TaskRetrieval`
exato sem `consume`: `TaskFluid` troca recipientes, `consume` tira itens); na entrada do `onServerTick`;
e no fim do laço. Um conjunto velho só pode ter `Item`s **a mais** (mais chamadas ao original, nunca um
pulo errado). Nenhuma referência a jogador passa de um tick para outro.

### 4.3 Modo verificar

Em toda chamada que o filtro pularia, o gancho fotografa, antes e depois do original: para cada uuid de
`ALL_UUIDS`, "completa?" e o progresso salvo (objeto e conteúdo); para cada espaço dos inventários de
`ACTIVE_PLAYERS` e `PLAYER`, a pilha (objeto) e a quantidade. Todo caminho de escrita do `TaskRetrieval`
(código travado) muda pelo menos uma dessas coisas (`setComplete` acrescenta um uuid; `setBulkProgress`
põe um array novo ou muda o conteúdo; o `Detector` escreve no array salvo; `consume` tira itens;
`markDirtyParty` só acontece junto com uma delas). Diferença = divergência, contada e registrada com
limitador. O desenho usava `QuestCache.getDirtyQuests()`; trocado por esta foto, mais direta (seção 9).

### 4.4 Erros e threads

- A chamada original (`task.onInventoryChange`, `conjunto.clear()`) fica sempre **fora** de try/catch:
  as exceções dela saem exatamente como sem o Hoshiestar.
- O nosso código fica sempre **dentro** de try/catch (`Throwable`): qualquer exceção nossa **desliga o
  recurso até reiniciar** (modo vira `desligado`, status mostra `DESLIGADO POR ERRO: <onde>: <exceção>`,
  log com limitador de 5 por minuto) e a chamada original roda. O tratamento não depende da classe
  `FiltroInventario` (se até a carga dela falhar, nada escapa). Depois do desligamento, os três ganchos
  voltam logo para o original sem chamar `FiltroInventario` (o `clear()` do BQ continua sempre): uma
  falha que se repetiria a cada tick conta **um** erro só, e não enche o log (revisão M2).
- Se os campos privados (`OreIngredient.ores`, `TaskProgressableBase.userProgress`) não puderem ser lidos
  (`CampoPrivado.leitorExato`), a regra (c) fica desligada (`REGRA 3 DESLIGADA`) e só (a) e (b) valem.
- Tudo roda na thread do servidor, dentro do `synchronized (playerInventoryUpdates)` do próprio BQ. Se
  um gancho for chamado de outra thread, roda só o original, sem tocar no cache (contador `outra thread`).
- Nenhuma alocação no caminho de decisão do modo ligado (o conjunto é reaproveitado, listas por índice,
  progresso lido do `TreeMap`). O modo verificar aloca (fotos).

## 5. Por que é seguro

Invariantes:
- **I1** Chamada pulada = chamada que o original rodaria sem efeito nenhum (seção 4.1).
- **I2** Toda chamada não pulada é a original, mesmo receptor, mesmos argumentos, mesma ordem: conclusão,
  progresso, consumo de itens (`consume && autoConsume`), `markDirtyParty` e sincronização com o cliente
  são feitos pelo próprio BQ como antes.
- **I3** Itens, energia, fluidos: o filtro só lê (`getSizeInventory`, `getStackInSlot`, `getItem`,
  `stackSize`); nunca chama `decrStackSize` nem escreve em tarefa, progresso ou inventário.
- **I4** Nada persistente: nada é salvo, o NBT do BQ não é tocado, o cache morre a cada tick.
- **I5** Sem locks novos, sem threads; nada a esperar no desligamento.
- **Desligado = original por construção**: com `modo: desligado` a classe nem passa pelo transformador.
- **Na dúvida, original**: padrão não achado exatamente, gancho ausente, trava errada, dependência
  ausente ou exceção deixam os bytes originais ou o caminho original.

O que um jogador poderia notar: nada. Tarefas de retrieval completam no mesmo tick de antes, nunca
depois, nunca duas vezes.

Riscos que sobram (documentados):
- Outro mod que mude inventário de outra thread durante o laço: mesma exposição do original, que também
  lê sem lock.
- Um addon que acrescente uma subclasse de `TaskRetrieval` ou outro `ITaskInventory`: vai sempre para o
  original (classe exata).
- Achado pré-existente do BQ (conferência): `consume && autoConsume` com dois requisitos que casam o
  mesmo espaço pode dar NullPointerException no BQ. Não muda com o filtro (essas chamadas vão para o
  original quando o item está presente); no pacote há 0 tarefas com `consume`.

## 6. Testes feitos e como repetir

Comandos (do repositório, com o workspace como argumento):

```
servidor/build.sh --rapido <workspace> --github-raw --sem-testes --mods <server-test3>/mods
HOSHIE_LIBS=<server-test3>/libraries HOSHIE_MODS=<server-test3>/mods servidor/test.sh <workspace>
```

O `test.sh` roda `src/test/mods/betterquesting.bash`, que pega as **nossas classes do server.jar
construído** (os bytes que vão para o servidor), traduz os nomes do Minecraft como o FML faz na carga
(mapa do próprio jar) e usa as **classes reais** do `BetterQuesting-3.8.79-GTNH.jar` (só pelo
classpath; nenhum jar de mod entra no workspace). Minecraft/Forge são "de mentira" no diferencial
(`src/test/mods/bq/stubs`, só os membros usados, com nomes SRG), como no harness da conferência. O script
para se alguma fonte nossa for mais nova que o jar (testaria bytes velhos). Variáveis: `BQ_RAPIDO=1`
(20 mil cenários), `BQ_SEM_MUTACAO=1`.

Resultado da última rodada (2026-11-22, depois da revisão, jar construído com os fontes do commit
`63604a27`; os commits seguintes só mexem em testes e neste doc): **TesteBetterQuesting 128 ok, 0 falha
(JDK 8, com mutação) e 85 ok, 0 falha (JDK 21)**; o resto da suíte também passou (TesteAsm 224,
TesteTrava 35, TesteLimitador 13, TesteCampoPrivado 8, TesteConfig 35, TesteMekanism 108/70, nos dois
JDKs): `[test] TODOS OS TESTES PASSARAM`.

O que cada parte prova:

1. **Transformador sobre a classe real** `bq_standard.handlers.EventHandler`, pelo registro real
   (`BetterQuesting.registrar`), com o gancho conferido no arquivo do jar construído: correção
   `aplicada`; classe carregada e inicializada (verificador da JVM); `CheckClassAdapter` sem erros;
   mesmo número de métodos e campos; todos os outros métodos idênticos; no original exatamente 1
   `onInventoryChange` e 1 `HashSet.clear`, no transformado 0 deles e exatamente 1 chamada a cada
   gancho; o método = 3 instruções na entrada + o original com exatamente as 2 trocas (104 linhas);
   mesmos blocos try/catch (o `synchronized` intacto); **alvos do BQRelief intactos: 10 `getSharedQuests`
   e 10 `filterKeys`, mesma instrução antes e depois, e uma vez cada no `onServerTick`**; o
   `onServerTick` transformado roda nas fases START e END. Recusas: desligado (classe nem entra),
   trava do alvo, cada uma das 8 dependências mudada (8 de 8 recusam), dependência ausente, gancho
   ausente; convivência com a correção de trava errada do autoteste na mesma classe. A linha de estado
   diz `correcao aguardando` antes da carga, `correcao aplicada` depois, `correcao NAO aplicada ...; o
   filtro NAO roda` com a trava recusada e com dependência ausente, e nada com o modo desligado.
2. **Diferencial** (o harness da conferência, agora contra os ganchos reais do jar): o mesmo cenário
   aleatório é montado duas vezes; o laço original roda numa cópia e o laço com os ganchos na outra;
   compara `completeUsers`, `userProgress` (chaves e conteúdo), inventários espaço a espaço, a sequência
   de `MarkDirtyPlayerEvent`, as chamadas a `markDirtyParty` e a exceção. Além do que a conferência
   sorteava, sorteia vários jogadores no mesmo tick, tarefas de addon que põem item no meio do laço,
   `Item` com hashCode instável, jogador com inventário nulo e uuid nulo, e (depois da revisão, M1)
   progresso salvo com um item a **menos** que a lista (quest editada).
   - semente 3: 200.000 cenários, 1.191.397 chamadas, 452.448 puladas (consume 128.553, completa
     180.218, sem item 143.677), **0 diferenças**, 0 erros;
   - semente 1: 20.000 cenários, **0 diferenças**; semente 7: 20.000, **0 diferenças**. Total 240.000;
   - em cada semente, todas as regras de pular e todos os motivos principais de ir ao original
     aconteceram;
   - modo verificar, semente 11, 20.000 cenários: resultado igual ao original, 45.221 comparadas,
     **0 divergências**.
3. **Casos montados à mão** (40, todos ok): progresso parcial 5 de 10 (original zera e marca);
   parcial negativo; parcial de outro membro offline; `groupDetect` com progresso 3 (o Detector zera
   no array salvo) e com 0 (pulada); `consume` sem `autoConsume` (pulada); `consume`+`autoConsume` com o
   item (original, consome igual) e sem (pulada); inventário nulo de membro ativo (original, a mesma
   exceção); inventário nulo do PLAYER velho fora da lista; **ore `""` registrado** com o item no
   inventário (original); ore com nome sem itens (pulada); ore com o item no inventário (original);
   **Item com hashCode instável** (original: conjunto por identidade); conclusão já satisfeita;
   `requireOnlyOneItem`; completa para o PLAYER (pulada) e só para outro membro; `TaskOptionalRetrieval`;
   tarefa de addon que põe item; uuid nulo; lista vazia; quantidade 0; respawn (item só no PLAYER velho;
   com consume); dois jogadores no mesmo tick; tarefa que põe item no meio do laço (conjunto refeito);
   exceção no meio do laço (a entrada do tick seguinte solta o cache); fim do laço; desligado (só o
   original, contadores parados); outra thread; verificar (0 divergências) e verificar **acusando** um
   inventário mudado por fora com cache velho; erro no nosso código (recurso desliga, original roda,
   mesmo resultado). Da revisão independente: progresso salvo **mais curto** que a lista (pulada, como
   zeros: T1); curto de um membro e parcial de tamanho certo de outro depois (original, zera: T1b);
   **ore registrado no meio do laço** por uma tarefa de addon (a tarefa exata seguinte vai ao original:
   a lista viva é lida a cada decisão: T2); A, B, A no mesmo tick com B mexendo no inventário de A (T3);
   verificar com `consume`+`autoConsume` e o item presente (não pulável, nada comparado: T5); depois de
   desligado por erro, 20 ticks sem erro novo.
4. **Mutação no código-fonte** (`TesteMutacaoBQ`, JDK 8): cada condição é tirada ou estragada no próprio
   `FiltroInventario.java`/`GanchoBQ.java`, a classe mutante é compilada e posta na frente da do jar, e
   o diferencial (20 mil, semente 7) e os casos rodam. Controle: os fontes sem mutação, compilados do
   mesmo jeito, dão o **mesmo bytecode** do jar (instrução por instrução) e passam em tudo.
   **34 de 34 mutações exigidas acusadas** (entre elas: classe exata → instanceof; consume sem
   autoConsume; completa olhando outro membro; lista vazia; inventário nulo C5; quantidade ≤ 0; Item
   presente; lista de ore não vazia; **ore pelo nome (atalho não exato): 74 diferenças**; groupDetect;
   parcial (2.502); parcial com `v > 0` (1.415); conclusão satisfeita; requireOnlyOneItem; progresso só do
   PLAYER; presentes só do PLAYER; sem o PLAYER fora da lista (27); conjunto não refeito (58); cache sem
   a chave do `ParticipantInfo` (332); **HashMap no lugar do IdentityHashMap (607)**; entrada e fim do
   tick sem soltar o cache; verificar sem comparar o progresso; fim do laço sem o `clear()`; verificar
   pulando de verdade; desligado passando pelo filtro; e as 8 exigidas do revisor: R1 progresso de
   tamanho errado vira "pular" na hora (501 diferenças), R2 presentes ignoram pilhas de 1 (2.542), R3 uuid
   nulo pulado (4), R4 exigido sempre do item 0 (171), R5 `algum &= ok` (1.057), R6 gancho pula também
   `PASSA_CLASSE` (7.036), **R9 sem o teste de tamanho do progresso** (acusada: `ArrayIndexOutOfBounds`
   no nosso código, que antes da revisão passava sem ser vista), R10 progresso conferido mesmo com consume
   (caso de contador)). 3 mutações equivalentes relatadas: tirar o teste de `Item` nulo (conservador, o
   `StackMatch` com Item nulo só casaria pilha de Item nulo, que entra no conjunto); R7 reconstruir sem
   olhar `consume` (consume só tira itens: conjunto velho só tem Items a mais); R8 inventário nulo não
   interrompe a montagem (a marca `inventarioNulo` já fica).
   **Falha persistente** (revisão M2): um `FiltroInventario` mutante que sempre lança exceção no início
   (ou no fim) do laço, 20 ticks: **1 erro** contado em cada caso (o primeiro desliga e os ganchos param
   de chamar o filtro). Controle: o mesmo mutante com o `GanchoBQ` sem a volta antecipada conta 20.
5. **Bancada** (indicação, **não medição**: stubs sem o `delegate.get()` do Forge, sem a JIT de
   produção): 7.400 tarefas de 1–2 itens que nenhum inventário tem, 34 espaços ocupados: laço original
   517 ns/tarefa, com o filtro 52 ns/tarefa (JDK 8); 736 → 40 (JDK 21); mesmos resultados (melhor caso:
   todas as tarefas puláveis; varia de rodada a rodada, o revisor viu 1.051 → 46 e 753 → 38). A
   conferência mediu 12–17 % com reflexão; aqui o caminho é por `MethodHandle` e sem alocação.

## 7. O que NÃO foi verificado

- **Nada rodou no servidor de verdade** (esta faixa não liga servidor): nem a aplicação da correção no
  boot real, nem a convivência com o Mixin do BQRelief na prática (só no bytecode), nem os contadores.
- O ganho em produção (0,8–1,4 % é estimativa da conferência; a bancada usa stubs).
- O custo do filtro em produção (proporção só com stubs).
- Que a cadeia de transformadores da produção seja a mesma medida no boot da base (Hoshiestar 2º,
  Mixin 6º). Se o Mixin viesse antes, a trava do alvo provavelmente recusaria (bytes diferentes) e nada
  seria aplicado; o boot mostra a posição e o motivo.
- Que um bot de protocolo receba o `PlayerContainerListener` do BQ e que `/give`/`/clear` cheguem a
  `sendSlotContents` nos espaços 9 a 44 (esperado pelo código; do desenho).
- Que a produção carregou `AkashaQuests.json` e as faixas de inventário/grupo (herdado do desenho).
- O NullPointerException pré-existente de `consume`+`autoConsume` no jogo real (só com stub).
- Boot em Java 8 (a base também não testou).
- **Outros transformadores sobre as 8 classes travadas, em execução** (revisão M4): a trava confere os
  bytes do **arquivo** de cada dependência, não o que outro transformador faria com elas ao carregar. A
  varredura dos jars de `mods/` e `plugins/` (nomes das classes travadas em `.class`/`.json`) não achou
  nenhum mixin em `TaskRetrieval`, `Detector`, `ItemComparison`, `OreIngredient`, `TaskBase` ou
  `TaskProgressableBase` (só BQRelief, BukkitBridge em outro método, Forestry por reflexão e
  `chromatictooltipscompat`, tela do cliente). Coremods ASM genéricos (que mexem em qualquer classe, ex.:
  IDs estendidos) **não foram analisados um a um**. O risco é baixo (a regra só depende de identidade de
  `Item`, `stackSize`, listas e progresso) e a prova prática é o modo `verificar` com 0 divergências no
  teste de servidor (execuções C e C').
- Revisão independente (regra do projeto para código de concorrência): **feita** por outro agente,
  veredito "aprovado com ressalvas" (nenhum achado bloqueante nem importante; 6 menores, tratados na
  seção 9). O recurso não cria thread nem lock.

## 8. Plano de teste no modpack real (para o agente que liga o servidor)

Regras: cópia nova (`scripts/prepare-test-env.sh`), só o nosso `server.jar`, nenhum jar nosso em
`mods/`/`plugins/`; parar sempre com `echo stop >> console.in`; JFR, nunca spark. `C` = pasta da cópia.
Comparar estado do BQ entre execuções exige **partir do mesmo mundo** (cópia do mundo-base).

**Preparação**
1. Build com `--mods` e `test.sh` com `HOSHIE_MODS` passando (seção 6).
2. Cópia nova com o jar construído como `server.jar`. Bot op.
3. Escolher os itens (só lê o livro de quests; imprime só ids de itens, nenhum segredo):
   `python3 -I src/test/mods/bq/escolher_itens.py <C>/config/betterquesting/saved_quests/AkashaQuests.json`
   → **X** (bloco vanilla que nenhuma tarefa pede), **Y** (id pedido pelo maior número de tarefas sem
   ore; ~282 tarefas), **Z** (quest de uma só tarefa de retrieval, sem ore, sem NBT, sem consume, 2
   itens distintos, 2 a 16 de cada; anota o id da quest e os itens). Com `--so-contagens` só conta
   (conferido: 8 candidatos a X, 43 a Z).

**Boot 0 — mundo-base** (`modo: desligado`, padrão, e **todos os outros recursos desligados** no
`hoshiestar.yml`): no log, `recursos: ..., betterquesting=desligado` e **obrigatoriamente** a linha
"nenhuma correcao embutida ligada: transformador NAO registrado" (prova de que, desligado, nada toca a
classe; se ela não aparecer, conferir qual recurso ficou ligado antes de seguir). O `hoshiestar.yml` ganha a seção `betterquesting` com `modo: desligado` e `relatorio: 0`.
O bot entra; como op: `/bq_admin default load AkashaQuests` (pelo código, com o arquivo
`saved_quests/AkashaQuests.json` e sem a pasta `saved_quests/AkashaQuests/`, chama `loadLegacy`;
não verificado no servidor). Conferir erros no console. `save-all`, `stop`. Guardar uma cópia da pasta
do mundo como **mundo-base**.

**Roteiro fixo do bot** (mesmo atraso em todas as execuções, ex.: 4 comandos por segundo):
0. logo depois de entrar: `/clear <bot>` (zera o inventário inicial do pacote, senão itens de começo
   mudam as contagens de "item presente" entre execuções que deviam ser iguais), esperar 5 s,
   `hoshie status` (anotar);
1. 100 × (`/give <bot> X 1`, `/clear <bot> X`);
2. 50 × (`/give <bot> Y 1`, `/clear <bot> Y`);
3. **progresso parcial** (conferência C9): `/give` do 1º item de Z com metade da quantidade, esperar 5 s
   (o BQ grava o parcial), `/clear` dele, esperar 5 s (o original zera o parcial e marca sujo; com o
   filtro, a regra "parcial" manda para o original);
4. `/give` do conjunto Z completo, esperar 10 s (a tarefa completa), `/clear`;
5. `save-all`, esperar 5 s, `hoshie status`, `stop`.

Rodar `hoshie status` no console **depois de cada passo** (0 a 4) e anotar a linha: assim cada contador
fica ligado ao passo que o fez subir (ex.: `parcial` tem de subir no passo 3, não antes).

**Execução A — `modo: desligado`** a partir de uma cópia do mundo-base. Guardar a pasta
`betterquesting/` do mundo (progresso e grupos; o nome exato dos arquivos de progresso deste BQ não foi
verificado: comparar a pasta inteira).

**Execução B — `modo: ligado`**, `relatorio: 60`, cópia nova do mundo-base, mesmo roteiro. No boot:
"transformador das correcoes embutidas registrado"; quando o BQ carregar a classe:
`[Hoshiestar] [embutidos] betterquesting: filtro exato antes de ITaskInventory.onInventoryChange em
onServerTick [bq_standard.handlers.EventHandler]: aplicada`. Se aparecer `trava de versao nao confere`,
`trava de dependencia`, `dependencia ausente` ou `gancho ausente`: **parar e trazer a linha** (com o
"igual/DIFERENTE do arquivo do jar"). Conferir também a linha "cadeia de transformadores" (Hoshiestar
antes do Mixin) e que **não há erro de Mixin do BQRelief** (`StandardLoopMixin`, `akashic$shared`,
`akashic$inventory`, "InvalidInjectionException", "Critical injection failure") e que a linha de
estatísticas do BQRelief (a cada 300 s) continua aparecendo.
`hoshie status`: a linha começa com `modo ligado (correcao aplicada);` (se disser `NAO aplicada` ou
`aguardando` depois do bot entrar, parar e trazer a linha); `puladas` grande (quase tudo em `sem item`
nos passos 1 e 2), `parcial` > 0 (subindo no passo 3), `item presente` > 0 (passos 2 a 4), `erros 0`,
nada de `DESLIGADO POR ERRO` nem `REGRA 3 DESLIGADA`.
Opcional: repetir B com `relatorio: 0` e conferir a mesma pasta `betterquesting/` (o relatório é só log).

**Execução C — `modo: verificar`**, cópia nova do mundo-base, mesmo roteiro. `hoshie status`:
`comparadas` grande (milhares por evento dos passos 1 e 2), **`divergencias 0`**, `erros 0`, nenhuma
linha `verificar: DIVERGENCIA` no log.

**Execuções A' e C' — grupo e respawn de verdade** (revisão M5; um bot só não passa por
`ACTIVE_PLAYERS` com mais de um jogador, membro offline em `ALL_UUIDS` nem `PLAYER` velho depois de
respawn). Dois bots, **bot1** e **bot2**, no mesmo grupo do BQ (criar o grupo pelo menu de grupo do BQ ou
pelo comando de admin de grupo do BQ; o comando exato não foi verificado, anotar o que foi usado). A'
com `modo: desligado`, C' com `modo: verificar`, cada uma de cópia nova do mundo-base, mesmo roteiro e
mesmos atrasos:
1. `/clear bot1`, `/clear bot2`; `hoshie status`;
2. bot1 faz os passos 1 e 2 do roteiro acima;
3. ao mesmo tempo, bot2 recebe metade do 1º item de Z (`/give`), 5 s, `/clear`, 5 s (parcial de
   **outro membro** do grupo);
4. no meio do passo 2 de bot1: `/kill bot1`, esperar o respawn (o `PLAYER` guardado pelo BQ fica velho);
5. bot2 sai do servidor; bot1 faz o passo 4 (Z completo; agora bot2 é membro offline em `ALL_UUIDS`);
6. `save-all`, `hoshie status`, `stop`.
Exigido em C': `divergencias 0`, `comparadas` > 0, `erros 0`; e a pasta `betterquesting/` de C' igual à
de A' depois de normalizar carimbos de tempo.

**Passa** se: a pasta `betterquesting/` de A, B e C é igual depois de normalizar carimbos de tempo (a
tarefa da quest Z completa nas três, o parcial zerado nas três, nada mais mudou); A' igual a C'; C e C'
com 0 divergências e comparações > 0; B, C e C' sem erros nossos; BQRelief sem erro de Mixin.

**Medição antes/depois (JFR)**: execuções A' (`desligado`) e B' (`ligado`, `relatorio: 60`), só o passo
1 do roteiro com 600 eventos a 5 por segundo (120 s). No pid do servidor iniciado:
`jcmd <pid> JFR.start name=bq settings=profile filename=<C>/bq-<modo>.jfr duration=150s`
(ou parar com `jcmd <pid> JFR.stop name=bq`). Comparar a fração das amostras da thread do servidor com
`bq_standard.handlers.EventHandler.onServerTick` na pilha (`jfr print --events jdk.ExecutionSample
<arquivo> | grep`), e em B' a "media ... us por tick" do relatório. **Esperado**: com só X no inventário
quase todas as chamadas caem em `sem item` e o custo por checagem cai muito (o passeio pelas tarefas,
~1 % no perfil de produção, continua). Repetir com o passo 2 (Y, pior caso). Reportar números
relativos (a carga é sintética).

## 9. Desvios do desenho (e por quê)

- **Mecanismo**: Mixin → transformador do fork. `@Redirect` na chamada → "trocar chamada" (formato 2);
  `@Inject` em HEAD → "gancho no início" (formato 1); `@Inject` em RETURN → troca do único
  `HashSet.clear()` do fim do laço (o método tem dois `return`, e o gancho de fim só interessa na fase
  START, depois do laço). `@Accessor` (M2, M3) → `CampoPrivado.leitorExato` (`MethodHandle` em
  `static final`); a classe do mod não ganha método.
- **Padrão desligado** (o desenho dizia ligado por padrão): decisão do coordenador.
- **Chaves**: `modo` (desligado/verificar/ligado) no lugar de `inventoryPrefilter` +
  `inventoryPrefilterVerify`; `relatorio` com padrão 0 (o desenho dizia 300).
- **Trava de versão**: hash dos bytes (C3) no lugar da string de versão; `ParticipantInfo` acrescentada
  à lista. A conferência de "chamada no offset 196" virou "exatamente 1 chamada no método".
- **Modo verificar**: foto de completas, progresso (objeto e conteúdo) e inventários no lugar de
  `QuestCache.getDirtyQuests()` (que é `synchronized`, e só veria o efeito indireto).
- **Erro nosso desliga o recurso até reiniciar** (o desenho falava só em logar).
- **Relatório (C8)**: contadores por regra e por motivo, reconstruções e "média de µs por tick do laço";
  **não** há ns só da decisão (exigiria `System.nanoTime()` por chamada, milhares por tick).

**Depois da revisão independente** (6 achados, todos menores):
- M1 (progresso salvo mais curto que a lista sem teste): o diferencial passou a sortear também progresso
  com um item a menos; os casos T1/T1b do revisor entraram nos casos; a mutação R9 (tirar o teste de
  tamanho) agora é acusada.
- M2 (erro repetido a cada tick depois de "DESLIGADO POR ERRO"): os ganchos de início e fim do laço voltam
  logo com o recurso desligado; teste de falha persistente com mutante (1 erro em 20 ticks; controle sem
  a correção: 20).
- M3 (linha de estado dizia "modo ligado" com a correção recusada): a linha mostra o estado da correção.
- M4 (trava confere arquivo, não outros transformadores): registrado na seção 7 como não verificado.
- M5 e M6 (plano de servidor): execuções A'/C' com dois bots, grupo, respawn e membro offline;
  `/clear` no começo; `hoshie status` a cada passo; execução A com todos os recursos desligados e a linha
  "transformador NAO registrado" obrigatória; repetição opcional de B com `relatorio: 0`.

## 10. Para quem for mexer

- Mudou o BQ (outro jar): todas as travas recusam; refazer a leitura do código, os hashes e rodar o
  diferencial (0 diferenças exigido) antes de aceitar hashes novos.
- Mudou a regra: a mutação compara os fontes com o bytecode do jar e cada trecho trocado tem de existir
  exatamente uma vez; o teste diz qual mutação refazer.
- Contadores e modo ficam em `BetterQuesting` (classe sem Minecraft), para o status e os testes.
