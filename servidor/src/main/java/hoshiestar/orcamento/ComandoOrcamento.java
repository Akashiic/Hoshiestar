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
package hoshiestar.orcamento;

import hoshiestar.comando.ComandoHoshie;
import net.minecraft.entity.player.EntityPlayerMP;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.v1_7_R4.entity.CraftPlayer;

/** Liga o orcamento ao jogo e registra o subcomando {@code hoshie regioes} (chamado com o servidor de pe). */
public final class ComandoOrcamento {

    private ComandoOrcamento() {
    }

    public static void instalar() {
        final GanchosOrcamento.JogoMinecraft jogo = new GanchosOrcamento.JogoMinecraft();
        Orcamento.definirJogo(jogo); // so cria o nucleo com orcamento.ligado
        if (Orcamento.LIGADO) {
            GanchosOrcamento.avisarLimitadorSpigot();
        }
        final boolean teste = Orcamento.ferramentasDeTeste();
        ComandoHoshie.registrar(new ComandoHoshie.Subcomando() {
            @Override
            public String nome() {
                return "regioes";
            }

            @Override
            public String ajuda() {
                return ComandoRegioes.ajuda(teste);
            }

            @Override
            public void executar(CommandSender quem, String[] args) {
                ComandoRegioes.Posicao pos = null;
                if (quem instanceof CraftPlayer) {
                    EntityPlayerMP p = ((CraftPlayer) quem).getHandle();
                    pos = new ComandoRegioes.Posicao(p.worldObj.provider.dimensionId, GanchosOrcamento.floor(p.posX),
                            GanchosOrcamento.floor(p.posZ));
                }
                for (String s : ComandoRegioes.executar(Orcamento.nucleo(), args, pos, teste ? jogo.ferramentas() : null)) {
                    quem.sendMessage(s);
                }
            }
        });
    }
}
