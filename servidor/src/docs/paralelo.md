# Paralelo (Degrau B): fase paralela sincronizada com barreira

Estado deste documento: **passos 1 e 5 do plano** (`docs/09-plano-degrau-b.md`, secoes 10 e 11). Existem os pre-requisitos
sem fase (as chaves do `hoshiestar.yml`, as recusas de boot, o comando `hoshie paralelo` em esqueleto e a correcao do
lado do FML) e o **nucleo de concorrencia sem Minecraft** (secao 6: threads de regiao, barreira, pedido delegado, portao,
abortar e encerrar), testado sozinho com regioes falsas. **O nucleo ainda nao esta ligado ao jogo**: nenhuma classe do
servidor o chama, nenhuma thread de regiao e criada, nao ha gancho no tick nem junção (passos 3, 4 e 6).
Com tudo no padrao (`paralelo.ligado: false`) o servidor se comporta como antes.

Termos: *thread* = uma linha de execucao do programa; *thread do servidor* = a que roda o laco de ticks; *tile* = bloco
com logica propria (bau, mesa de encantamento); *regiao* = grupo de chunks vizinhos que o orcamento (Degrau A) mede junto;
*sombra* = modo em que tudo roda em serie e so se conta o que seria ilegal na fase paralela.

## 1. Chaves (secao `paralelo:` do `hoshiestar.yml`, ultima do arquivo)

| Chave | Padrao | Faixa / valores | O que e |
|---|---|---|---|
| `ligado` | `false` | true/false | Interruptor. `false` = comportamento original; nenhum gancho roda. Lido so no boot. |
| `modo` | `sombra` | `sombra`, `sombra_ordenada`, `paralelo` | Os tres modos do plano (secao 2). Os dois ultimos exigem `parada.ligado: true`. |
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

**O que o boot confere hoje**: so o que o config sabe (itens 1 a 4 e 7). Os itens 6 e 8 a 12 dependem de fatos do jogo
(dimensoes carregadas, classe do `world.rand`, transformadores, observadores, firewall). A funcao ja os recebe
(`Recusas.Fatos`), mas **ninguem os coleta ainda**. Por isso, hoje, **`paralelo.ligado: true` sempre da "RECUSADO"** pelo
item 5, e `Paralelo.aceito()` e sempre falso: a fase nao pode abrir sem essas seis recusas. Para o recurso ficar aceito,
quem implementar a coleta (no boot do servidor, depois de os mundos e os mods carregarem) tem de preencher todos os fatos,
marcar `jogoConferido`, chamar `Recusas.avaliar` de novo e guardar o resultado com `Paralelo.configurar`. **Dono da coleta:
o plano ainda nao diz** (a linha 3 da secao 11 nao cita); pedido ao coordenador: po-la no "pronto quando" do passo 3 (ou,
no maximo, do 6, antes de abrir a fase), com um teste no boot de verdade que prove uma recusa com o fato preenchido.
"Jar de terceiros com SHA diferente tira so a classe afetada" (secao 10) e regra por classe, do passo 3; nao e recusa de boot.

## 3. Comando `hoshie paralelo`

Esqueleto: so informa. Console, RCON e operador de nivel 4, como o resto do `hoshie`.

- `hoshie paralelo` ou `hoshie paralelo estado`: ligado/desligado, modo configurado, valores das chaves e a frase "fase ainda
  nao implementada" (regioes elegiveis, tempos e contadores virao com os passos 3 a 6);
- `hoshie paralelo motivos [n]`: os motivos de recusa do boot (ate `n`; hoje, com o recurso ligado, sempre ha pelo menos o
  dos fatos do jogo nao conferidos). Os motivos de inelegibilidade de regiao (com uma
  posicao de exemplo) virao com o passo 3;
- `hoshie paralelo modo [m]`: sem argumento mostra o modo; com argumento valida o nome e responde que mudar o modo em
  execucao ainda nao esta implementado (nada muda). A regra "so para modo mais seguro" entra com a fase.

Os subcomandos so de teste (`congelar`, `injetar`) e a propriedade `-Dhoshiestar.paralelo.teste=true` ainda nao existem
(passos 2 e 6).

`hoshie status` ganha a linha do recurso `paralelo`: `desligado`, `ligado no config, mas RECUSADO no boot (...)` ou
`ligado no config, modo ..., sem recusa; fase ainda nao implementada` (esta ultima so depois que a coleta dos fatos do jogo
existir).

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
  `ehThreadDeRegiao`. Desligado, o resultado e sempre o original, mesmo que um plugin crie uma thread com esse nome.
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
  teste original e nenhuma outra classe de `net/minecraft` ou `cpw` cita `hoshiestar/paralelo`.

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
6. Thread estranha no `portao` com a fase rodando espera a barreira. Quem esperava e liberado quando a barreira passa, e a
   fase seguinte so e publicada depois de ele seguir (sem isso, com fases seguidas, ele poderia nunca passar).
7. Excecao unica do portao: a thread estranha segura o monitor indicado e um pedido declarou que precisa dele. A thread
   do servidor para todos os trabalhadores, a thread estranha passa, e a operacao so roda quando ela soltar o monitor.
