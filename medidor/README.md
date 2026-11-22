# Hoshiestar-Medidor

Mod pequeno, **só do servidor** e **só de leitura**, que mede **onde, no mapa, vai o tempo de tick**
de tile entities (máquinas, canos, baús com lógica) e de entidades (mobs, itens no chão, jogadores).
Ele existe para responder uma pergunta da Etapa 0 (tarefa 0.9): *poucas bases concentram a carga?*
O mundo de produção (150 GB) não pode ser copiado, então a medida tem que ser feita lá, pelo admin.

- Desligado por padrão. Desligado, o custo por tick de máquina/entidade é **uma leitura de uma
  variável booleana**: sem alocação, sem busca em mapa, sem relógio.
- Ligado por comando, por tempo limitado (para sozinho).
- Grava dois arquivos locais. **Não envia nada pela rede.** Não grava nome de jogador, UUID nem IP.
- Não muda nenhum comportamento do jogo.
- O cliente **não** precisa do jar (`acceptableRemoteVersions = "*"`).

Licença: GPLv3 ou posterior (o mesmo do projeto).

## Como o admin usa

1. Copie `Hoshiestar-Medidor-1.0.1-serveronly.jar` para `mods/` e reinicie o servidor.
   No boot aparecem linhas `[Hoshiestar-Medidor]`; a última diz, por exemplo:
   `Pronto (desligado). Ganchos: tile entities=firewall, entidades=sim, jogadores pela rede=sim.`
2. No horário de pico, **com jogadores conectados**, no console (ou como operador nível 3 ou mais,
   no chat):
   ```
   hoshie medir 600
   ```
   (segundos: padrão 300, mínimo 10, máximo 1800). Sai uma linha no console quando começa.
3. Espere. Para antes, se quiser: `hoshie parar`. Ver andamento: `hoshie status`.
4. Quando termina, sai uma linha no console com o caminho dos arquivos:
   `<pasta do servidor>/hoshiestar/medicao-AAAAMMDD-HHMMSS.json` e `.txt`.
   Mande os dois para o projeto.

Só uma medição por vez. Se o servidor desligar no meio, a medição é encerrada e gravada.

Sem jogador (e sem chunk forçado), o `WorldServer` para o laço de entidades e tile entities depois
de 1 minuto: uma medição feita assim não tem dados úteis. O medidor conta em quantos ticks algum
mundo rodou o laço; se foi em menos da metade (ou em nenhum), o `.txt` avisa logo no topo e junto
da linha de concentração, e o `.json` traz `dados_validos: false`.

**Custo enquanto mede** (ligado): cerca de 0,1 microssegundo por tick medido (microbenchmark do
código do medidor em Java 21). Com 20 a 30 mil ticks de máquina/entidade por tick do servidor,
como em produção, são uns 2 a 3 ms a mais por tick durante a medição. O `.txt` mostra essa
estimativa para a medição feita. Desligado, o custo é zero na prática (ver garantias).

## O que os arquivos dizem

Termos:
- **seção** = quadrado de 16 × 16 chunks (256 × 256 blocos). `secao_x = chunk_x >> 4`,
  `chunk_x = bloco_x >> 4`. É o tamanho de "região" que o projeto usa para pensar em threads.
- **tempo** = tempo de relógio (`System.nanoTime`) **próprio** de cada tick: se um tick acontece
  dentro de outro (passageiro dentro da montaria), o de dentro é descontado do de fora; nada é
  contado duas vezes.
- **ms/tick** = quanto aquele lugar custa, em média, por tick do servidor. O tick inteiro deveria
  caber em 50 ms (20 TPS).
- **%medido** = fatia do total medido (tile entities + entidades).
- **ticks** = chamadas do laço que passaram pelo gancho. Inclui ticks que o
  `Akashic-World-Firewall` bloqueou pela política dele e ticks "inativos" do Spigot (entidade longe
  de jogador); esses custam quase nada. O **tempo** é que diz onde está o peso.
- **Jogador**: a classe `EntityPlayerMP` é o tick do jogador pelo laço do mundo;
  `EntityPlayerMP (tick pela rede)` é o tick pela rede (`onUpdateEntity`), que roda uma vez por
  pacote de movimento (perto de 1 por tick do servidor, às vezes mais). As duas linhas ficam
  separadas para a contagem de ticks não somar caminhos diferentes. O tempo não é contado duas vezes.

O `.txt` (português, para ler direto):
- totais, ms por tick, e quanto do tick inteiro a parte medida representa;
- **a resposta principal**: "As 1, 3, 5, 10 e 20 seções mais pesadas concentram X% do tempo medido";
- as 20 seções mais pesadas, com dimensão, faixa de coordenadas em blocos e as 3 classes que mais
  pesam em cada uma; os 20 chunks e as 20 classes mais pesadas; tabela por dimensão;
