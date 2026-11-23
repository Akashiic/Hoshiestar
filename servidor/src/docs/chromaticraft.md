# Recurso `chromaticraft` (item 1.B): limites na habilidade "mover área" do ChromatiCraft

Correção embutida no `server.jar` do Hoshiestar (nada vai em `mods/` nem em `plugins/`). O recurso inteiro vem
**desligado** por padrão. Relatórios de origem: `docs/pesquisa/etapa1/B-chromaticraft.md` (desenho) e
`B-chromaticraft-conferencia.md` (conferência; onde divergem vale a conferência, e a decisão 19 do coordenador
manda implementar a versão do conferente: nenhuma mudança dentro do laço do mod). Os relatórios falavam em Mixin
(`@Inject`); aqui o mecanismo é o transformador do fork: formato 1 **protegido** da base (gancho no início do método,
com retorno antecipado, dentro de um `try/catch` de `Throwable` posto no bytecode) para a decisão e formato 3
**protegido** (ponte com `try/catch`) para a medição opcional. Protegido quer dizer: se o gancho não ligar em tempo
de execução ou lançar uma exceção, o método do mod segue pelo corpo original (ver seção 4 e o item I1 da seção 11).

Termos: **mover área (SHIFT)** = habilidade do ChromatiCraft em que o jogador marca uma caixa com dois cliques
direitos e o mod a desloca de até 24 blocos. **Caixa** = o cubo marcado. **Volume** = número de posições que o
mod percorre, `(dx+1)*(dy+1)*(dz+1)`. **Gancho** = método nosso que a classe do mod passa a chamar depois de
transformada. **Trava de versão** = SHA-256 (impressão digital) dos bytes originais de uma classe; a correção só
entra se bater. **Chunk** = pedaço do mundo de 16x16 blocos de largura. **Chunk carregado** = que já está na
memória do servidor.

---

## 1. O que faz, em linguagem simples

Em produção o servidor ficou **mais de 30 s sem tick** (D-21) dentro de `AbilityCalls.shiftArea`, o método que
faz o "mover área". O motivo, lido no código do mod:

* O tamanho da caixa é escolhido pelo jogador sem limite (dois cliques; o primeiro canto fica guardado para
  sempre; com a habilidade REACH o clique alcança 192 blocos).
* O mod percorre **toda** a caixa na thread do servidor e só depois calcula o custo de energia e a permissão.
  Por posição ele faz várias leituras do mundo (que **carregam ou geram chunks** na hora), cinco inserções em mapas
  e, depois, dois eventos de quebra (Forge e Bukkit) e duas gravações de bloco.
* A conta de custo dá 1 para caixa de uma camada, só de ar, distância negativa ou grande demais. Ou seja, caixas
  enormes saem de graça.
* O pacote que arma a habilidade não confere a distância (um cliente modificado manda qualquer inteiro).
* O mod apaga a origem **antes** de descobrir que o destino sai de y 0..255: o bloco que sai do mundo some
  (perda de bloco, com cliente normal: de y ≥ 232 para cima 24, ou de y ≤ 23 para baixo 24).

O Hoshiestar põe **no início** de `shiftArea` uma chamada a um gancho nosso. Antes de qualquer bloco ser lido,
movido ou removido, o gancho faz estas conferências, **todas só de leitura**, nesta ordem (a primeira que falha
é o motivo da recusa):

1. **Intervalo mínimo** entre dois pedidos aceitos do mesmo jogador (padrão 1000 ms).
2. **Distância** entre 0 e a potência máxima da habilidade (`Chromabilities.SHIFT.getMaxPower()`, 24 neste jar).
3. **Volume** `(dx+1)*(dy+1)*(dz+1)` (conta em `long`, nunca estoura) no máximo `volume` (padrão provisório 4096).
4. **Altura do destino**: a caixa deslocada tem de ficar inteira em y de 0 a 255.
5. **Chunks carregados**: todos os chunks da caixa de origem e da de destino, alargadas em 1 bloco em x e z (os
   vizinhos que o `setBlock` avisa), já estão no mapa de chunks carregados. A consulta é só de leitura nesse mapa:
   **nunca carrega nem gera chunk**.

Se uma conferência recusa, o gancho repete o ramo original de "sem energia" do mod (som de erro,
`SHIFT.setToPlayer(jogador, false)`, devolve `false`), manda uma **mensagem no chat** (configurável) e escreve
**uma linha no log** (jogador, dimensão, caixa, volume, distância, direção, motivo), no máximo uma por jogador a
cada `logsegundos` s. Se todas passam, **o código original roda inteiro, sem nenhuma mudança** (o laço do mod não é
tocado). O jogador **não recebe de volta** a taxa de energia cobrada ao armar a habilidade (o original também não
devolve quando falta energia; recalculá-la arriscaria criar energia).

Uma segunda correção, opcional (`loglento`, padrão 50 ms), só **mede**: registra no log todo "mover área" que
demorar esse tempo ou mais (quem, onde, tamanho, resultado, ms). Serve para calibrar o `volume`. Ela também é
protegida: se o gancho de medição falhar, a chamada original do mod acontece do mesmo jeito.

**O que muda para os jogadores** (com o recurso ligado): mover área fica recusado quando a caixa passa do volume,
quando o destino sairia da altura do mundo, quando alguma parte da origem ou do destino está em chunk não
carregado, ou quando o mesmo jogador tenta de novo antes de `intervalo` ms. Isso vale também para criativo e
operadores. Dentro dos limites o resultado é idêntico ao original.

**Ganho esperado.** Média: 0 % (o perfil de 602 s não contém a habilidade: 0 amostras em `shiftArea`,
`AbilityCalls`, `playerCanBreakAt` e `BlockBox`). O ganho é no pior caso: uma chamada deixa de poder ser
ilimitada. **Não** é "o travamento acabou": uma chamada dentro do limite ainda faz as fases de permissão e gravação
(custo por bloco **não medido**, ver seção 7), e o intervalo mínimo limita a repetição.

## 2. Chaves no `hoshiestar.yml`

Seção `chromaticraft`. O arquivo é lido uma vez, no boot. Valor inválido: aquele item usa o padrão e sai um aviso.

