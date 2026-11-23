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
package hoshiestar.mekanism.matriz;

import hoshiestar.api.ThreadServidor;
import hoshiestar.comando.ComandoHoshie;
import hoshiestar.mekanism.Mekanism;
import mekanism.api.Coord4D;
import mekanism.api.energy.IStrictEnergyStorage;
import mekanism.common.content.matrix.SynchronizedMatrixData;
import mekanism.common.tile.TileEntityInductionCasing;
import mekanism.common.tile.TileEntityInductionCell;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraftforge.common.DimensionManager;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;

/**
 * Subcomando de diagnostico (so leitura) para o teste no servidor:
 * {@code hoshie mekanism <x> <y> <z> [dimensao] [mais <x> <y> <z> ...]}, apontando para um bloco da
 * moldura ou uma porta da matriz. Mostra a soma DIRETA da energia das celulas (busca original de cada
 * coordenada, sem passar pelo Hoshiestar), a leitura normal (getEnergy, que passa pelo gancho quando a
 * correcao esta aplicada), se as duas sao iguais bit a bit, quantas coordenadas tem celula e o estado
 * guardado. Depois de "mais": outros blocos que guardam energia do Mekanism (cubos de energia, por
 * exemplo), cuja energia entra no "livro-caixa" (soma direta + extras), tudo lido na MESMA chamada,
 * entre dois ticks (energia que passa da matriz para um cubo nao muda o total); uma celula da propria
 * matriz depois de "mais" so tem a energia mostrada (para anotar antes de quebra-la). Nunca carrega chunk;
 * so na thread do servidor (console e comando de jogador rodam nela). Com a correcao nao aplicada
 * (modo desligado, trava...), nao toca na classe da logica.
 */
public final class ComandoMatriz implements ComandoHoshie.Subcomando {

    private ComandoMatriz() {
    }

    /** Chamado por reflexao em Mekanism.aoIniciarServidor (servidor de pe). */
    public static void registrar() {
        ComandoHoshie.registrar(new ComandoMatriz());
    }

    @Override
    public String nome() {
        return "mekanism";
    }

    @Override
    public String ajuda() {
        return "mekanism <x> <y> <z> [dimensao] [mais <x> <y> <z> ...] - energia da matriz de inducao (soma direta das"
                + " celulas x leitura normal; 'mais' soma blocos de energia do Mekanism no livro-caixa)";
    }

    @Override
    public void executar(CommandSender quem, String[] args) {
        int mais = -1;
        for (int i = 0; i < args.length; i++) {
            if ("mais".equalsIgnoreCase(args[i])) {
                mais = i;
                break;
            }
        }
        int nPrincipal = mais < 0 ? args.length : mais;
        int nExtras = mais < 0 ? 0 : args.length - mais - 1;
        if ((nPrincipal != 3 && nPrincipal != 4) || (mais >= 0 && (nExtras == 0 || nExtras % 3 != 0))) {
            quem.sendMessage("uso: hoshie " + ajuda());
            return;
        }
        if (!ThreadServidor.souAThreadDoServidor()) {
            quem.sendMessage("hoshie mekanism: rode pelo console do servidor ou como jogador (precisa da thread do servidor)");
            return;
        }
        int x;
        int y;
        int z;
        int dim;
        int[][] extras = new int[nExtras / 3][];
        try {
            x = Integer.parseInt(args[0]);
            y = Integer.parseInt(args[1]);
            z = Integer.parseInt(args[2]);
            dim = nPrincipal > 3 ? Integer.parseInt(args[3]) : 0;
            for (int k = 0; k < extras.length; k++) {
                int b = mais + 1 + 3 * k;
                extras[k] = new int[] {Integer.parseInt(args[b]), Integer.parseInt(args[b + 1]), Integer.parseInt(args[b + 2])};
            }
        } catch (NumberFormatException e) {
            quem.sendMessage("hoshie mekanism: coordenadas invalidas");
            return;
        }
        try {
            World w = DimensionManager.getWorld(dim);
            if (w == null) {
                quem.sendMessage("hoshie mekanism: dimensao " + dim + " nao carregada");
                return;
            }
            for (String linha : relatorio(w, dim, x, y, z, extras)) {
                quem.sendMessage(linha);
            }
        } catch (Throwable t) {
            quem.sendMessage("hoshie mekanism: erro: " + t);
        }
    }

