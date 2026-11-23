# Plano detalhado: Etapa 0 (medição) e Etapa 1 (fora da thread principal)

Cada tarefa tem: o que é, quem faz (modelo do subagente), como se sabe que está
pronta e o risco. Nenhuma tarefa escreve em servidor real.

Legenda de estado: ✅ feito · 🔄 em andamento · ⏳ a fazer · 🧑 depende do admin

---

## Etapa 0: Medição

Objetivo: saber, com número, **onde o tempo do tick vai** em produção, e ter um
ambiente de teste que qualquer sessão consegue montar de novo.

### 0.1 Ambiente de teste reproduzível ✅
- `scripts/prepare-test-env.sh` monta a cópia de teste e desliga 16 plugins (lista e motivo no script).
- `scripts/run-test-server.sh` resolve as bibliotecas e liga o servidor com console por arquivo.
- Pronto quando: uma pasta nova, criada do zero pelos dois scripts, chega a "Done" e para com `stop`. **Verificado.**

### 0.2 Inventário estático ✅
- `tools/static_inventory.py` → `docs/dados/inventario-mods.csv` e `inventario-plugins.csv`.
- Resultado em `DIAGNOSTICO.md` §2.

### 0.3 Linha de base ociosa ✅ (com ressalva)
- Medida em mundo novo: 3,1 ms por tick, 61 dimensões (`DIAGNOSTICO.md` §4).
- Ressalva: mundo novo contamina o perfil (D-10). Refazer quando houver cópia do mundo real.

### 0.4 Perfil sob carga real ✅ (um perfil de 8 jogadores; resultado em `DIAGNOSTICO.md` §6)
O que preciso saber: em horário de pico, quanto do tick é
(a) tile entities e entidades por dimensão, (b) handlers globais de mods,
(c) plugins, (d) rede, salvamento e chunk.
Sem isso não dá para dizer quanto as regiões vão render.

Roteiro para o admin (só leitura; não muda nada no servidor):
1. No pico, rode `/forge tps` e copie a saída inteira.
2. Rode `/spark tps` e `/spark health` e copie.
3. Rode `/spark profiler start --timeout 300`. Ao terminar ele dá um link.
   Atenção: o spark publica o perfil num site externo. Se não quiser, use
   `/spark profiler start` e depois `/spark profiler stop --save-to-file`
   (não verifiquei se a versão 1.10 para 1.7.10 aceita essa opção; se recusar, me avise).
4. Rode `/timings on`, espere 10 minutos, `/timings report` (se disponível) e copie.
5. Com o Opis: `/opis_te` e `/opis_ent` para as tile entities e entidades mais caras.
6. Me diga: número de jogadores na hora, núcleos e RAM da máquina, flags da JVM.

Pronto quando: `DIAGNOSTICO.md` tiver a tabela "onde vai o tick em produção"
com esses números. **Feito** com o relatório do spark enviado em 2026-11-21.
Ainda ajudaria: a saída de `/forge tps` no pico (dá o tempo por dimensão, que o spark não dá)
e um segundo perfil com mais jogadores.

### 0.5 Mundo de teste com carga 🧑 (o mundo tem 150 GB e não pode ser copiado)
- Preciso de uma cópia do mundo (ou só das regiões `.mca` de 3 a 5 bases pesadas mais `level.dat` e a pasta `data/`).
- Com isso o servidor de teste carrega bases reais e o perfil passa a significar algo.
- Alternativa sem o mundo: construir bases sintéticas por comando. Mais fraco, mas possível.
- Alternativa leve: só os arquivos de região de 3 a 5 bases (`region/r.<X÷512>.<Z÷512>.mca`, poucos MB cada) e o `level.dat`.

