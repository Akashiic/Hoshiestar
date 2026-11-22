# Conferência do desenho 1.E (ChunkBlockHashMap) e 1.G (desligamento D-24)

Conferente: agente diferente do autor de `E-chunkmap.md`. Só leitura: nenhum servidor ligado, nenhum
arquivo do repositório alterado, nenhum valor de config copiado. O arquivo do investigador não foi editado.

Caminhos usados:
- `$S` = `/tmp/claude-0/-home-user-Hoshiestar/86b4be55-47d2-5167-8109-96ebc6889fd2/scratchpad`
- `$J` = `$S/anexos/spark/wIBxG0D4y9.json` (perfil de produção)
- meus arquivos: `$S/etapa1/E-conf/` (`src/ApplyBin.java`, `src/RaceDemo.java`, `src/RaceOrig.java`,
  `scan.py`, `scan_exit.py`, `scan_tf.py`, `allthreads.py`, `patched/` = classes com binpatch aplicado)
- decompilados meus: `$S/etapa1/decomp/E-conf/` (`patched/` = `MinecraftServer` real de execução,
  `hodgepodge/`, `mx/` = MixinExtras do UniMixins, `fml/`, `dh/`, `pca/`, `am2/`, `bellow/`, `etf/`, `ct/`)

## Veredito

**Aprovado com correções.** O desenho do 1.E está certo para uma thread só e a prova diferencial confere
(rodei de novo: 0 diferenças, e o caso do MRU velho se repete). As medidas do perfil conferem. Mas há
cinco correções antes de escrever o código: (1) o 1.E deve vir **desligado** por padrão (decisão 16);
(2) o cache deve ser preenchido **só pela thread do servidor**, porque, se alguém de fora chamar a
busca, o erro passa de passageiro (3 chamadas) para permanente (até o chunk recarregar), provado com
harness; (3) S1/S2 não compilam contra a "visão SRG" citada, que tem o `MinecraftServer` sem patch;
(4) S1 espera demais depois de salvar (até 600 s) se algo travar entre o fim do save e o `System.exit`;
(5) no caminho de desistência (timeout), a thread do servidor não deve seguir para os eventos de
"servidor parado" enquanto o gancho ainda salva. O investigador também não viu três mixins do
hodgepodge em `MinecraftServer.run()`; não conflitam com os alvos, mas mudam a análise do desligamento
(para melhor: o S1 conserta uma perda de dados que hoje acontece no SIGTERM com o salvamento em thread
do hodgepodge ligado).

---

## 1. Assinaturas (javap)

Primeiro um problema de base: o `server.jar` **não contém** as classes `net.minecraft.*` nem as de nome
notch (`unzip -l server.jar | grep -c '^[a-z]{1,3}\.class$'` = 0). Elas vêm do jar vanilla mais o
`binpatches.pack.lzma`. A `srg.jar` do investigador (`$S/etapa1/E-srg/srg.jar`) tem as classes **vanilla**
remapeadas, sem o patch do Crucible. Para ter a verdade de execução, apliquei o binpatch eu mesmo com o
próprio `ClassPatchManager` do FML, em JDK 8:

```
cd $S/etapa1/E-conf
env -u _JAVA_OPTIONS -u JAVA_TOOL_OPTIONS /usr/lib/jvm/java-8-openjdk-amd64/bin/java -cp out:<server.jar>:<lzma>:<guava> \
  ApplyBin $S/server-test3/libraries/net/minecraft/server/1.7.10/server-1.7.10.jar patched \
  net.minecraft.server.MinecraftServer lt apx
javap -p -s -cp patched:$S/server-test3/server.jar net.minecraft.server.MinecraftServer   # e lt, apx
```

