# Observador de acessos entre regiões (`observador`)

Etapa 2 do plano (`docs/05-plano-etapa-2.md`, seções 1, 2, 3 e 5). O observador **só lê e anota**: não barra
nada, não roteia nada, não muda o que nenhum método devolve. Ele existe para responder, com número, a pergunta
que decide o desenho das regiões paralelas: **quem toca em quê, a que distância, e entre que regiões.**

## 1. O que anota (em linguagem simples)

A frase-alvo: *"o mod X, executando a partir da região A, tocou a posição P da região B"*, com o tipo de acesso, a
distância em chunks e em seções, a dimensão de origem e de destino, e se cruzou de dimensão. Tudo vira
**contagem em tabelas** na memória (nunca uma linha de log por evento) e é gravado no fim.

- **Contexto** ("quem está executando"): um campo da thread do servidor, trocado em poucos pontos do tick:
  tile entity, entidade, jogador pela rede, tick agendado, tick aleatório, evento de bloco, handler global
  antes/depois do tick do servidor, handler antes/depois do tick de um mundo, rede, agendador de plugins. Cada
  contexto de tile/entidade/tick traz o **chunk de origem** e a **classe** do objeto; os outros ficam "sem origem".
- **Mod**: classe → jar (o índice de jars do vigia, `IndiceDeJars`) → um número por mod, com cache por classe.
  Quando o contexto não diz o mod, ou o acesso cruza seção, uma **amostra de pilha** (1 em 256 acessos que cruzam
  seção, no máximo 20 por tick) acha o primeiro quadro que não é Minecraft/Forge/Bukkit/Crucible/Hoshiestar. A tabela
  guarda o mod do contexto e o mod da pilha **separados**; o da pilha é só amostra (serve para ver quem é, não para contar).
- **Região**: seção de 16 × 16 chunks; região = grupo de seções com chunk carregado a até 3 seções uma da outra
  (a regra do estudo Folia). Recalculada numa **foto** a cada 30 s no fim do tick (fase serial); as coordenadas das
  seções ficam guardadas na foto, então dá para recalcular com outra regra (o teste faz seção 8 × 8).
- **Tipos de acesso** (4 bits): ler bloco, escrever bloco, escrever metadado, ler tile, ler tile "provável
  inventário" (o tile devolvido é `IInventory`), pôr/remover/adicionar tile, adicionar/remover entidade, avisar vizinho,
  agendar tick, evento de bloco, escrita direta no chunk, inventário pelo funil.
- **Faixas de distância em chunks**: 0 / 1-2 / 3-8 / 9-16 / 17-48 / 49-63 / 64+ (e "n/a" quando não há origem ou
  cruzou de dimensão).

### As cinco tabelas (todas com teto de tamanho; o que passa do teto vai para o balde "outros" e o relatório avisa)

| Tabela | Chave | Guarda | Teto |
|---|---|---|---|
| T1 | mod do contexto, mod da pilha, contexto, acesso, faixa, mesma seção?, relação de região (mesma / outra / fora da foto / n/a), cruzou dimensão? | contagem e distância máxima | 20 000 linhas |
| T2 | dimensão e seção de origem, dimensão e seção de destino (só os que cruzam região ou dimensão) | contagem (candidatos a "vínculo") | 5 000 pares |
| T3 | contexto, mod do contexto, mod da pilha, onde a carga de chunk caiu (mesma seção / outra seção da mesma região / seção vazia / seção de outra região / sem origem / outra dimensão / sem foto) | contagem (frequência de carga dentro do tick) | 5 000 linhas |
| T4 | tipo de acesso, mod da pilha (amostra 1 em 64, no máximo 20 por tick somando T4 e lista) — acessos vindos de **outra thread** | contadores atômicos (a única parte tocada por outra thread) | fixo (16 × 4096 espaços: 0 = sem amostra, 1 + número do mod = amostrado; "(nenhum)" = amostrado sem mod) |
| T5 | uma foto por 30 s: por dimensão, regiões, seções, chunks, tiles e entidades; as regiões de mais carga; os pares de regiões **com carga** mais próximos (distância em seções e a faixa em chunks) | números | 40 fotos |

## 2. Chaves do `hoshiestar.yml`

| Chave | Padrão | O que faz |
|---|---|---|
| `observador.ligado` | `false` | habilita o comando. `false` = o comando recusa e todo gancho é um teste de campo estático (comportamento original). Mesmo ligado, só anota quando o admin manda e por tempo limitado. |
| `observador.amostra_pilha` | `256` | a pilha é olhada em 1 de cada tantos acessos que cruzam seção (1 a 1 000 000); fora da faixa vira 256. |
| `observador.limite_custo_pct` | `5.0` | válvula: para sozinho se o custo **estimado** passar disto % do tempo do tick (0,5 a 50; fora da faixa vira 5). |
| `observador.lista` | `false` | protótipo da lista de tiles (§10). Lido só no boot e só vale com `ligado: true`. `false` = nenhuma troca de objeto. |

