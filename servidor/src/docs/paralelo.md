# Paralelo (Degrau B): fase paralela sincronizada com barreira

Estado deste documento: **passos 1 a 5 do plano** (`docs/09-plano-degrau-b.md`, secoes 10 e 11). Existem os
pre-requisitos sem fase (as chaves do `hoshiestar.yml`, as recusas de boot, o comando `hoshie paralelo` e a correcao do
lado do FML), o **nucleo de concorrencia sem Minecraft** (secao 6), as ferramentas de teste (secao 7), o **modo
`sombra`** (secao 8: classificacao das regioes, guardas que so contam, invariantes, coleta dos fatos do jogo no boot) e o
**modo `sombra_ordenada`** (secao 9: lotes, adiados e juncao, rodados pela thread do servidor sozinha).
**O nucleo de concorrencia ainda nao esta ligado ao jogo**: nenhuma thread de regiao e criada (passo 6).
Com tudo no padrao (`paralelo.ligado: false`) o servidor se comporta como antes.

Termos: *thread* = uma linha de execucao do programa; *thread do servidor* = a que roda o laco de ticks; *tile* = bloco
com logica propria (bau, mesa de encantamento); *regiao* = grupo de chunks vizinhos que o orcamento (Degrau A) mede junto;
*sombra* = modo em que tudo roda em serie e so se conta o que seria ilegal na fase paralela.

## 1. Chaves (secao `paralelo:` do `hoshiestar.yml`, ultima do arquivo)

| Chave | Padrao | Faixa / valores | O que e |
|---|---|---|---|
| `ligado` | `false` | true/false | Interruptor. `false` = comportamento original; nenhum gancho roda. Lido so no boot. |
| `modo` | `sombra` | `sombra`, `sombra_ordenada`, `paralelo` | Os tres modos do plano (secao 2). Os dois ultimos exigem `parada.ligado: true`. `paralelo` ainda recusa (passo 6). |
| `dimensao` | `0` | um numero | Uma dimensao so. Tem de existir. |
| `threads` | `3` | 1 a 6 | Threads de regiao (a thread do servidor nao pega lote). |
| `classes` | vazio | `mesa`, `bau`, `bau_ender`, separadas por virgula | Vazio = fase vazia. `funil` so entra com o passo 8 do plano (onda 2, condicional). |
| `ao_falhar` | `parar` | `parar`, `rebaixar` | Excecao num tile de regiao. |
| `ao_violar` | `parar` | `parar`, `rebaixar` | Violacao de dono ou invariante quebrado. |
| `leitura_amostra` | `64` | 0 a 1000000 | Em paralelo, confere 1 a cada N leituras (0 = nao confere). |
| `aviso_espera_s` | `5` | 1 a 3600 | Fatia da espera (so avisa; nunca libera). |
| `sinal_ticks` | `1200` | 0 a 1728000 | Ticks em sombra depois de um sinal. |
| `aquecimento_ticks` | `600` | 0 a 1728000 | Ticks em sombra depois do boot, antes de abrir a fase. |

Os padroes e os valores aceitos de `modo`, `ao_falhar` e `ao_violar`, `threads` e `classes` sao os da secao 10 do plano.
**As faixas de `leitura_amostra`, `aviso_espera_s`, `sinal_ticks` e `aquecimento_ticks` o plano nao fixa**: sao decisao do
passo 1 (limite de 1728000 ticks = 24 horas), registrada em `PROGRESSO.md`.

Leitura do arquivo: a regra e a de todo o `hoshiestar.yml` (ver `HoshiestarConfig`). Valor de tipo errado (texto onde se
espera numero ou true/false) usa o padrao e sai aviso no log; chave desconhecida e secao no formato errado idem; o arquivo
nao e regravado. Valor de numero ou de texto **fora da faixa** nao vira padrao em silencio: vira motivo de recusa (abaixo) e
o recurso fica desligado.

## 2. Recusas de boot

Funcao pura `hoshiestar.paralelo.Recusas.avaliar(ligado, parametros, fatos)`: devolve a lista de motivos (um texto cada, em
ordem fixa; vazia = nada impede). Com `ligado: false` devolve sempre vazia. Motivos, na ordem:

1. valor fora da faixa em alguma chave `paralelo.*`;
2. **orcamento desligado** (`orcamento.ligado: false`; o modo `medir` basta);
3. `modo` `sombra_ordenada` ou `paralelo` com `parada.ligado: false` (sem a parada, o SIGTERM salva o mundo com a fase
   aberta; plano, secao 7, regra 1);
4. `modo: paralelo` com `chunkmap.cache4Seguro: false` (correcao H01 do cache de 4 chunks; **recusa acrescentada por este
   passo**, derivada da secao 4 do plano e do texto da chave `cache4Seguro`: sem ela duas threads gravando o cache deixam
   chunk descarregado vivo). Em `sombra` e `sombra_ordenada` so a thread do servidor roda, entao nao se exige;
5. **fatos do jogo ainda nao conferidos** (`Fatos.jogoConferido` falso, o valor inicial; **recusa acrescentada na correcao do
   passo 1**, achado da revisao): ninguem coletou no servidor os fatos dos itens 6 e 8 a 12. Vale em todos os modos. Com
   `jogoConferido` verdadeiro e a lista de dimensoes nula, a recusa e "lista de dimensoes nao informada";
6. dimensao inexistente;
7. classe fora da lista liberada;
8. `world.rand` trocado por mod (BugTorched `replaceRandomInWorld`);
9. distancia de simulacao do hodgepodge ligada (`addSimulationDistance_WIP`);
10. observador do `ChunkRequestEvent` do DragonAPI nao revisado no passo 0;
11. firewall presente sem o metodo `allowTileEntityTick` alcancavel;
12. transformador ou injecao da rede sem veredito do passo 0.

**Estado inicial: recusado.** Antes de o registro do paralelo rodar, a lista de motivos ja nasce com um motivo
("recusas do boot nao avaliadas: o registro do paralelo nao rodou") e `Paralelo.aceito()` e falso. O registro troca essa
lista pela que `Recusas.avaliar` devolve. Motivo: o paralelo e o ultimo do `Catalogo`, e todos os registros rodam num `try`
so (`Inicio.coremod`); um erro num recurso anterior faria o registro do paralelo nunca rodar, e com a lista nascendo vazia o
recurso ficaria "aceito" com o config ligado e nenhuma recusa avaliada (achado da revisao do passo 1). Nesse caso o recurso
tambem nao aparece no `hoshie status` (o erro do catalogo sai no log do boot).

**Passo 3 (decisao 39c e 39d):** recusas novas, na ordem, depois do item 4: `modo` `paralelo`
**ainda nao implementado** neste jar (no passo 3 valia tambem para `sombra_ordenada`; o passo 4 tirou); cada **fato do jogo nao lido** no boot; hodgepodge `preventChunkLoadingFromBlockUpdates`
ligado; watcher do `TileUpdateEvent` do DragonAPI fora de {`EMPTileWatcher`}. **A coleta dos fatos do jogo existe**
(secao 8.4): roda no servidor pronto ("Done"), antes do resumo do boot, e troca os motivos pelo resultado de
`Recusas.avaliar` com os fatos coletados. Ate ela rodar, o motivo do item 5 recusa (fail-closed). "Jar de terceiros com
SHA diferente tira so a classe afetada" (secao 10) nao e recusa: a classe sai da lista efetiva, com aviso.

## 3. Comando `hoshie paralelo`

Esqueleto: so informa. Console, RCON e operador de nivel 4, como o resto do `hoshie`.

- `hoshie paralelo` ou `hoshie paralelo estado`: ligado/desligado, modo configurado e efetivo, valores das chaves, classes
  efetivas e tiradas por trava; com o recurso aceito, do ultimo tick: regioes elegiveis de quantas tem tile, tiles no
  contexto da fase de quantos validos, lotes sujos; tempo da classificacao (media e pior); invariantes quebrados; e os
  contadores das guardas por operacao e resultado (secao 8.2). Espera por thread e tempo da fase sao do passo 6;
