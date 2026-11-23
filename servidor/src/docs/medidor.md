# Medidor de carga por região (`medidor`)

Tarefa 0.9 do plano. Porte do mod `Hoshiestar-Medidor` 1.0.4 para dentro do `server.jar` (decisão do
admin, 2026-11-22): mesmos comandos, mesmos arquivos de saída, nenhum jar em `mods/`.

## 1. O que faz (em linguagem simples)

O perfil do servidor diz **quanto** o tick custa, mas não **onde no mapa**. O medidor responde isso:
durante um tempo pedido pelo admin, ele cronometra cada atualização de tile entity (máquina, cano, baú
com lógica) e de entidade (mob, item no chão, jogador) e soma o tempo por **dimensão**, por **seção**
(quadrado de 16 × 16 chunks = 256 × 256 blocos) e por **chunk**. No fim grava dois arquivos:

- `<pasta do servidor>/hoshiestar/medicao-AAAAMMDD-HHMMSS.json` (tudo, para o projeto analisar);
- `<pasta do servidor>/hoshiestar/medicao-AAAAMMDD-HHMMSS.txt` (para ler direto: a linha principal é
  "As 1, 3, 5, 10 e 20 seções mais pesadas concentram X% do tempo medido").

Os arquivos não têm nome de jogador, UUID nem IP: só coordenadas, dimensões e nomes de classe. Nada vai
para a rede. O medidor não muda nada no jogo: só lê.

**Tempo próprio**: se um tick acontece dentro de outro (passageiro dentro da montaria, por exemplo), o de
dentro é descontado do de fora; nada conta duas vezes (pilha de "quadros": cada atualização medida abre um
quadro e, ao fechar, soma o tempo dela menos o dos quadros que abriram dentro).

### Comandos (subcomandos do `hoshie` da base)

| Comando | O que faz |
|---|---|
| `hoshie medir [segundos] [forcar]` | mede por 10 a 1800 s (padrão 300) e para sozinho. `forcar` mede mesmo com relógio lento. Qualquer outra palavra é **recusada** com a explicação (na 1.0.4 era ignorada). |
| `hoshie parar` | encerra a medição em andamento e grava o que já coletou. |
| `hoshie status` | o da base: a linha `medidor: ...` diz se está pronto ou medindo (andamento, válvula, custo estimado), que chamada de tile entity os ganchos envolvem (firewall ou vanilla), a última calibração, o último autoteste e o último resultado (só o nome dos arquivos; o caminho completo sai no console). |

Quem pode: o mesmo do `hoshie` da base (console, RCON e operador de nível 4 com o nó `hoshiestar.admin`).

### Proteções enquanto mede (as da 1.0.4; margem da válvula agora configurável)

- **Calibração** no início: mede o custo de `System.nanoTime` e de um quadro vazio nesta máquina. Relógio
  acima de 150 ns por leitura: recusa (salvo `forcar`). Pausa única de 15 a 40 ms no tick.
- **Válvula de sobrecarga**: para sozinha e grava se a mediana do tick, em 2 janelas seguidas de 100 ticks,
  passar a mediana dos 100 ticks antes de ligar em mais de 25% **e** 15 ms; ou se o custo estimado do
  medidor × a margem `medidor.margem_custo` (padrão **4**; na 1.0.4 era 2 fixo) passar de 10% do tick de
  antes (entre 5 e 10 ms).
- **Mod antigo em `mods/`**: se o jar `Hoshiestar-Medidor-*.jar` (1.0.x) estiver em `mods/`, o medidor se
  desabilita no boot com um erro no log dizendo o que apagar (ver seção 4, item 8).
- **Autoteste** (tick 100 e fim): se os mundos rodaram o laço com tile entities/entidades na lista e nada
  foi medido, diz `NAO_LIGADO` em vez de mostrar zero como dado; servidor ocioso (sem jogador) vira
  "sem dados"/cobertura baixa, com aviso no topo do `.txt` e `dados_validos: false`.
- **Memória limitada**: até 200.000 chunks, 100.000 seções, 200.000 pares seção/classe, 8.000 classes;
  o resto vai para "outros". Pior caso ~54 MB, só durante a medição.
- **Disco fora da thread do tick**: uma thread própria (daemon, prioridade mínima) monta e grava.

## 2. Chaves do `hoshiestar.yml`

| Chave | Padrão | O que faz |
|---|---|---|
| `medidor.habilitado` | `true` | `false`: `hoshie medir` recusa ("desabilitado no hoshiestar.yml") e os ganchos de tick do servidor nem leem o relógio. O medidor não tem chave "ligado": ele só mede quando o admin manda, por tempo limitado. |
| `medidor.margem_custo` | `4.0` | margem da regra de custo da válvula (1.0 a 20.0; fora disso ou inválido: 4.0 com aviso no log). A válvula para a medição se custo estimado × margem passar do limite (10% do tick de antes, entre 5 e 10 ms). Menor = mede mais no pico, com risco de o medidor pesar mais de 10% do tick sem a válvula parar. Copiada na medição ao começar; sai na linha de início, no `hoshie status` e no `.json` (`valvula.margem_custo_estimado`). |

