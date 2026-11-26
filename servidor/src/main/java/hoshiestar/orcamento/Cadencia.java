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

/**
 * A cadencia de uma regiao cortada, em funcoes puras (sem relogio, sem estado escondido): "esta regiao tica neste tick?".
 *
 * <p>Sem fase fixa: a regiao nao roda em {@code tick % N == 0} (uma maquina que age em {@code tempo % 20 == 0} nunca agiria
 * com N par). Um acumulador soma a taxa {@code 1 / (N + ALFA)}; com ALFA irracional os intervalos entre rodadas sao N ou
 * N+1 e as rodadas caem em todos os restos de qualquer modulo (sequencia de Beatty). N = 1 roda todo tick.
 */
public final class Cadencia {

    /** Parte fracionaria da razao aurea (0,6180339887...) dividida por 10: pequena e irracional. */
    public static final double ALFA = 0.0618033988749895;
    private static final double RAZAO_AUREA = 0.6180339887498949;

    private Cadencia() {
    }

    public static double taxa(int n) {
        return 1.0 / (n + ALFA);
    }

    /** Roda neste tick? {@code acc} e o acumulador antes do tick (0 &lt;= acc &lt; 1). N &lt;= 1 sempre roda. */
    public static boolean tica(double acc, int n) {
        return n <= 1 || acc + taxa(n) >= 1.0;
    }

    /** O acumulador depois do tick (sempre em [0, 1)). N &lt;= 1 zera. */
    public static double depois(double acc, int n) {
        if (n <= 1) {
            return 0.0;
        }
        double a = acc + taxa(n);
        return a >= 1.0 ? a - 1.0 : a;
    }

    /** Fase inicial de uma regiao nova (espalha as regioes: nao rodam todas no mesmo tick). */
    public static double faseInicial(long id) {
        double f = (id * RAZAO_AUREA) % 1.0;
        return f < 0 ? f + 1.0 : f;
    }

    /**
     * Quantos ticks de intervalo uma regiao com custo por rodada {@code custoMs} precisa para caber em {@code orcMs} por
     * tick real: {@code ceil(custo / orcamento)}, entre 1 e {@code nMax}; rodada acima de {@code picoMs} obriga ao menos
     * {@code 100 / picoFracaoPct} (a rodada pesada cai em no maximo picoFracaoPct% dos ticks). 1 = nao precisa de corte.
     */
    public static int nParaCusto(double custoMs, double orcMs, double picoMs, int picoFracaoPct, int nMax) {
        if (!(custoMs > orcMs)) {
            return 1;
        }
        int n = (int) Math.ceil(custoMs / orcMs);
        if (custoMs > picoMs) {
            n = Math.max(n, (100 + picoFracaoPct - 1) / picoFracaoPct);
        }
        return Math.max(2, Math.min(n, nMax));
    }
}
