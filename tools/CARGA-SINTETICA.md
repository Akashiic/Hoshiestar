# Carga sintética para o servidor de teste

**O que é e para que serve.** O mundo de produção não pode ser copiado e no teste não há jogadores.
Para exercitar o medidor (`hoshie medir`) e, mais tarde, o orçamento por região e as threads de
região, este roteiro monta no servidor de **teste** (cópia criada por `scripts/prepare-test-env.sh`,
presa em `127.0.0.1`) "bases" com máquinas de vários mods que ticam de verdade, mantém os chunks
carregados com bots parados e, se quiser, põe bots andando para o servidor carregar chunks novos.

**Aviso que vale para todos os números deste arquivo:** é **carga sintética, uma indicação**, medida
numa máquina de 4 núcleos com 8 GB de heap, mundo plano, sem jogadores reais. **Não é medição de
produção** e não deve ser comparada em valor absoluto com o perfil de produção.

Tudo roda só em cópia de teste. Nada daqui vai para `mods/` nem para `plugins/`; o servidor é o
`server.jar` do Hoshiestar, e os bots são scripts Python que falam o protocolo 1.7.10 em `127.0.0.1`.

## Peças

| Arquivo | Para quê |
|---|---|
| `tools/bot_anda.py` | Bots que andam (linha, circuito, anel) ou ficam parados; vários nomes num processo; só `127.0.0.1`; descobre a lista de mods pelo "ping" do servidor |
| `tools/carga_sintetica.py` | Gera os comandos `setblock` das bases (layouts a, b, c), aplica no console da cópia, confere o resultado contra o arquivo do medidor |
| `scripts/aplicar-carga-sintetica.sh <cópia> <layout>` | Liga a cópia (mundo plano), aplica a carga, opcionalmente mede |
| `scripts/parar-carga-sintetica.sh <cópia>` | Para bots e servidor com `stop` (nunca `kill`) |
| `tools/test_bot.py` | O bot parado antigo (comandos de chat, FML à mão); continua valendo |

## Como montar

Pré-requisito: uma cópia de teste com o `server.jar` do Hoshiestar (a cópia vem de
`scripts/prepare-test-env.sh <origem> <destino>`; troque o `server.jar` pelo jar entregue). Memória
livre para 8 GB de heap e **nenhum outro servidor de teste ligado** (um por vez).

```bash
# 1) liga a cópia com mundo plano, sem monstros/animais (a medida fica estável), e monta 4 bases distantes
scripts/aplicar-carga-sintetica.sh <cópia> a --mundo-novo --bases 4 --kits 2
#    layouts: a = bases distantes (>64 chunks), b = bases vizinhas (<48 chunks), c = uma base com cano e cabo
#    cruzando a borda de uma seção de 16x16 chunks
# 2) medir 60 s com o medidor do jar e conferir se as bases aparecem nas seções certas
scripts/aplicar-carga-sintetica.sh <cópia> a --medir 60     # (cópia já montada: refaz só a conferência)
python3 tools/carga_sintetica.py conferir --plano <cópia>/carga-plano.json --medicao <cópia>/hoshiestar/medicao-*.json
# 3) parar
scripts/parar-carga-sintetica.sh <cópia>
```

Só gerar os comandos, sem ligar nada: `python3 tools/carga_sintetica.py gerar --layout c --kits 2 --saida plano.json`
(o JSON tem, por base, os comandos, o centro, os chunks e as seções).

Bots andando (prova de carregamento de chunks), com o servidor ligado:

```bash
python3 tools/bot_anda.py --prefixo Andar --n 3 --rota linha --tamanho 600 --velocidade 30 --tempo 110 \
        --seguir-tp --log bots.log
# no console da cópia: tp Andar1 -3000 5 -3000 ; tp Andar2 3000 5 -3000 ; tp Andar3 0 5 3000
# acompanhe: crucible chunks   (linha "Dimension: 0 Loaded Chunks: ...")
```

## O que cada base tem

Tamanho = `--kits K` (padrão 2). Um kit tem:

* 3 colunas vanilla: baú com minério de ferro, funil, fornalha acesa (carvão no combustível), funil, baú;
* uma linha de 8 máquinas de cada mod (lista em `tools/carga_sintetica.py`, variáveis `MEK`, `IC2`, `TE`, `EIO`,
  `AE2`, `BC`, `FOR`). Nomes e metas foram achados no registro do mundo (`level.dat`) e conferidos com um
  `setblock` de cada ("Block placed"). O medidor confirmou tick a cada tick, por classe, nos 7 mods (classes
  vistas com tick nas bases): Mekanism (EnergyCube, HeatGenerator, EnrichmentChamber, OsmiumCompressor,
  Combiner, Crusher, Factory...), IC2 (Generator, Macerator, IronFurnace, Compressor, GeoGenerator,
  ElectricBatBox...), Thermal Expansion (Furnace, Pulverizer, Sawmill, Smelter, Cell, DynamoSteam...),
  EnderIO (AlloySmelter, CapBank, Crusher, Vat, FarmStation, StirlingGenerator...), AE2 (Controller,
  Charger, Inscriber), BuildCraft (EngineWood/Stone/Iron, Refinery, Pump, Tank, AutoWorkbench, canos) e
  Forestry (EnginePeat/Biogas/Electric, Carpenter, Centrifuge, Bottler, Apiary...);
* uma **linha de cano BuildCraft** de 14 blocos: baú, cano de madeira (puxa), motor de madeira com
  bloco de redstone, 12 canos de pedra, baú (o `pipeId` do cano é lido do `level.dat` da cópia);
* uma **linha de cabo IC2** de 14 blocos: gerador, 12 cabos, trituradora e fornalha.

No layout c o primeiro kit começa em x = 249 e há um cano extra ao longo de z em x = 245 (z de 250 a
263): canos e cabos **cruzam as bordas x = 255|256 e z = 255|256**, as bordas de seção do medidor
(seção = 256 x 256 blocos = 16 x 16 chunks).

Bots: um por base, parado ao lado dela (nomes `CargaA` a `CargaH`), mantém os chunks em volta carregados
(raio de visão 8 = 289 chunks por bot). O bot de uma base é um processo próprio; se algum não entrar
(o servidor às vezes prende um login), o script tenta de novo até 3 vezes.

## Limites conhecidos

* Sem `fill`/`clone` no 1.7.10: cada bloco é um `setblock` (um kit tem ~100 comandos).
* `testforblock` não escreve resposta no console deste servidor; a conferência é por contagem de
  tile entities (`crucible chunks`) e pelo medidor.
* Os cabos e canos são colocados, mas **não verificado** se a energia/itens realmente fluem por eles; o
  que foi verificado é que as máquinas ticam (medidor) e que o cano/cabo existe e cruza a borda (comandos).
* Os dois cabos de mod que só existem como multipartes (Mekanism PartTransmitter) e os conduítes do
  EnderIO (precisam de NBT de conduíte) ficam de fora.
* O **primeiro boot** de uma cópia nova pode regravar o `server.properties` e perder o mundo plano: o script
  avisa ("o mundo NAO ficou plano"); pare e rode de novo com `--mundo-novo`.
* O login do primeiro jogador trava o tick por ~4 s (o mod Salis Arcana consulta a internet ao entrar);
  isso é do pacote, não da carga, e aparece como "TICK LENTO" do vigia no começo do teste.
* Servidor de teste: um por vez; a carga com 4 bases e 4 bots usa ~1.300 chunks carregados.

## Números obtidos (INDICAÇÃO de carga sintética, não medição de produção)

Jar `Hoshiestar-1.7.10-1.0.0-dev-server.jar` (sha256 `502eac49...d501b3`), mundo plano, 8 GB de heap, 4 núcleos,
`--bases 4 --kits 2` (824 blocos com tile entity nas 4 bases; 222 no layout c). "ms/tick" é o `ms_por_tick_servidor`
do `hoshie medir` (40 s no vazio e só com bots, 60 s com as bases); o `forge tps` é a média dos últimos 100 ticks e
oscila bastante de uma leitura para outra. O TPS ficou em 20 em todos os casos (o servidor não saturou).

