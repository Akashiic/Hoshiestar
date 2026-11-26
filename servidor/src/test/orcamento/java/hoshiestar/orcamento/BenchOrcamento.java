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

import hoshiestar.regioes.FotoMundo;
import hoshiestar.teste.Verifica;

/** Custo do caminho quente do nucleo em ns (indicacao deste computador, sem Minecraft; NAO e medicao de servidor). */
public final class BenchOrcamento {

    public static void main(String[] args) {
        Verifica v = new Verifica("BenchOrcamento");
        TesteOrcamento.JogoFalso j = new TesteOrcamento.JogoFalso();
        j.foto = new FotoMundo[] {TesteOrcamento.foto(0, null, 0, 20, 40, 60)};
        Nucleo n = new Nucleo(Parametros.padrao(), j, Tempo.SISTEMA, false, false);
        n.inicioTick();
        int reps = 5_000_000;
        double mesma = Double.MAX_VALUE, troca = Double.MAX_VALUE, pura = Double.MAX_VALUE;
        boolean x = false;
        for (int rodada = 0; rodada < 5; rodada++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < reps; i++) {
                x ^= n.tile(0, 20 + (i & 63), 20);
            }
            mesma = Math.min(mesma, (System.nanoTime() - t0) / (double) reps);
            t0 = System.nanoTime();
            for (int i = 0; i < reps; i++) {
                x ^= n.tile(0, ((i & 3) * 20) * 256 + 20, 20);
            }
            troca = Math.min(troca, (System.nanoTime() - t0) / (double) reps);
            t0 = System.nanoTime();
            for (int i = 0; i < reps; i++) {
                x ^= n.rodaEm(0, ((i & 3) * 20) * 256 + 20, 20);
            }
            pura = Math.min(pura, (System.nanoTime() - t0) / (double) reps);
        }
        n.fimLaco();
        System.out.println("  indicacao (melhor de 5 rodadas, este computador, sem Minecraft; NAO e medicao de servidor):");
        System.out.printf("    tile na mesma secao (o caso comum):      %.1f ns/chamada%n", mesma);
        System.out.printf("    tile trocando de regiao (le o relogio):  %.1f ns/chamada (inclui nanoTime)%n", troca);
        System.out.printf("    rodaEm (pura, sem relogio):              %.1f ns/chamada%n", pura);
        // varredura da selecao dos agendados com acumulo guardado (achado 7 da revisao): 20 mil vencidas de uma regiao
        // cortada fora da rodada dela + 50 de regioes normais; custo por tick da selecao
        java.util.TreeSet<TesteCorte.Ag> fila = new java.util.TreeSet<TesteCorte.Ag>();
        for (int i = 0; i < 20000; i++) {
            fila.add(new TesteCorte.Ag(i & 255, 64, (i >> 8) & 255, "b", 1 + (i % 50), 0, i));
        }
        for (int i = 0; i < 50; i++) {
            fila.add(new TesteCorte.Ag(20 * 256 + i, 64, 0, "b", 60, 0, 100000 + i));
        }
        PuxadaAgendada.Decisor dec = new PuxadaAgendada.Decisor() {
            @Override
            public boolean roda(int bx, int bz) {
                return (bx >> 8) != 0;
            }
        };
        java.util.List<Object> saida = new java.util.ArrayList<Object>();
        double varredura = Double.MAX_VALUE;
        int sel = 0;
        for (int rodada = 0; rodada < 5; rodada++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < 200; i++) {
                saida.clear();
                sel += PuxadaAgendada.selecionar(fila, PuxadaAgendada.teto(fila.size()), 100, TesteCorte.ACESSO, dec, saida);
            }
            varredura = Math.min(varredura, (System.nanoTime() - t0) / 200.0 / 1000.0);
        }
        System.out.printf("    selecao dos agendados com 20 mil guardados: %.1f us/tick (50 selecionadas)%n", varredura);
        v.sim(sel == 5 * 200 * 50, "bench: a selecao achou as 50 normais atras das 20 mil guardadas");
        v.sim(x || !x, "bench: rodou");
        v.sim(mesma < 200, "bench: tile na mesma secao custa menos de 200 ns: " + mesma);
        v.sim(troca < 2000, "bench: tile trocando de regiao custa menos de 2000 ns: " + troca);
        v.fim();
    }
}
