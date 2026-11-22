# 1.C Mekanism (matriz de indução): conferência do desenho

Conferente: agente diferente do investigador. Só leitura e estudo; nenhum código do mod foi escrito.
Arquivo conferido: `etapa1/desenho/C-mekanism.md` (não foi alterado).

**Veredito: aprovado com correções.** A ideia central está certa e é a mais segura que existe aqui:
guardar só "qual objeto de célula está em cada coordenada" e ler a energia ao vivo, em vez de guardar um
total. Os nomes e assinaturas estão todos certos. Os números do perfil batem. Mas o desenho precisa de
quatro correções antes de virar código (seção 5), e o ganho "mais provável ~7 pontos" é otimista
(mais realista: 5,5 a 7 pontos).

Caminhos usados:
- `$S` = `/tmp/claude-0/-home-user-Hoshiestar/86b4be55-47d2-5167-8109-96ebc6889fd2/scratchpad`
- `$J` = `$S/anexos/spark/wIBxG0D4y9.json`
- jar real do Mekanism: `$S/server-test3/mods/Mekanism-1.7.10-9.10.48-AkashicPerf.3-ALL.jar`
- fontes do Crucible: `$S/build/Crucible/eclipse/cauldron/src/main/java` (abaixo: `CRU/`)
- meu harness: `$S/etapa1/C-conf-harness/src/hs/ConfAdv.java` (usa as classes do harness do investigador)
- descompilados meus: `$S/etapa1/decomp/C-mekanism-conf/` (throttle, firewall, hodge, bellow, f150816)

Termos: *tile entity (TE)* = objeto Java que guarda os dados de um bloco especial (aqui, cada célula
guarda sua energia). *Mixin* = remendo aplicado no bytecode de outra classe na hora de carregar.
*Cache* = guardar um resultado para não recalcular.

---

## 1. Assinaturas (javap no jar que carrega)

Comando base: `javap -p -s -c -cp <jar> <classe>` (JDK 21, com `env -u _JAVA_OPTIONS -u JAVA_TOOL_OPTIONS`).

| Item do desenho | Conferido | Resultado |
|---|---|---|
| `SynchronizedMatrixData.getEnergy (Lnet/minecraft/world/World;)D`, public, não estático | javap no jar do Mekanism | certo |
| `SynchronizedMatrixData.setEnergy (Lnet/minecraft/world/World;D)V`, public | idem | certo |
| `SynchronizedMatrixData.cells Ljava/util/Set;` public; criado `new HashSet()` no construtor | `javap -c`, construtor | certo; único `putfield cells` é o construtor; `MatrixUpdateProtocol` só faz `getfield` + `add` |
| corpo de getEnergy/setEnergy = laço em `cells` + `Coord4D.getTileEntity` + `instanceof TileEntityInductionCell` | `javap -c` | certo, idêntico ao descompilado do desenho |
| `TileEntityInductionCell.getEnergy ()D` (lê `electricityStored`), `setEnergy (D)V` (= `min(e, getMaxEnergy())`), `getMaxEnergy ()D` (= `tier.maxEnergy`), `canUpdate ()Z` = false | `javap -c` | certo |
| `Coord4D.getTileEntity (Lnet/minecraft/world/IBlockAccess;)Lnet/minecraft/tileentity/TileEntity;` = `exists(world)` → `IBlockAccess.func_147438_o` | `javap -c` | certo; `exists` chama `World.func_72863_F` e `IChunkProvider.func_73149_a(x>>4, z>>4)` |
| `TileEntity.func_145837_r ()Z`, `func_145831_w ()Lnet/minecraft/world/World;`, `field_145851_c/_145848_d/_145849_e I` | javap `$S/medidor-work/srg.jar` | certo |
| `World.field_72995_K Z`, `func_82737_E ()J`, `func_72863_F`, `func_72964_e (II)Lnet/minecraft/world/chunk/Chunk;`, `func_147438_o (III)` | idem | certo |
| `Chunk.field_76636_d Z`, `IChunkProvider.func_73149_a (II)Z` | idem | certo |
| `com.gtnewhorizon.gtnhmixins.ILateMixinLoader`: `getMixinConfig()Ljava/lang/String;`, `getMixins(Ljava/util/Set;)Ljava/util/List;` | javap no `+unimixins-all-1.7.10-0.3.1.jar` | certo (o jar também tem a cópia `io.github.tox1cozz.mixinbooterlegacy`; usar a `gtnhmixins`, como o pack) |
| `cpw.mods.fml.common.gameevent.TickEvent$ServerTickEvent` | javap `server.jar` | existe |

