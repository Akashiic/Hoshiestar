# Hoshiestar: servidor híbrido multithread para Forge 1.7.10 (inspirado no Folia)

> Prompt do projeto, versão 2. Parte do prompt original e inclui o que faltava.
> Os trechos novos estão marcados com **[novo]**. Os fatos do ambiente foram
> verificados no pacote real do servidor (ver `DIAGNOSTICO.md`).

## **[correção do admin, 2026-11-22]** O que é o Hoshiestar
O Hoshiestar é um **software híbrido: um `server.jar` próprio**, fork do Crucible (`staging@67b53034`), que carrega tudo sozinho. **Não é um mod e não entregamos mods.** O admin troca o `server.jar` e pronto; nenhum jar nosso vai em `mods/` nem em `plugins/`.
- Correção em classe do próprio servidor (Minecraft, Forge, CraftBukkit, Crucible): vira **patch no fork**, no estilo do upstream (patch gerado, marcação `// Hoshiestar`).
- Correção em mod de terceiros (Mekanism, BetterQuesting, Ars Magica 2, ChromatiCraft e os que vierem): vai **embutida no jar** e é aplicada pelo próprio servidor quando o mod alvo está presente e a **trava de versão** (SHA-256 da classe original do mod) confere. Se não confere, a correção não é aplicada e o servidor avisa.
- O medidor de carga por região e o vigia de tick lento são parte do jar.
- Um interruptor por recurso, no config do servidor (`hoshiestar.yml`, no estilo do `Crucible.yml`). Desligado = comportamento original. Padrão: tudo desligado, menos o vigia.

## Seu papel
Você é o Engenheiro-chefe sênior de servidores Minecraft e Java (concorrência e JVM). Você é responsável por este projeto de ponta a ponta: planejar, delegar, revisar e entregar.

**[novo]** Ao começar ou retomar o trabalho, leia primeiro `PROGRESSO.md` e `DIAGNOSTICO.md`. Não refaça o que já está registrado lá.

## Contexto
Sou administrador de um servidor survival com mods na 1.7.10 (Forge + plugins Bukkit). Com muitos players, cada um com sua base pesada, a thread única do Minecraft vira o gargalo: uma base que laga derruba o TPS do servidor inteiro. Temos máquina com muitos núcleos sobrando. Quero criar um software próprio, o **Hoshiestar**, que aplique a filosofia do Folia (mundo dividido em regiões, cada região em sua thread) ao Forge 1.7.10, com suporte a mods E plugins. Não é um port do Folia: é um projeto novo, inspirado nele, que vai além dele.

### **[novo]** Fatos já verificados do ambiente
- A produção já roda **Crucible** `1.7.10-staging-67b53034` (Forge 10.13.4.1614, CraftBukkit `v1_7_R4`), em Java moderno (17/21) com `java9args.txt`, lwjgl3ify 3.0.31 e RetroFuturaBootstrap 1.1.0.
- **UniMixins 0.3.1** já está instalado, e os mods próprios da rede (`Akashic-*`) já usam mixins "early" e "late" nesse servidor. Mixin sobre as classes do Crucible funciona em produção.
- O modpack tem 227 jars de mod (292 mods para o Forge) e 65 plugins. No boot o servidor carrega cerca de **60 dimensões**.
- Já existem ferramentas de medição instaladas: spark, Opis e Sampler.
- Já existem mods próprios que tiram trabalho da thread principal ou limitam carga: `SaveRelief` (salvamento em thread de I/O), `PlayerChunkAsync`, `TickRelief`, `MekThrottle`, `BQRelief`. A Etapa 1 começa auditando esses, não refazendo.
- O pacote do servidor **não traz**: mundo, `server.properties`, `bukkit.yml`, `spigot.yml`, `cauldron.yml`, `Crucible.yml` de produção, nem o script de inicialização (flags da JVM e `-javaagent`).
- Cliente: modpack TEOF 6.3. O Hoshiestar não pode exigir mudança no cliente.
- **Máquina de produção**: 7 vCores e 17 GB de RAM (heap de 14 GB, G1). "Muitos núcleos sobrando" são, na prática, 3 a 4 núcleos para threads de região.
- **Perfil de produção medido** (8 jogadores): 11,8 TPS, 84 ms por tick, thread principal sem nunca dormir. Do tick, 67% é trabalho local de região, 18% handlers globais de mods, 10% rede, 5% plugins, 3% disco e chunks. Detalhes em `DIAGNOSTICO.md` §6.
- A base foi reconstruída do zero e bate com o jar de produção (`docs/pesquisa/crucible-build.md`).

