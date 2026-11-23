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
package hoshiestar.observador;

/**
 * Mapa de secoes (chave long = dimensao, secao X, secao Z) para um numero de "vaga" 0..n-1, com tres contadores por
 * vaga (chunks, tiles, entidades). E a foto das secoes que tem chunk carregado. Sem boxing.
 *
 * <p>Chave: 8 bits de indice de dimensao, 28 bits de X e 28 bits de Z da secao (com sinal). Uma secao tem
 * 16x16 chunks (256x256 blocos): secao = chunk &gt;&gt; 4 = bloco &gt;&gt; 8.
 */
final class MapaSecoes {

    private int[] tab;      // vaga + 1 (0 = livre)
    private int mascara;
    private long[] chaves;  // por vaga
    int[] chunks;
    int[] tiles;
    int[] ents;
    private int n;

    MapaSecoes(int capacidadeInicial) {
        int cap = 16;
        while (cap < capacidadeInicial * 2) {
            cap <<= 1;
        }
        tab = new int[cap];
        mascara = cap - 1;
        int v = Math.max(8, capacidadeInicial);
        chaves = new long[v];
        chunks = new int[v];
        tiles = new int[v];
        ents = new int[v];
    }

    static long chave(int dimIdx, int secX, int secZ) {
        return ((long) (dimIdx & 0xFF) << 56) | ((long) (secX & 0xFFFFFFF) << 28) | (long) (secZ & 0xFFFFFFF);
    }

    static int dimDe(long chave) {
        return (int) (chave >>> 56) & 0xFF;
    }

    static int xDe(long chave) {
        return ((int) (chave >>> 28) & 0xFFFFFFF) << 4 >> 4;
    }

    static int zDe(long chave) {
        return ((int) chave & 0xFFFFFFF) << 4 >> 4;
    }

    /** A vaga da chave, ou -1 se a secao nao esta no mapa. */
    int vaga(long chave) {
        int i = TabelaContagem.espalhar(chave) & mascara;
        while (tab[i] != 0) {
            if (chaves[tab[i] - 1] == chave) {
                return tab[i] - 1;
            }
            i = (i + 1) & mascara;
        }
        return -1;
    }

    /** A vaga da chave; cria se nao existe. */
    int criar(long chave) {
        int i = TabelaContagem.espalhar(chave) & mascara;
        while (tab[i] != 0) {
            if (chaves[tab[i] - 1] == chave) {
                return tab[i] - 1;
            }
            i = (i + 1) & mascara;
        }
        if (n == chaves.length) {
            int nn = n << 1;
            chaves = java.util.Arrays.copyOf(chaves, nn);
            chunks = java.util.Arrays.copyOf(chunks, nn);
            tiles = java.util.Arrays.copyOf(tiles, nn);
            ents = java.util.Arrays.copyOf(ents, nn);
        }
        chaves[n] = chave;
        tab[i] = n + 1;
        n++;
        if (n * 2 > tab.length) {
            refazer();
        }
        return n - 1;
    }

    private void refazer() {
        tab = new int[tab.length << 1];
        mascara = tab.length - 1;
        for (int v = 0; v < n; v++) {
            int i = TabelaContagem.espalhar(chaves[v]) & mascara;
            while (tab[i] != 0) {
                i = (i + 1) & mascara;
            }
            tab[i] = v + 1;
        }
    }

    int tamanho() {
        return n;
    }

    long chaveDaVaga(int v) {
        return chaves[v];
    }
}
