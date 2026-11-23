# parada: parada por SIGTERM sem crash e sem salvamento duplo (item 1.G, achado D-24)

Estado: implementado no fork, **desligado por padrão**. Revisado por um agente diferente do que
escreveu (veredito: aprovado com ressalvas, nenhum achado bloqueante) e **corrigido depois da
revisão** (seção 4.5). Testado fora do jogo (JDK 8 e 21) e no modpack real de 292 mods (cópia de
teste), a última rodada com o jar do commit `7e782e3f` do workspace (`sha256 86a61e72…`). As
correções pós-revisão mexem em código de concorrência: pela regra do projeto, merecem um novo olhar
de outro agente antes de o recurso ser **ligado** em produção (integrar desligado não tem risco).

Termos usados aqui:
- **SIGTERM**: o sinal que o sistema manda para um programa fechar (`kill`, `docker stop`, alguns
  painéis de hospedagem). É diferente do comando `stop` digitado no console.
- **Gancho de desligamento**: uma thread que a JVM dispara sozinha quando o processo vai sair. O do
  servidor é o `ServerShutdownThread`.
- **Thread do servidor**: a thread que roda o laço de tick (o "relógio" do jogo, 20 vezes por segundo).
- **Trava**: um objeto que só uma thread segura por vez (`synchronized`).
- **Vigia de travamento (watchdog)**: uma thread do Spigot que observa a thread do servidor e, se ela
  ficar sem ticar por `timeout-time` segundos (90 neste pacote), manda reiniciar o servidor.
- **SIGKILL**: "matar à força" (`kill -9`); o processo acaba na hora, sem salvar nada.

## 1. O que muda, em linguagem simples

**Hoje (desligado).** Quando chega um SIGTERM, o gancho de desligamento chama `stopServer()` direto,
na thread dele, **com o laço de tick ainda rodando** na thread do servidor. O gancho desliga os
plugins, salva e descarrega os mundos enquanto o jogo continua ticando em cima deles. No teste real
(caso c1b, seção 7) isso levou 15 s e terminou em erro no tick
(`NullPointerException ... DimensionManager.getWorld(int) is null`) e crash-report; no D-24 original o
erro era em `MinecraftServer.java:1016`. Depois do erro a thread do servidor chama `stopServer()` de
novo, em paralelo com o do gancho (dois salvamentos ao mesmo tempo). E o fechamento do salvamento em
thread do hodgepodge (`closeSession`, que grava `level.dat`, mapas e afins) não roda nesse caminho.

**Com o recurso ligado.** No SIGTERM o gancho só **pede** ao laço de tick que pare (o mesmo que o
comando `stop` faz) e **espera**. A thread do servidor termina o tick em curso, sai do laço e faz a
parada normal: avisa os mods, salva jogadores e mundos, fecha o que tem de fechar e chega na saída. O
gancho então não salva de novo. Resultado nos testes reais: um único salvamento, na thread do
servidor, sem crash-report, processo fora em 9,5 a 15 s.

Se o servidor estiver travado e o laço não parar dentro do limite (T1), o gancho desiste de esperar e
salva ele mesmo, **como no código original, só que T1 segundos mais tarde**; se a thread do servidor
destravar no meio, ela espera esse salvamento acabar e não salva de novo. Esse atraso tem
consequência para o prazo que o painel dá antes de matar o processo: ver a seção 2.

Se quem pede a saída é o **vigia de travamento** do Spigot (o "watchdog", que reinicia o servidor
quando a thread do servidor fica muito tempo sem ticar; ligado no `spigot.yml` do pacote), o gancho
reconhece isso e salva na hora, como hoje, sem esperar T1.

O comando `stop` não muda: já era o caminho certo. O recurso só garante, também nele, que nunca
rodem dois salvamentos de parada ao mesmo tempo.

## 2. Chaves do `hoshiestar.yml`

| Chave | Padrão | O que faz |
|---|---|---|
| `parada.ligado` | `false` | O interruptor. Desligado: código original do Crucible, sem mudança de comportamento. |
| `parada.espera_laco_segundos` (T1) | `60` | Quanto o gancho espera o laço de tick parar depois do SIGTERM (1 a 3600). Passou disso: salva pelo caminho original e avisa no log, com a pilha da thread do servidor. **Com o servidor travado o salvamento só começa depois desse tempo.** Com observação (`hoshie observar`) ou medição de pé na hora da parada, use 20 ou mais: a thread do servidor espera a gravação delas (até 10 s + 5 s) antes de parar. |
| `parada.espera_salvar_segundos` (T2) | `600` | Quanto alguém espera um salvamento de parada que já começou em outra thread (1 a 7200), contado do início desse salvamento. Passou disso: quem esperava desiste **sem** salvar ao mesmo tempo. **Se quem esperava era o gancho, a JVM sai e o salvamento em curso é cortado no meio.** |
| `parada.folga_saida_segundos` (folga) | `30` | Depois de o mundo estar salvo, quanto o gancho espera a thread do servidor terminar o fechamento e chegar na saída (0 a 600). Só vale quando ela já saiu do laço de tick; com ela travada num tick não há essa espera. |

Valor fora da faixa: usa o padrão e avisa no log. O arquivo é lido uma vez, no boot. Os comentários
do `hoshiestar.yml` dizem o mesmo (num arquivo já existente os comentários antigos ficam: o arquivo
completo não é regravado).

### Prazos: o que o painel de hospedagem precisa esperar antes do SIGKILL

SIGKILL é o "matar à força" (`kill -9`; o `docker stop` manda sozinho 10 s depois do SIGTERM; o
systemd, 90 s). Depois dele nada mais roda. **Antes de ligar este recurso em produção é preciso saber
como o painel para o servidor e quanto ele espera.** Como a produção é parada **não foi verificado**.

| Situação no SIGTERM | Quando o salvamento começa | Tempo total até o processo sair | Comparação com hoje (desligado) |
|---|---|---|---|
| Servidor saudável | no fim do tick em curso | salvamento + fechamento (9,5 a 15 s nos testes, mundo ocioso) | hoje: parecido, mas com erro no tick e risco de dois salvamentos |
| Servidor **travado** (laço não para) | **só depois de T1** | **T1 + salvamento pelo gancho** (com T1 = 60: uns 70 s no mundo de teste) | **hoje o salvamento começa na hora.** Se o painel matar antes de `T1 + salvamento`, com o recurso ligado **nada é salvo**, onde hoje teria salvo |
| Travado, e a thread do servidor destrava durante o salvamento do gancho | depois de T1 | T1 + salvamento + fechamento dela (até a folga) | hoje: dois salvamentos ao mesmo tempo |
| Travado; o salvamento do gancho sai com exceção e a thread do servidor (destravada) assume | depois de T1; de novo quando ela assume | T1 + salvamento que falhou + salvamento dela (até T2) + fechamento (até a folga) | hoje: a JVM sai no meio do salvamento dela (até o achado P1 o recurso ligado também saía; seção 4.6) |
| Reinício pelo vigia de travamento (watchdog do Spigot, `timeout-time` = 90 s neste pacote) | na hora (o gancho reconhece o vigia) | salvamento pelo gancho (7 a 8 s no teste) | igual a hoje |
| Comando `restart` ou mod/plugin que chama `System.exit` na thread do servidor | na hora | salvamento pelo gancho | igual a hoje |
| Salvamento de parada passa de T2 | — | T2 | hoje não há limite; **ligado, a JVM sai em T2 e corta o salvamento** (parte do mundo nova, parte velha) |

Regras práticas:
- **T1 tem de ser menor que (prazo do painel − tempo de um salvamento de parada).** O padrão de 60 s
  já passa dos 10 s do `docker stop`. Com T1 curto demais, um tick demorado de um servidor vivo é
  tratado como travamento e cai no caminho original (o de hoje): é o lado seguro do erro.