## Objetivo final
- Lag isolado: uma base pesada laga só a região dela; o resto do servidor fica liso.
- Suportar 100–200 players espalhados pelo mapa.
- Rodar mods e plugins Bukkit sem precisar reescrever nenhum deles.

### **[novo]** O que NÃO é objetivo
- "Zero lag". Uma base pesada continua pesada; o objetivo é ela não arrastar as outras.
- Rodar 100% dos mods em paralelo. Mod que não aguenta paralelismo roda em série, como hoje.
- Mudar gameplay, receitas ou o modpack do cliente.

## Metas que dá para medir
- Com uma região sob carga pesada, as outras regiões mantêm TPS próximo de 20.
  **[novo]** Número: região A forçada a 150 ms ou mais por tick; as demais ficam com 19 TPS ou mais e p95 de tempo de tick de 50 ms ou menos.
- Nenhuma corrupção de mundo, chunk ou inventário em nenhum teste.
- **[novo]** Nenhuma duplicação nem perda de item ou energia (teste de conservação).
- **[novo]** Sem regressão: com todos os interruptores desligados, comportamento igual ao Crucible original e desempenho dentro de 5%.
- **[novo]** Estabilidade: 24 h de teste contínuo sem crash, sem deadlock (threads travadas uma esperando a outra) e sem vazamento de memória.
- Cada etapa precisa passar nos testes dela antes da próxima começar.

Se alguma meta se mostrar impossível, diga o motivo com evidência.

## Arquitetura desejada
1. **Base**: partir de um híbrido Forge+Bukkit existente para 1.7.10 (avalie Crucible e Thermos e recomende um, com justificativa). Verifique também licença e estado de manutenção de cada um.
   **[novo]** Avaliação feita: Crucible, branch `staging`, a partir do commit `67b53034` (igual à produção). Licença GPLv3: o Hoshiestar também será GPLv3 e o repositório só pode conter patches e código próprio (nada de código da Mojang nem jars de mods).
2. **Regiões**: mundo dividido em regiões, cada uma com seu próprio tick e seu próprio TPS, distribuídas entre os núcleos de forma equilibrada.
   **[novo]** Regras de dono obrigatórias: um chunk pertence a uma região só; regiões vizinhas dentro do raio de junção viram uma; região só cresce, junta ou divide no fim do tick. Toda leitura/escrita de mundo passa por uma checagem "esta thread é dona desta posição?" (a base não tem essa checagem, precisa ser criada).
3. **Núcleo de roteamento** (Mixin e/ou Java Agent): intercepta os pontos por onde todo mod passa (colocar/quebrar bloco, inventários, entidades, tile entities) e garante que cada ação rode na thread da região dona daquela posição.
   **[novo, corrigido em 2026-11-22]** Como o Hoshiestar é o próprio `server.jar`, os pontos do Minecraft/Forge/CraftBukkit são interceptados por **patch direto no fork**. Classes de mods de terceiros são alteradas por um transformador embutido no servidor, com trava de versão. Nada de mod nosso com Mixin; o UniMixins do modpack continua lá para os mods que já o usam.
4. **Módulos**: casos específicos (IC2, Thermal, Mekanism, AE2 etc.) ganham módulos plugáveis, sem mexer no núcleo.
5. **Thread global**: para coisas leves que valem pro servidor todo (economia, ranking, chat). Acesso a banco de dados vai para threads de I/O; qualquer efeito no mundo (ex.: entregar item da loja) volta para a região do player.
6. **"Correio" entre regiões**: regiões ligadas por cabos/canos trocam mensagens com atraso de 1 tick, sem uma esperar pela outra. Identificar o que não tolera esse atraso (ex.: cálculo de crafting do AE2, redstone sensível a tempo) e tratar à parte.
   **[novo]** Padrão é **juntar, não mandar correio**: duas regiões ligadas por uma rede (AE2, EnergyNet do IC2, conduítes do EnderIO, tubos, eixos do RotaryCraft) viram uma região só. Correio com atraso só para o mod que tiver módulo provando que aguenta.
7. **Adapter de plugins**: interceptar o agendador do Bukkit e mandar cada tarefa para a região certa ou para a thread global.
   **[novo]** Plugin 1.7.10 não é feito para threads. Por padrão, código de plugin roda um de cada vez (em série). Só entra em paralelo o plugin que estiver numa lista de liberados depois de testado.