- `hoshie paralelo motivos [n]`: os motivos de recusa do boot (ate `n`) e os motivos de inelegibilidade de regiao, somados
  por tick, mais comuns primeiro, com uma posicao de exemplo (nunca nome de jogador);
- `hoshie paralelo modo [m]`: sem argumento mostra o modo; com argumento valida o nome e responde que mudar o modo em
  execucao ainda nao esta implementado (nada muda). A regra "so para modo mais seguro" entra com a fase.

- `hoshie paralelo congelar [tick]`: **so com `-Dhoshiestar.paralelo.teste=true`** (passo 2; secao 7 abaixo). Sem a
  propriedade o subcomando nao existe (cai no uso) e a ajuda nao o cita.
- `hoshie paralelo injetar quebrar [x y z]`: idem, so com a propriedade (passo 4; secao 9.7). Os outros `injetar`
  (`escrever`, `carregar`, `erro`, `dormir`, `erro_serie`) sao do passo 6 e caem no uso.

`hoshie status` e o resumo do boot dizem `paralelo: RECUSADO` (e nao "ligado") quando o recurso esta ligado no config e
recusado (achado menor do passo 1, corrigido no passo 3 com `Recursos.Estado.recusado()`). Linha de estado:
`desligado`, `ligado no config, mas RECUSADO no boot (...)` ou `ligado no config, modo sombra, sem recusa; classes
efetivas [...]`.

## 4. `getEffectiveSide` (item S30 da secao 4 do plano)

Patch em `cpw.mods.fml.common.FMLCommonHandler.getEffectiveSide()` (`patches/cpw/mods/fml/common/FMLCommonHandler.java.patch`,
marcado `// Hoshiestar`). O original devolve `Side.SERVER` so se o **nome** da thread for `Server thread`; qualquer outra
thread (inclusive as de regiao, que o plano cria no passo 5) seria vista como cliente, e mod que pergunta o lado em
`getBlock` ou `getTileEntity` se comportaria errado. Agora tambem devolve `SERVER` quando
`Paralelo.ehThreadDeRegiao(thread)`:

```java
if ((thr.getName().equals("Server thread")) || hoshiestar.paralelo.Paralelo.ehThreadDeRegiao(thr)) // Hoshiestar
```

- **Criterio**: o nome da thread comeca com `Hoshiestar Regiao #` (nome fixado na secao 2 do plano: "Hoshiestar Regiao #i").
  O plano deixa a classe `ThreadRegiao` para o passo 5; ate la nao existe criterio melhor que o nome, que e o mesmo tipo de
  criterio que o FML ja usa para "Server thread". Quando `ThreadRegiao` existir ela tem de usar esse prefixo
  (`Paralelo.PREFIXO_THREAD_REGIAO`), ou o criterio muda aqui.
- **Interruptor**: `Paralelo.LIGADO` (`public static final boolean`, lido do config uma vez) e o primeiro teste de
  `ehThreadDeRegiao`; o segundo (passo 3) e `Paralelo.aceito()`. Desligado ou recusado, o resultado e sempre o original,
  mesmo que um plugin crie uma thread com esse nome.
- **Custo**: o teste novo so roda quando o nome nao e `Server thread` (curto-circuito do `||`); desligado, e uma leitura de
  campo estatico final.
- **Limite**: o criterio por nome pode ser enganado por quem criar uma thread com esse nome com o paralelo ligado. Aceito:
  quem pode criar thread arbitraria no processo ja pode tudo, e o mesmo vale para o nome `Server thread` hoje.

## 5. Testes

`src/test/paralelo/teste-paralelo.bash <workspace>` (roda dentro do `servidor/test.sh`), nos JDKs 8 e 21:

- `TesteRecusas`: cada recusa sozinha, faixas (limites validos e invalidos de cada chave), classes, ordem fixa dos motivos,
  desligado;
- `TesteParaleloConfig` (duas vezes, com `paralelo.ligado` true e false): arquivo ausente, valores validos, lixo, chave
  desconhecida, secao errada, arquivo ilegivel, secao ausente de arquivo antigo; registro; `Paralelo.LIGADO`; criterio de nome;
  texto do comando;
- `TesteParaleloBytecode`: `Paralelo.LIGADO` e `public static final boolean`, atribuido uma vez em `<clinit>`; as classes do
  paralelo nao citam Minecraft/Bukkit/Forge; com o build rapido feito, `getEffectiveSide` tem uma chamada ao paralelo depois do
  teste original e nenhuma outra classe de `net/minecraft` ou `cpw` cita `hoshiestar/paralelo` fora o `MinecraftServer`
  (gancho do `congelar`, passo 2).

- `TesteCongelar` (passo 2; com e sem `-Dhoshiestar.paralelo.teste=true`): a ferramenta `congelar` (secao 7); e o
  `TesteParaleloBytecode` agora aceita o `MinecraftServer` citando o paralelo, so o `Congelar`, com as duas chamadas do
  tick atras de `Congelar.TESTE` seguido de `IFEQ`;
- `TesteNucleoCasos` e `TesteNucleoCarga`: o nucleo de concorrencia (secao 6.5).

**Nao verificado**: `getEffectiveSide` em execucao dentro do jogo (precisa de servidor; aqui so o bytecode e o criterio de
nome foram conferidos); o custo do teste novo no JIT; "desligado igual ao jar anterior" em tres medianas (criterio de
pronto do passo 1, medicao do coordenador, D-62).

## 6. Nucleo de concorrencia (passo 5 do plano; ainda desligado do jogo)

Pacote `hoshiestar.paralelo`, sem nenhuma classe do Minecraft, do Bukkit ou do Forge (conferido pelo
`TesteParaleloBytecode`). Quem usa entrega as regioes por uma interface e recebe o resultado; o jogo so entra no passo 6.

Termos: *lote* = os tiles de uma regiao neste tick, na ordem original; *item* = um tile do lote; *trabalhador* = thread de
regiao; *barreira* = a thread do servidor espera todos os lotes fecharem; *ponto seguro* = o intervalo entre dois itens,
onde um trabalhador pode parar sem deixar nada pela metade; *pedido delegado* = a operacao proibida que o trabalhador
entrega para a thread do servidor rodar; *portao* = onde uma thread que nao e do servidor nem de regiao espera a barreira;
*monitor* = a trava de um bloco `synchronized`.

### 6.1 Classes

| Classe | Papel |
|---|---|
| `Trabalho` (interface) | O lote como o nucleo o ve: `chave`, `tamanho`, `custo`, `ticar(i)`, `descrever(i)`. O `Lote` do passo 4 implementa. |
| `Operacao` (interface) | A operacao de um pedido delegado (o metodo original). |
| `Ambiente` (interface) | Log, log de erro e o relogio do tempo dos lotes. |
| `Barreira` | O coordenador: `abrir`, `rodar`, `fechar` (thread do servidor); `papel`, `pedir`, `portao` (guardas); `abortar`, `encerrar`. |
| `Fase` | Uma fase: os lotes de um tick (lista imutavel), o contador de pega e o de lotes fechados. Estados em `Fase.Estado`. |
| `Andamento` | O andamento de um lote na fase: itens feitos, se ficou sujo, e o `Fecho` (como terminou). |
| `Trabalhador`, `ThreadRegiao` | O laco de cada thread de regiao (`daemon`, nome "Hoshiestar Regiao #i"). |
| `Pedido`, `Preso` | O registro de um pedido delegado (com a pilha de quem pediu) e de quem nao parou no prazo. |
| `Despejo` | Texto das pilhas (`Thread.getStackTrace`, `findDeadlockedThreads`). So le; nao interrompe ninguem. |

### 6.2 Estados e uso

`FECHADA -> MONTANDO` (`abrir`) `-> RODANDO -> JUNCAO` (`rodar`) `-> FECHADA` (`fechar`). So a thread do servidor muda o
estado. `rodar(lotes)` publica os lotes (mais caros primeiro), espera todos fecharem, atende os pedidos delegados enquanto
espera e so entao volta, ja em JUNCAO, com o `Andamento` de cada lote. Lista vazia nao acorda ninguem. Uma fase esquecida
em MONTANDO e descartada no `abrir` seguinte ou no `encerrar`, sem esperar ninguem.

