# Recurso `arsmagica` (item 1.F): atalhos exatos no `am2.AMEventHandler` do Ars Magica 2

Correção embutida no `server.jar` do Hoshiestar (nada vai em `mods/` nem em `plugins/`). Os dois atalhos
vêm **desligados** por padrão. Relatórios de origem: `docs/pesquisa/etapa1/F-arsmagica.md` (desenho) e
`F-arsmagica-conferencia.md` (conferência; onde divergem, vale a conferência). Só valem o F1 e o F2 deles:
o vigia (1.H) é de outro agente e o F5 (`hasWorldObj`) ficou de fora por decisão do coordenador. Os relatórios
falavam em Mixin (`@WrapOperation`); aqui o mecanismo é o transformador do fork, no formato "envolver uma
chamada" (seção 3).

Termos: **mapa de aceleradas / de retardadas** = os dois `HashMap` estáticos do AM2
(`acceleratedEntitiesUUIDs` e `slowedEntitiesUUIDs`) onde ele anota as entidades aceleradas pelo feitiço
"Accelerate" (componente `ConcentrateTime`) ou retardadas pelo "Dilute Time". **Gancho** = método nosso que a
classe do mod passa a chamar depois de transformada. **Ponte** = método pequeno que o transformador cria
dentro da classe do mod e que decide entre "chamar o original" e "pular". **Trava de versão** = SHA-256
(impressão digital) dos bytes originais de uma classe; a correção só entra se bater. **Harness** = programa de
teste que roda o código real do mod com um Minecraft de mentira.

---

## 1. O que faz, em linguagem simples

O perfil de produção (spark, 602 s, 8 jogadores) mostrou o `onEntityLiving` do Ars Magica 2 em **3,57 % do tick**.
Quase tudo disso vem de dois trabalhos que o AM2 faz mesmo quando não há nada a fazer:

* **F1, varredura de todos os mundos (até ~2,08 % do tick, estimativa).** Para cada pacote de movimento de cada
  jogador, o AM2 percorre a lista de entidades de **todos** os mundos carregados e, para cada entidade viva,
  monta o texto do UUID (`UUID.toString()`) só para procurar no mapa de aceleradas se ela foi acelerada pelo
  feitiço "Accelerate". O mapa está vazio quase sempre.
* **F2, consulta do "DiluteTime" (~0,35 % do tick, estimativa).** Em todo tick de toda entidade viva o AM2
  pergunta se a habilidade "DiluteTime" está desativada (o que copia listas de árvore de habilidades várias
  vezes) antes de olhar o mapa de retardadas, também quase sempre vazio.

O Hoshiestar coloca, antes dessas chamadas, uma **ponte** que pergunta a um gancho: "o mapa certo está vazio?".
Se estiver, pula o trabalho (que, com o mapa vazio, não acharia nada e não mudaria nada). Se tiver **qualquer**
entrada, ou se o interruptor estiver desligado, ou se der qualquer problema (inclusive um gancho que não
consiga ser ligado em tempo de execução), a ponte chama a chamada original com os mesmos argumentos, e o AM2
roda exatamente como sem o Hoshiestar.

**Aviso importante sobre o ganho.** O ganho só existe enquanto o mapa está vazio. O AM2 só tira uma entrada do
mapa quando o próprio jogador que lançou o feitiço está online e passa pelo tick dele; se ele sair com o efeito
ativo, a entrada fica lá até ele voltar ou até reiniciar (e, com "ConcentrateTime" desligado na config do AM2, as
entradas nunca saem). Nesse caso o atalho não ganha nada (e nunca faz mal). No harness, com jogadores saindo e
voltando, houve sementes em que o mapa quase nunca esvaziou. Por isso existe a **linha de estatística** no log
(seção 2): ela diz, no seu servidor, quantas chamadas foram puladas e quantas não.

Ganho esperado (desenho + conferência): F1 de 1,5 % a 2,1 % do tempo da thread do servidor e F2 de ~0,3 %;
**se a atribuição do perfil estiver errada**, o F1 cai para ~0,14 %. É estimativa a partir do perfil, **não
medição** (ver seção 7).

## 2. Chaves no `hoshiestar.yml`

```yaml
arsmagica:
  varredura: false     # F1: pula a varredura de todos os mundos quando o mapa de aceleradas esta vazio
  diluicao: false      # F2: pula a consulta do DiluteTime quando o mapa de retardadas esta vazio
  estatistica: 5       # minutos entre linhas de estatistica no log (0 = nunca)
```

| Chave | Padrão | O que faz |
|---|---|---|
| `arsmagica.varredura` | `false` | F1. `true`: na hora de pedir a lista de mundos, o AM2 só a pede se o mapa de aceleradas tiver entrada. `false`: a classe do AM2 nem é alterada. |
| `arsmagica.diluicao` | `false` | F2. `true`: a consulta do DiluteTime só roda se o mapa de retardadas tiver entrada. `false`: idem. |
| `arsmagica.estatistica` | `5` | De quantos em quantos minutos escrever no log uma linha com as chamadas puladas e as que rodaram o original (janela desde a última linha e totais desde o boot, mais o tamanho dos dois mapas). `0` = nunca; negativo vira `0` com aviso. Só conta e escreve se a correção foi aplicada na classe do AM2 (algum atalho ligado no boot); `hoshie arsmagica ... desligar` **não** a cala (as pontes seguem contando como "original"). |

O arquivo é lido **uma vez**, no boot. Os dois padrões são `false` (decisão do projeto: o que muda o
funcionamento entra desligado e o admin liga um a um). Ligue-os depois do teste de servidor (seção 8).

Exemplo da linha de estatística (formato real do código):

```
[Hoshiestar] [arsmagica] estatistica (ultimos ~5 min): varredura de mundos: puladas 42, rodou o original 10; checagem DiluteTime: puladas 7, rodou o original 0; mapas do AM2 agora: aceleradas 0, retardadas 0 | totais desde o boot: varredura puladas 43 / original 10, DiluteTime puladas 7 / original 0, erros 0, outra thread 0
```

**Comandos** (console, RCON, ou operador de nível 4 com o nó `hoshiestar.admin`):

* `hoshie status`: a linha de estado do recurso (modo de cada atalho, se a correção foi aplicada, contadores,
  tamanho dos mapas, erros).
* `hoshie arsmagica`: a mesma linha.
* `hoshie arsmagica varredura|diluicao ligar|desligar`: troca o interruptor **só na memória**, até reiniciar
  (o `hoshiestar.yml` não muda). Serve para comparar antes e depois no mesmo boot. `desligar` sempre funciona e
  devolve o AM2 ao original na hora. `ligar` só funciona se a correção foi aplicada na classe neste boot (o
  interruptor estava ligado no `hoshiestar.yml` e a trava conferiu) e a auto-checagem dos ganchos (seção 4.3)
  passou; senão o comando recusa e explica. `ligar` também limpa um "desligado por erro".

