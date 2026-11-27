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
package hoshiestar.paralelo;

import hoshiestar.comando.ComandoHoshie;
import org.bukkit.command.CommandSender;

/** Registra o subcomando {@code hoshie paralelo} (chamado com o servidor de pe). O texto esta em {@link ComandoParalelo}. */
public final class ComandoParaleloJogo {

    private ComandoParaleloJogo() {
    }

    public static void instalar() {
        ComandoHoshie.registrar(new ComandoHoshie.Subcomando() {
            @Override
            public String nome() {
                return "paralelo";
            }

            @Override
            public String ajuda() {
                return ComandoParalelo.ajuda();
            }

            @Override
            public void executar(CommandSender quem, String[] args) {
                for (String s : ComandoParalelo.executar(args)) {
                    quem.sendMessage(s);
                }
            }
        });
    }
}
