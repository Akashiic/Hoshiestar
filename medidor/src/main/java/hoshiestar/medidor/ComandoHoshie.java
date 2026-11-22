/*
 * Hoshiestar-Medidor: mede onde, no mapa, vai o tempo de tick.
 * Copyright (C) 2026 Projeto Hoshiestar
 *
 * Este programa e software livre: voce pode redistribui-lo e/ou modifica-lo
 * sob os termos da GNU General Public License publicada pela Free Software
 * Foundation, versao 3 da Licenca ou (a seu criterio) qualquer versao posterior.
 *
 * Este programa e distribuido na esperanca de que seja util, mas SEM QUALQUER
 * GARANTIA; sem mesmo a garantia implicita de COMERCIABILIDADE ou ADEQUACAO A
 * UM PROPOSITO ESPECIFICO. Veja a GNU General Public License para mais detalhes.
 *
 * Voce deve ter recebido uma copia da GNU General Public License junto com este
 * programa. Se nao, veja <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package hoshiestar.medidor;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.server.CommandBlockLogic;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;

import java.util.List;

/**
 * Comando "hoshie": medir [segundos], parar, status. Console e operadores de nivel 3+.
 *
 * No Crucible o CommandHandler desliga a checagem de nivel do jogo ("true ||") e deixa a
 * permissao para o Bukkit (no "hoshiestar.medidor.ComandoHoshie", padrao "op", sem nivel).
 * Um plugin de permissoes com "*" daria o comando a quem nao e op 3. Por isso o nivel e
 * conferido aqui dentro tambem, para jogadores e blocos de comando.
 */
public class ComandoHoshie extends CommandBase {

    static final int NIVEL = 3;

    @Override
    public String func_71517_b() {
        return "hoshie";
    }

    @Override
    public String func_71518_a(ICommandSender sender) {
        return "/hoshie medir [segundos " + Medidor.SEG_MIN + "-" + Medidor.SEG_MAX + ", padrao " + Medidor.SEG_PADRAO
                + "] | /hoshie parar | /hoshie status";
    }

    @Override
    public int func_82362_a() {
        return NIVEL;
    }

    @Override
    public List func_71516_a(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            return func_71530_a(args, "medir", "parar", "status");
        }
        return null;
    }

    @Override
    public void func_71515_b(ICommandSender sender, String[] args) {
        try {
            if ((sender instanceof EntityPlayer || sender instanceof CommandBlockLogic)
                    && !sender.func_70003_b(NIVEL, func_71517_b())) {
                enviar(sender, "Sem permissao: o comando hoshie e so para o console e operadores de nivel " + NIVEL
                        + " ou mais.");
                return;
            }
            if (args.length == 0) {
                enviar(sender, "Uso: " + func_71518_a(sender));
            }
            String sub = args.length == 0 ? "status" : args[0].toLowerCase(java.util.Locale.ROOT);
            if ("medir".equals(sub)) {
                int seg = Medidor.SEG_PADRAO;
                if (args.length >= 2) {
                    try {
                        seg = Integer.parseInt(args[1].trim());
                    } catch (NumberFormatException e) {
                        enviar(sender, "Numero de segundos invalido: '" + args[1] + "'. Uso: " + func_71518_a(sender));
                        return;
                    }
                    if (seg < Medidor.SEG_MIN || seg > Medidor.SEG_MAX) {
                        enviar(sender, "Valor fora do limite: " + seg + " s. Use entre " + Medidor.SEG_MIN + " e "
                                + Medidor.SEG_MAX + " segundos (padrao " + Medidor.SEG_PADRAO + ").");
                        return;
                    }
                }
                List<String> r = Medidor.comandoMedir(seg);
                // a linha de inicio ja saiu no console pelo log; ao console nao se repete
                if (!(sender instanceof MinecraftServer)) {
                    for (String s : r) {
                        enviar(sender, s);
                    }
                } else {
                    for (String s : r) {
                        if (!s.startsWith("Medicao iniciada")) {
                            enviar(sender, s);
                        }
                    }
                }
            } else if ("parar".equals(sub)) {
                for (String s : Medidor.comandoParar()) {
                    enviar(sender, s);
                }
            } else if ("status".equals(sub)) {
                for (String s : Medidor.comandoStatus()) {
                    enviar(sender, s);
                }
            } else {
                enviar(sender, "Uso: " + func_71518_a(sender));
            }
        } catch (Throwable t) {
            // o comando nunca derruba nada; avisa e segue
            Medidor.LOG.warn(Medidor.P + "Erro no comando: " + t);
            try {
                enviar(sender, "Erro no comando do medidor: " + t);
            } catch (Throwable ignorado) {
                // nada
            }
        }
    }

    /** ICommand estende Comparable cru; ordena por nome, como o CommandBase do jogo. */
    @Override
    public int compareTo(Object o) {
        if (o instanceof ICommand) {
            return func_71517_b().compareTo(((ICommand) o).func_71517_b());
        }
        return 0;
    }

    private static void enviar(ICommandSender sender, String s) {
        sender.func_145747_a(new ChatComponentText(s));
    }
}