Com `true` e sem medição, o custo é **um teste de campo estático por gancho** (`if (Medidor.ATIVO)`),
conferido no bytecode pelo `TesteMedidorBytecode`, mais umas poucas operações por **tick do servidor** (não
por entidade) para guardar a duração dos últimos 100 ticks, usando as leituras de relógio que o vigia já
faz (nenhuma leitura de relógio a mais quando o vigia está ligado).

## 3. Onde estão os ganchos e por que assim

Patches no fork, marcados `// Hoshiestar - medidor` (gerados com `servidor/gerar-patches.sh`):

| Arquivo / método | O que entrou |
|---|---|
| `World.updateEntities` (1ª linha) | `if (ATIVO) GanchosMedidor.inicioLacoMundo(this)` — o laço deste mundo rodou (autoteste) |
| `World.updateEntities`, laço de tiles, dentro do `try` | `if (ATIVO) inicioTE(this, tileentity)` como **1ª linha** do `try` (antes do `tickTimer.startTiming()` do Spigot) e `if (ATIVO) fimTE()` como **última** (depois do `stopTiming()`) |
| `World.updateEntityWithOptionalForce` | `inicioEntidade` logo depois de `if (isDead) return;`; `fimEntidade` antes dos dois `return` do Spigot/Thermos (tick inativo/bloqueado) e no fim do método |
| `EntityPlayerMP.onUpdateEntity` | `inicioJogadorRede`/`fimJogadorRede` (tick do jogador pela rede), ao lado das marcas do vigia |
| `MinecraftServer.tick` | `Medidor.inicioTickServidor()` depois de `Marcas.inicioTick()`; `Medidor.fimTickServidor()` depois de `Marcas.fimTick()` (pedidos do comando, válvula, autoteste, fim por tempo) |
| `MinecraftServer.run` | `Medidor.pararNoDesligamento()` logo antes do `handleServerStopping()` do FML (o mesmo ponto do evento que a 1.0.4 usava) |

Regras que decidiram a forma (conferidas no bytecode dos mods do pacote, `javap`/CFR):

- **Akashic-World-Firewall** (`PassiveItemTransformer.patchTileEntityTick`): exige **exatamente uma**
  chamada `TileEntity.updateEntity()` em `updateEntities` e a troca por `ALOAD 0; INVOKESTATIC
  WorldMutationGuard.runTileEntityUpdate`. Os ganchos não mudam a chamada e não acrescentam outra: o
  firewall continua aplicando, e os ganchos ficam em volta da chamada dele. O `RuntimeTickTransformer`
  dele põe `if (!allowEntityTick) return;` no **começo** de `updateEntityWithOptionalForce`, antes do
  nosso gancho.
- **Opis/MobiusCore** (`TransformerWorldCrucible`): procura a sequência exata `[linha, ALOAD, INVOKEVIRTUAL
  TileEntity.updateEntity]` e `[linha, ALOAD, ALOAD, INVOKEVIRTUAL World.updateEntity]` e injeta com
  `ALOAD 9` e `ALOAD 2`. Por isso: (a) nenhum `if` nem `try/catch` logo antes da linha da chamada (o desvio
  criaria um quadro de pilha entre a linha e o `ALOAD` — foi o que derrubou o 1º boot do vigia); o
  `inicioTE` fica antes do `startTiming()`; (b) **nenhuma variável local nova** (os índices 2 e 9 não podem
  mudar); (c) sem `try/catch` no ponto de chamada: quem trata erro é `GanchosMedidor`/`Medidor` (todo método
  ali tem `try/catch (Throwable)` que desliga a medição), e essas classes não têm inicialização estática que
  possa falhar. Conferido: `maxLocals` de `updateEntities` = 14 e de `updateEntityWithOptionalForce` = 13,
  os mesmos do `World` compilado sem os ganchos.
- **DragonAPI** (`TileUpdate` apaga do 1º `isInvalid()` ao 1º `blockExists(III)` do laço de tiles): os
  ganchos ficam depois do `blockExists`.
- **AkashicBellow** (MixinExtras `floor_double(?.posY / 16.0)` em `updateEntityWithOptionalForce`, `allow=1`):
  os ganchos não acrescentam `floor_double` (continuam 5).
- **Hodgepodge** (mixins em `updateEntities` e `tick`): alvos por chamada/HEAD, não afetados.
- `MinecraftServer.tick`/`run`: chamadas com `try/catch` no ponto de chamada (regra da base; o catch não
  muda os índices das variáveis seguintes).

### Arquivos novos (`src/main/java/hoshiestar/medidor/`)

