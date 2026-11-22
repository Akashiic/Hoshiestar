# Conferência do desenho 1.F (Ars Magica 2) e 1.H (vigia de 2 s)

Conferente: agente diferente do autor. Só leitura: nada foi iniciado no servidor, o repositório
não foi editado e nenhum arquivo de config das cópias foi lido. O relatório conferido é
`<S>/etapa1/desenho/F-arsmagica.md` (não foi alterado).
`<S>` = `/tmp/claude-0/-home-user-Hoshiestar/86b4be55-47d2-5167-8109-96ebc6889fd2/scratchpad`.
Meus arquivos de trabalho ficam em `<S>/etapa1/F-conf/`. O harness está em `<S>/etapa1/F-conf/harness/`.

## Veredito curto

**Aprovado com correções.**
- **F1 e F2 estão certos.** Conferi as assinaturas no jar. A equivalência foi provada também com um
  harness que roda o bytecode **real** do AM2 (seção 3.1). Nenhum caminho cria ou perde item,
  energia ou fluido, nem mexe em dados salvos.
- **Corrigir antes de implementar:**
  - o plano de teste 8.1, cujos números esperados estão errados;
  - quatro pontos do vigia: privacidade dos campos de pacote, parada sem atraso, descoberta do
    mod dono e amostragem durante a supressão;
  - um nome de campo escrito errado.