- **T2 tem de ficar bem acima do salvamento de parada mais demorado.** Medir numa cópia com o mundo
  de produção (o mundo de teste, ocioso, salva em 3 a 8 s na thread do servidor e em 6 a 14 s no
  gancho, que é mais lento porque o log4j está fechando ao mesmo tempo).
- O salvamento feito pelo gancho (caminhos "travado", "vigia" e "restart") foi 2 a 3 vezes mais
  lento que o da thread do servidor nos testes do revisor (12,5 e 14,0 s contra 3,2 a 7,8 s).

Linha no `hoshie status` (exemplo real):
`parada: ligado - ... | estado: ocioso; limites: laco 60 s, salvar 600 s, folga 30 s; salvamentos de parada: 0; chamadas puladas: 0`

## 3. Onde está o código

| Arquivo | O que é |
|---|---|
| `src/main/java/hoshiestar/parada/MaquinaDeParada.java` | A máquina de estados. Não usa nada do Minecraft; é a mesma classe no servidor e no teste. |
| `src/main/java/hoshiestar/parada/Parada.java` | Fachada estática: interruptor, tempos, registro, log. Nenhum método deixa escapar exceção. |
| `src/main/java/hoshiestar/parada/ComandoTesteParada.java` | Subcomando de teste `hoshie parada-travar <s>` (segura a thread do servidor por até 120 s). Vai dentro do jar, mas só é registrado com `-Dhoshiestar.parada.teste=true` e só o console usa. **Nunca ligar essa propriedade em produção.** |
| `patches/net/minecraft/server/MinecraftServer.java.patch` | `stopServer()`: `Parada.entrar(...)` no começo e `Parada.sair(ok)` num `finally`. O método `run()` **não foi tocado**. |
| `patches/net/minecraft/server/dedicated/DedicatedServer.java.patch` | `systemExitNow()`: `Parada.saindo()` antes do `System.exit(0)`. |
| `src/main/java/org/bukkit/craftbukkit/v1_7_R4/util/ServerShutdownThread.java` | O gancho: `Parada.ganchoAntes(...)` antes do `stopServer()` dele e `Parada.ganchoDepois(...)` depois. |

Todas as linhas em arquivo do upstream estão em blocos `// Hoshiestar start` / `// Hoshiestar end`, e
cada chamada nossa está dentro de `try/catch (Throwable)` que cai no caminho original.

## 4. A máquina de estados

### 4.1 Estados e quem muda cada um

| Estado | Significa | Entra por | Em qual thread |
|---|---|---|---|
| `OCIOSO` | nenhum salvamento de parada começou, ou o último saiu com exceção **fora** da thread do servidor (outra thread pode tentar de novo, uma de cada vez) | estado inicial; `sair(false)` | a dona do salvamento que falhou (gancho ou terceira thread) |
| `SALVANDO` | uma thread (a **dona**) está dentro do corpo de `stopServer()` | `entrar(true)` com o estado `OCIOSO`; ou com `FALHOU`, só se quem chama é a thread do servidor | quem chamou `stopServer()`: a thread do servidor (normal), o gancho (desistência, vigia, `System.exit`) ou outra |
| `FEITO` | um salvamento de parada terminou sem exceção (estado final) | `sair(true)` | a dona |
| `FALHOU` | o salvamento da **própria thread do servidor** saiu com exceção. Ela segue para o fechamento (avisos de "servidor parado" aos mods) e para a saída; **nenhuma outra thread salva** (rodaria junto com esse fechamento). Só ela mesma pode tentar de novo | `sair(false)` na thread do servidor | a thread do servidor |

A máquina só é mexida com a trava na mão. Quem é "a thread do servidor" vem da fachada
(`hoshiestar.api.ThreadServidor`, definida na primeira linha de `MinecraftServer.run()`).

Bandeiras, fora do estado:
- `saida`: falso → verdadeiro, uma vez, pela **thread do servidor**, em `systemExitNow()`, logo antes
  do `System.exit`. Diz ao gancho "não há mais nada rodando nela".
- `servidorNaParada`: falso → verdadeiro quando a thread do servidor chama `stopServer()` (logo, já
  saiu do laço de tick). Decide se o gancho espera a folga depois de salvar (E3).
- `desistiu`: só informativa (o gancho passou de T1).

Respostas de `entrar(vaiSalvar, naThreadDoServidor)`, chamado no começo de `stopServer()`
(`vaiSalvar` é a mesma condição do `if` original: não está apagando o mundo, o servidor chegou a
iniciar, ainda não foi marcado como parado):
- `ORIGINAL` (0): a máquina não se mete. Acontece quando `vaiSalvar` é falso (o corpo original não
  faria nada) e na reentrada (`stopServer()` chamado de dentro dele mesmo, na mesma thread).
- `DONO` (1): rode o corpo original e chame `sair(ok)` no `finally`.
- `PULAR` (2): não rode o corpo: outra thread já salvou; ou o salvamento da thread do servidor falhou
  e quem chama é outra thread; ou outra está salvando há mais de T2.

O gancho, em `ganchoAntes`, decide nesta ordem:
1. não há thread do servidor viva (ou o gancho roda nela): caminho original;
2. `saida` já é verdadeiro (parada normal por `stop`): não espera nada;
3. o servidor ainda não iniciou (estado `SERVER_STARTED` do FML não atingido) e a máquina está
   `OCIOSO`: **boot**; não pede a parada nem espera, a JVM sai na hora como hoje (ver seção 9);
4. a própria thread do servidor está parada dentro do `System.exit` (um mod, plugin ou o comando
   `restart` chamou no meio de um tick; visto pela pilha dela, inteira): esperar seria inútil; o
   gancho salva como no original;
5. a máquina está `OCIOSO` e quem chamou o `System.exit` foi o **vigia de travamento** (alguma thread
   parada dentro de `Runtime.exit` com a classe `org.spigotmc.WatchdogThread` na pilha): a thread do
   servidor já está travada há mais que o limite do vigia; o gancho não espera T1 e salva como no
   original;
6. senão: pede a parada do laço (`initiateShutdown`) e espera (4.2).

### 4.2 Todas as esperas e seus limites

| # | Quem espera | O que espera | Limite |
|---|---|---|---|
| E1 | qualquer thread em `entrar`, com outra dona salvando | o fim desse salvamento | `início do salvamento + T2` (não a chegada de quem espera: quem já esperou T2 por esse salvamento não espera de novo) |
| E2 | o gancho em `ganchoAntes` | a thread do servidor chegar na saída (ou terminar) | depende só do estado: `OCIOSO`: `pedido + T1`; `SALVANDO`: `início do salvamento + T2`; `FEITO`: `fim do salvamento + folga`; `FALHOU`: o maior entre `pedido + T1` e `fim do salvamento + folga`. Limite duro: `pedido + T1 + T2 + folga` |
| E3 | o gancho em `ganchoDepois` | depois do `stopServer()` dele: (1) outra thread `SALVANDO` (ex.: o salvamento do gancho saiu com exceção e a thread do servidor assumiu); (2) `FEITO` ou `FALHOU` **e** a thread do servidor já passou pelo `stopServer()`: ela chegar na saída; (3) `OCIOSO` com ela já no `stopServer()`: ela assumir (acabou de acordar em E1). O estado é reavaliado a cada volta | (1) `início do salvamento + T2`, como E1; (2) `fim do salvamento + folga`; (3) `chamada + folga`. Limite duro: `chamada + T2 + 2 × folga` |