### 0.6 Bots de teste 🔄 (sonnet)
- Parcial (2026-11-22): `tools/test_bot.py`, um bot mínimo em Python, entra no servidor de teste com o handshake FML e os 292 mods (D-30). Ele não anda nem gera carga.
- Validar `minecraft-protocol` + `minecraft-protocol-forge` contra o servidor de teste na 1.7.10. **Não verificado** que funcione com o handshake FML dessa versão e com 292 mods.
- Entrega: script que conecta N bots, anda, e desconecta; relatório do que funcionou.
- Pronto quando: 20 bots ficam conectados 10 minutos sem serem expulsos. Se não der, registrar o motivo e o plano alternativo (cliente real sem tela, ou bots só para carga de chunk).
- Risco: alto de não funcionar de primeira. Não bloqueia a Etapa 1.

### 0.7 Compilar a base do zero ✅ (passos em `docs/pesquisa/crucible-build.md`)
- Clonar o Crucible, fazer checkout de `67b53034`, instalar JDK 8, `./gradlew setupCrucible buildPackages`.
- Pronto quando: o jar gerado liga o servidor de teste e chega a "Done" igual ao jar de produção. **Verificado**: 5.317 de 5.318 entradas idênticas ao jar de produção; ligou com 292 mods e parou limpo.
- Risco: médio (dependências em repositório pessoal). Se uma dependência sumir, espelhar.
- Não é necessário para as Etapas 1 e 2, mas tem que estar provado antes da Etapa 3.

### 0.8 Conferir as estruturas hostis a threads no código decompilado ✅ (opus)
- **Feito** (2026-11-22): `docs/pesquisa/estruturas-hostis.md`, com a tabela pedida e conferência independente por área. Relatórios brutos em `docs/pesquisa/hostis/`.
- Parcial: tick, threads existentes, estado estático e campos públicos já mapeados com arquivo e linha (`docs/pesquisa/crucible-tick-e-threads.md` e `crucible-arquitetura-patches.md`). Falta a lista vanilla item a item.
- A lista de `docs/pesquisa/arte-previa.md` §4 é conhecimento geral. Conferir cada item no código do workspace gerado em 0.7.
- Entrega: tabela "estrutura, onde é usada, quem acessa de fora, tratamento previsto".

### 0.9 Medidor de carga por região ✅ construído e testado; 🧑 falta rodar em produção (opus, com revisão e teste por agentes diferentes)
O perfil do spark diz **quanto** o tick custa, mas não **onde no mapa**. Sem isso não dá
para saber se o orçamento por região (Degrau A) ajuda. Como o mundo não pode ser copiado,
a medida tem que ser feita em produção, por um mod pequeno e só de leitura:
- `Hoshiestar-Medidor`: mede o tempo de tick de tile entities e entidades e agrupa por dimensão, por seção de 16×16 chunks e por chunk.
- Desligado por padrão. Ligado por comando, por tempo limitado. Grava um arquivo local; não envia nada para fora.
- Não muda comportamento. Tem que conviver com o mixin do `Akashic-World-Firewall`, que já envolve o tick de tile entity.
- Pronto quando: sobe com o modpack real; desligado, não altera o tick; ligado, aponta corretamente blocos de teste colocados por comando; revisado por outro agente.
- Entrega: jar para o admin e roteiro de uma página.
- **Mudança (2026-11-22, correção do admin):** o medidor deixa de ser mod separado e passa a ser parte do `server.jar`, com os mesmos comandos. A versão em mod abaixo fica só como histórico até o porte terminar.
- **Porte feito (2026-11-23):** o medidor está em `servidor/` (pacote `hoshiestar.medidor`), roteiro em `servidor/src/docs/ROTEIRO-MEDIDOR.md`; a pasta `medidor/` saiu do repositório.
- **Histórico** (2026-11-22): versão 1.0.4 em `medidor/`, roteiro em `medidor/ROTEIRO-ADMIN.md`. Teste independente no modpack real aprovado, com e sem o `Akashic-World-Firewall`. O custo com o medidor desligado não pôde ser separado do ruído do servidor ocioso (não verificado numericamente; conferido no bytecode: uma leitura de variável por gancho).