## 3. Comandos (subcomandos do `hoshie`; mesma permissão: console, RCON e operador nível 4 com `hoshiestar.admin`)

| Comando | O que faz |
|---|---|
| `hoshie observar <segundos>` | observa por 5 a 3600 s. Na primeira vez monta o índice de jars numa thread à parte (alguns segundos) e começa sozinho no tick seguinte. Recusa se já há observação. |
| `hoshie observar parar` | encerra e grava o que já coletou. |
| `hoshie observar status` | andamento (ticks, acessos, cargas, acessos de outra thread, custo estimado, tempo que falta) e o último resultado. |

O comando pode vir de outra thread (RCON): ele só deixa um número num campo; quem inicia e para é o início do tick.
Dois comandos ao mesmo tempo (console e RCON) não passam os dois: o comando confere e pede sob uma trava. Se a montagem do
índice de jars não terminar em 120 s, um `hoshie observar <segundos>` novo abandona a montagem velha e começa outra.
**Não rode o medidor (`hoshie medir`) e o observador ao mesmo tempo**: o gancho do observador roda dentro da janela que o
medidor cronometra em cada tile e o tempo do tile sairia inflado.
A parada normal do servidor (`stop`) encerra e grava (espera a gravação até 10 s). Um erro interno do observador
(inclusive na foto) para a observação e grava o que tem; nunca derruba o tick.

## 4. Como ler o relatório

`<pasta do servidor>/hoshiestar/observador-AAAAMMDD-HHMMSS.json` (tudo) e `.txt` (para ler direto). Dois relatórios
no mesmo segundo ganham `-2`, `-3`. Sem nome de jogador, UUID nem IP; o contexto "jogador pela rede" entra **sem
objeto** (só o chunk); só aparecem nomes de **jar** (nunca de classe). Nada vai para a rede.

1. Cabeçalho: duração, ticks, motivo do fim (tempo, comando, válvula, parada do servidor, erro), **avisos** (tabela
   passou do teto, válvula parou, pilha de contextos transbordou) e o **custo estimado** (médio e no pior trecho de 100 ticks).
2. `resumo_por_mod_do_contexto` e `resumo_por_mod_da_pilha_so_amostras`: por mod, quantos acessos **cruzam seção**,
   **cruzam região**, **cruzam dimensão** e a distância máxima. É a lista de quem precisa de módulo ou de junção.
3. `t1_acessos`: as linhas de maior contagem (até 1 500), com todos os campos da chave.
4. `t2_pares_entre_regioes`: de que seção para que seção os acessos cruzaram região (candidatos a vínculo).
5. `t3_cargas_de_chunk`: quem carrega chunk dentro do tick e onde cai.
6. `t4_acessos_de_outra_thread`: o que mexe no mundo fora da thread do servidor (o que uma região paralela não aguenta).
7. `t5_fotos_de_regioes`: o dado de P3 (distância entre regiões com carga). A distância vem em **seções** (Chebyshev) e
   a faixa em chunks é `(d-1)*16+1` a `(d+1)*16-1`: é limite, não o valor exato. **Com carga sintética é indicação, não medição de produção.**

Leituras de bloco na mesma seção e leituras sem origem **só contam** (não entram em T1): são o caminho mais quente.

## 5. Onde estão os ganchos (patches `// Hoshiestar - observador`)

Cada ponto é uma linha `if (hoshiestar.observador.Observador.ATIVO) GanchosObservador.x(...)` — um teste de campo estático
que pula só a chamada — sem `try/catch` no bytecode do jogo (o Opis quebra com quadro novo em `updateEntities`; o
`try/catch` fica dentro do gancho). Nenhum gancho escreve em objeto do jogo nem muda retorno. Os do tile ficam numa linha
própria, ao lado dos do medidor, longe da linha da chamada `updateEntity()` (o Opis casa `[linha, ALOAD, chamada]`; o
Akashic-World-Firewall troca a chamada): a forma da chamada não mudou.

