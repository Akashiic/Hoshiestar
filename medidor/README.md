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

1. Copie `Hoshiestar-Medidor-1.0.4-serveronly.jar` para `mods/` e reinicie o servidor.
   No boot aparecem linhas `[Hoshiestar-Medidor]`; a última diz, por exemplo:
   `Pronto (desligado). Ganchos: tile entities=firewall, entidades=sim, jogadores pela rede=sim.`
2. Antes, confira a fonte de relógio da máquina (o medidor lê o relógio duas vezes por tick medido):
   ```
   cat /sys/devices/system/clocksource/clocksource0/current_clocksource
   ```
   `tsc` ou `kvm-clock` são rápidas. Se for `hpet` ou `acpi_pm`, o relógio é lento e o medidor
   provavelmente vai recusar (passo 3).
3. No horário de pico, **com jogadores conectados**, no console, pelo RCON ou como operador nível
   3 ou mais no chat:
   ```
   hoshie medir 600
   ```
   (segundos: padrão 300, mínimo 10, máximo 1800). Sai **uma** linha no console quando começa, com
   a calibração (custo do relógio e de um tick medido nesta máquina) e o tick do servidor antes de
   ligar. Se o relógio custar mais de 150 ns por leitura, a medição é **recusada** com o motivo; dá
   para insistir com `hoshie medir 600 forcar` (a válvula do passo 4 continua valendo). Com relógio
   lento, a recusa custa uma pausa única no tick: se as 2 primeiras rodadas de 1.000 leituras já
   passam de 450 ns por leitura (3 vezes o limite), a calibração para ali (2,6 / 4,6 / 10,7 ms com 1 /
   2 / 5 µs por leitura, cronometrado fora do servidor com um relógio lento simulado); entre 150 e
   450 ns, para sozinha em ~20 ms. A segunda parte da calibração nem roda.
4. Espere. Para antes, se quiser: `hoshie parar`. Ver andamento (inclui a válvula): `hoshie status`.
   Ele mostra, depois da primeira janela, `custo estimado X ms por tick (x2 = Y ms; limite Z ms)`, com
   o limite que vale **nesta** medição.
   **Válvula de sobrecarga**: o medidor para sozinho, e grava o que já coletou, se a mediana do tick
   do servidor, em 2 janelas seguidas de 100 ticks, passar a mediana dos 100 ticks antes de ligar
   em mais de 25% **e** em mais de 15 ms, ou se o custo estimado do próprio medidor, **vezes 2** de
   margem, passar de 10% do tick de antes de ligar, com piso de 5 ms e teto de 10 ms por tick (numa
   janela; sem referência, 5 ms). Exemplo: com o tick de 84 ms da produção, o limite fica em 8,4 ms.
   A margem existe porque a calibração mede o melhor caso e não inclui a parte do gancho
   (conferência da thread, busca na pilha, nome da classe). A regra que mede o dano de verdade é a
   do tempo de tick; a do custo é uma segunda trava. Quando a válvula dispara, sai um aviso no
   console e o `.txt` diz no topo que a medição foi parada.
   A válvula não sabe distinguir o custo do medidor de uma subida natural da carga (gente entrando,
   uma máquina ligando): nesses casos ela também para. Se isso acontecer, repita.
5. Quando termina, sai **uma** linha no console com o caminho dos arquivos e o veredito do autoteste:
   `<pasta do servidor>/hoshiestar/medicao-AAAAMMDD-HHMMSS.json` e `.txt` `(autoteste: ok)`.
   Se essa pasta não puder ser criada ou gravada (sem permissão; disco cheio, se o `/tmp` estiver em
   outro disco), os arquivos vão para a pasta reserva `<java.io.tmpdir>/hoshiestar-<usuário>/` (no
   Linux, normalmente `/tmp/hoshiestar-<usuário que roda o servidor>/`) e a linha sai como aviso, com
   o caminho que valeu e o motivo. Nesse caso, **copie os arquivos de lá logo**: o `/tmp` costuma ser
   apagado no reboot e é uma pasta compartilhada da máquina. A pasta e os arquivos da reserva ficam só
   para o dono (o usuário que roda o servidor). Se esse caminho já existe e é um link simbólico
   (atalho para outra pasta), não é uma pasta, ou é de outro usuário, o medidor **recusa** a reserva e
   não grava nada lá: sai um erro no console com o motivo e a medição precisa ser repetida depois de
   resolver a pasta principal. Mande os dois arquivos para o projeto.