**Nenhum erro de nome, descritor, acesso ou "estático".**

Quem mais mexe no mesmo lugar (transformadores/mixins):
- Varri todos os jars de `mods/` e `plugins/` e o `server.jar` atrás de `SynchronizedMatrix`,
  `content/matrix`, `TileEntityInduction`, `Coord4D`, `CableUtils`, `ChargeUtils`: só o próprio
  Mekanism, o `Akashic-MekThrottle` (alvos: `TileEntityFormulaicAssemblicator`,
  `CommonWorldTickHandler`, `MultiblockManager`, `TileEntityBasicBlock`, `TileEntityMultiblock`;
  `MekThrottleTransformer.java:59-63`) e referências inofensivas a `IStrictEnergyAcceptor`/`Coord4D`
  em Galacticraft, OpenComputers, AE2 e gendustry. **Ninguém transforma `SynchronizedMatrixData`.**
  Não há risco de dois mods injetarem no mesmo ponto.
- Mixins no caminho da busca (`World.getTileEntity`, `Chunk.func_150812_a`, `chunkExists`):
  - AkashicBellow `MixinWorld_Deep`: só troca o limite `y >= 0` de `getTileEntity` (mundos fundos);
  - hodgepodge `MixinChunk`: só checa se as coordenadas da TE cabem no chunk (lança erro ou cancela se não cabem).
  Nenhum dos dois muda qual TE é devolvida para y entre 0 e 255. O desenho já manda y fora de 0-255
  para o caminho original.
- O tick das TEs passa por `com.akashic.worldfirewall.WorldMutationGuard.runTileEntityUpdate`
  (aparece nas cadeias do perfil). Li o descompilado: ele só bloqueia tick de TE e `setBlock` por
  política de mundo. Não remove TE do mapa do chunk. Sem efeito no desenho.

Observação sobre o perfil: os quadros `HashSet.iterator → LinkedHashMap.keySet` sob `getEnergy`
pareceriam indicar um conjunto ligado (outra classe). Não é: o iterador que aparece é
`java.util.HashMap$KeyIterator.next`, que é o de um `HashSet` comum, e o padrão aparece no servidor
inteiro (`busca HashMap.keySet`: 0,54% `LinkedHashMap.keySet` contra 0,00% `HashMap.keySet`). É
artefato de atribuição do amostrador. A ordem de iteração também não importa para a correção, porque o
desenho grava a ordem do próprio conjunto (que nunca muda depois de formado).

---

## 2. Números (recalculados com o leitor)

Comandos (de `/home/user/Hoshiestar`):
```
python3 -I tools/spark_report.py $J busca SynchronizedMatrixData
python3 -I tools/spark_report.py $J arvore --raiz SynchronizedMatrixData --min 0.05 --prof 12
python3 $S/etapa1/C-tools/callers.py $J SynchronizedMatrixData 6
```

| Medida | Desenho | Conferido |
|---|---:|---:|
| `SynchronizedMatrixData` total | 8,27% | **8,27%** (49,5 s de 598,9 s) |
| `getEnergy` / `setEnergy` | 7,02 / 1,24 | **7,02 / 1,24** |
| chamadores de getEnergy: Uncomplication / porta direta / porta `CableUtils.emit` / entangloporter / `ChargeUtils.discharge` | 2,24 / 2,05 / 1,03 / 1,03 / 0,68 | **iguais** |
| chamadores de setEnergy: entangloporter / porta emit / discharge | 0,57 / 0,45 / 0,22 | **iguais** |
| `Coord4D.getTileEntity` dentro de getEnergy | 5,43 | **5,43** (1,72+1,62+0,78+0,79+0,52) |