- o **autoteste**: se os ganchos estavam mesmo ligados (ver abaixo); avisos graves (medição sem
  dados, parte não ligada, dimensão não medida) aparecem também **no topo** do arquivo;
- o custo estimado do próprio medidor nessa medição;
- os limites de memória e se algum foi atingido.

O `.json` tem tudo isso com mais linhas (300 seções, 300 chunks, 150 classes, até 10 classes por
seção), cada uma com `te_ns`, `ent_ns`, `te_ticks`, `ent_ticks` e `pct_do_medido`, mais os
metadados (versão, início, fim, segundos, ticks do servidor, totais em ns). `meta.autoteste` é o
autoteste refeito no fim (vale para a medição inteira); `meta.autoteste_no_tick_100` é a foto do
início; `meta.dados_validos` é `false` quando o autoteste final diz "sem dados" ou `NAO_LIGADO`.

## Garantias de segurança

1. **Desligado não pesa**: os ganchos fazem `if (Medidor.ATIVO)` e mais nada. Os ganchos `@Inject`
   não usam o `CallbackInfo`, então o Mixin passa `null` e não aloca.
2. **Nunca quebra o tick**: todo gancho tem `try/catch`; qualquer erro **do medidor** desliga a
   medição, aparece uma vez no log e o tick segue como se o mod não existisse. Os ganchos são
   métodos separados, inseridos antes e depois da chamada original: exceção da própria máquina ou
   entidade passa sem ser tocada.
   Se a máquina ou entidade lança exceção, o gancho de fim não roda; o quadro de tempo que ficou
   aberto é descartado (o Crucible remove a entidade ou invalida a tile entity que lançou, e o
   medidor reconhece isso ao abrir o próximo quadro). Esse tick não entra na conta.
3. **Memória limitada**: até 200.000 chunks, 100.000 seções, 200.000 pares seção/classe e 8.000
   classes distintas. Passou disso, vai para um balde "outros", e o relatório avisa. Pior caso
   por volta de 50 MB, só durante a medição. Contadores em `long` (não saturam).
4. **Sem disco na thread do tick**: no fim, os dados são entregues a uma thread separada (daemon,
   prioridade mínima) que monta e grava os arquivos em `<pasta do servidor>/hoshiestar/` (a pasta
   vem do próprio servidor, não do diretório de trabalho). Os nomes são reservados antes
   (`createNewFile`), então duas gravações nunca escrevem no mesmo arquivo; o temporário de cada
   escrita tem nome único e nunca fica para trás.
5. **Convive com o pacote**: nenhum `@Redirect` nem `@WrapOperation`; nenhuma instrução original é
   removida ou trocada. Todo gancho tem `require = 0` e o config de mixin é `required: false`:
   se um alvo não existir, o boot segue e o medidor diz o que não está medido.
6. **Só servidor**, bytecode Java 8 (versão 52).
7. **Permissão**: no Crucible o comando vira um nó Bukkit (`hoshiestar.medidor.ComandoHoshie`,
   padrão "op") e a checagem de nível do jogo fica desligada. Por isso o próprio comando confere
   o nível 3 para jogadores e blocos de comando: um plugin de permissões com `*` não basta.
8. **Comando de outra thread** (RCON): o pedido é entregue à thread principal sob uma trava, e
   `hoshie parar` vale só para a medição que estava em andamento quando foi dado.

### Akashic-World-Firewall e o autoteste

O `Akashic-World-Firewall` (transformador ASM, não mixin) troca, dentro de `World.updateEntities`
(`func_72939_s`), a chamada `tileentity.updateEntity()` (`func_145845_h`) por
`WorldMutationGuard.runTileEntityUpdate(TileEntity, World)`. Dependendo da ordem dos
transformadores, o Mixin vê uma ou outra chamada. Por isso o medidor tem **dois pares de ganchos**,
os dois opcionais:

| Par | Antes da chamada | Depois da chamada |
|---|---|---|
| vanilla | `@ModifyReceiver` (MixinExtras) em `TileEntity.func_145845_h()` devolve o mesmo objeto | `@Inject` com `shift = AFTER` |
| firewall | `@ModifyArg` (índice 0) em `WorldMutationGuard.runTileEntityUpdate` devolve o mesmo objeto | `@Inject` com `shift = AFTER` |

Só um dos dois encontra a chamada; não há contagem dupla. Como nenhum dos dois remove a chamada
original, se o Mixin rodar antes do firewall, o firewall ainda acha o que procura e se aplica
normalmente.

Entidades: `@Inject` no início e no fim de `World.func_72866_a(Entity, boolean)`
(`updateEntityWithOptionalForce`; o `WorldServer` chama o `super`). Chamadas com `forcar = false`
são só contabilidade de chunk e não contam. Jogadores: o tick pesado deles roda pela rede, em
`EntityPlayerMP.func_71127_g` (`onUpdateEntity`), com um par de ganchos próprio, atribuído à
posição do jogador.