Só uma medição por vez. Se o servidor desligar normalmente (`stop`) no meio, a medição é encerrada
e gravada. Se o servidor **cair** (crash no laço de tick) durante a medição, nenhum arquivo é
gravado: o Forge não avisa os mods nesse caso. Repita a medição depois.

Fora as linhas de início e de fim, o medidor só escreve no console quando há problema (autoteste
com parte não ligada, válvula, erro interno). O autoteste que deu certo fica no `hoshie status` e
nos arquivos.

Sem jogador (e sem chunk forçado), o `WorldServer` para o laço de entidades e tile entities depois
de 1 minuto: uma medição feita assim não tem dados úteis. O medidor conta em quantos ticks algum
mundo rodou o laço; se foi em menos da metade (ou em nenhum), o `.txt` avisa logo no topo e junto
da linha de concentração, e o `.json` traz `dados_validos: false`.

**Custo enquanto mede** (ligado): é **calibrado na própria máquina** no início de cada medição
(uma pausa única: a parte do relógio para sozinha em ~20 ms e a do tick vazio em ~30 ms; com relógio
rápido e o JIT já aquecido o total fica entre 15 e 25 ms; na primeira medição depois do boot, até
~40 ms. Esses tempos foram cronometrados fora do servidor, numa JVM 8 sozinha). No servidor de
teste deu ~25 a 30 ns por leitura do relógio e 80 a 90 ns por tick medido (melhor caso, cache
quente; na produção, com tabelas maiores, deve ser um pouco mais). Com 20 a 30 mil ticks de
máquina/entidade por tick do servidor, como em produção, isso daria uns 2 a 3 ms a mais por tick,
**se** a VM tiver relógio rápido; por isso a calibração recusa relógio lento e a válvula para a
medição se o tick piorar. Com a margem de 2x da válvula, esses 2 a 3 ms estimados viram 4 a 6 ms,
abaixo do limite de 8,4 ms que a produção teria (tick de 84 ms antes de ligar). Mas isso depende do
custo do tick medido que a calibração der **na máquina de produção**: se lá der 140 ns por tick
medido ou mais, uns 30 mil ticks de máquina/entidade por tick do servidor já bastam para a regra de
custo parar a medição (30.000 × 140 ns × 2 = 8,4 ms). Se a válvula parar a medição pela regra de
custo (isso apareceria já na primeira janela, de 100 ticks, uns 5 a 9 s), o `.txt` diz o motivo;
nesse caso, **mande os arquivos para o projeto mesmo assim** (eles têm a calibração e o que foi
medido até ali) e avise. O `.txt` e o `.json` trazem a calibração, a
estimativa de custo, o limite usado e o que a válvula viu. Desligado, o custo é zero na prática (ver
garantias).

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
- **artefato**: parte do custo do próprio medidor (meia leitura de relógio de cada lado e a volta
  do gancho, ~25 ns no teste) cai **dentro** de cada tick medido. Classes com muitos ticks quase
  vazios (funis parados, TEs bloqueadas pelo firewall, entidades inativas) ficam infladas em até
  ticks × esse valor. O `.txt` tem a coluna `s/artef.` na tabela de classes e o `.json` traz
  `total_ns_sem_artefato_est` em toda linha e `meta.calibracao.artefato_dentro_do_quadro_ns`.
- **Ticks indiretos**: o medidor mede o que passa pelo laço do mundo. Um acelerador que chama
  `updateEntity()` de outra TE direto (sem o laço) põe o tempo da TE acelerada na classe de quem
  chamou (a TE do acelerador, ou `EntityPlayerMP (tick pela rede)` quando é um item no inventário).
  O tempo não se perde nem conta duas vezes; só fica com outra classe.