1% = 0,842 ms por tick (84,2 ms/tick). O tempo quente está mesmo em `Coord4D.getTileEntity` +
iteração, que é o que o desenho tira das leituras rápidas. Não há "callee que o conserto não remove",
exceto: (a) a 1ª leitura de cada tick por estrutura (reconstrução), que continua custando uma leitura
original inteira; (b) o laço rápido em si.

**Ganho: o "mais provável ~7 pontos" é otimista.** O fator *f* (custo de uma leitura rápida dividido
pelo de uma original) foi medido no harness com tudo no cache do processador (4096 células cabem no
L2/L3). No servidor real (heap de 10 GB, TEs espalhadas na memória) o laço rápido ainda visita cada
objeto de célula, e cada visita tende a ser uma falta de cache; a busca original visita a célula **e**
mais 4-5 objetos (nó do HashSet, Coord4D, nó do HashMap do chunk, ChunkPosition). Então *f* real fica
provavelmente entre 0,05 e 0,25 (**não verificado**). Recalculando com a fórmula do próprio desenho,
R = (1 + (G-1+S)·f)/(G+S):

| cenário | f=0,05 | f=0,15 | f=0,25 |
|---|---:|---:|---:|
| 1 matriz, G≈31 leituras + S≈5,4 escritas/tick | 7,6 pts | 6,8 pts | 6,0 pts |
| várias matrizes, G≈8, S≈1,4 cada | 7,0 | 6,3 | 5,5 |
| pessimista, G≈3, S≈0,5 | 5,6 | 5,0 | 4,4 |

**Faixa: 4,4 a 7,6 pontos (3,7 a 6,4 ms/tick); centro realista 5,5 a 7 pontos.** O teto do desenho
(4,5-7,5) está bem; o "mais provável" deve virar "5,5 a 7". Com 6 ms a menos, 84,2 → ~78 ms
(~12,8 TPS) só com este item. A contagem de ~31 leituras/tick segue sendo inferência (**não
verificado**), coerente com os números: 3 leituras do discharge (0,68%) → 0,23% por leitura; IC2
2,24/0,23 ≈ 10 leituras ≈ 2 por porta em ~5 portas.

---

## 3. Segurança (tentativas de quebrar)

### 3.1 O que resistiu
Conferi no código-fonte do Crucible os pilares do desenho:
- `Chunk.func_150812_a` invalida a TE anterior antes do `put` (`CRU/net/minecraft/world/chunk/Chunk.java:973-1003`);
  `removeTileEntity` remove e invalida (`:1018-1031`, só se `isChunkLoaded`; se o chunk não está
  carregado, a checagem de chunk do desenho já reprova); `onChunkUnload` põe `isChunkLoaded=false` (`:1055`).
- `func_150806_e` devolve exatamente o objeto do mapa se ele não estiver inválido (`Chunk.java:932-958`).
- `World.getTileEntity` (`CRU/net/minecraft/world/World.java:3149-3211`): a varredura de
  `addedTileEntityList` só acha uma TE diferente da do mapa se alguém chamou `setTileEntity`, e
  `setTileEntity` (`:3213-3251`) sempre termina em `chunk.func_150812_a`, que invalida a antiga. Células
  têm `canUpdate()=false`, então nem entram nessa lista. A captura de árvore do Crucible (R4) só
  afeta posições que a árvore tocou; células ficam dentro da moldura.
- Valores lidos ao vivo: várias leituras no mesmo tick (portas, Uncomplication, entangloporter,
  GUI) veem o mesmo que o original. Nada é salvo; formar/quebrar cria outro objeto de estrutura.
- Thread: confirmei no fonte que o evento `ServerTickEvent` START sai de `MinecraftServer.tick()`
  (`CRU/net/minecraft/server/MinecraftServer.java:829`), chamado no laço `run()` (`:692`) da thread
  `primaryThread = new Thread(this, "Server thread")` (`:227/287`). O item "não verificado" do desenho
  sobre isso pode cair (verificado no fonte; a classe é binpatched, então sem javap).
- Desligar: com `matrixEnergyFastPath=false` na partida o mixin não é registrado: bytecode do jar,
  comportamento original exato. Desligado em tempo de execução, o mixin continua alocando um
  `CallbackInfoReturnable` por chamada, mas o resultado é idêntico.