Como um lote pode terminar (`Andamento.Fim`): `COMPLETO`; `ERRO` (um item lancou; o resto nao rodou); `SUJO` (um pedido
escreveu nesta regiao; parou no fim do item corrente e o resto fica para a juncao); `SOBROU` (nenhum item rodou, porque a
fase parou de entregar lotes: fica para a juncao); `ABORTADO` (parada do servidor; parou entre dois itens); `MORREU` (a
thread acabou com o lote aberto; o item da vez pode ter ficado pela metade).

`abrir` devolve `false`, com motivo, e nenhuma fase abre mais, depois de: `abortar`, `encerrar`, ou a morte de uma thread
de regiao. O erro de um item nao quebra a barreira (o que fazer com ele, `ao_falhar`, e do passo 6).

### 6.3 Regras (cada uma tem teste; a tabela completa esta no relatorio do passo 5)

1. Cada lote e ticado por uma thread so, em ordem, e fechado uma vez. A barreira so passa com todos fechados.
2. A operacao de um pedido delegado roda na thread do servidor, com nenhum outro trabalhador dentro de um item: os outros
   param no fim do item corrente ou no comeco do proprio pedido. **Prazo nenhum libera a operacao**; a cada fatia
   (`aviso_espera_s`) sai um aviso com as pilhas.