| Chave | Padrão | O que faz |
|---|---|---|
| `ligado` | `false` | Interruptor do recurso inteiro. Desligado: a classe do mod nem é alterada (bytes originais). |
| `intervalo` | `1000` | Conferência 1: ms mínimos entre dois pedidos **aceitos** do mesmo jogador (recusa não renova a hora). 0 = não confere. |
| `distancia` | `true` | Conferência 2: recusa distância < 0 ou > máximo da habilidade (24). |
| `volume` | `4096` | Conferência 3: volume máximo (16x16x16). 0 = sem limite. **Provisório**: o valor final sai da medição no servidor (seção 8). |
| `altura` | `true` | Conferência 4: recusa se o destino não cabe em y 0..255. |
| `chunks` | `true` | Conferência 5: recusa se algum chunk da origem ou do destino (alargados 1 bloco) não está carregado. |
| `mensagem` | `Mover area recusado: {motivo}.` | Chat do jogador recusado; `{motivo}` vira o texto do motivo. Vazio = sem mensagem. |
| `loglento` | `50` | Registra no log todo "mover área" que demorar pelo menos tantos ms (0 = nem mede; a segunda correção fica desligada). |
| `logsegundos` | `10` | No máximo uma linha de log de recusa por jogador a cada tantos segundos (mínimo 1); as outras só entram na conta (a linha seguinte diz quantas foram suprimidas). |

Valores negativos de `intervalo`, `volume` e `loglento`, e `logsegundos` menor que 1, são corrigidos no registro,
com aviso no log.

Em execução (sem reiniciar): `hoshie chromaticraft` mostra o estado; `hoshie chromaticraft desligar` /
`ligar` baixa/levanta os limites só na memória (o `hoshiestar.yml` não muda; vale de novo no próximo boot). `ligar`
só funciona se a correção está aplicada na classe do mod **ou vai se aplicar quando a classe carregar** (então um
`desligar` seguido de `ligar` antes do primeiro uso não deixa o primeiro uso sem limites) e a auto-checagem dos
ganchos passou; se a trava de versão ou o padrão recusou a classe, ou o recurso está desligado no yml, o comando
recusa e explica. O subcomando `hoshie chromaticraft` existe sempre que o ChromatiCraft está instalado, mesmo com o
recurso desligado (mostra `desligado`). Permissão como o resto do `hoshie` (console, RCON ou operador nível 4 com o
nó `hoshiestar.admin`).

## 3. O que muda na classe do ChromatiCraft

Classe alvo: `Reika.ChromatiCraft.Auxiliary.Ability.AbilityCalls` (jar `ChromatiCraft 1.7.10 V33a.jar`), método
estático `shiftArea(Lnet/minecraft/world/WorldServer;LReika/DragonAPI/Instantiable/Data/Immutable/BlockBox;Lnet/minecraftforge/common/util/ForgeDirection;ILnet/minecraft/entity/player/EntityPlayerMP;)Z`.

**Correção 1 (decisão, formato 1 PROTEGIDO da base, `Asm.ganchoNoInicioProtegido` com retorno antecipado).** O método
passa a começar assim (só isso é novo; o resto é o original, **fora** do `try`):

```java
static boolean shiftArea(WorldServer mundo, BlockBox caixa, ForgeDirection dir, int dist, EntityPlayerMP ep) {
    try {
        if (GanchoShift.recusar(mundo, caixa, dir, dist, ep)) return GanchoShift.valorRecusado(mundo, caixa, dir, dist, ep); // false
    } catch (Throwable t) {
        ChromatiCraft.falhaGancho(t);   // so conta e registra; o original segue
    }
    ...corpo original, sem nenhuma mudança...
```

`ChromatiCraft.falhaGancho` mora numa classe nossa **sem tipo do Minecraft nem do mod**, para ligar mesmo quando o
`GanchoShift` não liga. Esse `try/catch` é o que impede um erro de ligação (`NoClassDefFoundError`,
`NoSuchMethodError`, `VerifyError`) de sair de dentro de `shiftArea`: sem ele o erro sairia como `Error`, que o
`NetworkSystem` do servidor não captura, e derrubaria o tick (achado I1 da revisão).

**Correção 2 (medição, opcional, formato 3 PROTEGIDO `Asm.envolverChamadaProtegida` duas vezes).** A base não tem
formato "gancho no retorno", então a medição envolve duas chamadas que só aparecem **uma vez** em `shiftArea` (a base
exige o total): `ChromaSounds.ERROR.playSound(ep)` do ramo de falha e `Chromabilities.SHIFT.setToPlayer(ep, false)`,
a **última** coisa antes de `ireturn`. Cada uma passa por uma ponte sintética na classe do mod:

```java
try { if (!GanchoShift.antesDeTerminar(SHIFT, ep, false)) return; } catch (Throwable t) { ChromatiCraft.falhaGancho(t); }
SHIFT.setToPlayer(ep, false);   // a chamada ORIGINAL, fora do try, sempre acontece
```

O gancho só mede (`antesDoSom` marca que falhou; `antesDeTerminar` mede o tempo desde a decisão e registra se passou de
`loglento`) e devolve sempre `true` ("chame a original"). Se `loglento` é 0, esta correção nem entra.

**Defeito achado na auto-checagem do boot.** Se a auto-checagem dos ganchos (seção 4) reprovar, **nenhuma** das duas
correções é aplicada à classe do mod, mesmo que ela só carregue depois (a transformação consulta o sinalizador).

**Travas de versão.** `AbilityCalls` (as duas correções) e as classes de cuja forma a lógica depende (conferidas
quando `AbilityCalls` passa pelo transformador, lendo o `.class` sem carregar; qualquer uma diferente ou ausente =
a decisão não é aplicada, o mod fica original):

| Classe | Jar | SHA-256 aceito |
|---|---|---|
| `Reika.ChromatiCraft.Auxiliary.Ability.AbilityCalls` | ChromatiCraft 1.7.10 V33a | `8bcbe85ec63bca6a6146081f93b1ea4202dcd7fd9d3b06f635649df8a80be7a5` |
| `Reika.DragonAPI.Instantiable.Data.Immutable.BlockBox` | DragonAPI 1.7.10 V33b-ForbiddenFix1 | `e5175ca3fe44a9de1cbd78c1dedb2c0a148d870891d17a35eaa3259e59c7b1d8` |
| `Reika.DragonAPI.Instantiable.Data.BlockStruct.BlockArray` | idem | `1dd1a2f75e9fe94517050b9cc4f905dede8178576ccfb492c532738f245264ab` |
| `Reika.DragonAPI.Instantiable.Data.BlockStruct.FilledBlockArray` | idem | `1476505c756c6c346324eb85151feea34b01cda50a57b60cba7f27cdf4507bb8` |
| `Reika.ChromatiCraft.Registry.Chromabilities` | ChromatiCraft | `0d4ce3736ea1cb3fb9360582579975f1458dd2bce239a44abd9e688015694981` |
| `Reika.ChromatiCraft.Auxiliary.Ability.AbilityHelper` | ChromatiCraft | `3bbcc0779892a586eee1eb4908c1bea09dcbc85f533f136a7539ad44df7f6706` |