| Membro do desenho | Conferido em | Resultado |
|---|---|---|
| `thermos.wrapper.ChunkBlockHashMap#get(II)` / `put` / `remove` | `javap -p -s -cp server.jar thermos.wrapper.ChunkBlockHashMap` (notch: `(II)Lapx;`, `(Lapx;)V`) e `srg.jar` (`(II)Lnet/minecraft/world/chunk/Chunk;`...) | **certo**; todos `public`, não estáticos |
| `map` `Lcom/koloboke/collect/map/LongObjMap;` private final; `size` `I` private | mesmo javap | **certo** |
| `Chunk.field_76635_g` / `field_76647_h` `public final int` | `javap patched/apx.class`: `public final int g; public final int h;` + `notch-srg.srg` | **certo**, mas a citação "`javap -p -cp server.jar apx`" está errada: `apx` não está no `server.jar`. O fato vale para a classe com patch. |
| `MinecraftServer.func_71260_j()V` (stopServer) | patched: `public void o() throws ahg;` (`ahg` = MinecraftException); `MD: .../o ()V .../func_71260_j` | **certo** |
| `MinecraftServer.func_71263_m()V` (initiateShutdown) | patched: `public void r();` corpo `this.v = false` | **certo** |
| `field_71316_v` (serverStopped) / `field_71290_O` (worldIsBeingDeleted) | patched: `private boolean w;` / `private boolean M;` + srg | **certo**, ambos `private`, não `volatile` |
| `serverRunning` = `field_71317_u` | patched: `private boolean v;` | campo comum, **não volatile** (o R7 do investigador procede) |
| `MinecraftServer.primaryThread` | patched: `public final java.lang.Thread primaryThread;` (criado no construtor, logo depois de `addShutdownHook(new ServerShutdownThread(this))`, decompilado `patched/.../MinecraftServer.java:228-229`) | **existe em execução**. **Não existe na `srg.jar`** (`javap ... srg.jar ... | grep -c primaryThread` = 0) |
| `DedicatedServer.func_71240_o()V` (systemExitNow) | patched `lt`: `protected void t();` corpo `iconst_0; invokestatic System.exit` | **certo** |
| `ServerShutdownThread.server` / `run()V` | `javap -p -s -c -cp server.jar org.bukkit.craftbukkit.v1_7_R4.util.ServerShutdownThread` | **certo** (`private final`; `run` chama `MinecraftServer.o()V` e depois `reader.getTerminal().restore()`) |
| Corpo de `stopServer` usado no S2 (`willSave`) | decompilado patched, linha 405: `if (!this.M && Loader.instance().hasReachedState(LoaderState.SERVER_STARTED) && !this.w)` | **igual** ao do desenho |
| Ordem em `run()` | patched 541-560: `finally { WatchdogThread.doStop(); this.o(); this.w = true; } ... finally { reader...restore(); FMLCommonHandler.handleServerStopped(); this.w = true; this.t(); }` | confere com o desenho; o Watchdog do Spigot é parado **antes** do save (bom para o S1) |

**Erro de construção (corrigir):** o desenho manda compilar "contra a visão SRG (receita do `medidor/build.sh`)".
Essa visão tem o `MinecraftServer` vanilla: sem `primaryThread` e sem o `throws MinecraftException` em
`func_71260_j`. S1 (`server.primaryThread`) e S2 (`@Shadow primaryThread`) não compilam assim. Saída: gerar a
visão SRG a partir das classes **com binpatch** (o `ApplyBin.java` acima mostra como obter os bytes; depois
remapear notch->SRG como o medidor já faz) ou usar um stub só de compilação com esses membros.

**Quem mais mexe nos mesmos métodos** (`python3 -I $S/etapa1/E-conf/scan.py $S/server-test3/mods $S/server-test3/plugins -- thermos/wrapper ChunkBlockHashMap ServerShutdownThread func_71260_j func_71240_o systemExitNow stopServer func_71263_m`,
mais `scan_tf.py` para transformadores ASM):

- `ChunkBlockHashMap`, `thermos.wrapper`, `ServerShutdownThread`: **ninguém** (nenhuma ocorrência em 292 mods e
  65 plugins, nem com nome pontuado). Confere com o investigador.
- `func_71260_j`: só o `Akashic-SaveRelief` (`@Inject HEAD`), como o investigador disse.
- **O investigador não viu o hodgepodge 2.7.219**, porque ele usa refmap (o nome na classe é MCP:
  `stopServer`, `systemExitNow`). Três mixins em `MinecraftServer.run()` (decompilados em `decomp/E-conf/hodgepodge/`):
  - `memory.MixinMinecraftServer_ShutdownHook`: `@Inject` em `INVOKE systemExitNow()V` dentro de `run`, chama
    `AfterServerStoppedHook.run()` (limpa referências estáticas: CoFH, canais de rede, `CommandBase.func_71529_a(null)`,
    map storage). Registrado **sem condição** (`Mixins.java:125`, sem `setApplyIf`).
  - `memory.MixinMinecraftServer_ClearServerRef`: no mesmo ponto zera `MinecraftServer.field_71309_l` (o
    `getServer()` passa a devolver `null`). Condicional (`fixMinecraftServerLeak`, `Mixins.java:126`).
  - `minecraft.MixinMinecraftServer_WorldDataSave`: `@WrapOperation` na **chamada** de `stopServer()` dentro de `run`,
    seguida de `WorldDataSaver.INSTANCE.closeSession()` (fecha o salvamento em thread de level.dat, mapas,
    scoreboard, vilas, ForgeChunkManager). Condicional (`threadedWorldDataSaving`, `Mixins.java:158`).
  Nenhum mira o corpo de `func_71260_j`, o `func_71240_o` do `DedicatedServer` ou o `ServerShutdownThread`, então
  **não há conflito de injeção** com S1/S2/S3. Se estão ligados nesta cópia não conferi (seria ler valores de config).
