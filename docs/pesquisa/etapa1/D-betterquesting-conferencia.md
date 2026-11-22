# 1.D BetterQuesting: conferência do desenho (revisor cético)

Arquivo conferido: `etapa1/desenho/D-betterquesting.md` (não foi editado).
Revisor: agente diferente do autor. Estudo só leitura: nenhum servidor iniciado, nenhum processo
java alheio tocado, nada baixado. Valores de configs do servidor não foram impressos (do arquivo de
quests só saíram contagens).

Caminhos usados:
- `S` = `/tmp/claude-0/-home-user-Hoshiestar/86b4be55-47d2-5167-8109-96ebc6889fd2/scratchpad`
- `PROF` = `S/anexos/spark/wIBxG0D4y9.json`
- `JAR` = `S/etapa1/D-conf/jar/` (BetterQuesting-3.8.79-GTNH.jar descompactado de novo por mim, para javap)
- `DEC` = `S/etapa1/decomp/D-betterquesting/bq/` (CFR do autor; conferi o código contra o bytecode nos pontos-chave)
- `HAR` = `S/etapa1/D-conf/harness/` (meu harness JVM, ver seção 4)

## Veredito

**Aprovado com correções.** A ideia (pular, no ponto da chamada, as checagens de `TaskRetrieval`
que comprovadamente não mudam nada) está certa e é exata: um harness com as classes reais do BQ
rodou 220 mil cenários aleatórios sem nenhuma diferença entre "original" e "com filtro", e o mesmo
harness pega as variantes quebradas da regra (teste de mutação). Nenhuma assinatura está errada.
As correções são: (a) o custo do próprio filtro está subestimado e o ganho líquido cai um pouco no
limite de baixo; (b) alguns detalhes de implementação precisam virar obrigatórios (conjunto por
identidade, limpar cache também no início do método, trava por hash em vez de "string de versão",
comportamento em inventário nulo); (c) os números de cobertura "top 30 / top 60" do autor somam
contagens em vez de contar tarefas distintas.

---

## 1. Assinaturas (javap refeito por mim em `JAR`)

Comando base: `/usr/lib/jvm/java-21-openjdk-amd64/bin/javap -p -s -cp JAR <classe>` e `-c` para o bytecode.

| Item do desenho | Resultado |
|---|---|
| `bq_standard.handlers.EventHandler#onServerTick(Lcpw/mods/fml/common/gameevent/TickEvent$ServerTickEvent;)V` | Existe, **de instância** (não static). Logo o handler do @Redirect também deve ser de instância: o desenho já usa `private void`, certo. |
| INVOKE `Lbq_standard/tasks/ITaskInventory;onInventoryChange(Ljava/util/Map$Entry;Lbetterquesting/api2/utils/ParticipantInfo;)V` | `invokeinterface` no offset 196, **único** no método (javap -c). Correto. |
| Assinatura do handler `(ITaskInventory, Map.Entry, ParticipantInfo)V` | Correta para @Redirect de chamada de interface em método de instância. |
| `@Inject @At("RETURN")` | O método tem **dois** `return` (offset 10, quando a fase não é START, e offset 228). `RETURN` injeta nos dois: inofensivo. Mas se uma exceção escapar (o `athrow` do offset 227), o RETURN não roda: ver correção C2. |
| `OreIngredient.ores` | `private final java.util.List ores`, `Ljava/util/List;`. Correto. |
| `TaskProgressableBase.userProgress` | `protected final java.util.TreeMap userProgress`, `Ljava/util/TreeMap;`. Correto. |
| `TaskRetrieval.requiredItems` / `consume` / `autoConsume` / `groupDetect` / `requireOnlyOneItem` | `public final List` / `public boolean` (`Z`). Corretos. |
| `TaskRetrieval.getUsersProgress(Ljava/util/UUID;)[I` | Existe (há também a ponte `(Ljava/util/UUID;)Ljava/lang/Object;`). Correto. |
| `TaskBase.isComplete(Ljava/util/UUID;)Z` | Correto (herdado por TaskRetrieval). |
| `TaskOptionalRetrieval extends TaskRetrieval` | Correto; só sobrescreve `getUnlocalisedName`, `getFactoryID`, `ignored(UUID)`. |
| `BigItemStack.stackSize` (`I`, public), `getBaseStack()`, `getOreIngredient()`, `getOreDict()` | Corretos. |
| `ParticipantInfo.PLAYER/UUID/ALL_UUIDS/ACTIVE_PLAYERS` | `public final`, descritores corretos. |
| `QuestCache.getDirtyQuests()Lcom/google/common/collect/ImmutableSet;` | Existe e é **synchronized** (só usado no modo verify; custo alto mas aceitável em teste). |
| `QuestCache.LOC_QUEST_CACHE` | `public static final ResourceLocation`. Correto. |
| `ItemStack.func_77973_b` (getItem) | No Crucible: `return this.delegate != null ? this.delegate.get() : null;` (`build/Crucible/eclipse/cauldron/src/main/java/net/minecraft/item/ItemStack.java` linha 135). Confirma o "pointer chase" do autor como código; que ele explique os 0,6% de tempo próprio continua interpretação. |
| `InventoryPlayer.func_70302_i_` | Crucible `InventoryPlayer.java` linha 636: `mainInventory.length + 4` = 40. Correto. |