Por que cada dependência: `BlockBox` (campos `minX..maxZ` e `getSizeX/Y/Z() = max - min`, que o laço consulta: o
volume que calculamos tem de ser igual ao número de voltas); `BlockArray`/`FilledBlockArray` (`offset(dir, dist)`
desloca cada eixo em `direcao.offset * dist`, a conta do destino que repetimos); `Chromabilities`
(`SHIFT.getMaxPower()` = 24 e `setToPlayer(ep, boolean)`); `AbilityHelper` (único chamador de `shiftArea`: ignora o
valor devolvido e depois tira o primeiro clique guardado, por isso recusar não deixa estado preso). Hashes
recontados no jar real nesta tarefa (`unzip -p ... | sha256sum`) e iguais aos do código.

**Quem mais mexe nessa classe** (conferência do relatório, não refeita aqui): ninguém. Só o próprio ChromatiCraft
tem `AbilityCalls`/`shiftArea`; o coremod do ChromatiCraft (`ChromaASMHandler`) não altera essa classe; nenhum mixin
dos mods em `mods/` a toca. O transformador do Hoshiestar fica na posição 2 da cadeia (antes do Mixin).

## 4. Como funciona

**`hoshiestar.chromaticraft.DecisaoShift`** (`src/main/java`, código puro: só inteiros, um mapa e um relógio; é o
mesmo código que roda no servidor e que o teste isolado exercita). `decidir(jogador, caixa, direção, dist,
potência, mapaDeChunks)` devolve `PASSA` ou o motivo. Detalhes que importam:

* O volume usa o tamanho de cada eixo calculado em `int` (`max - min`), igual ao `BlockBox.getSizeX()` do mod, e
  soma 1 (`for (i = 0; i <= tamanho; i++)`). O produto é em `long` com saturação: uma caixa do tamanho do mundo
  (60 milhões x 256 x 60 milhões) cabe sem estouro. Caixa com 0 posições (tamanho negativo por estouro de `int`) passa
  sem conferir altura nem chunks: o laço original também não faz nada.
* O deslocamento é `direção * dist` em `long`: nunca volta para dentro de y 0..255 por estouro.
* Chunks: `((min - 1) >> 4)` a `((max + 1) >> 4)` em x e z, para a origem e para o destino. Para na primeira
  consulta que falha. Mais de 16384 chunks por caixa, ou fora da faixa de `int`, conta como "não carregado" (não
  há como provar que tudo está carregado sem varrer um número absurdo; com o volume padrão a conta dá cerca de 260).
* Intervalo: um mapa `UUID -> hora do último pedido aceito`. Só pedido **aceito** guarda a hora. O mapa não vaza
  memória: ao chegar a 64 jogadores, os registros vencidos são esquecidos (um registro vencido equivale a nenhum);
  se mesmo assim passar de 10000, esquece tudo (no pior caso deixa passar um pedido a mais por jogador).
* Métodos `synchronized`; no servidor só a thread do servidor chama.

**`hoshiestar.chromaticraft.shift.GanchoShift`** (`src/mods/java`, compila contra os jars dos mods) extrai os números
dos tipos do mod e do Minecraft (`BlockBox`, `ForgeDirection`, `WorldServer.theChunkProviderServer.chunkExists`,
UUID do jogador) e delega à decisão. Tudo o que o gancho pede ao mod vivo (potência máxima, som de erro, desligar a
habilidade, mensagem, nome) passa por uma interface (`Mod`); no servidor é sempre a implementação real, e o teste
isolado troca por uma de mentira para ver cada passo da recusa.

**`hoshiestar.chromaticraft.ChromatiCraft`** (`src/main/java`, só JDK + `hoshiestar.*`, roda na fase de coremod):
registro, travas, textos, contadores, linha do `hoshie status`, auto-checagem dos ganchos, o gancho `falhaGancho` e o
subcomando.

**Erros.** Todo o código nosso no caminho do jogo está em `try/catch (Throwable)`: se a decisão lançar exceção, o
pedido **segue pelo original**, conta em `erros` e sai um registro no log com limitador (5 por minuto). Na recusa, cada
passo (som, desligar, mensagem, log) tem o seu `try/catch`: uma falha no som não impede desligar a habilidade; o log
dessas falhas diz que o pedido **continua recusado** (só aquele passo falhou), e o das falhas da decisão diz que o pedido
segue pelo original. A chamada original das pontes da medição fica **fora** de `try/catch` (o que ela lançar sai como
sairia sem nós).

**Gancho que não liga, ou lança fora do nosso `try/catch`** (`NoClassDefFoundError`, `NoSuchMethodError`, erro dentro de
uma ponte): o `try/catch` posto no bytecode chama `ChromatiCraft.falhaGancho`, que conta em `falhasDeGancho`, guarda a
última falha e escreve uma linha no log (limitador de 5 por minuto); o `shiftArea` segue pelo original. A linha de status
mostra `GANCHOS FALHARAM EM EXECUCAO n vez(es)`.

**Auto-checagem ao ligar o servidor** (`hoshie` registrado em `Servidor.iniciando`): confere por reflexão, com as
classes de tempo de execução, que os ganchos ligam com os descritores usados e que as chamadas do mod que eles usam
existem (inclusive o gancho `falhaGancho`). Se algo falhar: `GANCHOS COM DEFEITO: <motivo>` no log e no status, os limites
ficam **desligados**, e a classe `AbilityCalls` **não é mais transformada** se ainda não carregou; se já tinha sido
transformada, o gancho que não liga cai no `try/catch` protegido e cada pedido segue pelo original (custa uma exceção
capturada por pedido, contada em `falhasDeGancho`). Linha de sucesso:
`[Hoshiestar] [chromaticraft] ganchos conferidos com as classes de tempo de execucao: ...`.

**Linha de status** (`hoshie status` e `hoshie chromaticraft`): ativo/desligado, se o gancho foi aplicado, os limites
em vigor, pedidos (aceitos, recusados por motivo), jogadores no mapa do intervalo, a medição (chamadas lentas e o
maior tempo), linhas de recusa no log e erros.

## 5. Por que é seguro

