# Vigia de tick lento (`vigia`, item 1.H)

Quando um tick do servidor passa de 2 s, o vigia grava **quem, onde e qual mod**: a pilha da
thread do servidor (algumas amostras), a entidade ou o tile que estava sendo atualizado, o último
pacote de rede e o jogador dele, as pausas de coleta de lixo (GC) e a lista de mods (jars) que
aparecem na pilha. O relatório vai para o log do servidor e para um arquivo
`hoshiestar/vigia-AAAA-MM-DD_HH-MM-SS-t<tick>.txt`.

É **o único recurso do Hoshiestar ligado por padrão**. Ele só lê e escreve relatório: não muda nada
no jogo, não interrompe, não suspende e não para a thread do servidor.

Termos: **tick** = um passo do jogo (normal: 50 ms; o servidor tenta 20 por segundo).
**Thread** = linha de execução. **Pilha** (stack trace) = a lista de métodos que a thread está
executando naquele instante, do mais interno (topo) para o mais externo. **GC** = coleta de lixo da
JVM (pode pausar o jogo). **Mixin/transformador** = código de mod que altera classes do jogo quando
elas carregam.

Desenho e conferência: `docs/pesquisa/etapa1/F-arsmagica.md` (parte 1.H) e
`docs/pesquisa/etapa1/F-arsmagica-conferencia.md`; motivação: `docs/pesquisa/stall-report.md`
(o travamento de 30 s do ChromatiCraft em que ninguém soube quem clicou).

---

## 1. Como funciona

**Na thread do servidor (caminho quente), só anotações:**

| Onde (patch `// Hoshiestar`) | O que anota |
|---|---|
| `MinecraftServer.tick()`, 2ª linha (depois do `++Relogio.ticks`) | `Marcas.inicioTick()`: hora de início do tick (`System.nanoTime`), limpa entidade/tile |
| `MinecraftServer.tick()`, última linha | `Marcas.fimTick()`: fim do tick; se ele passou do limite, guarda a duração exata |
| `World.updateEntities()`, laço de entidades, logo depois do `loadedEntityList.get(...)` | `Marcas.entidade(entity)`; depois do laço, `Marcas.entidade(null)` |
| `World.updateEntities()`, laço de tiles, logo depois do `loadedTileEntityList.get(...)` | `Marcas.tile(tileentity)`; depois do laço, `Marcas.tile(null)` |
| `EntityPlayerMP.onUpdateEntity()` (atualização do jogador, chamada pelo pacote de movimento C03) | `Marcas.entidade(this)` no começo, `null` no fim |
| `MinecraftServer.stopServer()`, 1ª linha | `Vigia.parar()` (espera no máximo 0,8 s; dentro de try/catch) |

O pacote e o jogador atuais **já são publicados pelo Crucible** (`WatchdogThread.currentPacket` e
`currentPlayer`, escritos em `NetworkManager.processReceivedPackets`); o vigia só lê.

**Na thread própria `Hoshiestar Vigia`** (daemon, criada quando o servidor termina de ligar):
1. A cada 100 ms lê o início do tick em andamento.
2. Se o tick passou do limite (`vigia.limite_ms`), pergunta ao limitador **antes** de amostrar. Se o
   limitador nega, só conta ("sem relatório").
3. Se pode, tira até `vigia.amostras` amostras da pilha (`Thread.getStackTrace`, a cada
   `vigia.intervalo_amostras_ms`), lendo entidade/tile/pacote/jogador antes e depois de cada amostra.
   Se o tick acabar antes de a 1ª amostra ser conferida, ela fica no relatório marcada "ATENCAO: o tick
   acabou antes..." e não entra na lista de mods nem no contexto.
4. Grava UM relatório **logo depois das amostras** (não espera o tick acabar). Se o tick acabou durante
   as amostras, o relatório diz a duração exata ("ja terminou"); se não, diz "CONTINUA TRAVADO ha X ms" e,
   quando esse tick terminar, sai uma linha no log com a duração total. Num travamento longo, enquanto o
   mesmo tick continua, sai outro relatório cada vez que o limitador deixa (`vigia.intervalo_minimo_segundos`).
   **Se a parada do servidor chega com amostras já colhidas** (stop, SIGTERM, reinício pelo watchdog do
   Spigot), o relatório é gravado na hora, marcado `SERVIDOR PARANDO` (correção I1 da revisão: antes ele
   era jogado fora, justo quando o admin reage ao travamento).
4b. O vigia olha a cada 100 ms e só durante o tick: um tick que passa do limite por menos de 100 ms
   pode acabar antes de ser visto. Ele não fica invisível: `Marcas.fimTick` anota todo tick acima do
   limite, e o vigia o conta como "visto só pelo fim (sem amostra)" (no `hoshie status` e no próximo
   relatório). Se vários assim acabarem enquanto o vigia está no meio de uma amostragem com intervalo
   longo, só o último é contado.
5. Nos primeiros `vigia.carencia_segundos` (30 s) depois do boot, ticks lentos só são contados (uma
   linha no log): o primeiro tick depois do boot passou de 2 s nos três boots de teste (3401 e 2289 ms).

O **mod dono** de cada linha da pilha sai de um índice "pacote Java → jar" montado pela própria
thread do vigia, no primeiro relatório, lendo com `ZipFile` os jars de `mods/` e `plugins/` (até 2
níveis de subpasta). Busca pelo prefixo de pacote mais longo. Não usa o class loader do jogo. O
`server.jar` (achado pelo `java.class.path`) e `libraries/` também entram, marcados "do servidor": um
pacote que existe neles (ex.: `org.slf4j`, que o DiscordSRV embute) não é atribuído a mod nenhum. Só
`cpw.mods.fml.` é neutro (antes era `cpw.mods.`, que escondia o IronChest, `cpw.mods.ironchest`). Métodos
com nome de código injetado por Mixin (`handler$`, `redirect$`, `wrapOperation$`, `modify$`...) saem
como `mixin <config>`. Classes sem pacote (as `ASMEventHandler_*` que o FML gera) não têm dono.
No modpack: 327 jars, 11 504 pacotes, índice montado em 262 ms.

**Exemplo real** (boot 3, jogador entrando; arquivo em
`<S>/fork/testes/vigia/boot3-relatorios/vigia-2026-11-22_22-29-31-t639.txt`, resumido):