3. Dois pedidos: um por vez, o do lote de menor chave primeiro. Quem ja foi respondido continua parado ate a fila esvaziar.
4. Pedido feito pela thread do servidor enquanto atende outro (guardas aninhadas) roda direto e nao conta de novo.
5. Depois do primeiro pedido, erro, aborto ou morte, nenhum lote novo comeca.
6. Thread estranha no `portao` com a fase rodando espera a barreira; interrupcao nao a faz passar (a espera continua e o
   sinal e reposto na saida). Quem esperava e liberado quando a barreira passa, e a fase seguinte so e publicada depois de
   cada liberado ter acordado e saido do `portao` (a "confirmacao do portao"; sem ela, com fases seguidas, ele poderia
   nunca passar). **A confirmacao nao garante que a escrita dele termina antes da fase seguinte**: ela so diz que a thread
   acordou; a escrita vem depois de `portao` voltar e pode cair com a fase seguinte ja rodando (e o limite "o portao so
   segura a entrada", secao 6.4). A confirmacao tambem poe a thread do servidor para esperar o escalonamento de uma thread
   de plugin, com aviso a cada fatia.
7. Excecao unica do portao: a thread estranha segura o monitor indicado e um pedido declarou que precisa dele. A thread
   do servidor para todos os trabalhadores, a thread estranha passa, e a operacao so roda quando ela soltar o monitor.
8. `abortar(prazo)`: ninguem pega outro item, os pedidos pendentes ainda sao atendidos, a chamada espera os trabalhadores
   sairem do item em que estao (volta assim que o ultimo sai, ou no prazo, dizendo quem ficou no meio de um item), e **so
   depois dessa espera** o portao abre. `encerrar(prazo)`: o mesmo, e as threads acabam. Nenhuma das duas esperas e
   largada por interrupcao de quem chama: a espera continua ate o prazo e o sinal e reposto na saida.
9. Nenhuma thread e interrompida pelo nucleo, e ele nunca chama codigo de fora com a trava dele na mao.

### 6.4 Limites conhecidos

- **O portao so segura a entrada.** Uma escrita de thread estranha que passou fora da fase e ainda esta em curso quando a
  fase seguinte abre corre contra os trabalhadores (hoje ela corre contra a thread do servidor). O plano aceitou assim
  (secao 3: "espera a barreira e segue").
- **A excecao do monitor depende de quem declara.** O pedido tem de dizer qual monitor a operacao toma; o nucleo roda a
  operacao dentro de `synchronized (monitor)`. Operacao que toma um monitor nao declarado (mesmo no meio dela: um
  `setBlock` delegado que dispara um evento sincrono do Bukkit), ou outra trava qualquer que uma thread parada no portao
  segura, trava: a thread do servidor fica na operacao e a barreira nao passa (so `abortar` destrava). O aviso de quem
  pediu, a cada fatia, mostra a pilha da thread do servidor. **Regra para o passo 6: toda guarda declara o monitor do
  gerenciador de plugins (`SimplePluginManager`) em todo `pedir` e em todo `portao`, e nao so a guarda do `callEvent`.**
  Declarar sempre e seguro (o monitor e reentrante); declarar so "quando a operacao o toma na entrada" nao e.
- **O trabalhador nao pode segurar monitor ao pedir** (as guardas ficam na entrada dos metodos). Se segurar um que a
  operacao precisa, trava do mesmo jeito.
- Thread de regiao morta nao e recriada: a fase em curso termina como falha e nenhuma outra abre.
- **`Thread.stop` numa thread de regiao nao e suportado**: trava o servidor. A morte assincrona pode deixar a trava do
  nucleo sem dono valido na fila, e a thread do servidor nunca mais sai da barreira, sem despejo (medido na revisao do
  passo 5, so no Java 8; no Java 21 `Thread.stop` nao existe mais). Nada no fork chama `Thread.stop`.

### 6.5 Testes

`teste-paralelo.bash` roda os tres nos JDKs 8 e 21. Com `--rapido` (o que o `servidor/test.sh` normal passa) a dose e curta;
sem a opcao, os testes recebem `-Dhoshiestar.teste.completo=true`.

- `TesteNucleoCasos`: casos montados, um por requisito da linha 5 do plano: estados da fase; ordem de pega; erro injetado;
  lote que dorme (rapido: 440 ms com fatia de 100 ms; completo: 20 s com fatia de 5 s), um despejo por fatia e ninguem
  interrompido; thread que morre (a unica, e uma de tres); pedido delegado (roda na thread do servidor e sozinho, excecao
  devolvida, dois juntos, aninhado, operacao que so termina na thread do servidor, espera longa, suja outro lote,
  trabalhador sem monitor pelo `ThreadMXBean`); portao (sem monitor, com monitor, com monitor e pedido nas duas formas,
  outro monitor, thread estranha que toma o monitor logo depois de a thread do servidor sair dele, confirmacao antes da
  fase seguinte com 64 threads, fases coladas, quatro threads estranhas que chegam com interrupcao pendente e ficam
  debaixo de uma tempestade de interrupcoes); abortar (todos param, um preso, com pedido pendente, no meio de uma
  operacao com outro trabalhador parado na pausa, com um trabalhador no meio de um pedido e os outros ociosos, chamado
  por thread com interrupcao pendente e debaixo de tempestade, com threads estranhas no portao que so podem passar depois
  do fim do item em curso, com o trabalhador no ultimo item do lote e com a thread morrendo logo depois dele (a chamada
  volta em dezenas de milissegundos, nao no prazo), pela thread do servidor, em MONTANDO); encerrar (e com um
  preso; e chamado por uma thread com interrupcao pendente: thread ociosa 200 vezes, tres threads no meio de um item
  com uma que demora a acabar, e um preso; e com threads estranhas no portao e um item em curso); thread do servidor
  interrompida na barreira.
- `TesteNucleoCarga`: regioes falsas de custo sorteado. (1) ticks seguidos com 1 a 8 threads (rapido: 15 mil por numero de
  threads; completo: 1 milhao): cada lote exatamente uma vez, itens em ordem, uma thread por lote, fase so termina com
  todos. (2) caos com 1, 2, 3, 4, 6 e 8 threads (rapido: 4 mil ticks; completo: 150 mil): pedidos com e sem monitor,
  aninhados, com excecao e sujando outro lote, erros de item e duas threads estranhas no portao, tudo junto; confere que
  nenhuma operacao rodou junto com outro trabalhador e, com o resto rodado em serie numa juncao falsa, que cada item rodou
  exatamente uma vez.
- `TesteNucleoSonda`: instante sorteado com oraculo proprio, que nao le estado interno do nucleo (veio da revisao do
  passo 5). Cada repeticao (rapido: 4 mil; completo: 40 mil) cria uma barreira, roda ate 25 fases e deixa cair, num instante
  sorteado, `abortar`, `encerrar`, a morte de uma thread, interrupcoes na thread do servidor ou um item preso com
  `abortar` de prazo curto, no meio de pedidos, de operacoes de 0,1 a 1 ms e de ate tres threads estranhas no portao; no
  fim, `encerrar`, uma vez em quatro chamado por uma thread ja interrompida. Uma vez em tres quem chama `abortar` ou
  `encerrar` no meio da fase chega com interrupcao pendente, e uma vez em quatro a thread estranha chega assim ao portao.
  Confere: nenhum item junto com uma operacao ou com uma escrita pela excecao do monitor, nenhum item depois de `abortar`
  voltar vazio, cada item no maximo uma vez, nenhum despejo; quem sai do portao por ter esperado nao ve item em curso na
  fase em que chegou; quem sai pelo portao aberto, sem prazo vencido, nao ve item em curso; `abortar`/`encerrar` sem item
  preso volta em menos de 3 s e com o sinal reposto. Os casos montados pegam o defeito na intercalacao que alguem pensou;
  a sonda sorteia a intercalacao.

O caso "thread estranha que toma o monitor logo depois" precisa de uma ordem que nao se monta por fora: a thread estranha
tem de chegar a trava do nucleo antes da thread do servidor, que acabou de sair do monitor. O teste pega a trava interna
da `Barreira` por reflexao (campo `trava`); se o campo mudar de nome, o caso falha com `NoSuchFieldException`.

Os casos nao dependem de a maquina estar folgada (80 execucoes seguidas com quatro JVMs ao mesmo tempo, nos dois JDKs,
todas verdes). A prova dos proprios testes e por mutacao: `src/test/paralelo/mutacoes-nucleo.py <workspace>` poe 37
defeitos no nucleo, um de cada vez numa copia das fontes, e roda os testes rapidos no JDK 8; cada defeito tem de ser
pego (na rodada de 2026-11-27, com a suite completa rodando ao mesmo tempo na maquina, os 37 foram pegos; uma rodada
anterior, com 32, deixou escapar uma vez `acorda_na_fase_vazia`, cujo teste lia o contador cedo demais e foi corrigido).
Com `--sonda` roda so a `TesteNucleoSonda` contra cada defeito (o dente da sonda sozinha). Leva uns 25 a 45 minutos e nao roda dentro do `servidor/test.sh`: rode quando mexer no nucleo ou nos testes.

**Nao verificado**: nada disto rodou dentro do jogo; o custo por item do ponto seguro e da contagem (duas leituras e uma
escrita `volatile` por item) nao foi medido; o tamanho de pilha certo para as threads de regiao (o nucleo aceita o valor,
ninguem o escolheu); o comportamento com as classes do jogo sendo carregadas pela primeira vez numa thread de regiao.

## 7. Ferramentas de teste (passo 2 do plano)

Tudo daqui so existe com a propriedade da JVM `-Dhoshiestar.paralelo.teste=true` ou fica fora do jar (scripts em
`src/test/paralelo/ferramentas/`). Independe de `paralelo.ligado`: as execucoes de referencia do plano sao com o recurso
desligado.

### 7.1 `hoshie paralelo congelar <tick>`

Quando o tempo total do mundo 0 (`getTotalWorldTime`, o `Time` do `level.dat`) chega ao tick pedido, o servidor **para
de ticar todos os mundos** e **salva uma vez** (jogadores e mundos, como o `save-all`). Depois, o `stop` salva de novo o
mesmo estado. Serve para comparar dois mundos salvos no mesmo tick exato com `tools/mundo_comparar.py mca`, no lugar da
foto de `hoshie regioes foto`, que quebra com o EndlessIDs (D-58).

- Sem numero: mostra o estado (tick atual do mundo 0, pedido, congelado ou nao).
- O tick tem de ser pelo menos o atual + 2 (o comando roda no fim do tick; um tick que ja passou ou que acabaria de
  passar daria um mundo congelado fora do pedido). Um pedido novo troca o anterior; depois de congelar nao ha outro pedido
  nem descongelar: a saida e o `stop`.
- Se o mundo 0 pular o tick (nao acontece no laco normal), congela no primeiro tick acima e o log diz o pedido e o real.
- Log: `paralelo (teste): mundos congelados no tick T do mundo 0 (pedido T); salvamento pedido. Pare com 'stop'.`

Codigo: `hoshiestar.paralelo.Congelar` (sem Minecraft) e um patch em `MinecraftServer.updateTimeLightAndEntities`
(marcado `// Hoshiestar`): antes do laco dos mundos, `if (Congelar.TESTE && Congelar.antesDosMundos(tempoDoMundo0))
hoshieSalvarCongelado();` e, como primeira linha do laco, `if (Congelar.TESTE && Congelar.congelado()) continue;`.
`Congelar.TESTE` e `public static final boolean`, lido uma vez da propriedade: sem ela, o gancho e um teste de campo falso
(comportamento original). Uma thread so: o gancho e o comando rodam na thread do servidor.

**O que continua rodando depois de congelar** (limite da ferramenta): rede, comandos, agendador do Bukkit (tarefas de
plugin), fila de tarefas do servidor, eventos de tick do servidor do FML (mods que mexem no mundo por evento de tick do
servidor, e nao do mundo, continuariam mexendo), descarga de dimensao vazia (salva o mesmo estado) e o autosave. So os
mundos param: tick do mundo, entidades, tiles e rastreador de entidades.

### 7.2 Montagens e roteiro (`src/test/paralelo/ferramentas/`)

- `b_monta_paralelo.py <copia> [--bases 16] [--espaco 64] [--hostil sim|nao]`: numa copia de teste ligada (mundo plano),
  monta perto do spawn uma grade de mini-bases (16 a 32; espaco padrao 64 blocos = 4 secoes, duas bases nunca se juntam).
  Cada mini-base, num piso de pedra: bau simples, bau duplo e bau-armadilha com itens fixos (quantidade depende do numero
  da base), bau de ender e mesa de encantamento. Sem comparador, sem funil, sem mob. **Montagem hostil** no centro da
  grade: composter do EtFuturum em cima e na frente de um funil vazio, barril do EtFuturum encostado num bau e outro em
  cima. Salva antes de ler o spawn do `level.dat`; confere cada bloco com `testforblock`. Os aneis de funis da onda 2 nao
  existem (o funil saiu da primeira entrega, decisao 39a). `--so-comandos arq` grava os comandos sem servidor.
- `b_roteiro_paralelo.py`: `base` (liga, regras fixas: sem mob, sem fogo, sem ciclo do dia, tempo limpo, pacifico; monta;
  `save-all`; para; guarda o mundo), `congelado` (restaura o mundo base, liga com a propriedade de teste, pede `congelar` no
  `Time` da base + `--ticks`, espera o log, para, guarda o mundo), `medir` (restaura, liga, 15 leituras de `forge tps` a
  cada 20 s, mediana do "Mean tick time"), `parar`. Um servidor por vez; para sempre com `stop`; nunca usa `kill` (se o
  `stop` for ignorado por 5 min, guarda dois thread dumps e avisa). Propriedades extras com `--prop=-Dx=y`.
- `b_comparar_montagem.py <mundo-X> <mundo-Y>`: compara so as posicoes da montagem (bloco e NBT inteiro do tile) e, a
  parte, tudo dentro dos chunks da montagem. Reusa `tools/mundo_comparar.py` e `tools/mundo_nbt.py` do repositorio.

### 7.3 Resultado no modpack (2026-11-27, carga sintetica: indicacao)

Duas execucoes desligadas, do mesmo mundo base, congeladas no tick 13883 (1200 ticks depois do boot): **zero diferencas
nas 707 posicoes da montagem** (65 baus, 16 mesas, 16 baus de ender, funil, composters, barris e pisos). O mundo inteiro
(`mundo_comparar.py mca`, 1377 chunks) deu **7 diferencas, nenhuma da montagem**: 3 cristais do ChromatiCraft
(`CCTileCrystalEncrusted`) que cresceram numa execucao e nao na outra (sorteio do mod; um deles encostado numa mini-base)
e um item no chao num chunk fora da area do spawn que ficou carregado por tempos diferentes (`ticksLived` 451 x 118). O
mundo plano do modpack tem estruturas de mod geradas (cristais, pilones, nos) e 32 entidades: a comparacao do mundo
inteiro so da zero sem elas. Uma terceira execucao (mesmo tick) usada como `--baseline`: as 7 diferencas aparecem tambem entre a
primeira e a terceira (que diferem em 11 pontos do mesmo tipo), e a montagem deu zero de novo. Detalhe no relatorio do passo 2 (fora do repositorio).

**Nao verificado**: `congelar` com jogador conectado (o jogador continua mandando pacotes de movimento com o mundo
parado); mod que mexe no mundo por evento de tick do servidor depois de congelar; `congelar` em outro mundo que nao o 0
como relogio (o relogio e sempre o mundo 0).


## 8. Modo sombra (passo 3 do plano)

Com `paralelo.ligado: true`, `modo: sombra` e o recurso aceito no boot, o laco de tiles do mundo da `dimensao` escolhida
ganha um "ensaio" da fase paralela que **nao muda nada no jogo**: todo tile continua ticando em serie, pela chamada
original, na ordem de sempre. O servidor so classifica as regioes, abre um contexto "lote R" em volta de cada tile que
estaria na fase, e as guardas contam o que, na fase de verdade, seria ilegal. Recusado ou desligado: nenhum gancho faz
nada alem de um teste de campo `static final`.

### 8.1 Linhas no `World.updateEntities` (marcadas `// Hoshiestar - paralelo`)

| Linha | Onde | O que faz |
|---|---|---|
| `GanchosParalelo.abrir(this)` | antes do laco de tiles (W:2533), sem variavel local nova | portoes da dimensao e classificacao (so leitura); fase em MONTANDO |
| `if (... despachar(this, tileentity)) continue;` | uma linha antes do gancho do orcamento, depois do trecho que o DragonAPI reescreve | em sombra **sempre devolve falso**; se o tile e de regiao elegivel, abre o contexto e tira a foto dos invariantes |
| `GanchosParalelo.fimTile(this)` | depois do update (junto do `fimTE` do medidor) e no `catch` | confere os invariantes e fecha o contexto |
| `GanchosParalelo.fimLaco(this)` | depois do laco (junto de `Marcas.tile(null)`) | fase FECHADA; publica os numeros |

As regras de bytecode da secao 2 do plano valem e sao testadas na classe final (secao 8.5): o tile continua no slot 9, o
metodo continua com uma so chamada de tick, nenhuma chamada `isInvalid`/`blockExists` nova antes do trecho do DragonAPI.
Fase esquecida em MONTANDO (excecao de tile em serie) e descartada no proximo `abrir`.

### 8.2 Classificacao e guardas

**Classificacao** (`Classificador`, funcao pura sobre uma `Visao`): regiao do mapa do orcamento elegivel quando todos os
tiles validos dela sao de classe liberada (mesa, bau/bau-armadilha, bau de ender; G3), nenhum aparece duas vezes (G4), os
3x3 chunks em volta de cada um estao carregados e sao da regiao (G1), bau e bau de ender estao fechados, cada vizinho bau
tem tile valido (o tick nao cria tile), e a regiao nao esta em corte nem suspensa (G2). Tile invalido esquecido nao conta.
Tile sem regiao fica em serie. A leitura nunca carrega chunk (confere `chunkExists` antes de ler bloco vizinho) e nunca
chama `TileEntity.hasWorldObj` (o AM2 troca esse metodo e escreve num mapa). **Portoes da dimensao** (G5): aquecimento
(`aquecimento_ticks`), e os sinais, que fecham por `sinal_ticks`: medidor ou observador ligados, mapa ausente, captura de
blocos/drops ligada, limitador do Spigot abaixo de 1000 ms, profiler de geracao do DragonAPI ligado, observador ou
watcher novo no DragonAPI depois do boot. O firewall nao e perguntado em sombra (a chamada original ja pergunta); a
pergunta no despacho e do passo 4.

**Guardas** (`Guardas.decidir`, funcao pura; `GanchosParalelo` e `GanchosEventos` traduzem a posicao em regiao):

| Categoria | Pontos | Com contexto aberto (tile de regiao elegivel) |
|---|---|---|
| leitura | `getBlock`, `getTileEntity` | fora do dono (outra regiao, sem regiao, outro mundo) = violacao |
| proibida | `setBlock`, `setBlockMetadataWithNotify`, `notifyBlockOfNeighborChange`, `setTileEntity`, `removeTileEntity`, `addTileEntity`, `func_147448_a`, `addEntity`, `removeEntity`, sorteio no `world.rand` do mundo, `loadChunk`, chunk faltando em `provideChunk`, `EventBus.post`, `sendPacket`, `saveAllChunks`, `saveAllWorlds` | violacao |
| dono | `func_147453_f`, `markTileEntityChunkModified` | fora do dono = violacao |
| adiada | `scheduleBlockUpdateWithPriority`, `func_147446_b`, `addBlockEvent`, os 11 metodos `void` que percorrem `worldAccesses` | adiado (o bau de ender gera um evento de bloco a cada 20 ticks) |
| evento Bukkit | `callEvent` | violacao so com ouvinte |

Sem contexto: escrita de bloco, tile ou entidade por tile em serie numa regiao elegivel = **lote sujo**; thread estranha
escrevendo, carregando chunk ou salvando com a fase em MONTANDO = **portao**. "Seria ilegal" = violacoes + portao. A
primeira ocorrencia de cada combinacao (operacao, resultado) vai ao log com a pilha; as outras so somam.

**Invariantes** (`Invariantes`): foto antes e depois de cada tile no contexto: objeto e tamanho das listas de tiles, de
tiles pendentes e de entidades; versao do mapa de chunks; tamanho da fila de ticks agendados; objeto do mapa de regioes;
captura de blocos desligada. Diferenca sem violacao de guarda no mesmo tile = "invariante quebrado".

### 8.3 Por que nao muda o jogo

`despachar` so devolve falso (conferido no bytecode: todo `ireturn` vem de `iconst_0`), entao nenhum tile e pulado nem
desviado. Os adaptadores (`GanchosParalelo`, `GanchosEventos`, `ColetaFatosJogo`) nao escrevem em campo de classe que nao
seja do hoshiestar e so chamam metodos de leitura do jogo (lista fechada no `TesteParaleloBytecode`). As leituras da
classificacao tem os mesmos efeitos laterais das leituras do proprio tick (cache de 4 chunks, `lastAccessedTick`), que nao
vao para o mundo salvo. A prova no modpack e o diferencial desligado x sombra (secao 8.6).

### 8.4 Fatos do jogo coletados no boot (decisao 39d)

`ColetaFatosJogo`, no servidor pronto: dimensoes registradas (`DimensionManager.getIDs`), classe do `rand` do mundo da
dimensao, hodgepodge (`FixesConfig.addSimulationDistance_WIP` e `preventChunkLoadingFromBlockUpdates`, por reflexao),
DragonAPI (observadores do `ChunkRequestEvent` e watchers do `TileUpdateEvent`, campos estaticos lidos por reflexao),
firewall (`WorldMutationGuard.allowTileEntityTick(TileEntity, World)` publico estatico booleano), mixins mesclados nas
classes do caminho (anotacao `MixinMerged`, visivel em execucao), cadeia de transformadores do class loader e SHA-256 dos
jars travados (achados pelo recurso de uma classe que so eles tem, sem carregar a classe). O que nao der para ler fica
`null` e a interpretacao (`ColetaFatos`, funcao pura) transforma em recusa: **nunca fail-open**. Mod ausente nao e fato
faltando, mas "ausente" so vale se o codigo do jar tambem sumiu: cada trava tem os prefixos de pacote do jar, e recurso-sonda
ausente com algum mixin ou transformador desses pacotes presente (mod atualizado, classe-sonda mudou de lugar) tira as
classes da trava; para hodgepodge, DragonAPI e firewall a presenca vira "nao lida" (recusa). Lista de mixins vazia com
algum jar travado presente tambem e "nao lida" (anotacao `MixinMerged` nao visivel). Vetor de ouvintes do DragonAPI de
tipo inesperado e "nao lido", nao "vazio" (revisao do passo 3, I1). O log diz so nomes de classe e ligado/desligado. Listas com veredito embutidas em `Travas` (passo 0, secao 7, e
o dump do passo 2). Propriedade de teste (so com `-Dhoshiestar.paralelo.teste=true`):
`-Dhoshiestar.paralelo.teste.esconder=dimensoes,rand,hodgepodge,dragonapi,firewall,veredito` trata esses fatos como nao
lidos.

**Limite:** o boot so ve mixins (pela anotacao) e a lista de transformadores. Um transformador ASM ja revisado que passe a
injetar noutro metodo, ou um jar novo com o mesmo nome de transformador, so e pego pelo SHA-256 (jars travados) ou pelo
teste de bytecode na classe final (8.5), que precisa de um boot com dump.

### 8.5 Teste de bytecode na classe final

`TesteBytecodeFinal <pasta loader/ do dump> src/test/paralelo/bytecode-permitido.txt` (roda no `teste-paralelo.bash`
quando `HOSHIE_PARALELO_DUMP` aponta a pasta): regras (a), (b) e (c) em `World.updateEntities`, os ganchos do paralelo no
lugar e atras de `Paralelo.LIGADO`, e a lista permitida por metodo do caminho de cada classe liberada (tirada do dump do
passo 2, revisada; cada alvo de terceiros tem o veredito anotado no arquivo). Alvo novo = a classe sai ate nova revisao.
O dump sai de um boot com `-Dlegacy.debugClassLoadingSave=true` (o RetroFuturaBootstrap grava em `RFB_CLASS_DUMP/`).

### 8.6 Resultado no modpack (2026-11-27, carga sintetica: indicacao)

Copia nova de `prepare-test-env.sh`, 127.0.0.1, modo offline; todos os boots parados com `stop`, nenhum `kill`.

- **Coleta dos fatos e recusas em boot real.** Com tudo certo: "aceito no boot (modo sombra, classes efetivas [mesa, bau,
  bau_ender])", as 11 travas com SHA-256 conferindo, 52 mixins e 129 transformadores com veredito. Com
  `-Dhoshiestar.paralelo.teste.esconder=` os seis fatos: sete recusas (uma por fato, mais a da lista de dimensoes), recurso
  RECUSADO, nenhum gancho roda. No mesmo boot, `preventChunkLoadingFromBlockUpdates` ligado no `hodgepodge.cfg` da copia foi
  lido como "ligado" e os dois mixins `PreventChunkLoading` apareceram como "sem veredito".