**Quem mais transforma o mesmo método** (varredura de bytes em todos os jars de `mods/`, `mods/1.7.10/`
e `plugins/`, script `S/etapa1/D-conf/scan.py`):
- `Akashic-BQRelief-2.0.0`: `StandardLoopMixin` coloca **dois @Redirect no mesmo `onServerTick`**,
  em `ParticipantInfo.getSharedQuests()` (offset 82) e em `IQuestDatabase.filterKeys` (offset 85).
  Alvos diferentes do nosso (offset 196): não há conflito. `StandardInventoryTickMixin` (HEAD
  cancelável) só entra com `-Dakashic.bqrelief.inventoryInterval>1`; desligado em produção
  (`busca coalesce` = 0; a chave `akashic.bqrelief` não aparece nos metadados do perfil, checado só
  pelo nome da chave). Se for ligado, ele cancela o método inteiro antes do nosso redirect: só reduz
  a frequência, não quebra.
- `BukkitBridge-1.0.1` (`RewardCommandMixin`): outro método. `Forestry` (`ContainerWorktable`):
  reflexão em `onItemCrafted`. Ninguém mais referencia `TaskRetrieval`, `ItemComparison`,
  `OreIngredient`, `TaskProgressableBase` ou o evento `MarkDirtyPlayerEvent` (o único ouvinte é o
  próprio BQ, `betterquesting.handlers.EventHandler.onMarkDirtyPlayer`, que só marca jogadores
  para salvar).

**Erros de assinatura encontrados: nenhum.** Duas imprecisões de desenho (não de assinatura):
1. "O loader confere a string de versão do BQ (`3.8.79-GTNH`)": `ILateMixinLoader.getMixins(Set<String>)`
   recebe só os **ids** dos mods, não versões. Ler a versão por outro caminho nesse momento é
   "não verificado". Correção C3 troca por uma trava por hash dos bytes das classes.
2. O "padrão" citado (manifest `FMLCorePlugin`) não é o que o BQRelief usa: o manifest dele não tem
   `FMLCorePlugin`; é um @Mod comum com uma classe `@LateMixin` que implementa `ILateMixinLoader`
   e um JSON de mixins sem lista (as mixins vêm de `getMixins`). Tanto faz para o Hoshiestar-Core,
   que terá early mixins também, mas o desenho não deve dizer que BQRelief segue aquele padrão.

## 2. Números (refeitos com o leitor)

