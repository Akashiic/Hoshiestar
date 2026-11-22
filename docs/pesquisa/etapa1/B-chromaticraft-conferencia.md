# 1.B Conferência: teto no shift de área do ChromatiCraft (stall de 30 s, D-21)

Conferente independente (não escrevi o desenho). Arquivo conferido: `etapa1/desenho/B-chromaticraft.md`, que não foi alterado.
Material próprio desta conferência, todo no scratchpad:
- `etapa1/decomp/B-chromaticraft/conf-AbilityCalls.javap.txt`: `javap -c -l` de `AbilityCalls`
- `etapa1/conf-B/AbilityHelper.javap.txt`
- `etapa1/decomp/B-chromaticraft/conf/`: CFR de `ChromaASMHandler` e de `DragonOptions`
- `etapa1/conf-B/grepjars.py`: varredura de jars
- `etapa1/conf-B/src/ConfDecision.java`: harness

**Veredito: aprovado com correções.** O diagnóstico está certo, e a ideia central também: recusar no HEAD, antes de qualquer
bloco mudar. Mas o desenho tem três problemas que precisam ser corrigidos antes de escrever o código.
1. Do jeito que está escrito, os campos estáticos do mixin podem impedir o mixin de carregar.
2. Ele chama de "removido" um stall que fica apenas limitado, de tamanho ainda desconhecido, e que pode ser repetido à vontade.
3. A calibração prevista roda com um bot op, que pula justamente a parte cara do código.

Proponho também um desenho mais simples: tudo no HEAD, sem cortar o laço no meio. Ele fecha de quebra um bug existente que
**destrói blocos** com um cliente normal.

Termos usados:
- **mixin**: um remendo aplicado à classe de outro mod quando ela carrega.
- **HEAD**: o começo do método.
- **callback**: o pedaço de código nosso que o mixin chama.
- **op**: jogador com permissão de administrador.

---

## 1. Assinaturas (javap refeito)

Todos os alvos existem no jar que carrega (`mods/ChromatiCraft 1.7.10 V33a.jar`, `mods/DragonAPI 1.7.10 V33b-ForbiddenFix1.jar`).
Descritores conferidos por mim:

| Membro | Descritor / acesso | Onde vi | OK? |
|---|---|---|---|
| `AbilityCalls.shiftArea` | `static` (pacote), `(Lnet/minecraft/world/WorldServer;LReika/DragonAPI/Instantiable/Data/Immutable/BlockBox;Lnet/minecraftforge/common/util/ForgeDirection;ILnet/minecraft/entity/player/EntityPlayerMP;)Z` | javap -p -s | sim |
| `FilledBlockArray.setBlock` | `public (IIILnet/minecraft/block/Block;I)V`, chamada única em shiftArea, offset 221 (linha 553) | javap -c | sim |
| `BlockBox.getSizeX/Y/Z` | `public ()I`, `maxX-minX` (CFR `BlockBox.java:86-96`) | javap + CFR | sim |
| `BlockBox.minX..maxZ` | `public final int` | javap | sim |
| `Chromabilities.SHIFT` | `public static final LReika/ChromatiCraft/Registry/Chromabilities;` | javap | sim |
| `Chromabilities.getMaxPower` | `public ()I`; SHIFT devolve 24 (CFR `Chromabilities.java:652-660`) | javap + CFR | sim |
| `Chromabilities.setToPlayer` | `public (Lnet/minecraft/entity/player/EntityPlayer;Z)V` | javap | sim |
| `ChromaSounds.playSound` | `public (Lnet/minecraft/entity/Entity;)V` | javap | sim |
| `func_145747_a` (addChatMessage) | `(Lnet/minecraft/util/IChatComponent;)V`, declarado em `ICommandSender` | `forge/fml/conf/joined.srg` + `methods.csv` | sim |
| `func_70005_c_` (getCommandSenderName) | `()Ljava/lang/String;` (`ICommandSender`) | joined.srg | sim |
| `Entity.field_71093_bK` (dimension) | `I` | joined.srg + fields.csv | sim |
| `ChatComponentText.<init>` | `(Ljava/lang/String;)V` | javap (jar Crucible com nomes MCP) | sim |
| Offsets do relatório (276, 281, 330, 348/359, 422, 427, 437, 454, 463, 468) | | javap -c | todos conferem |

