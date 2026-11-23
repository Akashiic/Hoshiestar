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

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Custo do caminho ligado (abrir + fechar um quadro de tile entity pelos mesmos metodos que os ganchos chamam),
 * numa JVM sozinha, com 3.000 tile entities falsas em 200 chunks. E INDICACAO, nao medicao de servidor (no
 * servidor ha cache frio, mais classes e o proprio tick do jogo). Nao falha: so imprime.
 */
public final class BenchMedidor {

    public static void main(String[] args) throws Exception {
        final File pasta = new File(args.length > 0 ? args[0] : "bench-medidor").getAbsoluteFile();
        Jogo j = new Jogo() {
            @Override
            public boolean morto(Object o, int tipo) {
                return false;
            }

            @Override
            public List<Mundo> mundos() {
                return new ArrayList<Mundo>();
            }

            @Override
            public File pastaDeSaida() {
                return pasta;
            }
        };
        Medidor.definirJogo(j, "vanilla", "bench");
        Object mundo = new Object();
        final int n = 3000;
        Object[] tes = new Object[n];
        for (int i = 0; i < n; i++) {
            tes[i] = new Object();
        }
        long relogio = System.nanoTime();
        Medidor.inicioTick(relogio);
        Medidor.comandoMedir(1800, true);
        Medidor.fimTick(relogio += 1000000L);
        double melhor = Double.MAX_VALUE;
        for (int rodada = 0; rodada < 30; rodada++) {
            long t0 = System.nanoTime();
            for (int t = 0; t < 10; t++) {
                Medidor.inicioTick(relogio);
                Medidor.inicioLacoMundo(mundo, 0, n, 0);
                for (int i = 0; i < n; i++) {
                    Medidor.inicioTE(mundo, 0, tes[i], i % 200, i / 200);
                    Medidor.fimTE();
                }
                Medidor.fimTick(relogio += 1000000L);
            }
            long t1 = System.nanoTime();
            melhor = Math.min(melhor, (t1 - t0) / (10.0 * n));
        }
        Coleta c = Medidor.coletaAtual();
        System.out.println(String.format(java.util.Locale.ROOT, "[bench] caminho ligado: %.0f ns por tile entity medida"
                + " (abrir + fechar quadro, melhor de 30 rodadas; calibracao do proprio medidor: quadro vazio %.0f ns,"
                + " nanoTime %.0f ns). Indicacao numa JVM sozinha, nao medicao de servidor.", melhor, c.calibQuadroNs,
                c.calibNanoNs));
        Medidor.comandoParar();
        Medidor.esperarGravacao(30000L);
    }
}