| Classe | Papel |
|---|---|
| `Medidor` | estado, pilha de quadros, calibração, válvula, autoteste, comandos, linha de status. **Sem classe do Minecraft** (o que roda nos testes é este mesmo código). |
| `Coleta`, `TabelaLong`, `Relatorio` | números de uma medição, tabela de hash de `long`, montagem e gravação dos arquivos (vieram da 1.0.4 quase sem mudança) |
| `Jogo` | o que o medidor pergunta ao jogo (entidade morta? quais mundos? pasta de saída) |
| `GanchosMedidor` | a única classe com tipos do Minecraft: tira dimensão/chunk/montaria do objeto e chama o `Medidor`; `JogoMinecraft`; detecta o firewall na cadeia de transformadores |
| `ComandoMedidor` | subcomandos `medir`/`parar` (Bukkit), registrados em `Servidor.iniciando` |
| `RegistroMedidor` | registro do recurso (fase de coremod) e linha do status |

Fora do pacote: uma linha em `Catalogo`, um bloco em `Servidor.iniciando`, os campos `medidor_habilitado` e
`medidor_margemCusto` em `HoshiestarConfig`, e dois métodos em `vigia/Marcas` (`nanoInicioTick`/`nanoFimTick`: entregam ao medidor
as leituras de relógio do início e do fim do tick que o vigia já faz).

## 4. Argumento de segurança

1. **Só leitura**: nenhum gancho escreve em objeto do jogo; as chamadas originais ficam como estão (mesma
   instrução, mesma ordem). Sem perda/duplicação de item, energia ou bloco possível por construção.
2. **Desligado não muda nada**: `ATIVO` falso ⇒ o patch só lê o campo e segue. `medidor.habilitado:
   false` ⇒ além disso os ganchos de tick voltam na 1ª linha.
3. **Erro nosso nunca quebra o tick**: todo método chamado por patch tem `try/catch (Throwable)`; o erro
   desliga a medição, descarta os dados (podem estar inconsistentes), sai **uma vez** no log por medição e
   aparece no status. O próprio `Medidor.falha()` fica inteiro dentro de `try/catch` (revisão M2): nem um
   `Throwable` com `toString`/`getMessage` que lançam escapa. Isso importa porque o gancho de tile entity
   roda dentro do `try` do laço de tiles do `World`: um erro que escapasse seria tratado como erro da tile
   entity da vez (invalidada ou servidor caído, conforme o `forge.cfg`). Exceção da própria máquina/entidade passa sem ser tocada (os ganchos são chamadas
   separadas); o quadro que ficou aberto é descartado quando o laço do Crucible marca a entidade como morta
   ou invalida a tile entity.
4. **Memória e disco**: limites fixos (seção 1); gravação numa thread própria; nomes reservados com
   `createNewFile` (duas gravações nunca escrevem no mesmo arquivo); temporário com nome único.
5. **Pasta reserva** (`<java.io.tmpdir>/hoshiestar-<usuário>`, se a principal falhar): nasce `rwx------`
   (correção 8b), arquivos `rw-------`; recusada se for link simbólico, se não for pasta ou se for de outro
   usuário; a sonda de dono agora é criada dentro dela (na 1.0.4 ia para o `/tmp`, e um `/tmp` sem escrita
   recusava uma reserva válida); a mensagem de erro sempre diz o motivo (correção 8b: `AccessDeniedException`
   trazia só o caminho).
6. **Concorrência** (o mesmo desenho da 1.0.4): `ATIVO` e a pilha só são tocados pela thread do servidor.
   Comando vindo de outra thread (RCON) vira pedido guardado por uma trava (`TRAVA`); a thread do servidor
   consome o pedido dentro da trava no início do tick seguinte (ligar) ou no fim do tick corrente (parar, só
   a medição que estava em andamento quando o pedido foi feito). O status lido de outra thread usa campos
   `volatile`. A thread de gravação recebe a `Coleta` inteira e a thread do servidor nunca mais a toca.
   Os dois métodos novos em `vigia/Marcas` (campo `fimTickNanos`, só a thread do servidor lê e escreve) e a
   troca do evento de tick do FML pelos patches em `MinecraftServer.tick` foram **revisados por outro agente**
   (revisão independente de 2026-11-23: nenhuma corrida achada; tabela de quem escreve/lê cada campo no
   relatório da revisão). O estresse com threads reais da revisão virou teste do projeto (`TesteMedidorMarcas`).
