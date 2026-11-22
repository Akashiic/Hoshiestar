# Folia x NeoFolia: estudo comparado para o Hoshiestar

Data: 2026-11-22. Pedido do admin na correção de rumo (item 4): ler e comparar o Folia e o NeoFolia antes de desenhar as Etapas 2 e 3 (observador, regiões, thread global, correio).

## 0. Fontes e como ler

**O que foi lido** (só leitura de código; nada compilado, nenhum servidor ligado, nada enviado para fora):

| Projeto | Repositório | Commit lido | Minecraft | Licença |
|---|---|---|---|---|
| Folia | https://github.com/papermc/folia | `eca40f918928e78c2c5f9c944cfb083d7d9851d4` (2026-11-18) | 26.3 | GPL v3 |
| NeoFolia | https://github.com/NeoFolia/NeoFolia | `fa76006de0c01f5cd0c3290f0994898c39e8441f` (2026-06-04) | 26.1.2, Java 25 | LGPL 2.1 (herdada do NeoForge) |
| Crucible (nossa base) | clone usado nos estudos anteriores (`scratchpad/build/Crucible`, árvore com patches aplicados) | `staging@67b53034` | 1.7.10 | GPL v3 |

O Folia guarda o código como arquivos `.patch` sobre o Paper; o Paper não estava no clone, então o que é código do Paper ficou "não verificado". O NeoFolia é um fork do NeoForge (carregador de mods moderno), com um patch por classe e um pacote Java próprio de regiões. Nenhum dos dois é 1.7.10: daqui saem **ideias de desenho**, não código. Não copiamos código de nenhum dos dois.

**Como foi feito.** Cinco temas. Cada tema teve um relatório e uma conferência feita por **outro agente**, que abriu cada arquivo e linha citados e tentou derrubar cada afirmação. Onde os dois divergem, **vale a conferência**. Esta página é a síntese: não traz fato novo sobre os dois projetos, só aponta para os relatórios. Fatos sobre o Crucible que não estavam nos nossos estudos anteriores foram conferidos no código para esta síntese e estão marcados "(conferido no Crucible nesta síntese)".

| Tema | Relatório | Conferência | Afirmações | Confirmadas | Corrigidas | Refutadas | Não verificáveis |
|---|---|---|---:|---:|---:|---:|---:|
| Folia: regiões e como ticam | `folia/folia-regioes.md` **[FR]** | `folia/folia-regioes-conferencia.md` **[FRc]** | 56 | 50 | 5 | 1 | 0 |
| Folia: thread global, correio, checagem de dono | `folia/folia-global-correio.md` **[FG]** | `folia/folia-global-correio-conferencia.md` **[FGc]** | 85 | 75 (3 delas só em parte, com o resto não verificável) | 7 | 1 | 2 |
| Folia: plugins | `folia/folia-plugins.md` **[FP]** | `folia/folia-plugins-conferencia.md` **[FPc]** | 65 | 54 | 9 | 1 | 1 |
| NeoFolia: arquitetura | `folia/neofolia-arquitetura.md` **[NA]** | `folia/neofolia-arquitetura-conferencia.md` **[NAc]** | 61 | 47 | 12 | 0 | 2 |
| NeoFolia: mods | `folia/neofolia-mods.md` **[NM]** | `folia/neofolia-mods-conferencia.md` **[NMc]** | 50 | 35 | 13 | 0 | 2 |
| **Total** | | | **317** | **261** | **46** | **3** | **7** |

(Linha FG recontada na conferência final a partir da tabela da [FGc]: 72 "C", 3 "C em parte / NV", 7 "Cor", 1 "R", 2 "NV". O texto de abertura da [FGc] fala em "10 correções" porque conta também ajustes descritos fora da tabela.)