```
TICK LENTO #639: 2823 ms (ja terminou); limite 2000 ms; thread 'Server thread'.
  GC durante o tick (por coletor; desde antes do tick comecar): G1 Young Generation 0 ms em 0 coleta(s); ...
  Ultimo pacote visto (o Crucible nao limpa se o pacote falhar): FMLProxyPacket (...) campos: timestamp=...
  Jogador do pacote: VigiaBot, dim 0, pos 66.5 64.0 294.5 (o mesmo em 2/2 amostras)
  Entidade em atualizacao: EntityPlayerMP (...) jogador VigiaBot, id 1581, dim 0, pos 66.5 64.0 294.5 (em 1/2 amostras)
  Tile em atualizacao: nenhum
  Mods na pilha (mais amostras primeiro; empate: o mais perto do topo): MatterOverdrive.jar (matteroverdrive.starmap.data.Planet.writeToBuffer; em 1/2 amostras) | ...
  Amostra 2 (2552 ms depois do inicio do tick; entidade: EntityPlayerMP; tile: nenhum):
    at ...NBTTagCompound.func_150298_a(NBTTagCompound.java:384)
    at ...matteroverdrive.starmap.data.Planet.writeToBuffer(Planet.java:256)  [MatterOverdrive.jar]
    ...
```

---

## 2. Chaves do `hoshiestar.yml` (seção `vigia:`)

| Chave | Padrão | Faixa | O que faz |
|---|---|---|---|
| `vigia.ligado` | `true` | | Liga o vigia. `false` = nenhuma anotação (as marcas viram constante falsa e o JIT as apaga) e nenhuma thread: o código original. |
| `vigia.limite_ms` | `2000` | 500..600000 | Duração de tick que gera relatório. |
| `vigia.amostras` | `5` | 1..20 | Amostras da pilha enquanto o mesmo tick continua lento. |
| `vigia.intervalo_amostras_ms` | `500` | 50..10000 | Intervalo entre amostras. |
| `vigia.intervalo_minimo_segundos` | `60` | 1..3600 | Intervalo mínimo entre dois relatórios (também entre relatórios do mesmo tick travado). |
| `vigia.max_relatorios` | `10` | 1..1000 | Máximo de relatórios por janela. |
| `vigia.janela_minutos` | `60` | 1..1440 | Tamanho da janela do máximo acima (janela fixa). |
| `vigia.quadros` | `40` | 5..200 | Linhas de pilha por amostra (amostra igual à anterior sai como "pilha igual a anterior"). |
| `vigia.nome_do_jogador` | `true` | | Escreve o nome do jogador (do pacote e da entidade em atualização). `false` = "(nome oculto)", com dimensão e posição. |
| `vigia.max_arquivos` | `200` | 10..10000 | Máximo de `vigia-*.txt` guardados por pasta; os mais velhos são apagados (só arquivos com esse padrão de nome). |
| `vigia.carencia_segundos` | `30` | 0..3600 | Depois do boot, ticks lentos só são contados nesse período; um tick que continuar travado depois dela gera relatório. |

Valor fora da faixa: aviso no log e o padrão. Fixo no código (não é chave): o vigia confere a cada
100 ms, grava o relatório logo depois das amostras (sem esperar o fim do tick), e `parar()` espera no
máximo 0,8 s.

**Privacidade.** O relatório **nunca** leva IP nem texto de pacote: do pacote, só a classe e os
campos numéricos ou booleanos (int, long, short, byte, float, double, boolean), nunca String, char,
array ou objeto. Campos só de classes `net.minecraft.*` e `cpw.mods.fml.*` (pacotes do jogo e o
`FMLProxyPacket`); de pacote próprio de mod sai só o nome da classe, com "(campos de classe de mod nao
lidos)": ler a lista de campos faria a JVM carregar o tipo de cada campo pelo class loader do jogo, na
thread do vigia (achado M4 da revisão). Um `C01PacketChatMessage` (chat, `/msg`, senha digitada no chat) sai só como o nome
da classe; um `C12PacketUpdateSign` (placa) também. O nome do jogador sai por padrão (o admin pediu,
D-21; padrão `true`); `vigia.nome_do_jogador: false` tira (sai "(nome oculto: ...)", com dimensão e posição). Nenhum `toString()` de entidade, tile ou pacote é chamado.
Os nomes dos campos do pacote saem como o jogo os tem em tempo de execução (`field_149479_a`...).

**Arquivo.** Pasta principal `hoshiestar/` (ao lado do `hoshiestar.yml`). Se não der para gravar, a
pasta de reserva `logs/hoshiestar-vigia/`, dentro do servidor (antes era `/tmp/hoshiestar-vigia`, pasta
comum a todos os usuários da máquina; achado M3); se nenhuma, o relatório fica só no log, com o
motivo. O caminho aparece na última linha do relatório no log.

**GC.** A soma das pausas e o aviso "a causa provável é GC" foram conferidos para o G1 (o coletor do
servidor de teste): "G1 Young/Old Generation" contam como pausa, "G1 Concurrent GC" não. Outros
coletores são tratados pelo nome do bean, sem teste em servidor: com ZGC/Shenandoah só os beans
"... Pauses" contam (os "... Cycles" são trabalho concorrente); CMS (`ConcurrentMarkSweep`, Java 8) conta
como pausa e o texto avisa que pode incluir trabalho concorrente.

**`hoshie status`**: `vigia: ligado | limite 2000 ms; thread viva; ticks lentos: N; relatorios: N (k/10
na janela); sem relatorio (limitador): N; maior tick lento: X ms; ultimo relatorio ha S s; na carencia
do boot: ...; indice de jars: ...`.

**Subcomando de teste** (só com `-Dhoshiestar.vigia.teste=true` na JVM; desligado por padrão; só
console): `hoshie vigia-teste <comando|tile|entidade|jogador> <ms>`. `comando` dorme no próprio
comando; `tile` põe na lista de tiles do mundo 0 um tile de teste (y=255 sobre o spawn, sem bloco,
fora do mapa do chunk, nunca salvo) que dorme no 1º update e se invalida; `entidade`/`jogador` armam um
ouvinte de `LivingUpdateEvent` que dorme na próxima entidade viva (não jogador) ou no próximo jogador.
**Nunca ligue essa propriedade em produção.**

---

## 3. Arquivos