- **Classes finais** (boot com dump): `TesteBytecodeFinal` 37/37; as tres classes liberadas pelo bytecode final.
- **Log do boot:** Opis "Found World.updateEntities()", firewall "Patched TileEntity tick gate at World#updateEntities",
  DragonAPI "Successfully applied TILEUPDATE".
- **Desligado x sombra, congelados no mesmo tick.** Montagem do passo 2 (spawn): 0 diferencas em 707 posicoes; mundo
  inteiro: so cristais do ChromatiCraft que crescem por sorteio (mesmo tipo de variacao de D1 x D3 do passo 2). A regiao do
  spawn nunca e elegivel (tem um `TileEntityMobSpawner` gerado pelo mundo). Por isso houve uma segunda rodada com tres
  ilhas longe do spawn, cada uma mantida carregada por um bot parado: so a ilha A ficou elegivel (as outras tem spawner
  gerado); D2 x S2 deu **0 diferencas na ilha A** (mini-base inteira); as 56 diferencas do mundo inteiro estao no spawn e
  nas ilhas B e C (crescimento, idade de itens e entidades que dependem do horario de entrada dos bots).
- **2 h em sombra** (143.120 ticks, 1,70 milhao de tiles abertos no contexto, um bau simples e um duplo quebrados e
  repostos na ilha elegivel, profiler do Opis ligado 90 s): "seria ilegal" = 0, invariantes quebrados = 0, lotes sujos = 0,
  erros nossos = 0; adiados = 7.088 (eventos de bloco do bau de ender, o esperado), leituras no dono 19. O Opis rodou sem
  excecao.
