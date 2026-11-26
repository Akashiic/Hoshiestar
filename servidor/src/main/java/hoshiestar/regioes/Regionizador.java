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
 * O regionizador de uma thread so (Etapa 2.5). A regra de juncao e a do estudo Folia que o observador ja usou: unidade =
 * secao de 16x16 chunks; duas secoes com chunk carregado, na mesma dimensao, a ate {@link #RAIO_JUNCAO} secoes uma da
 * outra (Chebyshev) ficam na mesma regiao; a juncao e transitiva. Dimensoes de {@code sempreJuntas} viram uma regiao so
 * por dimensao. So le a {@link FotoMundo}; nao escreve em nada do jogo. Uma instancia por servidor, usada so pela
 * thread do servidor (o indice de dimensoes cresce entre chamadas e so ela o escreve).
 */
public final class Regionizador {

    public static final int RAIO_JUNCAO = 3;
    /** O indice de dimensao cabe em 8 bits da chave; passou de 256 dimensoes diferentes, as demais ficam sem regiao. */
    public static final int MAX_DIMENSOES = 256;

    private final int raio;
    private int[] dims = new int[8];
    private int nDims;

    public Regionizador() {
        this(RAIO_JUNCAO);
    }

    public Regionizador(int raio) {
        this.raio = raio;
    }

    /** Numero de dimensoes que nao couberam no indice (nunca tem regiao). */
    public int dimensoesPerdidas;

    /** Indice interno da dimensao (cria se nova); -1 se passou de {@link #MAX_DIMENSOES}. */
    private int indice(int dim) {
        for (int i = 0; i < nDims; i++) {
            if (dims[i] == dim) {
                return i;
            }
        }
        if (nDims >= MAX_DIMENSOES) {
            return -1;
        }
        if (nDims == dims.length) {
            dims = java.util.Arrays.copyOf(dims, nDims * 2);
        }
        dims[nDims] = dim;
        return nDims++;
    }

    public Mapa calcular(FotoMundo[] mundos, int[] sempreJuntas) {
        int totalChunks = 0;
        for (FotoMundo m : mundos) {
            totalChunks += m.nChunks;
        }
        MapaSecoes mapa = new MapaSecoes(Math.max(16, Math.min(totalChunks, 1 << 16)));
        int[] idx = new int[mundos.length];
        for (int k = 0; k < mundos.length; k++) {
            FotoMundo m = mundos[k];
            int d = indice(m.dim);
            idx[k] = d;
            if (d < 0) {
                dimensoesPerdidas++;
                continue;
            }
            for (int i = 0; i < m.nChunks; i++) {
                int v = mapa.criar(MapaSecoes.chave(d, m.chunksX[i] >> 4, m.chunksZ[i] >> 4));
                mapa.chunks[v]++;
            }
            for (int i = 0; i < m.nTiles; i++) {
                int v = mapa.vaga(MapaSecoes.chave(d, m.tilesX[i] >> 8, m.tilesZ[i] >> 8));
                if (v >= 0) {
                    mapa.tiles[v]++;
                }
            }
            for (int i = 0; i < m.nEnt; i++) {
                int v = mapa.vaga(MapaSecoes.chave(d, m.entX[i] >> 8, m.entZ[i] >> 8));
                if (v >= 0) {
                    mapa.ents[v]++;
                }
            }
        }
        int n = mapa.tamanho();
        int[] pai = new int[n];
        for (int i = 0; i < n; i++) {
            pai[i] = i;
        }
        for (int v = 0; v < n; v++) {
            long c = mapa.chaveDaVaga(v);
            int d = MapaSecoes.dimDe(c);
            int x = MapaSecoes.xDe(c);
            int z = MapaSecoes.zDe(c);
            for (int dx = -raio; dx <= raio; dx++) {
                for (int dz = -raio; dz <= raio; dz++) {
                    if (dx < 0 || (dx == 0 && dz <= 0)) {
                        continue; // cada par uma vez so
                    }
                    int w = mapa.vaga(MapaSecoes.chave(d, x + dx, z + dz));
                    if (w >= 0) {
                        unir(pai, v, w);
                    }
                }
            }
        }
        if (sempreJuntas != null && sempreJuntas.length > 0) {
            int[] primeiro = new int[MAX_DIMENSOES];
            java.util.Arrays.fill(primeiro, -1);
            for (int v = 0; v < n; v++) {
                int d = MapaSecoes.dimDe(mapa.chaveDaVaga(v));
                boolean junta = false;
                for (int s : sempreJuntas) {
                    int si = indiceExistente(s);
                    if (si == d) {
                        junta = true;
                        break;
                    }
                }
                if (!junta) {
                    continue;
                }
                if (primeiro[d] < 0) {
                    primeiro[d] = v;
                } else {
                    unir(pai, primeiro[d], v);
                }
            }
        }
        int[] reg = new int[n];
        int[] idDaRaiz = new int[n];
        java.util.Arrays.fill(idDaRaiz, -1);
        int nReg = 0;
        for (int v = 0; v < n; v++) {
            int r = achar(pai, v);
            if (idDaRaiz[r] < 0) {
                idDaRaiz[r] = nReg++;
            }
            reg[v] = idDaRaiz[r];
        }
        int[] rDim = new int[nReg], rSec = new int[nReg], rChunks = new int[nReg], rTiles = new int[nReg];
        int[] rEnts = new int[nReg], rX0 = new int[nReg], rX1 = new int[nReg], rZ0 = new int[nReg], rZ1 = new int[nReg];
        java.util.Arrays.fill(rX0, Integer.MAX_VALUE);
        java.util.Arrays.fill(rZ0, Integer.MAX_VALUE);
        java.util.Arrays.fill(rX1, Integer.MIN_VALUE);
        java.util.Arrays.fill(rZ1, Integer.MIN_VALUE);
        for (int v = 0; v < n; v++) {
            int r = reg[v];
            long c = mapa.chaveDaVaga(v);
            int x = MapaSecoes.xDe(c);
            int z = MapaSecoes.zDe(c);
            rDim[r] = dims[MapaSecoes.dimDe(c)];
            rSec[r]++;
            rChunks[r] += mapa.chunks[v];
            rTiles[r] += mapa.tiles[v];
            rEnts[r] += mapa.ents[v];
            rX0[r] = Math.min(rX0[r], x);
            rX1[r] = Math.max(rX1[r], x);
            rZ0[r] = Math.min(rZ0[r], z);
            rZ1[r] = Math.max(rZ1[r], z);
        }
        return new Mapa(mapa, reg, nReg, java.util.Arrays.copyOf(dims, nDims), nDims, rDim, rSec, rChunks, rTiles, rEnts,
                rX0, rX1, rZ0, rZ1);
    }

    private int indiceExistente(int dim) {
        for (int i = 0; i < nDims; i++) {
            if (dims[i] == dim) {
                return i;
            }
        }
        return -1;
    }

    private static int achar(int[] pai, int v) {
        while (pai[v] != v) {
            pai[v] = pai[pai[v]];
            v = pai[v];
        }
        return v;
    }

    private static void unir(int[] pai, int a, int b) {
        int ra = achar(pai, a);
        int rb = achar(pai, b);
        if (ra != rb) {
            pai[Math.max(ra, rb)] = Math.min(ra, rb);
        }
    }
}