| Arquivo (workspace) | O que é |
|---|---|
| `src/main/java/hoshiestar/vigia/Marcas.java` | As marcas do caminho quente (só JDK + config). |
| `src/main/java/hoshiestar/vigia/NucleoVigia.java` | O laço da thread e a montagem do relatório. Sem Minecraft: os testes isolados rodam este mesmo código. |
| `src/main/java/hoshiestar/vigia/LimiteDeRelatorios.java` | Intervalo mínimo + máximo por janela. |
| `src/main/java/hoshiestar/vigia/IndiceDeJars.java` | Índice "pacote → jar" com `ZipFile`. |
| `src/main/java/hoshiestar/vigia/CamposNumericos.java` | Descrição do pacote sem texto. |
| `src/main/java/hoshiestar/vigia/SaidaEmArquivo.java` | Arquivo + log, pasta de reserva, limpeza dos velhos. |
| `src/main/java/hoshiestar/vigia/Vigia.java` | Registro, ajustes do yml, partida/parada, status; fontes de servidor (tick, pilha, GC por MXBean, donos). |
| `src/main/java/hoshiestar/vigia/ContextoMinecraft.java` | Lado Minecraft: lê `WatchdogThread.currentPacket/currentPlayer`, posição, dimensão, coordenadas. |
| `src/main/java/hoshiestar/vigia/ComandoTesteVigia.java` | Subcomando de teste (só com a propriedade). |
| `patches/net/minecraft/server/MinecraftServer.java.patch`, `patches/net/minecraft/world/World.java.patch`, `patches/net/minecraft/entity/player/EntityPlayerMP.java.patch` | Marcas (geradas por `gerar-patches.sh`). |
| `src/main/java/hoshiestar/config/HoshiestarConfig.java` (bloco `// ---- vigia ----`), `Catalogo.java` (1 linha), `Servidor.java` (partida e subcomando) | Ligações. |
| `src/test/vigia/` | Testes isolados (`teste-vigia.bash`). |

---

## 4. Argumento de segurança

1. **Só leitura do jogo.** Na thread do servidor, as marcas só escrevem campos estáticos do próprio
   Hoshiestar (`Marcas`); nada do jogo é escrito, nenhum valor do jogo muda, nenhum desvio de fluxo
   é criado. Nenhum item, energia, bloco ou dado salvo passa pelo vigia.
2. **Nunca lança no caminho quente.** Os métodos de `Marcas` fazem só `lazySet` em `AtomicReference`
   `static final` (nunca nulo), escrita de `volatile long` e `System.nanoTime`. A inicialização da
   classe lê o interruptor dentro de try/catch (config ilegível = marcas desligadas). Por isso as
   chamadas ficam **sem try/catch** no ponto de chamada (ver Desvios: o try/catch derrubou o boot).
3. **Custo** (pedido: desprezível). Por tick: 2 `System.nanoTime`, uma comparação, 4 escritas (6 se
   o tick foi lento). Por entidade e por tile: **1 escrita** (`lazySet`, sem barreira completa) + 1 por
   laço para limpar; jogador: 2 por atualização. Nenhuma alocação, mapa ou `nanoTime` por entidade.
   Medida de indicação (harness, não servidor): ~0,5 ns por marcação no JDK 21 (≈0,01–0,1 ns no JDK 8),
   par início+fim de tick ~71 ns. Desligado: `LIGADO` é `static final false` e o JIT apaga as marcas
   (medido: 0,00 ns de diferença numa JVM com config ilegível).
4. **Nunca mata nada.** Nenhum `interrupt`, `stop`, `suspend` ou `System.exit` em código do vigia
   (`grep` em `src/main/java/hoshiestar/vigia/`: só `Thread.currentThread().interrupt()` para
   devolver a interrupção a quem chamou `parar()`). `getStackTrace` de outra thread: no Java 21 a JVM
   para só a thread alvo por um instante (handshake); no Java 8 é uma parada geral curta de todas as
   threads (safepoint). Só depois de o tick já passar do limite, no máximo `vigia.amostras` vezes por relatório.
5. **A parada não atrasa.** `Vigia.parar()` (1ª linha de `stopServer`) marca a flag, acorda a thread e
   dá `join` de no máximo 0,8 s; toda espera da thread do vigia (`Object.wait` com prazo) olha a flag.
   Com a flag, o vigia não tira amostra nova: se já tem amostras, monta e grava o relatório
   (`SERVIDOR PARANDO`; texto + arquivo + log, milissegundos; no 1º relatório o índice de jars leva
   ~0,2–0,3 s) e termina. Se o `join` de 0,8 s esgotar (saída presa, `getStackTrace` preso), `parar()`
   volta assim mesmo, marca `desistiu` e a thread do vigia, se soltar depois, não grava mais nada (a
   JVM pode estar saindo). A thread é daemon. Testes isolados: `parar()` em 0 ms no meio das amostras,
   entre amostras de 5 s e esperando o fim do tick, com o relatório gravado; saída travada: 800 ms.
   Servidor real: ver 6.
6. **Travas.** A thread do vigia só segura o próprio monitor `espera` (solto durante o `wait`) e a trava
   do `LimiteDeRelatorios` (só ela usa). O índice de jars não usa o class loader, e os campos de pacote
   só são lidos de classes do Minecraft/FML (cujos tipos já estão carregados), então o vigia não carrega
   classe pelo class loader do jogo (risco residual: mod que acrescente por ASM/mixin um campo de tipo
   próprio a um pacote do Minecraft; não visto). Compartilhadas com o jogo, por instantes: o Log4j (os
   appenders são sincronizados; no pior caso a thread do servidor espera um log do vigia, uma vez por
   relatório) e, no Java 8, a parada geral do `getStackTrace` (item 4).
