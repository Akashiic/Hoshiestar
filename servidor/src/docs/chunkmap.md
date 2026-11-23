# chunkmap: cache de última consulta do mapa de chunks (item 1.E)

Recurso do Hoshiestar que acelera `thermos.wrapper.ChunkBlockHashMap.get`, a busca "qual chunk está
carregado na posição (x, z) deste mundo?". No perfil de produção ela custa **3,63% do tempo da thread
do servidor** (cerca de 3 ms dos 84 ms de um tick), porque roda a cada leitura de bloco, de tile
entity e de "este chunk existe?" (relatórios `docs/pesquisa/etapa1/E-chunkmap.md` e
`E-chunkmap-conferencia.md`).

**Padrão: DESLIGADO.** Desligado, o servidor roda o código original do Crucible nesse ponto.

Termos usados aqui:
- **chunk**: pedaço de 16x16 blocos do mundo; o servidor guarda os carregados num mapa por mundo.
- **cache**: uma memória pequena e rápida que guarda respostas recentes para não refazer a busca.
- **thread**: uma linha de execução. A "thread do servidor" é a que roda o laço de ticks; outras
  threads (de plugins, de rede, de salvamento) podem existir ao mesmo tempo.
- **MRU**: o cache que o Crucible já tem: os 4 últimos chunks achados ("most recently used"), em
  quatro campos `last1..last4`.

## 1. O que muda, em linguagem simples

