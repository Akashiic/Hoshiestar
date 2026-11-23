# Recurso `mekanism` (item 1.C): energia da matriz de indução do Mekanism

Correção embutida no `server.jar` do Hoshiestar (nada vai em `mods/` nem em `plugins/`). Desligada por
padrão. Relatórios de origem: `docs/pesquisa/etapa1/C-mekanism.md` (desenho) e
`C-mekanism-conferencia.md` (conferência; onde divergem, vale a conferência). Este texto já inclui as
correções da revisão independente (seção 11).

Termos: **tile entity (TE)** = o objeto Java que guarda os dados de um bloco especial (aqui, cada célula
guarda a própria energia). **Chunk** = pedaço 16×16 do mundo; cada chunk tem um mapa
"posição → tile entity". **Gancho** = método nosso que a classe do mod passa a chamar depois de
transformada. **Trava de versão** = SHA-256 (impressão digital) dos bytes originais da classe; a correção
só entra se bater. **Mutação** = estragar de propósito uma linha do código para ver se algum teste percebe.

---

## 1. O que faz, em linguagem simples

A matriz de indução não guarda um total de energia: a energia mora em cada célula. Toda vez que alguém
pergunta "quanta energia tem?" (portas, a rede IC2 do Uncomplication, o entangloporter, o slot de
carga), o Mekanism percorre **todas** as células e, para cada uma, transforma a coordenada no objeto da
célula com buscas em tabelas do mundo e do chunk. Isso se repete umas 30 vezes por tick e custou
**8,27 % do tick** no perfil de produção (`SynchronizedMatrixData.getEnergy` 7,02 % +
`setEnergy` 1,24 %).

O Hoshiestar guarda só **"posição i do conjunto de células → objeto da célula"** (nunca a energia). A
cada chamada confere, barato, que cada objeto guardado ainda é o que o mundo devolveria e então **lê e
escreve a energia ao vivo nas próprias células**, na mesma ordem e com a mesma conta do original. O
resultado é o mesmo número, bit a bit. Na dúvida, roda o código original do Mekanism.

## 2. Chaves no `hoshiestar.yml`

```yaml
mekanism:
  modo: desligado      # desligado | verificar | ligado
  intervalo: 1         # 1 a 20
```

| Chave | Padrão | O que faz |
|---|---|---|
| `mekanism.modo` | `desligado` | `desligado`: o Mekanism fica exatamente como no jar (a correção nem entra no transformador; a classe não é alterada). `verificar`: a classe é alterada, mas **sempre** roda o caminho original; a cada leitura o Hoshiestar calcula o que o caminho rápido daria e **conta** quando o objeto guardado não é o que o mundo devolve (identidade) ou a soma difere (bit a bit), e também confere, a cada escrita, se a escrita rápida seria aceita com algum objeto errado. Nunca lê nem escreve pelo caminho rápido. Custa **mais** que o original (é para teste). `ligado`: usa o caminho rápido quando todas as conferências passam. Valor desconhecido (ex.: `ligad`) vira `desligado` com aviso no log. |
| `mekanism.intervalo` | `1` | De quantos em quantos ticks a lista de células é refeita do zero. 1 = no primeiro uso de cada tick (recomendado). Fora de 1..20: usa o limite mais próximo, com aviso. |

O arquivo é lido uma vez, no boot. Mudou? Reinicie.

Atenção: no modo `verificar` a linha `recursos:` do boot mostra `mekanism=ligado` (a base só conhece
ligado/desligado: "ligado" quer dizer "a classe é alterada"). O modo de verdade aparece no
`hoshie status` (`modo verificar`).

**Erros.** Exceção do **nosso** código: o recurso desliga até reiniciar (todos os ganchos passam a rodar o
original na primeira linha), o estado daquela matriz é apagado e sai uma linha `ERROR` no log (com
limitador); o `hoshie status` mostra `DESLIGADO POR ERRO: ...`. Exceção de **outro mod** lançada de
dentro da busca original de uma coordenada (a busca pode criar a tile entity e avisar os vizinhos, que
rodam código de outros mods): é relançada exatamente como o Mekanism original faria naquele ponto, sem
desligar o recurso e sem rodar a busca de novo (contador "exceções de outros mods relançadas").

## 3. O que muda na classe do Mekanism

Só com `modo` diferente de `desligado` e as travas conferindo. Classe alvo:
`mekanism.common.content.matrix.SynchronizedMatrixData` (jar
`Mekanism-1.7.10-9.10.48-AkashicPerf.3-ALL.jar`).

- No início de `getEnergy(World)D`: `if (GanchoMatriz.antesDeLer(this, w)) return GanchoMatriz.valorLido(this, w);`
- No início de `setEnergy(World, double)V`: `if (GanchoMatriz.antesDeEscrever(this, w, e)) return;`
- Um campo público sintético `Object hoshiestar$matriz` (o estado daquela estrutura; nada é salvo em disco).
- O resto dos dois métodos fica intacto (o teste confere instrução por instrução).

Formato 1 da base (`Asm.ganchoNoInicio`), uma só correção (tudo ou nada). Travas (SHA-256 dos bytes
originais; qualquer diferença = correção não aplicada e o motivo no log e no `hoshie status`):

| Classe | SHA-256 aceito | Por que a lógica depende dela |
|---|---|---|
| `mekanism.common.content.matrix.SynchronizedMatrixData` (alvo) | `34fa7fec1a2471a25d8af849954852e3f43174e0c3aaf6d29c66c1f2394b44f8` | corpo exato de getEnergy/setEnergy (o caminho rápido refaz a mesma conta) e o campo `cells` |
| `mekanism.common.multiblock.UpdateProtocol` | `3c74587e2f2b731499f9ff0afebd4708ae7c47a7d3c5bee88a20e829e80422ba` | cria um `Coord4D` novo para cada coordenada interna (Coord4D é mutável) |
| `mekanism.common.tile.TileEntityInductionCell` | `79e03b7ef413d5b44480846b347a857b3a744b8012a9640822b829cd02299f0b` | getEnergy/setEnergy/getMaxEnergy só leem/gravam um campo; `canUpdate()` = false |
| `mekanism.common.tile.TileEntityBasicBlock` | `71c14cfb08bf6f6f00650f04874cc8f47e7a067c3824839701127e5f72e3a6a8` | `invalidate()` chama o do Minecraft primeiro (`isInvalid()` vira true) |
| `mekanism.api.Coord4D` | `6458d05a097ae29b08772a425606b514b36ac0bb6aaa281d7f14aacccf765801` | `getTileEntity` = `exists()` (chunkExists) + `World.getTileEntity` |
| `mekanism.common.content.matrix.MatrixUpdateProtocol` | `081d53034a5063a190f0ba0f834794a1db90030368eb115cdfcab69d9177a8ed` | único lugar que acrescenta em `cells`, e só numa estrutura nova |