7. **Permissão**: a do `hoshie` da base (console, RCON, operador nível 4 com `hoshiestar.admin`).
8. **Mod antigo junto** (revisão I1): o jar 1.0.x tem classes com o mesmo nome completo das do server.jar
   (`hoshiestar.medidor.Medidor`, `Coleta`, `TabelaLong`, `Relatorio`) e usa membros que não existem mais
   (`Medidor.LOG` do tipo log4j, `inicioTE(World,TileEntity,int)`), além de registrar outro comando `hoshie`.
   Juntos: provável `NoSuchFieldError` no boot ou ganchos de tick 2× por tick (por leitura do bytecode; não
   bootado com o mod antigo de verdade). Defesa: (a) no registro (fase de coremod, só lista a pasta) um jar
   `mods/Hoshiestar-Medidor*.jar` desabilita o medidor (`habilitado = false`: comando recusa, ganchos de tick
   voltam na 1ª linha, `ATIVO` nunca liga, então os ganchos do mod antigo também ficam parados) e sai um
   ERROR dizendo qual jar apagar; o `hoshie status` mostra `medidor: desligado - DESABILITADO: mod antigo ...`;
   (b) com o servidor de pé, a classe `hoshiestar.medidor.MedidorMod` (só existe no mod antigo) no class
   loader faz o mesmo, pegando o jar mesmo renomeado. O roteiro manda apagar o jar antes do primeiro boot.
9. **Parada**: no desligamento, o medidor espera a gravação no máximo 5 s (era 20 s; revisão M3), antes do
   salvamento do mundo; se passar, avisa e o desligamento segue (os arquivos daquela medição podem ficar
   vazios; o mundo é salvo normalmente).

## 5. Diferenças em relação ao mod 1.0.4 (desvios)

- Ganchos no fonte do fork em vez de Mixin. A distinção "par vanilla/par firewall" e o aviso de "gancho pela
  metade" deixaram de existir (os ganchos estão sempre no bytecode, em volta da chamada que estiver lá). Que
  chamada é envolvida (`firewall`/`vanilla`) é **deduzida** da cadeia de transformadores (o
  `PassiveItemTransformer` do firewall presente ou não); a prova é a linha do próprio firewall no log do boot
  (`Patched TileEntity tick gate at World#updateEntities.`). No `.json`, `te_ticks_via_firewall`/`_vanilla`
  recebem o total conforme essa dedução e há `"ganchos":"fork"`.
- `hoshie status` é o da base (uma linha do medidor) em vez do status de várias linhas do mod; permissão:
  operador **nível 4** + nó `hoshiestar.admin` (o mod aceitava nível 3).
- Entidade já morta no início de `updateEntityWithOptionalForce` não abre quadro (o mod contava um tick de
  ~0 ns). Entidade barrada pela política do firewall (`allowEntityTick`) também não (o firewall sai antes do
  gancho). Tile entity barrada pelo firewall continua contada (o gancho está em volta da chamada dele).
- Versão no `.json`/`.txt`: `1.1.0` (lógica) + `"servidor":"Hoshiestar 1.0.0-dev ..."`.
- Correções 8b: argumento desconhecido recusado; motivo nas mensagens de falha de pasta (principal e
  reserva), com cada caminho uma vez só (revisão M4); reserva criada já fechada. Extra: sonda de dono dentro
  da reserva (se ela não pode ser criada: "sem acesso para criar arquivo nela (pasta de outro usuario ou sem
  permissao de escrita: ...)"); status sem caminho de pasta.
- Margem da regra de custo configurável e padrão 4 (era 2 fixo), espera da gravação na parada 5 s (era 20 s),
  conferência do mod antigo no boot (rodada de correção da revisão, seção 10).
- Mensagens de log com o prefixo da base: `[Hoshiestar] [medidor] ...`.
- Testes isolados num script próprio (`src/test/medidor/teste-medidor.bash`), como o do vigia: o
  `servidor/test.sh` só compila uma lista fixa de pacotes. Proposta para o coordenador: o `test.sh` rodar
  também `src/test/*/teste-*.bash` (este faixa não altera scripts do repositório).

## 6. Testes isolados (um comando)

```bash
HOSHIE_LIBS=<pasta libraries/ de um servidor> bash <workspace>/src/test/medidor/teste-medidor.bash <workspace>
```

O script roda com `umask 022` (o teste da pasta reserva compara "nasce fechada" com uma pasta comum) e roda
o `TesteMedidorConfig` dentro da pasta de trabalho (o registro procura o jar antigo em `./mods`, como o
servidor). Roda no JDK 8 e no JDK 21 (resultado da rodada de correção, 2026-11-23, os dois iguais):