- **Custo** (JFR 15 min, servidor quase ocioso): 2,9% das amostras da thread do servidor tem codigo do paralelo em algum
  quadro, 1,1% no topo (metade em `VisaoMinecraft.te`, a leitura da lista de tiles); classificacao media 0,15 ms por tick,
  pior 23 ms (um tick, no comeco). Indicacao, nao medicao de producao.

**Nao verificado:** a ilha A teve 12 tiles no contexto por tick para 6 tiles gravados no mundo (o G4 so pega o mesmo objeto
duas vezes; se a lista de tiles tem objetos diferentes na mesma posicao, nao foi conferido); sombra com mais de uma regiao
elegivel; leitura de tile fora do dono, escrita de tile em serie em regiao elegivel e thread estranha com a fase aberta no
jogo de verdade (so nos testes isolados; no modpack os contadores ficaram em zero).


## 9. Modo sombra_ordenada (passo 4 do plano)

Com `paralelo.ligado: true`, `modo: sombra_ordenada`, `parada.ligado: true` (decisao 38c; sem a parada o boot recusa) e
o recurso aceito, a classificacao e a mesma do modo sombra. A diferenca: o tile de regiao elegivel **nao tica no laco**.
Ele vai para o **lote** da regiao, na ordem do laco, e o laco segue. Depois do laco, a **thread do servidor roda os lotes
sozinha**, um por vez, com os **adiamentos**, e faz a **juncao**. Nenhuma thread de regiao e criada: e a prova da ordem e
da juncao sem concorrencia (secao 2 do plano). O nucleo do passo 5 nao e usado; o lote ja implementa o `Trabalho` dele e a
juncao recebe os mesmos `Andamento` que a `Barreira` devolve, para o passo 6 so trocar quem roda os lotes.

### 9.1 Classes

| Classe | Papel | Minecraft? |
|---|---|---|
| `Lote` | tiles da regiao na ordem do laco, adiados na ordem da anotacao, tempo, e dois cursores: `proximo` (proximo tile) e `aplicados` (adiados). Os dois sobem ANTES do passo: o que lancou nunca roda de novo | nao |
| `Adiado` | uma operacao anotada; `aplicar()` chama o metodo original com os argumentos guardados | nao |
| `Ordenada` | roda os lotes na thread do servidor, por chave, e fecha um `Andamento` por lote | nao |
| `Juncao` | por lote, na ordem da chave: aplica os adiados, depois roda em serie o resto que nao rodou; `emergencia`; politica da secao 9 do plano (`decidir`) | nao |
| `Injecao` | pedido de `injetar quebrar` (so com a propriedade de teste) | nao |
| `OrdenadaJogo` | despacho (com a pergunta ao firewall), a fase no fim do laco, o tick de cada tile com a reconferencia, crash da politica `parar`, `injetar quebrar` | sim |
| `GanchosAdiados` | os 15 pontos "A" (abaixo): contam como antes e, com um lote aberto, anotam no lote em vez de rodar | sim |

### 9.2 O tick, passo a passo

1. `abrir` (antes do laco): classificacao (igual ao sombra); lotes vazios.
2. Laco original. Na linha do despacho, o tile de regiao elegivel e classe liberada: pergunta ao firewall
   (`WorldMutationGuard.allowTileEntityTick(te, world)`, por reflexao, a mesma pergunta que a chamada do firewall no laco
   faria); "nao" = o tile nao tica (como no original); "sim" = vai para o lote; `continue`. Os outros tiles ticam ali,
   como hoje. Escrita de bloco, tile ou entidade numa regiao elegivel por um tile em serie suja o lote dela (secao 3 do
   plano).
