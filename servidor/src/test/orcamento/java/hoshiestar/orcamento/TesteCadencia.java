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

import hoshiestar.teste.Verifica;

import java.util.HashSet;
import java.util.Set;

/** Cadencia (sequencia de Beatty), numero N por custo e limitador de alertas. Funcoes puras, sem Minecraft. */
public final class TesteCadencia {

    public static void main(String[] args) {
        Verifica v = new Verifica("TesteCadencia");
        try {
            restos(v);
            intervalos(v);
            nParaCusto(v);
            limitador(v);
        } catch (Throwable t) {
            v.erro("excecao no teste", t);
        }
        v.fim();
    }

    /** Roda a cadencia por {@code ticks} ticks e devolve os ticks em que rodou. */
    static long[] rodadas(int n, double fase, int ticks) {
        long[] r = new long[ticks];
        int k = 0;
        double acc = fase;
        for (int t = 0; t < ticks; t++) {
            if (Cadencia.tica(acc, n)) {
                r[k++] = t;
            }
            acc = Cadencia.depois(acc, n);
        }
        return java.util.Arrays.copyOf(r, k);
    }

    static void restos(Verifica v) {
        int[] ns = {2, 4, 5, 10, 20, 25, 40};
        int[] mods = {2, 4, 5, 10, 20, 40, 100};
        for (int n : ns) {
            long[] r = rodadas(n, 0.3, 4_000_000);
            for (int m : mods) {
                long[] cont = new long[m];
                for (long t : r) {
                    cont[(int) (t % m)]++;
                }
                long esperado = r.length / m;
                long min = Long.MAX_VALUE, max = 0;
                for (long c : cont) {
                    min = Math.min(min, c);
                    max = Math.max(max, c);
                }
                v.sim(min > 0 && min >= esperado * 0.7 && max <= esperado * 1.3 + 2,
                        "N=" + n + " modulo " + m + ": todos os restos aparecem, parecidos (min " + min + ", max " + max
                                + ", esperado " + esperado + ")");
            }
        }
    }

    static void intervalos(Verifica v) {
        for (int n : new int[] {2, 3, 7, 25, 40, 200}) {
            long[] r = rodadas(n, 0.0, 200_000);
            Set<Long> dist = new HashSet<Long>();
            for (int i = 1; i < r.length; i++) {
                dist.add(r[i] - r[i - 1]);
            }
            v.sim(dist.size() <= 2 && dist.contains((long) n) && (dist.size() == 1 || dist.contains((long) n + 1)),
                    "N=" + n + ": intervalos so N ou N+1 " + dist);
            double media = (double) 200_000 / r.length;
            v.sim(Math.abs(media - (n + Cadencia.ALFA)) < n * 0.003 + 0.01, "N=" + n + ": intervalo medio " + media + " perto de N+alfa");
        }
        v.sim(Cadencia.tica(0.0, 1) && Cadencia.tica(0.99, 1) && Cadencia.tica(0.5, 0), "N <= 1 sempre roda");
        v.igual(0.0, Cadencia.depois(0.7, 1), "N = 1 zera o acumulador");
        double acc = 0.5;
        boolean ok = true;
        for (int i = 0; i < 100000; i++) {
            acc = Cadencia.depois(acc, 2 + i % 39);
            ok &= acc >= 0.0 && acc < 1.0;
        }
        v.sim(ok, "o acumulador fica sempre em [0, 1), mesmo com N mudando a cada tick");
        // mudar N: nunca roda duas vezes seguidas com N >= 2 (taxa < 1/2)
        boolean junto = false;
        double a = 0.0;
        boolean ultimo = false;
        for (int i = 0; i < 100000; i++) {
            int n = 2 + (i / 37) % 39;
            boolean roda = Cadencia.tica(a, n);
            a = Cadencia.depois(a, n);
            if (roda && ultimo && i % 37 != 0) {
                junto = true;
            }
            ultimo = roda;
        }
        v.sim(!junto, "N entre 2 e 40 mudando de 37 em 37 ticks: sem rodadas coladas fora da troca");
        v.sim(Cadencia.faseInicial(1) != Cadencia.faseInicial(2) && Cadencia.faseInicial(-5) >= 0 && Cadencia.faseInicial(7) < 1,
                "fase inicial espalha as regioes e fica em [0, 1)");
        // pureza: mesma entrada, mesma saida
        v.sim(Cadencia.tica(0.4, 5) == Cadencia.tica(0.4, 5) && Cadencia.depois(0.4, 5) == Cadencia.depois(0.4, 5), "funcoes puras");
    }