1. **Recusar não muda nada no mundo.** O gancho é a primeira coisa do método (provado no teste com a classe real:
   as únicas instruções antes das originais são os 5 carregamentos de parâmetro, a chamada, o desvio, o retorno
   antecipado e o tratador `falhaGancho` do `try/catch`, que cobre só essas instruções). Ele só **lê**: números da caixa, o mapa de chunks carregados (`chunkExists`, que nunca carrega nem
   gera) e o próprio mapa de horários. Nenhum bloco, item, energia ou fluido é criado ou perdido; `new
   FilledBlockArray` nem chegou a rodar.
2. **O chamador ignora o valor devolvido** (`AbilityHelper.playerMakeBox` faz `pop`) e em seguida tira o primeiro
   clique guardado; recusar não deixa estado preso. A recusa faz o que o ramo "sem energia" do original já faz.
3. **Pedido aceito = original, sem nenhuma mudança no laço.** O teste compara o corpo do método instrução por
   instrução: com `loglento` 0 é idêntico; com a medição ligada, difere em **exatamente 2 instruções** (as duas
   chamadas trocadas por chamadas às pontes, que chamam as originais com os mesmos argumentos, fora do `try`) e a classe
   ganha só essas 2 pontes.
4. **Falha fechada.** Trava errada, dependência diferente ou ausente, gancho ausente, padrão não encontrado ou
   exceção do transformador: a classe fica com os bytes originais e o motivo vai para o log e o `hoshie status`.
   Exceção nossa em tempo de execução: o pedido segue pelo original. **Gancho que não liga em tempo de execução**: o
   pedido segue pelo original (provado no teste com a classe real: o `GanchoShift` carregado sem os métodos não derruba
   `shiftArea`). Interruptor desligado: a correção nem entra no transformador.
5. **A energia da taxa de armar não é devolvida** (decisão 19): mexer nisso exigiria recalcular a taxa (depende do
   foco do Thaumcraft) e poderia criar energia.
6. **Sem thread nova, sem estado fora o mapa de horários e os contadores.** O estado da medição é estático e só a
   thread do servidor o usa.
7. **A distância não recusa nenhum uso normal**: a interface do mod só manda `0..maxPower` (≤ 24) e o foco do
   Thaumcraft limita a `ceil(24*potência/5)` (conferido no desenho; que a potência do foco é ≤ 5 e nunca negativa sem
   edição de NBT está em "não verificado").

**Riscos conhecidos (todos aceitos e documentados):**
* Mudança de jogo visível e intencional: mover áreas acima do volume, fora da altura do mundo, sobre chunks não
  carregados ou com menos de `intervalo` ms desde o último, passam a ser recusadas; vale para criativo e
  operadores. O admin sobe `volume` ou zera `intervalo` se quiser.
* A taxa de armar é perdida na recusa (como já é na falta de energia).
* A recusa por altura é um pouco mais restritiva que o necessário (recusa também quando só haveria ar na parte que
  sai do mundo).
* **Borda horizontal do mundo não coberta** (achado M3 da revisão): `World.setBlock` do Crucible também recusa
  `|x|` ou `|z|` ≥ 30.000.000 em silêncio. Mover 24 blocos para leste a partir de x ≥ 29.999.976 passa nas 5 conferências
  e perde os blocos que saem do mundo, como o original já faz. É bug **que já existe**, raro (só a 30 milhões de blocos do
  centro) e fora do pedido da decisão 19 (que fixa a lista de conferências); não corrigido aqui.
* Não conserta: a conta de custo (preços são regra de jogo), blocos do destino sobrescritos sem drops, perda de dados
  de tile entity com `SHIFTTILES=true`, `data` não validado das outras habilidades em `ChromatiPackets`.

## 6. Testes feitos e como repetir

```bash
# 1. construir o jar com os ganchos de mod (precisa da pasta mods/ de um servidor de teste)
servidor/build.sh --rapido <workspace> --github-raw --sem-testes --mods <mods/ de um servidor de teste>
# 2. todos os testes isolados (os genéricos + os testes com mods, entre eles este)
HOSHIE_LIBS=<libraries/> HOSHIE_MODS=<mods/> servidor/test.sh <workspace>
# so este recurso (o que o test.sh chama): src/test/mods/chromaticraft.bash <workspace> <saida> <classpath> <java>...
#   CC_SEM_MUTACAO=1 pula a mutacao do fonte (a parte mais demorada)
```

O script para (com mensagem) se o jar construído for mais velho que alguma fonte nossa.

