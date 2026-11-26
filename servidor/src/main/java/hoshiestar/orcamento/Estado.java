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

import java.util.Arrays;

/**
 * O estado de uma regiao entre recalculos do mapa (so a thread do servidor mexe). O estado fica preso as secoes: quando o
 * mapa e recalculado, a {@link Heranca} passa os objetos de uma regiao velha para a nova que ficou com as secoes dela.
 */
final class Estado {

    static final int ANEL_RODADAS = 100;
    static final int ANEL_CARIMBOS = 1024;
    /** Janela do TPS da regiao, em ms. */
    static final long JANELA_TPS_MS = 20000L;

    long id;
    /** Intervalo atual: a regiao roda 1 vez a cada N ticks (1 = normal). */
    int n = 1;
    double acc;
    /** Ticks seguidos em que o custo pedia menos que N (para descer). */
    int ticksMenor;
    boolean pesada;
    /** O ultimo alerta de corte que saiu disse "em corte" (CORTE) e nao "voltou" (FIM_CORTE). */
    boolean avisadoCorte;
    int periodosLeves;
    long nascMs;

    // protecao (recalculada a cada mapa novo)
    String protecao;
    boolean suspensa;

    // contabilidade do tick
    long nsTick;
    boolean rodouNoTick = true;

    // periodo de avaliacao
    long perTodosNs;
    long perRodadaNs;
    int perRodadas;
    int perTicks;
    double custoRodadaMs;
    double custoRealMs;
    boolean temMedida;

    private final long[] rodadaNs = new long[ANEL_RODADAS];
    private int rodadaN, rodadaPos;
    private final long[] carimbos = new long[ANEL_CARIMBOS];
    private int carimboN, carimboPos;
    double maxRodadaMs;

    // ferramenta de teste
    double esperaMs;
    boolean esperouNaRodada;

    Estado(long id, long agoraMs) {
        this.id = id;
        this.nascMs = agoraMs;
        this.acc = Cadencia.faseInicial(id);
    }

    /** Fim de um tick do servidor: {@code rodou} diz se a regiao rodou; {@code nsTick} e o custo medido nele. */
    void fimTick(boolean rodou, long agoraMs) {
        rodouNoTick = rodou;
        perTicks++;
        perTodosNs += nsTick;
        if (rodou) {
            perRodadaNs += nsTick;
            perRodadas++;
            rodadaNs[rodadaPos] = nsTick;
            rodadaPos = (rodadaPos + 1) % ANEL_RODADAS;
            if (rodadaN < ANEL_RODADAS) {
                rodadaN++;
            }
            carimbos[carimboPos] = agoraMs;
            carimboPos = (carimboPos + 1) % ANEL_CARIMBOS;
            if (carimboN < ANEL_CARIMBOS) {
                carimboN++;
            }
            double ms = nsTick / 1e6;
            if (ms > maxRodadaMs) {
                maxRodadaMs = ms;
            }
        }
    }

    /** Fecha o periodo (calcula as medias) e zera os acumuladores. */
    void fecharPeriodo() {
        if (perTicks > 0) {
            custoRealMs = perTodosNs / 1e6 / perTicks;
            temMedida = true;
        }
        if (perRodadas > 0) {
            custoRodadaMs = perRodadaNs / 1e6 / perRodadas;
        }
        perTodosNs = 0;
        perRodadaNs = 0;
        perRodadas = 0;
        perTicks = 0;
    }

    /** Descarta a estatistica (acumuladores e aneis) mas mantem N, folga, alerta e fase: a regiao mudou de forma. */
    void reiniciarEstatistica() {
        perTodosNs = 0;
        perRodadaNs = 0;
        perRodadas = 0;
        perTicks = 0;
        rodadaN = 0;
        rodadaPos = 0;
        carimboN = 0;
        carimboPos = 0;
        maxRodadaMs = 0;
    }

    /** p95 do custo das ultimas (ate 100) rodadas, em ms; 0 se nao ha rodada. */
    double p95RodadaMs() {
        if (rodadaN == 0) {
            return 0;
        }
        long[] c = Arrays.copyOf(rodadaNs, rodadaN);
        Arrays.sort(c);
        int i = (int) Math.ceil(0.95 * rodadaN) - 1;
        return c[Math.max(0, Math.min(i, rodadaN - 1))] / 1e6;
    }

    /** Rodadas por segundo de relogio, nos ultimos 20 s (ou desde que a regiao existe, no minimo 1 s). */
    double tps(long agoraMs) {
        int dentro = 0;
        for (int i = 0; i < carimboN; i++) {
            if (agoraMs - carimbos[i] <= JANELA_TPS_MS) {
                dentro++;
            }
        }
        double janela = Math.min(JANELA_TPS_MS, Math.max(1000L, agoraMs - nascMs)) / 1000.0;
        return dentro / janela;
    }

    /** Junta o estado de outra regiao que se juntou a esta: vale o maior N e as maiores medidas. */
    void mesclar(Estado o) {
        if (o.n > n) {
            n = o.n;
            acc = o.acc;
        }
        ticksMenor = Math.max(ticksMenor, o.ticksMenor);
        pesada |= o.pesada;
        avisadoCorte |= o.avisadoCorte;
        periodosLeves = Math.max(periodosLeves, o.periodosLeves);
        custoRodadaMs = Math.max(custoRodadaMs, o.custoRodadaMs);
        custoRealMs = Math.max(custoRealMs, o.custoRealMs);
        temMedida |= o.temMedida;
        esperaMs = Math.max(esperaMs, o.esperaMs);
        nascMs = Math.min(nascMs, o.nascMs);
    }

    /** Copia o que a divisao passa para cada parte (id novo). */
    Estado paraDivisao(long novoId, long agoraMs) {
        Estado e = new Estado(novoId, agoraMs);
        e.n = n;
        e.acc = acc;
        e.ticksMenor = ticksMenor;
        e.pesada = pesada;
        e.avisadoCorte = avisadoCorte;
        e.periodosLeves = periodosLeves;
        e.custoRodadaMs = custoRodadaMs;
        e.custoRealMs = custoRealMs;
        e.temMedida = temMedida;
        e.esperaMs = esperaMs;
        e.nascMs = nascMs;
        return e;
    }
}
