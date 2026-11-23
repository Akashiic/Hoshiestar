# Plano da Etapa 2: o observador

> 2026-11-23. Plano curto; a implementação começa logo depois, sem esperar aprovação. O observador **só lê e anota**:
> não barra nada, não roteia nada, não muda o que nenhum método devolve. Nada de orçamento por região, threads
> de região nem checagem que barra (etapas futuras). Linhas do fonte conferidas no workspace do fork
> (`eclipse/cauldron/src/main/java/net/minecraft/...`, branch `hoshiestar`, commit `4dd2bdce`).

## 1. O que o observador anota

**A frase-alvo:** "o mod X, executando a partir da região A, tocou a posição P da região B", com: tipo de acesso,
distância em chunks e em seções, dimensão de origem e de destino, e se cruzou dimensão. Tudo **agregado em tabelas
de contagem** na memória (nunca uma linha de log por evento), gravadas no fim.

**Quem está executando (o "contexto").** A thread do servidor guarda num campo estático o contexto atual,
trocado em poucos lugares do tick (é o mesmo truque do vigia, `hoshiestar.vigia.Marcas.tile/entidade`, que já marca
o tile e a entidade da vez em `World.updateEntities`, linhas 2426/2524, e o jogador em
`EntityPlayerMP.onUpdateEntity`, linha 412). O contexto tem: tipo (tile, entidade, jogador pela rede, tick agendado,
tick aleatório, evento de bloco, handler global antes/depois do tick do servidor, handler de tick de mundo, rede,
agendador de plugins, carga de chunk, "outro"), mundo, chunk de origem e a classe do objeto que tica.
Contextos novos (só quando o observador está ligado): `WorldServer.tickUpdates` (707, por tick agendado),
`func_147456_g` (438, tick aleatório por chunk), `func_147488_Z` (1257, eventos de bloco), e em
`MinecraftServer.tick` em volta de `onPreServerTick` (874), `onPostServerTick` (939), `mainThreadHeartbeat` (952),
`onPreWorldTick`/`onPostWorldTick` (1004/1053) e `networkTick` (1070).

**Qual mod.** Classe do tile/entidade → jar → mod, pelo índice de jars que o vigia já monta
(`IndiceDeJars.dono(classe)`), com cache por classe (mapa de identidade, só na thread do servidor) e um número
inteiro por mod nas tabelas. Quando o contexto não diz o mod (handler global, rede, plugin, tick de bloco de bloco
vanilla chamando código de mod) ou quando o acesso cruza região, uma **amostra de pilha** (`Thread.getStackTrace`)
acha o primeiro quadro que não é Minecraft/Forge/FML/Bukkit/Crucible/Hoshiestar. Amostragem: 1 em 256 acessos
que cruzam seção, com teto de 20 amostras por tick; a tabela guarda "mod do contexto" e "mod da pilha" separados.

**Região nesta etapa.** Unidade = **seção de 16×16 chunks** (`chunk >> 4`, a mesma do medidor). "Região" = grupo de
seções com chunk carregado ligadas pela regra de junção do estudo (seções a até 3 seções viram uma; margem de 1),
recalculado numa **foto** a cada 30 s no fim do tick (fase serial, lendo `ChunkProviderServer.loadedChunks`). Cada
acesso grava as duas seções; o relatório diz se caiu na mesma seção, na mesma região da foto, ou entre regiões.
Como as coordenadas ficam guardadas, dá para recalcular depois com outra regra (ex.: seção 8×8).

**Tabelas (todas com teto de tamanho e contador de estouro, reaproveitando `hoshiestar.medidor.TabelaLong`):**
- T1 por (mod do contexto, mod da pilha, tipo de contexto, tipo de acesso, faixa de distância em chunks
  [0 / 1-2 / 3-8 / 9-16 / 17-48 / 49-63 / 64+], mesma seção?, mesma região?, cruzou dimensão?) → contagem e
  distância máxima.
- T2 pares (dim, seção de origem, seção de destino) que cruzam região → contagem (candidatos a "vínculo", D5).
- T3 cargas de chunk dentro do tick por (tipo de contexto, mod, onde caiu: mesma seção / seção da mesma região /
  seção vazia / seção de outra região) → contagem (pendência 9: frequência de carga dentro do tick).
- T4 acessos vindos de **outra thread** que não a do servidor, por (tipo de acesso, mod da pilha) → contador atômico
  (único estado tocado por outra thread).
- T5 (para P3) por foto e por dimensão: regiões, seções e chunks de cada uma, tiles e entidades por seção, e a menor
  distância em chunks entre regiões; com o medidor ligado junto, também os ms por seção.

## 2. Pontos de patch (só leitura; desligado = um teste de campo estático)

Cada ponto é uma linha `if (hoshiestar.observador.Observador.ATIVO) Ganchos.x(...); // Hoshiestar` no início do
método, sem `try/catch` no bytecode do jogo (o Opis quebra com quadro novo em `updateEntities`, achado do vigia) e com
o `try/catch` dentro do gancho. `ATIVO` é um `static boolean` escrito só pela thread do servidor (padrão do
`Medidor.ATIVO`). Nenhum gancho escreve em objeto do jogo nem muda retorno.

