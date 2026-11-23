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
package hoshiestar.vigia;

import com.mojang.authlib.GameProfile;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import org.spigotmc.WatchdogThread;

import java.util.Locale;

/**
 * O lado Minecraft do vigia: le, da thread do vigia, o que o Crucible e as marcacoes publicam.
 * So LEITURA de campos simples (posicao, dimensao, id, coordenadas) e dois getters que so devolvem
 * campo ({@code getEntityId}, {@code getGameProfile}, {@code getWorldObj}). Nunca chama toString de
 * entidade/tile/pacote (podem rodar codigo de mod), nunca le IP nem texto de pacote. Valores podem
 * estar velhos ou rasgados (outra thread escreve): so muda o texto do relatorio.
 *
 * <p>Carregada so com o servidor de pe ({@code hoshiestar.Servidor.pronto}).
 */
public final class ContextoMinecraft implements NucleoVigia.Contexto {

    @Override
    public Object ultimoPacote() {
        return WatchdogThread.currentPacket.get(); // Crucible publica em NetworkManager (sem finally: pode ficar velho)
    }

    @Override
    public Object jogadorDaRede() {
        return WatchdogThread.currentPlayer.get();
    }

    @Override
    public String entidade(Object o, boolean nomeDoJogador) {
        if (!(o instanceof Entity)) {
            return o.getClass().getName();
        }
        Entity e = (Entity) o;
        StringBuilder sb = new StringBuilder();
        sb.append(CamposNumericos.nomeSimples(o.getClass())).append(" (").append(o.getClass().getName()).append(')');
        if (e instanceof EntityPlayer) {
            sb.append(" jogador ").append(nome((EntityPlayer) e, nomeDoJogador));
        }
        sb.append(", id ").append(e.getEntityId());
        posicao(sb, e);
        return sb.toString();
    }

    @Override
    public String jogador(Object o, boolean nomeDoJogador) {
        if (!(o instanceof EntityPlayer)) {
            return o.getClass().getName();
        }
        EntityPlayer p = (EntityPlayer) o;
        StringBuilder sb = new StringBuilder();
        sb.append(nome(p, nomeDoJogador));
        posicao(sb, p);
        return sb.toString();
    }

    @Override
    public String tile(Object o) {
        if (!(o instanceof TileEntity)) {
            return o.getClass().getName();
        }
        TileEntity t = (TileEntity) o;
        StringBuilder sb = new StringBuilder();
        sb.append(CamposNumericos.nomeSimples(o.getClass())).append(" (").append(o.getClass().getName()).append(')');
        sb.append(" em ").append(t.xCoord).append(' ').append(t.yCoord).append(' ').append(t.zCoord);
        String dim = "?";
        try {
            World w = t.getWorldObj();
            if (w != null && w.provider != null) {
                dim = String.valueOf(w.provider.dimensionId);
            }
        } catch (Throwable ignorado) {
            // fica "?"
        }
        sb.append(", dim ").append(dim);
        return sb.toString();
    }

    private static String nome(EntityPlayer p, boolean mostrar) {
        if (!mostrar) {
            return "(nome oculto: vigia.nome_do_jogador = false)";
        }
        try {
            GameProfile g = p.getGameProfile();
            String n = g == null ? null : g.getName();
            return n == null ? "?" : n;
        } catch (Throwable t) {
            return "?";
        }
    }

    private static void posicao(StringBuilder sb, Entity e) {
        sb.append(", dim ").append(e.dimension).append(", pos ")
                .append(String.format(Locale.ROOT, "%.1f %.1f %.1f", e.posX, e.posY, e.posZ));
    }
}