7. **Convivência** (provado no boot do modpack, cópia `vigia1`):
   - **Spigot `WatchdogThread`**: não alterado; o vigia só faz `get()` das duas `AtomicReference`.
   - **Akashic-World-Firewall** (`PassiveItemTransformer` troca a chamada de update de tile em
     `World.updateEntities` por `WorldMutationGuard.runTileEntityUpdate`): log `Patched TileEntity tick
     gate at World#updateEntities.` nos 3 boots; no teste `tile`, a pilha passa por
     `WorldMutationGuard.runTileEntityUpdate` com o tile de teste anotado.
   - **Opis/MobiusCore** (`TransformerWorldCrucible` procura sequência exata `[linha, ALOAD, INVOKEVIRTUAL
     updateEntity]` e `[linha, ALOAD, ALOAD, INVOKEVIRTUAL World.updateEntity]` e injeta usando as
     variáveis locais 9 e 2): `Successful injection in h ()V` (4 vezes) nos 3 boots.
   - **DragonAPI** (`TileUpdate` apaga o trecho entre o 1º `isInvalid()` e o 1º `blockExists(III)` do
     laço de tiles): a marca de tile fica antes desse trecho.
   - **CoFHCore** (ancora em `onChunkUnload`/`field_147481_N`) e **Hodgepodge/SaveRelief** (mixins):
     não tocados pelas marcas (pontos diferentes; mixin casa por alvo, não por posição).
   - **PlayerAPI** troca `EntityPlayerMP.onUpdateEntity` por `localOnUpdateEntity` + `ServerPlayerAPI`:
     a marca fica no corpo original e funcionou (teste `jogador`).
   - **Medidor** (outro agente, depois): as marcas são linhas únicas, isoladas, marcadas `// Hoshiestar -
     vigia`; ganchos no mesmo laço convivem (são escritas em campos diferentes).
8. **Vazamento**: os campos guardam no máximo uma entidade e um tile; limpos no fim de cada laço e no
   início de cada tick.

### Concorrência: quem escreve o quê

| Campo | Escrito por | Lido por | Por que leitura velha/rasgada só afeta o texto |
|---|---|---|---|
| `Relogio.ticks` (volatile) | thread do servidor | vigia | O vigia lê `ticks`, `inicioTick`, `ticks` de novo e descarta se mudou (estilo seqlock). Erro possível: perder um poll. |
| `Marcas.inicioTick` (volatile) | thread do servidor | vigia | Idem. |
| `Marcas.seqFim`, `duracaoFim` (volatile) | thread do servidor (só ticks lentos) | vigia | Ordem de escrita: `seqFim = -1`, `duracaoFim`, `seqFim = número` (correção M9: antes o vigia podia ver o seq antigo duas vezes com a duração nova). O vigia lê seqFim, duração, seqFim; se não bate (ou -1), usa o tempo que ele mesmo viu ("cerca de") ou espera o próximo poll para contar o tick "só pelo fim". |
| `Marcas.guardarAcimaDeNanos` | thread principal no registro (antes de a thread do servidor existir) | thread do servidor | Publicado pelo `Thread.start` (relação happens-before). |
| `Marcas.ENTIDADE`, `TILE` (`lazySet`) | thread do servidor | vigia | Lidos antes e depois de cada amostra; diferente = "o contexto mudou durante a amostra" e não é usado como contexto daquela amostra. |
| `WatchdogThread.currentPacket/currentPlayer` | thread do servidor (Crucible) | vigia | Pode ficar velho (Crucible não limpa se o pacote lançar): rotulado "Ultimo pacote visto". |
| Campos de entidade/tile/pacote (posição, dimensão, coordenadas, números) | thread do servidor | vigia | Plain reads: valor velho ou de momentos diferentes; nunca usados pelo jogo; tudo em try/catch. |
| Estado do laço do núcleo (sem volatile) | só a thread do vigia | só a thread do vigia | Uma thread só. |
| Contadores do núcleo (volatile) | só a thread do vigia | `hoshie status` | Um escritor; status pode ver um número de um poll atrás. |
| `NucleoVigia.parar` (volatile) | quem para (thread do servidor, gancho de desligamento ou watchdog via `System.exit`) | vigia | Lido em toda espera; com ele, nenhuma amostra nova; o que já foi colhido é gravado. |
| `NucleoVigia.desistiu` (volatile) | quem para, só se o `join` de 0,8 s esgotou | vigia, antes de gravar | Se a thread do vigia se soltar depois do prazo, não grava (evita escrever com a JVM saindo). Leitura atrasada: no pior caso um relatório gravado durante a saída (arquivo de texto talvez cortado); nada do jogo. |
| `Marcas.LIGADO` (static final) | lido uma vez quando `Marcas` carrega: no registro (fase de coremod, `anotarTicksAcimaDe`) | todas | Se o `hoshiestar.yml` nunca foi lido, `HoshiestarConfig.atual()` devolve os padrões e as marcas ficam **ligadas** (sem a thread do vigia, só custam as escritas); config ilegível com exceção = desligadas. |
| `Vigia.nucleo/parado` | `iniciar()`/`parar()` sob a mesma trava (`Vigia.class`) | status | Depois de `parar()`, nenhuma thread nova começa. |
| Índice de jars | só a thread do vigia | só a thread do vigia | Montado e lido por ela (`DonosPorIndice.indice` volatile só para o status). |

**Código de concorrência: revisado por outro agente** (`<S>/fork/rev/vigia/revisao.md`: aprovado com
ressalvas, nenhuma intercalação que afete o jogo; achados I1 e M1–M10 tratados na rodada de correção,
ver 7b). As correções desta rodada (gravação na parada, `desistiu`, contagem pelo fim) mexem em
concorrência e ainda não foram revistas por um terceiro agente.

---

## 5. Testes isolados (sem Minecraft)

Um comando (JDK 8 e JDK 21; ~90 s):

```bash
HOSHIE_LIBS=<pasta libraries/ de um servidor> bash <workspace>/src/test/vigia/teste-vigia.bash <workspace>
```

Rodam o MESMO código do servidor (`Marcas`, `NucleoVigia`, `LimiteDeRelatorios`, `IndiceDeJars`,
`CamposNumericos`, `SaidaEmArquivo`, `Vigia`), com uma "thread do servidor" falsa de verdade que faz
ticks como o `MinecraftServer` com os patches (`++Relogio.ticks`, `Marcas.inicioTick`, trabalho num
método de um "mod" de teste, `Marcas.entidade`, `Marcas.fimTick`) e a thread do vigia de verdade.
Último resultado, depois da rodada de correção (log: `<S>/fork/testes/vigia-fix/testes-isolados.log`):
**TesteVigiaConfig 38 ok, TesteVigiaIndice 22 ok, TesteVigiaNucleo 111 ok, BenchVigia 2 ok, em cada JDK
(8 e 21); 0 falhas.** (Antes da correção: 38/16/74/2.)