**Nenhuma espera é sem limite.** As três usam `trava.wait(ms)` em passos de no máximo 50 ms, com
`ms >= 1` (nunca `wait(0)`, que é "para sempre"), e recalculam o prazo com `System.nanoTime()` a cada
volta. E1 tem prazo fixo a partir de um instante já passado. Em E2 e E3 o prazo muda quando o estado
muda, mas cada valor é limitado e o limite duro corta qualquer sequência de mudanças. Interrupção da
thread não encurta nem alonga a espera (a bandeira é devolvida no fim). Além do passo de 50 ms, quem
espera é acordado por `notifyAll` em `sair` e em `saindo` (há teste que prova isso com passo de 20 s).

O pior caso do gancho inteiro é E2 + E1 + o salvamento do próprio gancho (sem limite, como hoje: é o
corpo original) + E3 = `(T1 + T2 + folga) + T2 + salvamento + (T2 + 2 × folga)`. O termo `T2` de E3
só aparece quando o salvamento do gancho saiu com exceção e a thread do servidor assumiu (seção 4.6).

O que **não** tem limite e já não tinha: o corpo de `stopServer()` em si (plugins, mods, disco).

**O que acontece quando um limite estoura:**
- T1 (laço não parou): o gancho salva ele mesmo, só então (caminho original, atrasado em T1).
- T2 com a thread do servidor salvando e o gancho esperando: o gancho desiste e **não** salva junto;
  a JVM sai e **o salvamento em curso é cortado no meio**. O aviso no console diz isso. É o preço de
  não ter espera sem limite; por isso T2 tem de ser folgado (seção 2).
- T2 com o gancho salvando (desistência) e a thread do servidor esperando: ela segue para o
  fechamento sem salvar; o `System.exit` dela fica parado até o gancho terminar, então o salvamento
  do gancho **não** é cortado, mas o fechamento dela roda junto com ele.
- Folga: a JVM sai com a thread do servidor ainda no fechamento (o mundo já está salvo).

**Por que o gancho não espera a folga com a thread do servidor ainda num tick** (E3, casos 2 e 3,
exige `servidorNaParada`; o caso 1, alguém salvando, não exige: esperar não deixa ninguém tocar nos
mundos que já não tocaria): no teste real do vigia (caso s2), com a espera, a thread do servidor destravou
23 s depois do salvamento do gancho, terminou o tick em cima dos mundos já descarregados e gerou
erro no tick e crash-report (com tudo já salvo). Sem a espera a JVM sai logo depois do salvamento
do gancho, exatamente como no código original (caso s2b: sem crash-report).

### 4.3 Por que não há ciclo de travas

Só existe **uma** trava nossa (`MaquinaDeParada.trava`). Com ela na mão o código só lê e escreve
campos da própria máquina, lê o relógio, chama `Thread.isAlive()` e `wait/notifyAll` da própria
trava. Nunca chama, com a trava na mão: o corpo de `stopServer()`, o pedido de parada, o log, o exame
de pilhas (`getStackTrace`, `getAllStackTraces`) ou qualquer código de fora. Quem espera usa `wait`,
que solta a trava. Logo nenhuma thread segura esta trava enquanto pede outra, e um ciclo precisa de
pelo menos duas.

### 4.4 As duas correções obrigatórias do conferente

- **(a) A thread do servidor espera o salvamento em curso também no caminho de desistência.** A
  condição de E1 não olha a bandeira `desistiu`. Teste isolado "travado" e casos reais c7, r4 e s3: o
  fechamento (`closeSession`, aviso de "servidor parado") só começou depois do fim do salvamento do
  gancho.
- **(b) Depois de `FEITO` o gancho espera só a folga, não T2.** Teste isolado "folga": com T2 = 5 s
  e folga = 250 ms, o gancho saiu 350 ms depois do SIGTERM.

### 4.5 Correções depois da revisão independente

| Achado do revisor | O que foi feito |
|---|---|
| I1: servidor travado, o salvamento só começa depois de T1; o doc dizia que o prazo era igual a hoje | doc (seção 2) e comentários do yml corrigidos, com a tabela de prazos; **no código**, a saída pedida pelo vigia de travamento não espera mais T1 (ordem 5 de `ganchoAntes`) |
| I2: passado T2 a JVM sai e corta o salvamento em curso | escrito no doc, no comentário do yml e no aviso do console; teste em JVM de verdade guarda esse comportamento |
| I3: exceção no salvamento da thread do servidor depois de T1 fazia o gancho salvar de novo durante o fechamento dela | estado novo `FALHOU`: depois de uma falha na thread do servidor nenhuma outra thread salva; o gancho só espera a saída |
| M1: dois mutantes sobreviviam | testes novos: T2 medido (o `stopServer()` do gancho volta na hora) e avisos com passo de 20 s |
| M2: folga inteira com a thread do servidor travada | o gancho não espera a folga se ela nem chegou ao `stopServer()` |
| M3: só 12 quadros da pilha | pilha inteira |
| M5: contadores do gancho fora da trava | dentro da trava |
| M4, M6, M7 | sem mudança de código (ver seções 5, 8 e 10) |

### 4.6 Correção depois da revisão de concorrência (achado P1)

**O defeito.** Thread do servidor travada; o gancho desiste em T1 e salva; ela destrava e espera em E1;
o salvamento do gancho sai com exceção (o caso provável: ela voltou a ticar em cima dos mundos que o
gancho descarregava); o estado volta a `OCIOSO`, ela vira dona e salva sozinha (o caminho bom). Mas o
gancho chegava em `ganchoDepois`, via `SALVANDO` (ou ainda `OCIOSO`), voltava na hora e a JVM saía:
**salvamento cortado no meio**, fora do caso documentado ("passou de T2"). O mesmo acontecia se, durante
a espera de `ganchoDepois`, o estado ia de `FALHOU` para `SALVANDO` (ela tentando de novo). Não era
regressão (o original também sai), e vinha da primeira versão.

**A correção** (sugestão do revisor): `ganchoDepois` reavalia o estado a cada volta. Com outra thread
`SALVANDO`, espera até `início desse salvamento + T2`, o mesmo prazo de E1; depois vale a regra de
`FEITO`/`FALHOU` + folga. Com `OCIOSO` e a thread do servidor já no `stopServer()`, espera até
`chamada + folga` ela assumir. Limite duro `T2 + 2 × folga` desde a chamada. Continua com uma trava só
e sem espera sem prazo. Passado T2 com ela salvando, o aviso diz que o salvamento será cortado.

**Testes** (`TesteParadaMaquina`): "exceção no salvamento do gancho" agora exige que o gancho termine
depois do salvamento da thread do servidor; "gancho falha, servidor assume" (a, b, c) e
"FALHOU -> SALVANDO". No código de antes: 10 falhas nesses casos. O `RevGanchoFalha` do revisor, na
classe corrigida: `ganchoDepois=true depois de 1400 ms; estado ao voltar=2 (FEITO)` (antes: 0 ms,
estado 1). Suíte completa nos JDKs 8 e 21: todos passaram (log `fork/correcoes2/parada-completo.log`
no scratchpad da tarefa).

## 5. Argumento de segurança

**Desligado = código original.** `Parada.ligado()` devolve falso; `entrar` devolve 0, o `if` de
pular não dispara, o corpo original roda inteiro dentro de um `try/finally` que não faz nada;
`saindo`, `ganchoAntes` e `ganchoDepois` voltam na primeira linha. O recurso não grava nada em disco:
ligar e desligar não deixa rastro. Conferido no caso c1/c1b (desligado + SIGTERM reproduz o defeito
de hoje) e no primeiro boot das cópias (desligado + `stop`).

**Ligado, o que está garantido** (testado na seção 6):
- G1. Nunca dois corpos de `stopServer()` ao mesmo tempo em threads diferentes. Um segundo chamador
  espera (E1) e depois pula.