8. `abortar(prazo)`: ninguem pega outro item, os pedidos pendentes ainda sao atendidos, a chamada volta no prazo dizendo
   quem ficou no meio de um item, e o portao abre. `encerrar(prazo)`: o mesmo, e as threads acabam. Nenhuma das duas
   esperas e largada por interrupcao de quem chama: a espera continua ate o prazo e o sinal e reposto na saida.
9. Nenhuma thread e interrompida pelo nucleo, e ele nunca chama codigo de fora com a trava dele na mao.

### 6.4 Limites conhecidos

- **O portao so segura a entrada.** Uma escrita de thread estranha que passou fora da fase e ainda esta em curso quando a
  fase seguinte abre corre contra os trabalhadores (hoje ela corre contra a thread do servidor). O plano aceitou assim
  (secao 3: "espera a barreira e segue").
- **A excecao do monitor depende de quem declara.** O pedido tem de dizer qual monitor a operacao toma, e ela tem de
  toma-lo logo na entrada (o nucleo roda a operacao dentro de `synchronized (monitor)`). Operacao que precisa de um monitor
  nao declarado, ou de outra trava qualquer que uma thread parada no portao segura, trava: a thread do servidor fica na
  operacao e a barreira nao passa. O aviso de quem pediu, a cada fatia, mostra a pilha da thread do servidor.
- **O trabalhador nao pode segurar monitor ao pedir** (as guardas ficam na entrada dos metodos). Se segurar um que a
  operacao precisa, trava do mesmo jeito.
- Thread de regiao morta nao e recriada: a fase em curso termina como falha e nenhuma outra abre.

### 6.5 Testes

`teste-paralelo.bash` roda os tres nos JDKs 8 e 21. Com `--rapido` (o que o `servidor/test.sh` normal passa) a dose e curta;
sem a opcao, os testes recebem `-Dhoshiestar.teste.completo=true`.

- `TesteNucleoCasos`: casos montados, um por requisito da linha 5 do plano: estados da fase; ordem de pega; erro injetado;
  lote que dorme (rapido: 440 ms com fatia de 100 ms; completo: 20 s com fatia de 5 s), um despejo por fatia e ninguem
  interrompido; thread que morre (a unica, e uma de tres); pedido delegado (roda na thread do servidor e sozinho, excecao
  devolvida, dois juntos, aninhado, operacao que so termina na thread do servidor, espera longa, suja outro lote,
  trabalhador sem monitor pelo `ThreadMXBean`); portao (sem monitor, com monitor, com monitor e pedido nas duas formas,
  outro monitor, thread estranha que toma o monitor logo depois de a thread do servidor sair dele, confirmacao antes da
  fase seguinte com 64 threads, fases coladas); abortar (todos param, um preso, com pedido pendente, no meio de uma
  operacao com outro trabalhador parado na pausa, com um trabalhador no meio de um pedido e os outros ociosos, pela thread
  do servidor, em MONTANDO); encerrar (e com um
  preso; e chamado por uma thread com interrupcao pendente: thread ociosa 200 vezes, tres threads no meio de um item
  com uma que demora a acabar, e um preso); thread do servidor interrompida na barreira.
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
  fim, `encerrar`, uma vez em quatro chamado por uma thread ja interrompida. Confere: nenhum item junto com uma operacao
  ou com uma escrita pela excecao do monitor, nenhum item depois de `abortar` voltar vazio, cada item no maximo uma vez,
  nenhum despejo. Os casos montados pegam o defeito na intercalacao que alguem pensou; a sonda sorteia a intercalacao.

O caso "thread estranha que toma o monitor logo depois" precisa de uma ordem que nao se monta por fora: a thread estranha
tem de chegar a trava do nucleo antes da thread do servidor, que acabou de sair do monitor. O teste pega a trava interna
da `Barreira` por reflexao (campo `trava`); se o campo mudar de nome, o caso falha com `NoSuchFieldException`.

Os casos nao dependem de a maquina estar folgada (80 execucoes seguidas com quatro JVMs ao mesmo tempo, nos dois JDKs,
todas verdes). A prova dos proprios testes e por mutacao: `src/test/paralelo/mutacoes-nucleo.py <workspace>` poe 32
defeitos no nucleo, um de cada vez numa copia das fontes, e roda os testes rapidos; cada defeito tem de ser pego (os
32 sao pegos). Com `--sonda` roda so a `TesteNucleoSonda` contra cada defeito (o dente da sonda sozinha). Leva uns 25 a 45 minutos e nao roda dentro do `servidor/test.sh`: rode quando mexer no nucleo ou nos testes.

**Nao verificado**: nada disto rodou dentro do jogo; o custo por item do ponto seguro e da contagem (duas leituras e uma
escrita `volatile` por item) nao foi medido; o tamanho de pilha certo para as threads de regiao (o nucleo aceita o valor,
ninguem o escolheu); o comportamento com as classes do jogo sendo carregadas pela primeira vez numa thread de regiao.