## 3. O que muda na classe do AM2

Alvo: `am2.AMEventHandler` do `AM2.5-LTS-1.6.11-LE-CR40.jar` (SHA-256 do jar
`5cdd7170b82323a2e8be4cc0018597dae2379771ea33b52fbbab0fd0dd77af32`).

| Trava | SHA-256 de `am2/AMEventHandler.class` |
|---|---|
| AM2.5-LTS-1.6.11-LE-CR40 | `270c2ddf51bada28728b0f7eaa5ea7bc0f7d8248a0db3042f254c1f4155d04c3` |

Conferido: o mesmo hash nos jars de `server-orig/mods` e `server-test3/mods`. Classe Java 8 (versão 52), 41
métodos, 61.829 bytes. Hash diferente (outra versão do AM2) = nenhuma das duas correções entra, o motivo e o
hash visto vão para o log e para `hoshie status`.

Duas correções (uma por interruptor), cada uma atômica (ou entram todas as trocas dela, ou nenhuma):

**F1** (`arsmagica.varredura`), em `HandlePlayerSpellExtendedProperties(EntityPlayer, ExtendedProperties)V`
(privado, só de jogadores): a **única** chamada `invokestatic DimensionManager.getWorlds()[WorldServer` (offset
440) passa a ir para a ponte `hoshiestar$envolve$0()`:

```java
// ponte PROTEGIDA (Asm.envolverChamadaProtegida); o try cobre so os dois ganchos
private static synthetic WorldServer[] hoshiestar$envolve$0() {
    try {
        if (!GanchoAM2.varrerMundos()) return GanchoAM2.semMundos();     // lista vazia compartilhada
    } catch (Throwable t) {
        Atalhos.falhaVarredura(t);                                         // so desliga o atalho e avisa no log
    }
    return DimensionManager.getWorlds();                                  // a chamada original, igual, FORA do try
}
```

Com a lista vazia, o laço do AM2 (offsets 450 a 618) faz zero voltas e segue para o mesmo ponto (633) em que o
original segue depois do laço. Os locais 4, 5 e 6 (a lista, o tamanho, o índice) só são lidos dentro do laço e
são gravados de novo antes de qualquer outro uso (conferido no bytecode).

**F2** (`arsmagica.diluicao`), em `onEntityLiving(LivingEvent$LivingUpdateEvent)V` (toda entidade viva, todo
tick). O trecho original é:

```java
if (!SkillTreeManager.instance.isSkillDisabled(SkillManager.instance.getSkill("DiluteTime"))
    && slowedEntitiesUUIDs.containsKey(uuid = ent.getUniqueID().toString())
    && ent.ticksExisted % slowedEntitiesUUIDs.get(uuid) != 0) { event.setCanceled(true); return; }
```

As duas chamadas (cada uma aparece **uma vez** nesse método; o `HandlePlayerSpellExtendedProperties` tem outras
duas de cada, que **não** são tocadas) passam a ir por pontes:

```java
static ISkillTreeEntry hoshiestar$envolve$1(SkillManager self, String nome) {
    try {
        if (!GanchoAM2.consultarSkill(self, nome)) return GanchoAM2.semSkill(self, nome);    // null
    } catch (Throwable t) { Atalhos.falhaDiluicao(t); }
    return self.getSkill(nome);                                                              // original
}
static boolean hoshiestar$envolve$2(SkillTreeManager self, ISkillTreeEntry e) {
    try {
        if (!GanchoAM2.consultarDesativada(self, e)) return GanchoAM2.semDesativada(self, e); // true ("desativada")
    } catch (Throwable t) { Atalhos.falhaDiluicao(t); }
    return self.isSkillDisabled(e);                                                          // original
}
```

Com o mapa de retardadas vazio, o `if` original é falso (`containsKey` num mapa vazio é `false`); devolver `true`
em `isSkillDisabled` leva ao mesmo desvio (`ifne 72`) que o AM2 toma quando a habilidade está desativada, e
o `getSkill` pulado (`null`) só é usado como argumento dessa chamada.

Os ganchos (`hoshiestar.arsmagica.atalho.GanchoAM2`, em `src/mods/java`, compilados contra o jar do AM2 com
`--mods`) só leem o campo estático público do AM2 (**ao vivo, a cada chamada**) e delegam para
`hoshiestar.arsmagica.Atalhos` (em `src/main/java`, sem nenhuma classe do AM2/Minecraft: é o **mesmo código** que
o teste isolado exercita). O registro (`ArsMagica.registrar`) roda na fase de coremod e só usa JDK, ASM e
`hoshiestar.*`.

A ponte é gerada por `Asm.envolverChamadaProtegida` (seção 4.3), uma variante nova do formato "envolver uma chamada" da base:
o formato comum (`Asm.envolverChamada`, sem o `try`) continua como era e é o que os outros recursos usam.

### Quem mais mexe nessa classe (conferido)

* **Nenhum outro jar transforma `am2.AMEventHandler`.** Varredura própria nos 273 jars de `mods/` e `plugins/` do
  servidor de teste (textos `AMEventHandler`/`am2.AMEventHandler` em classes, JSON de mixin, `.cfg` e `.info`):
  só o jar do AM2 cita a classe, em 5 outras classes dele (`AMCore`, `CommonProxy`, `ServerTickHandler`,
  `Curse` e `MysteriumPatchesFixesMagicka`, que só **lê** `slowedTiles`). Nenhuma é transformador (o teste
  isolado refaz essa conferência nos jars do AM2 e do AM2PlayGround).
* **O coremod do AM2** (`MysteriumPatchesFixLoaderMagicka`) registra dois transformadores:
  `FirstClassTransformer` (Mysterium, com as correções `@Fix` de `MysteriumPatchesFixesMagicka`, cujos alvos são
  `World`, `EntityPlayer`, `EventBus`, `TileEntity`... nenhum é `AMEventHandler`) e
  `am2.preloader.BytecodeTransformers` (`ItemMageHood`, `EntityRenderer`, `MinecraftServer`, `EntityPlayerSP`,
  `NetHandlerPlayServer`, `RendererLivingEntity`, `World`, `PotionEffect`...). Nenhum toca a classe.
* **AM2PlayGround**: `PGClassTransformer` lista 19 classes do AM2 (`AMClientEventHandler`, `PlayerTracker`,
  `AffinityHelper`, `ArmorHelper`, `ExtendedProperties` por transformadores próprios, `SkillTreeManager` só no
  método `init`, `SpellUtils`...); `AMEventHandler` não está na lista e o log de boot do AM2PG não a cita.