- G2. Depois de um salvamento `FEITO`, nenhum outro roda.
- G3. Com o servidor saudável, o salvamento do SIGTERM roda na thread do servidor, depois do último
  tick. Os mundos só são descarregados por ela, que é quem os tica: a causa do D-24 some.
- G4. Nenhuma espera sem limite (4.2).
- G5. Falha nossa (exceção, classe que não carrega) cai no caminho original, com aviso limitado.
- G6. Nenhum salvamento começa em outra thread enquanto a thread do servidor faz o fechamento
  (avisos de "servidor parado", `closeSession`): depois de `FEITO` ninguém salva; depois de uma
  falha dela (`FALHOU`) só ela poderia salvar de novo. Vale enquanto T2 não estourar (4.2).

(Os números I1, I2 e I3 usados no resto deste documento são os achados do revisor, seção 4.5.)

**O que continua como hoje (risco conhecido, não piorado):**
- Caminho de desistência (servidor travado por mais de T1), vigia de travamento e `System.exit` na
  thread do servidor: o gancho salva com a thread do servidor possivelmente ainda num tick,
  exatamente como o código original. O recurso só acrescenta: aviso com a pilha, e a garantia G1. Se
  a thread do servidor destravar depois, pode dar erro no tick e crash-report (o D-24 de sempre), já
  com o mundo salvo.
- Exceção dentro do salvamento do gancho ou de uma terceira thread: o estado volta a `OCIOSO` e
  outro chamador pode tentar de novo, um de cada vez (hoje tentaria em paralelo).
- Exceção dentro do salvamento da thread do servidor: estado `FALHOU`; ela segue para o fechamento
  e ninguém salva de novo. É o que o comando `stop` já faz hoje (o gancho encontra `serverStopped`
  verdadeiro). O gancho só espera a saída (E2). Antes da correção do achado I3 o gancho podia,
  passado T1, salvar de novo no meio do fechamento.
- `serverRunning` é um campo comum (não `volatile`), como o comando `stop` já usa. Se a thread do
  servidor não enxergar a mudança, T1 cobre. Não mudei o campo para manter o original linha por linha.

**O que fica PIOR que hoje com o recurso ligado (aceito, documentado, depende de configuração):**
- Servidor travado + SIGTERM: o salvamento começa T1 depois. Se o painel matar o processo antes de
  `T1 + salvamento`, nada é salvo, onde hoje teria salvo (seção 2). Remédio: T1 menor que o prazo do
  painel menos o tempo de salvamento.
- Salvamento de parada mais longo que T2: a JVM sai em T2 e corta o salvamento; hoje o gancho faria
  o dele até o fim. Remédio: T2 folgado (padrão 600 s; máximo 7200 s).

**Convivência com os outros mods** (`run()` não foi tocado, então os pontos que eles miram estão
iguais):
- hodgepodge `MixinMinecraftServer_WorldDataSave` (embrulha a chamada `stopServer()` dentro de
  `run()` e depois chama `closeSession()`), `_ShutdownHook` e `_ClearServerRef` (antes da chamada
  `systemExitNow()` em `run()`): continuam casando; nenhum aviso de alvo não encontrado sobre
  `MinecraftServer` no log dos boots (as 8 linhas "was not found" são de classes de cliente, iguais
  com o recurso desligado). Com o recurso ligado o salvamento do SIGTERM volta a passar por dentro
  de `run()`, então o `closeSession()` roda (hoje não roda no SIGTERM). Indício no teste real: os
  `data/*.dat` têm hora 3 a 4 s **depois** do fim do salvamento, ainda antes da saída.
- Akashic-SaveRelief (`@Inject` no começo de `stopServer`): o código dele roda antes do nosso, como
  hoje, inclusive numa chamada que nós pulamos. Hoje ele já roda duas vezes numa parada normal (a
  segunda chamada, do gancho, não salva mas passa pelo começo do método). O revisor leu o código
  dele: `onServerStopping()` é repetível (marca "parando", espera um autosave em curso por até 60 s,
  fecha e esvazia a fila). Sem problema.
- Vigia de travamento do Spigot (`WatchdogThread`): é parado em `run()` antes do `stopServer()` da
  thread do servidor, como hoje. Quando ele dispara (thread do servidor sem ticar por `timeout-time`)
  chama `RestartCommand.restart()` → `FMLCommonHandler.exitJava`, na thread dele; o gancho o
  reconhece pela pilha (classe `org.spigotmc.WatchdogThread` + `Runtime.exit`) e salva na hora.
  Conferido no modpack real (casos s2 e s2b).
- O gancho usa o campo `server` dele, não `MinecraftServer.getServer()` (que o hodgepodge zera).

**Log durante a saída.** Com a JVM saindo, o log4j para de escrever (ele tem o próprio gancho): nos
testes reais nenhuma linha "Saving worlds" apareceu depois do SIGTERM, com o recurso ligado ou
desligado. Por isso, desde que o gancho começa, cada aviso da parada vai **também** direto para a
saída de erro do processo, com a marca `[direto/INFO]` ou `[direto/AVISO]`. É por essas linhas que
se confere um SIGTERM (seção 8).

**Regiões (Folia, D8 do estudo).** Nada de regiões foi implementado. A porta fica aberta: a máquina
não sabe o que é "parar o laço" nem "salvar"; recebe um pedido de parada (`Runnable`) e as chamadas
de entrada e saída do salvamento. Com regiões, o pedido passa a ser "parar de aceitar tarefas e
esperar as regiões na barreira", feito pela thread que coordena, antes do `stopServer()`; o gancho e
os limites continuam iguais.

## 6. Testes isolados

Um comando (JDK 8 e JDK 21):

```
HOSHIE_LIBS=<pasta libraries/ de um servidor> bash <workspace>/src/test/parada/teste-parada.bash <workspace> [--rapido]
```

Resultado em 2026-11-23, depois das correções da revisão: **194 verificações por JDK,
388 no total, 0 falhas** (log em `<scratchpad>/fork/testes/parada2/isolado-completo.log`). O
`servidor/test.sh` da base foi repetido e continua passando (315 verificações por JDK; log em
`test-base.log` na mesma pasta).

| Teste | O que prova |
|---|---|
| `TesteParadaConfig` (20) | chaves novas entram sozinhas, desligadas; arquivo completo não é regravado; faixas; registro e linha de estado |
| `TesteParadaMaquina` (155) | cenários com threads de verdade e tempos em ms, num servidor de mentira cuja cola copia a dos patches (lista abaixo) |
| `TesteParadaJvm` (20) | JVMs filhas de verdade, gancho real e `System.exit`: SIGTERM equivalente (código de saída 143, 1 salvamento na thread do servidor); `stop` (código 0); `System.exit` chamado pela thread do servidor (reconhecido; gancho ~300 ms em vez de T1 = 4 s); **saída pedida pelo vigia** com o servidor travado (reconhecido; o gancho salva na hora e não espera a folga) e o controle (saída pedida por uma thread qualquer: espera T1); **salvamento passando de T2** (o gancho leva T2 uma vez só, não salva junto, e a JVM corta o salvamento: comportamento documentado) |
| `TesteParadaEstresse` (10) | `stop` e SIGTERM ao mesmo tempo, 4000 repetições por JDK com atrasos aleatórios |
| `TesteParadaAdversario` (15) | estresse escrito pelo revisor: além de `stop` e SIGTERM, uma terceira thread chamando `stopServer()`, um segundo gancho, fechamento demorado e, numa segunda rodada, exceções no salvamento; 1500 repetições por rodada e por JDK |