3. Fim do laco (`fimLaco`): (teste) `injetar quebrar`; os lotes sujos sao marcados; **fecha a vez do orcamento**
   (`GanchosOrcamento.fimLaco`, idempotente: o tempo dos lotes nao cai na conta da ultima regiao em serie); foto dos
   invariantes; `Ordenada.rodar`: para cada lote limpo, contexto "lote R" aberto (as guardas sabem a regiao; os pontos "A"
   vao para o lote), e cada tile com a reconferencia (nao invalidado, com mundo) e a foto por tile do passo 3; confere os
   invariantes da fase; a fase vira FECHADA (o portao nao segura mais, como depois da barreira); `Juncao.juntar`;
   politica.
4. Os tiles do lote nao passam pelo gancho do orcamento, pelo `tickTimer` do Spigot nem pelo `tilesTicked` (Timings e
   Opis nao medem os tiles da fase: so medicao, secao 8 do plano). O vigia marca cada tile do lote enquanto ele roda.

Regras herdadas do plano: depois de um tile que lanca, o resto do lote nao roda e os lotes seguintes ficam para a juncao
(em serie); depois da primeira violacao de guarda do tick, idem; lote sujo roda inteiro em serie na juncao, com a
**reconferencia forte** (o tile ainda e o do chunk naquela posicao, lido no mapa do chunk, sem criar tile).

### 9.3 Os pontos adiados (secao 4 do plano, itens "A")

| Ponto | Metodo original chamado na juncao |
|---|---|
| evento de bloco (WS:1256) | `addBlockEvent` (com a deduplicacao dele) |
| ticks agendados (WS:596, WS:643) | `scheduleBlockUpdateWithPriority`, `func_147446_b` (o id do tick nasce em serie) |
| os 11 metodos `void` que percorrem `worldAccesses` (W:999 e familia) | o metodo inteiro: `markBlockForUpdate`, `markBlockRangeForRenderUpdate`, `func_147479_m`, `playSoundAtEntity`, `playSoundToNearExcept` (com o evento Forge deles), `playSoundEffect`, `playRecord`, `spawnParticle`, `playBroadcastSound`, `playAuxSFXAtEntity`, `destroyBlockInWorldPartially` |
| `loadChunk` com o chunk presente (CPS:174, "nao descarregar") | `loadChunk` (tira o chunk da fila de descarga); com o chunk ausente ou carga assincrona continua proibido. Operacao nova das guardas: `nao_descarregar` |

O patch e `if (Paralelo.LIGADO && GanchosAdiados.x(this, ...)) return;` (no `loadChunk`, `return` do chunk presente, sem
variavel local nova). Sem lote aberto (modo sombra, laco em serie, juncao) o gancho conta como no passo 3 e devolve falso.

**Mixins antes do gancho** (classes finais do passo 3): o akashicmixer poe "enter" no comeco dos tres metodos de som e
"exit" antes de cada `return`; o hodgepodge poe "increment"/"decrement" do contador de recursao no
`scheduleBlockUpdateWithPriority`, tambem em cada `return`. Adiar roda esses pares uma vez a mais, vazios; a juncao roda
de novo, com o corpo. Contadores e contexto em pares, sem efeito no mundo. Conferido de novo no dump do passo 4 (secao
9.9).

Outros pontos do passo: `callEvent` do Bukkit sem ouvinte, com lote aberto e evento sincrono, volta antes do
`synchronized` (efeito igual: o `fireEvent` nao teria ninguem a chamar); com ouvinte, violacao e o evento roda.
`EventBus.post` e sorteio no `world.rand` dentro do lote: violacao (so guarda, como no passo 3).

### 9.4 Juncao: nada perdido, nada duplicado

Para cada lote, na ordem da chave (a menor chave de secao entre os tiles dele): (1) aplica os adiados do lote, na ordem
da anotacao; (2) roda em serie o resto que nao rodou (lote sujo ou que sobrou), com a reconferencia forte e sem contexto
(efeitos diretos). Os adiados de um lote vem antes do resto em serie do mesmo lote, porque o que rodou na fase veio antes
no laco: a ordem dentro da regiao fica a do laco. Entre regioes a ordem muda (o plano aceita, secao 8).

Um adiado que lanca vira falha e os seguintes sao aplicados. Um tile que lanca no resto em serie vira falha e o resto
daquele lote nao roda. Lote que terminou com erro, abortado ou com a thread morta nao tem resto em serie (os dois
ultimos so existem no passo 6). Chamar a juncao duas vezes, ou a emergencia depois dela, nao repete nada (cursores).

**Erro no nosso codigo** no despacho: os tiles ja despachados rodam em serie no fim do laco, uma vez (`emergencia`), e os
que faltam ficam no laco original. Erro nosso na fase: idem, a partir dos cursores. Fase esquecida em MONTANDO (um tile
em serie lancou e o laco saiu sem `fimLaco`): os lotes sao descartados no `abrir` seguinte; os tiles deles nao ticam
naquele tick, como os tiles depois do erro no original.

### 9.5 Politica (secao 9 do plano)

Falha (tile, adiado) segue `ao_falhar`; violacao de guarda ou invariante da fase quebrado segue `ao_violar`. `parar`
(padrao): o `fimLaco` lanca um `ReportedException` com o crash-report ("Ticking block entity" com os dados do tile, ou
"violacao na fase"), pelo caminho de crash de hoje (crash-report e parada que salva). `rebaixar`: a dimensao fica em
serie ate o proximo boot (o `estado` diz o motivo). Qualquer valor que nao seja `rebaixar` escrito por extenso para.
Se o crash-report nao puder ser montado (o `CrashReport.makeCrashReport` lancar), o `parar` vale assim mesmo: o `fimLaco`
lanca um `RuntimeException` simples com a mesma causa (revisao do passo 4, m1; antes a falha virava "erro nosso" e a
parada era engolida).

### 9.6 Por que o modo sombra continua sem mudar o jogo

O codigo que muda o jogo neste modo fica so em `OrdenadaJogo` e `GanchosAdiados`. O `TesteParaleloBytecode` confere: o
`despachar` so devolve falso ou o resultado de `OrdenadaJogo.despachar`; `GanchosParalelo` so chama `despachar`,
`novoTick` e `fimDoLaco` de `OrdenadaJogo` logo depois de testar o campo `ordenada` (escrito so no `abrir`, a partir do
modo do config); so `OrdenadaJogo` abre um lote (`loteDaVez` nao nulo, `Guardas.loteAberto` verdadeiro); cada "verdadeiro"
de `GanchosAdiados` vem depois de `Lote.adiar`; o "verdadeiro" do `callEvent` so depois de ler `Guardas.loteAberto`; as
chamadas ao jogo dessas duas classes estao numa lista revisada; os adaptadores do sombra continuam so lendo.

### 9.7 `injetar quebrar` (teste)