| Caso | O que prova | Números |
|---|---|---|
| tick normal | ticks de 50 ms por 3 s, limite 500 ms: 0 relatórios, 0 amostras | 0 / 0 |
| 2,5 s | limite 2000: exatamente 1 relatório, 2521 ms depois do pedido; duração exata 2500 ms; pilha da thread falsa (tem `Trabalho.metodoLentoDoMod` e `ServidorFalso.run`, não tem `NucleoVigia`); dono `ModFalso-1.0.jar` pelo índice; entidade e jogador no texto | 1 amostra |
| travamento longo | tick de 7 s, limite 1 s, intervalo mínimo 1,5 s, máximo 3: 3 relatórios "CONTINUA TRAVADO" do mesmo tick, espaçados 1503–1504 ms; linha de fim "terminou depois de 7000 ms (3 relatório(s))"; não conta como suprimido | 3 |
| limitador antes de amostrar | máximo 1 por janela, 3 ticks lentos: 1 relatório, **nenhuma amostra** de pilha nos 2 negados, 2 contados "sem relatório" | 3 amostras só no 1º |
| parada durante tick lento | `parar()` no meio de 20 amostras, esperando o fim de um tick de 10 s e entre amostras de 5 s (só volta rápido se `parar()` acordar a thread): termina em 0 ms (< 1 s), thread do vigia morta, **1 relatório `SERVIDOR PARANDO` / `CONTINUA TRAVADO` com a pilha**, thread do servidor intocada | 0 / 0 / 0 ms |
| privacidade | pacote de teste com `String "SEGREDO-123"`, `char[]`, `Object` e `int x=42`, `boolean`, `double`: como classe de "mod" sai só o nome; com o prefixo liberado (como um pacote do Minecraft), só `x=42, b=true, d=1.5`; nem o nome dos campos de texto | |
| GC | GC falso com 700 ms de pausa (G1 Young) e 300 ms concorrente num tick de 900 ms: aviso "pausas de GC somam 700 ms de 900 ms: a causa provável é coleta de lixo"; o concorrente rotulado; tick sem GC não culpa o GC | |
| arquivo | pasta principal impossível (é um arquivo) → pasta de reserva, conteúdo igual; 13 velhos + 1 novo com máximo 10 → ficam 10, o mais velho sai, arquivo de outro nome fica | |
| donos | mixin pelo nome do método (`redirect$bao000$hodgepodge_2$...` → `mixin hodgepodge_2`), subpacote cai no jar do pacote pai, neutros e desconhecidos fora; `cpw.mods.ironchest` é do IronChest (só `cpw.mods.fml.` é neutro) | |
| contexto por amostra | entidade marcada só depois de 750 ms: aparece "(em 2/3 amostras)", amostra 1 diz "entidade: nenhuma"; mods somados "em 3/3 amostras" | |
| carência | tick de 1,5 s nos primeiros 3 s: nenhum relatório nem amostra; linha "1 tick(s)... o maior: 1500 ms"; depois da carência, relatório normal | |
| **adversários da revisão** (trazidos de `rev/vigia/adv`) | `ocioso` (1,5 s parado fora de `tick()`: nada); `nomeOculto` (nome não aparece); `saidaTravada` (`parar()` 800 ms e diz que não terminou); `amostradorTravado` (`parar()` limitado; solto depois, não grava); `paradaAposFim` (parada 0–8 ms depois do fim de um tick de 2,5 s: relatório gravado em 5/5; antes 0/5); `paradaNoTravamento` (parada aos 3 s: relatório `SERVIDOR PARANDO`; antes 0); `amostraAtrasada` (1ª amostra depois do fim: marcada, mods "nao somados"); `janela` (6 ticks de 2040 ms: 6 contados, 3 com relatório + 3 "só pelo fim"; antes 1/3 invisíveis); `classes` (pacote de mod com campo de tipo não carregado: nenhum pedido ao class loader); `malformada` (classe aninhada de nome malformado: `descrever` não lança, relatório sai, 0 erros, nos dois JDKs); `suprimido` (tick negado e relatado depois sai da conta "sem relatório"); GC (G1, ZGC/Shenandoah, CMS) | |
| índice de jars (jars de teste) | prefixo mais longo (`com.a.b.c.d.X` → jar de `com.a.b.c`, não de `com.a`), subpasta `mods/1.7.10`, profundidade 3 fora, pacote em 2 jars "(+1)", `plugins/`, `META-INF/` fora, classe sem pacote sem dono, jar ilegível pulado; com jars do servidor (`server.jar` + `libraries/` a 5 níveis): `org.slf4j` do servidor não é do plugin que o embute, subpacote só do plugin continua dele | 7 jars, 1 erro |
| config | padrões e texto do arquivo criado, valores do admin lidos sem regravar, fora da faixa → padrão, e JVM filha com `vigia.ligado: false`: as marcas não escrevem nada | |
| custo (indicação) | laço com chamada megamórfica (o JIT não embute, como `onUpdate`): JDK 21 ~0,5 ns por marcação, JDK 8 ~0–0,1 ns; par início+fim de tick ~71–74 ns | ver log |

Também: `servidor/test.sh` da base passa inteiro (TesteConfig 35 ok com os campos novos; não repetido
na rodada de correção: ela não mexeu em config nem em classe que o `test.sh` compila).

**Mutações** (erro posto de propósito para ver se os testes acusam; `<S>/fork/testes/vigia-fix/mut/`,
JDK 21): as 5 que passavam na revisão (M6 `fimTick` sem zerar o início, M10 nome sempre mostrado, M12
`join` sem limite, M14 contador não descontado, M17 `parar()` sem `notify`) e 4 novas da correção (parada
jogando fora as amostras, sem contar os vistos só pelo fim, 1ª amostra depois do fim sem marca, campos
de classe de mod lidos): **9 de 9 acusadas** (M12 pelo teste que não termina).

---

## 6. Testes no modpack real

Cópia `medidor-test/vigia1` (292 mods, 52 plugins; `newcopy.sh`), jar do workspace, nenhum jar nosso
em `mods/` ou `plugins/`. Logs e relatórios em `<S>/fork/testes/vigia/`. Todas as paradas com `stop`,
sem crash-report. **Nenhum uso de kill.**

