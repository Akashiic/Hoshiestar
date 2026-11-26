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
package hoshiestar.regioes;

/**
 * O resultado de um calculo do {@link Regionizador}: as secoes de 16x16 chunks que tem chunk carregado e a regiao de
 * cada uma. Depois de pronto nao muda mais (pode ser lido de qualquer thread). Os numeros das regioes (0..n-1) valem
 * so para este mapa: o calculo seguinte numera de novo (quem quer guardar estado por regiao guarda por secao).
 */
public final class Mapa {

    /** Secoes (chave, contadores de chunks, tiles e entidades por vaga). */
    public final MapaSecoes secoes;
    /** Regiao de cada vaga de {@link #secoes}. */
    public final int[] regiaoDaVaga;
    public final int nRegioes;
    /** Por regiao: numero da dimensao (provider.dimensionId). */
    public final int[] dim;
    public final int[] nSecoes;
    public final int[] chunks;
    public final int[] tiles;
    public final int[] ents;
    /** Por regiao: a caixa em secoes (inclusive). */
    public final int[] secX0, secX1, secZ0, secZ1;
    private final int[] dims;
    private final int nDims;

    Mapa(MapaSecoes secoes, int[] regiaoDaVaga, int nRegioes, int[] dims, int nDims, int[] dim, int[] nSecoes,
            int[] chunks, int[] tiles, int[] ents, int[] secX0, int[] secX1, int[] secZ0, int[] secZ1) {
        this.secoes = secoes;
        this.regiaoDaVaga = regiaoDaVaga;
        this.nRegioes = nRegioes;
        this.dims = dims;
        this.nDims = nDims;
        this.dim = dim;
        this.nSecoes = nSecoes;
        this.chunks = chunks;
        this.tiles = tiles;
        this.ents = ents;
        this.secX0 = secX0;
        this.secX1 = secX1;
        this.secZ0 = secZ0;
        this.secZ1 = secZ1;
    }

    /** Indice interno (0..255) da dimensao, ou -1 se este mapa nao a conhece. */
    public int indiceDaDim(int dimensao) {
        for (int i = 0; i < nDims; i++) {
            if (dims[i] == dimensao) {
                return i;
            }
        }
        return -1;
    }

    /** Regiao da secao (dimensao pelo indice interno), ou -1: secao sem chunk carregado (sem regiao). */
    public int regiaoDaChave(long chave) {
        int v = secoes.vaga(chave);
        return v < 0 ? -1 : regiaoDaVaga[v];
    }

    /** Regiao do ponto (bloco x, bloco z) da dimensao, ou -1. A secao e o bloco &gt;&gt; 8. */
    public int regiaoNoBloco(int dimensao, int bx, int bz) {
        int d = indiceDaDim(dimensao);
        return d < 0 ? -1 : regiaoDaChave(MapaSecoes.chave(d, bx >> 8, bz >> 8));
    }

    /** Regiao do chunk (cx, cz) da dimensao, ou -1. */
    public int regiaoNoChunk(int dimensao, int cx, int cz) {
        int d = indiceDaDim(dimensao);
        return d < 0 ? -1 : regiaoDaChave(MapaSecoes.chave(d, cx >> 4, cz >> 4));
    }

    /**
     * As regioes (sem repeticao) que tem secao a ate {@code raio} secoes (Chebyshev) da secao dada. Vazio se nenhuma.
     * Serve para saber quais regioes uma secao nova pode juntar.
     */
    public int[] regioesPerto(int dimIdx, int secX, int secZ, int raio) {
        int[] r = new int[8];
        int n = 0;
        for (int dx = -raio; dx <= raio; dx++) {
            for (int dz = -raio; dz <= raio; dz++) {
                int g = regiaoDaChave(MapaSecoes.chave(dimIdx, secX + dx, secZ + dz));
                if (g < 0) {
                    continue;
                }
                boolean tem = false;
                for (int i = 0; i < n; i++) {
                    if (r[i] == g) {
                        tem = true;
                        break;
                    }
                }
                if (!tem) {
                    if (n == r.length) {
                        r = java.util.Arrays.copyOf(r, n * 2);
                    }
                    r[n++] = g;
                }
            }
        }
        return java.util.Arrays.copyOf(r, n);
    }

    /** Caixa da regiao em blocos: {xMin, zMin, xMax, zMax} (inclusive). */
    public int[] caixaEmBlocos(int regiao) {
        return new int[] {secX0[regiao] << 8, secZ0[regiao] << 8, ((secX1[regiao] + 1) << 8) - 1,
            ((secZ1[regiao] + 1) << 8) - 1};
    }
}