| Arquivo | Método | Anota |
|---|---|---|
| `World` | `getBlock`, `setBlock`, `setBlockMetadataWithNotify` | ler/escrever bloco e metadado |
| `World` | `getTileEntity` (antes do `return` final), `setTileEntity`, `removeTileEntity`, `addTileEntity` | tile (provável inventário se `IInventory`) |
| `World` | `addEntity(Entity, SpawnReason)`, `removeEntity`, `notifyBlockOfNeighborChange` | entidade e alcance da atualização de vizinho |
| `World` | `updateEntities` (tile da vez), `updateEntityWithOptionalForce` (entidade da vez) | contexto |
| `WorldServer` | `scheduleBlockUpdateWithPriority`, `func_147446_b`, `addBlockEvent` | tick agendado e evento de bloco (acesso) |
| `WorldServer` | `tickUpdates`, `func_147456_g`, `func_147488_Z` | contexto: tick agendado (por entrada), tick aleatório (por bloco sorteado: a classe do bloco), evento de bloco |
| `Chunk` | `func_150807_a` | escrita direta no chunk (não conta se acabou de vir de `World.setBlock` no mesmo chunk) |
| `ChunkProviderServer` | `originalLoadChunk`, ao entrar no mapa | carga de chunk (T3) |
| `TileEntityHopper` | `func_145893_b` | inventário por posição (único ponto central que existe) |
| `EntityPlayerMP` | `onUpdateEntity` | contexto "jogador pela rede" |
| `MinecraftServer` | `tick` (antes/depois de `onPreServerTick` e `onPostServerTick`, início e fim do observador), `updateTimeLightAndEntities` (em volta de `mainThreadHeartbeat`, `onPreWorldTick`, `onPostWorldTick`, `networkTick`), `run` (parada) | contexto global e ciclo de vida |

Contexto velho (D-39): o que o próprio servidor lê antes do gancho seguinte não pode cair no contexto anterior. Por isso o
contexto de tick agendado fecha a cada entrada (antes do `getBlock` da próxima), o de tick aleatório fecha no fim de cada
chunk (a checagem de luz e o `func_147467_a` do chunk seguinte ficam sem contexto), e `fimTile`/`fimEntidade` também rodam no
`catch` do tick que lançou exceção e no fim do laço de entidades. Só trocam contexto; nada do jogo muda.

Ficam de fora (caros demais e contariam em dobro): `getChunkFromChunkCoords`/`provideChunk` e `getBlockMetadata`.
Acesso a `IInventory` não tem porta única na 1.7.10; por isso "provável inventário" (via `getTileEntity`) mais o funil.

### Arquivos novos (`src/main/java/hoshiestar/observador/`)

`Observador` (ciclo de vida, comando, válvula, gravação), `Nucleo` (contexto, acessos, tabelas, custo), `Regioes`/`Foto`/
`FotoMundo`/`MapaSecoes` (regra de junção e foto), `TabelaContagem`, `Pilha`, `Mods`, `Relatorio`/`Json`, `Jogo`,
`RegistroObservador`; e as duas que citam o Minecraft/Bukkit: `GanchosObservador` e `ComandoObservador`. Nada do
Minecraft aparece no que roda na fase de coremod nem no que os testes isolados exercitam.

## 6. Argumento de segurança

- **Desligado**: cada gancho é `GETSTATIC Observador.ATIVO` + `IFEQ` (conferido no bytecode real pelo teste). Mais, a cada
  tick, uma leitura volátil do pedido (`inicioTickServidor`) e um teste de campo (`fimTickServidor`), ambos dentro de
  `try/catch` como os do medidor. Nenhuma alocação e nenhuma amostra de CPU deveria aparecer no JFR (**não medido**).
- **Só lê**: `GanchosObservador` não tem `PUTFIELD`/`PUTSTATIC` em classe do Minecraft e só chama `getServer`, `getFile` e
  `getWorlds` (testado). Nenhum retorno muda: o gancho do `getTileEntity` vem antes do `return` e não toca na variável.
- **Nunca lança**: todo gancho público tem `try/catch`; uma falha para a observação e grava (`Observador.falha`).
- **Concorrência**: o estado do nucleo é da thread do servidor; os ganchos conferem `ThreadServidor.souAThreadDoServidor()` e
  mandam outras threads para T4 (contadores atômicos e registro de mods seguro). `ATIVO` só é escrito pela thread do
  servidor; `N` é `volatile`. O comando só deixa um inteiro num `AtomicInteger`, sob uma trava curta que o início do tick também
  usa quando há pedido. Revisado por outro agente em 2026-11-23 (§8.1); **as correções dessa revisão ainda não foram revisadas
  por um terceiro agente.**
- **Sem dado pessoal**: ver seção 4 (testado: nenhum UUID, IP, nome de classe, nem `EntityPlayer` nos relatórios).
- **Custo**: contagem × custo medido por amostra (a cada 64 acessos completos), mais constantes para a leitura rápida (8 ns) e
  a troca de contexto (12 ns), mais o custo exato das amostras de pilha e das fotos. A válvula olha janelas de 100 ticks e
  para se passar de `limite_custo_pct` **e** de 0,25 ms por tick (não vale parar por uma folga minúscula). É estimativa.
  O que as **outras threads** pagam também entra: 25 ns por chamada contada (constante) mais o tempo exato das amostras de
  pilha delas (no máximo 20 por tick; se o tick parar, o orçamento não volta e a outra thread deixa de tirar pilha).

## 7. Testes isolados (um comando)