| Boot | O que | Resultado |
|---|---|---|
| 0 (jar v1) | primeira versão, marcas com try/catch ao lado da chamada de update | **Boot falhou**: `MobiusCore ... Pattern not found while trying to inject into h` (Opis). Corrigido (ver Desvios). Log `boot0-falhou-opis.log`. |
| 1 (v2, padrão, mundo novo, sem `hoshiestar.yml`) | vigia ligado por padrão, 5 min ocioso, `forge tps` 5 vezes, `hoshie status`, thread dump | `recursos: ... vigia=ligado`; thread `Hoshiestar Vigia` viva (daemon, TIMED_WAITING); 1 relatório do **1º tick do boot, 3401 ms** (chunks do boot salvos por mixin do chunkapi, AM2, AkashicBellow), depois **nenhum relatório em 5 min ocioso**. Tick médio 3,28–4,43 ms (leituras 2 a 5; a 1ª, 8,8 ms, logo depois do boot). Stop: 4,73 s. |
| 2 (v2, `vigia.ligado: false`) | 5 min ocioso, `forge tps` 5 vezes, thread dump | `vigia=desligado`, nenhuma thread `Hoshiestar Vigia`, nenhum relatório. Tick médio 3,26–3,89 ms. Stop: 4,69 s. **Ligado x desligado: dentro do ruído** (médias 3,7 x 3,6 ms; faixas sobrepostas). |
| 3 (v4, ligado, `-Dhoshiestar.vigia.teste=true`, `intervalo_minimo_segundos: 5`, bot `VigiaBot`) | carência; casos de teste; travamento longo; stop no meio de um tick lento | ver abaixo |

Boot 3, em ordem (`hoshie status` no fim: `ticks lentos: 6; relatorios: 7 (7/10 na janela); sem
relatorio: 0; maior tick lento: 25008 ms; na carencia do boot: 1 tick(s) lento(s), o maior 2289 ms;
indice de jars: 327 jars, 11504 pacotes, montado em 262 ms`):
1. **Carência**: `nos primeiros 30 s depois do boot (carencia) houve 1 tick(s) lento(s), sem
   relatorio; o maior: 2289 ms`. Nenhum relatório. O campo novo `carencia_segundos: 30` entrou sozinho
   no `hoshiestar.yml` da cópia.
2. **Real (não provocado)**: o bot entrou e o tick de login levou **2823 ms**: relatório aponta
   `MatterOverdrive.jar (matteroverdrive.starmap.data.Planet.writeToBuffer)` (envio da galáxia ao
   jogador), jogador `VigiaBot` com dimensão e posição, último pacote `FMLProxyPacket` só com `timestamp`.
3. `hoshie vigia-teste comando 2500` → `TICK LENTO #1006: 2512 ms`, dono `Hoshiestar (server.jar)
   (ComandoTesteVigia.dormir)`, pacote/jogador/entidade/tile "nenhum".
4. `... tile 3000` → `3040 ms`, `Tile em atualizacao: TileTeste (...) em 66 255 294, dim 0 (em 2/2
   amostras)`; pilha: `TileTeste.func_145845_h` ← `WorldMutationGuard.runTileEntityUpdate` ← `World.func_72939_s`
   (o firewall continua aplicando, e a marca de tile funciona com ele).
5. `... entidade 2500` → `2523 ms`, `Entidade em atualizacao: RockBase (mglucas0123.blocks.RockBase), id
   76, dim 0, pos 84.5 67.0 250.5 [OreSpawn Remastered.jar]` (a 1ª entidade viva da vez; o ouvinte não
   escolhe porco).
6. `... jogador 2500` → `2521 ms`, `Ultimo pacote visto: C03PacketPlayer (...) campos: field_149479_a=0.0,
   ... field_149474_g=true ...`, `Jogador do pacote: VigiaBot, dim 0, pos 66.5 64.0 294.5`, entidade
   `EntityPlayerMP ... jogador VigiaBot`; pilha passa por `ServerPlayerAPI.onUpdateEntity [PlayerAPI-1.5.0.jar]`.
7. **Travamento longo** `... comando 25000`: relatório às 22:31:09 `CONTINUA TRAVADO ha 14056 ms` e às
   22:31:20 `25008 ms (ja terminou)`, do mesmo tick #2203 (espaçados pelo limitador; nenhum aviso do
   watchdog do Spigot nessa cópia, cujo `timeout-time` é ≥ 60 s).
8. **Stop no meio de um tick lento**: `... comando 8000` e, 0,3 s depois, `stop`. Comando → fim do
   processo: **12,88 s** = 8,0 s do tick + 4,9 s de stop (stop normal: 4,69–4,73 s). Relatório do tick
   de 8008 ms gravado no mesmo segundo do "Stopping the server"; nenhum relatório do desligamento,
   nenhum crash-report.

Nenhuma linha de erro do Hoshiestar nos três boots (fora o boot 0); nenhum `hoshiestar.vigia.Marcas`
em pilha de erro.

**Rodada de correção** (jar do workspace com as correções, cópia nova `medidor-test/vigia2`, apagada no
fim; logs e relatórios em `<S>/fork/testes/vigia-fix/`). `hoshiestar.yml` de teste: só
`nome_do_jogador: false`, `intervalo_minimo_segundos: 1`, `max_relatorios: 50` (o resto entrou com o
padrão); `spigot.yml` de teste com `timeout-time: 20` e `restart-script` inexistente (para o watchdog do
Spigot pedir a saída da JVM no meio de um tick travado); `-Dhoshiestar.vigia.teste=true`.