- `AM2.5` tem um transformador ASM em `MinecraftServer` (`alterServerTickrate`, `am2/preloader/BytecodeTransformers.java:80-82`):
  mexe no laço de tick, não nos nossos alvos.
- `@WrapMethod` x `@Inject HEAD` do SaveRelief: o "não verificado" do investigador fica resolvido pelo código.
  No MixinExtras empacotado no UniMixins 0.3.1 (`com/llamalad7/mixinextras/injector/wrapmethod/WrapMethodApplicatorExtension`,
  decompilado em `decomp/E-conf/mx/`), o embrulho é montado em `postApply` da classe alvo, depois de todos os
  injetores de todos os mixins. Logo a chamada do SaveRelief fica **dentro** do nosso embrulho. Ainda vale olhar a
  classe exportada no boot.
- Ordem "deobfuscação antes do Mixin" para classe fora de `net.minecraft`: o `DeobfuscationTransformer` do
  `server.jar` remapeia **toda** classe, sem filtro de pacote (`decomp/E-conf/fml/.../DeobfuscationTransformer.java`), e a
  ordem dos transformadores é a mesma para todas as classes. Somado ao medidor (que já funciona com nomes SRG),
  a inferência do investigador fica bem apoiada. Confirmar no primeiro boot, como ele propõe.

## 2. Números (perfil)

Comandos (de `/home/user/Hoshiestar` e de `$S/etapa1`):

```
python3 -I tools/spark_report.py $J busca ChunkBlockHashMap        -> get 3.63% (21 716 ms), bulkCheck 0.18%
python3 -I E-tools/selftime.py $J ChunkBlockHashMap.get            -> próprio 17 648 ms (2.95%); filhos: Koloboke 0.667%, SATB 0.013%
python3 -I E-tools/callers.py $J ChunkBlockHashMap.get --up 1      -> provideChunk 1.91%, chunkExists 1.63%, getChunkIfLoaded 0.07%, loadChunk 0.01%
python3 -I E-conf/allthreads.py $J ChunkBlockHashMap ...           -> só "Server thread" tem amostras (das 381 threads)
```

Tudo confere. Base: 598 904 ms / 7113 ticks = 84,2 ms por tick, 1% = 0,842 ms.

Microbenchmark refeito (JDK 21/G1, `Bench` do investigador, com varredura de cache):
padrão 2: original 56,0 ns, novo 21,6 ns (-61%); padrão 3: original 72,0 ns, novo 46,8 ns (-35%).
Os números absolutos variam com a máquina, as proporções batem com a tabela dele.

**Ressalva sobre o ganho esperado (0,9 a 2,2 pontos):** não está claramente exagerado, mas é otimista na ponta
de baixo. Parte do "tempo próprio" de 2,95% é a primeira falta de cache ao ler o objeto `Chunk` (na amostragem,
o custo cai na primeira instrução que toca a memória). O código novo também lê o `Chunk` num acerto
(`c.field_76635_g`); e quando não lê, quem chama vai ler logo depois. Esse pedaço muda de lugar no perfil, não
some. O microbenchmark mede só a busca. Também é verdade que, em produção, cada mundo tem seu próprio cache de
4096 posições e a maioria dos 60 mundos tem bem menos chunks que isso, o que favorece acertos. Conclusão: tratar
0,9 a 2,2 pontos como estimativa **não verificada**; o número real só vem da medição antes/depois com JFR (teste 3).

## 3. Segurança: tentativas de quebrar

### 3.1 Mapa de chunks (E1)