Cenários do `TesteParadaMaquina`: `stop` normal (o gancho não salva de novo nem espera); SIGTERM
saudável (1 salvamento, na thread do servidor, 0 ticks durante o salvamento); **controle com o
interruptor desligado** (o servidor de mentira mostra o defeito: tick durante o salvamento e 2
salvamentos ao mesmo tempo, ou seja, o teste enxerga o problema); travado (desiste em T1, salva pelo
gancho, a thread do servidor só segue para o fechamento depois do fim do salvamento, e o gancho
espera esse fechamento); travado para sempre (T1 + salvamento, **sem** a folga); exceção no
salvamento da thread do servidor, cedo e **tarde** (depois de T1, com fechamento demorado: o gancho
não salva; teste A1 do revisor); falha antiga na thread do servidor (só ela pode tentar de novo; o
gancho espera T1); exceção no salvamento do gancho (a thread do servidor refaz, em sequência, **e o gancho só termina
depois desse salvamento**); **achado P1**: gancho falha e a thread do servidor assume, com
`ganchoDepois` chamado com ela já dona, antes de ela assumir, e com o salvamento dela passando de T2
(o gancho desiste no prazo e avisa do corte); `FALHOU -> SALVANDO` dentro da espera de `ganchoDepois`;
**outra thread salva com o servidor travado** (teste A8 do revisor); gancho duas vezes e dois
ganchos juntos; salvamento passando de T2 dos dois lados (com o tempo do `stopServer()` do gancho
medido); folga depois de salvar; **avisos que acordam quem espera** (passo de 20 s); SIGTERM no boot;
reentrada; interrupção; fachada desligada e fachada dizendo quem é a thread do servidor.

Estresse, última rodada (sementes fixas; números de harness, não de servidor):

| JDK | Teste | Repetições | Zero salvamentos | Dois ao mesmo tempo | Salvamento durante o fechamento | Maior espera do gancho |
|---|---|---|---|---|---|---|
| 8 | Estresse (`stop` x SIGTERM) | 4000 | 0 | 0 | não medido | 9 ms |
| 21 | Estresse (`stop` x SIGTERM) | 4000 | 0 | 0 | não medido | 8 ms |
| 8 | Adversário, sem exceções (366 com terceira thread) | 1500 | 0 | 0 | 0 | 80 ms |
| 21 | Adversário, sem exceções (366 com terceira thread) | 1500 | 0 | 0 | 0 | 80 ms |
| 8 | Adversário, com exceções (403 terminaram em `FALHOU`) | 1500 | não se aplica | 0 | **0** (antes da correção do I3: 201 em 3000 na rodada do revisor) | 94 ms |
| 21 | Adversário, com exceções (404 terminaram em `FALHOU`) | 1500 | não se aplica | 0 | **0** | 94 ms |

**Mutações** (estragar o código de propósito numa cópia e ver se algum teste acusa; script e logs em
`<scratchpad>/fork/testes/parada2/mut/`). As 11 abaixo são todas pegas; a primeira e a terceira são
as que sobreviviam na revisão (M1):

| Mutação | Pega por |
|---|---|
| `sair` sem `notifyAll` (m5 do revisor) | "avisos: sair() acordou quem esperava" |
| `saindo` sem `notifyAll` | "avisos: saindo() acordou o gancho" |
| T2 contado da chegada (m8 do revisor) | "T2: o stopServer() do gancho voltou na hora" e o teste de JVM |
| falha na thread do servidor volta a `OCIOSO` (desfaz a correção do I3) | 18 falhas ("exceção tardia", estresse com exceções) |
| com `FALHOU`, outra thread salva | "exceção tardia (a)", "falha antiga" |
| com `FALHOU`, nem a thread do servidor refaz | "falha antiga" |
| com `FALHOU`, o gancho só espera a folga | "falha antiga" |
| sem reconhecer o vigia | "vigia" (JVM) |
| qualquer `System.exit` tratado como vigia | "sigterm", "controle do vigia" (JVM) |
| `ganchoDepois` nunca espera | "travado: o gancho esperou o fechamento dela" |
| `ganchoDepois` sempre espera | "travado para sempre", "vigia" |

As outras 6 mutações do revisor que já eram pegas (thread do servidor não espera na desistência;
dois salvamentos depois de T2; `FEITO` esperando T2; falha virando `FEITO`; sem tratar reentrada;
`saindo` sem marcar a saída) foram repetidas no código novo e **continuam pegas**
(`mut/resumo-revisor.txt`). Total: 17 mutações, 17 pegas (rodadas só no JDK 8, estresse reduzido).

## 7. Testes no modpack real

São três rodadas: 7.1, do implementador (antes da revisão); 7.2, do revisor; 7.3, depois das
correções da revisão (a que vale para o código atual).

### 7.1 Rodada do implementador (antes da revisão)

Cópia de teste `par` (pacote de produção, semente fixa, 127.0.0.1, offline), só com o `server.jar`
do workspace. Logs e resumos por caso em `<scratchpad>/fork/testes/parada/` (`<caso>-resumo.txt`,
`<caso>-console.log`, thread dumps, crash-reports). Backup da pasta do mundo antes de cada caso
(apagados no fim). Jogador = bot de teste `HoshieBot`.

| Caso | Jar | O que foi feito | Saída do processo | Crash-report | Salvamentos de parada | Observações |
|---|---|---|---|---|---|---|
| c1 | 1º | **desligado** + SIGTERM, ocioso | 14,0 s | não | não visível (o log para); `level.dat` gravado 7 s depois do sinal | `session.lock` da raiz ficou com **0 bytes**, mexido no último instante (causa não investigada; nos casos com o recurso ligado ficou sempre com 8 bytes) |
| c1b ("antes") | 2º | **desligado** + SIGTERM, 4 thread dumps | 16,8 s | **sim**: NPE no tick (`DimensionManager.getWorld(int) is null`) | no gancho | dumps: gancho 9 s em `disablePlugins` e depois em `saveAllWorlds`, com a thread do servidor ticando o tempo todo |
| c2 | 2º | ligado + SIGTERM, jogador online | 10,3 s | não | **1**, `Server thread`, 4,1 s | jogador desconectado com a mensagem de desligamento; `playerdata` gravado |
| c3 | 2º | ligado + `save-all` e SIGTERM 0,4 s depois | 15,2 s | não | **1**, `Server thread`, 5,9 s | dump: SIGTERM chegou com o `save-all` rodando; o tick terminou antes da parada. Sem jogador (o bot foi recusado: servidor ainda iniciando) |
| c4a | 2º | ligado + SIGTERM a 30 s do boot | 49,5 s | **sim** | 0 | **defeito do desenho original**: o boot seguiu com a JVM saindo e um mod quebrou em `addShutdownHook` ("Shutdown in progress"). Corrigido (seção 9) |
| c4b | 3º | ligado + SIGTERM a 30 s do boot | 3,6 s | não | 0 | "servidor ainda no boot: sem espera"; igual a hoje |
| c4c | 3º | ligado + SIGTERM a 105 s do boot (plugins ligando) | 3,6 s | não | 0 | idem |
| c7 | 3º | ligado, T1 = 5 s, thread do servidor travada 12 s (`hoshie parada-travar`) + SIGTERM, jogador online | 14,6 s | não | **1**, no gancho (`Thread-8`), 6,6 s | desistência: aviso com a pilha; dump 2: thread do servidor no aviso de parada aos mods e o gancho esperando em `ganchoDepois` |
| c5 | 3º | ligado + `stop` normal, jogador online | 5,1 s | não | **1**, `Server thread`, 3,8 s | 1 "Stopping server", 1 "Saving players", 1 "Saving worlds"; achado: o gancho dizia "boot" depois de um `stop` (corrigido, seção 9) |
| c2b, c5b | 4º | SIGTERM e `stop` | 14,5 s e 4,8 s | não | 1 e 1, `Server thread` | o bot não completou o login nestas duas (ver "não verificado"); rodadas sem jogador |
| c2c | 4º | ligado + SIGTERM, jogador online | 9,5 s | não | **1**, `Server thread`, 3,7 s | `playerdata` com a hora da parada |
| **f1** | **final** | ligado + SIGTERM, jogador online | 10,5 s | não | **1**, `Server thread`, 4,1 s | gancho: "chegou na saída em 9750 ms" |
| **f2** | **final** | religar (mundo abre sem erro), jogador entra, `stop` normal | 5,1 s | não | **1**, `Server thread`, 3,7 s | 0 exceções depois de "Stopping server" |