* **Access transformers** (corrigido depois da revisão): os dois existem e **nenhum** toca `AMEventHandler`, `SkillManager` nem `SkillTreeManager`
  (o teste isolado confere linha por linha): `arsmagica2_at.cfg` (em `META-INF`, 226 bytes) abre só classes e
  campos do vanilla (`EntityFishHook`, `EntityArrow`, `Potion.field_76418_K`, `EntityRenderer.func_78467_g`); o
  `am2pg_at.cfg` (4.228 bytes) abre cerca de 16 classes do AM2 (`SkillData`, `ExtendedProperties`, `BuffList`,
  `PowerTypes`, `ArcaneCompendium`, `AMKeyBindings`...), campos do Witchery e do vanilla.
* **Ordem em relação ao nosso transformador.** Pelo `servidor/README.md` (seção 7, medido no modpack real em
  outro recurso), o nosso transformador entra na cadeia do `LaunchClassLoader` na **posição 2 de 133**, antes dos
  coremods dos mods (que se registram depois, inclusive os do AM2 e do AM2PG) e do Mixin. Como nenhum deles mexe
  em `AMEventHandler`, a ordem não importa para esta classe. Se um dia algum mexer, ele receberia a classe já
  com as nossas pontes (que são métodos novos: não atrapalham quem muda outros métodos) e, se mudasse o trecho
  que nós trocamos, a nossa correção seria recusada na próxima versão por falta do padrão exato. Um
  access transformer do FML age por nome e não afeta métodos sintéticos novos. O que **não** foi verificado
  em servidor: que os bytes que chegam ao nosso transformador são iguais aos do jar (o log do boot dirá
  "igual ao arquivo do jar", como foi provado para outra classe).

## 4. Como funciona

### 4.1 A decisão (`hoshiestar.arsmagica.Atalhos`)

Uma regra só para os dois atalhos: **pula se, e só se,** o interruptor está ligado **e** o mapa não é nulo **e** o
mapa está vazio **e** a chamada vem da thread do servidor. Qualquer outra situação (interruptor desligado, mapa
com 1 entrada, mapa nulo, outra thread, exceção nossa) manda rodar o original.

* **Sem estado entre os passos do F2.** `getSkill` e `isSkillDisabled` decidem cada um por si, lendo o mapa ao
  vivo. Isso foi uma escolha em vez do campo "pulei o primeiro" sugerido pela conferência: um campo estático
  poderia misturar duas threads. Sem estado, qualquer combinação funciona: se o primeiro passo pulou (`null`) e o
  segundo roda o original, `isSkillDisabled(null)` devolve `false` (conferido no bytecode real de
  `SkillTreeManager`: `getSkillTreeEntry(null)` termina em `return null`; sem efeito colateral) e o mapa
  continua vazio, então nada é cancelado, igual ao original. O teste cobre todas as combinações (seção 6).
* **Sem alocação no caminho quente.** A "lista vazia" é um único array compartilhado (o AM2 só lê o `length`);
  contadores são `long[]` estático; nada de `new` por chamada.
* **Interruptor lido ao vivo** (campo `volatile`): pode ser trocado em execução pelo comando.
* **Relógio da estatística barato.** A decisão lê `Relogio.ticks` (uma leitura) e só a cada 200 ticks olha o relógio
  monotônico (`nanoTime`, que não volta com ajuste do relógio do sistema); a linha sai quando passaram os minutos configurados (o primeiro prazo começa na primeira chamada,
  para a primeira linha não sair com a janela vazia).

### 4.2 Erros e threads

* A chamada original fica **fora** de qualquer `try`: exceções dela saem como sairiam sem nós. O nosso código
  fica **dentro** de `try/catch (Throwable)` (no gancho, dentro de `Atalhos` e, desde a revisão, na própria ponte):
  qualquer exceção nossa vira "rode o original", desliga **aquele** atalho até reiniciar (ou até
  `hoshie arsmagica ... ligar`) e escreve no log, com limitador (5 por minuto).
* Os dois mapas são `HashMap` comuns que só o `AMEventHandler` lê e escreve, na thread do servidor. Os ganchos
  rodam na mesma thread e no mesmo ponto em que o original leria o mapa: não há corrida nova. Para não depender só
  dessa premissa, uma chamada de **outra thread** (ou antes de a thread do servidor existir) roda sempre o
  original, sem olhar o mapa (contador "outra thread").

### 4.3 Gancho que não liga em tempo de execução (achado I1 da revisão)

**O problema.** Se a JVM não conseguisse ligar um gancho quando a ponte o chama pela primeira vez (classe do gancho
que não carrega, método que não existe com aquele descritor, por exemplo porque o nome do tipo do Minecraft no
retorno `WorldServer[]` foi traduzido diferente do que o conferidor viu), sairia um `NoSuchMethodError` ou
`LinkageError` de **dentro do método do AM2**. Isso é `Error`, não `Exception`: o laço de rede do servidor só captura
`Exception`, e no caminho dos mobs o erro vira "Ticking entity". O servidor cairia, e só quando um jogador se
mexesse (o `semMundos` só é ligado no primeiro pulo), com o boot limpo. A chance é pequena (é o primeiro gancho do
projeto com tipo do Minecraft **no retorno** e em array, e nunca rodou num servidor), mas o efeito era queda.

**A correção, em duas camadas.**

1. *Ponte protegida* (`Asm.envolverChamadaProtegida`, nova na base; o formato comum ficou como era). Os dois ganchos da
   ponte ficam num `try { ... } catch (Throwable t) { aoFalhar(t); }` e a chamada original fica **depois** e **fora**
   do `try` (formato na seção 3). Qualquer falha de gancho (lançou, classe ausente, método ausente, descritor
   diferente) cai na chamada original, com os mesmos argumentos: o AM2 roda como sem o Hoshiestar. O `aoFalhar` é
   `Atalhos.falhaVarredura` ou `Atalhos.falhaDiluicao` (só JDK, nenhum tipo do Minecraft ou do AM2, para não depender
   do que pode estar quebrado): desliga **aquele** atalho, conta no contador `erros` e escreve no log (limitador de
   5 por minuto). Custo no caminho normal: nenhum (um `try` de bytecode é só uma tabela de exceções). **Custo no caminho
   de falha:** a ponte ainda chama o gancho a cada chamada e a JVM lança de novo o erro de ligação (da ordem de
   microssegundos por chamada, e de entidade viva por tick no F2); por isso a camada 2.