### Critério de saída da Etapa 0
1. Ambiente reproduzível ✅
2. Tabela "onde vai o tick em produção" preenchida ✅
3. Lista das maiores fontes de custo, com dono ✅ (`DIAGNOSTICO.md` D-16 e `docs/pesquisa/perfil-producao.md` §4)
4. Base compilável do zero ✅
5. Distribuição da carga pelo mapa medida em produção (0.9) ⏳
6. `DIAGNOSTICO.md` atualizado e `PROGRESSO.md` sem pendência da etapa

---

## Etapa 1 (revista): ganhos baratos medidos, o "Degrau 0"

**Mudança de rumo, decidida com os dados de produção.** O plano original desta etapa
(tirar disco, luz e geração da thread principal) ataca 2,9% do tick. O perfil mostrou
cerca de 20% em quatro pontos que não precisam de threads. A etapa passa a ser esta lista,
em ordem de retorno.

**Formato de entrega (correção do admin, 2026-11-22):** o `server.jar` do Hoshiestar, fork
do Crucible `staging@67b53034`. Nenhum mod nosso em `mods/`. Itens em classes do servidor
(1.E, 1.G, 1.H, o medidor da tarefa 0.9, relógio de tick) são patches no fork com a marcação
`// Hoshiestar`. Itens em mods de terceiros (1.B, 1.C, 1.D, 1.F) vão embutidos no jar e são
aplicados pelo servidor quando o mod está presente e a trava de versão (SHA-256) confere.
Um interruptor por item em `hoshiestar.yml`; tudo desligado por padrão, menos o vigia.
O código do fork fica em `servidor/` (patches sobre o upstream, arquivos novos e script de build).

**Critério de pronto da etapa:** o `server.jar` liga no modpack de teste (292 mods) sem
nenhum mod nosso, com os recursos embutidos e desligáveis pelo config, testado e com parada limpa.

| # | Item | % do tick | Risco | Observação |
|---|---|---:|---|---|
| 1.A | `dontSleepOnThreadedIO=false` no `hodgepodge.cfg` | devolve 1 núcleo | baixo | **só config**; verificado no teste (D-18). O admin pode aplicar já. |
| 1.B | Teto de volume e de tempo na habilidade de mover área do ChromatiCraft | evita stall de 30 s | baixo | D-21 |
| 1.C | Leitura de energia da matriz de indução do Mekanism | até 8,3 | médio | cache por tick; conferir que energia não é criada nem perdida (teste de conservação) |
| 1.D | BetterQuesting no tick do servidor | até 4,6 | médio | já existe o `Akashic-BQRelief`; ver por que ainda custa |
| 1.E | `ChunkBlockHashMap` do Crucible | até 3,6 | médio | é da base; candidato a pull request ao upstream |
| 1.F | Handler do Ars Magica 2 em toda entidade viva | até 3,6 | médio | |
| 1.G | Correção do desligamento (D-24) | não é desempenho | baixo | candidato a pull request ao upstream |
| 1.H | Vigia que nomeia jogador, posição e mod quando um tick passa de 2 s | diagnóstico | baixo | |

Os itens do plano original (abaixo) ficam como **reserva**: só entram se um perfil
futuro mostrar que pesam.

## Etapa 1, plano original (reserva): tirar da thread principal o que é independente

Regra: **só atacar o que a Etapa 0 mostrar que pesa.** Se o salvamento já custa
1 ms, não se mexe nele. Cada item tem interruptor no config, desligado por padrão.

Formato de entrega: patches no fork (o `server.jar` do Hoshiestar), como no resto da etapa.

### 1.0 Auditoria do que já existe ⏳ (opus; leitura de código)
Antes de escrever qualquer coisa, ler e documentar:
- `Akashic-SaveRelief`: o que foi para a thread de I/O e o que ainda roda na principal.
- `Akashic-PlayerChunkAsync`: o que exatamente é assíncrono.
- Thread de iluminação do Crucible: está ligada? Quais são os pontos de sincronização?
- `ChunkIOExecutor` do Forge: tamanho do pool, o que ainda bloqueia.
- Opções relevantes de Hodgepodge, CoreTweaks e ArchaicFix (do lado servidor).