Observação: o `server.jar` da cópia não contém as classes `net.minecraft.*`. Elas chegam por binpatch e são remapeadas para
nomes SRG em runtime. Por isso os nomes SRG foram conferidos em `joined.srg` e não por javap direto.

Linhas do dump contra os jars da cópia: `AbilityHelper` 320/304/327 e `FilledBlockArray` 88/115 aparecem na LineNumberTable
desses mesmos jars. Logo, **a produção roda as mesmas versões** que estão na cópia de teste.

**Quem mais mexe nesse método:** ninguém.
- Procurei `AbilityCalls`, `Auxiliary/Ability`, `Registry/Chromabilities` e `Ability/AbilityHelper` em todos os jars de
  `mods/` e `plugins/` e também na raiz da cópia, incluindo os três `Akashic-*-agent.jar`.
- Só aparece o próprio ChromatiCraft, mais `chromatifixes` `WarpPointDataMixin`/`WarpPointAccessor`, que são mixins de
  **cliente** em outra classe.
- O coremod do próprio ChromatiCraft (`ChromaASMHandler`, CFR linhas 108-120) faz patch de 13 classes, todas vanilla ou
  Lore/Rosetta. Nenhuma é `AbilityCalls`.

**Momento da aplicação:** o UniMixins aplica os late mixins em
`LoadController.distributeStateMessage(LoaderState.CONSTRUCTING, ...)` (javap de `LateMixinOrchestrationMixin`). Isso
acontece antes dos construtores dos mods. Nenhum coremod referencia `Chromabilities`, `AbilityHelper` ou `AbilityCalls`.
Por isso é provável que a classe ainda não esteja carregada nesse momento. Isso continua **não verificado** em runtime
(teste 8.1).

### Erros de assinatura ou de regra do Mixin
1. **Campos estáticos do mixin precisam ser `private`.** O desenho fala em "static fields of the mixin" (`start`, `scanned`)
   e num método `refuse(...)`, sem dizer o acesso. O Mixin do pack (UniMixins 0.15.6+mixin.0.8.7) rejeita a classe com
   estas mensagens, que achei como strings em `MixinPreProcessorStandard.class` e `MixinApplicatorStandard.class`:
   - `Mixin %s contains non-private static field %s:%s`
   - `Mixin %s contains non-private static method %s`

   Se alguém escrever os campos com o acesso padrão do Java, o mixin não aplica. Com `require=1`, isso derruba a carga de
   `AbilityCalls`. Correção: todos os membros estáticos devem ser `@Unique private static`, com prefixo (`hoshie$start`)
   para não colidir com nada da classe alvo. Melhor ainda: deixar config e estado numa classe auxiliar fora do pacote de
   mixins.
2. Detalhe sem efeito: o trecho de `addPlayerClick` no relatório omite o `isDrawingBox.containsKey(ep)` antes do `get`
   (javap offset 12). Não muda nada.

## 2. Números do perfil

Recalculei com o leitor (rodando em `/home/user/Hoshiestar`, `P=<scratchpad>/anexos/spark/wIBxG0D4y9.json`):

| Comando | Resultado |
|---|---|
| `python3 -I tools/spark_report.py $P busca shiftArea` | 0 ocorrências, 0.00% |
| `... busca AbilityCalls` | 0, 0.00% |
| `... busca playerCanBreakAt` | 0, 0.00% |
| `... busca BlockBox` | 0, 0.00% |
| `... busca AbilityHelper` | 0.1 s, 0.02% (todos são outros handlers: addPoint, analyzeGenes...); `clickBlock` 4 ms |
| `... busca FilledBlockArray` | 0.1 s, 0.02% (`setBlock` 64 ms, mas `shiftArea` = 0, então vem de outro chamador) |
| `... busca C08PacketPlayerBlockPlacement` | 1.2 s, 0.20% |
| `... busca Chromabilities` | 0.3 s, 0.04% (`enabledOn`/`playerHasAbility` 120 ms cada, chamados de ticks, não do shift) |