| Teste (arquivo) | O que prova |
|---|---|
| `TesteShiftDecisao` (`src/test/mods/cc/java/.../TesteShiftDecisao.java`) | A decisão pura, do jar construído, sem mod nem Minecraft: volume no limite (4096) e limite+1, limite-1, linha de 4096/4097 blocos, caixa do tamanho do mundo sem estouro; distância -1/0/24/25 (e extremos); destino em y -1/0/255/256 nas **seis** direções, e as horizontais com distância 0; intervalo (1ª passa, 2ª recusa, passa 1000 ms depois da aceita; recusa não renova a hora; jogadores independentes; relógio dando a volta); limpeza do mapa de jogadores (20000 jogadores distintos: o mapa nunca passa de 64; 25000 simultâneos: nunca passa de 10000); chunks não carregados na origem, no destino e na borda alargada (x e z, lados mínimo e máximo, coordenadas negativas); teto de chunks; ordem das checagens e contadores; exceção do mapa de chunks; 8 threads x 20000 chamadas. |
| `TesteChromatiCraft` (`.../TesteChromatiCraft.java`, `Execucao.java`) | O transformador sobre a classe **real** `AbilityCalls` (jar do mod só pelo classpath; nossas classes tiradas do `server.jar` construído, com os nomes reofuscados traduzidos como o FML faz): as duas correções são aplicadas; `CheckClassAdapter` sem erros; a JVM carrega e verifica a classe (controles negativos: sem o quadro de pilha do tratador, ou sem o do ponto de desvio, a JVM recusa com `VerifyError`); **o gancho é chamado antes de qualquer outra instrução do método** (prefixo exato: 5 carregamentos, chamada, desvio, retorno antecipado, tratador `falhaGancho`; o `try/catch` cobre só isso); o resto do método é idêntico ao original (laço intacto; só 2 instruções trocadas com a medição; os outros 39 métodos idênticos; mesmos campos; a classe ganha só 2 pontes, cada uma com gancho **dentro** do `try`, `falhaGancho` no tratador e a chamada original **fora**); recusas (desligado, `loglento` 0, outra versão, classe já transformada, cada dependência diferente, dependência ausente, gancho ausente, **defeito achado no boot impede a transformação**); execução com um `Mod` de mentira (recusa devolve `false` sem rodar o original e faz som, desligar e mensagem nessa ordem; pedido aceito deixa o original rodar; exceção nossa cai no original; falhas no som/desligar/mensagem não impedem o resto; log limitado; medição); **gancho que não liga** (o `GanchoShift` carregado sem `recusar`, `antesDoSom` e `antesDeTerminar`: `shiftArea` não sai com `NoSuchMethodError`, o corpo original roda, a falha é contada; as duas pontes chamam a original mesmo assim); **a cola** (ver abaixo); config; auto-checagem dos ganchos; subcomando (inclusive `ligar` antes de a classe carregar e recusa com a trava de versão recusada). |
| a cola do gancho (em `Execucao.cola`) | `GanchoShift.recusar` tira da `BlockBox` e da `ForgeDirection` nove inteiros posicionais. Caixa **assimétrica** (spans 38 x 6 x 36, nenhuma coordenada repetida): volume exato no limite passa e um abaixo recusa; 1x200 em cada eixo (mínimo e máximo); altura onde **só o maxY** estoura e onde **só o minY** estoura (e controles que terminam em 255 e começam em 0); com "tudo carregado" o conjunto **exato** de chunks perguntados ao mapa (origem e destino alargados em 1 bloco, calculado no teste por conta própria) nas seis direções com distâncias diferentes; destino que sai do carregado em x e em z, nos dois sentidos; a potência vem do mod; a medição conta só pedido aceito e demorado. |
| `TesteAsm` (base, `src/test/java`) | O novo formato 1 protegido (`Asm.ganchoNoInicioProtegido`) em amostras compiladas para Java 6, 7 e 8: sem falha igual ao formato 1; gancho que lança, que `valor` lança, que não liga (classe ausente, método ausente) segue pelo corpo original, a cada chamada, e chama o `aoFalhar`; forma do bytecode (um `try/catch` de `Throwable` a mais, só o gancho dentro); método estático, sem desvio (`long`), `void` sem retorno antecipado; controles (o formato 1 comum deixa o erro sair); recusas (sem `aoFalhar`, `aoFalhar` ausente ou com descritor errado, gancho ausente, construtor). |
| mutação do fonte (no `chromaticraft.bash`) | 26 mutações em `DecisaoShift.java`, uma por vez (cada proteção tirada ou trocada por `<=`, `>=`, int no lugar de long...), compiladas à parte: **cada uma tem de fazer o `TesteShiftDecisao` falhar**. |
| mutação do bytecode da cola (no `chromaticraft.bash`, `MutarBytecode`) | 11 mutações no `GanchoShift.recusar` do jar construído: `dz` recebe `offsetX`, `dx` recebe `offsetZ`, `dy` recebe `offsetX`, `maxY`/`minY`/`maxZ`/`minZ`/`maxX`/`minX` trocados pelo outro lado do mesmo eixo, depois da recusa devolve "siga", ignora o interruptor em execução: **cada uma tem de fazer o `TesteChromatiCraft` falhar** (por verificação ou por exceção que derruba o teste). A revisão provou que as três primeiras da lista de cola (G1, G2, G3) escapavam do teste anterior.

**Números do último ciclo completo** (jar reconstruído com `--mods` antes; `chromaticraft.bash`, saída 0, ciclo de 5 min por causa da
M19): `TesteShiftDecisao` **189 ok, 0 falhas** e `TesteChromatiCraft` **209 ok, 0 falhas**, cada um no JDK 8 e no JDK 21; **26 de 26
mutações do fonte acusadas** (a M19, "sem o teto de consultas de chunk", é acusada porque o teste nunca termina: o script aplica um
limite de 120 s por mutação e conta o estouro como acusação) e **11 de 11 mutações do bytecode da cola acusadas** (a C3, `dy` recebe
`offsetX`, é acusada por exceção que derruba o teste, não por verificação). `TesteAsm` da base: **656 ok, 0 falhas** nos dois JDKs
(inclui os casos do formato 1 protegido). Duas mutações do fonte (M15 e M17) escaparam na primeira rodada da implementação e
viraram casos novos no teste (lado máximo em z da borda alargada; produto direção x distância feito em long). A mensagem de erro
`ERROR Hoshiestar ... erro em ...` e `um gancho do mover area FALHOU em execucao` que aparecem no meio da saída do teste são
esperadas: são as exceções e falhas de ligação injetadas de propósito.

## 7. O que NÃO foi verificado

* **O custo real, em jogo, por bloco** das fases de permissão (dois eventos de quebra Forge+Bukkit) e de gravação
  (dois `setBlock`). Só o piso das estruturas de dados foi medido no desenho (0,35 µs/bloco até 32768 posições,
  rodada quente; 111 ms frio para 32³ segundo a conferência). A estimativa da conferência (dezenas de µs por bloco,
  logo possivelmente mais de 1 s a 32768) **não é medição**. Por isso o `volume` padrão é 4096 e provisório.
* **Que o transformador vê `AbilityCalls` no boot real** (a base provou isso com o OpenBlocks; aqui a classe é do
  ChromatiCraft). Se a classe só carregar no primeiro uso, a correção aparece como "ainda não carregada" até lá.
* **Que os ganchos ligam em tempo de execução com os nomes SRG** (a auto-checagem do boot diz; o teste isolado usa o
  mesmo mapa do FML, mas não é o servidor).
* **Valores de config do servidor real**: `ADMINPERMBYPASS` do DragonAPI (padrão `true`: operador pula os eventos de
  quebra, o caminho mais barato), `SHIFTTILES` do ChromatiCraft e `cauldron_settings_loadChunkOnRequest` do Crucible.
* **`chunkExists` com os mods Akashic de chunk** (`Akashic-PlayerChunkAsync`, `Akashic-Crucible-ChunkAPI-Bridge`):
  no Crucible só olha o mapa de carregados; que esses mods não mudam isso não foi analisado.
* **Intervalo de 1000 ms**: chute (um uso normal, com a interface mais dois cliques, leva mais que isso); não medido.
* **Limite de potência do foco do Thaumcraft 4** (≤ 5) e NBT `power` do foco nunca negativo sem edição manual.
* **Jogador em survival com caixa de uma camada** (custo 1, `isUnbreakable`) e **eventos de outros plugins** no
  caminho caro: Jobs, mcMMO e CoreProtect não estão instalados (conferência); o script Skript com `on break` tem
  nome começando com `-` (desativado pela convenção do Skript; não verificado em execução).