As travas das dependências são conferidas quando a classe alvo passa pelo transformador, lendo o arquivo
`.class` (sem carregar). Varredura de bytecode de todos os jars de `mods/` e `plugins/` (implementação e
revisão): ninguém escreve em `cells` fora do construtor; só `MatrixUpdateProtocol.canForm` faz `add`;
`Coord4D.translate` muda o próprio objeto, mas ninguém o chama nas coordenadas de `cells`.

Arquivos (no workspace):
- `src/main/java/hoshiestar/mekanism/Mekanism.java`: registro, travas, transformação, modo, contadores,
  desligamento por erro, linha de estado (roda na fase de coremod: nada do Minecraft).
- `src/mods/java/hoshiestar/mekanism/matriz/GanchoMatriz.java`: os ganchos (só delegam; tratam as
  exceções sem depender da classe da lógica).
- `src/mods/java/hoshiestar/mekanism/matriz/MatrizInducao.java`: a lógica.
- `src/mods/java/hoshiestar/mekanism/matriz/DeTerceiros.java`: embrulho de exceção de outro mod.
- `src/mods/java/hoshiestar/mekanism/matriz/ComandoMatriz.java`: subcomando de diagnóstico.
- Uma linha em `Catalogo`, um bloco em `HoshiestarConfig`, uma chamada em `Servidor.iniciando`.

## 4. Como funciona

Por estrutura (objeto `SynchronizedMatrixData`) fica guardado, no campo acrescentado:
a lista `refs[i]` (objeto da célula da posição i do conjunto `cells`, na ordem de iteração), as
coordenadas de cada uma, os chunks onde estão e **quantas tile entities cada um desses chunks tinha** no
fim da montagem.

**Leitura rápida** (`getEnergy`), só se TUDO vale:
1. thread do servidor (`ThreadServidor`), mundo não nulo e não cliente, relógio > 0;
2. a lista foi montada neste ciclo de `intervalo` ticks (relógio = `hoshiestar.api.Relogio`, o contador
   do servidor, não o tempo do mundo);
3. mesmo mundo, mesmo objeto `cells`, mesmo tamanho;
4. para cada chunk, nesta ordem e parando no primeiro que falha: `isChunkLoaded` → `chunkExists` no
   provedor → só então `getChunkFromChunkCoords` e o mesmo objeto → o mesmo número de tile entities no
   mapa do chunk;
5. para cada célula: não invalidada, mesma coordenada, mesmo mundo.

Então soma `getEnergy()` de cada célula, partindo de 0.0, na ordem guardada: o mesmo double do original.

**Reconstrução** (quando algo acima não vale): refaz a lista com **as mesmas chamadas do original**
(`Coord4D.getTileEntity` na ordem do conjunto) e devolve a mesma soma (custa uma leitura original).
Monta tudo em variáveis locais e só publica no fim. Uma chamada que chegue no meio (a busca original pode
criar uma tile entity e avisar os vizinhos, que leem/escrevem a matriz) roda o original (flag de
reentrada). O chunk é pedido só depois de a célula ter sido achada, uma vez por chunk, e só depois de
`chunkExists`: nunca carrega nem gera chunk. Estrutura incompleta (posição sem célula, chunk
descarregado, altura fora de 0..255): nada fica guardado, o original roda, e nova tentativa só depois de
20 ticks. Quando a conferência falha, a lista é apagada (não prende mundo, chunk nem célula).

**Escrita rápida** (`setEnergy`, só no modo `ligado`): exige a lista válida (itens 1-4 acima) e, ANTES
de escrever qualquer coisa, para **todas** as células: itens do passo 5 e a **identidade** — o objeto
guardado tem de ser o que está agora no mapa de tile entities do chunk naquela posição (é o que a busca
original devolveria; veja o risco R1 na seção 5). Passou tudo: roda o corpo original (zera, enche,
desconta) nas mesmas células e na mesma ordem. Recusou: apaga a lista e roda o original.

**Modo verificar**: na leitura com lista válida, calcula a soma rápida, refaz a busca original e compara
a identidade de cada célula guardada com o que o mundo devolve (`World.getTileEntity`, pela mesma
chamada do original) e a soma, bit a bit; na escrita com lista válida, se a escrita rápida seria aceita,
compara a identidade de cada célula com o mundo. Conta, registra no log (com limitador) e apaga a lista
quando diverge. Em todos os casos o corpo original roda.

**`hoshie status`** (linha do recurso): modo; leituras rápidas, reconstruções (incompletas), leituras no
original; escritas rápidas, no original (recusadas por identidade); reentradas; **outra thread**
(chamadas de outra thread que não a do servidor, ex.: OpenComputers; rodam o original e não tocam em
nada nosso); **sem mundo/antes do 1º tick** (chamadas na thread do servidor antes do primeiro tick ou com
mundo nulo/de cliente; original); exceções de outros mods relançadas; no verificar: leituras comparadas,
divergências de identidade e de soma, escritas comparadas e divergências; erros. Os contadores "outra
thread" e "erros" são os únicos que outra thread pode mudar, e só dentro de métodos `synchronized`
(nenhuma contagem se perde).