| Teste | O que prova | Resultado |
|---|---|---|
| `TesteMedidorConfig` | `medidor.habilitado` (padrão true, comentário no arquivo, valor do admin lido sem regravar); com false o comando recusa e os ganchos de tick não fazem nada; linha do status; `medidor.margem_custo` (padrão 4.0 no arquivo, 2.5 do admin vale, 99 vira 4); jar antigo em `./mods` (só `Hoshiestar-Medidor*.jar`, não `.disabled`): recurso desligado com a descrição do que apagar, comando recusa, status começa pelo motivo; classe `MedidorMod` num jar renomeado (class loader de teste) desabilita | 27 ok |
| `TesteMedidor` | TabelaLong (crescimento, limite → "outros"); chaves com negativos; Coleta; 14 casos de argumento (`30 xyz`, `30 forca`, `xyz forcar`... recusados); **contagem exata**: 104 TEs falsas × 601 ticks = 62.504, chunk (2,5) = 40 × 601 = 24.040, chunk (−3,18) = 64 × 601 = 38.464, 300 entidades × 601; `.json` válido (leitor estrito); sem nome/UUID/IP; aninhamento (montaria 1 ms + passageiro 2 ms = 3,01 ms por tick, não 5); TE que lança: quadro descartado; pilha de 40 (32 medidos, 8 pulados); autoteste ocioso / `NAO_LIGADO` / dimensão sem laço; robustez (duas medições, parar no meio do tick conta 1 tick, sem jogo); pedidos de outra thread (ligar, parar, cancelar); válvula de tempo (para no tick 199–200) e pico de uma janela não para; válvula de custo (limiares 4,8 / 5,2 ms); pasta reserva (`criarFechada` isolado: nasce `rwx------` enquanto uma pasta comum nasce `rwxr-xr-x`; arquivos `rw-------`, link simbólico, arquivo no lugar, outro dono, motivo nas mensagens com cada caminho uma vez só); válvula de custo com margem 4 (1,2 ms × 4 não para, 1,3 ms × 4 para) e com margem 2; margem do config na medição e no `.json`; falha interna desliga sem lançar, inclusive com `Throwable` cujo `toString`/`getMessage` lançam | 133 ok |
| `TesteMedidorMarcas` (veio da revisão) | relógio de verdade pelo caminho dos patches (`Marcas` do vigia antes, `Medidor` depois): `nanoInicioTick` reaproveita a leitura do vigia, `nanoFimTick` é consumido; referência da válvula com ticks de ~1 ms; a calibração (~33 ms) não entra no 1º tick (1 µs contado); estresse de 10 s com thread "RCON" mandando medir/parar/status (~23 mil ticks, ~110 medições, todas com `.json` e contagem exata, pilha vazia no fim de todo tick, sem erro interno); `habilitado=false` não mexe na referência | 19 ok |
| `TesteMedidorBytecode` | no `World`/`EntityPlayerMP`/`MinecraftServer` compilados do workspace: 9 chamadas a `GanchosMedidor`, todas atrás de `GETSTATIC Medidor.ATIVO` + `IFEQ` que pula só a chamada; padrões do Opis intactos (ALOAD 9 / ALOAD 0, 2); 1 chamada `TileEntity.updateEntity`; locais 2/9; `maxLocals` 14 e 13; 5 `floor_double`; ganchos de tick e de parada | 13 ok |
| `BenchMedidor` | custo do caminho ligado numa JVM sozinha | 88–91 ns por TE medida (calibração 89–104 ns). **Indicação, não medição de servidor.** |

**Mutações** (rodada de correção; cópia do `src/`, JDK 8): cada uma foi pega por pelo menos um teste. A: pasta
reserva criada sem `rwx------` (pega pelo `criarFechada` isolado); D: calibração dentro do 1º tick
(`TesteMedidorMarcas`); E: `Marcas.nanoFimTick` sem consumir (`TesteMedidorMarcas`); F: `habilitado=false`
não desliga os ganchos de tick (`TesteMedidorMarcas`); G: `jarsAntigos` nunca acha (`TesteMedidorConfig`, 5
falhas); H: regra de custo com margem 2 fixa (`TesteMedidor`); I: margem do config não copiada na medição
(`TesteMedidor`, 2 falhas). Na revisão, A, D, E e F passavam sem nenhuma falha.

Também rodado depois da rodada de correção: `servidor/test.sh` (TesteAsm 224, TesteTrava 35, TesteLimitador
13, TesteCampoPrivado 8, TesteConfig 35, nos dois JDKs). `vigia/Marcas` não mudou nesta rodada (o
`teste-vigia.bash` passou na rodada anterior: Config 38, Índice 22, Núcleo 111, Bench 2).

## 7. Testes no modpack real (2026-11-23, cópia `medidor-test/med1`, jar do workspace `b6cbd203`)

Os logs não vão para o repositório (têm dados da cópia de teste); as linhas que importam estão transcritas
aqui. Todos os boots com `stop` no console, parada limpa, sem
crash-report e sem "Exception stopping"; nenhum jar nosso em `mods/` ou `plugins/`; nenhum `kill` usado.

- **T1** (boot A, padrão, mundo novo): linha de boot `recursos: ... vigia=ligado, medidor=ligado`; depois do
  Done: `recurso medidor: ligado | pronto; nenhuma medicao em andamento ...; ganchos do fork: tile
  entities=firewall, entidades=sim, jogadores pela rede=sim (Akashic-World-Firewall na posicao 102 de 132
  ...)`; o `hoshie status` repete; `hoshiestar.yml` ganhou `medidor: habilitado: true` com o comentário.
