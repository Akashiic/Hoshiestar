# Roteiro do admin: medir a carga por região em produção

Uma página. Os detalhes estão no `README.md` desta pasta.

## O que é

Um mod só do servidor que mede **onde no mapa** o tempo de tick é gasto (máquinas e
entidades), por dimensão, região de 256 × 256 blocos e chunk. Fica desligado até você
mandar medir. Não muda nada no jogo, não envia nada pela rede e não grava nome de
jogador, UUID nem IP. O cliente não precisa do jar.

## Passo a passo

1. **Antes de tudo, backup** do mundo, como em qualquer troca de mod.
2. Confira o relógio da máquina:
   ```bash
   cat /sys/devices/system/clocksource/clocksource0/current_clocksource
   ```
   `tsc` ou `kvm-clock`: pode seguir. `hpet` ou `acpi_pm`: me avise antes; o medidor
   provavelmente vai se recusar a medir.
3. Copie `Hoshiestar-Medidor-1.0.4-serveronly.jar` para `mods/` e reinicie o servidor.
   No console deve aparecer:
   `[Hoshiestar-Medidor] Pronto (desligado). Ganchos: tile entities=firewall, entidades=sim, jogadores pela rede=sim.`
   Se aparecer `NENHUM` ou "gancho pela metade", não meça: me mande essas linhas.
4. No **horário de pico, com jogadores online**, digite no console primeiro um
   aquecimento curto (o resultado dele pode ser jogado fora):
   ```
   hoshie medir 10
   ```
   Quando ele terminar, a medição de verdade:
   ```
   hoshie medir 600
   ```
   São 10 minutos. Para acompanhar: `hoshie status`. Para parar antes: `hoshie parar`.
   O aquecimento existe porque a primeira medição depois de um reinício calibra o
   custo do medidor com o Java ainda "frio" e superestima esse custo em 2 a 5 vezes;
   em pico, isso poderia fazer a medição parar sozinha logo no começo.
5. No fim sai uma linha com o caminho de dois arquivos, dentro da pasta do servidor:
   `hoshiestar/medicao-AAAAMMDD-HHMMSS.json` e `.txt`. **Mande os dois para o projeto.**
   Se a linha sair como aviso e mostrar um caminho em `/tmp`, a pasta do servidor não
   pôde ser gravada: copie os dois arquivos de lá logo, porque o `/tmp` some no reboot.
6. Se puder, repita em outro horário (um pico e um horário médio já ajudam muito).
7. Depois pode tirar o jar de `mods/` no próximo reinício. Ele não deixa nada no mundo.

## O que esperar enquanto mede

- O tick fica um pouco mais lento enquanto a medição está ligada. A estimativa é de
  2 a 3 ms por tick com a carga do seu perfil; o número real da sua máquina sai na
  linha de início e no arquivo.
- **Ele para sozinho** se o servidor ficar bem mais lento do que estava antes de ligar,
  ou se o custo estimado do próprio medidor passar do limite. Nos dois casos ele grava
  o que já coletou: mande os arquivos assim mesmo. Em horário de pouca carga o limite
  de custo é mais apertado (5 ms por tick), então parar cedo ali é mais provável.
- Desligado, o custo é uma checagem de uma variável por máquina/entidade: na prática, zero.

## Se algo der errado

| O que aconteceu | O que fazer |
|---|---|
| "Medição recusada" por causa do relógio | Não force. Mande a linha para o projeto. |
| Quis usar `forcar` e digitou errado | Qualquer outra palavra depois do número é ignorada sem aviso (versão 1.0.4). |
| Parou sozinho ("válvula de sobrecarga") | Normal em pico pesado. Mande os arquivos. |
| O `.txt` começa com aviso de "dados inválidos" | Não havia jogador online. Repita no pico. |
| Qualquer erro com `Hoshiestar-Medidor` no log | Tire o jar no próximo reinício e mande o log. |
| O servidor caiu durante a medição | Nenhum arquivo é gravado. Mande o crash report. |

## O que eu faço com os arquivos

Eles dizem se poucas bases concentram a carga. Disso depende a ordem do projeto: se
uma ou duas bases respondem por mais da metade, o "orçamento por região" (isolar o lag
de uma base sem threads) já resolve muita coisa; se a carga é espalhada, o ganho só
vem com as threads de região.
