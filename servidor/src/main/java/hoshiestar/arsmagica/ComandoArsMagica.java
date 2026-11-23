/*
 * Hoshiestar: servidor hibrido Forge + Bukkit para Minecraft 1.7.10, fork do Crucible.
 * Copyright (C) 2026 Projeto Hoshiestar
 *
 * Este programa e software livre: voce pode redistribui-lo e/ou modifica-lo sob os termos da
 * GNU General Public License publicada pela Free Software Foundation, versao 3 da Licenca ou
 * (a seu criterio) qualquer versao posterior. Distribuido SEM QUALQUER GARANTIA.
 * Veja <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package hoshiestar.arsmagica;

import hoshiestar.comando.ComandoHoshie;
import org.bukkit.command.CommandSender;

/**
 * Subcomando {@code hoshie arsmagica}: mostra o estado dos atalhos do Ars Magica 2 e troca os
 * interruptores EM EXECUCAO (so na memoria; o hoshiestar.yml nao muda e vale de novo no proximo boot).
 * Serve para comparar antes e depois no mesmo boot e para desligar um atalho sem reiniciar.
 *
 * <p>Uso: {@code hoshie arsmagica} (estado) | {@code hoshie arsmagica varredura|diluicao ligar|desligar}.
 * Ligar so tem efeito se a correcao foi aplicada na classe do AM2 no boot (interruptor ligado no
 * hoshiestar.yml, trava conferida): senao o comando recusa e explica. Desligar sempre funciona e devolve
 * o comportamento original na hora (os ganchos passam a chamar so a chamada original).
 */
final class ComandoArsMagica implements ComandoHoshie.Subcomando {

    @Override
    public String nome() {
        return "arsmagica";
    }

    @Override
    public String ajuda() {
        return "arsmagica [varredura|diluicao ligar|desligar] - estado dos atalhos do Ars Magica 2; troca o interruptor so em execucao";
    }

    @Override
    public void executar(CommandSender quem, String[] args) {
        if (args.length == 0) {
            quem.sendMessage(ArsMagica.linhaDeEstado());
            return;
        }
        if (args.length != 2 || !(args[0].equalsIgnoreCase("varredura") || args[0].equalsIgnoreCase("diluicao"))
                || !(args[1].equalsIgnoreCase("ligar") || args[1].equalsIgnoreCase("desligar"))) {
            quem.sendMessage("uso: hoshie " + ajuda());
            return;
        }
        boolean f1 = args[0].equalsIgnoreCase("varredura");
        boolean ligar = args[1].equalsIgnoreCase("ligar");
        if (ligar && Atalhos.ganchoComDefeito != null) {
            quem.sendMessage("nao ligo: a auto-checagem dos ganchos reprovou neste boot (" + Atalhos.ganchoComDefeito + "). Veja 'hoshie status'.");
            return;
        }
        if (ligar && !ArsMagica.aplicada(f1 ? ArsMagica.correcaoVarredura : ArsMagica.correcaoDiluicao)) {
            quem.sendMessage("nao ligo: a correcao " + (f1 ? "da varredura" : "do DiluteTime")
                    + " nao foi aplicada na classe do AM2 neste boot (interruptor desligado no hoshiestar.yml, trava de versao, AM2 ausente"
                    + " ou classe ainda nao carregada); ligar agora nao teria efeito. Veja 'hoshie status'.");
            return;
        }
        synchronized (Atalhos.class) { // a mesma trava de Atalhos.erro: o status nao mostra "ligada" e "desligada por erro" ao mesmo tempo
            if (f1) {
                Atalhos.varredura = ligar;
                if (ligar) {
                    Atalhos.varreduraDesligadaPorErro = null;
                }
            } else {
                Atalhos.diluicao = ligar;
                if (ligar) {
                    Atalhos.diluicaoDesligadaPorErro = null;
                }
            }
        }
        quem.sendMessage("atalho " + args[0].toLowerCase(java.util.Locale.ROOT) + " " + (ligar ? "ligado" : "desligado") + " em execucao (so ate reiniciar). "
                + ArsMagica.linhaDeEstado());
    }
}