- **T4** (mesmo boot): `Patched TileEntity tick gate at World#updateEntities.` e `Patched denied-entity tick
  guard at World.` (firewall aplicando) e 4 × `Successful injection in h ()V` (Opis aplicando).
- **T2**: 104 funis por comando + bot conectado; `hoshie medir 30`: `TileEntityHopper` = **62.504 = 104 ×
  601** ticks (601 = ticks com laço); chunk (4,18) = **24.040 = 40 × 601**; chunk (4,15) = 39.065 = 65 × 601
  (64 funis + 1 TE que já existia no chunk); 602 ticks do servidor, cobertura 100%, autoteste ok, tick do
  jogador pela rede medido (599); sem `HoshieBot`, UUID nem IP nos arquivos.
- **T3**: `hoshie medir 30 xyz`, `30 forca`, `5`, `abc` recusados com o motivo; `hoshie parar` sem medição e
  `hoshie parar agora` recusados; segunda medição durante a primeira recusada; status no meio mostrou o
  andamento; medição de 60 s parada com `hoshie parar` aos 10 s (201 ticks, 1 parcial); sem jogador (70 s
  depois de o bot sair): laço em 1% dos ticks, aviso no topo do `.txt`, `dados_validos: false`; medição
  ligada durante o `stop`: arquivos gravados antes de "Stopping server" (`motivo: desligamento do servidor`).
- **Sem firewall** (boot D, jar do firewall fora de `mods/` só nesse boot): status `tile entities=vanilla
  (Akashic-World-Firewall ausente ...)`, nenhuma linha do firewall, Opis aplicando; `hoshie medir 20` com
  2.152 funis: `TileEntityHopper` = **862.952 = 2.152 × 401**.
- **T5** (carga: 2.152 funis + 400 porcos, bot conectado; `forge tps` = média dos últimos 100 ticks, uma
  leitura a cada ~10 s, começando 2 min depois do bot entrar):

  | Fase | Leituras | Média (ms) | Mediana | Desvio |
  |---|---|---|---|---|
  | jar base `75a77da9` (sem os ganchos), boot B | 15 | 17,68 | 17,69 | 1,19 |
  | jar do medidor, parado, boot C | 15 | 16,75 | 17,07 | 0,72 |
  | medindo, 1ª medição depois do boot (calibração 312 ns/quadro) | 15 | 18,70 | 18,57 | 0,64 |
  | medindo, 2ª medição (calibração 82 ns/quadro) | 10 | 18,35 | 18,30 | 0,58 |
  | parado de novo, logo depois | 10 | 17,58 | 17,59 | 0,78 |

  Parado: dentro do ruído contra o jar base (16,75 contra 17,68; o desvio do base é 1,19). Ligado:
  ~2.830 quadros por tick (2.197 de TE + ~630 de entidade); o tick subiu ~0,8 ms (2ª medição contra o
  "parado de novo") a ~2 ms (1ª medição contra o "parado" anterior). Custo **estimado** pelo medidor: 0,89 e
  0,23 ms por tick; válvula quieta nas duas (maior mediana de janela 21,6 e 20,3 ms contra 17,7 e 18,1 ms
  antes de ligar). A máquina tinha carga de outra faixa compilando (load average 2 a 3): números de um
  servidor de teste, não de produção.

### Rodada de correção (2026-11-23, cópia `medidor-test/medfix1`, jar `8490b225…` do workspace `d986e51c`)

Dois boots, os dois com `stop`, parada limpa, sem crash-report e sem "Exception stopping"; nenhum `kill`;
cópia apagada no fim.

- **Boot 1 (I1, mod antigo)**: com um zip **vazio** chamado `mods/Hoshiestar-Medidor-0.0.0-teste-vazio.jar`
  (sem nenhuma classe; só para o nome bater; não é o mod antigo): na fase de coremod, antes dos mods,
  `[RFB-Main/ERROR]: [Hoshiestar] [medidor] MEDIDOR DESABILITADO: o mod antigo Hoshiestar-Medidor esta
  instalado (mods/Hoshiestar-Medidor-0.0.0-teste-vazio.jar) ... APAGUE esse jar ...`; `recursos: ...
  medidor=desligado`; `hoshie medir 10` recusou com o mesmo texto. O FML só disse "non-mod file ... injected
  into your classpath". (A descrição do recurso desligado com o jar a apagar entrou depois deste boot e foi
  provada no teste isolado.)
