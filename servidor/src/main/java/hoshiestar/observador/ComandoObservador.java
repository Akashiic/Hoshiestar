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
package hoshiestar.observador;

import hoshiestar.comando.ComandoHoshie;
import org.bukkit.command.CommandSender;

/** Liga o observador ao jogo e registra o subcomando {@code hoshie observar} (chamado com o servidor de pe). */
public final class ComandoObservador {

    private ComandoObservador() {
    }

    public static void instalar() {
        Observador.definirJogo(new GanchosObservador.JogoMinecraft());
        ComandoHoshie.registrar(new ComandoHoshie.Subcomando() {
            @Override
            public String nome() {
                return "observar";
            }

            @Override
            public String ajuda() {
                return Observador.ajuda();
            }

            @Override
            public void executar(CommandSender quem, String[] args) {
                for (String s : Observador.comando(args)) {
                    quem.sendMessage(s);
                }
            }
        });
    }
}