* **Boot em Java 8** (a produção usa Java 21; a base também não testou).
* **Revisão independente**: feita (relatório do revisor em `fork/rev/chromaticraft/revisao.md`, fora do repositório): aprovado com
  ressalvas, sem achado bloqueante e sem intercalação de threads que quebre a decisão. As correções desta rodada (seção 11) **não
  foram revistas de novo** por outro agente; a parte de concorrência não mudou (só ganhou o `retrato()` sob a mesma trava e os
  contadores `falhasDeGancho`/`ultimaFalhaDeGancho`, escritos sob `synchronized(ChromatiCraft.class)`).
* **Formato 1 protegido no servidor real**: testado só nos testes isolados (amostras Java 6/7/8 e a `AbilityCalls` real, com o
  verificador da JVM). Que a ligação do `ChromatiCraft.falhaGancho` (classe nossa) a partir da classe do mod funciona no
  `LaunchClassLoader` real é a mesma suposição de todos os ganchos da base; a auto-checagem do boot confere a existência do método.
* O item "o que aconteceu no D-21" (jogador, local e tamanho da caixa) continua desconhecido: o dump não diz. A linha
  de log da recusa e a da medição passam a registrar isso daqui para a frente.

## 8. Plano de teste no modpack real (para o agente que liga o servidor)

Regras: só na cópia de teste criada por `scripts/prepare-test-env.sh`; parar com `echo stop >> console.in` (`kill`
só nos casos da regra do projeto, com dois thread dumps antes); nada de spark (use JFR); nada de jar nosso em
`mods/` ou `plugins/`; **nunca** imprimir valores de config do servidor.

**Preparação**

1. Construir com ganchos de mods: `servidor/build.sh --rapido <workspace> --github-raw --sem-testes --mods <mods/ da cópia>`
   e conferir `unzip -l <jar> | grep GanchoShift` (5 classes).
2. Cópia nova de teste só com esse `server.jar`. **Boot 1 com tudo no padrão** (sem `hoshiestar.yml`): conferir
   `chromaticraft=desligado` na linha `recursos:`, as duas correções `desligada` no fim do boot, o `hoshiestar.yml`
   criado com a seção `chromaticraft` (9 chaves), e `hoshie hash Reika.ChromatiCraft.Auxiliary.Ability.AbilityCalls`
   mostrando `8bcbe85e...be7a5` (o mesmo da trava). `hoshie chromaticraft` mostra `desligado`; `hoshie chromaticraft
   ligar` recusa e explica.
3. (Achado M1 da revisão: o subcomando agora existe mesmo com o recurso desligado, então o passo 2 vale como escrito.)
   Parar. Copiar a pasta do mundo para guardar o estado inicial (cada caso começa de um mundo igual). No
   `hoshiestar.yml`: `chromaticraft.ligado: true` (o resto no padrão). Para a calibração (passo 5), `volume` maior.
   Para medir com JFR, o `start` leva `-XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints`.

**Passo 1, boot com o recurso ligado**

* Esperado no log: duas linhas `[Hoshiestar] [embutidos] chromaticraft: ... aplicada` (uma por correção), ou, se a
  classe só carrega no primeiro uso, "ainda não carregada" até o primeiro mover área (então repetir a conferência
  depois do primeiro pedido); as linhas do fim do boot; a linha `ganchos conferidos com as classes de tempo de
  execucao`. Se vier `GANCHOS COM DEFEITO: <motivo>` ou `NAO aplicada`, **parar**, guardar o motivo, o hash visto e o
  `hoshie status`, e não seguir.
* Sem `VerifyError`, `NoSuchMethodError`, `IllegalAccessError` ou `NoClassDefFoundError` com `hoshiestar` ou
  `Reika.ChromatiCraft`. Comparar o número de erros de classe e de mixin com o boot do `server.jar` original na mesma
  máquina.
* `hoshie status`: linha `chromaticraft` com `ativo; gancho aplicado`.

**Passo 1b, o gancho que não liga não derruba** (só se a auto-checagem acusar `GANCHOS COM DEFEITO`; senão pular)

Se o boot trouxer `GANCHOS COM DEFEITO`: o primeiro mover área **não pode derrubar** a cópia. A classe `AbilityCalls` não é mais
transformada se ainda não tinha carregado (conferir `hoshie chromaticraft`: `gancho NAO aplicado` com o motivo da auto-checagem);
se já tinha carregado, cada pedido segue pelo original e `hoshie chromaticraft` mostra `GANCHOS FALHARAM EM EXECUCAO n vez(es)`.
Guardar o motivo e **parar o teste do recurso** (o defeito é do projeto).

**Passo 2, preparar o bot (sem op)**

Conferência (item G): o operador com `ADMINPERMBYPASS` pula os eventos de quebra e o criativo pula a checagem de
energia; calibrar assim mede o caminho mais barato. Então: bot entra; no console `op <bot>`; no chat do bot
`/chromaprog maximize abilities` (precisa de criativo; dá a habilidade SHIFT); depois no console `deop <bot>` e
`gamemode 1 <bot>`. O bot fica **em criativo e sem op**. Repetir 1 caso em survival com laje plana (custo 1), com **livro-caixa de energia** (achado
M6 da revisão): ler a energia do jogador (medidor do ChromatiCraft ou Skript) (E0) antes de armar, (E1) depois de armar a
habilidade e antes do segundo clique, e (E2) depois do pedido. Caso aceito: o desconto E1 - E2 tem de ser igual ao do ANTES
(mesmo mundo copiado, `ligado: false`). Caso recusado: E2 = E1 (a recusa não cobra nada além da taxa de armar, que não é
devolvida). Caso "sem energia" (survival com a energia esvaziada, caixa 16x16x16, `loglento` 1): o original cai no ramo de
falha, o log traz `mover area LENTO ... resultado falhou (sem energia ou sem permissao)` e a habilidade fica desligada no jogador
(confere a ponte do som e a da chamada final).

**Passo 3, como acionar o "mover área"** (o bot não manda o pacote `ChromaData`; este passo é do agente do teste)