`hoshie paralelo injetar quebrar [x y z]`, so com `-Dhoshiestar.paralelo.teste=true`. No proximo tick com lotes, no fim do
laco e antes de os lotes rodarem (o tile ja foi despachado), a thread do servidor quebra em serie o tile pedido (ou o
primeiro bau com itens dos lotes) com `func_147480_a(x, y, z, false)`: o conteudo cai no chao, o bloco do bau nao. A
escrita suja o lote; o tile invalidado e pulado pela reconferencia. O log diz o que aconteceu ("foi pulado pela
reconferencia (nao ticou)", lote sujo, itens que cairam) e o `estado` tambem.

### 9.8 Comando e painel

`hoshie paralelo estado` em sombra_ordenada mostra, a mais: lotes e tiles do ultimo tick e somados, tempo da fase por tick
com lotes (lotes e juncao separados), adiados aplicados, lotes e tiles em serie na juncao, lotes sujos, pulados na
reconferencia, barrados pelo firewall no despacho, falhas, invariantes da fase, emergencias, lotes descartados e o
ultimo `injetar`.

### 9.9 Testes

- `TesteJuncao` (JDK 8 e 21): mundo falso com inventarios, chao, fila de eventos com a deduplicacao do `addBlockEvent`,
  ticks agendados com o "primeiro vence" do original e sons. Casos (molde dos casos adversarios do orcamento; cada um
  confere a contagem de itens antes e depois, nenhum tile duas vezes, nenhum adiado duas vezes): todos limpos (igual ao
  tick em serie); deduplicacao; lote sujo antes da fase; tile quebrado depois do despacho; invalido sem sujar; tile
  substituido por outro objeto (pulado so no lote em serie); excecao no meio (antes, depois do movimento, depois dos
  adiados), no primeiro e no ultimo item, num lote sujo, ao aplicar um adiado; violacao; ordem por chave; reentrada;
  erro nosso no meio e antes; fechos `MORREU`, `ABORTADO` e lote nao fechado; lote vazio; lote sujo no meio (ordem
  dentro da regiao); ordem independente da thread (60 ordens sorteadas); diferencial sorteado contra o tick em serie (400
  mundos); politica. Prova dos testes: sete defeitos postos a mao no nucleo (cursor de item e de adiado, adiados depois do
  resto, resto so pelo fecho, violacao que nao para, sem reconferencia, sem reconferencia forte), todos pegos.
- `TesteCongelar` (com e sem a propriedade): `injetar quebrar` so existe com ela; pedido anotado, tirado uma vez,
  devolvido.
- `TesteRecusas`: `sombra_ordenada` aceito com a parada ligada, recusado sem ela; `paralelo` ainda nao implementado.
- `TesteParaleloBytecode`: as conferencias da secao 9.6, mais as de antes.
- `TesteBytecodeFinal` no dump do jar deste passo (resultado abaixo).
- Revisao do passo 4 (I1: nenhum teste pegava defeito nos adaptadores do jogo). `TesteAdiadosJogo` (ligado e desligado) e
  `TesteFaseJogo`, em `src/test/paralelo/jogo/`, rodam contra as classes REAIS do build rapido e as bibliotecas do
  servidor (`HOSHIE_LIBS`), sem subir o servidor; objetos do jogo criados sem construtor, so com os campos que os metodos
  tocam. `TesteAdiadosJogo`: os 15 pontos adiados chamados pelos metodos do jogo (inclusive `playSoundAtEntity` e
  `playSoundToNearExcept`, com o barramento do Forge de verdade), fase fechada (referencia) contra lote aberto + juncao:
  avisos na ordem e com os mesmos argumentos, eventos de bloco deduplicados, ticks agendados (o primeiro vence; chunk
  ausente diferencia `scheduleBlockUpdate` de `func_147446_b`), saida da fila de descarga, outra thread nunca adia;
  `callEvent` sem ouvinte (volta sem o monitor), com ouvinte (espera o monitor e chama o ouvinte), assincrono (nao e pulado:
  o original recusa) e sem lote; desligado, tudo roda na hora mesmo com o lote aberto. `TesteFaseJogo`: `OrdenadaJogo` de
  ponta a ponta com um firewall falso (`com.akashic.worldfirewall.WorldMutationGuard`, so no classpath do teste):
  despacho, reconferencia fraca (invalido, sem mundo) e forte (outro objeto no chunk, chunk fora do mapa, sem pedir carga),
  lote sujo, ordem por chave, emergencia, fase esquecida, violacao e excecao de tile com `parar`, e o `parar` chegando ao
  jogo pelo `GanchosParalelo.fimLaco` mesmo sem crash-report. Prova: `src/test/paralelo/mutacoes-jogo.py` (22 defeitos
  postos de proposito nos adaptadores, um por vez; todos pegos; uns 4 min). Sem o build rapido ou sem `HOSHIE_LIBS`, o
  script pula os dois com aviso.

### 9.11 Desvios do plano e limites conhecidos (revisao do passo 4)

- `tilesTicked` nao e somado na juncao (plano, secao 4): os tiles dos lotes nao entram nessa conta do vanilla.
- Os invariantes da fase sao conferidos depois dos lotes, nao no fim da juncao (plano, secao 3).
- `rebaixar` registra o motivo, mas nao o conteudo do tile que falhou (plano, secao 9).
- `hoshie paralelo modo` ainda nao muda nada e diz "modo efetivo: nenhum", embora o `sombra_ordenada` ja mude o tick.
- A reconferencia antes do tick nao repete o `blockExists` do laco: no lote limpo confere so `isInvalid` e o mundo (o que
  o plano pede). Descarregar um chunk so poe os tiles numa fila (`Chunk.onChunkUnload`), nao os invalida nem suja o lote;
  um chunk descarregado na hora por um tile em serie, entre o despacho e o lote, deixaria o tile ticar, e o laco original
  o pularia. Leitura de codigo; nao visto no jogo.
- `worldAccesses` tem 12 metodos que o percorrem; 11 tem guarda. Faltam `World.setLightValue` (grava luz e avisa direto) e
  `func_147450_X`. Nenhuma classe da onda 1 chega neles; precisam de guarda antes de liberar classe que mexa em luz
  (fornalha).
- Emergencia por erro nosso no despacho: os tiles seguintes da mesma regiao ticam no laco ANTES dos ja despachados, que so
  ticam no fim do laco (a ordem dentro da regiao inverte). Nao importa para a onda 1; importa para o funil.
- `injetar quebrar` diz "foi pulado" quando QUALQUER tile do lote foi pulado depois da quebra, e "N itens cairam no chao"
  e a contagem de dentro do tile antes da quebra: os dois sao indicacao, nao medida do tile alvo.

### 9.10 Resultado no modpack (2026-11-27, carga sintetica: indicacao)

Copia nova de `prepare-test-env.sh` (`degrauB/p4/copia`), 127.0.0.1, modo offline; mundo base copiado para a copia a cada
boot (a base nunca e alterada: e o backup); todos os boots parados com `stop`, nenhum `kill`. Tres ilhas longe do spawn,
cada uma mantida carregada por um bot parado; so a ilha A fica elegivel (as outras tem tiles de mod gerados ou tiles na
borda da area carregada; tentei limpar e nao deu), entao ha um lote por tick, com 12 tiles (os 6 da mini-base e 6 abaixo
de y = 0, na camada funda do AkashicBellow, que as ferramentas de mundo nao leem).

- **Classes finais** (boot com dump do jar deste passo): `TesteBytecodeFinal` 37/37; nos tres metodos de som e no
  `scheduleBlockUpdateWithPriority` o "exit"/"decrement" dos mixins aparece tambem antes do `return` novo.
- **Desligado x sombra_ordenada** (D4 x O4, congelados no tick 48432): mini-bases das tres ilhas 0 diferencas; chunks da
  ilha A (a do lote) 0 diferencas; montagem do spawn (707 posicoes) 1 diferenca, no funil da montagem hostil
  (`TransferCooldown`). Esse funil fica numa regiao em serie e o valor dele muda de boot para boot em todo modo com o
  recurso ligado (2 e 3 em duas execucoes sombra_ordenada, 8 em sombra) e tambem com **so o orcamento ligado** em modo
  medir e o paralelo desligado (5); fica igual (4) nas duas execucoes sem `hoshiestar.yml`. Entao a variacao vem com o
  orcamento, nao dos lotes nem da juncao (mecanismo nao verificado). O resto do mundo varia como entre duas execucoes desligadas (D4 x D4b: 40 diferencas; D4 x O4: 47;
  cristais, blocos de mod no chao, idade de itens e entidades).
- **Contadores** (O4, ~3.650 ticks com lote): falhas 0, invariantes 0, "seria ilegal" 0, erros nossos 0, emergencias 0;
  adiados aplicados 183 (eventos do bau de ender); fase 0,09 ms por tick com lote (lotes 0,05, juncao 0,02).
- **Conservacao**: itens das tres ilhas iguais na base, depois de O4 (parado com `stop`) e depois de religar (O4b, a
  partir do mundo salvo de O4).
- **`injetar quebrar`** (O4b): bau com 5 lingotes, ja despachado; quebrado em serie; lote sujo, rodou em serie na juncao
  (12 tiles); o bau foi o unico pulado pela reconferencia (nao ticou); os 5 lingotes no chao; contagem das ilhas igual.