- **Ganho médio de 0% está correto e não está exagerado.**
- **O ganho no pior caso está exagerado.** O relatório diz "remove a classe de stall do D-21". O teto só limita a fase A
  (o scan). Uma chamada dentro do teto ainda faz, por bloco:
  - 2 `BreakEvent` Forge+Bukkit (a fase C);
  - 2 `setBlock` com flags 3, ou seja, luz, vizinhos e pacotes (a fase D).

  Ninguém mediu isso. Minha estimativa (**não verificada**): algumas dezenas de microssegundos por bloco. Com 32 768
  posições, isso pode dar **mais de 1 s por chamada**. Além disso, nada impede repetir a chamada (seção 3, item B).
- **Harness do investigador reproduzido** (`ShiftDataHarness`, JDK 8, rodado por mim):
  - rodada quente: 32^3 em 12 ms (relatório: 11) e 64^3 em 175 ms (relatório: 183);
  - **rodada fria, a primeira: 32^3 em 111 ms**. O relatório não diz que o número dele é da rodada quente;
  - 181x1x181 (32 761 posições): 13 ms quente, 131 ms frio.
- **Fato novo:** `BlockBox.getVolume()` também estoura `int` em caixas enormes. No harness `ConfDecision`, uma caixa do
  tamanho do mundo dá `getVolume() = -1075310849`. `Math.pow(negativo, 1.25)` dá NaN, e `(int)NaN` é 0, então o custo vira 1.
  É mais uma rota para custo 1, além das que o relatório já lista.

## 3. Segurança, lido de forma adversária

### O que confirmei como seguro
- **Recusa no HEAD:** nada roda antes. `new FilledBlockArray` e `new BlockArray` são locais, e não há efeito no mundo nem
  no jogador.
- **O resultado é ignorado por quem chama:** `playerMakeBox` faz `invokestatic shiftArea` e depois `pop` (javap
  AbilityHelper offset 52-55). Em seguida `addPlayerClick` chama `removePlayerClick` (offset 96).
- **Cancelar dentro da fase A (injeção 2) também não perde nada.** Ela só faz:
  - leituras do mundo;
  - `isUnbreakable` (dureza e o evento `BreakSpeed`, somente para jogador em survival);
  - inserções em `HashMap`/`HashSet`/`ArrayList` locais.

  `BlockArray` não tem registro estático (CFR, os únicos estáticos são `rand` e comparadores). O laço também não tem nenhum
  `ProgressStage` ou missão (javap offsets 26-272). Interromper só **reduz** eventos em relação ao original.
- **Não achei nenhuma sequência em que a correção crie ou perca item, energia ou fluido,** nem uma que complete missão duas
  vezes. A única progressão ligada à habilidade é `ProgressStage.ABILITY.stepPlayerTo`, e ela fica em `triggerAbility`, no
  armar, fora do método alterado.
- **Estado entre chamadas:** só os dois campos estáticos, reescritos no HEAD de cada chamada. Não sobra cache depois de
  descarregar chunk, recarregar o mundo ou trocar de dimensão. Não há thread nova e não há atraso no desligamento do
  servidor.
- **"Desligado = original":** com `enabled=false` o mixin nem entra na lista, e a classe carrega com os bytes originais.
  Isso é verdade.

### Problemas encontrados
A. **Membros estáticos não privados derrubam o mixin** (seção 1, item 1).

B. **A máquina de lag continua aberta, só que limitada por chamada.** Não existe limite de frequência.
   - Para cada shift basta: 1 pacote `ChromaData ABILITY` para armar (sem checagem de faixa no servidor, `ChromatiPackets`
     linha 437-440) e 2 cliques C08.
   - Uma laje plana de 181x1x181 custa 1 unidade por elemento (o bug de custo), passa no teto de 32 768 e é movida de
     verdade.
   - Um cliente modificado pode repetir isso várias vezes por tick. Cada vez custa as fases C+D completas.
   - Correção: um intervalo mínimo por jogador (`cooldownMs`).