- **Faltou um custo do próprio AM2:** `MysteriumPatchesFixesMagicka.hasWorldObj` custa **0,66%** e
  roda para todo tile entity que tica. Ele tem a mesma forma do problema ("monta texto e procura
  num mapa quase sempre vazio"). Proponho incluí-lo como F5, depois de uma conferência própria.

---

## 1. Assinaturas (javap e fonte descompilada)

Feito com `javap -p -s -c -l` do JDK 21 sobre as classes extraídas do jar que carrega
(`<S>/server-test3/mods/AM2.5-LTS-1.6.11-LE-CR40.jar`). Saída completa em
`<S>/etapa1/F-conf/AMEventHandler.full.txt`.

### 1.1 Alvos do F1/F2: todos corretos

| Item do desenho | Conferido | Onde |
|---|---|---|
| `am2.AMEventHandler.HandlePlayerSpellExtendedProperties(Lnet/minecraft/entity/player/EntityPlayer;Lam2/playerextensions/ExtendedProperties;)V`, private, de instância | ok | javap -p -s |
| Única chamada `invokestatic net/minecraftforge/common/DimensionManager.getWorlds:()[Lnet/minecraft/world/WorldServer;` no offset 440 | ok (1 ocorrência no método) | javap -c |
| Faixa do try 450 a 618, com handlers para `IndexOutOfBoundsException` e `Exception` (a chamada no 440 fica fora do try) | ok | Exception table |
| Linhas: laço = 775 a 799 (offsets 440 a 618); `func_82165_m` = linha 1175 (offsets 4397/4410) | ok | LineNumberTable |
| `onEntityLiving(Lnet/minecraftforge/event/entity/living/LivingEvent$LivingUpdateEvent;)V`, public | ok | javap |
| Offset 14 `SkillManager.getSkill:(Ljava/lang/String;)Lam2/api/spell/component/interfaces/ISkillTreeEntry;`, offset 17 `SkillTreeManager.isSkillDisabled:(Lam2/api/spell/component/interfaces/ISkillTreeEntry;)Z`, offset 20 `ifne 72`; uma ocorrência de cada no método | ok | javap -c |
| `public static Map acceleratedEntitiesUUIDs`, `slowedEntitiesUUIDs`, `slowedTiles` (`Ljava/util/Map;`); só o `<clinit>` faz `putstatic` (HashMap) | ok | javap -c `static {}` |
| `SkillManager.instance` (public static), `SkillTreeManager.instance` (public static final) | ok | javap |
| A interface `am2/api/spell/component/interfaces/ISkillTreeEntry.class` existe no jar | ok | unzip -l |
| `DimensionManager.getWorlds` no `server.jar`: `public static mt[] getWorlds()` (nome obfuscado `mt` = WorldServer). O FML traduz para `WorldServer` no carregamento, então o alvo do desenho bate em tempo de execução | ok (só uma observação) | javap no server.jar |
| modid do AM2 = `arsmagica2` (lista do late mixin) | ok | `@Mod` em AMCore e mcmod.info |
| `WrapOperation` e `ModifyReceiver` (MixinExtras) presentes em `+unimixins-all-1.7.10-0.3.1.jar`. `WrapMethod` também existe | ok | unzip -l |
| `AMEventHandler` é carregada só no `postinit` (`CommonProxy.java:148`), depois de os late mixins serem registrados | ok | fonte CFR |

### 1.2 Alvos do vigia (W1 a W6) e campos lidos

Conferidos em `build/Crucible/forge/fml/conf/joined.srg`, no javap do `server.jar` e na fonte Crucible:
- `MinecraftServer.func_71217_p()V` (`MD: net/minecraft/server/MinecraftServer/u ()V ... func_71217_p`).
  O `DedicatedServer` não sobrescreve esse método (grep na fonte). Ok.
- `World.func_72866_a(Lnet/minecraft/entity/Entity;Z)V`, `World.func_72939_s()V`,
  `TileEntity.func_145845_h()V`, `EntityPlayerMP.func_71127_g()V`: ok.
- W5 e W6 repetem os ganchos do medidor, já provados no pacote real (PROGRESSO, entrega 1.0.4).
- `org.spigotmc.WatchdogThread.currentPacket` / `currentPlayer`: `public static final AtomicReference` (javap). Ok.
- `func_146103_bH` (EntityPlayer.getGameProfile), `func_145782_y` (getEntityId), `func_145831_w`,
  `field_71093_bK`, `field_73011_w`, `field_76574_g`, `field_145851_c/field_145848_d/field_145849_e`: ok.

### 1.3 Erros encontrados

1. **Nome de campo errado no vigia.** O desenho diz "posição via `field_70165_t/u/v`". Só `field_70165_t`
   (posX) existe. Os certos são **posY = `field_70163_u`** e **posZ = `field_70161_v`**
   (`FD: sa/t ... field_70163_u`, `FD: sa/u ... field_70161_v` no joined.srg). Do jeito que está
   escrito, não compila, ou lê o campo errado se alguém "completar" o nome.
2. **Quem chama o método quente, e quantas vezes.** O desenho diz "uma vez por jogador por tick".
   Na verdade é **uma vez por pacote de movimento** (C03 e derivados):
   - `NetHandlerPlayServer.processPlayer` chama `onUpdateEntity()` (fonte Crucible, linhas 416/445/488);
   - `EntityPlayerMP.onUpdateEntity` chama `super.onUpdate()`, e é aí que sai o `LivingUpdateEvent`;
   - o cliente 1.7.10 manda um C03 a cada tick do cliente, ou seja ~20/s, mesmo parado
     (`EntityClientPlayerMP`, `new C03PacketPlayer(this.onGround)`).

   O profile confirma: 100% do método está abaixo de `EntityPlayerMP.func_71127_g`.
   Consequências: as contas "56.904 chamadas / 102 µs / 251 µs por jogador-tick" estão infladas.
   Com ~20 C03/s x 8 jogadores x 602 s, são ~96 mil chamadas (estimativa, não verificado), ~60 µs
   por `containsKey` e ~148 µs por chamada. **A conclusão não muda** (60 µs por `containsKey`
   continua impossível). Mas o custo acompanha os pacotes, não o TPS, e isso muda o teste 8.1
   (seção 3.2).
3. Detalhe: o bloco depois do laço roda a cada `(jogadores no mundo do jogador + 1) x 5` chamadas
   (`tick % ((field_73010_i.size()+1)*5)`, offsets 684 a 704), não "a cada ~45 jogador-ticks" fixo.
   Dá 45 só com os 8 no mesmo mundo.

### 1.4 Outros transformadores no mesmo método (o jeito comum de dois mods quebrarem um ao outro)

Varredura própria em todos os 226 jars de `mods/` e todos os de `plugins/`
(`<S>/etapa1/F-conf/scan.py`, procura os textos `AMEventHandler`, `acceleratedEntitiesUUIDs`,
`slowedEntitiesUUIDs`, `HandlePlayerSpellExtendedProperties`, e classes `IClassTransformer` que citam `am2`):
- **Nenhum outro jar transforma `am2.AMEventHandler`.** A única outra referência é
  `net.tclproject.mysteriumlib.asm.fixes.MysteriumPatchesFixesMagicka`, dentro do próprio jar do AM2.
  Ela **lê** `AMEventHandler.slowedTiles` (veja F5) e não transforma a classe.
- AM2PG (`PGClassTransformer`) no log de boot `medidor-test/t6`: transforma `am2/spell/SkillTreeManager`
  **só no método `init`** e não toca em `isSkillDisabled` nem `getSkill`. A lista de classes
  transformadas não inclui `AMEventHandler`.
- `chromatifixes` (`AM2PreloadContainerASM`) só mexe num campo de `am2.preloader.AM2PreloaderContainer`
  (CFR em `<S>/etapa1/decomp/F-conf/chromatifixes`).
- O próprio transformador do AM2 (`am2/preloader/BytecodeTransformers`) não cita `AMEventHandler` nem `getWorlds`.
- `Akashic-TickRelief` (`AM2PGRegenFixTransformer`) mexe em `ExtendedProperties.manaBurnoutTick`, que fica fora do nosso caminho.
- No vigia, o laço de tiles de `World.func_72939_s` é tocado por:
  - Akashic-World-Firewall (já tratado no W6);
  - DragonAPI (`Reika.DragonAPI.Instantiable.Event.TileUpdateEvent.fire` aparece entre `func_72939_s` e o tile no profile);
  - Mysterium (`TileEntity.hasWorldObj`).

  O par W5/W6 do medidor funcionou com essa combinação no teste do pacote real, então não há conflito novo.

---

## 2. Números (recalculados com o leitor)

Comandos (de `/home/user/Hoshiestar`, `R="python3 -I tools/spark_report.py <S>/anexos/spark/wIBxG0D4y9.json"`):

| Medida | Comando | Valor | Relatório |
|---|---|---:|---|
| `AMEventHandler.onEntityLiving` | `$R eventos --top 400` | 3,57% | igual |
| idem, caminho jogador / outros | `agg.py ... am2.AMEventHandler.onEntityLiving EntityPlayerMP.func_71127_g` | 2,47 / 1,09% | igual |
| `HandlePlayerSpellExtendedProperties` | `$R busca HandlePlayerSpellExtendedProperties` | 2,38% | igual |
| filhos: `func_82165_m` (tempo próprio), `getExtraVariablesContains`, `UUID.toString`, `getWorlds`, `HashMap.containsKey`, `HashedArrayList.size` | `agg.py ... HandlePlayerSpellExtendedProperties` | 1,94 / 0,28 / 0,09 / 0,05 / 0,005 / ~0% | igual |
| `func_82165_m` no profile todo | `$R busca func_82165_m` | 1,95% (11.704 ms, dos quais 11.628 ms abaixo do método AM2) | igual |
| `isSkillDisabled` / `getSkill` (filhos diretos de onEntityLiving) | agg.py | 0,26 / 0,07% | igual |
| `busca "am2."` | `$R busca "am2."` | 4,39% | igual, **mas incompleto** (abaixo) |
| `tickContingencySpell` | `$R busca tickContingencySpell` | 0,41% | igual |
| **`MysteriumPatchesFixesMagicka`** (código do AM2 fora do pacote `am2.`) | `$R busca MysteriumPatchesFixesMagicka` | **0,95%**, dos quais **`hasWorldObj` 0,66%**, `isOnLadder` 0,12%, `onUpdate(EntityItem)` 0,09% | **ausente no relatório** |

Conferências extras sobre a atribuição de 1,94% ao laço:
- O nó JSON de `func_82165_m` abaixo do método AM2 tem **tempo próprio puro e nenhum filho
  `HashMap.containsKey`** (`<S>/etapa1/F-conf/desc.py`). Nos outros chamadores, `func_82165_m` mostra
  `HashMap.containsKey` como filho. Um dos quatro nós tem `libjvm.so.OptoRuntime::new_instance_C`
  como filho, ou seja, uma alocação. Isso combina com `UUID.toString` inlinado do laço, não com
  `containsKey(Integer)`.
- O código depois do laço (offsets 633 a 704 e 4390 a 4523) é trivial: leitura de campos, as duas
  chamadas `func_82165_m` e um `func_70644_a`. Não sobra outro lugar para 11,6 s.
- O `vmArgs` do profile não tem `DebugNonSafepoints` (conferido no JSON).
- O spark não gravou números de linha (`lineNumber`/`parentLineNumber` = 0 em todos os nós), então
  o teste 8.2 continua necessário.

**Avaliação:** a inferência é forte. O ganho do F1 (≤ 2,08% + 0,005%) **não está exagerado em
relação ao profile**. Mas ele **só existe enquanto o mapa estiver vazio** (seção 3.3).
O F2 (≈ 0,35%) também confere.

---

## 3. Segurança (tentativa de quebrar)

### 3.1 Harness com o bytecode real do AM2 (F1)

`<S>/etapa1/F-conf/harness/`, rodado com JDK 8, `env -u _JAVA_OPTIONS`, `-Xverify:none`.
- `hoshie.Patch` (ASM 9.9.1 das libraries do servidor) pega o **`AMEventHandler.class` do jar**:
  - deixa só `<init>`, `<clinit>` e o método alvo, sem tocar no corpo;
  - na variante "patched", troca **a** chamada `DimensionManager.getWorlds` por `hoshie/Hook.getWorlds`.
    Esse hook faz exatamente o que o handler do F1 faria, com o interruptor lido em tempo de execução.
- Stubs mínimos das classes MC/AM2 (nomes SRG reais) contam cada `func_70071_h_()` por entidade.
  `ExtendedProperties` copia a lógica real de `getExtraVariablesContains` (contains + cópia).
- `hoshie.Run` sorteia, com semente fixa:
  - 3 mundos com entidades vivas e não vivas, e 3 jogadores;
  - lançamentos de "accelerate" (variável `accelerated_fast_entity_<n>_<uuid>`) em entidades
    existentes e inexistentes;
  - jogadores saindo e voltando (ficam sem tick, deixando entradas velhas no mapa);
  - mortes e nascimentos de entidades.

  A cada iteração ele soma num hash as atualizações por entidade, o mapa estático e as variáveis
  de cada jogador.

Resultado (`run-log.txt`): 8 sementes x 20.000 iterações. O hash final é **idêntico** entre
original, patched com interruptor ligado e patched desligado em todas as sementes. Com o
interruptor ligado foram puladas de 12.340 a 19.608 varreduras por execução. Exemplo da semente 1:
original, ligado e desligado deram `trace=fc6e9e5ca29b6d19`, `extraUpdates=13002`, e o ligado
pulou 12.851 varreduras. Isso cobre o "interruptor desligado = original" e o caso de o mapa
encher e esvaziar no meio.

Limites do harness: usa stubs, não Minecraft. A troca de chamada simula o efeito do
`@WrapOperation`, não o Mixin em si (isso é o teste 8.1). O F2 não foi para o harness. Sua
equivalência sai direto do bytecode (offsets 14 a 20, desvio para 72).

### 3.2 Cenários tentados

| Tentativa | Resultado |
|---|---|
| Uma entidade fica acelerada no meio de um tick (outro jogador põe a entrada) | O `isEmpty()` é lido a cada chamada, na mesma thread e no mesmo ponto em que o original leria. Igual ao original (harness). |
| A entrada é posta **na mesma chamada**, depois do laço (bloco 707+) | O original também só vê na chamada seguinte, porque o laço (440) vem antes do `put` (fonte CFR 811). Igual. |
| Alguém mexe nos mapas fora da thread do servidor | Só `AMEventHandler` escreve nos 3 mapas (puts e removes nas linhas 806/811/825/830/836/841; varredura de jars). Não há corrida nova. |
| F2: o interruptor muda entre o `getSkill` e o `isSkillDisabled` (recarga por outra thread) | Inofensivo. `isSkillDisabled(null)` devolve `false` (`SkillTreeManager.java:362-367`: `getSkillTreeEntry(null)` dá null), e o mapa continua vazio, então nada é cancelado. Mesmo assim, recomendo ler o interruptor uma vez por chamada (5.2). |
| Itens, energia, fluidos, quests, NBT | Nenhum caminho pulado escreve estado. A única ação do laço é `func_70071_h_()` em entidades do mapa (que está vazio). Nada a criar ou perder. |
| Descarregar/recarregar mundo, trocar de dimensão | Não há cache. O mapa é do AM2 e é lido ao vivo. Nada fica velho por nossa causa. |
| Desligamento | F1 e F2 não têm thread nem estado. Para o vigia, veja 3.4. |
| Exceção | Com o mapa vazio e o F1 ligado, uma exceção estranha dentro do laço deixa de ser impressa. Só muda o log, como o desenho diz. |
| `@WrapOperation` cria um objeto `Operation` por chamada (lambda) | Custo pequeno. O JIT costuma eliminar (não verificado). No F2 são 2 por entidade viva por tick, contra as 4 cópias de ArrayList e a String que ele remove. Ganho líquido. |

**Erro no plano de teste 8.1.** Os números esperados estão errados e o teste falharia com código correto:
- O desenho espera "porco acelerado envelhece 600 em 200 ticks". Isso não acontece: `ticksExisted`
  (`field_70173_aa`) é incrementado em `World.updateEntityWithOptionalForce`
  (`World.java:2675/2681/2698` do Crucible), **não** em `func_70071_h_()`. Ele fica em ~200 nos dois porcos.
- As atualizações extras acompanham os **pacotes C03 do bot**, não os ticks (1.3, item 2).
- Correção: contar `LivingUpdateEvent`s não cancelados (ouvinte de prioridade LOWEST do comando de
  teste) do porco marcado (M), do não marcado (U) e do bot (B, que é igual ao número de chamadas do
  método AM2), na mesma janela. Esperado, igual com F1/F2 desligado e ligado:
  - accel n: `M − U = n × B` (1 jogador), e `ticksExisted` igual nos dois porcos;
  - slow n=2: `M ≈ U/2` (o `ticksExisted` continua andando, porque o World incrementa antes do evento);
  - mapas vazios: `M = U`.

  Repetir a contagem com 2 bots: `M − U = n × (B1 + B2)`.
- O lançamento do vigia "Packet: C01PacketChatMessage" também precisa da correção de privacidade (3.4).

### 3.3 O ganho pode sumir, sem dano

As entradas só saem do mapa no tick do **próprio lançador** (CFR 806/825). Se ele sair com o
efeito ativo, a entrada fica no mapa estático até ele voltar ou até o servidor reiniciar. Com o
"ConcentrateTime" desligado na config, as entradas nunca saem (linha 798 limpa só a cópia).
Nos dois casos o F1 nunca se aplica: ganho zero, dano zero. No harness, com saídas sorteadas, o
mapa ficou não vazio em 35 a 70% das chamadas. Com o mapa não vazio, o custo volta inteiro
(O(jogadores x entidades)). **A linha `am2.logStatsMinutes` é obrigatória**, não opcional: é ela
que diz se o ganho existe em produção.

### 3.4 Vigia: problemas concretos

1. **Privacidade (corrigir).** O desenho lê "campos declarados primitivos **ou String**" do pacote atual.
   - Num `C01PacketChatMessage`, a String é o texto da mensagem. Iriam para o log mensagens
     privadas (`/msg`, `/tell`, `/r` do EssentialsX estão na lista de plugins) e qualquer senha ou
     segredo digitado no chat.
   - O `C12PacketUpdateSign` levaria o texto de placas.
   - Correção: só a classe do pacote mais campos **numéricos ou booleanos**, nunca String. Para
     C01, no máximo "chat/comando, N caracteres" ou só a primeira palavra quando começa com `/`.
     Placas: nada de texto.
2. **Atraso no desligamento (deixar explícito).** O laço "espera o tick acabar ou 30 s" precisa olhar
   a flag `stopping` a cada volta. O handler de `FMLServerStoppingEvent` não pode dar `join()` sem
   tempo limite (no máximo 1 s, ou nenhum: a thread é daemon). Senão, um relatório em andamento
   atrasa o `stop` em até 30 s.

   Nota: o evento de parada sai depois do laço de ticks e antes do `stopServer()`
   (`MinecraftServer.java:699` e `754`), e o salvamento final fica fora de `func_71217_p`. Por isso
   o "sem relatório no desligamento" já é natural.
3. **Descobrir o mod dono via `Launch.classLoader.findResource` (trocar).** Este servidor sobe pelo
   **RetroFuturaBootstrap** (manifesto do `server.jar`, linhas `RFB-Main` no log). O comportamento e
   o lock desse método nele não foram verificados, e a thread do vigia poderia esperar um lock do
   carregador de classes.

   Alternativa sem esse risco: na partida, a thread principal guarda a lista `arquivo -> modid`
   (`ModContainer.getSource()`). A própria thread do vigia, só no primeiro relatório, abre esses
   jars com `java.util.zip.ZipFile` e monta um índice "prefixo de pacote -> jar". Incluir também
   os jars de `plugins/`, senão Skript e companhia aparecem como "?".

   Métodos com nome `handler$`, `wrapOperation$`, `redirect$` ou `modify$` são código de mixin
   injetado: marcar como tal.
4. **Amostrar só se o relatório vai sair.** Hoje o desenho tira as 5 amostras de pilha e só depois
   pergunta ao limitador. Numa sequência de ticks lentos, isso amostra todos. Perguntar ao limitador
   antes e, se ele negar, só contar.
5. **Pacote velho.** O Crucible não limpa `currentPacket` se `processPacket` lançar exceção
   (`NetworkManager.java:256-271`, sem `finally`). O `currentPlayer` continua preenchido durante
   `onNetworkTick()`. Rotular o pacote como "último pacote visto", e não como "pacote atual com certeza".
6. GC no relatório: em JDK 21, o bean "G1 Concurrent GC" provavelmente mede as pausas de
   remark/cleanup (não verificado). Mostrar o tempo por bean, em vez de uma soma sem rótulo.

O resto do vigia está correto:
- o lado da thread principal só faz `lazySet`;
- nunca interrompe nada;
- convive com o `WatchdogThread` do Spigot (só lê as duas AtomicReference);
- ligado por padrão, como diz a decisão 16 do PROGRESSO.

Continua obrigatório que o código do vigia seja revisado por outro agente (regra do PROMPT).

---

## 4. Alternativa mais simples, ou o que faltou

- **Só config?** Dá para desligar a habilidade "Accelerate" na config de skills do AM2
  (`AMCore.java:210`, `enabled_accelerate`). Isso elimina o laço, mas **tira um feitiço dos
  jogadores**: muda o jogo. Não recomendado. O F1 é a variante menos intrusiva.
- **Não fazer?** O F1 é barato, exato e tem interruptor. Vale fazer. O ganho real depende de 3.3.
- **F5, o custo esquecido (recomendo acrescentar, depois de conferência própria):**
  - O que roda hoje: o coremod do AM2 (Mysterium) substitui `TileEntity.hasWorldObj()`
    (`func_145830_o()Z`; log de boot: "Fixed method net.minecraft.tileentity.TileEntity#hasWorldObj()")
    por `MysteriumPatchesFixesMagicka.hasWorldObj(Lnet/minecraft/tileentity/TileEntity;)Z`
    (public static; javap). A cada chamada ele monta a String `x_y_z_dim` e procura em
    `AMEventHandler.slowedTiles`, que é quase sempre vazio.
  - Quem chama: o DragonAPI (`TileUpdateEvent.fire`), para **todo tile que tica, a cada tick**.
  - Custo: **0,66%** (`$R busca MysteriumPatchesFixesMagicka`). O único filho é
    `HashMap.containsKey`, de novo a montagem inlinada.
  - Por que um atalho é exato: com o mapa vazio, a função devolve `te.func_145831_w() != null` e
    não tem efeito colateral. O contador `tileticks` só anda quando a chave existe.
  - Desenho mínimo: mixin **early** em `net.minecraft.tileentity.TileEntity`,
    `@Inject(method = "func_145830_o()Z", at = @At("HEAD"), cancellable = true, require = 0, remap = false)`.
    - Quando o interruptor `am2.skipSlowTileKey` está ligado e o mapa está vazio:
      `cir.setReturnValue(((TileEntity)(Object)this).func_145831_w() != null)`.
    - O mapa vem de uma referência guardada uma vez, por reflexão, na partida. O campo só é
      escrito no `<clinit>`. Se o AM2 não estiver presente, nada acontece.
    - **Chamar o getter virtual `func_145831_w()`, não o campo `worldObj`**, para dar o mesmo
      resultado que o Mysterium para subclasses que sobrescrevem o getter.
  - Ordem com o transformador do Mysterium: o `HEAD` do Mixin fica antes da chamada inserida
    quando o Mixin roda depois (o medidor mostrou o Mixin na posição 130, depois da maioria). Se
    rodar antes, o Mysterium se põe na frente e o atalho nunca é alcançado: sem ganho e sem dano.
    O autoteste diz qual dos dois aconteceu.
  - Alternativa: um late mixin direto em `MysteriumPatchesFixesMagicka.hasWorldObj`. Não
    verificado se essa classe já está carregada antes de os late mixins serem registrados.
  - O `CallbackInfoReturnable` cancelável aloca um objeto por chamada, a menos que o JIT elimine
    (não verificado). Mesmo assim é menos que a String de hoje.
- `isOnLadder` (0,12%) e `onUpdate(EntityItem)` (0,09%) do Mysterium fazem trabalho real. Nada a ganhar.

---

## 5. Desenho corrigido (só o que muda)

1. **F1**: igual ao desenho. Acrescentar `@Unique` no `NO_WORLDS` e manter a linha de estatística
   (agora obrigatória).
2. **F2**: igual. Para não depender de "mesma thread", o wrapper de `getSkill` grava um campo
   `@Unique private static boolean hoshiestar$skipped` e o de `isSkillDisabled` lê esse campo e o
   zera. Assim um interruptor recarregado no meio do caminho não divide a decisão. Já é inofensivo
   hoje (3.2); é só higiene.
3. **F5 (novo, ganho até 0,66%)**: como na seção 4, interruptor `am2.skipSlowTileKey`, padrão `false`
   no primeiro jar. Precisa de uma conferência própria antes de entrar.
4. **Vigia**:
   - posY/posZ = `field_70163_u` / `field_70161_v`;
   - nada de String de pacote;
   - stop sem `join` longo, e espera que olha `stopping`;
   - dono por índice de jars (mods e plugins) montado pela thread do vigia;
   - limitador consultado antes de amostrar;
   - pacote rotulado como "último visto";
   - GC por bean.
5. **Teste 8.1**: trocar `ticksExisted` pelos invariantes de contagem de eventos da seção 3.2
   (`M − U = n × B`, `M ≈ U/2`, `M = U`), com 1 e com 2 bots.

   Teste novo para o F5: `setblock` de um funil e contagem de ticks do funil com o interruptor
   desligado e ligado, com `slowedTiles` vazio e com uma entrada de teste nas coordenadas do funil.
6. **Teste 8.2**: sem mudança. É ele que confirma a atribuição. Acrescentar a contagem de amostras
   em `MysteriumPatchesFixesMagicka.hasWorldObj` antes e depois do F5.

## 6. Não verificado (do conferente)

- ~96 mil chamadas no período (depende de os 8 clientes mandarem 20 C03/s; o profile não conta pacotes).
- Se o JIT elimina os objetos `Operation`/`CallbackInfoReturnable` por chamada.
- Ordem real do transformador Mysterium em relação ao Mixin para `TileEntity.func_145830_o`.
- Se `MysteriumPatchesFixesMagicka` já está carregada antes do registro dos late mixins.
- Semântica do bean "G1 Concurrent GC" no JDK 21.
- Comportamento de `findResource` sob o RetroFuturaBootstrap (por isso a troca proposta).
