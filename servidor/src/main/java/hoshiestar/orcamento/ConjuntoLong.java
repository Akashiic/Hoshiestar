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

/** Conjunto de long sem boxing (enderecamento aberto), com teto: passou do teto, deixa de guardar e diz que cheio. */
final class ConjuntoLong {

    private long[] tab = new long[64];
    private boolean[] usado = new boolean[64];
    private int n;
    private final int teto;

    ConjuntoLong(int teto) {
        this.teto = teto;
    }

    private static int h(long k, int mask) {
        k ^= (k >>> 33);
        k *= 0xff51afd7ed558ccdL;
        k ^= (k >>> 33);
        return (int) k & mask;
    }

    boolean contem(long k) {
        int m = tab.length - 1;
        for (int i = h(k, m); usado[i]; i = (i + 1) & m) {
            if (tab[i] == k) {
                return true;
            }
        }
        return false;
    }

    /** true se entrou agora (nao estava e coube). */
    boolean adicionar(long k) {
        if (contem(k) || n >= teto) {
            return false;
        }
        if ((n + 1) * 2 > tab.length) {
            long[] ot = tab;
            boolean[] ou = usado;
            tab = new long[ot.length * 2];
            usado = new boolean[ot.length * 2];
            n = 0;
            for (int i = 0; i < ot.length; i++) {
                if (ou[i]) {
                    inserir(ot[i]);
                }
            }
        }
        inserir(k);
        return true;
    }

    private void inserir(long k) {
        int m = tab.length - 1;
        int i = h(k, m);
        while (usado[i]) {
            i = (i + 1) & m;
        }
        tab[i] = k;
        usado[i] = true;
        n++;
    }

    int tamanho() {
        return n;
    }

    boolean cheio() {
        return n >= teto;
    }

    void limpar() {
        java.util.Arrays.fill(usado, false);
        n = 0;
    }
}