    static void nParaCusto(Verifica v) {
        v.igual(1, Cadencia.nParaCusto(0, 10, 40, 4, 40), "custo 0: N = 1");
        v.igual(1, Cadencia.nParaCusto(10.0, 10, 40, 4, 40), "custo igual ao orcamento: N = 1");
        v.igual(2, Cadencia.nParaCusto(10.01, 10, 40, 4, 40), "custo um pouco acima: N = 2 (minimo do corte)");
        v.igual(2, Cadencia.nParaCusto(20, 10, 40, 4, 40), "custo 20: N = 2");
        v.igual(4, Cadencia.nParaCusto(35, 10, 40, 4, 40), "custo 35: ceil(3,5) = 4");
        v.igual(4, Cadencia.nParaCusto(40, 10, 40, 4, 40), "custo 40 (nao passa do pico): 4");
        v.igual(25, Cadencia.nParaCusto(41, 10, 40, 4, 40), "rodada acima de 40 ms obriga N >= 25 (4% dos ticks)");
        v.igual(25, Cadencia.nParaCusto(150, 10, 40, 4, 40), "150 ms: ceil(15) = 15, pico obriga 25");
        v.igual(40, Cadencia.nParaCusto(400, 10, 40, 4, 40), "400 ms: ceil(40) = 40");
        v.igual(40, Cadencia.nParaCusto(5000, 10, 40, 4, 40), "custo enorme: limitado a n_maximo");
        v.igual(10, Cadencia.nParaCusto(50, 10, 40, 10, 40), "pico_fracao 10%: N >= 10 (e ceil(5) = 5): 10");
        v.igual(12, Cadencia.nParaCusto(50, 10, 40, 4, 12), "n_maximo 12 limita o pico de 25");
    }

    static void limitador(Verifica v) {
        LimiteAlertas l = new LimiteAlertas(5 * 60000L, 10);
        v.sim(l.permitir(1, 1, 0), "primeiro alerta passa");
        v.sim(!l.permitir(1, 1, 60000), "mesma regiao e tipo dentro de 5 min: barrado");
        v.sim(l.permitir(1, 2, 60000), "mesma regiao, outro tipo: passa");
        v.sim(l.permitir(2, 1, 60000), "outra regiao: passa");
        v.sim(l.permitir(1, 1, 5 * 60000L), "depois de 5 min: passa de novo");
        LimiteAlertas g = new LimiteAlertas(1000L, 10);
        int passou = 0;
        for (int i = 0; i < 25; i++) {
            if (g.permitir(100 + i, 1, 1000L + i)) {
                passou++;
            }
        }
        v.igual(10, passou, "no maximo 10 por minuto no total");
        v.sim(!g.permitir(500, 1, 1000L + 59000), "ainda dentro do minuto: barrado");
        v.sim(g.permitir(501, 1, 1000L + 61000), "passado o minuto: passa");
        // muitas regioes diferentes: nao explode a memoria
        LimiteAlertas m = new LimiteAlertas(1000L, 10);
        for (int i = 0; i < 5000; i++) {
            m.permitir(i, 1, i * 70000L);
        }
        v.sim(true, "5000 regioes distintas: sem erro");
    }
}