    /**
     * O relatorio (so leitura; a parte que o teste isolado roda num mundo falso). Nunca carrega chunk:
     * confere chunkExists antes de cada busca (Coord4D.getTileEntity tambem confere).
     */
    public static List<String> relatorio(World w, int dim, int x, int y, int z, int[][] extras) {
        List<String> r = new ArrayList<String>();
        IChunkProvider prov = w.getChunkProvider();
        if (prov == null || !prov.chunkExists(x >> 4, z >> 4)) {
            r.add("hoshie mekanism: chunk nao carregado em " + x + "," + z + " (nada foi carregado)");
            return r;
        }
        TileEntity te = w.getTileEntity(x, y, z);
        if (!(te instanceof TileEntityInductionCasing)) {
            r.add("hoshie mekanism: nao ha moldura/porta de matriz de inducao em " + x + "," + y + "," + z);
            return r;
        }
        SynchronizedMatrixData s = ((TileEntityInductionCasing) te).structure;
        if (s == null) {
            r.add("hoshie mekanism: matriz nao formada");
            return r;
        }
        double direta = 0.0;
        int comCelula = 0;
        int semCelula = 0;
        for (Coord4D c : s.cells) {
            TileEntity t = c.getTileEntity(w);
            if (t instanceof TileEntityInductionCell) {
                direta += ((TileEntityInductionCell) t).electricityStored;
                comCelula++;
            } else {
                semCelula++;
            }
        }
        double normal = s.getEnergy(w);
        r.add("matriz em " + x + "," + y + "," + z + " (dim " + dim + "): " + s.cells.size() + " coordenadas de celula, "
                + comCelula + " com celula, " + semCelula + " sem; capacidade " + s.storageCap);
        r.add("  soma direta das celulas: " + direta + " J");
        r.add("  leitura normal (getEnergy): " + normal + " J; iguais bit a bit: "
                + (Double.doubleToLongBits(direta) == Double.doubleToLongBits(normal) ? "sim" : "NAO"));
        if (extras != null && extras.length > 0) {
            double livro = direta;
            boolean completo = true;
            for (int[] e : extras) {
                String onde = e[0] + "," + e[1] + "," + e[2];
                if (!prov.chunkExists(e[0] >> 4, e[2] >> 4)) {
                    r.add("  extra " + onde + ": chunk nao carregado (fora do livro-caixa)");
                    completo = false;
                    continue;
                }
                TileEntity t = w.getTileEntity(e[0], e[1], e[2]);
                if (ehCelula(s, e)) {
                    // so mostra (para anotar a energia de uma celula antes de quebra-la); ja esta na soma direta
                    r.add("  extra " + onde + ": celula desta matriz, " + (t instanceof TileEntityInductionCell
                            ? ((TileEntityInductionCell) t).electricityStored + " J" : "sem celula")
                            + " (ja esta na soma direta; fora do livro-caixa)");
                    continue;
                }
                if (t instanceof TileEntityInductionCasing || !(t instanceof IStrictEnergyStorage)) {
                    r.add("  extra " + onde + ": nao e um bloco de energia do Mekanism (cubo de energia, por exemplo)"
                            + " fora da matriz (fora do livro-caixa)");
                    completo = false;
                    continue;
                }
                double en = ((IStrictEnergyStorage) t).getEnergy();
                livro += en;
                r.add("  extra " + onde + " (" + t.getClass().getSimpleName() + "): " + en + " J");
            }
            r.add("  livro-caixa (soma direta + extras): " + livro + " J" + (completo ? "" : " (INCOMPLETO: veja acima)"));
        }
        r.add("  Hoshiestar (modo " + Mekanism.nomeDoModo(Mekanism.modo) + "): " + estado(s));
        return r;
    }

    private static boolean ehCelula(SynchronizedMatrixData s, int[] e) {
        for (Coord4D c : s.cells) {
            if (c.xCoord == e[0] && c.yCoord == e[1] && c.zCoord == e[2]) {
                return true;
            }
        }
        return false;
    }

    /**
     * O estado guardado, so se a correcao foi aplicada nesta classe (campo presente): sem ela (modo
     * desligado, trava que nao conferiu) a classe da logica nem e carregada.
     */
    static String estado(SynchronizedMatrixData s) {
        try {
            SynchronizedMatrixData.class.getField(Mekanism.CAMPO);
        } catch (NoSuchFieldException e) {
            return "correcao nao aplicada nesta classe (Mekanism original; a leitura normal e a do Mekanism)";
        }
        return MatrizInducao.descrever(s);
    }
}