Prova diferencial refeita (JDK 8, classe real do `server.jar` contra o modelo `NewMap`):
`Diff`: 0 colisões x/-x, 0 colisões de chave em 2,2 milhões de pares, `new!=ref=0` e `orig!=ref=0` em 6 rodadas.
`Stale`: o original devolve o chunk descarregado A nas 3 situações; o modelo devolve `null`. Confere.

Conferi à mão a afirmação "só difere depois de put duplo + remove": um item velho só sobrevive no MRU original
atrás do item novo da mesma posição, e só aparece quando o `remove` tira o da frente. Fora disso o original devolve
o que a estrutura tem. O novo devolve sempre o que a estrutura tem (invariante C). Correto.

Achado extra a favor do desenho: há um caminho real de put duplo no próprio Crucible, além do que o investigador
citou. `CraftWorld.regenerateChunk` (`$B/src/main/java/org/bukkit/craftbukkit/v1_7_R4/CraftWorld.java:232-247`) só
**enfileira** a descarga (`unloadChunk` -> `unloadChunksIfNotNearSpawn`) e logo põe o chunk novo no mapa
(`chunkLoadPostProcess` -> `add`). Quando a fila descarrega, o `remove` acerta o novo e o MRU original pode voltar a
devolver o velho. Plugins de regeneração (WorldEdit `//regen`, por exemplo) passam por aí. O E1 corrige isso.

**Problema A (corrigir): corrida com leitor fora da thread do servidor vira erro permanente.**
O desenho diz "mesma exposição de hoje" para threads assíncronas. Não é a mesma. Sequência:
1. Uma thread T (não a do servidor) chama `get(5,7)`, erra o slot e lê a estrutura: recebe o chunk C.
2. Antes de T gravar o slot, a thread do servidor descarrega C (`remove`, que limpa o slot).
3. T grava `slots[i] = C`.

No original, o mesmo intervalo deixa C em `last1`, e ele sai do MRU depois de poucas buscas. No E1, C fica no slot
com as coordenadas certas e é devolvido **até o chunk (5,7) recarregar** (ou até uma busca que erra numa posição
64 chunks distante achar outro chunk). Nesse tempo `chunkExists(5,7)` diz "carregado" para um chunk descarregado, e
`provideChunk(5,7)` devolve um objeto que nunca mais é salvo: blocos colocados, itens empurrados por um cano ou funil
vizinho para dentro de um baú desse chunk, entidades: tudo se perde.

Harness (`$S/etapa1/E-conf/src/RaceDemo.java` e `RaceOrig.java`, JDK 8, repete deterministicamente a intercalação;
o original é a classe real, com o estado que a corrida deixa posto por reflexão em `last1`):

```
E1 as designed : structure says null, get(5,7) returned the unloaded chunk in 1000000 of 1000000 later server-thread calls
owner-only fill: structure says null, get(5,7) returned the unloaded chunk in 0 of 1000000 later server-thread calls
original: get(5,7) returned the unloaded chunk in 3 of 1000000 later calls (gone after iteration 3)
```

Hoje ninguém parece chamar a busca de fora: no perfil de 602 s nenhuma das outras 380 threads tem amostra em
`ChunkBlockHashMap`; o dump de travamento (411 threads) não mostra `ChunkProviderServer` em nenhuma; o Distant
Horizons (`Mirai-forge17`) manda as consultas para a thread do servidor (`ServerThreadTaskHandler.queueTask`,
`decomp/E-conf/dh/.../InternalServerGenerator.java:393-423`); o `Akashic-PlayerChunkAsync` roda no `PlayerManager`,
na thread do servidor. Então é risco latente, não bug presente. A correção custa uma comparação só no caminho do erro
de cache e é o mesmo formato do cache por thread das fases futuras: **gravar o slot só se
`Thread.currentThread() == dono`**, com `dono` = `MinecraftServer.func_71276_C().primaryThread` lido uma vez (não
depender da thread que construiu o mapa). Leitura de fora continua funcionando (acerto devolve o slot; erro faz a
busca pura), só não grava.

**Problema B (corrigir): padrão "ligado" contraria a decisão 16.** O E1 muda comportamento no caso do put duplo
(é uma melhora, mas é mudança) e troca uma estrutura central do servidor. A decisão 16 manda "tudo que muda
comportamento vem desligado". `B:chunkMapCache` deve vir `false`; o admin liga depois dos testes 8.1.

