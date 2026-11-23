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
 * Tabela de espalhamento aberta, chave long, dois contadores por chave: quantas vezes a chave apareceu e o maior valor
 * visto (por exemplo, a maior distancia). Sem objeto por entrada. Tem teto de chaves distintas: passado o teto, o que
 * seria uma chave nova vai para o balde "outros" e {@link #transbordou()} fica verdadeiro (o relatorio avisa).
 *
 * <p>Nao e segura entre threads: so a thread do servidor escreve; a thread de gravacao le depois que o observador
 * parou (Thread.start da a ordem).
 */
final class TabelaContagem {

    private final int teto;
    private int tamanho;
    private int mascara;
    private long[] chaves;
    private boolean[] usado;
    private long[] cont;
    private long[] max;

    /** Soma do balde "outros" (chaves recusadas por causa do teto). */
    long outrosCont;
    long outrosMax;
    long chavesRecusadas;

    TabelaContagem(int capacidadeInicial, int teto) {
        int cap = 16;
        while (cap < capacidadeInicial) {
            cap <<= 1;
        }
        this.teto = Math.max(1, teto);
        alocar(cap);
    }

    private void alocar(int cap) {
        chaves = new long[cap];
        usado = new boolean[cap];
        cont = new long[cap];
        max = new long[cap];
        mascara = cap - 1;
    }

    static int espalhar(long k) {
        k ^= (k >>> 33);
        k *= 0xff51afd7ed558ccdL;
        k ^= (k >>> 33);
        k *= 0xc4ceb9fe1a85ec53L;
        k ^= (k >>> 33);
        return (int) k;
    }

    /** Conta mais uma vez a chave e guarda o maior {@code valor} visto. */
    void registrar(long chave, long valor) {
        int i = espalhar(chave) & mascara;
        while (usado[i]) {
            if (chaves[i] == chave) {
                cont[i]++;
                if (valor > max[i]) {
                    max[i] = valor;
                }
                return;
            }
            i = (i + 1) & mascara;
        }
        if (tamanho >= teto) {
            chavesRecusadas++;
            outrosCont++;
            if (valor > outrosMax) {
                outrosMax = valor;
            }
            return;
        }
        usado[i] = true;
        chaves[i] = chave;
        cont[i] = 1;
        max[i] = valor;
        tamanho++;
        if (tamanho * 2 > chaves.length) {
            crescer();
        }
    }

    void somar(long chave) {
        registrar(chave, 0);
    }

    private void crescer() {
        long[] k0 = chaves;
        boolean[] u0 = usado;
        long[] c0 = cont;
        long[] m0 = max;
        alocar(k0.length << 1);
        for (int j = 0; j < k0.length; j++) {
            if (!u0[j]) {
                continue;
            }
            int i = espalhar(k0[j]) & mascara;
            while (usado[i]) {
                i = (i + 1) & mascara;
            }
            usado[i] = true;
            chaves[i] = k0[j];
            cont[i] = c0[j];
            max[i] = m0[j];
        }
    }

    int tamanho() {
        return tamanho;
    }

    int teto() {
        return teto;
    }

    boolean transbordou() {
        return chavesRecusadas > 0;
    }

    /** Soma de todos os contadores, o balde "outros" incluido. */
    long total() {
        long t = outrosCont;
        for (int i = 0; i < chaves.length; i++) {
            if (usado[i]) {
                t += cont[i];
            }
        }
        return t;
    }

    // ---- leitura (thread de gravacao, depois que o observador parou) ----

    /** Quantas vezes a chave foi registrada (0 se nao esta na tabela). */
    long contDe(long chave) {
        int i = espalhar(chave) & mascara;
        while (usado[i]) {
            if (chaves[i] == chave) {
                return cont[i];
            }
            i = (i + 1) & mascara;
        }
        return 0;
    }

    /** O maior valor visto para a chave (0 se nao esta na tabela). */
    long maxDe(long chave) {
        int i = espalhar(chave) & mascara;
        while (usado[i]) {
            if (chaves[i] == chave) {
                return max[i];
            }
            i = (i + 1) & mascara;
        }
        return 0;
    }

    int capacidade() {
        return chaves.length;
    }

    boolean ocupado(int i) {
        return usado[i];
    }

    long chave(int i) {
        return chaves[i];
    }

    long cont(int i) {
        return cont[i];
    }

    long max(int i) {
        return max[i];
    }
}