Entrega: tabela "trabalho, onde roda hoje, quem já tirou da thread principal, o que sobra".
Pronto quando: cada um dos 4 temas (geração, salvamento, luz, login) tem a linha
"já resolvido por X" ou "sobra Y, custa Z ms" (Z vindo da Etapa 0).

Preciso do admin 🧑: o código-fonte dos mods `Akashic-*` de desempenho, se puder
compartilhar. Sem ele, a auditoria é por decompilação (mais lenta e menos confiável).

### 1.1 Salvamento ⏳ (opus; revisão por outro agente, é concorrência)
- Hipótese: `SaveRelief` cobre `level.dat` e `data/*.dat`; a serialização dos chunks em NBT pode ainda rodar na thread principal.
- Proposta, se a medição confirmar: tirar uma cópia do chunk na thread principal (rápido) e serializar/gravar na thread de I/O.
- Teste: diferencial (mesmo mundo, salvo dos dois jeitos, arquivos equivalentes); teste de queda (matar o processo no meio do salvamento, mundo abre íntegro).
- Risco: **alto**. Erro aqui corrompe chunk. Só entra com os dois testes passando 50 vezes seguidas.

### 1.2 Carga de chunk no login e no teleporte ⏳ (opus)
- Hipótese: `PlayerChunkAsync` já cobre. Confirmar na auditoria.
- Se sobrar algo: carregar dados do jogador (`playerdata`, dados de plugins) fora da thread principal antes de colocar o jogador no mundo.
- Teste: 50 logins simultâneos com bots; tempo de tick durante o login.

### 1.3 Iluminação ⏳ (opus)
- O Crucible já tem thread de luz. Tarefa: medir se está ativa e se há disputa com a thread principal.
- Provável resultado: nada a fazer além de documentar e garantir que o modelo de regiões saiba dela.

### 1.4 Geração de chunks ⏳ (decisão de rumo, depois fable se for adiante)
É o item mais arriscado da etapa, e o prompt precisa saber disso:
- Na 1.7.10 a geração roda na thread principal e a "população" (árvores, minérios, estruturas) escreve em chunks vizinhos já carregados.
- Os geradores de mods (ChromatiCraft, Galacticraft, Twilight Forest, Thaumcraft...) não foram feitos para threads. `IntCache`, usado nos biomas, é estático.
- Proposta em ordem de risco:
  1. **Pré-gerar o mundo** (operacional, risco zero). Resolve a maior parte do custo.
  2. **Limite de geração por tick** (orçamento): gerar no máximo N ms de chunks novos por tick. Risco baixo.
  3. Geração do terreno-base em thread separada, com a população ainda na principal. Risco alto; só como experimento, desligado por padrão.
- Pronto quando: 1 e 2 medidos; 3 só se a Etapa 0 mostrar que geração ainda pesa depois de 1 e 2.

### 1.5 Interruptores e medição ⏳ (sonnet)
- Arquivo `config/hoshiestar.cfg`: um interruptor por recurso, tudo desligado por padrão.
- Comando `/hoshie status`: o que está ligado, e o tempo gasto em cada trabalho movido.
- Teste de não-regressão: com tudo desligado, mesmo comportamento e tick dentro de 5% do original.

### Critério de saída da Etapa 1
1. Tabela da auditoria 1.0 completa.
2. Cada recurso implementado tem: interruptor, teste diferencial, teste de queda quando grava em disco, e medição antes/depois.
3. Zero corrupção em todos os testes.
4. Código de concorrência revisado por um agente diferente do que escreveu.
5. Jar entregue ao admin com roteiro de teste de uma página; números do admin registrados.

---

## O que estas duas etapas não entregam

Elas **não isolam o lag de uma base**. Tiram picos (salvamento, login, geração)
da thread principal. O isolamento começa na Etapa 2.5 (orçamento de tick por
região). Se o perfil da Etapa 0 mostrar que o problema de produção é quase todo
tile entity e entidade, a Etapa 1 pode ser curta e o esforço vai para a 2 e a 2.5.