C. **Spam de log.** Cada recusa grava uma linha INFO. As mesmas repetições de B viram milhares de linhas por segundo no
   console. Correção: no máximo 1 linha por jogador a cada N segundos, mais um contador.

D. **O orçamento de tempo (injeção 2) é fraco.**
   1. Não é determinístico: a mesma ação pode passar ou falhar conforme o lag.
   2. Só cobre a fase A. As leituras do destino na fase C (`playerCanBreakAt(world, moved, ep)`) também carregam ou geram
      chunks, e ficam de fora.
   3. O contador só anda nos blocos que chegam ao `setBlock`. Blocos pulados (inquebráveis, ou com tile entity quando
      `SHIFTTILES=false`) não contam.
   4. Põe um callback com alocação dentro do laço.

   Substituto melhor: recusar no HEAD se algum chunk da origem ou do destino **não estiver carregado**. Isso é uma consulta
   de leitura pura, sem efeito colateral (seção 5).

E. **Bug existente que destrói blocos, alcançável com cliente normal, e que o desenho deixa passar.**
   - `World.setBlock` do Crucible devolve `false` sem colocar nada quando `y < 0` ou `y >= 256`
     (`build/Crucible/eclipse/cauldron/.../World.java:793-799`).
   - O `toDel.clearArea()` da origem roda antes.
   - Resultado: com dist 24 PARA CIMA a partir de y >= 232, ou PARA BAIXO a partir de y <= 23, os blocos da parte que sai
     do mundo **somem**.
   - O harness `ConfDecision` confirma a conta: origem y 232..240 para cima 24 dá destino y 256..264; origem y 0..23 para
     baixo 24 dá -24..-1.
   - O relatório trata isso como "fora do escopo". Mas é uma perda de blocos, e uma recusa no HEAD resolve sem mexer em
     nenhuma regra de custo. Proponho incluir, com chave própria.

F. **Recusar faz o jogador perder a taxa de armar.** `triggerAbility` já cobrou `use × 10` ao armar (CFR
   `Chromabilities.java:414-416,447-449`). Um shift grande que antes passaria agora é recusado, e essa taxa não volta. É o
   mesmo que acontece hoje na falha por falta de energia. Não proponho devolver a energia: recalcular a taxa (que depende do
   `costScale` do foco do Thaumcraft) arriscaria **criar** energia. Fica só documentado.

G. **O plano de calibração mede o caminho mais barato.**
   - `ReikaPlayerAPI.playerCanBreakAt` devolve `true` sem disparar evento nenhum quando o jogador é op e
     `DragonOptions.ADMINPERMBYPASS` está ligado (CFR `ReikaPlayerAPI.java:261`). O padrão dessa opção é `true` (CFR
     `DragonOptions.java:43`). O valor na cópia não foi lido.
   - O plano dá `op HoshieBot` e modo criativo. Criativo também pula `isUnbreakable` e a checagem de energia.
   - Ou seja, a fase C, que é a mais cara, não seria medida, e o `maxVolume` sairia alto demais.
   - Correção: rodar `/chromaprog maximize abilities` como op e depois `deop`. O bot fica em criativo pelo console e sem op,
     e assim dispara os `BreakEvent`. Rodar também um caso em survival com laje plana (custo 1) para medir `isUnbreakable`.

H. `require=1` com outra versão do ChromatiCraft derruba a carga da classe. O relatório já reconhece. O jar está fixo, e as
   linhas batem com a produção. Aceitável, desde que o teste 8.1 seja repetido a cada atualização.

I. **O foco do Thaumcraft não perde nada com a checagem de distância.** O foco limita a potência com
   `min(pwr, ceil(24*potency/5))` (CFR `ItemAbilityFocus.java:100`). Com potency até 5 isso dá no máximo 24 e passa.
   Ficam **não verificados**: que o Thaumcraft 4 limita potency a 5, e que o NBT `power` do foco nunca vem negativo sem
   edição manual.