- **Jogador**: `EntityPlayerMP (tick pela rede)` é o tick pela rede (`onUpdateEntity`), que roda
  uma vez por pacote de movimento (perto de 1 por tick do servidor, às vezes mais). A classe
  `EntityPlayerMP` sem sufixo é o tick do jogador pelo laço do mundo **e também** o
  `worldserver.updateEntity(jogador)` que o próprio caminho da rede chama, logo depois do
  `onUpdateEntity`, quando o jogador está montado ou dormindo. As duas linhas ficam separadas para a
  contagem de ticks não somar caminhos diferentes. O tempo não é contado duas vezes.
- **Tick em que a medição liga ou para pelo comando**: conta só o trecho medido (do início da
  medição ao fim do tick, ou do início do tick até a parada). Assim a parte medida nunca passa de
  100% do tick contado. Esse tick pela metade conta como um tick inteiro nas médias por tick (ms/tick
  do servidor e de cada lugar); a diferença é de ~1 tick em N (~0,05% em 300 s; 1 a 2% numa medição
  mínima de 10 s).

O `.txt` (português, para ler direto):
- totais, ms por tick, e quanto do tick inteiro a parte medida representa;
- **a resposta principal**: "As 1, 3, 5, 10 e 20 seções mais pesadas concentram X% do tempo medido";
- as 20 seções mais pesadas, com dimensão, faixa de coordenadas em blocos e as 3 classes que mais
  pesam em cada uma; os 20 chunks e as 20 classes mais pesadas; tabela por dimensão;
- o **autoteste**: se os ganchos estavam mesmo ligados (ver abaixo); avisos graves (medição sem
  dados, parte não ligada, dimensão não medida) aparecem também **no topo** do arquivo;
- o custo estimado do próprio medidor nessa medição, a calibração e o que a válvula viu;
- os limites de memória e se algum foi atingido.

O `.json` tem tudo isso com mais linhas (300 seções, 300 chunks, 150 classes, até 10 classes por
seção), cada uma com `te_ns`, `ent_ns`, `te_ticks`, `ent_ticks` e `pct_do_medido`, mais os
metadados (versão, início, fim, segundos, ticks do servidor, totais em ns; `ticks_iniciados` pode
ser 1 a menos que `ticks_servidor` quando a medição começa no meio de um tick, porque esse tick
pela metade é contado mas não foi "iniciado" com a medição ligada), `meta.calibracao`
(`nanotime_ns`, `custo_quadro_total_ns` = custo total de um tick medido vazio,
`artefato_dentro_do_quadro_ns` = a parte desse custo que cai dentro do tempo medido, `forcado`) e
`meta.valvula` (`disparou`, `motivo`, tick antes de ligar, maior mediana de janela, maior custo
estimado sem a margem, `limite_custo_estimado_ms_por_tick` usado nesta medição, `limite_custo_regra`
e `margem_custo_estimado`). `meta.autoteste` é o
autoteste refeito no fim (vale para a medição inteira); `meta.autoteste_no_tick_100` é a foto do
início; `meta.dados_validos` é `false` quando o autoteste final diz "sem dados" ou `NAO_LIGADO`.

## Garantias de segurança

1. **Desligado não pesa**: os ganchos fazem `if (Medidor.ATIVO)` e mais nada. Os ganchos `@Inject`
   não usam o `CallbackInfo`, então o Mixin passa `null` e não aloca. Fora do caminho quente, o
   evento de tick do servidor (uma vez por tick do servidor, não por máquina/entidade) lê o relógio
   duas vezes e guarda a duração em um vetor fixo de 100 posições: é a referência da válvula.