- Desligamento do servidor: não há thread, trava nem fila nova. Não atrasa o `stop`.
- Rodei de novo o harness do investigador: `diff 7 20000 8 std 1` → 0 diferenças, 0 leituras
  rápidas erradas; `scenario raw` diverge no mesmo tick e não diverge com um tick no meio. Reproduz.

### 3.2 R1 é menor do que o desenho temia
Varri o bytecode de todas as 54 classes do pack que usam `Chunk.field_150816_i` (o mapa de TEs do
chunk) atrás de `remove`/`put`/`clear` no mapa (lista em `$S/etapa1/conf-C-f150816.txt`, classes
extraídas em `$S/etapa1/decomp/C-mekanism-conf/f150816/`). Quem tira TE do mapa sem invalidar:
- AE2 `appeng/spatial/CachedPlane` (Spatial IO): troca mundo e coordenadas da TE, então a checagem pega;
- MrTJPCore `WorldLib.uncheckedRemoveTileEntity/uncheckedSetTileEntity`: **nenhum jar do pack chama**
  (Project Red não está no pack);
- AkashicBellow `selftest/CompatProbe.forceAir` e immersivepetroleum `diagnostics/MachineProbeFixtures`:
  sondas de autoteste/diagnóstico;
- ForgeMultipart `MultipartSaveLoad.loadTiles`: só no carregamento do chunk, só para tiles de multipart.

Os demais só leem o mapa. R1 continua existindo (é teórico), mas não encontrei um caminho normal de
jogo que o dispare.

### 3.3 Problemas encontrados (precisam de correção)

**S1. O modo de teste `matrixVerify` compara somas e é cego justamente para R1. Demonstrado.**
Uma célula órfã (fora do mapa, não invalidada) com 0 J não muda a soma, então a verificação passa.
Mas no mesmo tick o `setEnergy` rápido despeja energia nela, ou seja, a energia sai do mundo.
Harness `ConfAdv` (matriz 4×4×4, células reais do Mekanism):
```
cd $S/etapa1
CP="$S/etapa1/C-tools/harness/classes:$S/etapa1/decomp/C-mekanism/raw:$S/medidor-work/srg.jar:$S/server-test3/mods/industrialcraft-2-2.2.828a-experimental.jar:$S/server-test3/libraries/net/minecraft/server/1.7.10/server-1.7.10.jar:$S/server-test3/server.jar"
env -u _JAVA_OPTIONS -u JAVA_TOOL_OPTIONS /usr/lib/jvm/java-8-openjdk-amd64/bin/java -cp "$S/etapa1/C-conf-harness/classes:$CP" hs.ConfAdv
```
Saída:
```
read A=3.989337e+12 B=3.989337e+12 | sum-verify passes=true | identity-verify mismatches=1
world before=3.989337e+12 | after inject X=3.0e+10: A=4.019337e+12 (gain 3.000000e+10) B=4.018337e+12 (gain 2.900000e+10)
orphan energy A=0.000000e+00 B=1.000000e+09  -> energy that left the world in B = 1.000000e+09 J (fastSets=1)
```
Isso é R1, que o desenho já admite. O defeito novo é que o teste que deveria pegá-lo no servidor
(passo B do plano, "0 verify mismatches") passaria mesmo assim. **Correção:** em modo verify,
comparar a **identidade** de cada ref com `coord.getTileEntity(world)` (o mesmo objeto, `==`), e
fazer isso também antes de cada `setEnergy` rápido. Se divergir, contar, registrar, invalidar o
estado e usar o caminho original.

**S2. A reconstrução pode ser reentrante.** Durante a reconstrução o desenho chama o original
`coord.getTileEntity(world)`. Se a entrada do mapa estiver inválida, `func_150806_e` cria uma TE nova e
chama `world.setTileEntity`, que avisa os vizinhos (`World.java:3250`, `func_147453_f`). Um vizinho
(porta, outro bloco) pode ler a energia da matriz dentro desse aviso, ou seja, chamar o nosso handler de
novo no meio da reconstrução. Se os campos `@Unique` forem preenchidos no lugar, a chamada interna vê
um estado pela metade. E a reconstrução acontece justamente quando há TE inválida, que é quando essa
criação ocorre. O protótipo do harness (`Fast.build`) já monta tudo em variáveis locais e só publica no
fim, mas o texto do desenho (5.2, passo 5: "store the ref...") não exige isso. **Correção:** montar
em arrays locais e publicar de uma vez no final; flag `hs$building`: se o handler for chamado com a
flag ligada, devolver sem cancelar (roda o original).