Também conferi os plugins que reagiriam aos `BlockBreakEvent` falsos da fase C, como Jobs, mcMMO e CoreProtect. Nenhum está
instalado. O único script Skript com `on break` é `-realistic drops.sk`, e o `-` no começo do nome, pela convenção do Skript,
indica script desativado (**não verificado** em runtime).

## 4. Existe alternativa mais simples ou mais segura?
- **Só config:** não existe. `ChromaOptions` não tem opção para desligar habilidades nem para limitar o shift (CFR, só
  `SHIFTTILES` e outras sem relação). WorldGuard e GriefPrevention não ajudam, porque a checagem deles só roda depois do scan.
- **Não fazer:** o problema pesa 0% na média e aconteceu uma vez. Mesmo assim é um exploit de lag e de destruição de blocos,
  ao alcance de qualquer jogador com a habilidade. Vale fazer.
- **Mais simples e mais seguro:** um mixin com **uma única injeção no HEAD**, mais o log no RETURN. Sem a injeção 2. O
  desenho corrigido está abaixo.

## 5. Desenho corrigido (substitui as seções 5.1 a 5.5 do original onde diferir)

Alvo, carga e regras como no original: `@LateMixin` + `ILateMixinLoader`, listado só se `loadedMods` contém `ChromatiCraft`,
`remap=false`, `require=1`, `"minVersion": "0.8.5-GTNH"`, sem refmap.

**Injeção única de decisão:** `@Inject(method = "shiftArea(...)Z", at = @At("HEAD"), cancellable = true, remap = false, require = 1)`.
O handler é `private static`, com os 5 argumentos mais `CallbackInfoReturnable<Boolean>`. Todo o estado mora numa classe
auxiliar fora do pacote de mixins (por exemplo `ShiftGuard`), e qualquer membro dentro do mixin é `@Unique private static`.

Ordem das checagens, todas de leitura pura, sem tocar no mundo:
1. **Intervalo mínimo** (`cooldownMs`, padrão 1000): um `HashMap<String,Long>` pela chave `ep.func_70005_c_()`, com tempo de
   `System.nanoTime()`. O mapa cresce no máximo um item por jogador distinto, e um item velho só guarda um horário. Não precisa
   limpar.
2. **Distância** (`validateDistance`): recusar se `dist < 0 || dist > Chromabilities.SHIFT.getMaxPower()`.
3. **Volume:** `vol = (getSizeX()+1L)*(getSizeY()+1L)*(getSizeZ()+1L)`. O harness provou que isso é igual à contagem real do
   laço, inclusive com y de 0 a 255 e x,z em ±30M (9,2e17, abaixo de `Long.MAX`). Recusar se `vol > maxVolume`.
4. **Altura do destino** (`refuseOutOfWorld`, padrão true): calcular `dMinY = minY + dir.offsetY*(long)dist` e
   `dMaxY = maxY + dir.offsetY*(long)dist` (`ForgeDirection.offsetX/Y/Z`, `public final int`, javap no `server.jar`). Recusar
   se `dMinY < 0 || dMaxY > 255`. É um pouco mais restritivo que o necessário: recusa também quando só haveria ar na parte
   que sai do mundo. Isso é aceitável e fica documentado.
5. **Chunks carregados** (`requireLoadedChunks`, padrão true): para a caixa da origem e para a do destino (origem deslocada
   por `offset*dist`, como em `BlockArray.offset`, CFR linha 1064-1065), cada uma alargada 1 bloco em x e z (os vizinhos
   notificados pelo `setBlock` com flag 3), recusar se algum `world.field_73059_b.func_73149_a(cx, cz)` for falso.
   - `WorldServer.field_73059_b` é `theChunkProviderServer`, `public`.
   - `ChunkProviderServer.func_73149_a (II)Z` é `chunkExists`. Nos dois casos: joined.srg + javap.
   - No Crucible esse método só olha o mapa de chunks carregados (`ChunkProviderServer.java:98-101`).
   - Com o teto em 4096 isso dá no máximo cerca de 260 consultas a um hash. Com isso, o scan nunca carrega nem gera chunk.

   Fica **não verificado** se `Akashic-PlayerChunkAsync`/`Akashic-Crucible-ChunkAPI-Bridge` mudam esse método (vi apenas
   chamadores de `func_73149_a` nos jars, sem analisar cada um).

