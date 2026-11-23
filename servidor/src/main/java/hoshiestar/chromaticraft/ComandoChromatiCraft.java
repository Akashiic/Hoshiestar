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
package hoshiestar.chromaticraft;

import hoshiestar.comando.ComandoHoshie;
import org.bukkit.command.CommandSender;

/**
 * Subcomando {@code hoshie chromaticraft}: mostra o estado dos limites do "mover area" e liga/desliga os
 * limites EM EXECUCAO (so na memoria; o hoshiestar.yml nao muda e vale de novo no proximo boot).
 *
 * <p>Uso: {@code hoshie chromaticraft} (estado) | {@code hoshie chromaticraft ligar|desligar}. Desligar
 * devolve o comportamento original na hora (o gancho passa a responder "nao recusar" na primeira linha).
 * Ligar so tem efeito se a correcao esta aplicada na classe do mod ou vai se aplicar quando a classe carregar
 * (interruptor ligado no hoshiestar.yml, trava conferida): se a trava de versao ou o padrao recusou a classe, ou o recurso
 * esta desligado no yml, o comando recusa e explica.
 */
final class ComandoChromatiCraft implements ComandoHoshie.Subcomando {

    @Override
    public String nome() {
        return ChromatiCraft.ID;
    }

    @Override
    public String ajuda() {
        return "chromaticraft [ligar|desligar] - estado dos limites do mover area do ChromatiCraft; liga/desliga so em execucao";
    }

    @Override
    public void executar(CommandSender quem, String[] args) {
        if (args.length == 0) {
            quem.sendMessage(ChromatiCraft.linhaDeEstado());
            return;
        }
        if (args.length != 1 || !(args[0].equalsIgnoreCase("ligar") || args[0].equalsIgnoreCase("desligar"))) {
            quem.sendMessage("uso: hoshie " + ajuda());
            return;
        }
        boolean ligar = args[0].equalsIgnoreCase("ligar");
        if (ligar) {
            if (ChromatiCraft.decisao == null) {
                quem.sendMessage("nao ligo: o recurso esta desligado no hoshiestar.yml (chromaticraft.ligado); a classe do mod nao foi alterada no boot.");
                return;
            }
            if (ChromatiCraft.ganchoComDefeito != null) {
                quem.sendMessage("nao ligo: a auto-checagem dos ganchos reprovou neste boot (" + ChromatiCraft.ganchoComDefeito + "). Veja 'hoshie status'.");
                return;
            }
            if (!ChromatiCraft.correcaoAplicada(ChromatiCraft.correcaoDecisao) && !ChromatiCraft.aguardandoClasse(ChromatiCraft.correcaoDecisao)) {
                quem.sendMessage("nao ligo: a correcao nao foi aplicada na classe AbilityCalls neste boot (trava de versao ou padrao nao conferiu,"
                        + " ou ChromatiCraft ausente); ligar agora nao teria efeito. Veja 'hoshie status'.");
                return;
            }
        }
        ChromatiCraft.ativo = ligar;
        quem.sendMessage("limites do mover area " + (ligar ? "ligados" : "desligados") + " em execucao (so ate reiniciar). " + ChromatiCraft.linhaDeEstado());
    }
}