O recurso só atua dentro de `shiftArea`; as camadas de cima (pacote, evento de clique) não são tocadas. Chamar as
mesmas entradas do servidor: `Chromabilities.triggerAbility(jogadorNMS, Chromabilities.SHIFT, dist)` e depois
`AbilityHelper.instance.addPlayerClick(jogadorNMS, mundo, x1, y1, z1)` e o mesmo com `x2, y2, z2` (o segundo clique
chama `shiftArea`), medindo o tempo da segunda chamada com `System.nanoTime()`. Direção = olhar do jogador: para
CIMA use `tp <bot> x y z 0 -90` (que isso dá `UP` é suposição, não verificada; a linha de log da recusa mostra a
direção usada, confira).
* **Passo 0 do acionador (rápido, antes de tudo, na cópia de teste)**: conferir que o `skript-reflect` (já presente em
  `plugins/`) alcança as classes de mods. Script de uma linha só da cópia (nome sem `-`; a sintaxe do `skript-reflect` abaixo é
  de memória, **não verificada**; conferir na documentação do plugin): `import: Reika.ChromatiCraft.Registry.Chromabilities`
  e, num comando de teste, enviar ao console o valor de `Chromabilities.SHIFT.getMaxPower()` (esperado 24). Por leitura do
  código, o `PluginClassLoader` do Crucible é carregado pelo `LaunchClassLoader` (que enxerga as classes dos mods), então é
  provável que funcione, mas **isso não foi executado**.
* **Opção A (sem código novo)**: script Skript **só da cópia de teste** com o `skript-reflect`, comando
  `/shifttest x1 y1 z1 x2 y2 z2 dist`, que chama as entradas do servidor descritas acima.
* **Opção B (decisão do coordenador, NÃO escrita)** (achado I3 da revisão): se o passo 0 falhar, um subcomando de depuração
  `hoshie chromaticraft testar ...`, **desligado por padrão** (chave própria, por exemplo `chromaticraft.comandoteste: false`),
  que chama as mesmas entradas. Não foi escrito porque cria uma entrada nova capaz de mover blocos (decisão de produto que o
  pedido não cobre). Sem A nem B, ficam sem prova em servidor os casos (a) a (m) e a calibração; os passos 1, 1b e 6 valem.
  **Quem liga o servidor deve receber do coordenador a escolha entre A e B antes de começar.**

**Passo 4, casos** (cada um num mundo copiado igual, uma vez com `ligado: false` = ANTES e uma com `true` = DEPOIS;
pré-carregar as áreas antes com `tp <bot> 200 100 200` e esperar 10 s; esperar mais de 1 s entre pedidos)

"Soma de conferência" = contagem de blocos por tipo na união da origem e do destino antes e depois (WorldEdit
`//distr` é uma opção, **não verificado** que dá para usar do console; ou o próprio Skript). Para "intocado" a
soma tem de ser igual à de antes da chamada.

| # | Caixa (terreno natural perto de 200,200) | dist | ANTES | DEPOIS |
|---|---|---|---|---|
| a | 16x16x16 = 4096 (no limite) | 24 | movida | movida, **mesma soma do ANTES** |
| b | 17x1x241 = 4097 (limite+1) | 24 | movida | recusada (volume), mensagem, soma intocada, habilidade desligada |
| c | laje 100x1x100 = 10000 | 24 | movida (de graça) | recusada (volume) |
| d | laje 200x1x200 = 40000 | 24 | movida, congelada de verdade (anotar o tempo; só na cópia; regra dos 15 min) | recusada em < 1 ms |
| e | caixa de (a) | -5 | movida 5 para baixo | recusada (distância) |
| f | caixa de (a) | 1000 | origem apagada, destino acima de y=255: **os blocos somem** (previsto pelo código) | recusada (distância), soma intocada |
| g | origem y 236..240 para cima | 24 | **os blocos somem** | recusada (altura), soma intocada |
| h | origem y 0..23 para baixo | 24 | os blocos somem | recusada (altura) |
| i | 16x16x16 numa área **não carregada** (ou destino que atravessa a borda do carregado) | 24 | movida (gera chunks na hora) | recusada (chunks) e **o número de chunks carregados não sobe** (medidor ou Skript) |
| j | dois pedidos com menos de 1 s entre eles | 24 | os dois movidos | o segundo recusado (intervalo); depois de 1 s passa |
| k | 1000 recusas seguidas do mesmo jogador | | | log: no máximo uma linha por jogador a cada 10 s, a seguinte com "N suprimidas"; `hoshie chromaticraft` conta todas |
| l | `hoshie chromaticraft desligar` e repetir (c) (só 10000, nunca a de 40000) | 24 | | movida como o original; `ligar` volta a recusar |
| m | (a) com `loglento` baixo (ex. 1) | 24 | | linha `mover area LENTO: N ms ...` com quem, onde, tamanho, resultado (`bloco(s) movido(s)`) |
| n | survival sem energia, caixa 16x16x16, `loglento` 1 | 24 | habilidade desligada, nada movido | igual ao ANTES; a linha LENTO diz `resultado falhou (sem energia ou sem permissao)` (ver livro-caixa no passo 2) |

Depois de cada execução: sem exceção nova no log, TPS de volta a 20 (`forge tps` 3 vezes), nenhum item solto ao redor
das caixas, `erros 0` em `hoshie chromaticraft`.

**Passo 5, calibrar o `volume`** (único número que ainda é chute)

Com `volume: 0` no `hoshiestar.yml` e `intervalo` 0, medir (ligado, no bot sem op; repetir 5 vezes cada, em área nova
já carregada; anotar a **primeira** chamada depois do boot, JIT frio, e a mediana das outras) caixas sólidas de 8³,
16³, 24³, 32³, 48³ e lajes 100x1x100, 200x1x200, 400x1x400. Opcional: JFR (`jcmd <pid> JFR.start ... filename=...`)
para separar leituras de mundo, eventos e gravações. Critério proposto pela conferência: `volume` = o maior volume
com mediana ≤ 100 ms **fria e sem op** (o desenho original usava 500 ms; escolher é decisão de produto do admin).
Depois voltar `volume` para o valor escolhido e repetir os casos (a) e (b) com ele.

**Passo 6, mesmo boot, sem jogador mexendo**

5 minutos parado com `forge tps`: TPS igual ao do boot do `server.jar` original (o recurso só atua dentro de
`shiftArea`; sem uso, custo zero). Parar com `stop`.

**Para o relatório**: tabela dos casos (ANTES e DEPOIS), tempos medidos, `hoshie status` no fim, linhas de log
`mover area RECUSADO`/`LENTO`, e o `volume` sugerido com os números que o sustentam.

## 9. Desvios do desenho (e por quê)

* **Mixin → transformador do fork** (decisão do coordenador): sem `@LateMixin`, sem `require=1`, sem
  `@Unique private static` (o problema A da conferência não existe aqui; o gancho é classe nossa normal).
* **Versão do conferente** (decisão 19): nenhuma injeção dentro do laço; sem `scanBudgetMs`; com intervalo mínimo por
  jogador, altura do destino e chunks carregados.