```bash
HOSHIE_LIBS=<pasta libraries/ de um servidor> bash <workspace>/src/test/observador/teste-observador.bash <workspace>
```

O `servidor/test.sh` já roda `src/test/*/teste-*.bash`. JDK 8 e 21. Resultado desta entrega (2026-11-23): todos passaram
(`teste-observador.bash`: 125 + 58 + 13 + 44 + 3 verificações por JDK).

- `TesteObservadorNucleo` (mundo falso): acesso na mesma seção, no vizinho (mesma região), distante (outra região),
  fora da foto, em outra dimensão, sem origem; agregação exata (1000 acessos = 1000, uma linha); tetos de T2 e da tabela;
  pilha 1 em N com teto de 20 por tick e separada do mod do contexto; pilha de contextos (substituição, transbordo);
  regra de junção (3 seções juntam, 4 não, cadeia, diagonal, negativos, dimensões separadas, recálculo com seção 8 × 8);
  tiles/entidades por seção e distância entre regiões com carga; T3 nos 7 casos; válvula (para, não para, folga mínima);
  relatório (JSON válido, sem UUID/IP/classe/jogador).
- `TesteObservadorConfig`: chaves, arquivo criado/regravado/não regravado, registro, comando (argumentos), ciclo completo
  com jogo e relógio falsos (fim por tempo, parar, parada do servidor, válvula, erro na foto, pasta impossível, índice de jars
  em outra thread).
- `TesteCorrecoesObservador` (defeitos da revisão independente): mundo coletado pelo GC depois do relatório; mod com número
  maior que 255 na T4 e na lista; `falha()` de outra thread só pede a parada (um relatório só); contexto "outro" fora da
  conta de serial.
- `TesteRevisaoConcObservador` (achados da revisão de concorrência, §8.1): teto de 20 amostras de pilha por tick em outra
  thread e válvula; pedido lido e zerado de uma vez (bytecode e 300 corridas); `falha()` que lança em outra thread não mexe em
  `ATIVO`; falha de gancho antigo não para a observação seguinte nem apaga pedido de início; 30 000 rodadas de dois `observar`
  juntos com exatamente um aceito; índice de jars travado abandonado depois do prazo.
- `TesteObservadorConcorrencia`: 4 threads × 200 000 acessos de outra thread ao mesmo tempo que a thread do servidor
  registra: contas exatas; uma thread "RCON" mandando comandos sem parar durante `HOSHIE_ESTRESSE_S` s (padrão 5).
- `TesteObservadorBytecode` (precisa do build rápido): 43 chamadas aos ganchos, todas atrás de `ATIVO`; padrões do Opis,
  do firewall (uma chamada `updateEntity`), `maxLocals` iguais; ganchos só leem; jogador entra sem objeto.
- `BenchObservador` (**indicação deste computador, sem Minecraft; não é medição de servidor**), JDK 8: leitura na mesma
  seção < 1 ns no núcleo isolado (o JIT embute; é limite inferior; a válvula supõe 8 ns contando o gancho); entrar+sair de
  contexto 3 a 6 ns (supõe 12); escrita registrada na mesma seção ~11 ns; escrita que cruza seção ~28 a 31 ns (já com a
  amostra de pilha amortizada); escrita que cruza região ~31 a 36 ns.

## 8. Não verificado

- **Boot com os 292 mods**, com o Opis, o firewall e o Mixin na cadeia: não foi feito nesta entrega (nenhum servidor foi
  ligado). Só o bytecode dos patches foi conferido. Os ganchos com objetos reais do Minecraft (`World`, `TileEntity`...)
  nunca rodaram: só o núcleo, com mundo falso, e a forma dos ganchos no bytecode.
- **Custo no servidor**: a meta (desligado ≤ 1% da mediana do tick e nada no JFR; ligado ≤ 5% com carga sintética) **não foi
  medida**. Os ns acima são do núcleo isolado.
- O índice de jars com a pasta `mods/` real (tempo de montagem, jars ilegíveis): usa o `IndiceDeJars` do vigia, não testado
  com o modpack nesta entrega.
- A distância entre regiões é em **seções**, a faixa em chunks é um limite (não o mínimo exato em chunks).
- Quem de fato chama (pilha) pode não aparecer quando o código do mod é injetado por mixin em classe do Minecraft.
- Comportamento com jogador humano de verdade, bases reais e distância real entre elas: **não verificável aqui**.
- Protótipo da lista (§10): só testes isolados; nunca rodou num mundo de verdade (ver §10.7).
- **Valvula de custo**: a primeira janela de 100 ticks de cada observação é de aquecimento (foto fria das regiões, primeiras
  amostras de pilha, D-44): o custo dela entra nos totais e aparece no relatório (`pct_da_primeira_janela_aquecimento`), mas
  só da segunda janela em diante a válvula pode parar. Um excesso real nos primeiros 100 ticks só é visto 100 ticks depois.