| Boot | O que | Resultado |
|---|---|---|
| 4 | boot; 5 min ocioso | `vigia=ligado`; `Patched TileEntity tick gate` e `MobiusCore Successful injection in h` (4x): firewall e Opis continuam aplicando. Carência: 1 tick de 3189 ms contado sem relatório. Depois, **5 min sem nenhum relatório** nem pasta `hoshiestar/`; tick médio 11,4 / 9,5 / 4,1 / 4,5 ms (minutos 2–5; o 1º, 92 ms, logo depois do boot). |
| 4 | bot entra (`nome_do_jogador: false`) | tick de login 2452 ms (real, MatterOverdrive/akashicplayer): `Jogador do pacote: (nome oculto: vigia.nome_do_jogador = false), dim 0, pos ...`, idem na entidade; **o nome do bot aparece 0 vezes nos 21 relatórios**. Índice: 327 jars de mods/plugins + 146 do servidor, 12 419 pacotes, 363 ms. |
| 4 | `vigia-teste comando 2500 / tile 3000 / entidade 2500 / jogador 2500` | 2524 / 3039 / 2545 / 2547 ms; tile `TileTeste ... em 66 255 294, dim 0` com `WorldMutationGuard.runTileEntityUpdate` na pilha; entidade `RockBase ... [OreSpawn Remastered.jar]`; jogador: `C03PacketPlayer` só números, nome oculto, PlayerAPI na pilha. Chat `SEGREDOVIGIA` enviado pelo bot antes: não aparece em relatório nenhum. |
| 4 | `comando 2040 / 2030 / 2050` (M1) | 2049 e 2060 ms com relatório; o de ~2039 ms acabou antes de o vigia ver: **contado** ("vistos so pelo fim (sem amostra): 1", e a linha no relatório seguinte). |
| 4 | `comando 12000` (I1: relatório logo depois das amostras) | relatórios aos 4097, 6199, 8301, 10403 ms (`CONTINUA TRAVADO`; intervalo mínimo 1 s) e `12010 ms (ja terminou)`. |
| 4 | `comando 60000` → watchdog do Spigot aos ~21 s pede a saída da JVM (`System.exit`) → gancho de desligamento → `stopServer` → `Vigia.parar` com a thread do servidor travada | último arquivo `CONTINUA TRAVADO ha 21486 ms` + **`SERVIDOR PARANDO`**, gravado no mesmo segundo da saída (antes da correção estas amostras se perdiam). Processo terminou 30 s depois do comando, sem crash-report. (A linha desse relatório não saiu no `console.log`: o log já estava fechando; o arquivo é a prova.) |
| 5 | `comando 8000` e, 0,3 s depois, `stop` | relatórios aos 4034 e 6144 ms e, na parada, `8019 ms (ja terminou)` + `SERVIDOR PARANDO`. **Comando → fim do processo: 12,86 s** = 8,0 s do tick + 4,86 s de stop (stop normal neste modpack: 4,69–4,73 s). Sem crash-report. |

Nenhuma linha de erro do Hoshiestar nos boots 4 e 5. Nenhum uso de kill.

---

## 7. Desvios do desenho (e por quê)

1. **Patch no fork, não mixin** (decisão do coordenador): W1/W2 viraram linhas em `MinecraftServer.tick`;
   W3/W5/W6 viraram marcas no **começo da volta** dos laços de `World.updateEntities` (não ao redor da
   chamada de update), limpas no fim do laço; W4 ficou em `EntityPlayerMP.onUpdateEntity` (o desenho
   pede `func_71127_g`; ela é chamada pelo `NetHandlerPlayServer.processPlayer`).
2. **Sem try/catch no ponto de chamada das marcas** (a base pede try/catch). O primeiro jar tinha
   try/catch ao lado da chamada de update de tile e de entidade; o bytecode ganhou um quadro de pilha
   entre a linha e o `ALOAD`, o padrão exato do Opis não casou e o boot caiu. `Marcas` não lança e a
   classe não falha ao iniciar, então o try/catch não protegia nada. `Vigia.parar()` em `stopServer`
   mantém o try/catch.
3. **Uma escrita por entidade/tile, não duas**: marca no começo da volta e limpeza no fim do laço.
   Consequência: durante o "remove" da mesma volta, o campo ainda aponta a entidade da vez; riders
   atualizados dentro da montaria aparecem como a montaria (o desenho perdia a montaria).
4. **Carência depois do boot** (`vigia.carencia_segundos = 30`): não estava no desenho. O 1º tick
   depois do boot passou de 2 s nos 3 boots; sem carência, todo boot gerava um relatório.
5. **Limitador**: intervalo mínimo + máximo por janela fixa (o desenho: 60 s e 30 por hora; aqui 60 s e
   10 por 60 min). Relatórios repetidos para o mesmo tick travado, um por intervalo.
6. **Sem espera do fim do tick** (o desenho: 30 s; a 1ª versão: 10 s): o relatório sai logo depois das
   amostras (~4 s num travamento), e o fim vira uma linha no log. Assim nada se perde se a JVM sair no
   meio (correção I1 da revisão).
7. **Dono por índice de jars** (correção do conferente) em vez de `findResource`/lista de mods do FML;
   inclui `plugins/`. O relatório lista os mods da pilha em ordem (o 1º não-neutro pode ser uma
   biblioteca do mod culpado, como no stall do ChromatiCraft: DragonAPI em cima de ChromatiCraft).
8. Chaves não implementadas do desenho: `pollMs` (fixo 100 ms), `entityContext`/`tileContext` (as marcas
   custam uma escrita; não valia uma chave), `positions` (virou `nome_do_jogador`; posição sempre sai).
9. Três erros internos seguidos desligam a thread do vigia (como no desenho), com uma linha no log.

### 7b. Rodada de correção (revisão independente `<S>/fork/rev/vigia/revisao.md`)

| Achado | O que foi feito |
|---|---|
| I1 (importante) parada joga fora as amostras | Corrigido: com amostras na mão, a parada grava o relatório na hora (`SERVIDOR PARANDO`); relatório logo depois das amostras; `desistiu` evita gravar depois do prazo de 0,8 s. Testes isolados e boots 4/5. |
| M1 tick entre o limite e limite+100 ms invisível | Corrigido: contado pelo fim anotado ("vistos so pelo fim"), no status e no próximo relatório; folga documentada (seção 1, 4b). |
| M2 lacunas de teste | Testes adversários trazidos (seção 5); 9/9 mutações acusadas; `nome_do_jogador: false` rodou no servidor (boot 4) e está no plano. |
| M3 reserva em /tmp | Corrigido: `logs/hoshiestar-vigia/`. |
| M4 class loader do jogo em `CamposNumericos` | Corrigido: campos só de classes `net.minecraft.`/`cpw.mods.fml.`; teste `classes`. |
| M5 1ª amostra sem conferir o tick | Corrigido: marcada, fora dos mods e do contexto. |
| M6 dono escondido/errado | Corrigido: só `cpw.mods.fml.` neutro; `server.jar` e `libraries/` indexados como neutros. |
| M7 `getSimpleName` no Java 8 | Corrigido: `CamposNumericos.nomeSimples` (pelo `getName`). Teste com classe malformada nos dois JDKs. |
| M8 GC de outros coletores | Corrigido por nome (seção 2, "GC"); vale conferido só para G1. |
| M9 ordem duracaoFim/seqFim | Corrigido: `seqFim = -1` antes da duração. |
| M10 frases do doc | Corrigidas (seção 4, itens 4 a 6, e tabela de concorrência). |
| M11 comando de teste no jar | Mantido (só com a propriedade e só console), como a revisão aceitou. |

