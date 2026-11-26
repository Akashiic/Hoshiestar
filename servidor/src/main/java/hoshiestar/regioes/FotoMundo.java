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
 * O que o jogo entrega ao observador para a foto de um mundo (so numeros, nenhum objeto do jogo): os chunks carregados,
 * a posicao (em blocos) de cada tile entity e de cada entidade. Quem preenche e o {@link Jogo}; os testes preenchem
 * a mao. Os vetores podem ser maiores que a contagem (vale {@code nXxx}).
 */
public final class FotoMundo {

    /** Numero da dimensao (provider.dimensionId). */
    public final int dim;
    public int[] chunksX = new int[0];
    public int[] chunksZ = new int[0];
    public int nChunks;
    public int[] tilesX = new int[0];
    public int[] tilesZ = new int[0];
    /** Opcional: nome da classe de cada tile entity (mesmo tamanho de tilesX; null = nao informado). */
    public String[] tilesClasse;
    public int nTiles;
    public int[] entX = new int[0];
    public int[] entZ = new int[0];
    public int nEnt;

    public FotoMundo(int dim) {
        this.dim = dim;
    }

    /** Atalho para testes: acrescenta um chunk. */
    public FotoMundo chunk(int cx, int cz) {
        if (nChunks == chunksX.length) {
            int c = Math.max(16, nChunks * 2);
            chunksX = java.util.Arrays.copyOf(chunksX, c);
            chunksZ = java.util.Arrays.copyOf(chunksZ, c);
        }
        chunksX[nChunks] = cx;
        chunksZ[nChunks++] = cz;
        return this;
    }

    /** Atalho para testes: acrescenta uma tile entity na posicao (bloco x, bloco z). */
    public FotoMundo tile(int x, int z) {
        if (nTiles == tilesX.length) {
            int c = Math.max(16, nTiles * 2);
            tilesX = java.util.Arrays.copyOf(tilesX, c);
            tilesZ = java.util.Arrays.copyOf(tilesZ, c);
        }
        tilesX[nTiles] = x;
        tilesZ[nTiles++] = z;
        return this;
    }

    /** Atalho para testes: acrescenta uma entidade na posicao (bloco x, bloco z). */
    public FotoMundo entidade(int x, int z) {
        if (nEnt == entX.length) {
            int c = Math.max(16, nEnt * 2);
            entX = java.util.Arrays.copyOf(entX, c);
            entZ = java.util.Arrays.copyOf(entZ, c);
        }
        entX[nEnt] = x;
        entZ[nEnt++] = z;
        return this;
    }
}