- **Parar durante a montagem do índice**: `hoshie observar parar` cancela o pedido (a observação não começa).
- **T3 não vê carga de chunk assíncrona** (achado 9 da revisão): `ChunkIOProvider.callStage2` e `CraftWorld.regenerateChunk`
  põem chunk no mapa sem passar por `originalLoadChunk`; a T3 conta só cargas síncronas/geração (D-41).
- **Achados menores da revisão que ficaram sem correção**: 6 (código de mod dentro de uma operação da lista não é contado: o
  contador de profundidade é um só; só subconta), 8 (contexto aninhado do mesmo tipo perde o de fora, só no resto do tick da
  montaria), 10 (a marca de escrita direta liga antes das checagens do `setBlock`: uma escrita direta legítima seguinte no mesmo
  chunk pode não ser contada), 11 (o `embrulhar` copia a lista na limpeza de tiles descarregados: custo não medido) e 12 (a
  lista pedida de dentro de um tile entra em "dentro"). Todos mexeriam no núcleo ou na lista sem teste de servidor e não mudam o
  comportamento do jogo; ficam como limite dos dados. A chave do plano `lista_tiles` é, no código, `observador.lista` (config) e
  o objeto `lista_tiles` (relatório).

### 8.1 Revisão de concorrência (2026-11-23): o que foi corrigido e o que ficou como limite

"Thread do servidor" = a que roda o laço de tick. "Válvula" = o que para a observação quando o custo estimado passa do limite.

Corrigido (cada um com teste em `TesteRevisaoConcObservador`):
- **I1** amostra de pilha em outra thread: teto de 20 por tick (T4 e lista somadas) e custo dentro da válvula. Números da
  revisão (carga sintética, indicação): 166 a 860 ns por chamada antes da correção. Depois dela: **não medido**.
- **m1** o início do tick lê e zera o pedido de uma vez (`getAndSet`): um `parar` não se perde mais.
- **m2** `falha()` recebe o núcleo do gancho; de outra thread só pede a parada daquela observação, num campo próprio.
- **m3** o comando confere e age sob trava; `parar` e `status` usam `N` (volátil) no lugar de `ATIVO`.
- **m4** `falha()` não escreve `ATIVO` de outra thread.
- **m8** prazo de 120 s na montagem do índice de jars.

Limites que ficaram (sem mudança de código):
- **m5** Na parada, a thread do servidor espera a gravação do observador por até 10 s (e a do medidor por até 5 s) **antes**
  do `stopServer()`. Para o recurso `parada` isso ainda é "o laço não parou". Com `parada.ligado: true`, use
  `parada.espera_laco_segundos` de **20 ou mais** (o padrão é 60) se for parar o servidor com observação ou medição de pé; com
  valor menor e gravação lenta o gancho de parada pode desistir e salvar ele mesmo. O limite do vigia do Spigot
  (`WatchdogThread`) nessa espera não foi conferido.
- **m6** Se o laço de tick cair por exceção, a observação em andamento **não é gravada** (o encerramento só roda na saída
  normal do laço; mesmo limite do medidor). Não mexemos no caminho de queda do `MinecraftServer` sem teste de servidor.
- **m7** `ATIVO` não é volátil (para o teste de campo nos ganchos custar o mínimo). Outra thread num laço quente pode demorar
  a ver a mudança: a T4 pode contar a menos no começo da observação. Só afeta a contagem.
- **m9** Medidor e observador juntos: o tempo de tile do medidor inclui o gancho do observador. Não rodar os dois juntos.

## 9. Plano de teste no modpack (para o agente que ligar o servidor)

1. Cópia nova com `newcopy.sh`, trocar o `server.jar` pelo desta entrega; `observador.ligado: true` só na cópia (não imprimir o config).
2. Boot padrão (observador desligado): linha `recursos:` com `observador`, nenhuma linha de erro, boot com Opis/firewall (a linha
   "Patched TileEntity tick gate" do firewall tem de continuar saindo). Conferir `hoshie status`.
3. Ligado, com a carga sintética do medidor (cerca de 2 000 funis e 400 porcos) mais uma corrente de funis atravessando a borda
   de uma seção e máquinas de mod por comando: `hoshie observar 120`; ler `.json`/`.txt`; conferir que o relatório tem acessos que
   cruzam seção, T2 e T5, e nenhum dado pessoal; `hoshie observar status` durante.
4. Parada no meio: `hoshie observar 600` e `stop` no console; o relatório tem de sair com `parou_no_meio`.
   Com `parada.ligado: true`, repetir com SIGTERM (item 1.G) e observação de pé: o relatório sai e o log da parada não mostra
   desistência (§8.1, m5). Dois `hoshie observar 60` seguidos (console e RCON): o segundo é recusado.