---

## 8. Não verificado

- Revisão de concorrência por outro agente: feita (aprovado com ressalvas); as mudanças de concorrência
  da rodada de correção (gravação na parada, `desistiu`, contagem pelo fim) não foram revistas por um
  terceiro agente.
- Soma de pausas com ZGC/Shenandoah/CMS (regra por nome, sem servidor com esses coletores); flags de GC
  da produção.
- O índice também varre subpastas de `plugins/` (até 2 níveis), inclusive pastas de plugins desativados
  como `plugins/!DISABLED-HOSHIESTAR/` desta cópia: um pacote de plugin desativado pode aparecer como dono.
  Só texto; não corrigido.
- Custo das marcas medido no servidor com carga (2 000 funis + 400 porcos, como o T6 do medidor): só
  houve comparação ocioso ligado x desligado (dentro do ruído) e o harness (indicação).
- Pausa de `Thread.getStackTrace` em outra thread no JDK 21 (handshake por thread): não medida.
- Semântica exata do coletor "G1 Concurrent GC" no JDK 21 (o relatório o rotula como concorrente e não
  o soma nas pausas).
- Convivência com o watchdog do Spigot: testada com `timeout-time: 20` (boot 4): o watchdog despejou as
  threads e pediu a saída; o vigia gravou o relatório `SERVIDOR PARANDO`. Com o `timeout-time` da
  produção, não testado.
- Boot em Java 8 (a produção usa Java 21).
- O medidor (outro agente) ainda não pôs os ganchos dele nos mesmos laços.
- Se algum outro coremod que não está neste modpack casa padrão exato perto das marcas (aqui foram
  conferidos Opis, DragonAPI, Akashic-WorldFirewall, CoFHCore, Hodgepodge, SaveRelief e PlayerAPI).

---

## 9. Plano de teste de servidor (para repetir)

Preparação: cópia nova (`medidor-test/newcopy.sh <nome>`), troque o `server.jar` pelo jar do workspace.
Nenhum jar nosso em `mods/`/`plugins/`. Antes de ligar: `pgrep -f 'server.jar nogui'` vazio.

1. **Padrão**: ligue sem `hoshiestar.yml` (`start.sh <nome>`). Tem de aparecer `recursos: ... vigia=ligado`,
   `[vigia] vigia ligado: limite 2000 ms ...`, `recurso vigia: ligado | ... thread viva`, e ~30 s depois
   do boot talvez a linha da carência. `jcmd <pid> Thread.print | grep 'Hoshiestar Vigia'` mostra a
   thread daemon. Fique 5 min sem jogador: **nenhum** `TICK LENTO` nem arquivo em `hoshiestar/`.
   `forge tps` a cada minuto (anote a média). `stop`, cronometre.
2. **Desligado**: `vigia: ligado: false`, ligue, mesmas leituras; nenhuma thread `Hoshiestar Vigia`,
   nenhuma linha `[vigia]` além do `recurso vigia: desligado`. Compare as médias com o passo 1 (devem se
   sobrepor).
3. **Casos provocados**: `vigia.ligado: true`, `vigia.intervalo_minimo_segundos: 5`, ligue com
   `JAVA_TOOL_OPTIONS="$JAVA_TOOL_OPTIONS -Dhoshiestar.vigia.teste=true"`. Conecte um bot
   (`tools/test_bot.py 25565 VigiaBot <modlist> <cmds> <log>`). No console, um por vez, com 6 s entre eles:
   - `hoshie vigia-teste comando 2500` → relatório ~2500 ms, dono `Hoshiestar (server.jar)`;
   - `hoshie vigia-teste tile 3000` → `Tile em atualizacao: TileTeste ... em <x> 255 <z>, dim 0`, e na pilha
     `WorldMutationGuard.runTileEntityUpdate` (firewall ativo; confira também `Patched TileEntity tick gate`
     no boot);
   - `hoshie vigia-teste entidade 2500` → `Entidade em atualizacao: <classe> id .. dim 0 pos ..`;
   - `hoshie vigia-teste jogador 2500` → `Ultimo pacote visto: C03PacketPlayer ... campos: ...` (só números),
     `Jogador do pacote: VigiaBot ...`;
   - `hoshie vigia-teste comando 12000` → `CONTINUA TRAVADO` aos ~4 s (e outros a cada intervalo mínimo) e
     depois `ja terminou` ou a linha "terminou depois de ~12000 ms";
   - privacidade: mande pelo bot um chat com uma palavra única (ex.: `SEGREDOVIGIA`) logo antes de
     `vigia-teste jogador`; `grep SEGREDOVIGIA hoshiestar/*.txt` não pode achar nada (feito no boot 4: nada);
   - `vigia.nome_do_jogador: false` (num boot próprio ou no mesmo dos casos acima): o nome do bot não pode
     aparecer em relatório nenhum (`grep -c VigiaBot hoshiestar/*.txt` = 0; feito no boot 4);
   - ticks logo acima do limite: `comando 2040`, `2030`, `2050`; os que não geram relatório têm de aparecer
     em "vistos so pelo fim" no `hoshie status`;
   - parada pelo watchdog com o tick travado (opcional, só em cópia de teste): `spigot.yml` com
     `settings.timeout-time: 20`, `restart-on-crash: true` e `restart-script` apontando para um arquivo que
     NÃO existe (senão o watchdog roda o script e liga outro servidor); `vigia-teste comando 60000` por
     último: o último `hoshiestar/vigia-*.txt` tem de ter `SERVIDOR PARANDO`;
4. **Stop no meio de tick lento**: `hoshie vigia-teste comando 8000` e logo `stop`. Tempo do comando ao fim
   do processo ≈ 8 s + o stop normal do passo 1; sem crash-report; o relatório do tick lento sai (com
   `SERVIDOR PARANDO` se a parada chegou durante as amostras).
5. Apague a cópia.