**S3. A ordem das checagens de chunk precisa estar escrita, senão o conserto pode CARREGAR chunks.**
No Crucible, `World.func_72964_e` → `ChunkProviderServer.provideChunk`, que **carrega** o chunk se ele
não estiver na memória (`CRU/net/minecraft/world/gen/ChunkProviderServer.java:308-309`). O original nunca
faz isso, porque `Coord4D.exists` checa `func_73149_a` antes. **Correção:** na validação, a ordem
obrigatória é `chunk.field_76636_d` → `provider.func_73149_a(cx,cz)` → só então
`world.func_72964_e(cx,cz) == chunkGuardado`, com curto-circuito. Na reconstrução, pegar o Chunk só
depois que `getTileEntity` devolveu uma célula, e uma vez por par (cx,cz), não uma vez por célula (o
texto do desenho diz "store ... its chunk world.func_72964_e(x>>4, z>>4)" por célula; isso também
encarece a reconstrução em ~1 busca por célula).

**S4. O relógio do intervalo de reconstrução deve ser um contador do servidor, não `func_82737_E`.**
`getTotalWorldTime` é por mundo. Em mundos criados pelo construtor de "compatibilidade vanilla" do
Crucible (`CRU/net/minecraft/world/WorldServerMulti.java:29`) o `worldInfo` é `DerivedWorldInfo`, cujo
`incrementTotalWorldTime` não faz nada (`CRU/net/minecraft/world/storage/DerivedWorldInfo.java:124`):
o tempo só anda quando o overworld anda, e a ordem dos mundos no tick vem de
`DimensionManager.getIDs` (`MinecraftServer.java:940-1006`), sem ordem garantida. Pode haver tick com 0
ou 2 incrementos para um mundo, ou seja, R1 aberto por 2 ticks, ou uma reconstrução a mais. Também
vale se algum mod mexer no tempo total. Não cria energia (a validação continua valendo), mas o desenho
promete "janela de 1 tick". **Correção:** contador próprio `hs$tick` incrementado no
`ServerTickEvent` START (o mesmo ponto que captura a thread). Válido só se `builtAt == hs$tick`
(intervalo 1) ou `0 <= hs$tick - builtAt < N`.

**S5 (desempenho, não segurança).** Estrutura incompleta (célula quebrada sem a matriz reformar):
reconstrução a cada tick, alocando arrays do tamanho da matriz (até 4096) por tick, e o caminho
original segue rodando. **Correção:** quando incompleta, lembrar "incompleta no tick T" e só tentar
reconstruir a cada 20 ticks. Enquanto isso, original puro, sem alocar nada.

Nada encontrado que crie item, mude regra de jogo, complete missão duas vezes, mova meia área, trave ou
mude o que o jogador vê. Mudança de dimensão e descarga do mundo: a checagem de mundo/chunk pega.
Memória: os `@Unique` prendem World/Chunk/TEs só enquanto o objeto da estrutura vive. Ele é
referenciado pelas TEs do próprio mundo (o `MultiblockManager` guarda caches, não a estrutura;
**não verificado** em todos os caminhos). Sugestão barata: zerar os arrays quando a validação falha
por mundo/chunk.

---

## 4. Alternativa mais simples ou mais segura?

- **Só config:** `B:BlacklistIC2Power=true` (Mekanism.cfg) tiraria as portas da rede IC2 (−2,24%), mas
  quebra a ligação de energia Mekanism↔IC2 que os jogadores usam. É mudança de jogo. Não.
- **Total em cache** (o plano original): exigiria invalidar em toda escrita, inclusive escrita direta
  no campo público `electricityStored`. Um total velho é exatamente como se duplica energia. O
  desenho fez bem em recusar.