2. **Nunca quebra o tick**: todo gancho tem `try/catch`; qualquer erro **do medidor** desliga a
   medição, aparece uma vez no log e o tick segue como se o mod não existisse. Os ganchos são
   métodos separados, inseridos antes e depois da chamada original: exceção da própria máquina ou
   entidade passa sem ser tocada.
   Se a máquina ou entidade lança exceção, o gancho de fim não roda; o quadro de tempo que ficou
   aberto é descartado (o Crucible remove a entidade ou invalida a tile entity que lançou, e o
   medidor reconhece isso ao abrir o próximo quadro). Esse tick não entra na conta. Um quadro de
   tile entity do **mesmo** mundo no topo da pilha também é tratado como sobra de exceção; o de outro
   mundo (mod que roda o laço de outro mundo dentro do update de uma TE) fica como pai.
3. **Memória limitada**: até 200.000 chunks, 100.000 seções, 200.000 pares seção/classe e 8.000
   classes distintas. Passou disso, vai para um balde "outros", e o relatório avisa. Pior caso
   por volta de 54 MB fixos, só durante a medição; na última expansão de uma tabela grande, ~65 MB
   por um instante e uma pausa única de poucos ms na thread do tick. A produção (~4.500 chunks)
   fica em poucos MB. Contadores em `long` (não saturam).
4. **Sem disco na thread do tick**: no fim, os dados são entregues a uma thread separada (daemon,
   prioridade mínima) que monta e grava os arquivos em `<pasta do servidor>/hoshiestar/` (a pasta
   vem do próprio servidor, não do diretório de trabalho); se essa pasta falhar, a mesma thread tenta
   a reserva `<java.io.tmpdir>/hoshiestar-<usuário>/` (pasta e arquivos só para o dono; recusada se o
   caminho é link simbólico, não é pasta ou é de outro usuário). Os nomes são
   reservados antes
   (`createNewFile`), então duas gravações nunca escrevem no mesmo arquivo; o temporário de cada
   escrita tem nome único e nunca fica para trás.
5. **Convive com o pacote**: nenhum `@Redirect` nem `@WrapOperation`; nenhuma instrução original é
   removida ou trocada. Todo gancho tem `require = 0` e o config de mixin é `required: false`:
   se um alvo não existir, o boot segue e o medidor diz o que não está medido.
6. **Só servidor**, bytecode Java 8 (versão 52).
7. **Permissão**: no Crucible o comando vira um nó Bukkit (`hoshiestar.medidor.ComandoHoshie`,
   padrão "op") e a checagem de nível do jogo fica desligada; e um "remetente" de mod (computador
   de comando, por exemplo) que chama o comando direto nem passa pelo Bukkit. Por isso o próprio
   comando tem uma lista de permitidos: o console, o RCON e jogador de verdade (não FakePlayer)
   com op nível 3 ou mais. Todo o resto é recusado (blocos de comando, mods, plugin com `*` para
   quem não é op 3).
8. **Proteção enquanto mede**: calibração do relógio no início (recusa relógio lento, salvo
   `forcar`; a parte do relógio para em ~20 ms mais uma rodada, a do tick vazio em ~30 ms mais uma
   rodada) e válvula de sobrecarga durante a medição (ver "Como o admin usa").
9. **Comando de outra thread** (RCON): o pedido é entregue à thread principal sob uma trava, e
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
- no boot, o log diz qual gancho ficou no bytecode (`tile entities=vanilla|firewall|NENHUM`). Um par
  só conta como ligado se as **duas metades** (antes e depois da chamada) estão no bytecode; se só
  uma ficou, o log, a linha "Pronto" e o `hoshie status` avisam "gancho pela metade" e o par conta
  como não ligado (vale também para entidades e para o jogador pela rede). Sai
  também uma linha `Transformadores: ...` com a posição do firewall e do Mixin na lista do
  LaunchClassLoader. O Mixin pode ter vários `Proxy` nessa lista: cada `Proxy` novo desliga os
  anteriores, e o que trabalha é o **último** (no pacote real, posição 130, depois do firewall na
  100; por isso a chamada do firewall já está no bytecode quando o Mixin aplica). A linha diz a
  posição do Proxy ativo e quantos antigos estão desligados;
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

