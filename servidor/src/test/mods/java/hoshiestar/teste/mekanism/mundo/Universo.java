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
package hoshiestar.teste.mekanism.mundo;

import hoshiestar.mekanism.Mekanism;
import mekanism.api.Coord4D;
import mekanism.common.Tier;
import mekanism.common.content.matrix.SynchronizedMatrixData;
import mekanism.common.tile.TileEntityInductionCell;
import net.minecraft.tileentity.TileEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * Um "universo" do teste diferencial: um mundo falso, uma matriz (classe REAL do Mekanism, ja
 * transformada pela correcao REAL), as celulas (classe REAL) e o livro-caixa da energia que saiu
 * das celulas (itens de celulas quebradas, "disco" de chunks descarregados, orfas). O universo A
 * chama tudo com o recurso desligado (o gancho devolve false na primeira linha: corpo original); o B
 * com o modo dado.
 */
public final class Universo {

    public final int modo;
    public MundoFalso w;
    public SynchronizedMatrixData sd;
    public final List<int[]> layout = new ArrayList<int[]>();
    /** Energia guardada no "disco" (chunk descarregado): posicao -&gt; {energia, tier}. */
    public final Map<Long, double[]> disco = new HashMap<Long, double[]>();
    /** Energia em itens (celula quebrada) esperando para voltar numa celula colocada. */
    public double solto;
    /** Celulas tiradas do mapa sem invalidar (fora do padrao): a energia delas tambem conta. */
    public final List<TileEntityInductionCell> orfas = new ArrayList<TileEntityInductionCell>();

    public Universo(int modo) {
        this.modo = modo;
    }

    public static long pos(int x, int y, int z) {
        return ((long) (x & 0x3ffffff) << 38) | ((long) (z & 0x3ffffff) << 12) | (y & 0xfff);
    }

    public static TileEntityInductionCell celula(int tier, double e) {
        TileEntityInductionCell c = new TileEntityInductionCell();
        c.tier = Tier.InductionCellTier.values()[tier];
        c.setEnergy(e);
        return c;
    }

    public static double max(int tier) {
        return Tier.InductionCellTier.values()[tier].maxEnergy;
    }

    /** Cubo L x L x L de celulas a partir de (ox, oy, oz), chunks 0..1 x 0..1 carregados, matriz formada. */
    public void iniciar(int l, long semente, int ox, int oy, int oz) throws Exception {
        Random r = new Random(semente);
        w = MundoFalso.novo();
        for (int cx = 0; cx <= 1; cx++) {
            for (int cz = 0; cz <= 1; cz++) {
                w.carregarChunk(cx, cz);
            }
        }
        for (int x = 0; x < l; x++) {
            for (int y = 0; y < l; y++) {
                for (int z = 0; z < l; z++) {
                    int[] p = {ox + x, oy + y, oz + z};
                    layout.add(p);
                    int t = r.nextInt(4);
                    w.colocar(p[0], p[1], p[2], celula(t, r.nextDouble() * max(t)));
                }
            }
        }
        reformar();
    }

    /** Como MatrixUpdateProtocol.canForm: estrutura NOVA com as coordenadas que tem celula. */
    public void reformar() {
        SynchronizedMatrixData s = new SynchronizedMatrixData();
        for (int[] p : layout) {
            TileEntity t = w.cru(p[0], p[1], p[2]);
            if (t instanceof TileEntityInductionCell && !t.func_145837_r()) {
                s.cells.add(new Coord4D(p[0], p[1], p[2], 0));
                s.storageCap += ((TileEntityInductionCell) t).tier.maxEnergy;
            }
        }
        sd = s;
    }

    public double ler() {
        Mekanism.modo = modo;
        return sd.getEnergy(w);
    }

    /** Leitura pelo corpo original (gancho desligado), para comparar com {@link #ler()}. */
    public double lerOriginal() {
        Mekanism.modo = Mekanism.DESLIGADO;
        return sd.getEnergy(w);
    }

    /** Como TileEntityInductionCasing.setEnergy: limita a [0, capacidade] e escreve. */
    public void escrever(double e) {
        double v = Math.max(Math.min(e, sd.storageCap), 0.0);
        Mekanism.modo = modo;
        sd.setEnergy(w, v);
    }

    public TileEntityInductionCell celulaEm(int[] p) {
        TileEntity t = w.cru(p[0], p[1], p[2]);
        return t instanceof TileEntityInductionCell && !t.func_145837_r() ? (TileEntityInductionCell) t : null;
    }

    /** Fotografia de toda a energia: cada posicao (mundo ou disco), o solto e cada orfa, em bits. */
    public TreeMap<String, Long> foto() {
        TreeMap<String, Long> m = new TreeMap<String, Long>();
        for (int[] p : layout) {
            long k = pos(p[0], p[1], p[2]);
            TileEntityInductionCell c = celulaEm(p);
            if (c != null) {
                m.put("p" + k, Double.doubleToLongBits(c.electricityStored));
            } else if (disco.containsKey(k)) {
                m.put("d" + k, Double.doubleToLongBits(disco.get(k)[0]));
            }
        }
        m.put("solto", Double.doubleToLongBits(solto));
        for (int i = 0; i < orfas.size(); i++) {
            m.put("orfa" + i, Double.doubleToLongBits(orfas.get(i).electricityStored));
        }
        return m;
    }

    public double total() {
        double s = 0;
        for (Long v : foto().values()) {
            s += Double.longBitsToDouble(v);
        }
        return s;
    }

