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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Os numeros do orcamento (do bloco {@code orcamento} do hoshiestar.yml), ja conferidos: valor fora da faixa vira o
 * padrao e entra em {@code avisos}. Imutavel. So JDK.
 */
public final class Parametros {

    /** Uma area protegida: dimensao e caixa em blocos (inclusive). */
    public static final class Area {
        public final int dim, x1, z1, x2, z2;

        Area(int dim, int x1, int z1, int x2, int z2) {
            this.dim = dim;
            this.x1 = Math.min(x1, x2);
            this.z1 = Math.min(z1, z2);
            this.x2 = Math.max(x1, x2);
            this.z2 = Math.max(z1, z2);
        }
    }

    /** Media do tick do servidor (ms, em {@link #janelaTicks} ticks) acima da qual o orcamento age. */
    public final double alvoTickMs;
    /** Media por tick real (ms) que uma regiao pode usar. */
    public final double regiaoMs;
    /** Rodada acima disto (ms) obriga N &gt;= 100 / picoFracaoPct. */
    public final double picoMs;
    public final int picoFracaoPct;
    public final int nMaximo;
    public final int recalculoTicks;
    public final boolean protegerSpawn;
    public final int[] nuncaDimensoes;
    public final List<Area> nuncaAreas;
    public final String[] nuncaMods;
    public final int[] sempreJuntoDimensoes;
    public final boolean cortarComJogador;
    public final double alertaMs;
    public final int alertaIntervaloMin;
    public final boolean alertaOps;

    // constantes de projeto (nao sao chave de config)
    /** Tamanho da janela de avaliacao (ticks). */
    public static final int JANELA_TICKS = 100;
    /** Servidor abaixo desta fracao do alvo conta como folgado. */
    public static final double FOLGA_FRACAO = 0.80;
    /** Ticks seguidos de folga para N cair a metade (e de custo menor que N para a regiao descer). */
    public static final int FOLGA_TICKS = 200;
    /** Recalculo antecipado: no minimo tantos ticks depois do ultimo. */
    public static final int RECALCULO_MINIMO_TICKS = 20;
    /** No maximo tantos alertas por minuto, no total. */
    public static final int ALERTAS_POR_MINUTO = 10;

    public Parametros(double alvoTickMs, double regiaoMs, double picoMs, int picoFracaoPct, int nMaximo,
            int recalculoTicks, boolean protegerSpawn, int[] nuncaDimensoes, List<Area> nuncaAreas, String[] nuncaMods,
            int[] sempreJuntoDimensoes, boolean cortarComJogador, double alertaMs, int alertaIntervaloMin,
            boolean alertaOps) {
        this.alvoTickMs = alvoTickMs;
        this.regiaoMs = regiaoMs;
        this.picoMs = picoMs;
        this.picoFracaoPct = picoFracaoPct;
        this.nMaximo = nMaximo;
        this.recalculoTicks = recalculoTicks;
        this.protegerSpawn = protegerSpawn;
        this.nuncaDimensoes = nuncaDimensoes;
        this.nuncaAreas = nuncaAreas;
        this.nuncaMods = nuncaMods;
        this.sempreJuntoDimensoes = sempreJuntoDimensoes;
        this.cortarComJogador = cortarComJogador;
        this.alertaMs = alertaMs;
        this.alertaIntervaloMin = alertaIntervaloMin;
        this.alertaOps = alertaOps;
    }

    /** Os padroes do plano (secao 2 e 6). */
    public static Parametros padrao() {
        return ler(45.0, 10.0, 40.0, 4, 40, 100, true, "", "", "", "", true, 25.0, 5, true, new ArrayList<String>());
    }

    /** N minimo que a regra do pico obriga: 100 / picoFracaoPct, arredondado para cima. */
    public int nDoPico() {
        return Math.min(nMaximo, (100 + picoFracaoPct - 1) / picoFracaoPct);
    }