| Arquivo | Método (linha) | Anota |
|---|---|---|
| `world/World.java` | `setBlock(int,int,int,Block,int,int)` (782) | escrita de bloco (as outras sobrecargas e `setBlockToAir` passam por ela) |
| `world/World.java` | `setBlockMetadataWithNotify` (915) | escrita de metadado |
| `world/World.java` | `getBlock` (665) | leitura de bloco: **só compara seção**; registra só se cruzou (caminho mais quente) |
| `world/World.java` | `getTileEntity` (3160) | leitura de tile; se o tile devolvido é `IInventory`, conta como "provável inventário" |
| `world/World.java` | `setTileEntity` (3224), `removeTileEntity` (3264), `addTileEntity` (4739) | tile posto/removido |
| `world/World.java` | `addEntity(Entity,SpawnReason)` (1734), `removeEntity` (1866) | entidade criada/removida (`spawnEntityInWorld` passa por `addEntity`) |
| `world/World.java` | `notifyBlockOfNeighborChange` (1087) | alcance real da atualização de vizinho (N9 do estudo) |
| `world/WorldServer.java` | `scheduleBlockUpdateWithPriority` (592), `func_147446_b` (638), `addBlockEvent` (1238) | tick de bloco agendado e evento de bloco (`scheduleBlockUpdate` passa pela 592) |
| `world/chunk/Chunk.java` | `func_150807_a` (602) | escrita direta no chunk (mod que pula o `World`) |
| `world/gen/ChunkProviderServer.java` | `originalLoadChunk`, na entrada no mapa (261-262) | carga de chunk e quem causou (T3) |
| `tileentity/TileEntityHopper.java` | `func_145893_b` (754) | inventário por posição (único ponto central de inventário que existe) |
| `world/World.java`, `WorldServer.java`, `MinecraftServer.java` | os pontos de contexto da §1 | troca de contexto (um campo), sem anotar acesso |

Acesso a `IInventory` não tem porta única na 1.7.10 (cada mod faz `getTileEntity` e converte); por isso o
"provável inventário" acima, e não uma contagem exata. Ficam de fora (caros demais e contariam em dobro):
`getChunkFromChunkCoords`/`provideChunk` e `getBlockMetadata`.

## 3. Interruptor e comandos

- `hoshiestar.yml`: `observador: ligado: false` (padrão). Desligado = nenhum gancho faz nada e o comando responde
  "desligado no hoshiestar.yml". Também: `lista_tiles: false` (protótipo da §4), `amostra_pilha: 256`,
  `limite_custo_pct: 5` (válvula: para sozinho se o custo estimado passar disso, como a do medidor).
- Comandos (permissão do `hoshie`): `hoshie observar <segundos>` | `hoshie observar parar` | `hoshie observar status`.
- Relatório em `hoshiestar/observador-<data-hora>.json` e `.txt`: só classes, mods, coordenadas de seção e
  números; **sem nome, UUID nem IP de jogador** (jogador aparece como "jogador"). Parada do servidor no meio grava.

## 4. Protótipo da lista por região (`world.loadedTileEntityList`), hipótese não verificada

Pergunta: dá para trocar o **objeto** da lista por um que entregue a cada região só a parte dela? Antes de
construir isso, medir **quem usa a lista de fora do `World` e como**.

- Experimento: com `observador.lista_tiles: true` (só no boot), cada mundo cria a lista como `ListaObservada`,
  subclasse de `io.github.crucible.util.HashedArrayList`, que **devolve exatamente o mesmo** e só conta. Achado
  ao conferir o fonte: o Crucible **troca o objeto** por um `ArrayList` comum quando remove tiles
  (`World.java:2510`, `this.loadedTileEntityList = temporary_tile_entity_list`); o patch embrulha ali também.
- O `World` liga um marcador "dentro do mundo" nos seus próprios laços (`updateEntities`, `func_147448_a`,
  limpeza de 2495-2516); toda chamada com o marcador desligado é "de fora". Conta por (operação: `size`/`get`/
  `iterator`/`contains`/`add`/`remove`/`clear`/troca do campo; tipo de contexto; mod da pilha por amostra).
- No fim de cada tick confere se o campo ainda é o nosso objeto (alguém trocou por reflexão?) e conta.
- Critério de decisão (no relatório do portão):
  1. **Descarta** a lista por região se houver modificação de fora vinda de contexto de tile/entidade em mod
     que não dá para pôr em série, ou troca do campo por mod: a lista teria de mentir para o mod (N4).
  2. **Desnecessária no Degrau B** se ≥ 99% dos acessos de fora vêm de contextos seriais (handlers globais,
     comandos, rede): basta a regra já prevista, "a lista não muda de tamanho na fase paralela".
  3. **Prova a hipótese** se os acessos de fora em contexto de região forem só leitura/iteração e de poucos
     mods (até ~5) que cabem num módulo ou na lista "só em série".
  A mesma medição para `loadedEntityList` fica para depois, se o resultado pedir.