**`hoshie mekanism <x> <y> <z> [dimensão] [mais <x> <y> <z> ...]`** (console ou operador nível 4; só
leitura; nunca carrega chunk; só na thread do servidor; registrado só se o Mekanism está no servidor):
apontando para uma moldura ou porta da matriz, mostra a **soma direta** da energia das células (busca
original de cada coordenada, sem passar pelo Hoshiestar), a leitura normal (`getEnergy`, que passa pelo
gancho quando a correção está aplicada), se as duas são iguais bit a bit, quantas coordenadas têm célula,
a capacidade e o estado guardado. Com a correção **não** aplicada (modo `desligado`, trava que não
conferiu) a última linha diz `correcao nao aplicada nesta classe` e a classe da lógica nem é carregada.
Depois de `mais`: blocos de energia do Mekanism de fora da matriz (cubos de energia), lidos **na mesma
chamada** (entre dois ticks), e a linha `livro-caixa (soma direta + extras)`: energia que passa da matriz
para um cubo e volta não muda esse total. Uma célula da própria matriz depois de `mais` só tem a energia
mostrada (para anotar antes de quebrá-la), sem somar de novo.

## 5. Por que é seguro

A energia nunca é guardada: só a resolução "posição → objeto". Então não há total velho para duplicar
energia; o único jeito de errar é usar um objeto que não é mais o que o mundo devolveria.

- **Mesmos objetos, mesma ordem ⇒ mesmo resultado.** O conjunto `cells` é o mesmo objeto, do mesmo
  tamanho (nunca muda depois de formado; ver travas), então a ordem de iteração é a mesma. A soma parte
  de 0.0 e usa `+=` na mesma ordem: mesmo double. A escrita roda o mesmo corpo nos mesmos objetos.
- **O objeto guardado ainda é o do mundo**: todo caminho normal que tira uma TE do chunk a invalida
  (`Chunk.func_150812_a` invalida a anterior antes do `put`; `removeTileEntity` invalida) ou descarrega o
  chunk (`isChunkLoaded=false`). Conferimos invalidação, coordenada, mundo, chunk (carregado, existe, mesmo
  objeto). Além do desenho: o **número de TEs no mapa de cada chunk** igual ao da montagem pega quem tira
  uma TE do mapa sem invalidar (o caso R1 do desenho e o "ConfAdv" do conferente), a não ser que ponha
  outra no mesmo chunk no mesmo tick.
- **Escrita**: além disso, a **identidade** de cada célula no mapa do chunk é conferida antes de escrever,
  em **todas** as posições (teste R1 da revisão) — nenhuma energia vai para uma célula órfã.
- **Valores ao vivo**: várias leituras no mesmo tick (portas, IC2, entangloporter, GUI) veem exatamente o
  que o original veria; escrita direta no campo público `electricityStored` aparece na leitura seguinte.
- **Nada salvo**: o estado vive num campo do objeto em memória; o NBT de cada célula e o da matriz
  continuam iguais. Formar/quebrar a matriz cria outro objeto de estrutura.
- **Threads**: só a thread do servidor usa ou mexe no estado; outra thread (ou o cliente) roda o original
  sem tocar em nada (só conta, sincronizado). O campo estático `ultimoValor` (o valor que o gancho de valor
  entrega logo depois do gancho de início) só é escrito pela thread do servidor; reentrada cruzada (ler
  outra matriz no meio da reconstrução desta) testada (R2).
- **Exceções**: as de outros mods saem como sairiam do original (mesma exceção, mesmo ponto, busca não
  repetida); as nossas desligam o recurso e caem no original.
- **Nunca carrega chunk**: ordem obrigatória com curto-circuito (provado no teste: 0 chamadas que
  carregariam chunk, inclusive com chunk sumido do provedor sem `onChunkUnload`; a mutação que inverte a
  ordem é acusada).
- **Falha fechada**: trava errada, dependência diferente, gancho ausente ⇒ classe original.

### Riscos que sobram (documentados, não escondidos)

- **R1 residual (leitura)**: um mod que **troca** a TE de uma célula no mapa do chunk sem invalidar a
  antiga (ou tira uma e põe outra no mesmo chunk), e alguém lê a matriz **no mesmo tick**: a leitura
  rápida conta a célula velha. No tick seguinte a lista é refeita. A escrita rápida recusa (identidade),
  mas a leitura errada pode virar uma escrita (pelo original) com o valor errado: duplica ou some a
  diferença de energia entre a célula velha e a nova. Quem faz isso no pacote: nenhum caminho normal de
  jogo encontrado (conferência 3.2: AE2 Spatial IO muda mundo e coordenada, o que é pego; MrTJPCore não é
  chamado por nenhum jar; sondas de diagnóstico; ForgeMultipart só na carga do chunk). Plugins (WorldEdit
  6.1.9, PlotMe...) não foram lidos. O teste mostra o efeito (diferencial "INFORMATIVO") e que o modo
  `verificar` o acusa. Não dá para fechar sem perder o ganho (seria refazer a busca original).
- **R1 também na escrita, em estados do Crucible que o teste isolado não tem** (achado M4 da revisão): a
  identidade da escrita é comparada com o **mapa do chunk**, não com `World.getTileEntity`. No Crucible as
  duas só diferem (a) durante o laço de tiles, se houver na `addedTileEntityList` uma TE válida naquela
  posição que não está no mapa — só acontece se um mod chamar `World.addTileEntity` direto, sem
  `setTileEntity` (este põe no mapa e invalida a célula, o que é pego); células não entram nessa lista
  (`canUpdate()` = false); e (b) durante a captura de árvore do Crucible (`worldChangeHolder`), que só
  afeta posições que a árvore tocou (células ficam dentro da moldura). Nesses casos a escrita rápida
  aceitaria uma célula que a busca original não devolveria. O modo `verificar` compara com
  `World.getTileEntity` (a mesma chamada do original), então cobre isso no servidor: **o `verificar` é
  obrigatório antes do `ligado`** (plano, seção 8).
- **Coord4D mutável**: `translate()` muda o próprio objeto; nenhum código o chama nas coordenadas de
  `cells` (varredura). Dentro do `intervalo` uma mudança dessas não seria vista.
- **Memória**: até 5 arrays do tamanho da matriz e poucos objetos `Chunk` por estrutura viva, refeitos a
  cada tick; morrem com a estrutura.