8. **Extras**: prioridade de CPU para o spawn (área de eventos, quase vanilla); alerta automático ao admin quando uma região começar a pesar, indicando a base.
9. **[novo] Modo seguro**: tudo que ainda não foi classificado roda em série, como hoje. O config tem uma lista "só em série" por mod e por dimensão. Um vigia detecta deadlock e violação de dono; ao detectar, registra e pode voltar o servidor para thread única sem derrubar.
10. **[novo] Eventos de tick globais dos mods**: definir em que thread rodam `ServerTickEvent`, `WorldTickEvent` e dados `WorldSavedData`. Inventário estático: 55 jars usam o primeiro, 41 o segundo, 34 o terceiro. Esse é o ponto que mais decide se um mod pode rodar em paralelo.
11. **[novo] Dimensões**: com ~60 dimensões, "uma thread por dimensão" é um degrau intermediário mais simples que regiões e precisa ser avaliado antes.
12. **[novo] Bordas e travessias**: definir o que acontece quando jogador, entidade, pistão, fluido, explosão ou redstone cruza a borda de uma região; teleporte e portal entre regiões/dimensões; chunk loaders (ForgeChunkManager, ForbiddenChunkLoaders) e FakePlayers.
13. **[novo] Medição por região**: TPS e tempo de tick por região e por dimensão, comando para o admin ver, e perfil por thread no spark. `/forge tps` e Timings assumem tick único e não servem sozinhos.

### **[novo]** Plano B (se regiões em paralelo não fecharem para parte dos mods)
"Orçamento de tick por região" em thread única: a base que estoura o orçamento passa a ticar menos vezes por segundo; o resto do servidor continua a 20 TPS. Entrega o objetivo nº 1 (lag isolado) com muito menos risco e convive com as threads depois. Deve ser implementado e medido **antes** das regiões em paralelo.

## Ordem de trabalho (por etapas, cada uma estável antes da próxima)
- **Etapa 0 – Medição**: perfilar o servidor com o modpack real e listar o que mais pesa.
  **[novo]** Inclui: ambiente de teste reproduzível, inventário estático dos mods, linha de base ociosa, compilar o Crucible do zero (provar que a base é reconstruível) e validar bots de teste.
- **Etapa 1 – Tirar da thread principal o que é independente**: geração de chunks, salvamento em disco, cálculo de luz, carregamento de dados/chunks no login.
  **[novo]** Revista com o perfil de produção: disco, luz e geração somam 2,9% do tick. A etapa passa a ser "ganhos baratos medidos" (Degrau 0): os pontos que o perfil mostrou valer até ~20% do tick sem threads. O plano original fica como reserva (`docs/03-plano-etapa-0-1.md`).
- **Etapa 2 – Núcleo de roteamento em modo "observador"** (patches no fork): só registra quais mods mexem fora da própria área, sem mudar comportamento. Gera a lista de mods que vão precisar de módulo.
- **[novo] Portão "vai / não vai"**: com os dados da Etapa 2, decidir com o admin se segue para regiões em paralelo, se fica no Plano B, ou um misto por dimensão.
- **[novo] Etapa 2.5 – Orçamento de tick por região** (thread única) e avaliação de threads por dimensão.
- **Etapa 3 – Divisão por regiões + thread global + correio entre regiões.**
- **Etapa 4 – Adapter de plugins.**
- **Etapa 5 – Módulos dos mods pesados, um por vez.**

## **[novo]** Testes obrigatórios
- **Ambiente**: o servidor de teste é montado por `scripts/prepare-test-env.sh` a partir do pacote de produção.
- **Diferencial**: mesmo mundo, mesmo número de ticks, com recurso desligado e ligado; comparar blocos, tile entities e inventários.
- **Conservação**: contar itens e energia antes e depois; diferença tem que ser zero.
- **Bordas**: rede cruzando regiões, pistão/redstone/fluido/explosão na borda, teleporte, portal, chunk loader, jogador cruzando borda, login e logout em massa.
- **Queda**: matar o processo no meio do salvamento e conferir que o mundo abre íntegro.
- **Longa duração**: 24 h.
- **Carga**: bots com o handshake do Forge (a validar na Etapa 0) e uma cópia de mundo com bases reais, fornecida pelo admin.
- Sem carga real medida, não se declara meta de TPS cumprida.

## Segurança do mundo (regra máxima)
- Nunca mexa no servidor real. Trabalhe só em uma cópia de teste do mundo e do modpack.
- Faça backup antes de cada teste que escreve no mundo.
- Cada recurso novo precisa ter um interruptor no config para desligar e voltar ao comportamento original.
- Use Git: uma branch por etapa e um commit por mudança, para poder voltar atrás.
- Prefira segurança (sem corrupção, sem perda de item) a desempenho.
- **[novo] Segredos**: o pacote do servidor tem senhas e tokens (banco MySQL, bot do Discord, pagamentos). Nunca commitar, nunca colar em conversa, nunca enviar para serviço externo.
- **[novo] Serviços reais desligados**: no teste ficam fora os plugins que falam com o mundo real (Discord, punições, pagamentos, votos, proxy) e os que atrapalham medição. A lista e o motivo de cada um ficam no script.
- **[novo] Rede**: servidor de teste só em `127.0.0.1`, `online-mode=false`.
- **[novo] Licença**: nada de jar de mod, plugin, mundo ou código da Mojang no repositório.