5. Custo: mesma cópia, três rodadas de 10 min (jar 1.0.0, Hoshiestar desligado, ligado), tempo de tick pelo relógio do vigia/medidor e
   `jcmd <pid> JFR.start duration=600s filename=...` em cada uma; comparar mediana, p95 e as amostras de CPU em `hoshiestar.observador`.
6. Bot (`tools/test_bot.py`) conectando e andando enquanto observa (contexto "jogador pela rede" aparece, sem nome).

## 10. Protótipo da lista de tiles (`observador.lista`, plano da Etapa 2 §4)

**Pergunta:** dá para trocar o **objeto** `world.loadedTileEntityList` por uma lista nossa (no futuro, uma lista que entrega a
cada região só a parte dela) sem quebrar mods? Antes de construir isso, o protótipo mede **quem usa a lista de fora do
`World`, como e com que frequência**. Nesta etapa a lista nossa **devolve exatamente o mesmo** que a original e só anota.

### 10.1 O que o fonte do fork faz com a lista (todo uso conferido antes de escrever)

- Campo: `public List loadedTileEntityList = new HashedArrayList();` (`World.java:131`). Tipo declarado `List`; o objeto é
  `io.github.crucible.util.HashedArrayList` (subclasse de `ArrayList` com um `LinkedHashSet` para o `contains`).
- O Crucible **troca o objeto** por um `ArrayList` comum na limpeza dos tiles descarregados (`World.updateEntities`,
  `this.loadedTileEntityList = temporary_tile_entity_list`). Depois da primeira limpeza o campo já não é `HashedArrayList`.