2. *Auto-checagem ao ligar o servidor* (`ArsMagica.verificarGanchos`, chamada de `Servidor.iniciando` por
   `aoIniciarServidor`, **antes** de qualquer jogador se mexer), só se algum atalho foi pedido no `hoshiestar.yml`. Por
   reflexão, **no class loader dos mods e com os tipos de tempo de execução** (o mesmo que a JVM usa ao ligar a
   ponte), confere: `DimensionManager.getWorlds()` devolve `WorldServer[]`; `SkillManager.getSkill(String)` devolve
   `ISkillTreeEntry`; `SkillTreeManager.isSkillDisabled(ISkillTreeEntry)` devolve `boolean`; os seis ganchos existem,
   são `public static` e têm exatamente os tipos de parâmetro e de retorno que a ponte usa; e **chama** os três
   ganchos `sem...` (sem efeito colateral, não contam nada) para ligar o método de verdade e conferir o valor
   (array vazio, `null`, `true`). Passou: uma linha `ganchos conferidos ...` no log. Reprovou: os dois atalhos ficam
   **desligados**, `Atalhos.ganchoComDefeito` guarda o motivo, o log mostra `ERRO ... GANCHOS COM DEFEITO: <motivo>`,
   o `hoshie status` diz `GANCHOS COM DEFEITO` e `hoshie arsmagica ... ligar` recusa. Se a classe do AM2 já foi
   transformada, as pontes seguem pelo original (camada 1) até o admin desligar as chaves e reiniciar.

Testado (seção 6): na base, com amostras Java 6, 7 e 8; no harness com a classe real, mutilando o `GanchoAM2` de oito
jeitos (seis métodos ausentes, e `semMundos`/`semSkill` com o **retorno de outro tipo**, que é o caso do tipo traduzido
diferente): a ponte protegida dá o mesmo hash do original, a auto-checagem acusa o gancho certo, e o **controle** (formato
comum, sem a proteção) deixa o `NoSuchMethodError` sair do método do AM2.

## 5. Por que é seguro

1. **Mesmo resultado.** Com o mapa de aceleradas vazio, a única ação observável do laço do F1 é chamar
   `func_70071_h_()` (o `onUpdate`) das entidades cujo UUID está no mapa: não há nenhuma. As outras operações do
   laço (`size`, `get`, `instanceof`, `toString`, `containsKey`) não têm efeito colateral. Com o mapa de
   retardadas vazio, o `if` do F2 é falso de qualquer jeito.
2. **Valores usados depois.** O que o laço grava (lista de mundos, tamanho, índices) não é lido depois dele; o
   `true` do F2 entra no mesmo `ifne` que o original já toma quando a habilidade está desativada.
3. **Mapa com entrada = original inteiro.** A emptiness é lida ao vivo em cada chamada: uma entrada posta no meio
   de um tick é vista pela chamada seguinte, como no original (o `put` do AM2 vem **depois** do laço do F1, então
   o original também só a veria na chamada seguinte).
4. **Nada de itens, energia, fluidos, blocos ou dados salvos**: nenhum caminho pulado escreve estado; a única
   ação do laço (`func_70071_h_`) só existe para entidades que estão no mapa.
5. **Interruptor desligado = original**: a ponte chama a chamada original com os mesmos argumentos (custo: uma
   chamada estática extra e uma leitura de campo). Desligado no `hoshiestar.yml`, a classe nem é alterada.
6. **Falha fechada**: trava errada, padrão não achado exatamente uma vez, gancho ausente ou qualquer exceção na
   transformação deixam os bytes originais. O F2 é atômico. E, em tempo de execução, um gancho que falha ou não liga
   manda a ponte para a chamada original (seção 4.3), em vez de derrubar o servidor.
7. **Diferenças conhecidas e aceitas (só no log/exceção, nunca no jogo):**
   * No F1, com o mapa vazio, uma exceção estranha dentro do laço do AM2 (que ele captura e imprime com
     `printStackTrace`) deixa de aparecer porque o laço nem roda.
   * No F2, o original lançaria `NullPointerException` se o UUID da entidade fosse nulo
     (`func_110124_au().toString()`); com o atalho esse caso, que não existe em entidade real, não lança.
   * Uma chamada a mais na pilha (ponte e gancho) nas exceções que atravessem esses pontos.

## 6. Testes feitos e como repetir

Tudo com o bytecode **real** do AM2 e com o código **final** do gancho tirado do `server.jar` construído.

```bash
# 1. construir o jar com os ganchos de mod (precisa da pasta mods/ de um servidor de teste)
servidor/build.sh --rapido <workspace> --github-raw --sem-testes --mods <mods/>
# 2. todos os testes isolados (os genéricos + os testes com mods, entre eles este)
HOSHIE_LIBS=<libraries/> HOSHIE_MODS=<mods/> servidor/test.sh <workspace>
# so este recurso (a mesma coisa que o test.sh chama):
HOSHIE_LIBS=<libraries/> HOSHIE_MODS=<mods/> src/test/mods/arsmagica.bash <workspace> <pasta de saida> <classpath base> <java> [<java>...]
# variaveis: AM_RAPIDO=1 (menos iteracoes), AM_SEM_MUTACAO=1 (pula a mutacao do fonte)
```

Resultado do último ciclo (workspace `w-mods`, jar construído com `--mods`): `servidor/test.sh` completo, com os testes
genéricos da base e os de BetterQuesting e Mekanism (estes dois em modo rápido, só para conferir que a mudança no
`CarregadorMods` não os quebrou), JDK 8 e 21: **TODOS OS TESTES PASSARAM** em 4 min 33 s depois da revisão (com BQ e Mekanism em modo rápido e sem a mutação deles; o do
Ars Magica com a mutação do fonte); o do Ars Magica sozinho, completo (com a mutação), leva ~4 min. Números do ciclo: `TesteAsm` 401
(eram 224), `TesteTrava` 35, `TesteLimitador` 13, `TesteCampoPrivado` 8, `TesteConfig` 35, `TesteArsMagica` 113 (parte verificada) + 116 (harness)
em cada JDK, `TesteBetterQuesting` 81, `TesteMekanism` 70.

Fontes dos testes: `src/test/mods/arsmagica.bash`, `src/test/mods/am2/java/hoshiestar/teste/arsmagica/`
(`TesteArsMagica`, `ErroCapturado`, `mundo/Roda`, `mundo/Casos`, `mundo/GanchoErrado`, `mundo/Reentrancia`) e os stubs em
`src/test/mods/am2/stubs/` (só os membros, com nomes SRG, que os dois métodos usam). O script para se algum fonte
nosso for mais novo que o jar (testaria bytes velhos).