## Limites do que você consegue fazer
Você pode ler código, compilar e rodar testes que não dependam do jogo. Talvez não consiga abrir o servidor com o modpack real nem simular dezenas de players. Se não conseguir medir TPS ou testar algo, não invente resultado. Entregue o .jar e um roteiro curto de teste (usando Spark, /forge tps ou o profiler do modpack), dizendo o que eu devo medir. Eu te devolvo os números.

**[novo]** Verificado: o servidor com o modpack real **sobe** no ambiente de trabalho (4 núcleos, 15 GB de RAM). Dá para testar boot, estabilidade e corretude. **Não** dá para medir escala (100–200 players, muitos núcleos): esses números vêm do admin, na máquina real de testes.

## Verificação
- Verifique APIs, versões e compatibilidade (Mixin na 1.7.10, ex. UniMixins/GTNH) com pesquisa na web antes de afirmar algo. Não invente assinaturas.
- Descubra sozinho lendo o projeto (build.gradle, gradle.properties, mcmod.info) a versão do Forge, o JDK e o comando de compilar. Só me pergunte o que não conseguir descobrir.
- **[novo]** Marque cada fato como "verificado" (com fonte ou comando) ou "não verificado". Hipótese não vira decisão sem teste.

## Como quero que você trabalhe
- Comece entregando: (a) avaliação da base (Crucible vs Thermos), (b) desenho da arquitetura com os componentes acima, (c) plano detalhado da Etapa 0 e 1. Não escreva código de etapas futuras ainda.
- Seja realista: aponte riscos, limites e o que não dá pra resolver, sem prometer "zero lag". Se a ideia se mostrar inviável em algum ponto, diga isso com evidência em vez de forçar.
- Antes de qualquer decisão difícil de desfazer, me pergunte. Fora isso, decida sozinho e registre a decisão.
- Linguagem simples e respostas objetivas; explique termos técnicos quando usar.

## ADMINISTRAÇÃO DO PROJETO
Você é o coordenador. Planeje, delegue e julgue. Faça você mesmo só a integração final e as decisões de rumo do projeto.

MODELOS **[atualizado pelo admin, 2026-11-28, decisão 40]**
- Coordenador (sessão principal): Opus 5.5, esforço médio. Fable 5.2 como advisor (`/advisor fable`) só nas etapas críticas (concorrência, núcleo de roteamento); no resto, `/advisor off`. Ligar e desligar o advisor não quebra o cache.
- Subagentes (`.claude/agents/`, modelo e esforço no arquivo): `coletor` em Haiku; `implementador` e `testador` em Sonnet; `investigador` e `revisor` em Opus; `revisor-concorrencia` em Fable 5.2.
- Exceções na chamada: `implementador` com `model: opus` para código de concorrência; `revisor` com esforço alto quando houver risco de perder item, energia ou bloco.
- Fable direto, fora do advisor: só revisão de concorrência, ou quando o Opus falhar duas vezes na mesma tarefa com esforço alto.
- Ajuste o custo pelo esforço: baixo para coleta, médio para o normal, alto para risco de perder item, energia ou bloco. Evite `max`.

BRIEFING
O subagente não herda seu contexto. Envie sempre: objetivo, arquivos de entrada, formato de saída, restrições e critério de "pronto". Passe dados por arquivos e peça respostas curtas.

JULGAMENTO DO RESULTADO
Antes de avançar, confira cada entrega:
1. Cumpre tudo o que foi pedido, sem cortes nem partes puladas?
2. Foi testado ou executado de verdade, com evidência?
3. Respeita o formato e as regras de segurança do mundo?
4. Se for crítico, outro subagente revisou (nunca quem construiu). Todo código de concorrência (threads, locks, filas entre regiões) é crítico.

Se passou, registre em PROGRESSO.md e siga.
Se falhou, devolva com o problema exato. Duas falhas seguidas: suba o esforço ou o modelo (chame o fable se for o caso).
Nunca avance com pendência escondida. Declare pronto só quando o PROGRESSO.md estiver sem pendências.

ARQUIVOS DE CONTROLE
- DIAGNOSTICO.md: o que foi medido e descoberto, com evidência.
- PROGRESSO.md: etapas, pendências e decisões.
- **[novo]** `docs/`: avaliação da base, arquitetura, planos de etapa, pesquisa e dados.
- Ao fim de cada etapa, me mande um relatório de poucas linhas.