O Crucible guarda os chunks num mapa de "grupos de 16x16 chunks" e, na frente dele, os 4 últimos
chunks achados. Com centenas de máquinas e entidades espalhadas por milhares de chunks, os 4 últimos
quase nunca são o que se procura: o servidor confere os 4 (cada um num lugar diferente da memória),
depois faz a busca no mapa, e ainda reescreve os 4 campos (cada escrita passa pela "barreira de
escrita" do coletor de lixo G1).

Com o recurso ligado, cada mundo ganha uma **tabela de 64x64 casas**. O chunk (x, z) só pode ficar
na casa `(x mod 64, z mod 64)`. Uma consulta olha **uma** casa e confere se o chunk que está lá é
mesmo o da posição pedida; se for, pronto; se não, faz a busca original no mapa e guarda o resultado
naquela casa. O mapa em si (a estrutura que diz o que está carregado), o contador `size`, o
`bulkCheck` e o `raw()` não mudam.

## 2. Chaves do `hoshiestar.yml`

```yaml
chunkmap:
  ligado: false     # padrao. true = liga o cache (reinicie o servidor)
  bits: 6           # lado da tabela = 2^bits (4..7). 6 = 64x64 = 4096 casas, 16 KB por mundo
  conferir: false   # so para teste: confere cada acerto no mapa e conta divergencias
```

| Chave | Padrão | O que faz |
|---|---|---|
| `chunkmap.ligado` | `false` | Liga o cache. Desligado = código original (ver 4.1). Lido uma vez no boot. |
| `chunkmap.bits` | `6` | Tamanho da tabela. Menor gasta menos memória e erra mais quando há jogadores longe uns dos outros. Fora de 4..7: usa 6 e avisa no log. |
| `chunkmap.conferir` | `false` | Teste: a cada acerto do cache também faz a busca no mapa; se diferirem, conta, avisa (com limite de 5 por minuto), devolve o do mapa e corrige a casa. Custa mais que o original. |

Linha no `hoshie status` (e no fim do boot), exemplo:
`recurso chunkmap: ligado | tabela 64x64 por mundo; caches criados: 7 (um por mundo carregado desde o boot); desligados por seguranca: 0; puts sobre posicao ocupada: (contado so com conferir); leitura de outra thread: nenhuma vista`
(com `conferir: true`, `puts sobre posicao ocupada` mostra o número e a linha acrescenta `conferir: acertos A, faltas F, divergencias D`).
O contador de puts sobre posição ocupada custa uma busca extra no mapa a cada `put`, por isso só existe no modo conferir (achado M5 da revisão).

## 3. Onde está o código

| Arquivo | Papel |
|---|---|
| `src/main/java/thermos/wrapper/ChunkBlockHashMap.java` | patch "// Hoshiestar": um campo `static final boolean HOSHIE_CHUNKMAP`, um campo com o cache do mapa, e chamadas que só delegam (em `get`, antes e depois de `put`, antes de `remove`), cada uma num `try/catch` cujo `catch` só grava dois campos (sem chamar método: funciona até com a pilha estourada) e cai no código original; e `hoshieSoltar`, que solta um cache desligado. |
| `src/main/java/hoshiestar/chunkmap/CacheDeChunks.java` | toda a lógica: tabela, regra de quem pode gravar, desligamento por segurança, modo conferir. Usa a classe `Chunk` do Minecraft: só carrega quando o primeiro mundo carrega. |
| `src/main/java/hoshiestar/chunkmap/ChunkMap.java` | interruptor, contadores e linha do status; registro (fase de coremod, sem nada do Minecraft). |
| `src/main/java/hoshiestar/config/HoshiestarConfig.java` | as três chaves (bloco `// ---- chunkmap ----`). |
| `src/main/java/hoshiestar/Catalogo.java` | uma linha: `hoshiestar.chunkmap.ChunkMap.registrar(cfg, correcoes);` |
| `src/test/chunkmap/` | testes isolados (seção 6). |

## 4. Argumento de segurança

### 4.1 Desligado = código original

`HOSHIE_CHUNKMAP` é `static final`, lido uma vez quando a classe `ChunkBlockHashMap` carrega (primeiro
mundo). Desligado, cada método faz um teste desse campo (o compilador JIT da JVM elimina o bloco
inteiro, porque o valor é constante) e segue para o código original sem nenhuma outra mudança: o MRU, a
busca, `put`, `remove`, `size`, `bulkCheck`, `raw` são as linhas do upstream. O campo do cache fica
`null` e nada do pacote `hoshiestar.chunkmap.CacheDeChunks` é carregado. Provado no teste diferencial
(modo `desligado`): 24 milhões de operações por JDK, todas idênticas à classe original, **inclusive** os
44 950 casos em que a original devolve um chunk que já saiu do mapa (seção 4.4): a nova desligada devolve
exatamente o mesmo objeto.

Se a leitura do interruptor falhar (classe nossa ausente ou com erro), `HOSHIE_CHUNKMAP` fica `false`.
Se a criação do cache falhar, o campo fica `null`: código original.

### 4.2 Quem escreve e quem lê (com o recurso ligado)

| Dado | Quem grava | Quem lê |
|---|---|---|
| tabela de casas (`CacheDeChunks.slots`) | **só a thread do servidor** (consulta que errou, `put`, `remove`) | **só a thread do servidor** |
| mapa do Crucible (grupos 16x16, `size`) | quem chama `put`/`remove`, como hoje (na prática a thread do servidor) | todos, como hoje |
| MRU (`last1..last4`) | `put` e `remove` (código original, como hoje); com o cache ativo, o fim do `put` da thread do servidor zera os 4 (só a thread do servidor chega lá com o cache ativo); `hoshieSoltar` **não** grava neles | ninguém enquanto o cache está ativo; o código original volta a ler depois de um desligamento |
| `ativo` (volatile, público) | qualquer thread, só de `true` para `false` (em `desligar` ou no `catch` do patch) | todos, a cada chamada |
| `erroNoPonto` (volatile) | o `catch` do patch, junto com `ativo = false` | `soltou()`, para contar no status |
| campo `hoshieCache` do mapa (comum) | construtor; `hoshieSoltar` põe `null` | todos; quem vir valor velho ainda lê `ativo == false` e cai no original |

"Thread do servidor" = `hoshiestar.api.ThreadServidor.thread()`, lida **quando o mapa é criado** e
guardada num campo `final` (`dono`). Ela é definida na primeira linha de `MinecraftServer.run()`,
antes de qualquer mundo carregar. Se um mapa for criado antes disso (não acontece no servidor
dedicado), ele fica sem cache (status: "mapas criados antes da thread do servidor").

Regras, todas em `CacheDeChunks`:
1. **Consulta da thread do servidor**: olha a casa; acerto só se o objeto não é `null` e tem
   `xPosition == x` e `zPosition == z` (campos `final` do Chunk); senão faz a busca original no mapa
   (mesmas fórmulas do Crucible, só leitura) e grava o resultado na casa se não for `null`.
2. **Consulta de outra thread**: faz **só** a busca original no mapa. Não lê nem grava a tabela, e não
   lê nem grava o MRU. (A primeira vez fica registrada no status: "leitura de outra thread".)
3. **`put`/`remove` de outra thread**: antes de tocar no mapa, o cache **daquele mundo** se desliga para
   sempre (`ativo = false`) e loga o motivo (com limite); a mudança segue pelo código original. Daí em
   diante o mapa usa só o código original. Um mundo recarregado ganha um mapa e um cache novos.
4. **`put` da thread do servidor**: ANTES de o mapa mudar, se a casa tem um chunk com as mesmas
   coordenadas, ela vira `null`; depois o código original inteiro (mapa, `size`, MRU); depois, se o
   cache continua ativo, zera o MRU e grava `casa(x, z) = chunk`.
5. **`remove` da thread do servidor**: ANTES de o mapa mudar, se a casa tem um chunk com as **mesmas
   coordenadas** (qualquer objeto, como o mapa, que zera a posição sem olhar qual objeto está lá), a casa
   vira `null`; depois o código original inteiro. Não há nada depois.
6. **Qualquer exceção** no nosso código (dentro do cache ou no ponto de chamada): o cache daquele mundo
   se desliga para sempre e a chamada em curso segue pelo código original. Isso inclui uma exceção que
   venha do próprio mapa numa consulta de outra thread (corrida com uma escrita, que também aconteceria
   no original). No ponto de chamada o `catch` só grava `erroNoPonto = t` e `ativo = false` (gravação de
   campo, sem chamada de método), então vale até com estouro de pilha (`StackOverflowError`).
7. **Cache desligado é solto na chamada seguinte** (`get`, `put` ou `remove`, de qualquer thread):
   `hoshieSoltar` conta o desligamento no status se ainda não foi contado (`soltou()`) e põe o campo
   `hoshieCache = null` (a tabela sai da memória e com ela os chunks que guardava). Não grava no MRU:
   ele já está vazio (regra 4) e o código original recomeça com ele vazio (seção 4.5).

### 4.3 Por que nenhum leitor vê chunk descarregado preso

**Invariante (I):** com o cache ativo, toda casa tem `null` ou um chunk que o mapa guarda nas
coordenadas dele.

- Vale no início (tabela vazia).
- As regras 4 e 5 esvaziam a casa daquela posição **antes** de o mapa mudar, e a regra 4 só grava o
  chunk novo **depois** que o `put` o pôs no mapa. Em qualquer ponto em que um erro interrompa a
  operação (inclusive estouro de pilha no meio), a casa está vazia ou certa (ver 4.6). A regra 1 grava o que a busca acabou de achar
  no mapa. As três rodam **na mesma thread**, uma depois da outra: entre a busca e a gravação da regra 1
  não existe `put`/`remove` (os da thread do servidor esperam a vez; os de outra thread desligam o
  cache antes, regra 3). Colisão de casa (outro chunk na mesma casa) só troca um chunk válido por outro:
  causa uma falta, nunca um resultado errado, porque o acerto confere as coordenadas.
- Logo a consulta da thread do servidor devolve sempre o que o mapa guarda, e a de outra thread lê o
  próprio mapa.

**A corrida que o conferente achou** ("Problema A": leitor T de outra thread acha C no mapa, a thread
do servidor descarrega C, T grava C na casa e C fica lá até o chunk recarregar) **não pode acontecer**:
T nunca grava (regra 2). Testes: replay determinístico na classe real (0 de 1 000 000) e estresse com 3
leitores de outras threads (0 preso; seção 6).

**Visibilidade entre threads (modelo de memória do Java):** a tabela fica presa a uma thread só, então
não precisa de sincronização. O único dado escrito por uma thread e lido por outra é `ativo`, que é
`volatile`. Numa mudança de outra thread (regra 3), ela grava `ativo = false` **antes** de mudar o mapa.
Uma consulta da thread do servidor que já estava em andamento nesse instante pode devolver o valor de
antes da mudança: é a mesma janela de corrida que o código original tem (o mapa do Crucible não é seguro
para escrita de várias threads, com ou sem o recurso). A consulta seguinte da thread do servidor lê
`ativo` de novo e vai para o código original; o que a consulta em andamento gravou na casa nunca mais é
lido. Não existe caminho em que um chunk descarregado continue sendo devolvido depois da corrida.

### 4.4 Diferença de comportamento em relação ao original (uma só, e é uma correção)

Fora de um caso, a nova devolve **exatamente** o que a original devolve. Prova (a mesma do conferente,
escrita para este código): enquanto nenhuma posição recebe um segundo `put` sem `remove` no meio, cada
entrada do MRU original é um chunk que o mapa guarda nas coordenadas dele, então a original também
devolve sempre o que o mapa guarda.

O caso: **`put` sobre posição ocupada seguido de `remove`** (dois objetos para a mesma posição; caminho
real: `CraftWorld.regenerateChunk`, usado pelo `//regen` do WorldEdit, que põe o chunk novo antes de a
fila descarregar o velho; e o "chunk que já estava sendo carregado" do `ChunkProviderServer`). Depois do
`remove`, o MRU da original pode continuar devolvendo o objeto velho, que **não está mais no mapa**
(escrita nele nunca é salva), por algumas chamadas. A nova devolve o que o mapa guarda (`null` ou o
chunk atual). Não dá para reproduzir o comportamento velho sem manter o MRU em toda consulta (que é
justamente o custo que o recurso tira): o que a original devolve nesse caso depende de todo o histórico
de consultas. Por segurança (escrita perdida é pior que uma releitura do disco), a nova fica com o mapa.
No teste diferencial: 134 642 consultas em que a original devolveu chunk fora do mapa, todas em posições
que receberam put duplo; **nenhuma outra diferença** em 72 milhões de operações por JDK. O status conta
os puts sobre posição ocupada (`puts sobre posicao ocupada`).

O `size` continua contando a mais depois de put duplo, igual ao original (mudar isso mudaria quando uma
dimensão descarrega).

### 4.5 Como o cache novo convive com o MRU de 4 entradas

- `put` e `remove` continuam rodando o código original **inteiro**, inclusive o MRU. Mas com o cache
  ativo as consultas não empurram mais nada no MRU, então um objeto velho de put duplo + `remove` (o
  defeito do original, 4.4) ficaria lá mais tempo que no original (achado M1 da revisão). Por isso,
  **enquanto o cache está ativo o MRU fica vazio**: o fim do `put` da thread do servidor zera os 4
  campos (regra 4; `remove` não precisa: com o MRU vazio ele não acha nada para empurrar). Quando o
  cache se desliga, o original recomeça com o MRU vazio e devolve o que o mapa guarda. MRU vazio é
  sempre certo: ele é só um atalho para o mapa.
- Quem zera é **só a thread do servidor**, e só com o cache ativo (um `put` de outra thread desliga o
  cache antes e não passa por ali). A primeira versão zerava o MRU em `hoshieSoltar`, que roda em
  qualquer thread: uma thread de fora podia gravar `null` em `last1` entre o `last1 != null` e o
  `last1.xPosition` da thread do servidor, que já tinha voltado ao código original (achado C1 da
  revisão de concorrência: `NullPointerException` na thread do servidor, 77 em 20 000 rodadas no JDK 8
  com `-Xint`). No original só o `remove` grava `null` no MRU. Agora o Hoshiestar não grava `null` no
  MRU fora da thread do servidor, então a thread do servidor fica com o mesmo risco do original.
- Com o cache ativo, ninguém lê o MRU e a consulta não grava nele: a escrita de 4 campos por consulta
  (o "ler já escreve estado compartilhado" do estudo D-32) **some** do caminho quente. A thread do
  servidor passa a escrever só na tabela dela, e só quando erra; as outras threads não escrevem nada
  numa consulta (hoje escrevem no MRU). Ou seja: não piora o D-32, melhora.
- Por que a consulta de outra thread não usa o MRU: como a thread do servidor deixou de empurrar
  chunks no MRU, um objeto velho de put duplo ficaria lá mais tempo do que hoje; ler direto o mapa é
  mais seguro e não escreve nada.

### 4.6 Erro no meio de `put`/`remove` (estouro de pilha) e contagem no status

Achado I1 da revisão: antes, a casa só era corrigida **depois** de o mapa mudar, por uma chamada de
método. Um estouro de pilha nessa chamada (e de novo no `catch`) deixava o mapa mudado, a casa velha e o
cache ativo: chunk preso. Agora a ordem é: limpar a casa, mudar o mapa, gravar a casa nova (regras 4 e
5). Erro antes da limpeza: o mapa não mudou, casa certa. Erro entre a limpeza e a gravação: casa vazia.
Erro na gravação: a gravação de um elemento de array acontece inteira ou não acontece. Em nenhum caso a
casa diverge do mapa, e isso não depende de o desligamento rodar.

Achado M2: o desligamento feito pelo `catch` do patch (sem pilha para contar) agora é contado na
chamada seguinte, por `soltou()`, antes de soltar o cache. O contador sobe dentro de `synchronized`
antes de marcar "já contado", então um erro no meio não perde a contagem (no pior caso, ela fica para a
próxima chamada).

Teste: `TesteChunkmapAdversario` (T2) provoca estouro de pilha em cada profundidade possível dentro de
`put`, `remove` e `get`, no modo padrão da JVM e com `-XX:-TieredCompilation`, nos JDKs 8 e 21: 0 cache
ativo com casa diferente do mapa, 0 chunk fora do mapa devolvido, 0 desligamento fora do contador, 0
cache desligado não solto. O mesmo teste no código de antes da correção falha (6 casas erradas e 6
chunks presos no JDK 21 com `-XX:-TieredCompilation`; 8 desligamentos fora do contador no modo padrão).

Limite conhecido: se a própria entrada em `hoshieSoltar` estourar a pilha, a exceção sobe antes de o
código original rodar (o mapa não muda e o cache continua desligado e consistente); o código original
também chamaria métodos mais fundos logo em seguida (a busca no mapa).

### 4.7 Custo

Memória: 4 bytes por casa (ponteiro comprimido), 16 KB por mundo com `bits: 6` (60 mundos ≈ 1 MB).
Caminho quente ligado: um teste de campo estático, uma leitura do campo do cache, uma leitura volatile
(no x86 é uma leitura comum), `Thread.currentThread()` (registrador), uma leitura de casa e duas
comparações de coordenadas. Nenhuma alocação. Sem o modo conferir, nenhum contador é tocado.

## 5. Microbenchmark (só indicação; JDK 21, G1)

`BenchChunkmap` (adaptado do `Bench.java` do desenho: 8 áreas de 21x21 chunks + 500 soltos, 400
"âncoras", cache do processador sujo a cada 256 consultas). Mediana de 7 repetições, ns por consulta,
duas rodadas (`fork/testes/chunkmap/bench-jdk21.log` no scratchpad da tarefa):

| Padrão | original | desligado | ligado |
|---|---:|---:|---:|
| 1: mesmo chunk 4x + vizinho | 24,6 / 26,2 | 26,2 / 25,3 | 26,6 / 20,2 |
| 2: âncora ±1 chunk, 6 consultas | 40,4 / 40,5 | 40,6 / 40,0 | 24,2 / 23,1 |
| 3: chunk carregado aleatório | 50,5 / 48,9 | 52,1 / 50,1 | 46,1 / 42,0 |
| 4: como 2, 20% na borda | 41,9 / 41,1 | 44,5 / 44,7 | 34,1 / 33,1 |

Padrão 4 repetido mais 3 vezes (original x desligado): 44,4 x 44,4; 40,7 x 43,8; 42,4 x 44,9.

Leitura: ligado, -40% no padrão 2, -16 a -20% no 4, -9 a -14% no 3; no padrão 1 (onde o MRU já acerta)
empata ou ganha. Desligado fica igual ao original nos padrões 1 a 3; no 4 ficou em média ~5% mais lento
(5 rodadas: 42,1 x 44,5 ns), dentro da faixa de variação do próprio original (40,7 a 44,4), mas sempre do
mesmo lado (ver "não verificado"). O ganho no servidor real **não foi medido** (o desenho estimou 0,9 a 2,2 pontos
dos 3,63%; o conferente lembra que parte do custo é a primeira leitura do objeto Chunk, que só muda de
lugar). Só uma medição antes/depois com JFR em produção ou numa cópia carregada responde.

## 6. Testes isolados

Um comando (JDK 8 e JDK 21; ~2 min; `--rapido` ~30 s; `--bench` acrescenta o microbenchmark):

```bash
HOSHIE_LIBS=<pasta libraries/ de um servidor> bash <workspace>/src/test/chunkmap/teste-chunkmap.bash <workspace>
```

Os fontes ficam em `src/test/chunkmap/` (e não em `src/test/java`) porque compilam a classe **real** do
fork e uma cópia da original, que precisam do koloboke e da classe `Chunk` (um substituto mínimo em
`stubs/`, com os mesmos dois campos `public final int` da classe real); o `servidor/test.sh` compila
`src/test/java` sem essas bibliotecas. O script confere antes que a cópia da original
(`java/hoshiestar/teste/chunkmap/original/ChunkBlockHashMap.java`) é o arquivo do upstream
`67b53034` com só a linha do pacote trocada.

Resultado (última rodada completa, depois das correções da revisão, commit `d435fe47`; igual no JDK 8 e
no 21; log `fork/testes/chunkmap/fix/teste-completo.log` no scratchpad da tarefa):

| Teste | O que prova | Resultado |
|---|---|---|
| `TesteChunkmapConfig` | chaves no `hoshiestar.yml` (padrões, arquivo antigo ganha a seção, valor inválido avisa, arquivo completo não é regravado), `bits` fora de 4..7, registro e linha de estado | 18 ok |
| `TesteChunkmapDiferencial desligado` | 24 000 000 operações (4 sementes x 3 domínios x com/sem put duplo): get, put, remove, `put(null)`, `remove(null)` (NPE nos dois), size, bulkCheck, raw, get de outra thread: **tudo idêntico** à original, inclusive 44 950 gets em que a original devolve chunk fora do mapa | 51 ok |
| `TesteChunkmapDiferencial ligado` | 72 000 000 operações (tabelas de 4, 6 e 7 bits; domínios: pequeno, grande com negativos e extremos de `int`, colisão de casa): get = mapa sempre (inclusive de outra thread); put/remove/size/bulkCheck/raw/NPE idênticos; get = original menos 134 642 casos, todos do defeito do put duplo; 0 diferença nas rodadas sem put duplo; cache nunca se desligou | 184 ok |
| `TesteChunkmapDiferencial conferir` | o mesmo com o modo conferir: 14 143 986 acertos conferidos, 0 divergência | 185 ok |
| `TesteChunkmapCorrida` | replay da corrida do conferente: classe nova 0 de 1 000 000 (casa ocupada e vazia; o leitor de fora não grava), modelo sem proteção 1 000 000, modelo com proteção 0, original 3; estresse 3 000 000 iterações com 3 leitores de outras threads (~110 milhões de leituras): 0 preso, 0 errado, tabela coerente com o mapa, cache ativo; o mesmo estresse no modelo sem proteção achou 3 900 / 4 872 presos (controle); put/remove de outra thread desliga e segue certo (200 000 operações); erro interno cai no certo e desliga; conferir detecta e corrige um acerto falso; mapa sem thread do servidor fica sem cache | 32 ok |
| `TesteChunkmapAdversario` (padrão e `-XX:-TieredCompilation`) | testes da revisão independente, com o resultado que a correção exige: T1 depois de um desligamento (put de outra thread; erro dentro da consulta) e de descarregar 3 000 chunks, o cache é solto e 0 de 3 000 chunks fica na memória; T5 put duplo + remove + desligamento: `get` devolve o que o mapa guarda (`null`), como a original; T7 casa envenenada à força: o leitor de fora recebe o que o mapa guarda e a leitura não desliga o cache (pega a mutação M9 da revisão); T2 estouro de pilha em 7 254 profundidades dentro de `put`/`remove`/`get` (seção 4.6). Controle: no código de antes da correção o mesmo teste dá 7 falhas por modo | 19 ok em cada modo |

`servidor/test.sh` (testes da base) continua passando: o `TesteConfig` passou a gerar o caso "arquivo
válido e completo" a partir dos padrões, para valer com qualquer campo novo.

## 7. Testes no modpack real

### 7.1 O que foi feito (2026-11-22, jar do workspace no commit `fd46b381`)

Cópias novas do pacote (`newcopy.sh`: semente fixa, 127.0.0.1, offline, 292 mods), só com o
`server.jar` trocado; nenhum jar nosso em `mods/` ou `plugins/`. Logs em
`fork/testes/chunkmap/` no scratchpad da tarefa (`on-console.log`, `off-console.log`, `on2-console.log`,
`*-bot.log`, `resumo.py`, `sequencia.sh`).

Sequência fixa (`sequencia.sh`), igual nas cópias: bot `HoshieBot` entra, `op` e `gamemode 1` (para não
morrer sufocado num teleporte), 4 teleportes longe (`tp HoshieBot 2000 120 2000`, `-3000 120 1500`,
`500 120 -2500`, `-1500 120 -1500`), 25 s em cada, `forge tps`; dois `//chunk` + `//regen` do WorldEdit
(regeneram os chunks -94,-94 e -92,-94 por `CraftWorld.regenerateChunk`, o caminho do put duplo);
volta a 0,0; bot sai; 4 x 45 s para os chunks descarregarem; `save-all`. Em cada marca: `crucible chunks`
(chunks carregados por dimensão = `size()` do mapa) e `hoshie status`.

1. **Ligado + conferir** (cópia `cm-on`, mundo novo; um primeiro boot exploratório na mesma cópia, com
   o bot morrendo num teleporte, deu o mesmo resultado de segurança e foi descartado para a contagem):
   "Done" em 69 s; boot: `recursos: autoteste=desligado, chunkmap=ligado`; 61 caches (um por mundo).
   No fim: `desligados por seguranca: 0; puts sobre posicao ocupada: 2` (os dois `//regen`: o caminho do
   put duplo aconteceu de verdade); `leitura de outra thread: nenhuma vista`;
   `conferir: acertos 916897807, faltas 14305612, divergencias 0` (cada acerto conferido contra o mapa;
   taxa de acerto ~98,5% neste teste). Nenhuma linha `[chunkmap]` de aviso. `stop` limpo ("Saving
   worlds", processo saiu, sem crash-report).
2. **Desligado** (cópia `cm-off`, mundo novo, sem `hoshiestar.yml`: o arquivo foi criado com
   `chunkmap: ligado: false, bits: 6, conferir: false`): mesma sequência, os dois `//regen` responderam
   "Region regenerated.", `stop` limpo.
3. **Contagem de chunks carregados por dimensão depois da mesma sequência (fim, assentado)**:

   | | total | -112 | 0 | 17 | 80 | 81 | 82 | 83 | 84 | 85 |
   |---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
   | ligado | 322 | 21 | 258 | 13 | 1 | 1 | 1 | 13 | 13 | 1 |
   | desligado | 322 | 21 | 258 | 13 | 1 | 1 | 1 | 13 | 13 | 1 |

   **Bate em todas as dimensões** (as demais com 0 nos dois). As contagens no meio da sequência variam
   entre as duas cópias (o ritmo de carga e descarga depende do tempo de cada tick), o que é esperado.
4. Linhas com "Exception" no console: 556 (ligado) x 561 (desligado); as diferenças são só o nome da
   pasta da cópia e falhas de rede de verificadores de atualização de mods. Nenhuma exceção nova.
5. **Ligado sem conferir** (a configuração de produção; mesma cópia `cm-on`, reinício com o mundo
   já existente; "Done" em 45 s): o `hoshiestar.yml` editado à mão não foi regravado (mesmo SHA-256 antes
   e depois). Mesma sequência (com o último passo trocado por `spawn HoshieBot`, porque o bot salvo no
   ar a 0,120,0 foi expulso por "voar" ao reentrar numa tentativa anterior; o arquivo de jogador dele
   foi tirado da cópia). Fim: `desligados por seguranca: 0; puts sobre posicao ocupada: 2` (os dois
   `//regen`); `leitura de outra thread: nenhuma vista`; 61 caches; `stop` limpo, sem crash-report.
6. Os tempos de tick do `forge tps` nessas rodadas **não servem** para comparar desempenho (mundo
   sendo gerado, modo conferir, ruído de uma cópia sem jogadores); não tiro conclusão deles.

### 7.1b Repetição depois das correções da revisão (2026-11-22, commit `d435fe47`, jar `dd439f60…`)

Uma cópia nova (`cmfix`), mesma sequência, logs em `fork/testes/chunkmap/fix/` no scratchpad da tarefa.

1. **Ligado + conferir**, mundo novo: "Done" em 70 s; 61 caches. No fim: `desligados por seguranca: 0;
   puts sobre posicao ocupada: 2` (os dois `//regen` responderam "Region regenerated."); `leitura de
   outra thread: nenhuma vista`; `conferir: acertos 828163427, faltas 13894233, divergencias 0`. Nenhuma
   linha `[chunkmap]`. Contagem final por dimensão **igual** às rodadas de 7.1 (ligado e desligado):
   total 322 (-112=21, 0=258, 17=13, 83=13, 84=13, 80/81/82/85=1). `stop` limpo, sem crash-report.
   A rodada desligada não foi repetida: o caminho desligado não mudou (as correções só tocam código
   dentro de `if (HOSHIE_CHUNKMAP)` e de `hoshie != null`).
2. **Ligado sem conferir** (produção), mesmo mundo, `hoshiestar.yml` editado à mão não regravado (mesmo
   SHA-256 antes e depois): "Done" em 37 s; 2 x "Region regenerated."; no fim `desligados por seguranca:
   0`, `leitura de outra thread: nenhuma vista`, `puts sobre posicao ocupada: (contado so com conferir)`;
   nenhuma linha `[chunkmap]`; dimensão 0 = 258 no fim (as outras dimensões não carregaram neste boot,
   como no reinício de 7.1); `stop` limpo, sem crash-report. Uma primeira tentativa desse boot foi
   descartada: o bot, salvo no ar, foi expulso por "voar" ao entrar (logs `prod-falho-*`; parada limpa);
   tirei o arquivo de jogador do bot da cópia e repeti. Nenhum `kill`. A cópia foi apagada.

### 7.2 Plano para repetir (ou para o admin repetir numa cópia carregada)

1. Cópia nova: `newcopy.sh cm-on` e `newcopy.sh cm-off`; troque o `server.jar` das duas pelo jar do
   Hoshiestar. Em `cm-on` escreva o `hoshiestar.yml` com `chunkmap: ligado: true` e `conferir: true`
   (o resto no padrão).
2. `start.sh cm-on`; espere 20 s depois do "Done"; `bash sequencia.sh <pasta cm-on> on`.
   No log tem de aparecer: `recursos: ... chunkmap=ligado`; no fim do boot
   `recurso chunkmap: ligado | ... caches criados: N`; nas marcas, `divergencias 0` e
   `desligados por seguranca: 0`; depois do regen, `puts sobre posicao ocupada` >= 1.
3. `stopw.sh <pasta cm-on>`: "Saving worlds", processo sai, sem `crash-reports/`.
4. O mesmo com `cm-off` (sem `hoshiestar.yml`). Compare a última marca das duas com
   `python3 -I resumo.py <console.log>`: os números por dimensão têm de bater.
5. Produção (antes de ligar de vez): numa cópia carregada (ou em produção, com o admin), medir com JFR
   (`jcmd <pid> JFR.start duration=300s filename=...`) o tempo de `ChunkBlockHashMap.get` com o recurso
   desligado e ligado (sem `conferir`), e olhar no `hoshie status` se `desligados por seguranca` e
   `leitura de outra thread` continuam em 0 / "nenhuma vista".

## 8. Não verificado

- Ganho real no servidor (taxa de acerto em produção; onde vai parar o custo da primeira leitura do
  Chunk). Só os números do microbenchmark acima, que são indicação.
- Boot em Java 8 (a produção usa Java 21; os testes isolados rodam nos dois).
- Que nenhum mod ou plugin chama `put`/`remove` do mapa fora da thread do servidor em produção. Se
  chamar, o cache daquele mundo se desliga sozinho (seguro) e o status mostra o motivo e a thread.
- Que nenhum leitor de outra thread exista em produção (o perfil de 602 s não mostrou nenhum; o status
  passa a mostrar a primeira).
- A diferença de 2 a 8% do modo desligado no padrão 4 do microbenchmark: o código desligado é o
  original mais um teste de constante que o JIT elimina; o método `get` ficou com 292 bytes de bytecode
  (280 antes das correções da revisão; 242 no original), abaixo do limite de inlining de 325. O
  microbenchmark não foi repetido depois das correções (o caminho desligado não mudou). Pode ser ruído de layout de código; não investigado.
- A classe `Chunk` real nos testes isolados: eles usam um substituto com os mesmos dois campos. A classe
  real só roda no teste de servidor.
- **Revisão independente**: feita (`fork/rev/chunkmap/revisao.md`, veredito "aprovado com ressalvas").
  As correções da seção 10 ainda não passaram por uma segunda revisão de outro agente.
- O estouro de pilha só foi provocado em teste isolado; no jogo a chance é baixa (revisão, I1).

## 9. O que mudou em relação aos relatórios de desenho e de conferência

- **Patch no fork, não mixin**: o mesmo desenho E1 (tabela direta, invalidação no `remove`), escrito
  em `ChunkBlockHashMap.java` com marcas `// Hoshiestar` e a lógica em `CacheDeChunks`. A "impressão
  digital" da classe (trava de versão) não se aplica: a classe é nossa, compilada no jar.
- **Correções do conferente aplicadas**: padrão desligado (B); só a thread do servidor grava a tabela (A).
  A thread dona vem de `hoshiestar.api.ThreadServidor` (decisão do coordenador) em vez de
  `MinecraftServer.primaryThread`; as duas são a mesma thread (`Server thread`, confirmado no boot:
  "thread do servidor: confere").
- **A mais, por segurança**: (1) `put`/`remove` de outra thread desligam o cache daquele mundo antes da
  mudança (o desenho não tratava escrita fora da thread do servidor); (2) qualquer exceção desliga o
  cache daquele mundo; (3) consulta de outra thread lê direto o mapa, sem o MRU (o pedido era "caminho
  original sem tocar no cache novo": a busca é a original, sem o MRU, pelo motivo da seção 4.5).
- **`put`/`remove` mantêm o MRU original** (o desenho tirava as escritas do MRU de `put`): custa pouco
  (são raros). Depois de um desligamento o MRU é zerado (seção 4.5).
- **Semântica**: o pedido do coordenador foi "idêntica à original em todos os casos, inclusive put
  duplo + remove". É idêntica em todos os casos menos esse, em que a original devolve um chunk que já
  saiu do mapa e a nova devolve o que o mapa guarda (seção 4.4: copiar o comportamento velho exigiria
  manter o MRU em toda consulta). Com o interruptor **desligado** é idêntica em todos os casos, inclusive
  esse (testado).
- **Opção de verificação** (`chunkMapVerify` do desenho) virou `chunkmap.conferir`; `chunkMapCacheBits`
  virou `chunkmap.bits`.
- **Testes isolados num script próprio** (`src/test/chunkmap/teste-chunkmap.bash`; extensão `.bash`
  porque o `.gitignore` do upstream ignora `*.sh`), porque o `servidor/test.sh` compila `src/test/java`
  sem koloboke nem a classe Chunk. O `servidor/test.sh` roda este script (com `--rapido` no modo
  padrão) junto com os dos outros recursos e conta a falha.

## 10. Correções depois da revisão independente

| Achado | Correção | Teste |
|---|---|---|
| I1 estouro de pilha no meio de `put`/`remove` deixava casa errada com o cache ativo | casa limpa **antes** de o mapa mudar, gravada só depois do `put`; sem gancho depois do `remove`; `catch` do patch sem chamada de método (4.6) | T2: 0 casa errada, 0 chunk preso (antes: 6 e 6) |
| I2 tabela morta segurava chunks depois de um desligamento | regra 7: na chamada seguinte o cache é solto (`hoshieCache = null`) | T1: 0 de 3 000 na memória (antes: 3 000) |
| I3 semântica ligada difere no put duplo + remove | **não mudado**: decisão do coordenador (4.4); o revisor recomenda aceitar | diferencial |
| M1 MRU velho depois de um desligamento | o MRU fica vazio enquanto o cache está ativo (4.5; até o achado C1, era zerado quando o cache era solto) | T5: `null`, como a original (antes: o chunk velho) |
| M2 desligamento fora do contador do status | contado na chamada seguinte (`soltou()`), dentro de `synchronized` (4.6) | T2: 0 fora do contador (antes: 8) |
| M3 sem teste para "leitor de fora não lê a tabela" | T7 trazido para `TesteChunkmapAdversario` | T7 |
| M4 testes fora do `servidor/test.sh` | **não mudado**: o `test.sh` é do coordenador (proposta na seção 9) | - |
| M5 busca extra em todo `put` só para um contador | o contador só existe com `conferir: true` | diferencial ligado/conferir |

## 11. Correção depois da revisão de concorrência (achado C1)

| Achado | Correção | Teste |
|---|---|---|
| C1 `hoshieSoltar` zerava `last1..last4` de qualquer thread: `NullPointerException` (ou `null` para chunk carregado) na thread do servidor que já tinha voltado ao código original | variante C do revisor: o MRU fica vazio enquanto o cache está ativo (zerado no fim do `put` da thread do servidor) e `hoshieSoltar` só põe `hoshieCache = null` (4.5, regras 4 e 7) | T8 em `TesteChunkmapAdversario` (MRU vazio com o cache ativo; soltar de outra thread não apaga o MRU que a thread do servidor já usa): 7 ok no JDK 8 e no 21, nos dois modos; na classe de antes, 3 falhas. `RevSoltar` do revisor (20 000 rodadas) contra a classe corrigida: NPE na thread do servidor 0 e exceção no leitor de fora 0, no JDK 8 e no 21, com `-Xint` e no modo padrão (antes: 77 / 94 / 1 / 0). As "respostas erradas" que sobram também aparecem no upstream (corrida do próprio MRU do Crucible com leitores de fora). Suíte completa: todos os testes passaram. Logs: `fork/correcoes2/` no scratchpad da tarefa |