Conferidos e sem problema:
- Descarga, recarga, troca de dimensão: toda mutação passa por `put`/`remove` (grep em todo o Crucible:
  `CPS:261`, `ChunkIOProvider:46`, `CraftWorld:301` põem; `CPS:567` tira; `raw()` sem usuário; nenhum mod ou plugin
  cita `loadedChunkHashMap_KC`, `rawThermos`, `ChunkBlockHashMap`). Cada mundo tem seu mapa; mundo descarregado
  leva o mapa e o cache junto. O mundo só descarrega com `size()==0`, e o `size` não muda.
- `EmptyChunk(0,0)` posto com chave de outra posição (`CPS:236`): estrutura e cache ficam iguais nos dois códigos.
- `remove` de um objeto diferente do guardado na mesma posição: estrutura zera, cache também (coordenadas batem).
- Ordem de iteração: ninguém itera o `ChunkBlockHashMap`; quem itera usa `rawVanilla()` ou `loadedChunks`.
- "Desligado = original": com o mixin não registrado o Mixin não toca a classe. Correto.
- Impressão digital (fingerprint) para recusar o mixin se o upstream mudar: a ideia é boa, mas o "como" não está
  verificado (o `shouldApplyMixin` não recebe os bytes). Mais simples e suficiente, já que o `server.jar` é fixo:
  no plugin, ler os bytes **crus** da classe (`Launch.classLoader.getClassBytes("thermos.wrapper.ChunkBlockHashMap")`,
  antes de transformar) e comparar um SHA-256 com o do `staging@67b53034`. Que o RFB mantém esse método igual ao
  launchwrapper: **não verificado**.

### 3.2 Desligamento (S1, S2, S3)

Conferidos e corretos: SIGTERM durante o tick vira o mesmo caminho do `stop` (save na thread do servidor, no fim de
um tick inteiro, com o Watchdog já parado em `run()`); `stop` seguido de SIGTERM durante o save (hoje dá um
**segundo save em paralelo**, porque `w` ainda é falso; com S1 o gancho espera); recursão de `stopServer` na mesma
thread (passa pelo `owner == current`); exceção no primeiro save (volta a IDLE; o segundo chamador ainda vê `w=true`
e não salva, igual a hoje). O hodgepodge `ClearServerRef` zera `MinecraftServer.getServer()` antes de
`systemExitNow`: o S1 usa o campo `server` da própria thread de gancho, então não é afetado (manter assim).

Ponto a favor que o investigador não viu: com o `threadedWorldDataSaving` do hodgepodge ligado, o
`closeSession()` só roda em volta da chamada de `stopServer()` **dentro de `run()`**. No SIGTERM de hoje o save roda
na thread do gancho, sem `closeSession()`, e a JVM pode parar com level.dat, mapas e scoreboard ainda na fila. Com
S1 o save volta para `run()` e o `closeSession()` acontece. Testar isso no 8.2 (ver abaixo).

**Problema C (corrigir): espera longa depois de salvar.** O laço do S1 só sai com `exitReached` ou com a thread do
servidor morta. Entre o fim do `stopServer()` e o `systemExitNow()` rodam: `closeSession()` do hodgepodge, o
`FMLServerStoppedEvent` de todos os mods e o `AfterServerStoppedHook`. Se qualquer um travar (por exemplo, um mod que
chame `System.exit` ali: essa chamada fica bloqueada para sempre, porque a JVM já está desligando), o gancho espera
até `startOfRunning + 600 s`, com os dados já salvos. Procurei esse padrão (`scan_exit.py`) e não achei nenhum mod
que chame `System.exit` nesses eventos (os três suspeitos, `AkashicBellow`, `etfuturum`, `coretweaks`, não chamam).
Mesmo assim: depois de `state == DONE`, esperar no máximo uma folga curta (proposta: `I:stopAfterSaveGraceSeconds=30`)
por `exitReached`.

**Problema D (corrigir): caminho de desistência.** Se a fase 1 estoura, o gancho marca `giveUp` e salva ele mesmo
(comportamento de hoje). Se nesse momento a thread do servidor terminar o tick e entrar em `stopServer`, o desenho
faz ela **voltar na hora** (`!giveUp` na condição do `while`) e seguir para `closeSession()`, `FMLServerStoppedEvent`
e `AfterServerStoppedHook` enquanto o gancho ainda salva os mundos. Mods desmontam estado e o hodgepodge fecha a fila
de salvamento no meio do save do gancho. Correção: a thread do servidor deve **esperar** o save em curso terminar
(limite T2) também quando `giveUp` for verdadeiro; `giveUp` só serve para o gancho parar de esperar o laço.