Comandos (de `/home/user/Hoshiestar`):
```
python3 -I tools/spark_report.py PROF eventos --top 15
python3 -I tools/spark_report.py PROF arvore --raiz EventHandler_onServerTick_ServerTickEvent --prof 3 --min 0.01
python3 -I tools/spark_report.py PROF arvore --raiz bq_standard.handlers.EventHandler.onServerTick --prof 14 --min 0.02
python3 -I tools/spark_report.py PROF busca TaskRetrieval | busca TaskBase.isComplete | busca "Detector.<init>"
python3 -I tools/spark_report.py PROF busca SimpleDatabase.getEntries | busca bqrelief | busca coalesce | busca TaskFluid
```

| Número | Autor | Conferido |
|---|---:|---:|
| `EventHandler_onServerTick_ServerTickEvent` | 4,62% | 4,62% (27.676 ms) = 2,95% (bq_standard, START) + 1,66% (núcleo, END) |
| `TaskRetrieval.onInventoryChange` sob o handler | 1,85% | 1,85% (11.088 ms) |
| `forEachOrdered` (varredura dos slots) | 1,18% | 1,18% |
| `Detector.<init>` | 0,45% | 0,45% |
| `TaskBase.isComplete` sob detect | 0,19% | 0,19% (0,20% somando todos os chamadores) |
| `SimpleDatabase.getEntries` sob o handler | 0,36% | 0,36% (0,39% em todo o thread) |
| `bqrelief` (helpers) | 0,10% | 0,10% |
| `coalesce` | 0 | 0 |

Parte removível: o filtro tira `forEachOrdered` (1,18) e `Detector.<init>` (0,45) das tarefas
puladas; `isComplete` (0,19) continua (o filtro o chama). 1,85 − 0,19 = **1,66%**: confere. O passeio
pelas tarefas (próprio do handler 0,5%, `getEntries` 0,36%, iteradores ~0,15%) fica: confere.

**Correção 1: cobertura por item.** O autor diz "os 30 (60) ids mais pedidos cobrem 1.576 (2.098)
tarefas, 20% (27%)". Isso é a soma das contagens de cada id (uma tarefa com dois desses ids conta
duas vezes). Contando tarefas distintas (script `S/etapa1/D-conf/stats.py`, só contagens, nível de
`Item`, que é o que o filtro compara por identidade):

| Inventário contém | tarefas que passam | custo que passa (ponderado por nº de itens exigidos) |
|---|---:|---:|
| os 10 Items mais pedidos | 11,1% | 12,1% |
| os 30 mais pedidos | 16,2% (1.244) | 21,3% |
| os 60 mais pedidos | 22,7% (1.744) | 27,5% |
| os 100 mais pedidos (grupo grande, pior caso) | 28,3% | 32,1% |
| 30 Items aleatórios entre os pedidos | — | 4,2% |

(script `S/etapa1/D-conf/weighted.py`). Mais as 279 tarefas com ore dict, que sempre passam. Então a
fração de custo pulada fica entre ~68% (grupo grande carregando o "mais pedido") e ~96%. A faixa do
autor (70–95%) se mantém; o argumento dele estava mal contado, a conclusão não.

**Correção 2: custo do próprio filtro.** O autor estima 0,10–0,15%. Medi com o harness
(`hs.PrefilterBench`, 7.400 tarefas de 1–2 itens, 34 slots ocupados): original 730–1.330 ns por
tarefa, filtro 100–155 ns por tarefa com 99% de pulos, ou seja **~12–17% do custo original por
tarefa**. Os stubs não têm o `delegate.get()` do Forge e usam `Field.get` (reflexão) em vez de
@Accessor, então os ns absolutos não valem para produção; a proporção é a melhor estimativa que
tenho: 0,12–0,17 × 1,85% ≈ **0,2–0,3% do thread** (não verificado em produção). A maior parte é
`getUsersProgress` (busca no TreeMap e, para quem não tem progresso, um `new int[]` por tarefa) mais
`isComplete` (outra busca em árvore). A correção C4 tira a alocação.