Jars: 1º `2ef9caa0…`, 2º `1694d4f9…` (avisos direto no console), 3º `e5a810b7…` (boot sem espera),
4º `c9bee67c…` (ordem das conferências do gancho), final desta rodada `51faa894…` (commit `fe109e0f`: sem espera
quando a thread do servidor chamou `System.exit`; fachada com `try/catch`). Do 2º ao final a parte
exercitada por c2 e c3 (SIGTERM com o servidor de pé) não mudou de lógica; f1 e f2 repetiram os dois
caminhos principais no jar final.

Em todos os casos com o recurso ligado e o servidor de pé:
- **sem crash-report novo**; sem exceção nova no log (as linhas de erro depois do "Done" são as
  mesmas da base: rede bloqueada, PlaceholderAPI, e erros de plugins no login do bot);
- **um único salvamento de parada**, na thread indicada na tabela;
- `session.lock` com 8 bytes e a hora do boot (ninguém mexeu nele na parada);
- `level.dat` com a hora do salvamento e `level.dat_old` com a do salvamento anterior;
- arquivos de região tocados na hora do salvamento (2 de 51 no mundo ocioso);
- **religar depois de SIGTERM**: os boots seguintes a c2, c4c (dois SIGTERM no boot), c7, c2b e f1
  chegaram ao "Done" sem erro de `session.lock` ou de carga de mundo; depois de c4c, c7 e f1 o bot
  também entrou com os dados salvos. (Depois de c2c a cópia foi apagada, sem religar.)

Linhas do caso f1 (console):

```
[Hoshiestar] [parada] [direto/INFO] [Thread-8] JVM saindo com o servidor de pe (SIGTERM?): pedi a parada do laco de tick e espero a thread do servidor salvar
[Hoshiestar] [parada] [direto/INFO] [Server thread] salvamento de parada comecou na thread Server thread
[Hoshiestar] [parada] [direto/INFO] [Server thread] salvamento de parada terminou em 4118 ms na thread Server thread
[Hoshiestar] [parada] [direto/INFO] [Thread-8] a thread do servidor chegou na saida em 9750 ms (salvamentos de parada: 1)
```

**Uso de `kill`** (só `kill -TERM`, nunca `-9`, só na cópia de teste, só para testar a própria
parada por SIGTERM): 11 vezes, uma por caso: c1, c1b, c2, c3, c4a, c4b, c4c, c7, c2b, c2c, f1.
Registro com hora e pid em `<scratchpad>/fork/testes/parada/usos-de-kill.txt`. Nenhum `kill` por
cópia travada.

### 7.2 Rodada do revisor (jar `51faa894…`, cópia `revpar`)

Resumo; o detalhe está no relatório dele (`<scratchpad>/fork/rev/parada/revisao.md`, logs em `srv/`).

| Caso | O que foi feito | Saiu em | Salvamentos | Resultado |
|---|---|---|---|---|
| r1 | desligado + `stop` | 19,8 s | 1, thread do servidor | caminho original |
| r2 | ligado + SIGTERM no boot | 4,7 s | 0 | "ainda no boot: sem espera"; sem crash-report |
| r3 | ligado + `save-all` + SIGTERM, jogador | 11,9 s | 1, thread do servidor (3,2 s) | `playerdata` gravado |
| r4 | ligado, T1 = 5 s, thread travada 8 s + SIGTERM | 18,6 s | 1, gancho (12,5 s) | desistência com sobreposição: a thread do servidor destravou no meio do salvamento do gancho e só chegou na saída 236 ms depois do fim dele |
| r5 | ligado, `stop` e SIGTERM 1 s depois, jogador | 7,3 s | 1, thread do servidor (3,6 s) | o gancho esperou 5,6 s e não salvou |
| r6 | ligado, comando `restart` no console, jogador | 15,0 s | 1, gancho (14,0 s) | a própria thread do servidor chama a saída; reconhecido no modpack real |
| r7 | religar depois de tudo, jogador, `stop` | 9,6 s | 1, thread do servidor (7,8 s) | mundo abriu; bot na mesma posição |

Uso de `kill` pelo revisor: 4 vezes (`kill -TERM`, cópia `revpar`: r2, r3, r4, r5).

### 7.3 Rodada depois das correções da revisão (cópia `par2`)

Cópia nova do pacote de produção, só com o `server.jar` do workspace; backup do mundo antes de cada
caso; jogador = bot `HoshieBot`. Logs e resumos em `<scratchpad>/fork/testes/parada2/`
(`<caso>-resumo.txt`, `<caso>-console.log`, dumps). Jars: A = `730f85e9…` (commit `cd7c90a9`);
**final** = `86a61e72…` (commit `7e782e3f`: A + "sem folga com a thread do servidor ainda num tick").

| Caso | Jar | O que foi feito | Saída do processo | Crash-report | Salvamentos de parada | Observações |
|---|---|---|---|---|---|---|
| s0 | A | desligado + `stop` (primeiro boot, cria o mundo) | — | não | caminho original | nenhuma linha `[parada]` |
| s1 | A | ligado + SIGTERM, jogador online | 13,7 s | não | **1**, `Server thread`, 7,4 s | um mod calculava receitas em outra thread; salvamento mais lento |
| s2 | A | ligado, thread do servidor travada 120 s (`hoshie parada-travar`): o **vigia** dispara aos ~95 s e pede a saída | 31 s depois do pedido do vigia | **sim** | **1**, no gancho, 6,5 s, **na hora** | o vigia foi reconhecido ("quem pediu a saida foi o vigia"). Mas o gancho esperou a folga, a thread do servidor destravou 23 s depois e terminou o tick em cima dos mundos já descarregados: NPE no tick (`DimensionManager.getWorld(int) is null`) e crash-report, com tudo já salvo. **Motivo da correção M2** |
| s2b | **final** | o mesmo do s2 | **7,8 s** depois do pedido do vigia | **não** | **1**, no gancho, 6,8 s, na hora | "a JVM sai sem esperar a folga, como no codigo original". (Antes, por erro do meu script, a thread ficou travada 80 s, menos que o limite do vigia, e voltou sozinha: nada aconteceu, como esperado) |
| s3 | **final** | ligado, T1 = 5 s, thread travada 8 s + SIGTERM, jogador | 12,8 s | não | **1**, no gancho, 6,9 s | desistência com sobreposição: a thread do servidor destravou no meio do salvamento, esperou, e o gancho esperou o fechamento dela (153 ms) |
| s4 | **final** | ligado, comando `restart` no console, jogador | 7,5 s | não | **1**, no gancho, 6,6 s | `System.exit` na thread do servidor, reconhecido com a pilha inteira |
| s1b | **final** | ligado + SIGTERM, jogador online | 9,3 s | não | **1**, `Server thread`, 3,6 s | "chegou na saida em 8536 ms (salvamentos de parada: 1)" |
| s5 | **final** | ligado + `stop` normal, jogador | 5,1 s | não | **1**, `Server thread`, 3,8 s | 1 "Stopping server", 1 "Saving players", 1 "Saving worlds"; 0 exceções depois de "Stopping server" |
| s6 | **final** | ligado, **T2 = 1 s** de propósito + SIGTERM, jogador (achado I2 no servidor real) | 6,7 s | não | 1 **começado** na `Server thread` e **cortado** | o salvamento começou 4,9 s depois do sinal; 1 s depois o gancho desistiu com o aviso "a JVM vai sair e esse salvamento sera CORTADO no meio" e a JVM saiu. É o comportamento documentado de T2 curto demais. Mundo não reaberto (cópia apagada) |