O `TesteArsMagica` roda em duas JVMs por JDK (8 e 21): a **parte verificada** (com o verificador da JVM ligado) e
o **harness** (com `-XX:-BytecodeVerificationRemote`, porque a classe reduzida cita centenas de classes que não
há como simular; a verificação da classe inteira é da primeira parte).

**Parte verificada (113 verificações em cada JDK):**

* *Unidades*: a decisão (mapa vazio, 1 entrada, nulo, interruptor desligado, outra thread, antes da thread do
  servidor, exceção no mapa), os contadores, a estatística no log com relógio e ticks falsos (primeira chamada só
  arma; antes de 5 min não escreve; depois escreve a janela e os totais; `0` = nunca), o registro e a config
  (padrões, seção `arsmagica` criada no `hoshiestar.yml` sem avisos, `estatistica` negativa vira 0), as falhas de gancho
  informadas pela ponte (`falhaVarredura`/`falhaDiluicao` desligam só o atalho certo), o relógio da estatística começando em 0 ou
  negativo (monotônico).
* *Transformador sobre a classe REAL*: o hash do jar bate com a trava; as duas correções aplicadas pelo
  registro real; `CheckClassAdapter` sem erros; o **verificador da JVM** aceita a classe (carregada e ligada;
  controle: a original também liga e, sem o quadro de pilha de uma ponte, a JVM recusa com `VerifyError`);
  **forma exata**: os outros 39 métodos da classe idênticos instrução por instrução, os dois métodos alvo =
  original com **exatamente** a(s) chamada(s) trocada(s) pela(s) ponte(s) (e os mesmos blocos `try/catch`),
  cada ponte `private static synthetic` (formato protegido) com `deve`, `sem`, `aoFalhar` e a chamada original, uma vez cada e nessa ordem,
  um único `try/catch` de `Throwable` que cobre só `deve` e `sem` (o `aoFalhar` e a chamada original ficam fora); com só um
  interruptor ligado só o respectivo é transformado; as chamadas duplicadas de `HandlePlayer...` (2 de
  `getSkill` e 2 de `isSkillDisabled` fora de `onEntityLiving`) **não** são tocadas; recusas (interruptor
  desligado, trava errada nas duas, gancho ausente); **atomicidade** do F2 (sem a segunda chamada, nem a
  primeira fica trocada).
* *Subcomando*: estado, desligar, ligar recusado sem correção aplicada, ligar com a correção aplicada na
  classe real (limpa o "desligado por erro"), uso inválido; **auto-checagem reprovada** (os dois atalhos ficam desligados, a linha
  de estado diz `GANCHOS COM DEFEITO`, o comando recusa ligar) e auto-checagem que nem roda com os dois atalhos desligados no yml.
* *Ordem dos transformadores*: nos jars do AM2 e do AM2PlayGround, nenhuma classe de transformador ou coremod cita
  `AMEventHandler`, e nenhum `META-INF/*_at.cfg` (access transformer) cita `AMEventHandler`, `SkillManager` nem `SkillTreeManager`.

**Harness (116 verificações em cada JDK):** a classe real reduzida aos dois métodos alvo (e às pontes), com
Minecraft/Forge de mentira, depois de passar pelo transformador real e pelo `ResolvedorDeGanchos` com os
ganchos do jar construído (a verificação confere que o gancho testado é o do `-srg.jar` do server.jar).

* *Diferencial, 8 sementes x 12.000 iterações* (a cada iteração: 3 jogadores chamam o F1 e 6 entidades vivas
  disparam o F2), cada rodada num class loader novo (o AM2 guarda estado em campos estáticos). A cada iteração entram
  feitiços de aceleração e de retardo (variáveis extras dos jogadores, que o **AM2 real** transforma nas entradas
  dos mapas), jogadores que saem e voltam (entradas velhas), entidades que nascem e morrem, **armadilhas no meio
  da varredura** (uma entidade atualizada pelo Accelerate tira ou põe entrada no mapa durante o próprio laço),
  habilidade ausente ou desativada. O hash do estado (atualizações de cada entidade, cancelamentos, mapas, variáveis
  dos jogadores, contador interno do AM2) é **igual** entre: original, ligado, desligado, interruptores
  trocados ao acaso no começo e no meio de cada iteração, só F1 e só F2 (6 rodadas por semente, 48 no total). Também
  são iguais o total de atualizações extras e de cancelamentos e o número de `NullPointerException` que o próprio AM2
  captura e imprime (entrada que some no meio da varredura).
  Evidência de que os atalhos agiram: **o que o AM2 deixou de pedir (lista de mundos, `isSkillDisabled`,
  `getSkill`) é exatamente o que o gancho contou como pulado** em todas as sementes; com tudo desligado o AM2 faz as
  mesmas chamadas internas do original.
  Totais (JDK 8, ligado): F1 69.351 puladas e 79.529 no original; F2 253.524 puladas e 322.476 no original; 94.369
  atualizações extras do Accelerate; 1.154 eventos cancelados pelo Dilute; 430 `NullPointerException` engolidos pelo
  próprio AM2. Os dois lados de cada decisão e os mapas vazios e não vazios (inclusive sementes em que o mapa quase
  nunca esvazia: 2.000 puladas em 12.759 chamadas) aconteceram.
* *Casos montados a mão* (`mundo/Casos`; observáveis iguais no original, ligado e desligado, e o que o AM2 deixou
  de fazer = o que o gancho pulou, caso a caso): **mapa vazio** (ligado: 0 de 5 pedidos de lista de mundos e 0 de 3
  consultas do DiluteTime; original: 5 e 3); **mapa com 1 entrada** (acelerada com poder 2, retardada com n=2,
  habilidade desativada e ausente: nada pulado); **entrada removida entre chamadas**; **entrada removida no meio da
  varredura** (outra entrada, e a própria: o `NullPointerException` que o AM2 imprime é igual); **interruptor trocado em
  execução** (pula só enquanto ligado e com o mapa vazio: 3 de 7 nos dois); **pontes do F2 passo a passo**, com o
  interruptor trocado **entre** `getSkill` e `isSkillDisabled` em todas as combinações (mapa cheio ou vazio,
  habilidade registrada ou não, desativada ou não, 2 ticks): a conta do original sempre igual; **outra thread**
  (nunca pula); **estresse** (uma thread troca os interruptores sem parar durante 240 mil chamadas: 0 divergências);
  **erro nosso** (o mapa lança exceção: 2 erros, os dois atalhos se desligam, o AM2 segue original).