**Ganho líquido corrigido:** 1,66 × (0,68 a 0,96) − (0,15 a 0,3) ≈ **0,8% a 1,4% do thread**
(≈ 0,7 a 1,2 ms/tick a 84,2 ms/tick). O autor dizia 0,9–1,4%. Pequena correção no limite de baixo;
o item continua valendo a pena. O 1,66% do núcleo (onLivingUpdate/updateCache) não muda.

## 3. Segurança: tentativas de quebrar

Li `TaskRetrieval.detect`, `Detector.<init>`, `Detector.run`, `checkAndComplete`,
`ItemComparison.StackMatch`/`OreDictionaryMatch`, `OreIngredient`, `BigItemStack`, `TaskBase`,
`TaskProgressableBase`, `ParticipantInfo`, `DirtyPlayerMarker` (CFR em `DEC`, conferido com javap).
Cada tentativa abaixo foi pensada para fazer o filtro pular uma chamada que **não** era um no-op.

| Sequência tentada | Resultado |
|---|---|
| Jogador junta 5 de 10 itens (progresso parcial salvo) e depois guarda no baú. | O original zera o parcial e marca sujo. A regra exige "nenhum `0 < p < req`", então passa. **Seguro.** (mutante sem essa condição: 2.227 diferenças) |
| Tarefa `groupDetect` com progresso ≠ 0. | Pegadinha real: `Detector.<init>` faz `Arrays.fill(..., 0)` **direto no array salvo**, mesmo com `updated=false`, ou seja, escreve no dado salvo sem passar por `setBulkProgress`. A regra exige tudo zero, então passa. **Seguro.** (mutante: 1.443 diferenças). No pack todas as 7.691 tarefas têm `groupDetect=0`. |
| Progresso já satisfaz a conclusão mas a tarefa não está completa (para algum membro). | Regra exige "não satisfaz"; passa. **Seguro.** (mutante: 1.864 diferenças) |
| Respawn: o `EntityPlayer` em `playerInventoryUpdates` é o objeto velho, diferente do que está em `ACTIVE_PLAYERS`. | `present` usa a união `ACTIVE_PLAYERS + PLAYER`, superconjunto do que o Detector varre. **Seguro.** (mutante só com PLAYER: 1.107 diferenças) |
| Algum mod registra itens no nome de ore `""`. | Então `NO_ORE` passa a casar itens. Com o @Accessor (lista viva) não pula. Atalho "olhar só `getOreDict().isEmpty()`" **não é exato** (mutante: 61 diferenças). Manter M2 (ou ver alternativa na seção 5). |
| Item com `equals`/`hashCode` sobrescrito ou que muda com o tempo. | Com `HashSet<Item>` um `hashCode` instável faz `contains` dar falso para um Item presente: **pulo errado**. Com conjunto por identidade, impossível. O desenho diz "(identity)": tornar obrigatório (C1). |
| Progresso negativo (NBT corrompido) ou com tamanho errado. | Negativo: Detector zera e marca sujo; a regra `p == 0 \|\| p >= req` passa. Tamanho errado: `getUsersProgress` devolve zeros novos, o original não grava nada; pular é equivalente. **Seguro**, desde que a implementação use exatamente `p == 0 \|\| p >= req` (não `p <= 0`). |
| Requisito com `stackSize <= 0`, lista vazia, entrada nula, item nulo. | A regra não pula nenhum desses; o original roda, inclusive as exceções dele. **Seguro.** |
| Chamada que passa e muda o inventário (TaskFluid troca recipientes; retrieval com consume). | Desenho reconstrói `present` depois de qualquer passagem que não seja `TaskRetrieval` simples sem consume. Consumir só remove itens (deixa `present` "a mais", o que é seguro: mutante sem reconstrução deu 0 diferenças); TaskFluid pode **acrescentar** Items, então a reconstrução é necessária para ela. **Seguro.** |
| Edição de quest / reload do banco / mudança de dimensão / unload. | O filtro não guarda nada sobre tarefas; lê `requiredItems`, flags, progresso e a lista de ore ao vivo a cada decisão. O único cache (`present` por `ParticipantInfo`) vive dentro de uma chamada de `onServerTick`. **Seguro**, com C2 para o caso de exceção. |
| Exceção no meio do laço. | O harness compara também a classe da exceção: iguais em todos os 735 cenários com exceção. O filtro não pode lançar antes do original: inventário nulo de um membro faria o filtro estourar onde o original só estouraria mais tarde (C5). |
| Travamento / atraso no desligamento. | Sem locks novos, sem threads; roda dentro do `synchronized` do próprio BQ, na thread do servidor. Nada a esperar. **Seguro.** |
| Itens/energia/fluidos criados ou perdidos. | O filtro só lê (`func_70302_i_`, `func_70301_a`, `func_77973_b`, `field_77994_a`); nunca chama `func_70298_a`. **Seguro.** |
| Quest completa duas vezes ou nunca; atraso. | Pulos só acontecem quando nenhum item exigido está nos inventários varridos; nesse caso o original não soma nada e não completa. Chamadas que podem completar rodam no mesmo tick, mesma ordem. **Nada visível ao jogador.** |
| "Desligado = original". | Verdadeiro **por construção** se o loader não registrar M1/M2/M3 quando `inventoryPrefilter=false` (bytecode = BQ + BQRelief). O modo verify sempre roda o original. Precisa reiniciar para trocar: correto e declarado. |