Em todos os casos com o servidor de pé: `session.lock` com 8 bytes e a hora do boot; `level.dat`
com a hora do salvamento e `level.dat_old` com a do anterior; 5 de 51 arquivos de região tocados;
`playerdata` do bot gravado na parada. **Religar:** cada caso de s1 a s6 começou com um boot em cima
do mundo deixado pelo caso anterior (inclusive depois do s2, que teve crash-report): todos chegaram
ao "Done" sem erro de mundo e o bot entrou na mesma posição. Os avisos de mixin do boot são as
mesmas 8 linhas de sempre (classes de cliente ou de mods ausentes); nenhum sobre `MinecraftServer`.

Linhas do caso s2b (console):

```
[Spigot Watchdog Thread/ERROR] [Minecraft]: The server has stopped responding!
[Spigot Watchdog Thread/INFO] [FML]: Java has been asked to exit (code 0) by ...FMLCommonHandler.exitJava(FMLCommonHandler.java:638).
[Hoshiestar] [parada] [direto/AVISO] [Thread-8] quem pediu a saida foi o vigia de travamento (watchdog): a thread do servidor esta travada ha mais que o limite dele, entao o gancho nao espera e salva ele mesmo, como no codigo original; ...
[Hoshiestar] [parada] [direto/INFO] [Thread-8] salvamento de parada comecou na thread Thread-8
[Hoshiestar] [parada] [direto/INFO] [Thread-8] salvamento de parada terminou em 6806 ms na thread Thread-8
[Hoshiestar] [parada] [direto/AVISO] [Thread-8] o salvamento de parada terminou e a thread do servidor continua dentro de um tick (nao chegou ao stopServer): a JVM sai sem esperar a folga, como no codigo original | ...
```

**Achado desta rodada (vale para escolher T1):** depois do SIGTERM a thread do servidor leva uns 5 s
para sair do laço e começar a salvar, porque o log4j, que se fecha no próprio gancho dele, é
recriado na primeira linha de log seguinte (os dumps de s1 e s1b mostram a thread do servidor
dentro de `LoggerContext.reconfigure`, lendo jars). Por isso **T1 abaixo de uns 15 s faria um
servidor saudável cair no caminho de desistência** neste modpack (no s3, com T1 = 5 s, a
desistência viria mesmo sem a thread travada).

**Uso de `kill` nesta rodada** (só `kill -TERM`, nunca `-9`, só na cópia `par2`, só para testar a
parada por SIGTERM): 4 vezes: s1 (23:30:10), s3 (23:57:38), s1b (00:06:13), s6 (00:13:32). Os casos
s0, s2, s2b, s4 e s5 terminaram por comando no console ou pelo vigia. Registro em
`<scratchpad>/fork/testes/parada2/usos-de-kill.txt`. Nenhum `kill` por cópia travada. A cópia `par2`
e os backups do mundo foram apagados no fim.

## 8. Plano de teste de servidor (para repetir, ou para o admin repetir numa cópia carregada)

Preparação: `newcopy.sh <nome>`; trocar o `server.jar` pelo do workspace; um boot e `stop` para criar
o mundo e o `hoshiestar.yml`. Antes de cada caso: backup da pasta `world-test`, anotar o que há em
`crash-reports/`. As funções prontas estão em `<scratchpad>/fork/testes/parada2/lib.sh` (`antes`,
`sigterm`, `parar`, `depois`) e o roteiro de cada caso em `caso.sh` (o `pidsrv` acha o processo pela
pasta da cópia, para nunca mandar sinal ao servidor de outra pessoa).

1. **Antes (controle):** `parada.ligado: false`; ligar; esperar 30 s depois do "Done";
   `kill -TERM <pid>`. Esperado: o defeito de hoje (crash-report com erro no tick em parte das
   tentativas; o salvamento roda na thread do gancho; nenhuma linha `[parada]`).
2. **Ocioso:** `parada.ligado: true`; ligar; entrar com um jogador; `kill -TERM <pid>`. Tem de
   aparecer no console: `pedi a parada do laco de tick`, `salvamento de parada comecou na thread
   Server thread`, `terminou em N ms na thread Server thread`, `chegou na saida em N ms (salvamentos
   de parada: 1)`. Não pode aparecer: crash-report novo, `[direto/AVISO]`.
3. **Depois de `save-all`:** `save-all` no console e `kill -TERM` em seguida. Mesmo critério.
4. **No boot:** `kill -TERM` antes do "Done". Esperado: uma linha "servidor ainda no boot ... sem
   espera", processo fora em poucos segundos, nenhum crash-report.
5. **`stop` normal:** esperado 1 "Stopping server", 1 "Saving worlds" na `Server thread`, duas
   linhas `[parada]` (começou/terminou) pelo log normal, nenhuma linha `[direto/...]`.
6. **Religar** depois de cada caso: o mundo abre sem erro; o jogador volta onde estava.
7. **Desistência (só cópia de teste):** `parada.espera_laco_segundos: 5`; ligar com
   `JAVA_TOOL_OPTIONS="$JAVA_TOOL_OPTIONS -Dhoshiestar.parada.teste=true"`; no console
   `hoshie parada-travar 8` e, 1,5 s depois, `kill -TERM`. Esperado: `[direto/AVISO] ... o laco de
   tick nao parou em 5000 ms`, salvamento `na thread Thread-N`, `depois do salvamento, a thread do
   servidor terminou o fechamento em N ms`, um salvamento só.
8. **Comando `restart`** no console (na cópia de teste o script de reinício não existe, então o
   servidor só sai): esperado `[direto/AVISO] a propria thread do servidor chamou System.exit`, um salvamento
   `na thread Thread-N`, sem crash-report.
9. **Vigia de travamento (só cópia de teste):** `parada.espera_laco_segundos: 60`; ligar com a
   propriedade de teste; `hoshie parada-travar 120` (o `timeout-time` do `spigot.yml` do pacote é 90).
   Uns 95 s depois o vigia pede a saída. Esperado: `[direto/AVISO] quem pediu a saida foi o vigia de
   travamento`, salvamento `na thread Thread-N` começando **na hora**, `a JVM sai sem esperar a
   folga`, processo fora uns 8 s depois do pedido, sem crash-report.

Em cada caso conferir: `crash-reports/`; `session.lock` (8 bytes, hora do boot); hora de `level.dat`
e dos `.mca`; tempo até o processo sair. Numa cópia carregada (muitos jogadores, bases grandes) o
que muda é a duração do salvamento: medir e comparar com T2 e com o prazo do painel (seção 2).

**A cada mudança nos patches** (`MinecraftServer.stopServer`, `DedicatedServer.systemExitNow`,
`ServerShutdownThread`) ou na máquina, os casos 2, 3, 5, 7, 8 e 9 são **obrigatórios**: os testes
isolados usam uma cópia da cola dos patches (`ServidorFalso`), não o `MinecraftServer` de verdade
(achado M6 da revisão); a cola real só é exercitada no servidor.

Antes de ligar em produção: saber como o painel para o servidor e quanto espera antes do SIGKILL;
escolher T1 e T2 pela seção 2; rodar os casos 2, 3 e 5 numa cópia com o mundo de produção e medir
o salvamento de parada; um segundo olhar de outro agente sobre as correções pós-revisão.