## 5. Meta de custo e como medir

- **Desligado: ~0.** Mediana do tempo de tick igual à do jar 1.0.0 dentro do ruído (diferença ≤ 1%, já que a meta
  do projeto é ≤ 5% com tudo desligado); JFR sem nenhuma alocação nem amostra de CPU em `hoshiestar.observador`.
- **Ligado: até 5% do tick** (mediana) com a carga sintética do teste do medidor (cerca de 2.000 funis e 400 porcos)
  mais uma corrente de funis atravessando a borda de uma seção e máquinas de mod postas por comando. Número de carga
  sintética é **indicação, não medição de produção**.
- Como: mesma cópia, três rodadas de 10 min (jar 1.0.0, Hoshiestar desligado, ligado), tempo de tick por tick
  pelo relógio do vigia/medidor e `jcmd <pid> JFR.start duration=600s filename=...` em cada uma; comparar mediana,
  p95 e as amostras de CPU dos ganchos. Válvula: se o custo estimado passar de `limite_custo_pct`, o observador para
  e grava o que tem.

## 6. As três lacunas da pendência 14 (só desenho)

1. **Dono de chunk carregado na fase paralela, até o ponto de junção.** Proposta: o chunk passa pela porta única de
   carga, fica visível só para a região que o carregou e entra no mapa de regiões na junção; se cair em seção de
   **outra** região, é acesso entre regiões (no Degrau B vira tarefa da fase serial). **Medida que confirma:** T3,
   quantas cargas dentro do tick caem na própria região, em seção vazia ou em outra região, e de qual mod.
2. **"Fase serial" no Degrau C, que não tem barreira.** Proposta: pausa **por dimensão**, só quando preciso: antes
   de um handler global não liberado que mexe na dimensão, a thread global espera as regiões daquela dimensão
   terminarem o tick em curso (nenhuma região espera outra); dimensões sem handler desses nunca param (casa com a
   decisão 32). **Medida que confirma:** T1 com contexto "handler global/tick de mundo": por dimensão, quais mods
   tocam nela fora do tick de região e com que frequência; a lista sai pronta para a P1.
3. **Liberar ou isolar a região que falhou** (só no modo "rebaixar e seguir", desligado pela decisão 33).
   Proposta: a região falha nunca fica "ticando para sempre" (o defeito do Folia): ela volta para a fase serial
   (a thread global passa a ticá-la), continua dona dos seus chunks, junções com ela só no ponto serial, e depois
   de N falhas para de ticar e avisa o admin; nada é descartado. **Medida que confirma:** quantos erros de tick de
   tile/entidade por mod e por região ocorrem hoje (o laço já captura o erro de entidade em `World.java:2450`; o de
   tile, a conferir no passo 2) e quantos acessos de outras regiões caem numa região, para saber quem ficaria
   preso se ela fosse isolada.

## 7. Passos (decisão 31: modelo e esforço) e critério de pronto

| # | Passo | Agente / modelo / esforço |
|---|---|---|
| 1 | Núcleo em `src/main/java/hoshiestar/observador/`: config, registro, contexto, tabelas, foto e regras de região, relatório, comando, válvula; testes isolados das tabelas e da regra de junção | sonnet / médio |
| 2 | Patches da §2 e pontos de contexto (`gerar-patches.sh`), conferindo cada linha e o boot com Opis/firewall | opus / médio |
| 3 | Protótipo `ListaObservada` (§4) com interruptor próprio | opus / médio |
| 4 | Revisão que tenta quebrar (desligado = original, nenhum retorno muda, nada de dado pessoal) | opus / médio, agente diferente |
| 5 | Revisão de concorrência do T4 e da lista (mods podem ler a lista de outra thread) | fable / médio, agente diferente |
| 6 | Roteiro de servidor: boot padrão, ligado com carga sintética, bot, JFR, três rodadas da §5, parada no meio | sonnet / médio |
| 7 | Varredura estática dos jars (pendência 9: padrões das estruturas hostis; singletons de IC2, AE2, EnderIO, CoFH, BuildCraft) | haiku / baixo coleta; opus / médio lê |
| 8 | Relatório do portão: mods que precisam de módulo, P1 por dimensão, dado de P3 (sintético), lacunas 14, veredito da lista | opus / médio |
| 9 | Junção, exportação, registro em `PROGRESSO.md` | coordenador |

**Pronto quando:** testes isolados passam (JDK 8 e 21); boot com 292 mods sem jar nosso em `mods/`/`plugins/`;
desligado igual ao original e custo dentro da §5; ligado gera `.json`/`.txt` sem dado pessoal, inclusive com
parada no meio; veredito da §4 escrito; as duas revisões feitas por agentes diferentes, achados importantes
corrigidos; relatório do portão pronto. **Aí a etapa para no portão "vai / não vai"** e a decisão é do admin;
nenhum código da Etapa 2.5 antes disso. Não verificável aqui: bases reais de jogadores e distância real entre elas
(P3 só com carga sintética), comportamento com jogador humano de verdade.