- **Mexer nos chamadores** (portas, Uncomplication) para ler menos vezes: seriam vários mixins em vez
  de um, e cada um mudaria a ordem das leituras e escritas. Pior.
- **Não fazer:** perder ~6 ms/tick do maior item isolado do perfil. Não se justifica: o desenho é
  isolado (uma classe, nada salvo) e o desligamento volta ao original exato.

Conclusão: não há alternativa melhor. Manter o desenho, com as correções abaixo.

---

## 5. Desenho corrigido (só o que muda; o resto do `C-mekanism.md` vale)

1. **Relógio:** campo estático `hs$tick` (long), incrementado no `ServerTickEvent` START, no mesmo
   handler que grava `hs$serverThread`. `builtAt` usa `hs$tick`. Válido se `0 <= hs$tick - builtAt < N`
   (N = `matrixRebuildIntervalTicks`, padrão 1). Sai `func_82737_E`.
2. **Reconstrução:** em arrays locais. Flag de reentrância `hs$building` (no próprio objeto): se ligada,
   o handler devolve sem cancelar. O Chunk é obtido uma vez por (cx,cz) e só depois de achar a célula. A
   publicação dos campos `@Unique` acontece no fim, de uma vez. A soma devolvida é a do laço original
   (mesmas chamadas, mesma ordem).
3. **Validação de chunk:** ordem obrigatória `field_76636_d` → `func_72863_F().func_73149_a(cx,cz)` →
   `func_72964_e(cx,cz) == guardado`, com curto-circuito. Quantidade de grupos tirada das próprias
   células (não fixar 4).
4. **Incompleta:** guardar `hs$incompleteAt`; nova tentativa de reconstrução só depois de 20 ticks.
   Enquanto isso, retorno imediato sem cancelar.
5. **`matrixVerify` por identidade:** em cada leitura rápida e **antes de cada escrita rápida**,
   para cada i: `refs[i] == coord_i.getTileEntity(world)`, iterando `cells` na mesma ordem. Se
   divergir: contador `verifyMismatch`, log com coordenadas, estado invalidado, caminho original. A
   comparação de somas pode ficar como checagem extra, mas não substitui a de identidade.
6. Zerar `hs$refs`/`hs$chunks` quando a validação falha por mundo ou chunk (não prender mundo
   descarregado).

Plano de testes, acréscimos:
- **Harness:** (a) `ConfAdv` acima deve passar a mostrar `verify` acusando a órfã (com o verify por
  identidade); (b) cenário de reentrância: um `FakeWorld` cujo `func_147438_o`, ao achar entrada
  inválida, cria uma célula nova e chama `Fast.getEnergy` de dentro (simula `func_150806_e` +
  aviso aos vizinhos); A e B devem ficar iguais; (c) relógio que não anda (mesmo `hs$tick` por 3
  "ticks" do mundo) mais eventos padrão: 0 diferenças.
- **Servidor de teste, passo B:** passa somente com `verifyMismatch=0` **no verify por identidade**
  e contador de leituras rápidas > 0. Acrescentar uma contagem de "chunk carregado pelo conserto":
  comparar o número de chunks carregados com o conserto ligado e desligado, com o bot longe da matriz
  (tem que ser igual).
- O NBT do `/setblock` do desenho está certo quanto aos tipos: `TileEntityInductionCell.func_145839_a`
  lê `tier` com `func_74762_e` (inteiro) e `electricityStored` com `func_74769_h` (double), conferido
  com `javap -c`. O nome de registro `Mekanism:BasicBlock2` e os metadados continuam **não verificados**.

---

## 6. Não verificado (o que sobra)

- *f* real no Crucible (custo da leitura rápida / original) e as ~31 leituras por tick: definem onde
  cai o ganho entre 4,4 e 7,6 pontos.
- Que o `MultiblockManager`/outros não guardam referência longa ao objeto `SynchronizedMatrixData`
  (afeta só memória, não energia).
- Aplicação do mixin e registro do late loader sob UniMixins (só o algoritmo foi testado).
- Os itens de servidor de teste do desenho (nome de registro, cubo de energia, descarga por distância).