## 6. Testes feitos e como repetir

Tudo com um comando (depois do build com `--mods`):

```bash
S=/tmp/claude-0/-home-user-Hoshiestar/86b4be55-47d2-5167-8109-96ebc6889fd2/scratchpad
servidor/build.sh --rapido $S/fork/w-mods --github-raw --sem-testes --mods $S/server-test3/mods
HOSHIE_LIBS=$S/server-test3/libraries HOSHIE_MODS=$S/server-test3/mods servidor/test.sh $S/fork/w-mods
```

(`MEK_RAPIDO=1` reduz as sementes do diferencial; `MEK_SEM_MUTACAO=1` pula a mutação.) O passo 4 do
`test.sh` chama `src/test/mods/mekanism.bash` (extensão `.bash` porque o `.gitignore` do upstream ignora
`*.sh`: com `.sh` o script sumia na exportação). O `test.sh` nunca passa em silêncio: `HOSHIE_MODS`
definido sem nenhum script é falha; script `.sh` em `src/test/mods` é falha; sem `HOSHIE_MODS` o resumo
diz `TODOS OS TESTES RODADOS PASSARAM (1 teste(s) com mods PULADOS...)` (os três casos conferidos). O
script:
1. traduz o server.jar **construído** e o Minecraft vanilla de `libraries/` dos nomes "notch" para os
   nomes de tempo de execução (SRG), com o **mesmo mapa** que o FML usa
   (`deobfuscation_data-1.7.10.lzma` de dentro do jar), subindo a hierarquia como o FML
   (`RemapeadorSrg`). Assim o teste roda **os bytes que vão para o servidor**;
2. carrega tudo num class loader de teste no papel do LaunchClassLoader (`CarregadorMods`), com o
   Mekanism e o IC2 do pacote **pelo classpath** (nada copiado), e passa a classe real
   `SynchronizedMatrixData` pelo **registro de correções real** (`Mekanism.registrar`), com os ganchos
   conferidos no arquivo reofuscado do jar;
3. roda `TesteMekanism` no JDK 8 (com a mutação) e no JDK 21.

Resultado (2026-11-22): **TODOS OS TESTES PASSARAM** em 103-109 s. **TesteMekanism 108 ok / 0 falhas
no JDK 8** (com a mutação) e **70 ok / 0 falhas no JDK 21**; base intacta (TesteAsm 224, TesteTrava 35,
TesteLimitador 13, TesteCampoPrivado 8, TesteConfig 35, nos dois JDKs).

**Transformador** (classe real): correção aplicada; CheckClassAdapter sem erros; carregada e verificada
pela JVM; um campo a mais, nenhum método a mais; `getEnergy` chama `antesDeLer`/`valorLido` e `setEnergy`
chama `antesDeEscrever` com os tipos de tempo de execução; o resto de cada método é o original instrução
por instrução (29 e 38 instruções). Recusas: desligado (nem entra no transformador), outra versão da
classe (trava), `Coord4D` diferente (trava de dependência), dependência ausente, gancho ausente, gancho
sem a tradução de nomes, classe já transformada. Leitura do modo (maiúsculas, espaços, erro de
digitação, vazio).

**Cenários** (29, todos passam; A = original, B = Hoshiestar; energia de cada célula comparada bit a bit,
mais as células órfãs e o "livro-caixa" de itens): órfã 0 J por remoção crua (ConfAdv: lista recusada,
nenhuma energia na órfã); órfã 0 J por troca crua (escrita recusada pela identidade, lista apagada);
ConfAdv com o mapa do mesmo tamanho (outra TE posta no mesmo chunk) no `ligado` (identidade recusa a
escrita) e no `verificar` (identidade acusa 1, soma 0); `verificar` com troca crua (identidade acusa,
soma não); `verificar` com lista válida o tick todo (leituras e escritas comparadas, nenhuma rápida,
0 divergências); reentrada durante a reconstrução; **R2 reentrada cruzada** (durante a reconstrução de S1
um vizinho lê e escreve S2 pelo caminho rápido; leitura de S1 e todas as células iguais); chunk
descarregado no meio e recarregado; **chunk sumido do provedor sem `onChunkUnload`** (0 chamadas que
carregariam chunk); lista zerada quando a validação falha; troca de célula na mesma coordenada; estrutura
que cresce e encolhe; mundo diferente; chamada de outra thread; **relógio parado** (antes do 1º tick:
original, contado à parte); altura fora de 0..255; estrutura incompleta (2 reconstruções em 25 ticks);
troca crua e um tick; remoção crua com energia no mesmo tick; célula movida no mesmo chunk; **R4 célula
movida num só eixo** (x, y e z, um de cada vez); célula levada para outro mundo (Spatial IO); **R1 troca
crua em cada uma das 27 posições + escrita cheia** (nenhuma energia na órfã em nenhuma posição); **R3
célula invalidada que ficou no mapa + escrita no mesmo tick** (nenhuma energia na célula morta); **R5
exceção de outro mod** durante a reconstrução (`ligado`) e durante a comparação (`verificar`): sai a
mesma exceção do original, a busca roda uma vez só, o recurso não desliga, 0 erros; **subcomando** (o
`ComandoMatriz.relatorio` do jar num mundo falso: contagem, soma direta, "iguais bit a bit: sim",
livro-caixa = soma direta + cubo, extra em chunk descarregado recusado, célula da própria matriz só
mostrada, posição sem matriz, chunk descarregado; 0 cargas de chunk); diferencial curto.
Mais: o subcomando num class loader **sem** a correção (Mekanism original, como no modo `desligado`):
relatório completo e a linha `correcao nao aplicada nesta classe`, sem carregar a classe da lógica
(antes dava `ExceptionInInitializerError`, achado I4).

**Diferencial aleatório** (mesma sequência de leituras, injeções, extrações, ticks, quebrar/colocar/
trocar célula, descarregar/carregar chunk, reformar; depois de cada passo: fotos bit a bit, leituras
iguais, leitura do modo de B = leitura original), JDK 8 e 21 com os mesmos números:

| Lote | Sementes × passos | Diferenças | Uso do caminho rápido |
|---|---|---|---|
| ligado, eventos padrão, 216 células | 30 × 4000 | 0 | 17 234 leituras e 6 868 escritas rápidas, 4 552 reconstruções |
| ligado, eventos padrão, 512 células | 6 × 4000 | 0 | 3 155 / 1 235 |
| verificar, eventos padrão | 10 × 4000 | 0 (e 0 divergências contadas) | nunca usa o rápido |
| ligado, relógio parado (sem tick) | 10 × 4000 | 0 | 6 444 / 2 542 |
| ligado, com remoção crua (fora do padrão) | 10 × 4000 | 0 | 5 099 / 2 040 |
| ligado, com troca crua seguida de tick | 10 × 4000 | 0 | 4 832 / 1 941 |
| ligado, intervalo 5 | 10 × 4000 | 0 | 6 000 / 2 180 |
| verificar, troca crua no mesmo tick | 1 × 4000 | 0 (estado igual ao original), identidade acusou 7 vezes | — |
| INFORMATIVO: ligado, troca crua no mesmo tick (R1 residual) | 6 × 4000 | diverge (é o risco documentado) | — |

Em todos os lotes exigidos: 0 chamadas que carregariam chunk, 0 erros. Limite conhecido do diferencial:
depois de algumas escritas as células ficam cheias (valores inteiros) ou vazias, e somas de inteiros não
dependem da ordem; por isso erros de ORDEM da soma são pegos pelos cenários (energias fracionárias), não
pelo diferencial (a mutação abaixo mostra isso).

**Mutação no código-fonte** (`TesteMutacao`, no lugar das antigas chaves `semProtecao`, que saíram do
código de produção). Cada proteção é tirada ou estragada no próprio `MatrizInducao.java` do workspace
(troca de trecho exato; trecho que não aparece exatamente uma vez = falha "refaça a mutação"), a classe
é compilada com os nomes de tempo de execução e posta na frente da original num class loader novo, com a
correção real aplicada, e os 29 cenários rodam. **Controle**: o fonte sem mutação, compilado do mesmo
jeito, dá **o mesmo bytecode da classe do jar construído** (MatrizInducao e as duas classes internas,
instrução por instrução, sem depuração), a classe testada vem da pasta da variante e os 29 cenários
passam. Resultado: **32 de 32 mutações exigidas acusadas**, entre elas as 6 da revisão (MA relógio com
`>`, MB soma invertida, MC identidade pulando a última célula, MD coordenada sem z, ME reconstrução
acumulando no estático, MG escrita sem conferir a célula) e mais: conjunto, relógio, laço de chunks,
`chunkExists`, ordem `getChunk`/`chunkExists`, tamanho do mapa, célula invalidada, coordenada (inteira e
sem x / sem y), mundo da célula, grupo mundo+chunk+mundo da célula, flag de reentrada na leitura e na
escrita, publicar antes de montar + sem flag, espera de 20 ticks, identidade (laço inteiro, sem a
primeira), zerar a lista (escrita inválida, depois da recusa), thread, altura, exceção de outro mod sem
o embrulho, `verificar` sem comparar identidade, `verificar` lendo ou escrevendo pelo rápido. **9
equivalentes** (outra proteção ou a própria busca original cobre; só relatadas): `isChunkLoaded` sozinho;
chunk mesmo objeto sozinho; mundo da estrutura sozinho; publicar antes sozinho (coberto pela flag);
ordem na reconstrução (a busca original já conferiu `chunkExists`); zerar na leitura (a reconstrução
sobrescreve); coordenada/mundo da célula achada na reconstrução (conferidos de novo a cada uso); no
`verificar`, comparar a soma (mesmos objetos ⇒ mesma soma) e a identidade na escrita (no mundo falso a
conferência da escrita já pega; no Crucible ela cobre o caso M4 da seção 5).

**Banco de medida (INDICATIVO: mundo falso, não é o Crucible)**, 4096 células num chunk, dois runs
completos (JDK 8 e 21): `getEnergy` original 87-145 µs, rápido 8-12 µs (**7-12 %** do original);
`setEnergy` original 115-145 µs, rápido 56-85 µs (**39-62 %**). A revisão mediu a escrita entre 46 % e
78 %, e num run curto a escrita rápida ficou **mais lenta** que a original (a conferência de identidade
custa uma busca no mapa por célula). Então: o ganho da escrita é **incerto (de nenhum a ~60 %)** e a
estimativa de ganho no servidor depende quase só da leitura. No Crucible cada busca original é mais cara
(alocação de `ChunkPosition`, `ChunkBlockHashMap`), o que tende a favorecer o rápido na leitura.
Estimativa: com ~30 leituras por estrutura por tick (1 reconstrução + 29 rápidas) a leitura cai ~85 %
(≈ 6 dos 7,02 pontos); com poucas leituras por tick cai menos. Total esperado **4 a 7 pontos de 8,27,
quase todos da leitura — não verificado** (medir no Boot 4 do plano).

## 7. O que NÃO foi verificado

- Nada rodou no modpack real (esta faixa não liga servidor): aplicação da correção pelo transformador do
  servidor, tradução de nomes pelo FML de verdade, custo real, interação com os 292 mods. É o plano da
  seção 8.
- O caminho de `World.getTileEntity` do Crucible com `addedTileEntityList` e a captura de árvore: o mundo
  falso não os tem (seção 5, R1 na escrita; o modo `verificar` no servidor cobre).
- Quantas leituras por tick e o tamanho das matrizes de produção (o ganho depende disso).
- Thread em que o OpenComputers chama a matriz (fora da thread do servidor o original roda: inofensivo).
- Subcomando: o relatório (`ComandoMatriz.relatorio`) roda no teste isolado; a leitura dos argumentos,
  `DimensionManager.getWorld` e o envio das linhas pelo Bukkit (`executar`) só no servidor.
- Nome de registro `Mekanism:BasicBlock2` e `Mekanism:EnergyCube` aceitos pelo `/setblock` (o bytecode
  registra "BasicBlock2" e "EnergyCube" no mod "Mekanism"), metas da moldura/porta na formação, e o
  cubo criado por `setblock` com só `{facing:N}` (pelo bytecode: tier ausente vira Basic, controle de
  redstone 0 = desligado, lado de saída = a frente; não executado). Conferir com 1 comando antes.