Achado à parte (não causado pelo filtro, pré-existente no BQ): numa tarefa `consume && autoConsume`
com dois requisitos que casam o mesmo slot, `Detector.run` pode receber `null` de
`func_70298_a` (slot já esvaziado) e dar NullPointerException (`TaskRetrieval$Detector.lambda$run$1`).
Visto no harness com stub que imita o vanilla (devolve `null` para slot vazio); no jogo real é
"não verificado". No pack não importa: 0 tarefas de retrieval com `consume`.

## 4. Harness (prova)

- Fonte: `HAR/src/hs/PrefilterHarness.java`, `HAR/src/hs/PrefilterBench.java`; stubs de
  Minecraft/Forge com nomes SRG em `HAR/stub/` (só os membros que as classes do BQ usam, lista
  tirada do constant pool via `javap -v`). As classes do BQ são as **reais** do jar
  (`TaskRetrieval`, `Detector`, `ItemComparison`, `BigItemStack`, `OreIngredient`, `TaskBase`,
  `TaskProgressableBase`, `ParticipantInfo`, `DirtyPlayerMarker`, `MarkDirtyPlayerEvent`, `Tuple2`).
- O que faz: monta o mesmo cenário aleatório duas vezes (mesma semente): grupo de 1–3 jogadores
  (online/offline, às vezes objeto "velho" de respawn), inventários de 40 slots (inclui tamanho 0,
  negativo, item nulo), 1–8 tarefas (às vezes `TaskOptionalRetrieval`), flags aleatórias, requisitos
  com dano/curinga 32767/ore dict/tamanhos 0 e −1, progresso salvo ausente/parcial/completo/negativo/
  tamanho errado, `completeUsers` aleatório, e às vezes itens registrados no ore `""`. Roda o laço
  original numa cópia e o laço com a regra do desenho (seção 5.1, literal, `present` preguiçoso,
  reconstrução após passagem não-simples) na outra. Compara: `completeUsers`, `userProgress`
  (chaves e conteúdo), inventários slot a slot, a sequência de eventos `MarkDirtyPlayerEvent`, o
  número de chamadas a `markDirtyParty` por jogador e a exceção.
- Como rodar (de `S/etapa1`):
  `env -u _JAVA_OPTIONS /usr/lib/jvm/java-8-openjdk-amd64/bin/java -noverify -cp D-conf/harness/out:D-conf/harness/stubout:D-conf/jar hs.PrefilterHarness 200000 3`