`refuse(ep, motivo)`:
- `ChromaSounds.ERROR.playSound(ep)`, depois `Chromabilities.SHIFT.setToPlayer(ep, false)`, depois a mensagem no chat
  (`ep.func_145747_a(new ChatComponentText(msg))`), depois `cir.setReturnValue(false)`.
- Log com limite de frequência: no máximo 1 linha a cada 10 s por jogador, mais um contador do que foi suprimido. A linha traz
  jogador, dimensão (`field_71093_bK`), caixa, volume, dist e motivo.
- Uma linha INFO única "1.B ativo" na primeira chamada, para o teste 8.1 provar que o mixin aplicou.

**Log de tempo:** `@At("RETURN")`, só para registrar (como a seção 5.3 original). Serve para calibrar.

**Removido:** a injeção 2 (`@At INVOKE FilledBlockArray.setBlock`) e a chave `scanBudgetMs`.

Tudo dentro de `try/catch (Throwable)`. Em caso de erro, cai no caminho original (sem proteção, sem dano).

**Config** (`chromaticraft_shift`):

| Chave | Padrão | Observação |
|---|---|---|
| `enabled` | true | Lida pelo loader. false = bytes originais |
| `maxVolume` | **4096** (provisório) | O original propunha 32768. Começar baixo é o lado seguro, e subir depois é só config. O valor final sai do teste 8.2 |
| `validateDistance` | true | |
| `refuseOutOfWorld` | true | Novo: impede a destruição de blocos fora de y 0..255 |
| `requireLoadedChunks` | true | Novo: substitui `scanBudgetMs` |
| `cooldownMs` | 1000 (0 = desligado) | Novo. Chute: um uso normal (GUI mais 2 cliques) leva mais de 1 s, **não verificado** |
| `logAboveMs` | 50 | |
| `refuseMessage` | como no original | Uma mensagem fixa por motivo |

**O que muda para os jogadores:** shifts acima de `maxVolume`, fora da altura do mundo, sobre chunks não carregados, ou com
menos de 1 s desde o anterior passam a ser recusados. A recusa traz o som de erro, desliga a habilidade e mostra uma mensagem.
A taxa de armar continua perdida, como na falha por energia que já existe. Nenhum bloco, energia ou item muda na recusa.
Dentro dos limites, o resultado é idêntico ao original.

**Correções no plano de teste (8.1/8.2):**
- Calibrar com o bot **sem op**: `/chromaprog maximize abilities` como op, depois `deop`, e `gamemode 1` pelo console. Rodar
  também 1 caso em survival com laje plana. Medir a **primeira** chamada após o boot (JIT frio) e a mediana das seguintes.
- Novos casos:
  - (i) origem y 236..240, dist 24 para cima: ANTES os blocos somem, DEPOIS é recusado e o checksum fica intacto;
  - (j) caixa com 1 chunk descarregado: recusado;
  - (k) dois shifts com menos de 1 s entre eles: o segundo é recusado;
  - (l) 1000 recusas seguidas: o log fica limitado.
- O caso (g) do original (`scanBudgetMs=1`) sai do plano.
- Critério para o `maxVolume` final: o maior volume com mediana de no máximo 100 ms **fria e sem op**. Propor 100 ms em vez
  de 500 é decisão de produto. O original usa 500, e o admin escolhe.

## 6. Não verificado (além da lista do original)
- O custo real em jogo por bloco das fases C e D. Meu "mais de 1 s a 32 768" é estimativa.
- Que `AbilityCalls` ainda não está carregada no estado CONSTRUCTING (provável, pela varredura).
- O comportamento de `chunkExists` com os mods Akashic de chunk.
- O valor de `ADMINPERMBYPASS` na cópia e na produção (valores de config não foram lidos).
- O limite de potency do Thaumcraft 4.
- Que o Skript não carrega scripts cujo nome começa com `-`.
- Se `"required": false` na config do mixin transformaria uma falha de `require=1` em aviso em vez de crash.