- **Boot 2 (zip removido, padrão)**: `recursos: ... medidor=ligado`; `recurso medidor: ligado | pronto ...
  tile entities=firewall ... (Akashic-World-Firewall na posicao 102 de 132 ...)`; `Patched TileEntity tick
  gate at World#updateEntities.`, `Patched denied-entity tick guard at World.` e 4 × `Successful injection
  in h ()V` (T4 igual); `hoshiestar.yml` ganhou `margem_custo: 4.0` na seção `medidor`.
  - T2: 104 funis + bot; `hoshie medir 30 xyz` recusado (`Argumentos demais: '30 xyz'`); `hoshie medir 30`:
    linha de início com `(custo x margem 4.0)`; `TileEntityHopper` = **62.296 = 104 × 599**; chunk (4,18) =
    **23.960 = 40 × 599**; (4,15) = 65 × 599; sem `HoshieBot` nem `127.0.0.1` nos dois arquivos; autoteste ok.
  - Carga (2.152 funis + 400 porcos, bot): `hoshie medir 90`: calibração 82 ns/quadro, custo estimado 0,23
    ms/tick, × 4 = 0,94 ms contra limite 5,0 ms: **válvula quieta** (`disparou: false`); `TileEntityHopper` =
    2.152 × 1.800 exato. `forge tps` (média de 100 ticks, 5 leituras): parado 19,29 / 19,77 / 19,98 / 18,62 /
    18,23 ms; medindo 19,85 / 20,44 / 20,35 / 18,54 / 17,86 ms (ruído da cópia; o código do caminho quente
    não mudou nesta rodada, então a comparação com o jar base do T5 acima continua valendo).
  - Parada durante medição: `hoshie medir 60` e `stop` 15 s depois: "Arquivos gravados" (motivo
    `desligamento do servidor`) às 03:29:49, "Stopping server" às 03:29:50, "Saving worlds" logo depois;
    nenhum aviso de espera.
  - O bot desta rodada conectou na 2ª tentativa: o `conectar-bot.bash` novo matou só o PID da 1ª.

## 8. Não verificado

- **Custo real × estimativa**: no T5 o custo real ligado foi ~0,8 ms (2ª medição) a ~2 ms (1ª) contra 0,23
  ms estimados (3 a 8×; o ruído entre boots é ~1 ms, então o tamanho exato não está verificado). A margem
  passou a 4 (configurável). Em produção (~30 mil quadros por tick, tick de 84 ms, limite 8,4 ms), a conta dá
  ~2,4 ms estimados × 4 ≈ 9,7 ms: a válvula **pararia a medição na 1ª janela** do pico, a menos que o admin
  baixe `medidor.margem_custo` (com 2, ~4,9 ms, mede; o custo real pode passar de 8 ms). Conta, não medida.
- Boot com o mod antigo 1.0.4 **de verdade** em `mods/` (a regra do projeto não põe jar nosso em `mods/`): o
  aviso no boot foi provado com um zip vazio de mesmo nome; a conferência pela classe `MedidorMod` só no
  teste isolado (class loader de teste). Se o FML cai com o mod antigo antes de o servidor ficar de pé, a
  linha de ERROR da fase de coremod já saiu antes.
- Mensagem "sem acesso" da sonda numa reserva `0700` de outro usuário com servidor **não root** (os testes
  rodam como root, que passa por cima da permissão).
- RCON (o caminho "pedido de outra thread" foi testado só no teste isolado); operador pelo chat (a
  permissão é a da base, já testada lá).
- Relógio lento de verdade (o caminho da recusa da calibração); boot em Java 8; carga de produção.
- Mod que roda o laço de um mundo fora de `World.updateEntities` (o autoteste por dimensão avisa).
- Que nenhum coremod fora deste pacote case padrão exato perto dos ganchos (conferidos: Opis, DragonAPI,
  Akashic-World-Firewall, AkashicBellow, Hodgepodge, CoFHCore/PlayerAPI pelo vigia).
- As mudanças desta rodada de correção (margem, conferência do mod antigo, `falha()`, espera na parada,
  mensagens de pasta) ainda não passaram por uma segunda revisão independente. Nenhuma delas mexe em
  concorrência nem no caminho quente dos ganchos (patches iguais).

## 9. Plano de teste de servidor (para repetir)

Ajudantes em `src/test/medidor/servidor/`: `gerar-comandos.py <pasta>` (grava `t2.txt` com os 104 funis,
`t6.txt` com 2.048 funis e `porcos.txt` com 400 porcos), `conectar-bot.bash <saida> <test_bot.py> <modlist>`
(bot de teste `tools/test_bot.py`; a 1ª tentativa costuma cair logo depois do boot) e
`amostras.bash <copia> <n> <intervalo_s> <rotulo>` (leituras do `forge tps`).

1. Cópia nova: `medidor-test/newcopy.sh <nome>` (do pacote de produção, semente fixa, 127.0.0.1, offline);
   troque o `server.jar` pelo jar do workspace; confira que não há jar nosso em `mods/`/`plugins/`;
   `medidor-test/start.sh <nome>`.
2. T1/T4: no `logs/latest.log`, `recurso medidor: ligado | pronto ... tile entities=firewall`,
   `Patched TileEntity tick gate`, `Successful injection in h ()V` (4×); `echo 'hoshie status' >> console.in`.