- Se as portas com cubo escrevem todo tick (lido em `CableUtils.emit`: escreve sempre que há um lado
  conectável, mesmo com 0 J enviados; não visto rodando).
- Boot em Java 8 (como a base).

## 8. Plano de teste no modpack real (para o agente que liga o servidor)

Regras: cópia nova (`scripts/prepare-test-env.sh`), só o nosso server.jar, nenhum jar nosso em
`mods/`/`plugins/`; parar sempre com `echo stop >> console.in`; JFR (nunca spark). `C` = pasta da cópia.
**A escrita é obrigatória**: é o caminho que poderia perder ou duplicar energia, e uma matriz parada
(portas para o ar) quase não escreve. Por isso todo boot de teste tem o **circuito de fluxo** abaixo.

**Preparação**
1. `servidor/build.sh --rapido <workspace> --github-raw --sem-testes --mods <mods/>`; `test.sh` com
   `HOSHIE_MODS` passa (seção 6).
2. Cópia nova com o jar construído como `server.jar`. Bot op parado ao lado da matriz (chunks carregados).
3. Comandos da matriz (moldura 7×7×7 cruzando o canto de 4 chunks: x=1004..1010, y=100..106,
   z=1004..1010; chunks 62/63 nos dois eixos), gerados por:

```python
X0, Y0, Z0, N = 1004, 100, 1004, 7
for x in range(N):
    for y in range(N):
        for z in range(N):
            X, Y, Z = X0 + x, Y0 + y, Z0 + z
            if x in (0, N - 1) or y in (0, N - 1) or z in (0, N - 1):
                print(f"setblock {X} {Y} {Z} Mekanism:BasicBlock2 1")          # moldura (meta 1)
            elif (x, y, z) == (3, 3, 3):
                print(f"setblock {X} {Y} {Z} Mekanism:BasicBlock2 4 replace {{tier:3}}")  # provedor
            else:
                print(f"setblock {X} {Y} {Z} Mekanism:BasicBlock2 3 replace {{tier:0,electricityStored:4.0E8d}}")
print("setblock 1004 103 1007 Mekanism:BasicBlock2 2 replace {mode:1b}")   # porta de SAIDA (face oeste)
print("setblock 1004 104 1007 Mekanism:BasicBlock2 2")                     # porta de ENTRADA (logo acima)
```
   Total conhecido no início: 124 × 4,0e8 = 4,96e10 J (célula tier 0: até 1,0e9 J). Antes de tudo, um
   `setblock` de cada tipo e `hoshie mekanism ...` para confirmar nomes de registro e metas.
4. **Circuito de fluxo** (energia sai pela porta de saída, passa por dois cubos de energia e volta pela
   porta de entrada; a matriz é lida e escrita todo tick pelos dois lados, sem fonte nem consumo):

```
setblock 1003 103 1007 Mekanism:EnergyCube 0 replace {facing:1}   # cubo A: recebe da porta de saida (leste), entrega para cima
setblock 1003 104 1007 Mekanism:EnergyCube 0 replace {facing:5}   # cubo B: recebe de baixo, entrega para leste (porta de entrada)
```
   Leitura atômica do livro-caixa (matriz + cubos, numa só chamada, entre dois ticks):
   `hoshie mekanism 1004 103 1007 0 mais 1003 103 1007 1003 104 1007`.
   O cubo é Basic (2,0e6 J, 800 J/t): depois de encher, circulam ~800 J por tick.
   **Livro-caixa constante** = em 5 leituras, 1 min uma da outra, `livro-caixa` varia no máximo 1e-9 em
   termos relativos (`|L - L0| / L0 <= 1e-9`), e as energias dos cubos mudam entre leituras (prova de
   que há fluxo). Se os cubos ficarem em 0 J: o circuito não liga; parar e trazer as linhas.
5. Para **eventos** (quebrar célula etc.) o circuito sai antes (`setblock 1003 104 1007 minecraft:air` e
   `setblock 1003 103 1007 minecraft:air`; a energia dos cubos some junto: anotar o livro-caixa logo antes
   e a soma direta logo depois). Sem os cubos não há escrita, então a energia de cada célula fica parada.

**Boot 1 — padrão, controle** (sem mexer no `hoshiestar.yml`): no log, `recursos: autoteste=desligado,
mekanism=desligado`; "nenhuma correcao embutida ligada: transformador NAO registrado"; depois do Done,
`recurso mekanism: desligado` e a correção "desligada". `hoshiestar.yml` ganhou a seção `mekanism` com
`modo: desligado` e `intervalo: 1`.
1. Montar a matriz, esperar ~5 s (forma no tick do casing; o MekThrottle roda o MultiblockManager a cada
   20 ticks): `hoshie mekanism 1004 103 1007` → 124 com célula, `iguais bit a bit: sim`, última linha
   `correcao nao aplicada nesta classe`.
2. **Controle de conservação com fluxo**: montar o circuito (Preparação 4); 5 leituras do livro-caixa,
   1 min uma da outra: constante (≤ 1e-9 relativo) e cubos mudando. Isto prova que o próprio circuito
   conserva energia sem o Hoshiestar. `save-all`, `stop` (o circuito fica montado).

