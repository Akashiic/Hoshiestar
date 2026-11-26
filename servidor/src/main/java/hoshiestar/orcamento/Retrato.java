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

import hoshiestar.regioes.Mapa;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Foto imutavel do orcamento, publicada pela thread do servidor (a cada avaliacao e a cada mapa novo) e lida pelo comando
 * de qualquer thread. Nada aqui muda depois de pronto.
 */
public final class Retrato {

    /** Uma regiao. */
    public static final class Regiao {
        public final long id;
        public final int dim;
        /** {xMin, zMin, xMax, zMax} em blocos. */
        public final int[] caixa;
        public final int secoes, chunks, tiles, ents;
        public final int n;
        /** "normal", "cortada", "estouraria" (medir), "protegida" ou "suspensa". */
        public final String estado;
        public final String protecao;
        public final double tps, custoRodadaMs, custoRealMs, p95RodadaMs, maxRodadaMs;
        public final boolean pesada;
        /** As classes de tile mais comuns: "classe=quantidade". */
        public final String[] classes;
        public final double esperaMs;

        Regiao(long id, int dim, int[] caixa, int secoes, int chunks, int tiles, int ents, int n, String estado,
                String protecao, double tps, double custoRodadaMs, double custoRealMs, double p95RodadaMs,
                double maxRodadaMs, boolean pesada, String[] classes, double esperaMs) {
            this.id = id;
            this.dim = dim;
            this.caixa = caixa;
            this.secoes = secoes;
            this.chunks = chunks;
            this.tiles = tiles;
            this.ents = ents;
            this.n = n;
            this.estado = estado;
            this.protecao = protecao;
            this.tps = tps;
            this.custoRodadaMs = custoRodadaMs;
            this.custoRealMs = custoRealMs;
            this.p95RodadaMs = p95RodadaMs;
            this.maxRodadaMs = maxRodadaMs;
            this.pesada = pesada;
            this.classes = classes;
            this.esperaMs = esperaMs;
        }

        public int centroX() {
            return (caixa[0] + caixa[2]) / 2;
        }

        public int centroZ() {
            return (caixa[1] + caixa[3]) / 2;
        }
    }

    public final boolean ligado;
    public final boolean cortar;
    public final long tick;
    /** Servidor inteiro: TPS (20 s), media dos ultimos 100 ticks, p95, p99 e maximo dos ultimos 1200 ticks (ms). */
    public final double servidorTps, servidorMediaMs, servidorP95Ms, servidorP99Ms, servidorMaxMs;
    public final int servidorTicksNaJanela;
    public final int recalculos;
    public final Mapa mapa;
    public final Regiao[] regioes;

    Retrato(boolean ligado, boolean cortar, long tick, double tps, double media, double p95, double p99, double max,
            int ticksNaJanela, int recalculos, Mapa mapa, Regiao[] regioes) {
        this.ligado = ligado;
        this.cortar = cortar;
        this.tick = tick;
        this.servidorTps = tps;
        this.servidorMediaMs = media;
        this.servidorP95Ms = p95;
        this.servidorP99Ms = p99;
        this.servidorMaxMs = max;
        this.servidorTicksNaJanela = ticksNaJanela;
        this.recalculos = recalculos;
        this.mapa = mapa;
        this.regioes = regioes;
    }

    /** A regiao que contem o ponto (bloco x, bloco z) da dimensao, ou null (sem regiao). */
    public Regiao emBloco(int dim, int bx, int bz) {
        if (mapa == null) {
            return null;
        }
        int r = mapa.regiaoNoBloco(dim, bx, bz);
        return r < 0 || r >= regioes.length ? null : regioes[r];
    }

    public Regiao porId(long id) {
        for (Regiao r : regioes) {
            if (r.id == id) {
                return r;
            }
        }
        return null;
    }

    /** As {@code k} regioes de maior custo por rodada (desempate pelo id). */
    public List<Regiao> maisPesadas(int k) {
        List<Regiao> l = new ArrayList<Regiao>();
        Collections.addAll(l, regioes);
        Collections.sort(l, new Comparator<Regiao>() {
            @Override
            public int compare(Regiao a, Regiao b) {
                int c = Double.compare(b.custoRodadaMs, a.custoRodadaMs);
                return c != 0 ? c : Long.compare(a.id, b.id);
            }
        });
        return l.size() > k ? new ArrayList<Regiao>(l.subList(0, k)) : l;
    }

    public int contar(String estado) {
        int c = 0;
        for (Regiao r : regioes) {
            if (r.estado.equals(estado)) {
                c++;
            }
        }
        return c;
    }
}