As três afirmações refutadas eram do lado dos relatórios, não dos projetos: (1) "a divisão de região do Folia perde item de máquina" (não perde: só descarta entradas de chunks já descarregados, [FRc #50]); (2) um trecho de `Entity` atribuído a pistão (não é pistão, [FGc #50]); (3) "`getTPS()` falha na thread global do Folia" (funciona, [FPc #49]).

**Notação.** `[FR §3.2]` = seção do relatório. `[FRc #10]` = linha 10 da tabela da conferência. `[FRc +3]` = item 3 da seção "o que faltou" da conferência. `[NAc +2.1]` = subseção 2.1 de "o que faltou". `[EH M02]` = item do nosso estudo `docs/pesquisa/estruturas-hostis.md`. `[CT §4]` = `docs/pesquisa/crucible-tick-e-threads.md`. `[02 §4 (6)]` = `docs/02-arquitetura.md`. `D-27` = achado do `DIAGNOSTICO.md`. Caminhos do Crucible seguem o estudo de estruturas hostis: `MC` = Minecraft/Forge/FML com patches, `CR` = CraftBukkit/Spigot/Crucible; siglas `W` (`World.java`), `WS` (`WorldServer.java`), `E` (`Entity.java`), `MS` (`MinecraftServer.java`), `CPS` (`ChunkProviderServer.java`), `SCM` (`ServerConfigurationManager.java`), `SPM` (`SimplePluginManager.java`).

Os caminhos `Folia/...` e `NeoFolia/...` dentro dos relatórios se referem aos clones nos commits acima (não estão no repositório). Para reconferir, clonar os dois repositórios nesses commits. O texto das páginas de documentação do Folia que os relatórios citam como fonte secundária (`docs-baixados/`) não foi copiado para o repositório: é texto de terceiros e está em https://docs.papermc.io/folia/.

**Termos** (explicados uma vez):
- **tick**: um passo do jogo; alvo de 50 ms (20 por segundo).
- **região**: conjunto de chunks vizinhos que tica junto, numa thread só por vez.
- **seção**: quadrado fixo de N×N chunks (N potência de 2). É o tijolo das regiões nos dois projetos e no nosso desenho.
- **regionizador**: o código que diz a qual região cada seção pertence e que junta ou divide regiões.
- **thread global**: a thread que cuida do que não pertence a nenhuma região (hora, clima, console).
- **correio**: fila de tarefas que uma região deixa para outra (ou para a global) rodar.
- **dono**: a thread que, naquele momento, é a única autorizada a mexer num chunk, bloco ou entidade.
- **chunk holder / ticket** (Folia e NeoFolia): o "registro" que o servidor moderno cria para um chunk pedido (ticket = pedido de "mantenha este chunk"). **A 1.7.10 não tem isso**: só tem chunk carregado ou não.
- **barreira**: ponto em que a thread principal espera todas as regiões terminarem antes de seguir.
- **fase serial / fase paralela / ponto de junção**: as três partes do tick do nosso Degrau B: tudo em uma thread, regiões em paralelo, e o instante entre elas em que pedidos adiados são aplicados [02 §3].
- **capability**: no NeoForge, a "porta" padrão pela qual um mod pede o inventário, o tanque ou a energia de um bloco de outro mod.
- **observador**: o modo da Etapa 2 que só registra quem mexe fora da própria área, sem mudar nada.

---

## 1. Resumo (uma página)

1. **Divisão do mundo: igual nos dois.** Seções de 16×16 chunks; cada seção com chunk ganha uma margem de 1 seção vazia; seções com chunk a até 3 seções de distância viram uma região só. O Folia faz isso de forma incremental, com estados, e **nunca muda o dono de uma região que está ticando**. O NeoFolia refaz o mapa do zero a cada tick, sem esse cuidado [FR §3; FRc #5; NA §5; NAc #16, #19, #42].
2. **Ritmo: oposto.** No Folia a thread principal só liga o servidor e dorme; cada região tem relógio e agenda próprios num pool de threads, e a "região global" é mais uma tarefa nesse pool. No NeoFolia a thread principal continua dona do laço de 20 TPS e, a cada tick, põe tarefas das regiões num pool **sem esperar** que terminem [FR §5; FGc #1-#3; NA §6; NAc #33].
3. **Correio.** Folia: fila FIFO (primeiro a entrar, primeiro a sair) por região, endereçada por posição; quem posta põe um ticket, processado na hora, para a região dona existir (que esse ticket crie o chunk holder é regra do Paper, fora do clone: não verificado, [FGc #18]); a ordem sobrevive a junções e divisões. NeoFolia: fila FIFO por região sem ticket, mais uma espera bloqueante para mexer em duas regiões; uma região nunca pode esperar outra [FG §3.2; FGc +1; NA §7; NAc #40].
4. **Plugins.** O Folia proíbe: só carrega plugin marcado `folia-supported: true`, e o agendador antigo do Bukkit lança exceção (`CraftScheduler.handle` lança sempre; que todo método do agendador passe por ele não foi verificado, [FPc #9]). O NeoFolia não tem Bukkit. **Nenhum dos dois tem modo de compatibilidade** [FP §3, §9; FPc #1-#9; NA §3.1].
5. **Mods.** Só o NeoFolia tentou, e o que fez é raso: eventos de tick do servidor e do mundo ficam na thread principal; eventos de entidade e tile entity rodam nas regiões, **sem trava** nos ouvintes e **sem barreira**; acesso fora da região vira `null`, ar ou `false` em silêncio; não há lista de mods, modo seguro nem chave geral; a parada não espera as regiões. Projeto com dois dias de histórico e nenhum teste com mod; as notas de versão o chamariam de "alfa", mas isso não foi verificado (HTTP 403) [NM §1; NMc; NAc #3-#7, +2.1].
6. **O que trazemos:** a geometria; o correio FIFO que preserva ordem; "uma região nunca espera outra nem a global"; o regionizador que não muda dono de região em execução; parada que espera as regiões e só então salva (Folia); vigia e TPS por região; interruptor por parte com chave geral; rascunhos e sorteio por thread (que o nosso estudo de estruturas hostis já pedia).
7. **O que não dá:** tirar a thread principal (os eventos de tick do Forge, o agendador do Bukkit e os pacotes moram nela); rodar sem barreira (mods leem listas públicas do mundo direto); checagem que derruba o servidor; resposta silenciosa ao mod; embrulhar capabilities (a 1.7.10 não tem); teleporte assíncrono obrigatório; exigir marca nos 65 plugins; relógio por região visível aos mods; cortar atualização de bloco na borda.
8. **Efeito no nosso desenho:** a ordem em degraus sai confirmada (Degrau B com barreira antes do C; o NeoFolia é, na prática, um "B sem barreira" e mostra o custo disso). Mudam pontos concretos do regionizador, da thread global, do correio, do modo seguro e da parada (seção 9; marcados em `docs/02-arquitetura.md`). Três perguntas vão para o admin (seção 10).

---

## 2. Tabela comparativa

"Nosso desenho" é o `docs/02-arquitetura.md` **antes** deste estudo. O que muda está na seção 9.

| Assunto | Folia (`eca40f9`) | NeoFolia (`fa76006`) | Nosso desenho atual |
|---|---|---|---|
| **Unidade de região** | Seção de 2^`gridExponent` chunks, padrão 16×16, lida uma vez na partida [FR §3.1; FRc #1-#2]. Região = conjunto de seções vizinhas | Quadrado fixo de 16×16 chunks, constante no código, sem config [NA §5.1; NAc #14] | Seção de 16×16 chunks, ajustável; um regionizador por dimensão [02 §4 (2)] |
| **Junção e divisão** | Margem de 1 seção (16 chunks no padrão) e junção a até 3 seções [FRc #3-#5, #51]. Região que tica não ganha seção nova, mas ganha chunk nas suas [FRc #10]. Junção com região que tica fica para o fim do tick dela; divisão só no fim do tick e só apaga seção sem chunk holder [FR §3.5; FRc #15-#17, #50]. Uma trava de escrita por mundo, pega no começo e no fim de **todo** tick de região [FRc +1] | Mesma distância (os testes citam "Folia empty bridge distance") [NAc #16, #19]. Mapa refeito do zero a cada tick com união de grupos; sem estados; o dono pode mudar com tarefa rodando (corrida provável, não testada) [NA §5.1, §7.3; NAc #20-#21, #42] | Regiões vizinhas no raio viram uma; região que tica não cresce; junção e divisão no fim do tick; junção forçada por rede de mod [02 §4 (2)] |
| **Agendamento nas threads** | Pool fixo; escalonador EDF (padrão: roda primeiro quem tem prazo mais perto) ou roubo de trabalho; cada região com agenda e TPS próprios; região atrasada **não recupera** ticks [FR §5; FRc #34, #38]. Automático: metade dos núcleos; se der 4 ou menos, 1 thread; senão, divide por 4 (conta inteira): 1 thread até 15 núcleos, 2 com 16, 4 com 32 [FGc #5, conferido em A4 5786-5800 na conferência final] | Thread principal põe tarefas por tipo ("raia": entidades, tile entities...) num pool fixo; fatia de 256 tarefas ou 5 ms [NAc #29]; tarefa repetida descartada se a anterior não acabou, então entidades pulam ticks mas ticks agendados acumulam [NAc #34; NMc +7]. Sem barreira [NAc #33]. Automático: 1 thread com 8 núcleos [NAc #28] | Grupo de 3 a 4 threads atendendo uma fila de regiões; Degrau B em passo (com barreira), Degrau C solto [02 §3; decisão 9] |
| **Thread global** | Tarefa no mesmo pool, a 20 TPS, sem chunks; a thread principal dorme depois de ligar [FGc #1-#3]. Cuida de hora, clima, console, borda, sono, login; **não pode** tocar bloco nem entidade [FG §3.1; FGc #8-#10]. Fila drenada sem limite [FGc +3] | A própria thread principal; drena uma fila com teto de 1.024 tarefas ou 10 ms por tick; continua rodando os eventos de tick do servidor e do mundo; **não é impedida** de tocar no mundo enquanto as regiões rodam [NA §7.1; NAc #36, #38] | Relógio, clima, console, lista de jogadores, economia, chat; banco e disco em threads de I/O [02 §4 (5)] |
| **Correio entre regiões** | `RegionizedTaskQueue`: fila por região, FIFO por prioridade; o ticket é processado na hora para a região dona existir (o elo "ticket cria chunk holder" é do Paper: não verificado, [FGc #18]); na junção, a fila da região que some vai para a frente; o dreno do começo do tick roda só o que já estava na fila [FG §3.2; FGc #13-#23, +1]. Mais um agendador por entidade que segue a entidade [FGc #26] | Fila FIFO por chave de região, sem ticket; se o dono mudou, repassa as pendentes [NAc #30, #39]. `awaitRegions` para duas regiões (threads paradas esperando); região esperando outra = exceção; a principal espera até 60 s [NA §7.3; NAc #40-#41; NMc #48] | Fila entregue no começo do próximo tick da região destino; **padrão é juntar, não mandar correio** [02 §4 (6)] |
| **Teleporte e borda** | Teleporte síncrono proibido; `teleportAsync` tira a entidade do mundo, cria uma **cópia** (se não for jogador) e posta a entrada na região destino [FG §3.3; FGc #29-#31, +5]. Na borda, vizinho e redstone fora da região são **cortados**; colisão vira parede até 4 chunks antes da borda [FG §3.4; FGc #40-#47] | Portal vai para a fila global e roda na thread principal; `/tp` com `awaitRegions` [NAc #37, #41]. Vizinho de outra região tratado como ar; escrita em outra região adiada e devolve `false` [NAc #44, +2.2] | Sai da origem no fim do tick, entra no destino como tarefa; borda coberta pelo raio de junção [02 §5; EH M27, H35, H49] |
| **Checagem de dono** | 224 `ensureTickThread` + 21 `ensureGlobalTickThread`; por seção, e com raio confere todas as seções [FGc #56, #63]. Falha com força; exceção que escapa do tick para o servidor de forma ordenada [FRc #42]; mas erro de pacote só expulsa o jogador e erro de tarefa de plugin só vai ao log [FGc #28] | Esconde em vez de falhar: 241 usos de "devolve só o que é da região", 24 checagens que lançam; a thread principal não é checada; erro em tarefa só vai ao log [NAc +2.4, #38, #54] | Patches no fork; três modos: observador, checagem, roteamento [02 §4 (3)] |
| **Plugins** | Só carrega com `folia-supported: true`; `BukkitScheduler` lança (o `handle` lança sempre; que todo método passe por ele: não verificado); quatro agendadores novos (região, entidade, global, assíncrono), três deles do Paper; eventos rodam na região do jogador; `ServerTickStartEvent` uma vez **por região** [FP §3-§5; FPc #1-#12, #27] | Não tem Bukkit [NA §3.1] | B: como hoje (fase serial, trava global do Bukkit); C: trava única + lista de liberados; agendador interceptado [02 §4 (7); EH K01] |
| **Eventos de mods** | (não é carregador de mods) | `ServerTickEvent` e `LevelTickEvent` na principal; `EntityTickEvent`, `PlayerTickEvent`, tile entity, ticks agendados e aleatórios nas regiões; barramento sem mudança e sem trava; `ServerTickEvent.Post` dispara com regiões ainda rodando [NM §2.1-§2.2; NMc #1-#16] | Definir a thread de `ServerTickEvent`, `WorldTickEvent`, `WorldSavedData` (55, 41 e 34 jars); ouvintes não liberados numa trava "serial" única [PROMPT item 10; EH S04] |
| **Dados de mods** | Dado por região genérico (`RegionizedData`) com funções de juntar e dividir que não podem bloquear nem lançar; não oferecido a plugins [FR §4.1; FRc #21] | `SavedData` só ganhou mapa concorrente e `setDirty` sincronizado; attachments (dados pendurados em bloco/mundo) sem trava; nada por região para mods [NM §2.3; NMc #17-#19] | Conteúdo do `WorldSavedData` de mod é assunto do módulo do mod; `MapStorage` com trava [EH M24] |
| **Redes de mods** | (não trata mods), mas já força junção pondo tickets num caminho entre dois pontos [FGc +4] | Junção por proximidade + embrulho das capabilities de item, fluido e energia (a chamada vai para a região dona; transferência entre duas regiões com `awaitRegions`); pedido novo para bloco de outra região devolve `null`; redes próprias de mods não tratadas [NM §2.5; NMc #26-#28; NAc #50] | Juntar regiões ligadas por rede; correio só para mod com módulo que prove que aguenta [02 §4 (6)] |
| **Modo seguro para o não adaptado** | Não tem: o que não foi adaptado não carrega [FP §9] | Não tem: o despachante sempre liga (mínimo 1 thread); os 4 interruptores vêm ligados e, desligados, não param pacotes nem a geração paralela; nenhuma opção por mod [NM §2.12; NMc #45-#47, +1] | Tudo não classificado em série; lista "só em série" por mod e por dimensão; vigia que rebaixa sem derrubar [02 §4 (9)] |

---

## 3. Como cada um divide o mundo em regiões

**Folia.** O regionizador reage à criação e à remoção de chunk holder, não ao chunk ficar pronto [FR §3; FRc #6]. Cada chunk novo marca um bit na sua seção; se a seção é nova, ela cria seções vazias num raio de 1 e procura regiões num raio de 2 [FR §3.3]. Regiões vizinhas que não estão ticando são absorvidas na hora; se a vizinha está ticando, a junção fica marcada e quem espera fica "transiente" e não tica até a junção acontecer [FRc #9; FGc +2]. No fim de cada tick ou lote de tarefas a região faz as junções pendentes e, se tiver seções mortas, tenta se dividir [FR §3.5]. Cada região guarda seus próprios dados (listas de entidades e tile entities, ticks agendados, conexões, fila de tarefas) e seu próprio relógio de tick e de "redstone time"; o "game time" do mundo é avançado só pela global e copiado no começo do tick de cada região [FR §4; FRc #31]. Na junção, os prazos são corrigidos campo a campo; o `BlockEntity` base não corrige nada, então tempo guardado por código de terceiros não seria corrigido [FRc +3]. Uma região nova nasce com relógio zerado [FRc +2]. Tamanho na prática, com o padrão: uma região com um jogador parado já ocupa 3×3 seções (48×48 chunks) contando a margem [FRc +7]. Duas áreas carregadas se juntam quando seus chunks caem em seções a até 3 seções uma da outra [FRc #5]. Em chunks (conta da conferência final, a partir dessa regra): a até 48 chunks de distância (no eixo em que estão mais longe) elas **sempre** se juntam; de 49 a 63, depende de onde caem na grade; a 64 ou mais, nunca (a não ser por uma cadeia de áreas no meio). A [FRc #5] escreveu "33 a 63 chunks", que é a faixa de distância entre chunks de duas seções a exatamente 3 seções, não o limite de junção.

**NeoFolia.** A chave de região é `(dimensão, chunkX >> 4, chunkZ >> 4)` [NAc #13]. Um quadrado é "ativo" se tem chunk holder ou jogador [NAc #15]. A cada tick a thread principal recalcula tudo do zero: une quadrados ativos a até 3 de distância, escolhe como dono o de menor X (e menor Z), e cada ativo cobre 1 quadrado em volta [NAc #16-#20]. Os testes de unidade confirmam: quadrados a 3 se juntam, a 4 não [NAc #19]. Não há dados por região com juntar e dividir: um mapa estático por dono guarda o pouco que existe, nunca é limpo e é percorrido inteiro a cada tick [NAc #26].

## 4. Thread global e correio entre regiões

**Folia.** A global é um objeto agendado no mesmo pool das regiões; "sou a global?" é uma pergunta sobre a tarefa atual, não sobre a thread [FGc #1-#2]. Antes do seu tick ela esvazia a fila global e os pacotes de quem está em login [FGc #7]. Dentro de um tick de região a ordem é fixa: fila de tarefas da região, pacotes dos jogadores, agendador de região dos plugins, agendadores de entidade, depois o mundo [FGc #22]. O correio (`RegionizedTaskQueue`) é uma fila por região: postar incrementa um contador por seção e põe um ticket no canto da seção, **processado na hora**, para a região dona existir quando a tarefa for procurada; quando a contagem zera, o ticket sai [FGc #15-#16, +1]. Se postar também carrega o chunk: não verificado [FGc #19]. Teleporte entre regiões usa esse correio: tira a entidade do mundo, coloca na região destino por tarefa; durante a viagem a entidade não tica [FG §3.3]. Exceção que escapa de um tick vira parada ordenada do servidor; a parada roda numa thread própria, espera o pool até 60 s e salva região por região [FRc #42, +8].

**NeoFolia.** A global é a thread principal. Ela recalcula as regiões, posta as tarefas do tick por raia e drena a fila global com teto [NAc #20, #32, #36]. Correio sem ticket: se a posição não tem dono, a tarefa cai na fila do próprio quadrado e roda assim mesmo [NAc #39]. Para mexer em duas regiões, `awaitRegions` reserva uma thread parada em cada região; quando o pedido nasce numa região, ele vai para a fila global e é a thread principal que fica parada esperando, até 60 s, com o tick inteiro parado [NAc #40-#41, +2.5]. A parada fecha o pool sem esperar e **cancela** o que estava na fila antes de salvar [NAc +2.1].

## 5. Como tratam plugins

**Folia.** Plugin sem `folia-supported: true` não carrega: três pontos lançam exceção na montagem [FPc #1-#4]. Não há chave escondida, trava global nem modo em série [FPc #5-#6]. O `CraftScheduler.handle` lança sempre; que todo método do `BukkitScheduler` passe por ele é provável, mas não verificado (corpo no Paper) [FPc #8-#9]. Os eventos rodam na região dona do jogador, porque os pacotes dele são processados no tick dessa região [FPc #26]; `ServerTickStartEvent` dispara uma vez por região, em paralelo, e a global não dispara [FPc #27]. A API de blocos e entidades checa o dono e lança; a de jogador não (`CraftPlayer.getHandle`: "total mess") [FPc #36-#38]. Algumas APIs esperam em vez de lançar [FPc +5]. Vários eventos antigos simplesmente deixaram de disparar (chat síncrono, `PlayerPreLoginEvent`, `PlayerSpawnLocationEvent`) e bloco de comando não executa nada [FPc #28, +2, +3].

**NeoFolia.** Não tem camada Bukkit nem plugins ("This is not a Paper/Bukkit plugin", README) [NA §3.1; FPc #63].

**Para nós.** Nenhum dos dois oferece modelo para os nossos 65 plugins 1.7.10. O caminho continua sendo o nosso: trava global do Bukkit que já existe (`SPM:482-496`, [EH K01]) e adapter de plugins [02 §4 (7)].

---

## 6. O que o NeoFolia mudou para conviver com mods (o caso mais perto do nosso)

**Veredito: imaturo e raso.** Não serve de base de código nem de prova de que mods funcionam em regiões. Serve como mapa de **onde** um carregador de mods precisa ser mexido e como aviso do que acontece quando se pula a barreira e o modo seguro.

Por que "imaturo": 22 commits em dois dias, duas contas, três commits de código; a junção de regiões entrou só no último commit de código [NAc #3-#6, #61]. As notas de versão diriam "Mod Compatibility = 0" e "first alpha", mas as páginas do GitHub deram HTTP 403 nesta sessão: **não verificado** [NAc #7]. O README promete portar mods "quando o núcleo estiver mais ou menos estável" [NMc #39]. Só há 4 testes de unidade, nenhum com mod [NMc #40]. Não há documentação de desenho [NAc +2.7].

O que ele mudou, e o que achamos:

| # | Mudança no NeoFolia | Evidência | Para nós |
|---|---|---|---|
| 1 | Eventos de tick do servidor e do mundo ficam na thread principal; eventos de entidade, jogador, tile entity, ticks agendados e aleatórios vão para as regiões | [NMc #4-#12; NAc #46] | **A divisão é a mesma que queremos**, mas lá roda sem barreira |
| 2 | Sem barreira: `ServerTickEvent.Post` e `LevelTickEvent.Post` disparam com as regiões ainda ticando aquele mundo; há teste que prova que é de propósito | [NMc #13-#14] | **Não copiar**: mods 1.7.10 fazem a "conta do tick" nesses eventos |
| 3 | Barramento de eventos sem mudança e sem trava em nenhum dos 260 pontos de `post` | [NMc #1-#3] | **Não copiar**: ouvinte de mod chamado por várias threads ao mesmo tempo |
| 4 | Capabilities de item, fluido e energia embrulhadas: a chamada vai para a região dona; da thread principal ela espera; de outra região, um pedido novo devolve `null` | [NMc #26-#28, #31; NAc #50] | Ideia boa, **inaplicável**: a 1.7.10 não tem capability (seção 8, N5) |
| 5 | Pacote de mod marcado "thread principal" passa a rodar na região do jogador, sem avisar o mod | [NMc #32] | **Não copiar no B**. Na 1.7.10 o pacote de mod roda na thread do servidor (seção 7, D6) |
| 6 | 89 dos 90 comandos vanilla vão para a região do alvo; comandos de mods não, e rodam na principal sem checagem | [NMc #11, #37-#38] | Mostra o tamanho do trabalho; comandos de mods ficam na fase serial |
| 7 | `SavedData`: só mapa concorrente e `setDirty` sincronizado; attachment de mundo é um mapa só para todas as threads | [NMc #17, #19] | Igual ao nosso `MapStorage` com trava [EH M24]: o conteúdo é problema do mod |
| 8 | Lista de tickers de tile entity do mundo esvaziada a cada tick; listas de jogadores filtradas por região e devolvidas como cópia | [NMc #20, +8; NAc +2.3] | **Não dá**: na 1.7.10 essas listas são campos públicos (seção 8, N4) |
| 9 | Sorteio (`RandomSource`) por região quando chamado de uma região | [NMc +3; NAc #31] | **Trazer**: é o tratamento de `World.rand` [EH M17] |
| 10 | Spawners de mods chamados uma vez por região, em paralelo, e só onde há jogador | [NMc #23, +11] | **Não copiar**: muda comportamento e chama código de mod em paralelo |
| 11 | Erro em tarefa: só uma linha de log; a entidade quebrada falha de novo todo tick e as seguintes do lote não ticam | [NMc #16, +4] | **Não copiar**: estado pela metade é a origem de dupe e perda |
| 12 | Geração de mundo em vários lotes paralelos, **mesmo** com os 4 interruptores desligados | [NMc #35, +1] | **Não copiar**: a geração da 1.7.10 não aguenta threads [EH H13, S15, S33] |
| 13 | Quatro interruptores por parte (ligados por padrão), sem chave geral; desligados, pacotes ainda vão para as regiões | [NMc #45-#47; NAc #48] | **Trazer a ideia** do interruptor por parte, **com** chave geral e tudo desligado por padrão |
| 14 | Parada do servidor fecha o pool sem esperar, cancela tarefas e salva | [NAc +2.1] | **Não copiar**: contraria a nossa regra de parada limpa |
| 15 | Mapa de dados por região nunca é limpo e é percorrido inteiro a cada tick | [NAc #26] | Defeito: custo do tick cresce com o que já foi visitado |
| 16 | Uma região nunca pode esperar outra (`ensureSafeBlockingAwait`) | [NAc #40; NMc #48] | **Trazer**: evita deadlock (threads paradas uma esperando a outra) |
| 17 | Nada reescreve bytecode de mods (`coremods/` sem nada de região) | [NMc +9] | Eles contam com portar mods depois. Nós não podemos (regra "rodar mods sem reescrever") |
| 18 | Thread principal sem proteção para acesso direto a bloco e entidade, enquanto as regiões rodam | [NMc #31; NAc #38] | Com barreira o problema some: a principal só roda com as regiões paradas |

Resumo: o NeoFolia escolheu desempenho antes de garantia em quase todos os pontos que importam para mods antigos (sem barreira, sem trava, resposta silenciosa, erro engolido). É o contrário da regra do projeto ("segurança antes de desempenho").

---

## 7. O que dá para trazer para Forge 1.7.10 + Crucible

Cada item diz de onde vem, por que cabe na nossa base e onde entra no desenho.

**D1. Geometria das regiões.** Seção de 16×16 chunks, margem de 1 seção, junção a até 3 seções. Os dois projetos concordam [FRc #3-#5; NAc #16, #19].
- Por que cabe: o mapa de chunks do Crucible já guarda os chunks em baldes de exatamente 16×16 chunks (`CBHM:119,141,154-156,168`, [EH H48]); criar o balde no ponto de junção torna barata a inserção de chunk [EH H02].
- Diferença obrigatória: a 1.7.10 não tem chunk holder nem ticket. A região só conhece chunks **carregados**. Os ganchos são a entrada no mapa (`CPS:261-262`: `loadedChunkHashMap_KC.add` e `loadedChunks.add`) e a saída (`CPS:567-568`) (conferido no Crucible nesta síntese), mais as outras vias de inserção (`CIOP:46-47`, `CW:301-302`, [EH H02, H04]). O único "ticket" que existe são os chunks forçados do `ForgeChunkManager`, que o Crucible tira da fila de descarga (`CPS:512-520`, conferido nesta síntese).
- Aviso de tamanho: com essa geometria, a menor região tem 48×48 chunks; duas áreas carregadas a até 48 chunks uma da outra (768 blocos) sempre viram uma só, de 49 a 63 chunks depende da grade, e a 64 chunks (1.024 blocos) ou mais nunca, salvo por cadeia [FRc #5, +7; conta corrigida na conferência final, ver seção 3]. Paralelismo só aparece entre bases afastadas (pergunta 3, seção 10).

**D2. O dono de uma região em execução não muda.** O Folia garante isso com estados (transiente, pronta, ticando, morta) e junção adiada [FR §3.5-§3.6]. O NeoFolia não garante e abre uma corrida em que duas threads podem mexer na mesma região [NAc #42; NMc #49].
- Por que cabe: o nosso Degrau B já tem um ponto de junção em série [02 §3]. Basta o regionizador mudar **só ali**: chunk carregado na fase paralela entra no mapa só na junção, como o estudo das estruturas hostis já pede para `loadedChunks` [EH H04, T5]. Sem trava na fase paralela (o Folia paga uma trava de escrita por mundo duas vezes por tick de região, [FRc +1]).
- No Degrau C, o modelo é o do Folia: região que tica não ganha seção nova; junção com ela fica para o fim do tick dela [FRc #9-#10].

**D3. Divisão segura.** A divisão do Folia só descarta entradas de seções apagadas por estarem mortas, isto é, sem chunk holder; ticks agendados nunca são descartados [FRc #50, +5].
- Por que cabe: com a mesma regra (só apagar seção sem chunk carregado), a divisão não perde dado de máquina. As estruturas que o nosso estudo manda ter "por região" (pendentes de tile entity, ticks agendados, block events, FakePlayer) precisam de uma função de juntar e uma de dividir, como o `RegionizedData` do Folia, que não podem bloquear nem lançar erro [FR §4.1; EH T2: M04, M09, M11, S06]. A regra de divisão do FakePlayer continua em aberto [EH S06].
- Apoio na 1.7.10 (conferido no Crucible na conferência final): o próprio vanilla já descarta tick agendado de chunk descarregado. Na hora de rodar, se o chunk não existe, o tick é reagendado (`WS:764`, `WS:795-797`), mas `scheduleBlockUpdateWithPriority` só guarda se o chunk existe (`WS:622`), então ele some da lista; ele não se perde porque foi gravado no chunk ao salvar (`AnvilChunkLoader.java:404`, `getPendingBlockUpdates(chunk, false)`). Descartar na divisão as entradas de seção sem chunk carregado repete esse comportamento.

**D4. Correio FIFO que preserva a ordem.** Do Folia: fila por região; na junção, a fila da região que some vai para a frente; na divisão, cada tarefa vai para a dona da sua posição; o dreno do começo do tick roda só o que já estava na fila [FGc #20-#23].
- Por que cabe: é o mecanismo que o nosso estudo chama de "fila aplicada no ponto de junção" (28 itens, [EH T5]) generalizado para o Degrau C: teleporte e portal [EH M27, H35, H49], kick e logout [EH H34], tarefas de plugin com local [EH K02].
- Ajuste para a 1.7.10: sem ticket, não dá para "fazer a região existir" sem carregar o chunk. Tarefa endereçada a posição sem região roda na fase serial.
- Duas regras de espera: do NeoFolia, "uma região nunca espera outra" [NAc #40]; do nosso estudo, "nunca esperar a thread global dentro da fase paralela" (ela está parada na barreira: deadlock) [EH H07, K10].
- Teto na fila global por tick: o Folia drena sem teto e uma tarefa que se repõe prende a global [FGc +3]; o NeoFolia usa 1.024 tarefas ou 10 ms [NMc +6].

**D5. Junção forçada.** O Folia já força duas regiões a virarem uma pondo tickets num caminho entre veículo e passageiro, "force connect the chunks" (A12 84-113) [FGc +4]. Que isso de fato junte as regiões depende do elo "ticket cria chunk holder", que é do Paper: não verificado.
- Por que cabe: o regionizador é nosso; não precisamos de ticket nem de carregar os chunks do meio. Basta um "vínculo" entre duas seções, registrado por módulo ou pela lista do observador, que mantém as duas no mesmo dono. É a regra "juntar, não mandar correio" do prompt (item 6). Região que não é contígua: hipótese, a confirmar em protótipo.

**D6. Pacotes do jogador na região do jogador (só no Degrau C).** Os dois projetos processam os pacotes do jogador na região dele [FGc #79-#80; NMc #32-#33].
- Por que cabe: no Crucible, quase todo pacote, inclusive pacote de mod, roda na thread do servidor durante `networkTick` (`MS:1023`): `NetworkManager.channelRead0` põe na fila todo pacote cujo `hasPriority()` é falso (`MC/net/minecraft/network/NetworkManager.java:135-148`); o padrão é falso (`Packet.java:57-60`) e o `FMLProxyPacket` dos mods não muda isso; a fila é processada em `processReceivedPackets` (`NetworkManager.java:221-245`) (conferido no Crucible nesta síntese). Exceções que rodam na thread do Netty (conferido na conferência final): os pacotes com `hasPriority()` verdadeiro (keep-alive `C00PacketKeepAlive`, chat sem "/" `C01PacketChatMessage`, handshake `C00Handshake`); o aperto de mão do FML (`FML|HS`, `REGISTER`, `UNREGISTER`), tratado dentro de `NetworkDispatcher.channelRead0` (`MC/cpw/mods/fml/common/network/handshake/NetworkDispatcher.java:309-334`); e o repasse do Cauldron do pacote de canal de mod para os ouvintes de mensagem de plugin do Bukkit (`dispatchIncomingMessage`, mesmo arquivo, linha 339), antes de o pacote entrar na fila. Foi nessa fase que o clique do ChromatiCraft parou o servidor por 30 s (D-21). Só regiões independentes com o pacote na região do jogador isolariam isso [DIAGNOSTICO §7].
- No Degrau B os pacotes continuam na fase serial [CT §5]. Exceção já existente: chat sem "/" roda na thread do Netty [CT §2].

**D7. Rascunhos e sorteio por thread.** O Folia trocou os estáticos de contexto do `CraftEventFactory` por `ThreadLocal` e fez o sorteio compartilhado de entidades por thread [FPc #34; FGc #78]; o NeoFolia tem um sorteio por região [NMc +3].
- Por que cabe: são exatamente os tratamentos que o nosso estudo já pedia para `CEF:64-65` [EH K03] e `World.rand` [EH M17]. Confirma o grupo T3 [EH §4].

**D8. Parada que espera as regiões e salva depois.** O Folia para por uma thread própria: espera o pool até 60 s, termina teleportes pendentes, salva região por região [FRc #42, +8; FGc +13]. O NeoFolia é o contraexemplo [NAc +2.1].
- Por que cabe: é a mesma lição do D-24 (o gancho de SIGTERM chama `stopServer()` sem parar o tick) e da correção descrita em [CT §3] (pedir a parada e esperar a thread do tick). Com regiões: parar de aceitar tarefas, esperar as regiões na barreira, aplicar o correio pendente, e só então salvar, numa thread só.

**D9. Conter o erro no menor escopo possível.** No Folia, erro ao processar pacote expulsa o jogador; erro em tarefa de plugin vai ao log do plugin; só o erro que escapa do tick para o servidor (de forma ordenada) [FGc #28]. O NeoFolia engole tudo [NMc #16].
- Por que cabe: pacote e tarefa de plugin são fronteiras naturais no Crucible também. Para erro no tick do mundo de uma região, a escolha entre parar limpo e rebaixar a dimensão é do admin (pergunta 2). Cuidado para a opção "rebaixar": no Folia a região que falha **não é liberada** (fica "ticando" para sempre e as vizinhas que querem se juntar a ela ficam esperando) [FRc +9]; lá isso não importa porque o servidor para, mas um modo que segue rodando precisa liberar ou isolar a região falha.

**D10. Vigia e TPS por região.** O Folia tem um vigia que escreve a pilha da região travada a cada 5 s [FRc #41] e `getRegionTPS`, legível de qualquer thread [FPc +1]. O NeoFolia não tem vigia de região [NAc #56].
- Por que cabe: o vigia do Spigot no Crucible só conhece a thread principal [EH K20; CT §2]. Entra no nosso vigia (9) e no `/hoshie` (10).

**D11. Interruptor por parte, com chave geral.** Ideia do NeoFolia (quatro interruptores) [NAc #48], corrigida: chave geral que desliga tudo, inclusive pacotes e geração, e tudo desligado por padrão (decisões 18 e 23).

**D12. Thread de região reconhecida como thread do servidor.** No Folia as threads de região são subclasses de `TickThread`, o tipo que o código do Paper reconhece (pista; o corpo das checagens do Paper está fora do clone) [FPc +9].
- Por que cabe: no Crucible o FML decide "sou servidor" pelo **nome** da thread (`FMLCommonHandler.getEffectiveSide`, `MC/cpw/mods/fml/common/FMLCommonHandler.java:156-165`, conferido nesta síntese) e o Bukkit compara com `primaryThread` [EH S30, K09]. Confirma o grupo T9.

**D13. Checagem de dono por seção.** No Folia a checagem com raio confere todas as seções da área [FGc #56].
- Por que cabe: com o mapa seção → região, a checagem do observador é uma consulta e uma comparação de referência, nos pontos de maior cobertura do Crucible (`provideChunk`, `setBlock` do chunk, entrada de entidade, agendamento de tick) [CT §4].

**D14. Hora do mundo copiada no começo do tick da região (só no C).** O Folia avança o game time só na global e cada região copia no começo do tick [FRc #31]. Mesmo tratamento que o nosso estudo previa para `worldInfo` [EH M30, T6]. Ver N8: o relógio continua único.

**D15. A lista do que o Folia teve de desligar é uma lista de trabalho.** Placar, criar e descarregar mundo, `/save-all`, comandos de servidor inteiro, bloco de comando, chat síncrono [FGc #72, +7; FPc #42-#44, +2-+3]. No nosso Degrau C, cada um desses fica na fase serial em vez de desligado [EH M29, K11, K13, K29].

---

## 8. O que não dá

**N1. Tirar a thread principal (Folia).** Os eventos de tick do Forge disparam na thread do servidor: `onPreServerTick` e `onPostServerTick` em `MS:829` e `MS:894`, `onPreWorldTick` e `onPostWorldTick` em `MS:957` e `MS:1006` (conferido no Crucible nesta síntese). São 55 jars no primeiro e 41 no segundo, e eles guardam estado global [CT §5; EH S04]. O agendador do Bukkit (`MS:904-906`) e os pacotes (`MS:1023`) também moram ali [CT §1.3]. O Folia passou o seu evento de início de tick para "uma vez por região, em paralelo" [FPc #27]; o equivalente Forge chamaria os handlers de 55 mods N vezes ao mesmo tempo. **Conclusão:** a global é a thread do servidor rodando a fase serial.

**N2. Regiões sem barreira (NeoFolia).** `loadedEntityList`, `loadedTileEntityList` e `playerEntities` são campos públicos de `World` (`W:129,131,134`, conferido nesta síntese), lidos direto por 34, 26 e 63 jars [CT §4; D-27]. Elas não podem mudar de tamanho enquanto alguém lê [EH M02, M04, M07]. Sem barreira, um handler de `WorldTickEvent` lê essas listas enquanto as regiões as mudam; o NeoFolia não checa a thread principal [NMc #31]. Com barreira, a principal só roda com as regiões paradas.

**N3. Checagem que "falha com força" como padrão (Folia).** Com 292 mods que nunca respeitaram dono, exceção no tick pararia o servidor a toda hora [FRc #42]. E na 1.7.10 a maior parte do acesso nem passa por método onde pôr a checagem (campos públicos, [CT §4]). O próprio Folia deixou buracos: leitura de bloco de outra região do mesmo mundo sem checagem [FGc #67] e jogador sem checagem [FPc #38]. Exemplo concreto do custo: no Folia, agendar tick de bloco num chunk de outra região lança exceção ("Cannot schedule tick for another region!", A4 20437) [FRc +4; FGc #44]; mods 1.7.10 chamam `scheduleBlockUpdate` em vizinhos sem conferir nada. **Conclusão:** observador primeiro (Etapa 2), e a reação padrão é registrar e rebaixar.

**N4. Responder em silêncio (NeoFolia).** `null` para tile entity de outra região, `VOID_AIR` para bloco, `false` para `setBlock` que vai acontecer depois, lista de jogadores cortada [NM §2.6; NAc +2.2, +2.3]. Mod 1.7.10 lê `null` como "não há máquina" ou "foi removida" e pode corromper o próprio estado [NM §2.6]. Filtrar no "getter" nem é possível: a lista de jogadores do servidor é o campo público `SCM.playerEntityList`, lido direto por 46 jars [CT §4]. **Regra:** não mentir para o mod.

**N5. Embrulhar capabilities.** O Forge da 1.7.10 não tem sistema de capability: não existe o pacote `net/minecraftforge/common/capabilities` na árvore; existe `IFluidHandler` (`MC/net/minecraftforge/fluids/IFluidHandler.java`); `IEnergyHandler` não está no Forge (é do CoFH) (conferido no Crucible nesta síntese). Cada mod faz `world.getTileEntity` e converte para `IInventory`, `ISidedInventory`, `IFluidHandler` ou API própria. Não há uma porta onde embrulhar. **Saída:** junção por proximidade, junção forçada (D5) e o observador contando quantas chamadas cruzam região [EH H15; CT §4].

**N6. Teleporte assíncrono obrigatório (Folia).** `Entity.travelToDimension` (`E:2469`) e `SCM.transferPlayerToDimension` (`SCM:940-955`; em `SCM:945` o comentário do Cauldron diz "mods such as Twilight Forest call this method directly") são síncronos e chamados de código de mod dentro do tick (conferido no Crucible nesta síntese). Converter todos os chamadores seria corrigir mod por mod, a armadilha do TickThreading [02 §2]. **Saída:** adiar para o ponto de junção [EH M27, H35, H49]. Sobre a cópia do objeto que o Folia faz [FGc +5]: na 1.7.10 a troca de dimensão de entidade que não é jogador **já cria uma cópia** (`E:2528`, `createEntityByName`; `E:2532-2533`, `copyDataFrom` e `spawnEntityInWorld`; `E:2540`, `isDead = true`), mas o teleporte no mesmo mundo não (conferido no Crucible nesta síntese). O jogador não é copiado: `EntityPlayerMP.travelToDimension` (linha 694) sobrescreve o método e chama `transferPlayerToDimension` com o mesmo objeto, como o Folia (conferido na conferência final). Não devemos passar a copiar no mesmo mundo: mods guardam referência à entidade.

**N7. Exigir marca dos plugins ou quebrar o `BukkitScheduler` (Folia).** São 65 plugins 1.7.10; nenhum conhece Folia. Os eventos de plugin já passam por uma trava global (`SPM:482-496`, [EH K01]), que é o nosso caminho de compatibilidade. Os quatro agendadores novos do Folia são API do Paper, que não existe para plugins 1.7.10 [FP §4.1].

**N8. Relógio por região visível aos mods (Folia).** `World.getTotalWorldTime()` lê `worldInfo` (`W:4358-4361`, conferido nesta síntese) e `MinecraftServer.currentTick` é estático (`MS:186`), lido por fornalha, alambique, item no chão e zumbi [EH S17]. No Folia, a correção de prazos na junção é campo a campo e o `BlockEntity` base não corrige nada [FRc +3]; região nova nasce com relógio zerado [FRc +2]; e o contador global de ticks deixou de andar [FGc +8]. Dado de mod nunca seria corrigido. **Conclusão:** um relógio por mundo, avançado na fase serial; no C a região só copia a hora no começo do tick (D14).

**N9. Cortar atualização de bloco na borda (Folia A7, portado pelo NeoFolia).** Os dois cortam vizinho, redstone, trilho e tripwire fora da região [FGc #40-#44; NAc #44]. Para redes de mod, corte é item ou energia perdidos, e o teste de conservação exige diferença zero (PROMPT, metas). **Regra:** juntar, não cortar. O alcance real das consultas na borda é medido pelo observador [EH T8, H40].

**N10. Recalcular as regiões com as regiões rodando (NeoFolia).** Ver D2: o mapa só muda no ponto de junção.

**N11. Descartar ticks de região lenta em silêncio (NeoFolia).** Lá entidades e tile entities pulam ticks enquanto ticks agendados acumulam: as partes da mesma região saem de passo [NMc #15, +7]. O Degrau A faz o mesmo efeito **de propósito**: a região inteira pula junto, com aviso e config [02 §3]. Os dois projetos também não recuperam ticks perdidos [FRc #38], o que reforça a pendência 5 do admin (máquinas mais lentas na base pesada).

**N12. Espera bloqueante na thread principal (NeoFolia).** `awaitRegions` e `callOn...AndWait` param o tick inteiro até 60 s e podem travar dois chamadores que reservam as mesmas regiões em ordem diferente (inferência) [NAc +2.5]. No nosso B a principal já espera na barreira; outra espera vira risco de deadlock [EH H07, K10].

**N13. Geração de mundo paralela (NeoFolia, sem interruptor).** A geração da 1.7.10 não aguenta threads: gerador com sorteio e buffers compartilhados, `IntCache` global, `BiomeDecorator` que lança erro depois de cerca de 10 s esperando [EH H13, S15, S33]; e carregar chunk já gerado também roda gerador de estrutura [EH H43]. **Regra:** porta única de carga por mundo [02 §5].

**N14. As fórmulas automáticas de threads.** Folia: 1 thread até 15 núcleos, 2 com 16 (A4 5786-5800; a [FGc #5] dizia "até 9", o que é verdade mas esconde que de 10 a 15 também dá 1). NeoFolia: 1 thread com 8 núcleos [NAc #28]. Na máquina de produção (7 vCores) as duas dariam 1 thread, ou seja, nenhum ganho. Mantemos o número explícito, 3 a 4 (decisão 9).

---

## 9. O que muda no nosso desenho

Mudanças já escritas em `docs/02-arquitetura.md`, cada uma marcada "(estudo Folia x NeoFolia, 2026-11-22)". Nenhuma decisão registrada foi apagada.

1. **§2, fatos:** a linha do Folia passa a citar o código (antes só a documentação); entra uma linha sobre o NeoFolia.
2. **§3, degraus:** o Folia é o Degrau C puro; o NeoFolia é um "B sem barreira". Reforça a ordem B antes de C.
3. **(2) Regionizador:** correção da regra "região que tica não cresce" (vale para seções novas, não para chunks nas seções que já são dela); geometria copiada com aviso de tamanho (D1); gancho em carga e descarga de chunk, porque a 1.7.10 não tem chunk holder; mapa de regiões só muda no ponto de junção (D2); divisão só apaga seção sem chunk carregado (D3).
4. **(3) Núcleo de roteamento:** reação padrão a acesso fora do dono = registrar e rebaixar; nem exceção que derruba (N3) nem resposta silenciosa (N4); checagem por seção (D13).
5. **(4) Módulos:** a porta única de capabilities do NeoFolia não existe na 1.7.10 (N5).
6. **(5) Thread global:** é a thread do servidor rodando a fase serial, com barreira (N1, N2); fila com teto (D4); no C, ouvintes globais de mods não liberados só com as regiões da dimensão paradas (pergunta 1).
7. **(6) Correio:** regras de ordem, dreno limitado, nenhuma espera entre regiões nem da global, posição sem região vai para a fase serial (D4); junção forçada por vínculo de seções (D5).
8. **(7) Plugins:** confirmado que não há modelo para copiar; `BukkitScheduler` nunca lança (N7).
9. **(9) Vigia e modo seguro:** chave geral (D11); política de erro (D9, pergunta 2); parada que espera as regiões (D8); vigia por região (D10).
10. **(10) Medição:** TPS por região legível de qualquer thread (D10).
11. **§5, bordas:** teleporte sem cópia no mesmo mundo (N6); a margem não bastou nos dois projetos, então o observador mede e o excesso vira junção (N9); nota sobre travessia de entidade.
12. **§6, riscos:** novo risco 8, tamanho das regiões (D1).
13. **§7, decisões:** três itens novos em "ainda dependem do admin".

O que **não** muda: a base (Crucible), a ordem em degraus, o Degrau 0, o Degrau A, a decisão de começar o B só com tile entities e entidades (decisão 15, que o NeoFolia reforça: ele também separa o trabalho por tipo), o grupo de 3 a 4 threads (decisão 9) e a regra "juntar, não mandar correio".

---

## 10. Perguntas ao admin

1. **Degrau C e os eventos de tick dos mods.** Os ouvintes de `ServerTickEvent` e `WorldTickEvent` de mods não liberados só podem rodar com as regiões da dimensão paradas (nem o modelo do Folia, "uma vez por região", nem o do NeoFolia, "junto com as regiões", servem a mods 1.7.10). Proposta: o Degrau C vale **por dimensão**, só onde todos os ouvintes globais que mexem naquela dimensão estão liberados; o resto fica no Degrau B. Concorda em limitar o C assim?
2. **Erro dentro de uma região.** O Folia para o servidor de forma ordenada; o NeoFolia registra e segue (o que deixa estado pela metade). Proposta: erro em pacote expulsa o jogador; erro em tarefa de plugin vai ao log; erro no tick do mundo de uma região → **parada limpa por padrão** (segurança antes de desempenho), com uma opção no `hoshiestar.yml` para, em vez disso, rebaixar a dimensão para série e seguir. Qual padrão prefere?
3. **Distância entre as bases.** Com a geometria dos dois projetos, áreas carregadas a até 48 chunks (768 blocos) uma da outra sempre viram uma região só; de 49 a 63 chunks (até cerca de 1.000 blocos) depende de onde caem na grade; a 64 chunks (1.024 blocos) ou mais, só se houver outras áreas carregadas no meio. Uma região usa uma thread por vez. As bases pesadas do servidor costumam ficar mais longe que isso umas das outras? O medidor (pendência 4) responde em parte. Diminuir a seção para 8×8 chunks diminui as regiões, mas a margem cai para 8 chunks, que é pouco para redes de mod.

---

## 11. Não verificado (consolidado)

**Folia: código do Paper ou de bibliotecas, fora do clone**
- Corpo de `TickThread.ensureTickThread` e `isTickThread` (se lança e qual exceção) [FGc #59; FPc #64].
- Interfaces e implementações dos agendadores de plugin (`RegionScheduler`, `GlobalRegionScheduler`, `AsyncScheduler`, `EntityScheduler`) e a classe `EntityScheduler` [FPc §3].
- Se todo método do `BukkitScheduler` passa por `CraftScheduler.handle` [FPc #9].
- Valor e efeito de `MAX_TICKET_LEVEL`: se o ticket só mantém o chunk holder ou carrega o chunk; portanto, se "postar tarefa não carrega o chunk" [FGc #19; FRc #47; FPc #15].
- Quem garante que `addChunk`/`removeChunk` nunca rodam em paralelo para a mesma seção [FRc §3].
- Como o pool escolhe a próxima região, quando roda tarefas entre ticks e se a região troca de thread no EDF; semântica de `Schedule.getPeriodsAhead/advanceBy` [FRc §3].
- O que `OSNuma.getTotalCores()` conta (núcleo físico ou lógico) [FRc §3].
- O que o vigia antigo do Spigot ainda faz no Folia (reiniciar ou derrubar) [FRc #41].
- Barramento de eventos do Paper com eventos "síncronos" em thread de região; `isPrimaryThread`, `AsyncCatcher`, `MCUtil.isMainThread` [FPc §3, +9].
- Como `folia-supported` do `paper-plugin.yml` vira o campo; quem captura a exceção do plugin barrado [FPc §3].
- Thread de eventos específicos (dano, entrada, saída, portal, respawn) além da regra geral do README; "modificador async obsoleto" [FPc §3].
- Thread do pacote de handshake [FGc #81].

**Folia: leitura sem execução**
- Como uma entidade muda de região andando [FRc §3].
- "Portal volta à origem" na parada (só documentação; o código põe no mundo da região que para) [FGc #33].
- `NullPointerException` ao ler bloco fora do pool; se `tickCount` pode passar de 1 no relógio global; efeito da folga de 10 ns em `haveTime` [FGc §3; FRc #40].
- Nome exato do método do trecho de `Entity` com raio de 16 [FGc #50].
- Comportamento em produção: tamanho típico de região, frequência de junção e divisão, custo da trava [FRc §3].

**NeoFolia**
- Issues, notas de versão, estrelas e forks: HTTP 403 nesta sessão [NAc #7; NMc §3].
- Versão de base do NeoForge e se as funções `fire*` são iguais às do NeoForge [NAc #47].
- Se o barramento `net.neoforged:bus:8.0.5` aguenta `post` concorrente [NMc §3].
- Corridas deduzidas do código, sem teste: troca de dono no meio de um lote, thread principal contra regiões, impasse entre dois chamadores de `awaitRegions`, escrita durante o salvamento na parada [NAc #42, §3].
- Não lidos a fundo: `RegionizedChunkExecutors`, `ChunkTaskDispatcher`, `ServerExplosion.java.patch`, `ForcedChunkManager`, pastas `rejects_*` [NAc §3].
- Cadeia até `Player.tick()`; em que thread rodam as etapas de decoração da geração; se `Level.random` é público; origem do `ConcurrentHashMap` do `SavedDataStorage`; thread de `ChunkDataEvent.Save` e `ChunkEvent.Unload`; caminho de `HandlerThread.NETWORK` [NMc §3].
- Comportamento real com mods: nada compilado, nenhum teste rodado [NM §4].

**Desta síntese (inferências para a 1.7.10, a provar em protótipo ou medida)**
- Que um "vínculo" entre seções não contíguas funcione como junção forçada sem efeito colateral (D5).
- Que tarefa para posição sem região possa sempre esperar a fase serial sem mudar comportamento visível (D4).
- Quantas regiões o mapa de produção teria com a geometria copiada: depende da distribuição das bases; não medido (D1, pergunta 3).
- Custo da consulta seção → região em cada checagem do observador: não medido (D13).
- Que mover os pacotes do jogador para a região dele no Degrau C isole um caso como o D-21 sem quebrar mods que tratam pacote supondo a thread do servidor: hipótese (D6).
- (conferência final) Quem é dono de um chunk carregado **durante** a fase paralela até o ponto de junção, já que o mapa de regiões só muda ali (D2): falta regra (por exemplo, dono provisório = região que pediu a carga, pela porta única de carga [EH H07]).
- (conferência final) O que é "fase serial" no Degrau C, que não tem barreira: D4 ("posição sem região roda na fase serial"), N1 e a fila global do C dependem dessa definição; hoje ela só existe para o Degrau B.
