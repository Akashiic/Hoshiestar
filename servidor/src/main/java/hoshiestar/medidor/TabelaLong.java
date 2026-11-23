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
package hoshiestar.medidor;

/**
 * Tabela de espalhamento aberta, chave long, com quatro contadores por chave
 * (ns e ticks de tile entity, ns e ticks de entidade). Sem objetos por entrada, sem
 * boxing. Contadores de ticks em long (nao saturam). Tem limite de chaves distintas: passado o limite, o que seria uma chave nova
 * vai para o balde "outros" e {@link #transbordou()} fica verdadeiro.
 *
 * So e usada pela thread principal enquanto a medicao corre; depois, so pela thread
 * que grava o relatorio (a troca acontece antes de Thread.start, que da a ordem).
 */
final class TabelaLong {

    static final int TE = 0;
    static final int ENT = 1;

    private final int limite;
    private int tamanho;
    private int mascara;
    private long[] chaves;
    private boolean[] usado;
    private long[] teNs;
    private long[] entNs;
    private long[] teTicks;
    private long[] entTicks;

    // balde "outros"
    long outrosTeNs;
    long outrosEntNs;
    long outrosTeTicks;
    long outrosEntTicks;
    long chavesRecusadas;

    TabelaLong(int capacidadeInicial, int limite) {
        int cap = 16;
        while (cap < capacidadeInicial) {
            cap <<= 1;
        }
        this.limite = limite;
        alocar(cap);
    }

    private void alocar(int cap) {
        chaves = new long[cap];
        usado = new boolean[cap];
        teNs = new long[cap];
        entNs = new long[cap];
        teTicks = new long[cap];
        entTicks = new long[cap];
        mascara = cap - 1;
    }

    private static int espalhar(long k) {
        k ^= (k >>> 33);
        k *= 0xff51afd7ed558ccdL;
        k ^= (k >>> 33);
        k *= 0xc4ceb9fe1a85ec53L;
        k ^= (k >>> 33);
        return (int) k;
    }

    void somar(long chave, int tipo, long ns) {
        int i = espalhar(chave) & mascara;
        while (usado[i]) {
            if (chaves[i] == chave) {
                acumular(i, tipo, ns);
                return;
            }
            i = (i + 1) & mascara;
        }
        if (tamanho >= limite) {
            chavesRecusadas++;
            if (tipo == TE) {
                outrosTeNs += ns;
                outrosTeTicks++;
            } else {
                outrosEntNs += ns;
                outrosEntTicks++;
            }
            return;
        }
        usado[i] = true;
        chaves[i] = chave;
        tamanho++;
        acumular(i, tipo, ns);
        if (tamanho * 2 > chaves.length) {
            crescer();
        }
    }

    /** Soma direto no balde "outros" (por exemplo, coordenada fora da faixa da chave). */
    void somarOutros(int tipo, long ns) {
        if (tipo == TE) {
            outrosTeNs += ns;
            outrosTeTicks++;
        } else {
            outrosEntNs += ns;
            outrosEntTicks++;
        }
    }

    private void acumular(int i, int tipo, long ns) {
        if (tipo == TE) {
            teNs[i] += ns;
            teTicks[i]++;
        } else {
            entNs[i] += ns;
            entTicks[i]++;
        }
    }

    private void crescer() {
        long[] k0 = chaves;
        boolean[] u0 = usado;
        long[] a0 = teNs;
        long[] b0 = entNs;
        long[] c0 = teTicks;
        long[] d0 = entTicks;
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
            teNs[i] = a0[j];
            entNs[i] = b0[j];
            teTicks[i] = c0[j];
            entTicks[i] = d0[j];
        }
    }

    int tamanho() {
        return tamanho;
    }

    int limite() {
        return limite;
    }

    boolean transbordou() {
        return chavesRecusadas > 0;
    }

    // ---- leitura (thread de gravacao) ----

    int capacidade() {
        return chaves.length;
    }

    boolean ocupado(int i) {
        return usado[i];
    }

    long chave(int i) {
        return chaves[i];
    }

    long teNs(int i) {
        return teNs[i];
    }

    long entNs(int i) {
        return entNs[i];
    }

    long teTicks(int i) {
        return teTicks[i];
    }

    long entTicks(int i) {
        return entTicks[i];
    }
}