* *Reentrância* (caso adversário do revisor, `mundo/Reentrancia`, 3 sementes): uma entidade acelerada, ao receber a atualização extra
  **dentro** do laço do F1, tira a própria entrada, troca os interruptores, chama de novo o método do jogador 2 e o `onEntityLiving`
  de outras entidades (com e sem entrada de DiluteTime, com cancelamento) e devolve a entrada: original = ligado = desligado =
  trocado (442 observáveis; 67 varreduras e 294 checagens do F2 puladas nos aninhados).
* *Ganchos que não ligam* (achado I1, seção 4.3): o `GanchoAM2` do server.jar é **mutilado** de oito jeitos (cada um dos seis métodos
  ausente; `semMundos` e `semSkill` com o retorno de outro tipo). Para cada um: a auto-checagem acusa o gancho certo; a ponte protegida,
  nos modos 1 a 4 (ligado, trocado, só F1, só F2), dá o **mesmo hash do estado do original** e o erro é contado; e o **controle** (formato
  comum, sem proteção) deixa o `NoSuchMethodError` sair do método do AM2. A classe intacta passa na auto-checagem. Na base
  (`TesteAsm`, Java 6, 7 e 8): ponte protegida sem falha, gancho `deve` que lança, `sem` que lança, chamada `void`, receptor
  (interface e privado), gancho cuja **classe não existe** em tempo de execução (`NoClassDefFoundError`), gancho **sem o método
  `deve`** e **sem o método `sem`** (`NoSuchMethodError`), segunda chamada também segue pelo original, forma (um `try/catch`, original
  fora), recusas (`aoFalhar` ausente, nulo, com descritor errado) e o controle do formato comum.
* *Só F1 não age no F2* agora confere também `getSkill` (lacuna M2 da revisão: mutação A1 do revisor passou a ser acusada).
* *Controle negativo*: quatro ganchos **errados de propósito** (`GanchoErrado`: F1 pula sempre; F1 pula com o mapa
  não vazio; F2 pula `isSkillDisabled` sempre; F2 pula `getSkill` sempre) mudam o hash, ou seja, o harness enxerga o erro.
* *Indicação de custo* (**não é medição de servidor**): `HandlePlayerSpellExtendedProperties` com o mapa vazio e 60 mundos x 40
  entidades (metade viva): ~248.000 ns por chamada desligado e ~47 ns ligado, incluindo o custo da reflexão do
  harness. Mostra a ordem de grandeza (a produção inferiu ~250 µs por chamada), nada mais.

**Mutação do fonte** (só no primeiro JDK; `Atalhos.java` mutado e compilado à parte, harness em modo rápido):
dez proteções tiradas, **todas acusadas** (o harness falhou em cada uma): F1 pula com o mapa não vazio; F1 ignora o
interruptor; F1 pula sempre; F2 (`isSkillDisabled`) pula com o mapa não vazio; F2 (`getSkill`) pula sempre; F1 sem a
regra de outra thread; F2 sem contar as puladas; F1 conta a pulada como original; F1 pula quando dá erro nosso; **F2 (`getSkill`) decidido pelo interruptor da varredura** (a
mutação A1 do revisor, que antes passava em tudo). A
décima possível (F2 pular quando dá erro no segundo passo) é equivalente na prática: o primeiro passo usa o mesmo mapa e
falha antes, desligando o atalho.

## 7. O que NÃO foi verificado

1. **Que o 1,94 % atribuído a `isPotionActive` é o laço de todos os mundos.** É inferência forte do relatório (o spark
   rodou sem `-XX:+DebugNonSafepoints` e não gravou números de linha); o teste de servidor (seção 8) confirma ou não.
   Se estiver errada, o F1 vale ~0,14 %.
2. **Que os mapas ficam vazios na produção.** Podem ficar com entrada velha (lançador offline). A linha de estatística
   e `hoshie arsmagica` mostram.
3. **Que `enabled_accelerate` (e `enabled_slow`) estão ligados na produção** (inferido das chamadas no perfil, não lido da
   config do AM2; no `<clinit>` real os dois são `true` por padrão).
4. **Ganho real em ms/tick**: nenhum servidor foi ligado. O número do harness é só indicação.
5. **Servidor real**: aplicação das duas correções no modpack de 292 mods (logs `aplicada`), que os bytes que chegam
   ao transformador são os do jar (`igual ao arquivo do jar`), carga do gancho pelo `LaunchClassLoader` com a classe
   `GanchoAM2` recebendo os tipos do Minecraft reofuscados (`WorldServer` na lista de mundos; a base provou isso para
   outros ganchos, mas **este** gancho com `WorldServer[]` no retorno só foi testado traduzindo os nomes pelo
   `RemapeadorSrg` do teste, e não pelo `RemapeadorFml` de produção; os dois herdam o `mapMethodDesc` de `Remapper`
   do ASM, que trata arrays do mesmo modo, mas isso é leitura de código, não prova). **Se falhar, agora a falha é
   fechada** (seção 4.3: auto-checagem no boot e ponte protegida), mas o log do boot tem de mostrar
   `ganchos conferidos ...` para provar que ligou; e o comportamento do feitiço Accelerate/Dilute Time com um jogador real.
6. **Boot em Java 8** (a produção usa Java 21; a base também não testou).
7. **Que o JIT inline as pontes e os ganchos** (custo esperado: uma chamada estática e uma leitura de campo volátil).
8. **A classe em tempo de execução depois de todos os transformadores genéricos** (FML `SideTransformer` e outros): o teste
   usa os bytes do jar. A varredura dos jars e os logs de boot não mostram ninguém transformando a classe.
9. **Outras versões do AM2**: a trava recusa; nada foi feito para elas.
10. **Custo do `try` da ponte protegida e da auto-checagem em servidor**: por desenho, nenhum custo no caminho normal (o
    `try` é só uma tabela de exceções); não medido.
11. **Que a ponte protegida passa no verificador da JVM de produção (Java 21) com a classe real inteira**: o teste isolado
    verifica a classe real inteira transformada com o verificador da JVM no JDK 8 e no 21; o servidor real não foi ligado.