Três verificações dizem se o gancho está vivo, em vez de mostrar zero como se fosse dado:
- no boot, o log diz qual gancho ficou no bytecode (`tile entities=vanilla|firewall|NENHUM`);
- `hoshie status` repete isso a qualquer momento;
- **autoteste**: depois de 100 ticks de medição (aviso cedo no log) e **de novo no fim** (é o que
  vale para os arquivos), se algum mundo rodou o laço de entidades com tile entities (ou
  entidades) na lista e nenhum tick foi medido, o log, o status e os arquivos dizem `NAO_LIGADO`.
  Se nenhum mundo rodou o laço (servidor sem jogador fica ocioso: o `WorldServer` pula o laço
  depois de 1200 ticks sem ninguém), diz "não deu para testar". Também confere **dimensão por
  dimensão**: dimensão com jogador ou chunk forçado cujo laço nunca passou pelo gancho (mundo de
  mod com laço próprio), ou cujo laço rodou com tile entities/entidades na lista e nada foi
  medido, aparece como aviso.
- o tick de jogador pela rede aparece como "gancho aplicado, nenhum jogador visto" quando nenhum
  jogador foi medido, em vez de "medido".

Visto no teste com o pacote real (2026-11-21, versão 1.0.0): com o firewall instalado, quando o
Mixin roda a chamada do firewall já está no bytecode, e o par "firewall" é o que fica ativo; sem o
firewall, fica o par "vanilla". Os dois casos mediram 40 funis colocados por comando no chunk certo,
com exatamente 40 ticks por tick do servidor.

Visto no teste da versão 1.0.1 (pacote real, com o firewall; 2 boots): par "firewall" ativo; com um
bot conectado (cliente mínimo com o handshake do FML, só no servidor de teste), 3.072 funis e 300
porcos, o laço rodou em 100% dos ticks; o tick do jogador pela rede foi medido (≈1 por tick, na
seção do jogador) e saiu separado do tick pelo laço; jogador sem op recebeu a recusa do Bukkit;
jogador **sem op** com o nó Bukkit dado pelo LuckPerms recebeu a recusa do próprio comando; op
nível 4 usou o comando pelo chat. Sobrecarga ligada (`forge tps`, médias de 100 ticks, 5 amostras
por fase, mesma carga, ≈3.900 ticks medidos por tick do servidor): desligado 17,3 / 13,4 / 11,8 ms,
ligado 15,5 / 12,6 ms. O servidor foi ficando mais rápido ao longo do teste (aquecimento do JIT), e
a diferença ficou dentro do ruído; a estimativa do medidor para essa carga é ≈0,4 ms por tick.
Servidor ocioso (sem jogador): o laço rodou em 1,3% dos ticks e o `.txt` avisou no topo.

## Como compilar

Rota "SRG direto": sem Gradle e sem rede.

```
medidor/build.sh <pasta-do-servidor> <pasta-de-saida>
```

A pasta do servidor precisa ter `server.jar`, `libraries/` (as bibliotecas que o Crucible instala
no primeiro boot) e `mods/+unimixins-all-1.7.10-*.jar`. Precisa de JDK 8 (variável `JAVA8_HOME`,
padrão `/usr/lib/jvm/java-8-openjdk-amd64`), `xz` e `unzip`. O script:

1. tira do `server.jar` o mapeamento notch→SRG que o próprio FML usa no boot
   (`deobfuscation_data-1.7.10.lzma`);
2. junta as classes do Minecraft (jar vanilla em `libraries/`) com o `server.jar` e renomeia tudo
   para nomes SRG com o SpecialSource que já está em `libraries/`;
3. compila com `javac` do JDK 8 contra essa visão. O código escreve todo alvo de mixin e todo membro
   do Minecraft com nome SRG e `remap = false`, então não há refmap nem reobfuscação;
4. empacota e confere que todo `.class` tem versão 52.

Limitação da visão SRG: as classes do jogo vêm do jar vanilla, sem os patches binários do Forge e
do Crucible. Para o que o medidor usa (campos de posição, dimensão, comando) isso basta; os alvos
dos mixins são conferidos no boot de verdade.

## Arquivos

- `src/main/java/hoshiestar/medidor/` – mod, comando, coleta, relatório.
- `src/main/java/hoshiestar/medidor/core/` – coremod (entrega os mixins ao GTNHMixins) e plugin
  do config de mixin (só observa o bytecode para o diagnóstico).
- `src/main/java/hoshiestar/medidor/mixins/early/` – os ganchos.
- `src/main/resources/` – `mcmod.info` e `mixins.hoshiestar_medidor.early.json`.