- Resultados:
  - semente 3: 200.000 cenários, 898.548 chamadas, 331.269 puladas (2a 104.725, 2b 122.158,
    3 104.386), **0 diferenças**;
  - semente 1: 20.000 cenários, **0 diferenças**; semente 7: 20.000, **0 diferenças**.
  - Mutantes (`-Dmut=...`, semente 7, 20.000 cenários): `nopartial` 2.227, `nogroup` 1.443,
    `nocomplete` 1.864, `onlyplayer` 1.107, `oredictstring` 61, `norebuild` 0. Ou seja, o harness
    detecta cada condição da regra que for removida; a reconstrução após consume é redundante
    (mas barata, manter).
- Limites: Minecraft/Forge são stubs (sem NBT real: `ignoreNBT=false` só foi exercitado com tags
  nulas; sem `delegate` do Forge). A regra não depende de NBT (ela exige `Item` ausente, e NBT só é
  comparado depois de o `Item` bater), então isso não enfraquece a prova da exatidão.

## 5. Alternativas

1. **Só config (BQRelief `-Dakashic.bqrelief.inventoryInterval=N`)**: muda comportamento. A checagem
   roda N−1 ticks depois, e um item segurado por menos tempo que isso (pegou e guardou) pode não
   contar mais: jogador percebe. Ganho desconhecido. **Não recomendo** no Degrau 0 (concordo com o autor;
   conferi `Entity.equals` por `entityId`, Crucible `Entity.java` linha 258, e a cópia do id no
   respawn, `ServerConfigurationManager.java` linha 752).
2. **`lockedProgress=0` no livro de quests**: maior ganho possível (encolhe o conjunto "ativo" e
   ajuda também o 1,66% do núcleo), mas muda o jogo (itens deixam de contar para quests trancadas).
   Decisão do admin, não do Degrau 0.
3. **Não fazer**: deixa ~0,8–1,4% na mesa. O filtro é pequeno, exato e tem chave; não há motivo.
4. **Menos superfície de mixin**: M2 pode ser trocado por API pública:
   pular só se `r.getOreDict().isEmpty()` **e** `OreDictionary.getOres("").isEmpty()` (lido uma vez
   por chamada de `onServerTick`). É exato porque em `BigItemStack` todo caminho que deixa `oreDict`
   vazio põe `oreIng = NO_ORE` (construtores, `setOreDict`, `readFromNBT`; `copy()` só erra para o
   lado conservador), e nenhum outro jar referencia `OreIngredient`. Perde o pulo de tarefas com nome
   de ore cuja lista está vazia. Mas @Accessor é rotina neste pack (20 jars usam, vários em late
   mixins, por ex. `chromatifixes`, `WitchingGadgets`, `hodgepodge`; script `S/etapa1/D-conf/acc.py`),
   então **manter M2** é aceitável. Fica a critério de quem implementar; o harness cobre as duas formas.
5. **Injetar em `TaskRetrieval.detect` em vez do redirect**: pior. `detect` tem outro chamador
   (`QuestInstance.detect`, linha 158 do CFR, disparado pelo pacote de ação do cliente em
   `NetQuestAction` linha 103); o redirect no ponto da chamada é mais estreito. Manter o redirect.

---

## 6. Desenho corrigido (só o que muda; o resto do desenho do autor vale)

**C1. Conjunto `present` por identidade, obrigatório.** `Collections.newSetFromMap(new IdentityHashMap<>())`
(ou um open-hash por referência). Nunca `HashSet<Item>`.

**C2. Cache só dentro de uma chamada, com chave `==`.** Guardar `lastInfo` e `present`; usar só se
`pInfo == lastInfo`. Limpar no `@Inject` em **HEAD** de `onServerTick` (novo) e em RETURN. Assim uma
exceção que pule o RETURN não deixa referência a jogador viva por mais de um tick.