3. T2: `cat t2.txt >> console.in`; conecte o bot; `hoshie medir 30`; espere "Arquivos gravados" no log;
   no `.json`: classe `TileEntityHopper` com `te_ticks` = 104 × `ticks_com_laco`; chunk (4,18) = 40 × esse
   número; procure o nome do bot, UUID e IP nos dois arquivos (não pode haver).
4. T3: `hoshie medir 30 xyz` (recusa), `hoshie medir 60` + `hoshie parar` depois de ~10 s, segunda medição
   durante a primeira (recusa); o bot sai (`echo SAIR >> <saida>/cmds.txt`), espere 70 s, `hoshie medir 10`
   (aviso de poucos ticks no topo do `.txt`); `hoshie medir 60` + `stop` (arquivos gravados antes de
   "Stopping server").
5. T5: `cat t6.txt porcos.txt >> console.in`, `save-all`, `stop`. Boot com o jar base (sem os ganchos) e
   boot com o jar do medidor, os dois com o bot conectado: espere 2 min, `amostras.bash <copia> 15 10
   <rotulo>`; no jar do medidor, depois, `hoshie medir 180` e as mesmas leituras durante, e de novo parado;
   compare as médias; leia no `hoshie status` o custo estimado e a válvula.
6. Sem firewall (opcional): tire o jar do firewall de `mods/` só nesse boot; o status deve dizer
   `tile entities=vanilla`; `hoshie medir 20`: funis × ticks exato. Devolva o jar.
7. Mod antigo (opcional): com o servidor parado, crie um zip vazio `mods/Hoshiestar-Medidor-0.0.0-teste-vazio.jar`
   (`python3 -c "import zipfile; zipfile.ZipFile('mods/Hoshiestar-Medidor-0.0.0-teste-vazio.jar','w').close()"`),
   ligue: deve sair `MEDIDOR DESABILITADO: o mod antigo ...` antes dos mods, `medidor=desligado` na linha
   `recursos:`, e `hoshie medir 10` recusa. Pare, apague o zip.
8. Parada durante medição: `hoshie medir 60`, `stop` ~15 s depois: "Arquivos gravados" antes de "Stopping
   server", sem o aviso "nao terminou em 5 s".
9. Pare sempre com `stop` (`medidor-test/stopw.sh <copia>`), confira que não há `crash-reports/`, apague a
   cópia.

## 10. Rodada de correção da revisão independente (2026-11-23)

| Achado | O que foi feito |
|---|---|
| I1 roteiro não mandava tirar o jar 1.0.4 | Passo no roteiro (apagar `mods/Hoshiestar-Medidor-*.jar`); no boot o medidor se desabilita com ERROR claro se achar o jar (nome em `mods/`) ou a classe `MedidorMod` (jar renomeado). Seção 4, item 8. |
| I2 válvula de custo com margem 2 subestima (real 3 a 8×) | Margem configurável `medidor.margem_custo`, padrão 4. Decisão tomada aqui (fácil de desfazer: uma linha no `hoshiestar.yml`); consequência para o pico de produção na seção 8. |
| M1 testes não pegavam 4 de 6 mutações | `TesteMedidorMarcas` (casos 1 a 3 da revisão + calibração fora do 1º tick) e `criarFechada` isolado com `umask 022`; todas as mutações pegas (seção 6). |
| M2 `falha()` não protegida por inteiro | Corpo inteiro em `try/catch`, texto montado dentro; teste com `Throwable` cujo `toString`/`getMessage` lançam. |
| M3 espera de 20 s na parada antes do salvamento | 5 s, com aviso se passar. |
| M4 caminho repetido nas mensagens de pasta; "Permission denied" seco | Cada caminho uma vez; sonda que não pode ser criada vira "sem acesso para criar arquivo nela (pasta de outro usuario ou sem permissao de escrita)". |
| M5 testes fora do `servidor/test.sh` | Não corrigido aqui (esta faixa não muda scripts do repositório). Proposta: o `test.sh` rodar também `src/test/*/teste-*.bash`. |
| M6 roteiro em `src/docs`; doc citava logs no scratchpad | O roteiro fica em `src/docs/ROTEIRO-MEDIDOR.md` (no repositório: `servidor/src/docs/ROTEIRO-MEDIDOR.md`). Na junção das faixas vimos que tirá-lo do workspace faria o exportador pôr a criação e a remoção do arquivo em `servidor/patches/` (que só deve ter mudanças em arquivos do upstream), então ele ficou ao lado dos outros docs. A citação dos logs saiu. |
| M7 `pkill -f test_bot.py` no ajudante do bot | Mata só o PID que ele lançou. |
| M8 (herdado) `Coleta.ultimoMundo` segura um mundo descarregado até o fim da medição | Não mudado: no máximo 30 min, uma dimensão; mexer pede teste de descarga de dimensão. |