| Layout | Vazio (sem bots), forge tps | Vazio, medidor | Só bots (chunks mantidos), medidor | Com as bases, medidor (te / entidades) | forge tps com as bases | Chunks da dim 0 |
|---|---|---|---|---|---|---|
| a (4 bases, 80 chunks entre vizinhas) | 3,5 a 3,7 ms | 3,73 ms | 8,37 ms | 8,76 ms (te 0,86; ent 1,50) | 8,4 a 11,1 ms | 1.188 a 1.404 |
| b (4 bases, 30 chunks entre vizinhas) | 3,9 a 4,4 ms | 6,10 ms | 10,31 ms | 9,49 ms (te 0,93; ent 1,62) | 7,8 a 13,7 ms | 1.414 |
| c (1 base cruzando bordas) | 3,5 a 4,0 ms | 5,60 ms | 6,83 ms | 8,55 ms (te 0,59; ent 1,31) | 7,2 a 10,3 ms | 546 a 621 |

Leitura honesta: (1) a maior parte do custo extra vem de **manter os chunks e os bots** (de ~4 ms para 7 a 10 ms),
não das máquinas; as 824 máquinas paradas (sem receita, sem energia) custam ~0,9 ms por tick (~1 microssegundo
por tile entity por tick). Uma base de produção, com receitas e redes de energia ativas, deve pesar bem mais:
use `--kits` maior para escalar, mas **não** extrapole destes números. (2) O ruído é grande: o mesmo servidor
vazio deu 3,7, 6,1 e 5,6 ms no medidor em três boots; só diferenças grandes significam alguma coisa.
(3) Fora das bases ainda há ~150 tile entities ticando (estruturas que os geradores de mundo dos mods
espalham no mundo plano, perto do spawn e dos bots).

**O medidor achou as bases nos lugares certos** (`carga_sintetica.py conferir`): em a e b, cada base caiu em uma
seção própria com ~207.000 `te_ticks` por 60 s (a: seções (0,0), (5,0), (0,5), (5,5); b: (0,0), (2,0), (0,2),
(2,2)); em c a base aparece nas **4 seções** (0,0), (0,1), (1,0), (1,1), com 88.652, 37.138, 77.870 e 51.514
`te_ticks`: o cano, o cabo e as máquinas cruzam a borda, como planejado.

**Bots que andam (mundo plano, 3 bots em linha de 600 blocos a 30 blocos/s, teleportados para longe um do outro):**
chunks carregados na dim 0: 257 (sem bots) -> 388 (3 bots no spawn) -> 413, 466, 573, 653, 916 e **1.115**
(pico, andando) -> 588 (depois que saíram). Cada bot recebeu 8.800 a 10.700 pacotes de chunk do servidor
(contados no próprio bot). Duas ressalvas: o servidor devolve o bot de vez em quando (a posição é corrigida
e a rota continua), e um dos três bots do primeiro teste ficou preso no login (problema do servidor ao
logar vários de uma vez, não da rota); por isso foi acrescentado o aviso "NAO ENTROU" no bot e a repetição
por até 3 vezes em `carga_sintetica.py`. **Essa repetição não foi exercitada de novo depois de escrita
(nos três layouts finais todos os bots entraram de primeira): não verificado.**

## Resultados guardados

Fora do repositório (pasta da sessão `etapa2/carga/`): `resultado-{a,b,c}.txt` (saída completa dos três
layouts), `plano-*.json`, `medicao-*-bases.json`. O roteiro de cada layout é `rodar-layout.sh`
(mundo novo, vazio, só bots, bases; mesmas chamadas descritas acima).

## O que exige jogador de verdade (não verificável aqui)

Carga real de rede e de interface, jogadores abrindo máquinas, `/home` e teleportes por plugin, o efeito de
lag percebido: **não verificável aqui**. Bots só mandam posição.