**C3. Trava por hash em vez de "string de versão".** No `ILateMixinLoader.getMixins`: exigir
`betterquesting` e `bq_standard` no conjunto de mods; ler por `getResourceAsStream` os bytes
**originais** (antes de qualquer transformação) de `bq_standard/tasks/TaskRetrieval`,
`bq_standard/tasks/TaskRetrieval$Detector`, `bq_standard/tasks/base/TaskBase`,
`bq_standard/tasks/base/TaskProgressableBase`, `betterquesting/api/utils/ItemComparison`,
`betterquesting/api/utils/BigItemStack`, `betterquesting/api2/utils/OreIngredient` e
`bq_standard/handlers/EventHandler`, e comparar SHA-256 com os valores do jar 3.8.79-GTNH (calcular
na hora de construir). Qualquer diferença: não registrar nada e logar. A exatidão depende do código
**exato** dessas classes; o hash garante isso melhor que uma string. Manter também a checagem por
`ClassReader` da chamada no offset do `onServerTick`.

**C4. Progresso sem alocar.** Usar o accessor de `userProgress` (M3 passa a valer também fora do
modo verify): `int[] a = (int[]) userProgress.get(u)`; se `a == null || a.length != requiredItems.size()`,
tratar como "tudo zero" (é exatamente o que `getUsersProgress` devolve); senão aplicar
`p == 0 || p >= req` (sem groupDetect), `p == 0` (com groupDetect, só se `!consume`) e o teste de
conclusão. Ordem barata: classe exata → `consume && !autoConsume` → `isComplete` → itens (identidade,
ore) → progresso. Se o accessor falhar: cair para `getUsersProgress` (correto, só mais caro).

**C5. Inventário nulo.** Ao montar `present`, se `PLAYER.field_71071_by` ou o de algum membro de
`ACTIVE_PLAYERS` for nulo, marcar "não pular nada" para esse `ParticipantInfo` (o original segue,
com o comportamento e a exceção dele).

**C6. `require` do redirect.** `require = 1, allow = 1`. Se outro mod um dia redirecionar a mesma
chamada, o Mixin acusa conflito; com `require = 1` isso vira erro na carga. Como o GTNH Mixin
0.8.5 trata `require` dentro de config `"required": false` é **não verificado**: testar no servidor
de teste, com um alvo propositalmente errado, se o resultado é falha clara na partida ou só log. O
aceitável é "falha clara" ou "log claro + mixin ausente" (nesse caso roda o original). Nunca meio aplicado.

**C7. Ganho esperado (substitui a seção 7 do autor):** 0,8% a 1,4% do thread (≈ 0,7–1,2 ms/tick);
custo do filtro estimado em 0,15–0,3% (medido em proporção com stubs, não em produção). O 1,66% do
núcleo não muda.

**C8. Relatório.** Além dos contadores do autor: reconstruções de `present`, passagens por motivo
(classe, ore, item presente, parcial, satisfeito, inventário nulo), e ns médios só da decisão do filtro.

**C9. Plano de teste.** Antes do servidor: rodar o harness `HAR` contra o jar que vai para produção
(0 diferenças exigido; refazer se o BQ mudar). No servidor de teste: o plano A/B/C do autor vale
como está; acrescentar no roteiro um item com **progresso parcial** (dar metade de Z, depois `/clear`)
para exercitar o reset, que é o caso mais delicado. Medição com JFR como no autor.

## 7. Não verificado (do revisor)

- Custo do filtro em produção (só a proporção medida com stubs).
- Comportamento de `require` em config não obrigatória no GTNH Mixin 0.8.5 (C6).
- Que `@Accessor(remap=false)` aplique nestes dois alvos específicos (o padrão é comum no pack, mas
  não rodei).
- Que produção usou `AkashaQuests.json` e as faixas de inventário/grupo (herdado do autor).
- O NPE de consume+autoConsume no jogo real (só visto com stub).
