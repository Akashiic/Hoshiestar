# Roteiro do admin: medir a carga por região em produção

Uma página. Os detalhes estão em `medidor.md` (mesma pasta).

## O que é

Uma parte do `server.jar` do Hoshiestar que mede **onde no mapa** o tempo de tick é gasto
(máquinas e entidades), por dimensão, região de 256 × 256 blocos e chunk. Fica parado até você
mandar medir. Não muda nada no jogo, não envia nada pela rede e não grava nome de jogador, UUID
nem IP. Não há nada para instalar em `mods/` nem em `plugins/`; o cliente não muda.

## Passo a passo

1. **Antes de tudo, backup** do mundo, como em qualquer troca de `server.jar`.
   **Se você instalou o mod antigo**, apague-o de `mods/` com o servidor parado:
   ```bash
   ls mods/ | grep -i hoshiestar-medidor     # deve sair vazio; se sair um nome, apague:
   rm mods/Hoshiestar-Medidor-*.jar
   ```
   O mod antigo e o `server.jar` novo têm classes com o mesmo nome e quebram juntos. Se você esquecer,
   o servidor avisa no boot (`[Hoshiestar] [medidor] MEDIDOR DESABILITADO: o mod antigo
   Hoshiestar-Medidor esta instalado ...`) e o `hoshie medir` recusa; mesmo assim o boot pode cair por
   causa do mod antigo. Apague o jar e ligue de novo.
2. Confira o relógio da máquina:
   ```bash
   cat /sys/devices/system/clocksource/clocksource0/current_clocksource
   ```
   `tsc` ou `kvm-clock`: pode seguir. `hpet` ou `acpi_pm`: me avise antes; o medidor
   provavelmente vai se recusar a medir.
3. Ligue o servidor com o `server.jar` do Hoshiestar. Depois do "Done" deve aparecer no console:
   `[Hoshiestar] recurso medidor: ligado | pronto; nenhuma medicao em andamento (...); ganchos do fork: tile entities=firewall, entidades=sim, jogadores pela rede=sim (...)`
   e, mais acima, a linha do próprio firewall: `Patched TileEntity tick gate at World#updateEntities.`
   Se aparecer `medidor: desligado`, o `hoshiestar.yml` está com `medidor: habilitado: false`: mude
   para `true` e reinicie. Se aparecer `tile entities=desconhecida`, me mande essas linhas.
4. No **horário de pico, com jogadores online**, digite no console primeiro um aquecimento curto
   (o resultado dele pode ser jogado fora):
   ```
   hoshie medir 10
   ```
   Quando ele terminar, a medição de verdade:
   ```
   hoshie medir 600
   ```
   São 10 minutos. Para acompanhar: `hoshie status` (linha `medidor: ...`). Para parar antes:
   `hoshie parar`. O aquecimento existe porque a primeira medição depois de um reinício calibra o
   custo do medidor com o Java ainda "frio" e superestima esse custo (no teste: 312 ns contra 82 ns
   na segunda); em pico, isso poderia fazer a medição parar sozinha logo no começo.
5. No fim sai uma linha no console com o caminho de dois arquivos, dentro da pasta do servidor:
   `hoshiestar/medicao-AAAAMMDD-HHMMSS.json` e `.txt`. **Mande os dois para o projeto.**
   Se a linha sair como aviso e mostrar um caminho em `/tmp`, a pasta do servidor não pôde ser
   gravada (a linha diz o motivo): copie os dois arquivos de lá logo, porque o `/tmp` some no reboot.
6. Se puder, repita em outro horário (um pico e um horário médio já ajudam muito).
7. Não precisa desfazer nada: parado, o medidor não faz nada além de uma checagem por
   máquina/entidade. Para proibir o comando: `medidor: habilitado: false` no `hoshiestar.yml`.
8. Se você parar o servidor (`stop`) no meio de uma medição, ela é encerrada e gravada antes do
   salvamento do mundo; o medidor espera a gravação no máximo 5 segundos e não atrasa o salvamento
   além disso.

## O que esperar enquanto mede

- O tick fica um pouco mais lento enquanto a medição está ligada. O medidor estima o próprio custo
  na linha de início e no `hoshie status`. No servidor de teste o custo de verdade foi 3 a 5 vezes a
  estimativa (0,8 ms contra 0,23 ms por tick com ~2.800 máquinas/entidades). Com o tamanho da
  produção, o custo real pode chegar a 5 a 10 ms a mais por tick (conta, não medida).
- **Ele para sozinho** se o tick ficar bem mais lento do que estava antes de ligar, ou se o custo
  estimado, vezes uma margem de segurança, passar de 10% do tick de antes de ligar (entre 5 e 10 ms).
  Nos dois casos grava o que já coletou: mande os arquivos assim mesmo.
- A margem fica no `hoshiestar.yml`: `medidor: margem_custo: 4.0` (padrão). Com 4, num pico muito
  pesado a válvula pode parar a medição logo na primeira janela (a linha diz "custo estimado do
  medidor ... com a margem de 4.0x"). Se isso acontecer e você aceitar um custo maior por alguns
  minutos, mude para `2.0` (o valor do mod antigo), reinicie e meça de novo com um tempo curto
  (`hoshie medir 300`). Com 2, o medidor pode pesar uns 10% do tick sem a válvula parar.
- Só aceita `hoshie medir`, `hoshie medir <segundos>`, `hoshie medir forcar` e
  `hoshie medir <segundos> forcar`. Qualquer outra coisa é recusada com a explicação.

## Se algo der errado

| O que aconteceu | O que fazer |
|---|---|
| "Medicao RECUSADA" por causa do relógio | Não force. Mande a linha para o projeto. |
| "Argumento desconhecido" / "Argumentos demais" | Digite de novo: `hoshie medir 600` (ou com `forcar`, se o projeto pedir). |
| Parou sozinho ("valvula de sobrecarga") | Normal em pico pesado. Mande os arquivos. Se o motivo for "custo estimado" logo no começo, veja a margem acima. |
| `MEDIDOR DESABILITADO: o mod antigo Hoshiestar-Medidor esta instalado` | Pare o servidor, apague `mods/Hoshiestar-Medidor-*.jar`, ligue de novo. |
| O `.txt` começa com aviso de "dados inválidos" ou "poucos ticks" | Não havia jogador online. Repita no pico. |
| Qualquer linha `[Hoshiestar] [medidor]` com "Erro interno" | A medição foi desligada sozinha e o servidor seguiu normal. Mande o log. |
| O servidor caiu durante a medição | Nenhum arquivo é gravado. Mande o crash report. (Parada normal com `stop` grava.) |

## O que eu faço com os arquivos

Eles dizem se poucas bases concentram a carga. Disso depende a ordem do projeto: se uma ou duas
bases respondem por mais da metade, o "orçamento por região" (isolar o lag de uma base sem
threads) já resolve muita coisa; se a carga é espalhada, o ganho só vem com as threads de região.