* **Gancho no retorno → duas chamadas envolvidas por pontes protegidas** (formato 3 protegido): a base não tem "gancho no
  retorno". `setToPlayer` e `playSound` aparecem uma vez cada em `shiftArea`, e `setToPlayer` é a última chamada antes de
  `ireturn` (provado no teste). (Na primeira versão eram duas trocas de chamada do formato 2, sem proteção; a revisão achou
  que um gancho que não liga derrubaria o servidor, e o formato 3 protegido já existia na base.)
* **Decisão no formato 1 PROTEGIDO**, que é novo na base: `Asm.ganchoNoInicioProtegido` (revisão I1). Mudança de
  infraestrutura da base em `src/main/java/hoshiestar/embutidos/asm/Asm.java` com os testes em `TesteAsm` e `GanchosTeste`;
  o formato 1 comum não mudou (os outros recursos usam o mesmo caminho de código, `noInicio`, sem tratador).
* **Mensagem única com `{motivo}`** em vez de um texto fixo por motivo (a decisão pede "mensagem configurável"). O
  texto do motivo é fixo por motivo.
* **Chave do intervalo = UUID do jogador** (o relatório propunha o nome): o nome pode mudar e não é único entre
  contas premium e offline.
* **Limpeza do mapa de jogadores** (a decisão pede o teste): o relatório dizia "não precisa limpar" porque guarda um
  horário por jogador; aqui há limpeza de registros vencidos e um teto absoluto de 10000 para o mapa nunca crescer sem
  fim com UUIDs falsos.
* **Sem linha única "1.B ativo"**: o equivalente é a linha de aplicação do boot, a da auto-checagem e o `hoshie status`.
* **Dois campos públicos para testes** (`DecisaoShift` aceita um relógio, `GanchoShift.mod` é trocável), documentados
  no código.
* **Defeito dos ganchos achado no boot impede a transformação** (`ChromatiCraft.ganchoComDefeito` é consultado quando a classe
  passa pelo transformador).
* **Dependências da trava**: além de `AbilityCalls`, `BlockBox`, `Chromabilities` (pedidas), também `BlockArray`,
  `FilledBlockArray` (de cuja conta de `offset` o destino depende) e `AbilityHelper` (único chamador).
* Mudanças de infraestrutura da base: a lista de prefixos do `CarregadorMods` dos testes (`hoshiestar.chromaticraft.shift.`,
  `hoshiestar.teste.chromaticraft.mundo.`) e, na rodada de correção, o formato 1 protegido (`Asm`, `TesteAsm`,
  `GanchosTeste`) e a ferramenta de teste `MutarBytecode` (`src/test/mods/java/hoshiestar/teste/mods/`). Nenhum script
  (`build.sh`, `test.sh`) nem trecho do Gradle foi alterado por este recurso; o `chromaticraft.bash` ganhou a mutação do
  bytecode da cola.

## 10. Para quem for mexer

* Mudou o jar do ChromatiCraft ou do DragonAPI: o recurso se desliga sozinho (trava). Recalcular os hashes com
  `hoshie hash <classe>` ou `unzip -p mod.jar caminho.class | sha256sum`, reler `shiftArea` com `javap -c -p`
  (os testes `TesteChromatiCraft` acusam a diferença) e repetir o plano da seção 8.
* A decisão é a única lógica; qualquer mudança nela passa pela mutação do fonte (26 casos). A **cola** do gancho (os nove inteiros
  que `GanchoShift.recusar` passa à decisão) passa pela mutação do bytecode (11 casos): ao mexer no gancho, conferir os dois.

## 11. Achados da revisão independente e o que foi feito

Relatório do revisor: `fork/rev/chromaticraft/revisao.md` (fora do repositório). Veredito dele: aprovado com ressalvas. Rodada de
correção (um commit por tema):

| Achado | O que foi feito |
|---|---|
| **I1** (importante): gancho que não liga derruba o servidor; a auto-checagem só baixava `ativo` e a classe ainda era transformada | **Corrigido.** Novo formato na base, `Asm.ganchoNoInicioProtegido` (`try/catch` de `Throwable` posto no bytecode, `falhaGancho` no tratador, corpo original fora do `try`), usado na decisão; a medição, que tinha o mesmo defeito (troca de chamada sem proteção, **depois** de o bloco já ter sido movido), passou ao formato 3 protegido. Além disso, defeito achado na auto-checagem impede a transformação. Provas: `TesteAsm` (amostras Java 6/7/8, gancho que lança/não liga, controles sem proteção que deixam o erro sair) e `TesteChromatiCraft` (a `AbilityCalls` real com o `GanchoShift` sem os métodos: `shiftArea` roda o original). O texto do doc e do log agora diz a verdade. |
| **I2** (importante): a cola do gancho (9 inteiros) sem teste que a discrimine | **Corrigido.** Casos do revisor e outros por eixo e por lado na `Execucao.cola` (caixa assimétrica, conjunto exato de chunks nas 6 direções, altura só por maxY e só por minY, volume por eixo) e mutação do bytecode da cola no `chromaticraft.bash` (11 mutações, todas acusadas; G1, G2 e G3 do revisor entre elas). |
| **I3** (importante): o plano de servidor depende de acionador não verificado e não tem alternativa pronta | **Parcial.** O plano ganhou o passo 0 (teste de um `import` com o `skript-reflect`, com o argumento de leitura do código a favor) e a opção B descrita. A opção B (subcomando de teste desligado por padrão) **não foi escrita**: é decisão de produto (entrada nova que move blocos). Pendente do coordenador antes do teste em servidor. |
| **M1**: subcomando não registrado com o recurso desligado | **Corrigido**: registrado sempre que o ChromatiCraft existe; teste isolado do ramo `desligado`. |
| **M2**: texto de log enganoso nas falhas da recusa | **Corrigido**: `erroNaRecusa` ("o pedido continua RECUSADO; só este passo da recusa falhou"). |
| **M3**: borda horizontal (`|x|`, `|z|` ≥ 30.000.000) não coberta | **Não corrigido**: bug que já existe no original, raro, fora da lista de conferências da decisão 19. Documentado na seção 5. |
| **M4**: comentário "lidos sem trava" e retrato inconsistente dos contadores | **Corrigido**: comentário certo e `DecisaoShift.retrato()` (leitura única sob a mesma trava; a linha de status usa). |
| **M5**: `ligar` recusado até a classe carregar | **Corrigido**: `ligar` vale quando a correção está aplicada ou aguardando a classe; recusa só com trava/padrão recusados (testado). |
| **M6**: plano sem livro-caixa de energia nem caso "sem energia" | **Corrigido no plano**: passo 2 (livro-caixa) e caso (n). |
