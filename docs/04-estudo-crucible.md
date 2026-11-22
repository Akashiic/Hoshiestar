# Estudo do Crucible: história, vitórias e o que isso muda para o Hoshiestar

Síntese para decisão. Os relatórios completos, com arquivo e linha de cada
afirmação e a revisão independente de cada um, estão em `docs/pesquisa/`:
`crucible-historia.md`, `crucible-arquitetura-patches.md`,
`crucible-tick-e-threads.md`, `crucible-build.md`.

## 1. De onde vem

```
MCPC+  ->  Cauldron  ->  KCauldron  ->  Thermos  ->  Crucible
```

| Geração | O que resolveu | Por que parou |
|---|---|---|
| MCPC+ e Cauldron | juntar CraftBukkit/Spigot e Forge no mesmo servidor | em setembro de 2014 o repositório do CraftBukkit foi derrubado, e o Cauldron junto |
| KCauldron | manter a 1.7.10 viva depois disso | períodos de inatividade (segundo o README do Thermos) |
| Thermos | menos lag, proteção de mundo, patches do Spigot | arquivado em 2019-01-26 |
| Crucible | fechar as lacunas do Thermos, Java moderno, APIs novas | **ativo** |

Verificado: Thermos arquivado em 2019 e o fim do Cauldron em 2014. Autores e datas
das gerações antigas não foram verificados (as páginas originais saíram do ar).

Estado do Crucible hoje: 129 commits visíveis no clone (de março de 2024 a
novembro de 2026), 80 deles em 2026. Um mantenedor principal concentra metade dos
commits. Contribuição externa é aceita (23 pull requests de fora mesclados). As
versões são só builds automáticas da branch `staging`; não há versão "estável"
desde 2022. A produção roda uma dessas builds.

## 2. As vitórias: por que um híbrido é difícil e como a linhagem resolveu

"Híbrido" é um servidor que roda mods Forge e plugins Bukkit ao mesmo tempo. Os
dois mundos não foram feitos para se conhecer. Cada linha abaixo é um problema
que já foi resolvido e que **não podemos quebrar**.

| Problema | Como foi resolvido |
|---|---|
| Plugins são compilados contra nomes de classe que não existem num servidor Forge | Cada plugin é reescrito ao carregar (biblioteca SpecialSource), trocando os nomes |
| Bloco e item de mod não existem na lista fixa `Material` do Bukkit | A lista é estendida com o servidor rodando |
| Dimensão de mod não é "mundo" para o Bukkit | Cada dimensão do Forge vira um mundo Bukkit |
| Inventário de máquina de mod não é inventário Bukkit | Envoltórios que apresentam um como o outro |
| Mod quebra e coloca bloco sem avisar os plugins de proteção | Ponte de eventos: a ação do mod dispara o evento do Bukkit, com jogador falso quando é máquina |
| Plugin precisa saber "o que vai mudar" antes de autorizar | Captura dos blocos antes de aplicar |
| Tile entity e entidade ticam sempre, mesmo as inúteis | `tileentities.yml` e `entities.yml`: intervalo de tick por tipo |
| Desempenho fraco da 1.7.10 | Patches do Spigot: activation range, limitador de tick, watchdog |
| Java 8 obrigatório | Crucible: Java 8 a 21 com lwjgl3ify e RetroFuturaBootstrap |

O que o Crucible acrescentou de mais importante (com data): barramento de eventos
para plugins (2024-10), novo sistema de captura de blocos (2026-07), envio de
chunks em vários pacotes por tick e salvamento automático sem travar o tick
(2026-08), teleporte com gestão de chunks (2026-09, é o commit de produção), e
os primeiros cuidados com threads: handshake concorrente e lista de jogadores que
pode ser lida fora da thread principal (2026-10, **depois** da build de produção).

## 3. Como o código é montado