## 9. O que mudou em relação aos relatórios de desenho e de conferência

| Ponto | Desenho / conferência | Feito | Por quê |
|---|---|---|---|
| Mecanismo | três mixins (S1, S2, S3) | patches diretos no fork | decisão do projeto; sem "embrulho" de método |
| SIGTERM no boot | pedir a parada e esperar T1; se o boot acabar, salvar | **antes de `SERVER_STARTED` o gancho não pede nem espera** (caminho original) | teste real c4a: com a JVM saindo, o boot continuou, um mod chamou `Runtime.addShutdownHook`, levou "Shutdown in progress", e saiu crash-report 49 s depois |
| Gancho com o estado `FEITO` e sem `saida` | voltar na hora (`state == DONE`) | esperar a folga | senão o `closeSession()` do hodgepodge seria cortado quando o SIGTERM chega logo depois do salvamento |
| Depois do `stopServer()` do gancho | nada | `ganchoDepois`: espera a folga se há salvamento `FEITO` | mesmo motivo, no caminho de desistência e quando o gancho pula |
| Prazo de quem espera o salvamento | T2 a partir da chegada | T2 a partir do **início do salvamento** | quem já esperou T2 no gancho não espera outros T2 em `entrar` |
| Reentrada | cai no corpo e marca `DONE` ao sair | devolve `ORIGINAL` sem tocar no estado | a chamada de dentro não pode marcar `FEITO` com a de fora ainda rodando |
| `System.exit` chamado pela thread do servidor | não previsto | reconhecido pela pilha; sem espera | senão seriam T1 + folga de atraso, com o salvamento só começando depois de T1 |
| Avisos durante a saída | log normal | log normal **e** direto na saída de erro | teste real: o log4j para de escrever quando a JVM começa a sair |
| Nomes das chaves | `safeStopOnSignal`, `stop*TimeoutSeconds` (.cfg) | `parada.*` no `hoshiestar.yml` | padrão da base |
| Exceção no salvamento da thread do servidor | volta a `IDLE`; o segundo chamador "vê `serverStopped`" e não salva | estado `FALHOU`: nenhuma outra thread salva; só a thread do servidor poderia tentar de novo | revisão (I3): `serverStopped` só vira verdadeiro depois do aviso de "servidor parado"; passado T1 o gancho salvava no meio desse fechamento |
| Saída pedida pelo vigia de travamento (watchdog) | não previsto | reconhecido pela pilha; sem esperar T1 | revisão (I1): o reinício por travamento está ligado no `spigot.yml`; esperar mais T1 só atrasava o salvamento |
| Folga com a thread do servidor ainda num tick | esperar a folga sempre que há salvamento feito | sem folga se ela nem chegou ao `stopServer()` | revisão (M2) e caso real s2: a espera deu tempo de ela destravar e ticar em cima dos mundos descarregados (crash-report) |
| Reconhecer `System.exit` pela pilha | 12 primeiros quadros | pilha inteira | revisão (M3) |

## 10. Não verificado

- **Como a produção é parada** (comando `stop` ou SIGTERM) e **quanto o painel espera** antes do
  SIGKILL. É a condição para escolher T1 e T2 (seção 2) e para ligar o recurso.
- **Segundo olhar sobre as correções pós-revisão.** O código foi revisado por outro agente antes
  delas; o estado `FALHOU`, o reconhecimento do vigia e a regra da folga são posteriores e só foram
  conferidos por testes (isolados, 11 mutações, servidor real).
- Duração do salvamento de parada no **mundo de produção** e com o servidor carregado: os testes
  reais foram com o mundo ocioso (0 ou 1 jogador, 5 arquivos de região tocados). Salvamento longo,
  perto de T2, só no teste isolado e no s6 (T2 reduzido a 1 s).
- **Exceção no salvamento da thread do servidor (I3) no servidor real**: não há como provocar sem
  mexer no jogo; coberto por teste isolado (cenários, estresse com exceções, mutações).
- Se o hodgepodge desta cópia tem `threadedWorldDataSaving` e `fixMinecraftServerLeak` ligados (não
  li valores de config). O que vi: os `data/*.dat` são gravados 3 a 4 s depois de `level.dat` e
  antes da saída, o que é compatível com o `closeSession()` rodando; não é prova.
- JDK 8 no servidor real (as cópias rodam em JDK 21; os testes isolados rodam nos dois).
- O vigia com o script de reinício **presente** (na cópia de teste o arquivo apontado por
  `restart-script` não existe; se na produção existe, não sei): com o script, o `RestartCommand`
  antes expulsa os jogadores, fecha a rede e pede a parada do laço, e registra um gancho que relança
  o servidor. O reconhecimento pela pilha é o mesmo (a chamada de saída parte do mesmo lugar,
  `RestartCommand.restart` chamado pelo `WatchdogThread`); não testado.
- Visibilidade de `serverRunning` entre threads pelo modelo de memória do Java (funciona na prática;
  T1 cobre).
- SIGTERM dentro da janela de milissegundos entre o estado `SERVER_STARTED` e o primeiro tick.
- Se o mundo abre depois de um salvamento cortado por T2 (caso s6): não religuei (o caso é
  destrutivo por desenho).
- Por que o bot às vezes não completa o login na primeira tentativa (c2b, c5b, s2, s4: entrou na
  segunda). Não investigado; não tem a ver com a parada.
- Desempenho: o recurso não tem código no caminho do tick (só em `stopServer`, `systemExitNow` e no
  gancho); nada foi medido porque nada roda durante o jogo. No gancho há agora uma leitura das
  pilhas de todas as threads (`Thread.getAllStackTraces`), uma vez por saída da JVM; nos casos reais
  não apareceu nos tempos (s1b saiu em 9,3 s, contra 9,5 a 10,5 s antes).

## 11. Achados fora do recurso (não corrigidos aqui)

- Com SIGTERM, o log4j para de escrever assim que a JVM começa a sair: as linhas "Saving players" e
  "Saving worlds" não aparecem em `console.log` nem em `logs/latest.log`, com ou sem o recurso.
- Pelo mesmo motivo a parada por SIGTERM é uns 5 s mais lenta que a por `stop`: a primeira linha de
  log depois do fechamento do log4j o recria inteiro (lê jars atrás de plugins de log), na thread do
  servidor antes de salvar, e de novo na thread do gancho (por isso o salvamento feito pelo gancho é
  mais lento). Dumps em s1 e s1b. Seria um ganho barato desligar o gancho de saída do log4j
  (`shutdownHook="disable"` no `log4j2.xml` do servidor) e fechá-lo no fim da parada; não é deste
  recurso e não foi testado.
- No login do bot com dados já salvos, quatro plugins (WorldGuard, Auctions, GriefPreventionPlus,
  Multiverse-Core) lançam `NullPointerException` em `PlayerTeleportEvent` (`getTo()` nulo). Acontece
  antes de qualquer parada; parece ligado aos commits de teleporte do upstream.
- ChromatiCraft grava o cache de progresso do jogador com 0 bytes e reclama ao ler no boot seguinte
  ("Could not load cached player progress"), também depois de `stop` normal.
- No caso c1b o gancho passou cerca de 9 s dentro de `disablePlugins` (os dumps mostram leitura de
  jars e de YAML), enquanto uma parada inteira pela thread do servidor leva cerca de 5 s.
- O `teste-chunkmap.sh` do recurso chunkmap tinha extensão `.sh`, que o `.gitignore` do upstream ignora
  e por isso ele NÃO estava no git do workspace: sumia na exportação. Resolvido na junção das faixas:
  virou `teste-chunkmap.bash`.