- Uso do próprio `World`: laço de tiles por índice (`size`/`get`/`remove(int)`), limpeza (`size`, `for-each`), pendentes
  (`contains`/`add`), `func_147448_a` e `addTileEntity` (`add` via `Collection dest`, comentário "List -> Collection for CB
  loadedTileEntityList type change"), `setTileEntity` (`add`). Fora do `World`: `CauldronHooks` (dump: `size`/`get`),
  `CauldronCommand`/`CrucibleCommand` (`size`), `WatchdogThread` (`size`, de **outra thread**), medidor (`size`) e a foto do
  observador (`size`, `for-each`). Ninguém no fork faz cast para `HashedArrayList`.
- Defeitos do `HashedArrayList` que a lista nossa **preserva** (não conserta nada, só observa): `listIterator()` sem
  argumento chama a si mesmo (StackOverflowError; `equals` no JDK 8 passa por ele); `listIterator(int)`, `removeIf` e
  `replaceAll` não atualizam o conjunto de hash; `set` tira do conjunto o elemento **novo**; `clone` compartilha o
  conjunto. O teste diferencial confirma que a nossa erra igual.

### 10.2 Como funciona

- Com `observador.ligado: true` **e** `observador.lista: true`, cada `World` (inicializador logo depois do campo) cria a lista
  como `ListaObservadaHashed` (subclasse de `HashedArrayList`); quando o Crucible troca o objeto na limpeza, o patch embrulha o
  `ArrayList` novo em `ListaObservadaArray` (subclasse de `ArrayList`, mesmos elementos, mesma ordem) e marca a velha como
  **aposentada**. Como as duas são `ArrayList`, cast para `ArrayList` continua funcionando como antes.
- Cada método público (e o `removeRange` protegido, que `subList().clear()` usa) chama o **mesmo** método da superclasse e
  devolve o que ele devolveu, inclusive exceções. Sem observação é só isso, depois de um teste de campo estático
  (`Observador.ATIVO`).
- Durante `hoshie observar`: o `World` marca os trechos em que **ele** mexe na lista (limpeza; laço de tiles + pendentes;
  `func_147448_a`; `setTileEntity`; `addTileEntity`); dentro do laço, o `updateEntity` do tile volta a ser "de fora" (o
  contexto de tile do observador liga isso). Toda chamada fora dos trechos marcados é **de fora** e conta por operação ×
  tipo de contexto; as chamadas internas da superclasse (ex.: `equals` → `listIterator`) não contam de novo (profundidade).
  O iterador devolvido a quem chamou de fora é embrulhado num que delega tudo e só conta `remove`/`add`/`set`.
- Amostra de pilha (classe.método chamador, mod da pilha, mod do contexto): **toda** modificação de fora e as leituras 1 em
  `amostra_pilha`, no máximo 20 por tick; o custo entra na válvula.
- De outra thread: contadores atômicos por operação (mod da pilha em 1 de cada 64, no máximo 20 amostras por tick somando com a T4). Chamadas numa lista aposentada (alguém
  guardou a referência antiga) e numa cópia (`clone`, não conta) são separadas.
- No fim de cada tick: confere se o campo de cada mundo ainda é uma lista nossa; se não for, conta "troca de fora" e anota a
  classe achada (alguém trocou o objeto, ex.: por reflexão). Nada é desfeito: só anota.

Operações contadas (categoria): L leitura (`size`, `isEmpty`, `get`, `contains`, `containsAll`, `indexOf`, `lastIndexOf`,
`toArray`); I iteração/visão (`iterator`, `listIterator`, `forEach`, `spliterator`, `stream`, `parallelStream`, `subList`);
M modificação (`set`, `add`, `addAll`, `remove`, `removeAll`, `retainAll`, `removeIf`, `replaceAll`, `sort`, `clear`,
`removeRange` via `subList`, `iterator.remove`, `listIterator.remove/add/set`); A só de `ArrayList` (`trimToSize`,
`ensureCapacity`, `clone`: sinal de cast para `ArrayList`); O `equals`, `hashCode`, `toString`.

### 10.3 Patches (`World.java`, todos `// Hoshiestar - observador`)

| Onde | Linha |
|---|---|
| logo depois do campo | `{ if (Observador.LISTA) this.loadedTileEntityList = new ListaObservadaHashed(); }` |
| limpeza dos descarregados | `mundo()` antes; `if (Observador.LISTA) temporary_tile_entity_list = ListaObservada.embrulhar(campo, temporary_tile_entity_list);` antes da atribuição; `fimMundo()` depois |
| laço de tiles + pendentes | `mundo()` antes do laço; `fimMundo()` depois dos pendentes |
| `func_147448_a`, `setTileEntity`, `addTileEntity` | `mundo()`/`fimMundo()` em volta do `add` |

As marcas ficam atrás de `if (Observador.ATIVO)` (pula só a chamada); a troca e o `embrulhar`, atrás de
`if (Observador.LISTA)`. Nenhuma variável local nova (`updateEntities` continua com `maxLocals` 14), nenhuma mudança na linha do
`updateEntity` (Opis/firewall). Desligado = um teste de campo estático e **o objeto original**. Interruptor só no boot porque
trocar o objeto com mundos já carregados não faz parte do protótipo.

### 10.4 Critério de decisão (sai no relatório como **indicação**; a decisão é do relatório do portão)

Contextos "de região" = tile, entidade, jogador pela rede, tick agendado, tick aleatório, evento de bloco (existem na fase
paralela de um Degrau B/C). Os demais (handlers globais antes/depois do tick, tick de mundo, rede, agendador de plugins)
são seriais. O contexto "outro" (nenhum contexto marcado: não se sabe quem executa) **não é serial nem de região**: é
contado à parte (`de_fora_sem_contexto_conhecido`) e mostrado na indicação.

1. **Descarta** a lista por região se o campo foi trocado fora do `World`, ou se há **modificação de fora em contexto de
   região** (a lista teria de mentir para o mod, N4). O relatório lista os mods; o portão confere se dá para pô-los em série.
2. **Desnecessária no Degrau B** se ≥ 99% dos acessos de fora **com contexto conhecido** vêm de contextos seriais (ou não há
   acesso de fora): basta a regra já prevista, "a lista não muda de tamanho na fase paralela". Se mais da metade dos acessos
   de fora vem sem contexto conhecido, a indicação é "inconclusivo" (limite escolhido por nós, não do plano).
3. **Prova a hipótese** se os acessos de fora em contexto de região são só leitura/iteração e de até 5 mods (cabem num módulo
   ou na lista "só em série").
4. Fora disso: inconclusivo (leitura de mais de 5 mods).

Também pesam, sem entrar na conta automática: chamadas numa lista **aposentada** (um mod guardou a referência; com uma lista
por região ele veria uma lista velha, como já acontece hoje com o Crucible), chamadas de **outra thread** e operações "só de
`ArrayList`". A mesma medição para `loadedEntityList` fica para depois, se o resultado pedir.

### 10.5 Relatório

`.json`: objeto `lista_tiles` (`ligada`, `indicacao`, totais do `World`/de fora/serial/região, modificações, lista
aposentada, outra thread, trocas pelo `World` e de fora, classes achadas no campo, `por_operacao` com `de_fora_por_contexto`,
`amostras` com operação/contexto/mod do contexto/mod da pilha/chamador, outra thread por operação e mod). `.txt`: seção
"Protótipo da lista de tiles" com a INDICAÇÃO, a tabela por operação e as 30 maiores amostras. Só classes, mods e números.

### 10.6 Testes isolados (`TesteListaObservada`, no `teste-observador.bash`, JDK 8 e 21)

- **Diferencial**: `ListaObservadaArray` × `java.util.ArrayList` e `ListaObservadaHashed` × `HashedArrayList` (o objeto que o
  `World` usa de verdade em cada fase), 6 modos (sem observação; de fora; dentro do `World`; outra thread; lista aposentada;
  cópia), 250 000 operações aleatórias por par e modo = **3 milhões por JDK** (`HOSHIE_LISTA_OPS` muda). Todas as operações de
  `List`/`ArrayList` do Java 8, iteradores e `ListIterator` vivos (inclusive usados depois de modificar a lista),
  `subList` (e `subList` de `subList`, `subList().clear()`), `removeAll`/`retainAll`/`addAll` com coleção nula, índices
  inválidos, `forEach`/`removeIf` que mexem na lista no meio, `spliterator` com modificação no meio, `stream`, `sort`,
  `equals`/`hashCode`/`toString`/`clone`, `trimToSize`/`ensureCapacity`. Resultado identico **inclusive exceções** (classe e
  mensagem: ConcurrentModificationException nos mesmos pontos, IndexOutOfBounds, NPE, StackOverflowError do
  `listIterator()`) e estado identico (elementos na ordem e, a cada 16 operações, `contains` de cada valor, que mostra o
  conjunto de hash escondido). A JVM roda com `-XX:-OmitStackTraceInFastThrow` (sem ele a exceção implícita repetida perde a
  mensagem em momentos diferentes nas duas listas). Conferido que o teste pega mutações (trocar `indexOf` por `lastIndexOf`;
  esquecer de devolver a profundidade).
- Contagens (de fora, contexto de tile, chamador e mod pela amostra, 1 em N, teto por tick, custo na válvula, cópia não conta,
  iterador embrulhado só de fora), marcas do `World` (inclusive sobra de exceção, outra thread não mexe, estouro), `embrulhar`
  (ordem, aposentada, nulo, lista que já é nossa), as 4 saídas do critério, relatório `.json`/`.txt`.
- `TesteObservadorBytecode` (no bytecode do build): marcas atrás de `ATIVO`, troca no construtor e `embrulhar` atrás de
  `LISTA`, nenhuma outra chamada à lista no `World`, `maxLocals` igual ao jar base.

### 10.7 Limites e não verificado

- **Não verificado em servidor**: nenhum servidor foi ligado nesta entrega. Boot com os 292 mods com `observador.lista: true`,
  o comportamento com mods reais e o custo **não foram medidos**. Os números do diferencial são de teste isolado.
- Cast (`(ArrayList) world.loadedTileEntityList`) não aparece em tempo de execução; só os métodos "só de `ArrayList`". Achar
  cast precisa da varredura estática dos jars (passo 7 do plano).
- Dentro dos trechos marcados do `World`, código de mod chamado **fora** do `updateEntity` conta como do `World`: `isInvalid`,
  `invalidate` chamado por `Chunk.removeInvalidTileEntity`, o tratamento de erro do tick (`invalidate` + `setBlockToAir`),
  `canUpdate`. Se um mod mexer na lista ali, não aparece. `onChunkUnload` fica fora das marcas (conta como de fora).
- `next()` de iterador e `set` feito por uma `subList` não são contados (a criação do iterador/visão é); `add`/`remove`/`clear`
  pela `subList` passam pela lista mãe e são contados.
- Métodos novos do JDK 21 (`getFirst`, `addFirst`, `reversed`...) vão direto para a superclasse, sem contar (mods de 1.7.10
  não os usam).
- De outra thread não há proteção de reentrada: uma operação que chama outra internamente (ex.: `equals`) conta as duas.
  Leituras de outra thread já existem hoje no fork (`WatchdogThread`); a lista nossa não acrescenta trava nem sincronização
  (a original também não tem).
- Uma `ListaObservadaArray` com JDK 21 tem classe diferente de `ArrayList`: `ArrayList.equals` do JDK 21 usa um caminho rápido
  só quando a outra é exatamente `ArrayList`; o resultado é o mesmo, muda só qual verificação de modificação concorrente roda.
  Também o construtor de cópia `new ArrayList(lista)` do JDK 9+ copia o array em vez de reaproveitá-lo: mesmo conteúdo.

### 10.8 Plano de teste no modpack (para o agente que ligar o servidor)

1. Cópia nova; `observador.ligado: true` e `observador.lista: true` só na cópia. Boot: sem erro, Opis/firewall como antes;
   `hoshie status` mostra "lista de tiles observada (protótipo)".
2. `hoshie observar 300` com a carga sintética do §9 mais máquinas de mod postas por comando, bot andando, e descarga de chunks
   (andar para longe e voltar: força a limpeza que troca o objeto). Ler a seção `lista_tiles`: `trocas_do_objeto_pelo_world` > 0,
   `trocas_do_objeto_de_fora` = 0 esperado, quem aparece de fora e em qual contexto.
3. Comparar com o mesmo boot com `observador.lista: false` (o relatório diz `ligada: false`) para ver se algo mudou no jogo
   (tiles ticando, contagem de tiles no `hoshie status`/`/crucible tps`). Perda de bloco/item: conferir um baú e uma máquina
   antes e depois de descarregar o chunk. Comportamento com jogador humano de verdade: não verificável aqui.