- 406 arquivos de patch aplicados sobre Minecraft + Forge decompilados, mais 406 arquivos Java próprios.
- Os maiores patches estão em `NetHandlerPlayServer`, `World`, `ServerConfigurationManager`, `MinecraftServer` e `Entity`. `World`, `WorldServer`, `MinecraftServer` e `ChunkProviderServer` são onde o tick por região vai colidir com o upstream.
- **A base foi reconstruída do zero aqui.** O jar gerado a partir de `67b53034` tem o mesmo manifesto e as mesmas 5.318 entradas do jar de produção; 5.317 são idênticas byte a byte (a única diferença é um arquivo de patches binários que muda a cada build). Esse jar ligou o servidor de teste com os 292 mods e parou limpo.
- Para compilar precisa de JDK 8 **e** JDK 17 (o segundo não está no README), ambiente em UTF-8, e uma troca de endereço do repositório Maven pessoal. Tudo documentado em `docs/pesquisa/crucible-build.md`. Leva cerca de 5 minutos.

## 4. O que o estudo mudou no plano

### 4.1 Já existe um "tick mais lento por tipo", e ele tem falhas
`tileentities.yml` e `entities.yml` deixam o admin dizer "esta classe tica a cada N ticks".
Mas, pelo código:
- O cache é por **classe** e guarda o primeiro mundo que ticou aquela classe. A configuração por mundo na prática não funciona.
- Não existe ajuste por chunk nem por posição. Não dá para desacelerar **uma base**; só um tipo de máquina no servidor inteiro.
- Todas as instâncias da classe ticam juntas no mesmo tick (`tempo do mundo % N == 0`), o que cria picos.

O Degrau A (orçamento por região) é o que falta: o mesmo efeito, mas por lugar,
e escalonado para não fazer pico. Ele deve somar-se a esses arquivos, não ignorá-los.

### 4.2 O Spigot dentro do Crucible está quase desligado na prática
- O limitador de tick é um orçamento por mundo de 1.000 ms: nunca atua.
- O activation range, pelo código, quase nunca age.
- A iluminação em thread separada existe, mas vem desligada (`use-async-lighting: false`).

Antes de escrever código novo vale conferir os valores reais de `spigot.yml`,
`cauldron.yml`, `tileentities.yml` e `entities.yml` de produção (não vieram no pacote).

### 4.3 O erro de desligamento (D-07) tem causa conhecida
O gancho de desligamento chama `stopServer()` na thread dele sem parar o laço de
tick. O Forge remove a dimensão do mapa de tempos enquanto a thread do servidor
ainda está usando. Continua assim na ponta do upstream. A revisão achou que é pior
do que parece: depois do erro, `stopServer()` roda uma segunda vez em paralelo
(salvamento e desligamento de plugins concorrentes). Correção pequena e boa candidata
a ser enviada ao Crucible original.

### 4.4 Plugins já passam por uma trava global
`SimplePluginManager.callEvent` executa todo evento síncrono dentro de uma trava
única. Na fase paralela, eventos de plugin vindos de várias regiões já ficariam
em fila sozinhos. Isso é bom para a segurança (era a regra que eu tinha proposto)
e ruim para o ganho: evento de plugin em região é ponto de espera.

### 4.5 Mods leem campos públicos direto
Contagem de jars que referenciam campos do mundo sem passar por método:
`playerEntities` 63, `playerEntityList` 46, `loadedEntityList` 34, `worldServers` 29,
`chunkTileEntityMap` 28, `loadedTileEntityList` 26. Confirma que interceptar métodos
não basta; a troca do objeto da lista (ver arquitetura) é necessária.

### 4.6 Estado estático que várias threads tocariam
Cache de tick por classe, `ItemStack.currentPlayer`, caixas do activation range,
o mapa de chunks do Thermos com cache dos 4 últimos chunks (`ChunkBlockHashMap`,
que sozinho custa 3,6% do tick em produção), e o Timings, que descarta em silêncio
qualquer medição feita fora da thread principal.

## 5. Hábitos do upstream que vamos seguir

1. Mudança em Minecraft/Forge só por `.patch` gerado com `./gradlew genPatches`. Nunca editar patch à mão.
2. Código novo em classes próprias; o patch só chama.
3. Marcar cada alteração com comentário `// Hoshiestar`.
4. Opções no `Crucible.yml`, com prefixo `hoshiestar_`.
5. Compilar com JDK 8, como o upstream.
6. Correção que não depende de regiões (como o D-07) vai como pull request pequeno ao Crucible, separado do nosso trabalho. Diminui a diferença que carregamos.
