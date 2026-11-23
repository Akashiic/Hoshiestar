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
package hoshiestar.medidor;

import hoshiestar.comando.ComandoHoshie;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;

/**
 * Subcomandos do medidor no comando "hoshie" da base: {@code hoshie medir [segundos] [forcar]} e
 * {@code hoshie parar}. O {@code hoshie status} e o da base (a linha "medidor: ..." vem de
 * {@link Medidor#linhaDeEstado()}). A permissao (console, RCON ou operador de nivel 4 com o no
 * hoshiestar.admin) ja foi conferida pela base quando estes metodos rodam.
 */
public final class ComandoMedidor {

    private ComandoMedidor() {
    }

    /** Chamado com o servidor de pe (Servidor.iniciando): liga o medidor ao jogo e registra os subcomandos. */
    public static void instalar() {
        GanchosMedidor.instalar();
        // Segunda conferencia do mod antigo, agora com os mods carregados: pega o jar mesmo com outro nome.
        Medidor.conferirClasseAntiga(Medidor.class.getClassLoader());
        ComandoHoshie.registrar(new ComandoHoshie.Subcomando() {
            @Override
            public String nome() {
                return "medir";
            }

            @Override
            public String ajuda() {
                return "medir [segundos " + Medidor.SEG_MIN + "-" + Medidor.SEG_MAX + ", padrao " + Medidor.SEG_PADRAO
                        + "] [forcar] - mede onde vai o tempo de tick no mapa (grava hoshiestar/medicao-*.json e .txt)";
            }

            @Override
            public void executar(CommandSender quem, String[] args) {
                medir(quem, args);
            }
        });
        ComandoHoshie.registrar(new ComandoHoshie.Subcomando() {
            @Override
            public String nome() {
                return "parar";
            }

            @Override
            public String ajuda() {
                return "parar - encerra a medicao em andamento (grava o que ja coletou)";
            }

            @Override
            public void executar(CommandSender quem, String[] args) {
                if (args.length > 0) {
                    quem.sendMessage("hoshie parar nao aceita argumentos (recebeu: '" + String.join(" ", args) + "').");
                    return;
                }
                for (String s : Medidor.comandoParar()) {
                    quem.sendMessage(s);
                }
            }
        });
    }

    static void medir(CommandSender quem, String[] args) {
        Medidor.Pedido p = Medidor.interpretarMedir(args);
        if (p.erro != null) {
            quem.sendMessage(p.erro);
            return;
        }
        boolean console = quem instanceof ConsoleCommandSender;
        for (String s : Medidor.comandoMedir(p.segundos, p.forcar)) {
            // a linha de inicio ja saiu no console pelo log; ao console nao se repete
            if (!(console && s.startsWith("Medicao iniciada"))) {
                quem.sendMessage(s);
            }
        }
    }
}