    // ------------------------------------------------------------------ operacoes do diferencial

    /** Codigos: ver {@link #operar}. 7 (tick) e tratado fora (o relogio e um so para A e B). */
    public static final int TICK = 7;

    /**
     * Aplica a operacao. Devolve o valor lido (NaN se a operacao nao le). Os sorteios usam {@code r};
     * A e B recebem Randoms com a mesma semente, entao fazem exatamente o mesmo.
     */
    public double operar(int op, Random r) throws Exception {
        switch (op) {
            case 0:
            case 1:
            case 2: { // injetar: le e escreve lido + x (o caminho de duplicacao se a leitura estivesse velha)
                double e = ler();
                escrever(e + r.nextDouble() * 2e10);
                return e;
            }
            case 3:
            case 4:
            case 5: {
                double e = ler();
                escrever(e - r.nextDouble() * 2e10);
                return e;
            }
            case 6:
                return ler();
            case 8: { // quebrar uma celula (caminho normal: remove e invalida); a energia vai para o item
                int[] p = layout.get(r.nextInt(layout.size()));
                if (!w.chunkCarregado(p[0], p[2])) {
                    return Double.NaN;
                }
                TileEntity t = w.remover(p[0], p[1], p[2], false);
                if (t instanceof TileEntityInductionCell) {
                    solto += ((TileEntityInductionCell) t).electricityStored;
                }
                return Double.NaN;
            }
            case 9: { // colocar uma celula numa posicao vazia, com energia do item
                int[] p = layout.get(r.nextInt(layout.size()));
                int t = r.nextInt(4);
                if (!w.chunkCarregado(p[0], p[2]) || w.cru(p[0], p[1], p[2]) != null) {
                    return Double.NaN;
                }
                double e = Math.min(solto, max(t));
                solto -= e;
                w.colocar(p[0], p[1], p[2], celula(t, e));
                return Double.NaN;
            }
            case 10: { // descarregar um chunk (celulas para o "disco"; nada e invalidado)
                int cx = r.nextInt(2);
                int cz = r.nextInt(2);
                if (!w.carregados.containsKey(MundoFalso.chave(cx, cz))) {
                    return Double.NaN;
                }
                for (int[] p : layout) {
                    if (p[0] >> 4 == cx && p[2] >> 4 == cz) {
                        TileEntityInductionCell c = celulaEm(p);
                        if (c != null) {
                            disco.put(pos(p[0], p[1], p[2]), new double[] {c.electricityStored, c.tier.ordinal()});
                        }
                    }
                }
                w.descarregarChunk(cx, cz);
                return Double.NaN;
            }
            case 11: { // carregar de volta: Chunk novo, tile entities novas lidas do "NBT"
                int cx = r.nextInt(2);
                int cz = r.nextInt(2);
                if (w.carregados.containsKey(MundoFalso.chave(cx, cz))) {
                    return Double.NaN;
                }
                w.carregarChunk(cx, cz);
                for (int[] p : layout) {
                    if (p[0] >> 4 == cx && p[2] >> 4 == cz) {
                        double[] d = disco.remove(pos(p[0], p[1], p[2]));
                        if (d != null) {
                            w.colocar(p[0], p[1], p[2], celula((int) d[1], d[0]));
                        }
                    }
                }
                return Double.NaN;
            }
            case 12: { // a matriz se forma de novo (estrutura nova), so com os 4 chunks carregados
                for (int cx = 0; cx <= 1; cx++) {
                    for (int cz = 0; cz <= 1; cz++) {
                        if (!w.carregados.containsKey(MundoFalso.chave(cx, cz))) {
                            return Double.NaN;
                        }
                    }
                }
                reformar();
                return Double.NaN;
            }
            case 13: { // trocar uma celula por outra na mesma coordenada (caminho normal: a velha e invalidada)
                int[] p = layout.get(r.nextInt(layout.size()));
                int t = r.nextInt(4);
                TileEntityInductionCell velha = w.chunkCarregado(p[0], p[2]) ? celulaEm(p) : null;
                if (velha == null) {
                    return Double.NaN;
                }
                solto += velha.electricityStored;
                double e = Math.min(solto, max(t));
                solto -= e;
                w.colocar(p[0], p[1], p[2], celula(t, e));
                return Double.NaN;
            }
            case 14: { // FORA DO PADRAO: um mod tira a celula do mapa do chunk sem invalidar (orfa)
                int[] p = layout.get(r.nextInt(layout.size()));
                TileEntityInductionCell c = w.chunkCarregado(p[0], p[2]) ? celulaEm(p) : null;
                if (c == null) {
                    return Double.NaN;
                }
                w.remover(p[0], p[1], p[2], true);
                orfas.add(c);
                return Double.NaN;
            }
            case 15: { // FORA DO PADRAO: um mod poe outra celula no mapa por cima, sem invalidar a velha
                int[] p = layout.get(r.nextInt(layout.size()));
                int t = r.nextInt(4);
                TileEntityInductionCell velha = w.chunkCarregado(p[0], p[2]) ? celulaEm(p) : null;
                if (velha == null) {
                    return Double.NaN;
                }
                double e = Math.min(solto, max(t));
                solto -= e;
                w.colocarCru(p[0], p[1], p[2], celula(t, e));
                orfas.add(velha);
                return Double.NaN;
            }
            default:
                return Double.NaN;
        }
    }
}