**Boot 2 — `modo: verificar`**: no boot, "transformador das correcoes embutidas registrado (1 classe(s)
alvo)"; quando a matriz for lida, `[Hoshiestar] [embutidos] mekanism: gancho no inicio de getEnergy e
setEnergy ... aplicada` (se aparecer "trava"/"dependencia"/"gancho ausente", PARAR e trazer a linha). A
linha `recursos:` mostra `mekanism=ligado` (normal no verificar); o `hoshie status` mostra `modo verificar`.
1. **10 min com o circuito rodando**. `hoshie status`: `leituras comparadas` > 0 **e** `escritas
   comparadas` > 0 (obrigatório: escritas comparadas = 0 quer dizer que o fluxo não chega à matriz;
   parar), `divergencias de identidade 0, de soma 0`, `divergencias 0` nas escritas, `erros 0`,
   `excecoes de outros mods relancadas 0`, nenhuma linha `[mekanism] verificar:` no log. Livro-caixa
   constante em 5 leituras.
2. Tirar o circuito (Preparação 5). Eventos, um por vez, cada um seguido de 1 min e de `hoshie mekanism
   ...` + `hoshie status`: quebrar uma célula interna (antes:
   `hoshie mekanism 1004 103 1007 0 mais X Y Z` mostra a energia E da célula e a soma S0; depois
   `setblock X Y Z minecraft:air`; soma depois = S0 − E, ≤ 1e-9 relativo); colocar de volta com
   `{tier:0,electricityStored:<E>d}` (soma volta a S0); quebrar e recolocar um bloco da moldura (a matriz
   se refaz); levar o bot a > 20 chunks por 2 min e voltar (longe, `hoshie mekanism` diz "chunk nao
   carregado"); `save-all`; `stop` e boot de novo (soma direta igual à de antes do stop).
3. **Passa** se: divergências 0 em tudo, `erros 0`, leituras **e escritas** comparadas > 0, livro-caixa
   constante com o circuito, contas dos eventos fechando. (No verificar "soma direta = leitura normal" é
   sempre verdade, porque a leitura normal é a original; o que prova é divergências 0 com comparações > 0.)

**Boot 3 — `modo: ligado`**, mesma matriz:
1. Remontar o circuito. **10 min**. `hoshie status`: `leituras rapidas` > 0 **e** `escritas rapidas` > 0
   (obrigatório; se as escritas rápidas ficarem 0, ver `recusadas por identidade` e parar), `erros 0`.
   `hoshie mekanism ... mais ...`: `iguais bit a bit: sim` e **livro-caixa constante** em 5 leituras
   (≤ 1e-9 relativo), como no Boot 1.
2. Tirar o circuito e repetir os eventos do Boot 2, item 2, com as mesmas contas (S0 − E; volta a S0).
3. `save-all`, `stop`, boot (ainda `ligado`): soma direta igual à de antes do stop.

**Boot 4 — custo antes/depois (JFR)**: matriz grande (moldura 18×18×18 = 16³ = 4096 posições internas:
4095 células `{tier:0}`, 50 delas com `electricityStored:4.0E8d`, 1 provedor; 4 portas de saída, cada uma
com um cubo de energia na frente (um cubo cheio continua conectável, então a porta escreve todo tick, como
na produção), e 1 porta de entrada; ~5 400 comandos gerados como acima). Com `modo: desligado` e depois
`ligado` (um boot cada): `jcmd <pid> JFR.start name=mek settings=profile filename=<C>/mek-<modo>.jfr
duration=120s`; comparar a fração de amostras da thread do servidor com
`SynchronizedMatrixData.getEnergy`/`setEnergy` na pilha (`jfr print --events jdk.ExecutionSample ... |
grep`), o MSPT (`forge tps`) e, no ligado, os contadores (reconstruções ≈ 1 por estrutura por tick).
**Esperado**: a leitura cai ≥ 80 %; a escrita pode não cair (seção 6). Números absolutos dependem da
máquina e da carga sintética: reportar relativos.

**Opcionais úteis**: com `verificar`, mover a matriz (ou parte dela) com o Spatial IO do AE2 (caminho que
tira TE do mapa); contar chunks carregados com o bot longe, ligado × desligado (iguais; comando para
contar neste Crucible não verificado, talvez `/gc` do EssentialsX).

## 9. Desvios do desenho (e por quê)

- Era um mod com Mixin; virou correção embutida (decisão do coordenador): gancho no início com retorno
  antecipado; o estado vai num campo acrescentado pela transformação (no lugar dos `@Unique`).
- Relógio = `hoshiestar.api.Relogio` (correção 1 da conferência); reconstrução em arrays locais,
  publicada no fim, flag de reentrada; chunk pedido uma vez por chunk e só depois de achar a célula
  (correção 2); ordem obrigatória `isChunkLoaded → chunkExists → getChunkFromChunkCoords` (3); espera de
  20 ticks da estrutura incompleta (4); verificação por identidade, inclusive antes de cada escrita
  rápida (5; comparada com o mapa do chunk, ver R1 na escrita); referências zeradas quando a validação
  falha (6).
- **A mais**: conferência do número de tile entities do mapa de cada chunk (barata: uma leitura por
  chunk). Fecha o caso "tirada do mapa sem invalidar" também na leitura.
- **A mais**: travas das classes de que a lógica depende (TileEntityInductionCell, TileEntityBasicBlock,
  Coord4D, MatrixUpdateProtocol, UpdateProtocol).
- **A mais**: exceção de outro mod dentro da busca original é relançada como no original (o desenho
  desligava o recurso em qualquer exceção).
- Sem as chaves `matrixSetEnergyFastPath` e `matrixReportSeconds` do desenho: o modo único
  (desligado/verificar/ligado) substitui as duas primeiras e os contadores ficam no `hoshie status`.
- Rápido antes do primeiro tick do servidor não roda (relógio parado na partida).
- Escrita rápida com identidade: ganho incerto (de nenhum a ~60 % do custo original no banco; no desenho,
  ~10 %): é o preço da correção 5.
- Subcomando `hoshie mekanism` (o desenho previa um comando de mod), registrado só com o Mekanism presente,
  em qualquer modo.
- Teste de mutação no código-fonte (o desenho não tinha), no lugar de chaves de teste no código.

## 10. Para quem for mexer

- Membros do Minecraft na lógica são acessados por variável do tipo `TileEntity`/`World`/`Chunk`, nunca
  pela classe do Mekanism (ex.: `TileEntity t = celula; t.isInvalid()`): a reofuscação do build não
  conhece a hierarquia do mod e deixaria o nome errado. O teste (bytes do jar construído traduzidos como o
  FML) pega esse erro.
- Mudou `MatrizInducao.java`? A tabela de mutações (`TesteMutacao.MUTANTES`) usa trechos exatos do fonte:
  trecho que mudou faz o teste falhar com "refaça a mutação". Membro novo do Minecraft na lógica: pôr o
  nome MCP → SRG em `TesteMutacao.SRG` (senão o controle não compila ou não bate com o jar).
- Chamada nova ao mundo (busca de TE) na lógica: passar por `buscar(...)`, para que exceção de outro mod
  saia como no original.
- Testes com classes de mod ficam em `src/test/mods/` (não em `src/test/java`, que o `test.sh` compila
  sem o Minecraft): `java/` + um script `*.bash` que o `test.sh` chama com `HOSHIE_MODS`.

## 11. Revisão independente: o que foi corrigido

| Achado | Gravidade | O que foi feito |
|---|---|---|
| I1 script do teste fora do git (`*.sh` ignorado), `test.sh` pulava em silêncio | importante | script virou `mekanism.bash` (commitado); `test.sh` falha com `HOSHIE_MODS` sem script e com `.sh` em `src/test/mods`, e o resumo diz quando pulou |
| I2 plano não exercitava a escrita | importante | circuito de fluxo obrigatório (dois cubos), `escritas comparadas > 0` e `escritas rapidas > 0` exigidos, conservação com livro-caixa atômico (`mais`) no desligado (controle) e no ligado |
| I3 cobertura menor que a anunciada | importante | cenários R1-R5 da revisão (R4 nos três eixos) e outros; mutação no código-fonte com controle de bytecode: 32/32 exigidas acusadas, inclusive MA, MB, MC, MD, ME, MG |
| I4 `hoshie mekanism` com erro no desligado | importante | sem a correção aplicada o comando não toca na classe da lógica; testado num class loader sem a correção |
| M1 contadores de outras threads | menor | "outra thread" e "erros" só mudam em métodos `synchronized`; "sem mundo/antes do 1º tick" separado |
| M2 exceção de outro mod tratada como nossa | menor | embrulho `DeTerceiros` e relançamento da exceção original; testado (R5) no ligado e no verificar |
| M3 número da escrita não se repetia | menor | doc: ganho da escrita incerto, estimativa vem da leitura |
| M4 identidade × `World.getTileEntity` | menor | documentado como parte do R1 (seção 5); `verificar` obrigatório antes do `ligado` |
| M5 chaves de mutação em produção | menor | removidas; a mutação é no fonte, no teste |
| M6 subcomando registrado sem o Mekanism | menor | só registra com o Mekanism presente (em qualquer modo: no desligado serve de referência) |
| M7 README / `build.gradle` gerado | menor | fora do recurso; relatado ao coordenador |

## 12. Ganho de energia numa reabertura (achado do teste integrado)

**O que foi visto.** No teste integrado (2026-11-23), o mundo salvo no `stop` do B3 (os oito recursos
ligados) reabriu com o livro-caixa em **4,90020048E10 J**, 4,004e6 J (0,008 %) acima das leituras em
jogo, que ficaram constantes em 4,89980008E10. S1 (tudo desligado), S2 (mekanism ligado) e S4b (os
oito ligados) conservaram. Detalhe: `fork/testes/integrado/relatorio.md` e `exp-mek.txt` no
scratchpad da tarefa.

**Explicação provável (conferida no código, não reproduzida no servidor).** Três fatos, lidos com
`javap` no jar do Mekanism do pacote e no fonte do Minecraft do fork:
1. `TileEntityInductionCell.setEnergy` só grava o campo; a célula só marca o chunk como modificado
   (`markDirty`, `func_70296_d`) em `onChunkLoad` (herdado de `TileEntityBasicBlock`). A porta marca ao
   trocar de modo; nada marca quando a energia muda.
2. O `SynchronizedMatrixData.setEnergy` **original** também não marca: percorre as coordenadas,
   `Coord4D.getTileEntity` e `setEnergy` em cada célula, e só.
3. Na parada o Minecraft salva um chunk sem entidades **só se ele está marcado**
   (`Chunk.needsSaving(true)`: `hasEntities && tempo mudou || isModified`).

Logo, num chunk de células que não foi modificado de outro jeito depois do último salvamento, a energia
gravada é a daquele salvamento, não a de agora; na reabertura o total pode subir ou descer. Isso é do
próprio Mekanism, com o recurso ligado ou desligado. Nos experimentos S2 e S4b o último evento foi
quebrar e recolocar uma célula: o chunk ficou marcado, foi salvo e o total conservou. No B3 houve troca
de energia pelo circuito (que só acontece logo depois de o chunk carregar) depois do evento; é a
diferença mais provável, mas não foi provada.

**O que o Hoshiestar muda nisso: nada (teste).** A escrita rápida chama `setEnergy` nas mesmas células,
na mesma ordem, e não chama `getTileEntity` (que, para uma tile entity que existe, também não marca
nada). Cenário novo no `TesteMekanism`, "marcação do chunk como modificado igual ao original": mundo
falso que conta `World.markTileEntityChunkModified` (o que `markDirty` chama) e `getBlockMetadata` (a
primeira coisa que `markDirty` faz); 60 passos de leitura + escrita (+1,0e9 / −7,0e8 J) com ticks no
meio, original × Hoshiestar, nos modos `ligado` e `verificar`. Resultado nos JDKs 8 e 21: as contagens
são iguais nos dois universos e são **0** no original; a energia de cada célula fica igual bit a bit; a
escrita rápida de fato rodou. Log: `fork/correcoes2/mekanism.log` no scratchpad da tarefa.

**Não verificado.** A repetição pedida pelo testador (parar e reabrir várias vezes com o recurso
desligado e ligado, com fluxo no circuito depois do último evento) não foi feita: o mundo da matriz foi
apagado com a cópia do teste integrado e montar outro custa um ciclo inteiro. Para quem fizer: depois
de montar a matriz e o circuito, quebre e recoloque um bloco qualquer no chunk de cada parte da matriz
**antes** do `stop` (marca o chunk) e compare com a mesma parada sem isso; com o recurso **desligado** a
diferença deve aparecer do mesmo jeito. Se o admin quiser que a energia da matriz sempre persista, isso
seria uma correção nova do Mekanism (marcar o chunk ao escrever), fora deste recurso: não feita.