    public static Parametros ler(double alvoTickMs, double regiaoMs, double picoMs, int picoFracaoPct, int nMaximo,
            int recalculoTicks, boolean protegerSpawn, String nuncaDimensoes, String nuncaAreas, String nuncaMods,
            String sempreJunto, boolean cortarComJogador, double alertaMs, int alertaIntervaloMin, boolean alertaOps,
            List<String> avisos) {
        double alvo = faixa("alvo_tick_ms", alvoTickMs, 10.0, 50.0, 45.0, avisos);
        double reg = faixa("regiao_ms", regiaoMs, 0.5, 50.0, 10.0, avisos);
        double pico = faixa("pico_ms", picoMs, 5.0, 1000.0, 40.0, avisos);
        int frac = (int) faixa("pico_fracao_pct", picoFracaoPct, 1, 50, 4, avisos);
        int nMax = (int) faixa("n_maximo", nMaximo, 2, 200, 40, avisos);
        int rec = (int) faixa("recalculo_ticks", recalculoTicks, 40, 2400, 100, avisos);
        double alerta = faixa("alerta_ms", alertaMs, 1.0, 1000.0, 25.0, avisos);
        int interv = (int) faixa("alerta_intervalo_min", alertaIntervaloMin, 1, 1440, 5, avisos);
        return new Parametros(alvo, reg, pico, frac, nMax, rec, protegerSpawn,
                inteiros("nunca_cortar_dimensoes", nuncaDimensoes, avisos), areas(nuncaAreas, avisos),
                palavras(nuncaMods), inteiros("sempre_junto_dimensoes", sempreJunto, avisos), cortarComJogador, alerta,
                interv, alertaOps);
    }

    private static double faixa(String nome, double v, double min, double max, double padrao, List<String> avisos) {
        if (v >= min && v <= max) {
            return v;
        }
        avisos.add("orcamento." + nome + " = " + v + " fora da faixa " + min + " a " + max + "; usando " + padrao);
        return padrao;
    }

    private static int[] inteiros(String nome, String texto, List<String> avisos) {
        List<Integer> r = new ArrayList<Integer>();
        if (texto != null) {
            for (String p : texto.split(",")) {
                p = p.trim();
                if (p.isEmpty()) {
                    continue;
                }
                try {
                    r.add(Integer.valueOf(p));
                } catch (NumberFormatException e) {
                    avisos.add("orcamento." + nome + ": '" + p + "' nao e um numero de dimensao; ignorado");
                }
            }
        }
        int[] a = new int[r.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = r.get(i);
        }
        return a;
    }

    private static String[] palavras(String texto) {
        List<String> r = new ArrayList<String>();
        if (texto != null) {
            for (String p : texto.split(",")) {
                p = p.trim().toLowerCase(Locale.ROOT);
                if (!p.isEmpty()) {
                    r.add(p);
                }
            }
        }
        return r.toArray(new String[0]);
    }

    /** Formato: {@code dim:x1,z1,x2,z2;dim:x1,z1,x2,z2}. */
    private static List<Area> areas(String texto, List<String> avisos) {
        List<Area> r = new ArrayList<Area>();
        if (texto == null) {
            return r;
        }
        for (String item : texto.split(";")) {
            item = item.trim();
            if (item.isEmpty()) {
                continue;
            }
            try {
                String[] dp = item.split(":");
                String[] n = dp[1].split(",");
                if (dp.length != 2 || n.length != 4) {
                    throw new IllegalArgumentException();
                }
                r.add(new Area(Integer.parseInt(dp[0].trim()), Integer.parseInt(n[0].trim()),
                        Integer.parseInt(n[1].trim()), Integer.parseInt(n[2].trim()), Integer.parseInt(n[3].trim())));
            } catch (RuntimeException e) {
                avisos.add("orcamento.nunca_cortar_areas: '" + item + "' nao esta no formato dim:x1,z1,x2,z2; ignorada");
            }
        }
        return r;
    }
}