Visto no teste da versão 1.0.2 (pacote real, com o firewall; 1 boot, 2026-11-22): par "firewall"
ativo; a linha de transformadores agora mostra o firewall na posição 100 e o Proxy ativo do Mixin na
130; o aviso do log4j sobre o logger `Hoshiestar-Medidor` sumiu. Calibração nesta máquina: relógio
25 a 30 ns, tick medido 79 a 90 ns (25 ns dentro do tempo medido). Com um bot conectado e 104 funis
em duas seções, `hoshie medir 30` mediu exatamente 104 × 601 = 62.504 ticks de `TileEntityHopper`
(24.040 no chunk de 40 funis e 38.464 no de 64). Sem op, o Bukkit recusou; sem op mas com o nó
Bukkit, o próprio comando recusou; com op, aceitou; o RCON foi aceito. Com ~4.800 porcos invocados
durante uma medição de 300 s, a válvula parou a medição (mediana do tick 41,8 ms contra 8,6 ms
antes de ligar, 2 janelas seguidas) e gravou os arquivos com o aviso no topo; um pico anterior
(uma janela de 65 ms) não disparou, porque a regra pede 2 janelas seguidas. Não verificado:
cliente de verdade (só o bot de protocolo), relógio lento de verdade (o caminho da recusa não teve
como ser exercitado nesta máquina) e carga de produção.

Versão 1.0.3 (correções da revisão da 1.0.2): calibração limitada no tempo (e recusa rápida de
relógio claramente lento), regra de custo da válvula com margem 2x e limite de 10% do tick antes de
ligar (entre 5 e 10 ms), conferência das duas metades de cada par de ganchos no boot,
pasta reserva para os arquivos (só para o dono), nomes mais claros na calibração do `.json`, tick parcial contado na
ligada/parada por comando, versão vinda de uma fonte só. **Não testada com servidor rodando**: só
compilação, conferência estática do bytecode e testes numa JVM 8 sozinha, sem Minecraft rodando
(tempo da calibração com relógio rápido e com relógio lento simulado, limite da regra de custo,
contagem do tick parcial, pasta reserva, `.json` válido). A conferência das metades de início (o nome do método
de gancho que o Mixin deixa no bytecode para `@ModifyReceiver`/`@ModifyArg`) segue a mesma regra já
vista funcionando para os de fim, mas precisa ser confirmada no primeiro boot (a linha "Pronto" deve
dizer `tile entities=firewall` sem aviso de "gancho pela metade").

Versão 1.0.4 (correções pequenas da revisão da 1.0.3): `hoshie status` mostra o custo estimado com a
margem e o limite desta medição; a pasta reserva passou a ter o nome do usuário
(`hoshiestar-<usuário>`) e é recusada se for link simbólico, não for pasta ou for de outro usuário;
README explica `ticks_iniciados` e a dependência da regra de custo com a calibração da produção.
**Não testada com servidor rodando**: só compilação, conferência estática do bytecode e um teste da
pasta reserva numa JVM 8 sozinha (caso normal, link simbólico, arquivo no lugar, pasta de outro dono).

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
4. empacota e confere que todo `.class` tem versão 52 e que a versão é a mesma no manifesto, no
   `mcmod.info` e no `@Mod`. A única fonte da versão é `Medidor.VERSAO`: o `mcmod.info` do código
   traz `${version}` e o script põe o número (e falha se não conseguir).

Limitação da visão SRG: as classes do jogo vêm do jar vanilla, sem os patches binários do Forge e
do Crucible. Para o que o medidor usa (campos de posição, dimensão, comando) isso basta; os alvos
dos mixins são conferidos no boot de verdade.

## Arquivos

- `src/main/java/hoshiestar/medidor/` – mod, comando, coleta, relatório.
- `src/main/java/hoshiestar/medidor/core/` – coremod (entrega os mixins ao GTNHMixins) e plugin
  do config de mixin (só observa o bytecode para o diagnóstico).
- `src/main/java/hoshiestar/medidor/mixins/early/` – os ganchos.
- `src/main/resources/` – `mcmod.info` e `mixins.hoshiestar_medidor.early.json`.
