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
package hoshiestar.teste.parada;

import hoshiestar.parada.MaquinaDeParada;
import hoshiestar.teste.Verifica;

import java.util.Random;

/**
 * "stop" e SIGTERM ao mesmo tempo, milhares de vezes, com atrasos aleatorios. Em cada repeticao:
 * um servidor de mentira ticando, uma thread que manda o "stop" e outra que manda o SIGTERM, cada
 * uma depois de um atraso sorteado. Sempre tem de dar: exatamente UM salvamento (nunca zero, nunca
 * dois ao mesmo tempo) e nenhuma espera acima do limite.
 *
 * <p>Dois modos sorteados: "normal" (T1 grande: o gancho nunca desiste, entao o salvamento tem de
 * ser na thread do servidor e sem tick no meio) e "desistencia" (T1 de 0 a 3 ms e um tick
 * comprido: o gancho as vezes desiste e salva ele mesmo, e a thread do servidor chega no meio).
 *
 * <p>Uso: {@code TesteParadaEstresse [repeticoes] [semente]}.
 */
public final class TesteParadaEstresse {

    public static void main(String[] args) throws Exception {
        int reps = args.length > 0 ? Integer.parseInt(args[0]) : 4000;
        long semente = args.length > 1 ? Long.parseLong(args[1]) : 20261122L;
        Verifica v = new Verifica("TesteParadaEstresse");
        Random r = new Random(semente);
        int zero = 0;
        int dois = 0;
        int simultaneos = 0;
        int naoTerminou = 0;
        int acimaDoLimite = 0;
        int tickNoSave = 0;
        int foraDaThread = 0;
        int erros = 0;
        int desistencias = 0;
        int pelaPrincipal = 0;
        int peloGancho = 0;
        int ganchoJaSaiu = 0;
        int ganchoEsperou = 0;
        long maxGanchoMs = 0;
        final long limiteMs = 3000; // esperas esperadas: milissegundos; acima disto e defeito
        long t0 = System.nanoTime();
        for (int i = 0; i < reps; i++) {
            boolean modoDesistencia = r.nextInt(10) < 3;
            long t1 = modoDesistencia ? r.nextInt(4) : 20000;
            final ServidorFalso s = new ServidorFalso(new MaquinaDeParada(t1, 20000, 5000, null), true);
            s.tickMs = r.nextInt(3);
            s.primeiroTickMs = modoDesistencia ? r.nextInt(12) : -1;
            s.saveMs = r.nextInt(4);
            s.fechamentoMs = r.nextInt(3);
            final long atrasoStop = r.nextInt(7);
            final long atrasoTerm = r.nextInt(7);
            final boolean comStop = r.nextInt(8) != 0; // 1 em 8: so SIGTERM
            Thread stop = new Thread(new Runnable() {
                @Override
                public void run() {
                    ServidorFalso.dormir(atrasoStop);
                    s.pedirParada();
                }
            }, "stop");
            Thread term = new Thread(new Runnable() {
                @Override
                public void run() {
                    ServidorFalso.dormir(atrasoTerm);
                    s.sigterm();
                }
            }, "sigterm");
            s.ligar();
            if (comStop) {
                stop.start();
            }
            term.start();
            if (!s.esperarFim(30000)) {
                naoTerminou++;
                continue;
            }
            int n = s.iniciados.get();
            if (n == 0) {
                zero++;
            }
            if (n > 1) {
                dois++;
            }
            if (s.maxSalvando.get() > 1) {
                simultaneos++;
            }
            if (s.ganchoMs > limiteMs) {
                acimaDoLimite++;
            }
            maxGanchoMs = Math.max(maxGanchoMs, s.ganchoMs);
            if (s.erroInesperado != null) {
                erros++;
            }
            boolean ganchoSalvou = s.quemSalvou.contains("Gancho");
            if (ganchoSalvou) {
                peloGancho++;
            } else {
                pelaPrincipal++;
            }
            if (s.motivoGancho == MaquinaDeParada.G_ESGOTOU_LACO) {
                desistencias++;
            } else if (s.motivoGancho == MaquinaDeParada.G_JA_SAIU) {
                ganchoJaSaiu++;
            } else {
                ganchoEsperou++;
            }
            if (!modoDesistencia) {
                if (ganchoSalvou) {
                    foraDaThread++;
                }
                if (s.ticksDuranteSave.get() > 0) {
                    tickNoSave++;
                }
            } else if (ganchoSalvou && s.motivoGancho != MaquinaDeParada.G_ESGOTOU_LACO) {
                foraDaThread++; // o gancho so pode salvar depois de desistir
            }
        }
        long ms = (System.nanoTime() - t0) / 1000000L;
        System.out.println("  " + reps + " repeticoes em " + ms + " ms (semente " + semente + "): salvou a thread do servidor " + pelaPrincipal
                + ", salvou o gancho " + peloGancho + "; gancho: ja tinha saido " + ganchoJaSaiu + ", esperou " + ganchoEsperou
                + ", desistiu por T1 " + desistencias + "; maior espera do gancho " + maxGanchoMs + " ms");
        v.igual(0, naoTerminou, "todas as repeticoes terminaram (nenhuma espera sem fim)");
        v.igual(0, zero, "nunca zero salvamentos");
        v.igual(0, dois, "nunca mais de um salvamento");
        v.igual(0, simultaneos, "nunca dois salvamentos ao mesmo tempo");
        v.igual(0, acimaDoLimite, "nunca espera do gancho acima de " + limiteMs + " ms (maior: " + maxGanchoMs + " ms)");
        v.igual(0, foraDaThread, "o gancho so salva depois de desistir por T1; sem desistencia, salva a thread do servidor");
        v.igual(0, tickNoSave, "modo normal: nenhum tick durante o salvamento");
        v.igual(0, erros, "nenhum erro da maquina");
        v.sim(pelaPrincipal > 0 && ganchoJaSaiu > 0 && ganchoEsperou > 0, "os caminhos 'stop primeiro' e 'SIGTERM primeiro' foram exercitados");
        v.sim(reps < 500 || (desistencias > 0 && peloGancho > 0), "o caminho de desistencia foi exercitado");
        v.fim();
    }
}