Outros pontos (sem mudança de desenho, só registrar):
- SIGTERM durante o carregamento dos mods (boot de vários minutos): hoje a JVM para na hora; com S1 para 60 s
  depois (fase 1), num ponto também arbitrário. Não piora o tipo de risco, mas atrasa a parada em 60 s.
- O desenho supõe que a produção recebe SIGTERM. Painéis de hospedagem costumam mandar o comando `stop` no console
  e só matam depois de um tempo; `docker stop` manda SIGTERM e SIGKILL depois de 10 s por padrão. **Não verificado**
  como a produção para. Se for `stop`, o 1.G não muda nada em produção.

## 4. Alternativas mais simples

- **1.E:** o E1 já é a forma simples (três métodos, ~40 linhas). Não há troca só de configuração. Como patch no
  fork/upstream é o mesmo diff; concordo com "mixin agora, pull request depois", com as correções A e B.
- **1.G:** antes de escrever S1-S3, perguntar ao admin como a produção é parada. Se for o comando `stop` do painel
  (e com tempo para salvar), basta manter a regra operacional da decisão 3 e mandar a correção como pull request ao
  Crucible; o mixin fica opcional, desligado (como já está no desenho).

## 5. Desenho corrigido (só o que muda)

**E1 `MixinChunkBlockHashMap`**
- Novo campo `@Unique private Thread hoshie$owner;` preenchido de forma preguiçosa com
  `MinecraftServer.func_71276_C().primaryThread` (nome SRG de `getServer()`; conferir no javap da visão com patch).
- `get`: igual ao desenho, mas a gravação vira `if (c != null && Thread.currentThread() == hoshie$owner) s[i] = c;`.
- `put` e `remove`: iguais ao desenho (já gravam/limpam o slot; rodam na thread do servidor).
- Config: `B:chunkMapCache=false` (padrão desligado, decisão 16); o resto igual.
- Impressão digital: SHA-256 dos bytes crus da classe via `getClassBytes` no plugin; diferente -> não aplica e avisa.
- Compilar contra a visão SRG feita das classes **com binpatch**.

**S2 `hoshie$singleStop`** — trocar o laço de espera por:
```
while (state == RUNNING && owner != current && notTimedOut(T2)) lock.wait(250);   // sem "!giveUp"
```
(o resto igual).

**S1** — no laço, depois que `state == DONE`, sair também quando passar `stopAfterSaveGraceSeconds` (30 s) desde o
fim do save, mesmo sem `exitReached`; avisar no log com o stack da thread do servidor.

**Testes a mais**
- 1.E: no teste 8.1 passo 2, também `//regen` do WorldEdit (ou `CraftWorld.regenerateChunk` por plugin de teste)
  numa área e depois sair até descarregar: contador de divergência do `chunkMapVerify` = 0.
- 1.G: no 8.2 passo 2 (`hoshie testexit`), conferir que os arquivos de dados do mundo (level.dat, `data/*.dat`) têm a
  hora do desligamento (mostra que o `closeSession()` do hodgepodge rodou) e, com o interruptor desligado, que hoje
  eles podem ficar velhos.
- 1.G: provocar o caminho de desistência num ambiente de teste (fase 1 de 1 s e um tick artificialmente longo,
  por um comando de debug) e conferir que o `FMLServerStoppedEvent` só aparece no log depois do "Saving worlds" do
  gancho terminar.

## 6. Não verificado (o que sobra)

- Se o hodgepodge desta cópia tem `fixMinecraftServerLeak` e `threadedWorldDataSaving` ligados (não li valores).
- Que o RFB expõe `LaunchClassLoader.getClassBytes` igual ao launchwrapper.
- (Conferido depois: `getServer()` = `MD: .../I ()Lnet/minecraft/server/MinecraftServer; .../func_71276_C`, e o
  `MinecraftServer` com patch tem `public static MinecraftServer I()`, decompilado `patched/.../MinecraftServer.java:837`.)
- Como a produção é parada (comando `stop` ou SIGTERM, e quanto tempo o painel espera).
- O ganho real do 1.E em produção (taxa de acerto e onde a falta de cache do `Chunk` vai aparecer no perfil).
- Todos os demais itens da seção 9 do investigador continuam valendo, menos o do `@WrapMethod`, que ficou
  resolvido pelo código do MixinExtras (ainda conferir na classe exportada).