12. **Revisão de concorrência por outro agente**: não necessária (código de thread única; a regra "outra thread roda o
    original" é a única concorrência), mas não foi feita.

## 8. Plano de teste no modpack real (para o agente que liga o servidor)

Regras: só na cópia de teste criada por `scripts/prepare-test-env.sh`; parar com `echo stop >> console.in`; nada de
spark (use JFR); nada de jar nosso em `mods/` ou `plugins/`; **nunca** imprimir valores de config do servidor.

**Preparação**

1. Construir com os ganchos de mods: `servidor/build.sh --rapido <workspace> --github-raw --sem-testes --mods <mods/ da cópia>`
   (e conferir `unzip -l <jar> | grep GanchoAM2`).
2. Cópia nova de teste só com esse `server.jar`. Primeiro boot **com tudo no padrão** (sem `hoshiestar.yml`): conferir
   `recurso arsmagica: desligado`, as duas correções `desligada`, o `hoshiestar.yml` criado com a seção `arsmagica`.
3. Parar. No `hoshiestar.yml` pôr `arsmagica.varredura: true`, `arsmagica.diluicao: true`, `arsmagica.estatistica: 1`.
   Para as medições com JFR, o `start` da cópia leva `-XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints`.

**Passo 1, boot com os atalhos ligados**

* No log: duas linhas `[Hoshiestar] [embutidos] arsmagica: ... aplicada` (uma por correção) ou, se não, o motivo (trava com
  o hash visto, mod ausente, padrão não encontrado); as linhas do fim do boot `correcao: ... aplicada`; a posição do
  transformador (`2 de ...`, Mixin depois). **E a linha da auto-checagem (seção 4.3)**:
  `[Hoshiestar] [arsmagica] ganchos conferidos com as classes de tempo de execucao: ligam com os descritores das pontes`.
  Se em vez dela vier `GANCHOS COM DEFEITO: <motivo>`, **parar**: o atalho ficou desligado de propósito; mandar o motivo
  (é o caso do tipo `WorldServer[]` traduzido diferente) e o `hoshie status`. Esperado: sem `NAO aplicada`, sem `VerifyError`, `NoSuchMethodError`,
  `IllegalAccessError` ou `NoClassDefFoundError` com `hoshiestar` ou `am2`. Comparar as contagens de erro de classe e de
  mixin com o boot do `server.jar` original na mesma máquina.
* `hoshie status` (console): linha do `arsmagica` com `(correcao aplicada)` nos dois atalhos. Se algum disser
  `NAO roda`, parar e mandar a linha (traz o hash).
* Se a correção não aplicou, **não** seguir para os passos seguintes: registrar o hash visto e o motivo e parar.

**Passo 2, o servidor roda sem erros e sem mudar o jogo**

* **O primeiro movimento do bot é o ponto de risco** que a revisão apontou (o `semMundos` só é ligado no primeiro pulo): a
  proteção agora faz a falha cair no original e a auto-checagem do boot já a teria acusado, mas é aqui que se prova. Logo
  depois do primeiro movimento, `hoshie arsmagica` tem de mostrar `erros 0` e `varredura de mundos: puladas` > 0. Se
  `erros` > 0, há uma linha `erro no atalho do Ars Magica 2 (ponte da varredura ...)` com a exceção (provavelmente
  `NoSuchMethodError ... semMundos`): anotar e parar.
* Bot (`tools/test_bot.py`, op) entra, anda e fica parado 5 minutos, com `forge tps` 3 vezes. Esperado: nenhum
  `ERROR` novo no `fml-server-latest.log` com `hoshiestar` ou `am2`; `hoshie arsmagica` mostra `varredura de mundos:
  puladas N` e `checagem DiluteTime: puladas M` crescendo; `erros 0`; `outra thread 0` (ou pequeno: anote o valor).
* Esperar a linha de estatística (1 minuto): conferir que `puladas` cresce e `mapas do AM2 agora: aceleradas 0,
  retardadas 0` (é o caso esperado sem ninguém ter lançado o feitiço). **Se os mapas mostrarem entradas sem
  ninguém ter lançado nada, anotar** (ganho zero nesse caso).

**Passo 3, antes e depois no mesmo boot (JFR)**

1. Montar a carga do relatório: ~400 porcos e ~1.500 itens parados em volta do spawn (`summon`), o bot online.
2. `hoshie arsmagica varredura desligar` e `hoshie arsmagica diluicao desligar` e conferir `hoshie status` ("desligada").
   `jcmd <pid> JFR.start name=am2off settings=profile duration=300s filename=<scratchpad>/F-jfr/am2off.jfr`;
   no meio, `forge tps` 3 vezes.
3. `hoshie arsmagica varredura ligar` e `hoshie arsmagica diluicao ligar`; repetir a gravação (`am2on.jfr`) com a mesma
   carga. Repetir desligado, ligado (`off, on, off, on`), 300 s cada.
4. **Gravação de referência com a classe intacta** (revisão M7): o "desligado" pelo comando ainda passa pelas pontes. Para ver o
   custo delas, pare, ponha `arsmagica.varredura: false` e `arsmagica.diluicao: false` no `hoshiestar.yml` (a classe do AM2
   **não** é transformada: confira `correcao ... desligada` no log), suba, monte a mesma carga e grave `am2intacta.jfr` (300 s).
   Compare as amostras de `HandlePlayerSpellExtendedProperties`/`onEntityLiving` entre `am2intacta`, `am2off` (pontes, atalhos
   desligados) e `am2on`.
5. Ler com `jfr print --events jdk.ExecutionSample`: contar amostras da thread do servidor com
   `AMEventHandler.HandlePlayerSpellExtendedProperties` na pilha e ver em **quais linhas** do fonte elas caem (laço = linhas
   775 a 799; `isPotionActive` = linha 1175). Esperado: desligado, a maioria no laço (confirma a atribuição do relatório);
   ligado, essas amostras somem; `SkillTreeManager.isSkillDisabled` quase some do caminho de `onEntityLiving`. Anotar
   também o tempo médio de tick (`forge tps`). Com 1 bot e ~1.900 entidades o ganho no tick é pequeno e pode ficar no
   ruído do `forge tps`; a contagem de amostras do JFR é o que decide.
6. Registrar: amostras antes e depois, ms/tick antes e depois (com a variação entre as repetições), e as linhas de
   estatística.

**Passo 4, mapa NÃO vazio (o único caso em que o atalho tem de recuar)** (manual, **receita derivada do código, não executada**)

O harness com o bytecode real cobre esse caso (mapa com 1 entrada, entrada que some no meio, reentrância; seção 6), então este passo
**confirma no servidor real**; se não der para montar o feitiço em ~20 minutos, registre "não montado" e siga: o harness é a prova
(decisão do coordenador, achado I2 da revisão). O bot do projeto não lança feitiço: precisa de um **jogador humano op**.

O que o código do AM2 mostra (decompilado do jar): "Concentrate Time" é o componente `ConcentrateTime` (id 95) e "Dilute Time" é
`DiluteTime` (id 97). Lançado **sobre uma entidade** (inclusive o próprio lançador, com a forma "Self"), cada um grava no
`ExtendedProperties` do lançador a variável `accelerated_fast_entity_<poder>_<uuid do alvo>` (ou `accelerated_slow_entity_...`) com a
duração (base 600 x 2 ticks, ou seja ~60 s, mais com modificadores). O `HandlePlayerSpellExtendedProperties` do lançador, no pacote de
movimento seguinte, transforma a variável em entrada do mapa; ela sai no tick do lançador quando a duração acaba. Lançado sobre um
**bloco**, vira `..._tile_...` (isso é do F5, fora daqui, e **não** enche estes mapas).

1. Preparar (comandos que existem no AM2, nomes lidos do código; o Tab completa as habilidades): `/setskillknown <jogador> ConcentrateTime`,
   `/setskillknown <jogador> DiluteTime`, `/giveskillpoints <jogador> <n>`, `/fillmanabar <jogador>`, `/setmagiclevel <jogador> <n>`.
   Montar o feitiço **na Mesa de Inscrição** (Inscription Table) em criativo: forma "Self" (ou Touch/Projétil para um porco parado) +
   componente "Concentrate Time". **Como montar na mesa e os nomes exatos das habilidades não foram verificados aqui**: anotar os passos reais.
2. Com `hoshie arsmagica varredura desligar`: lançar. Esperado (observável e objetivo, pelo console): `hoshie arsmagica` mostra
   `mapas do AM2: aceleradas 1, retardadas 0` por ~60 s; depois volta a `aceleradas 0`.
3. Com `hoshie arsmagica varredura ligar`: lançar de novo. Esperado: o mesmo efeito (o alvo age mais rápido, ou o lançador se move
   mais rápido na forma Self); **enquanto `aceleradas 1`, `varredura de mundos: puladas` NÃO cresce e `original` cresce**; ao voltar a
   `aceleradas 0`, `puladas` volta a crescer. É a prova de que o atalho recua com o mapa cheio.
4. O mesmo com "Dilute Time" (`retardadas 1`; o alvo se move devagar, eventos cancelados em ticks alternados) e
   `checagem DiluteTime: puladas` parada enquanto a entrada existir.
5. Deslogar o lançador com o efeito ativo: o mapa continua com a entrada e o atalho para de pular (o ganho some, sem dano):
   conferir com `hoshie arsmagica`. Voltar e esperar o efeito acabar.
6. Se alguém quiser os invariantes da conferência (`M − U = n × B`, `M ≈ U/2`, `M = U`), precisa de um ouvinte de contagem de
   `LivingUpdateEvent`, que não existe: não fazem parte deste passo.

**Passo 5, parada**: `echo stop >> console.in`; conferir "sem crash-report" e que o `hoshiestar.yml` não foi regravado
(mesmo SHA-256 e data) se estava completo.

## 9. Desvios do desenho (e por quê)

* **Mecanismo**: transformador do fork (formato "envolver uma chamada") no lugar de Mixin `@WrapOperation`/late mixin. As
  pontes são métodos sintéticos criados dentro de `AMEventHandler`.
* **Chaves e arquivo**: `hoshiestar.yml` (seção `arsmagica`: `varredura`, `diluicao`, `estatistica`) no lugar de
  `config/hoshiestar.cfg` (`am2.skipAccelScan`, `am2.skipDiluteCheck`, `am2.logStatsMinutes`). Os dois atalhos são
  **desligados** por padrão (decisão do coordenador; o desenho pensava em ligar depois do teste 8.1).
* **F2 sem campo `@Unique`**: a conferência sugeriu gravar a decisão do primeiro passo num campo estático para o segundo
  ler; foi escolhido o contrário (cada passo decide sozinho, lendo o mapa ao vivo), por não criar estado compartilhado
  entre threads; a equivalência para qualquer combinação é provada no bytecode e testada (seção 6). O contador do F2 conta
  no segundo passo.
* **Regra de outra thread** (acrescentada): chamada fora da thread do servidor sempre roda o original.
* **Comando `hoshie arsmagica`** (acrescentado): estado e troca do interruptor em execução, para o antes e depois no mesmo
  boot. Não estava no desenho.
* **Linha de estatística**: obrigatória (conferência 3.3); a cada `estatistica` minutos, também no `hoshie status`.
* **Teste 8.1 do desenho** (contagem de `LivingUpdateEvent` com um comando de teste dentro do servidor) foi substituído pelo
  harness com o bytecode real; os invariantes da conferência (`M − U = n × B`, `M ≈ U/2`, `M = U`) continuam valendo e
  ficam para o passo 4 do plano de servidor.
* **Fora**: vigia (1.H), F5 (`hasWorldObj`, 0,66 %), F3 e F4 (decisão do coordenador).
* **Base: formato 3 protegido (depois da revisão, achado I1)**: `Asm.envolverChamadaProtegida` (método novo em
  `src/main/java/hoshiestar/embutidos/asm/Asm.java`; o `envolverChamada` comum gera os mesmos bytes de antes, o autoteste e os
  outros recursos não mudam). Testado em `TesteAsm` (amostras Java 6, 7 e 8; 224 para 401 verificações). Como é arquivo comum, o
  coordenador deve conferir a junção com a outra faixa.
* **Relógio da estatística**: passou de `currentTimeMillis` para `nanoTime` (monotônico), achado M4.
* **Base (alterações mínimas nos arquivos compartilhados)**: `src/test/mods/java/hoshiestar/teste/mods/CarregadorMods.java`
  ganhou dois prefixos de pacote (`hoshiestar.arsmagica.atalho.` e `hoshiestar.teste.arsmagica.mundo.`) na lista dos que
  ficam no class loader filho. Nenhuma mudança em `test.sh`, `build.sh` nem no Gradle (o caminho `--mods` já servia). No
  `Servidor.iniciando`, 5 linhas para registrar o subcomando.

## 10. Para quem for mexer

* **Interruptores novos**: um campo `arsmagica_*` em `HoshiestarConfig` (bloco `// ---- arsmagica ----`, no fim dos campos).
* **Outra versão do AM2**: pegar o hash (`hoshie hash am2.AMEventHandler`), conferir com `javap -c -p` que os dois métodos
  têm **uma** `getWorlds` (F1) e **uma** `getSkill` + **uma** `isSkillDisabled` (F2) com os mesmos descritores e que o resto do
  laço continua não lendo os locais 4, 5 e 6 depois dele; rodar o teste isolado; só então acrescentar o hash em
  `ArsMagica.SHA_EVENTOS` (uma lista de hashes aceitos, se for mais de um).
* **Mexer na decisão**: ela mora em `Atalhos.java` (sem classes do jogo) e o teste de mutação tira cada proteção; se uma
  proteção nova entrar, acrescente a mutação em `arsmagica.bash`.
* **Armadilha dos testes**: o `AMEventHandler` guarda estado em campos estáticos; use um class loader novo por rodada.
