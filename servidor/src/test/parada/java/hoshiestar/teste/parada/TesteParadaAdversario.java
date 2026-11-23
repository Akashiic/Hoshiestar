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
 * Estresse adversario, escrito pelo REVISOR do recurso e trazido para ca: alem do "stop" e do
 * SIGTERM, sorteia uma terceira thread chamando stopServer() direto, um segundo gancho, tempos de
 * fechamento, o FML voltando ao inicio e (na segunda rodada) excecoes no salvamento da thread do
 * servidor e no do gancho. Tudo com atrasos aleatorios e semente fixa.
 *
 * <p>Sem excecoes, sempre: exatamente um salvamento, nunca dois ao mesmo tempo, e o fechamento da
 * thread do servidor nunca comeca antes do fim do salvamento. Com excecoes: nunca dois ao mesmo
 * tempo, nunca dois salvamentos completos, e nenhum salvamento roda durante o fechamento da
 * thread do servidor (achado I3 da revisao: antes da correcao isso acontecia em ~7% das repeticoes).
 *
 * <p>Uso: {@code TesteParadaAdversario [repeticoes por rodada] [semente]}.
 */
public final class TesteParadaAdversario {

    public static void main(String[] args) throws Exception {
        int reps = args.length > 0 ? Integer.parseInt(args[0]) : 1500;
        long semente = args.length > 1 ? Long.parseLong(args[1]) : 777L;
        Verifica v = new Verifica("TesteParadaAdversario");
        try {
            estresse(v, reps, semente, false);
            estresse(v, reps, semente + 1, true);
        } catch (Throwable t) {
            v.erro("excecao inesperada no teste", t);
        }
        v.fim();
    }

    static void estresse(Verifica v, int reps, long semente, boolean comExcecao) throws Exception {
        Random r = new Random(semente);
        int naoTerminou = 0;
        int simult = 0;
        int zero = 0;
        int mais = 0;
        int fechAntes = 0;
        int erros = 0;
        int lento = 0;
        int saveNoFechamento = 0;
        int doisCompletos = 0;
        int cFolga = 0;
        int cLaco = 0;
        int cSaiu = 0;
        int cJa = 0;
        int cTerceiro = 0;
        int cFalhouNaPrincipal = 0;
        long maxG = 0;
        String exemplo = null;
        for (int i = 0; i < reps; i++) {
            boolean desist = r.nextInt(10) < 4;
            long t1 = desist ? r.nextInt(40) : 20000;
            long folga = r.nextInt(30);
            final ServidorFalso s = new ServidorFalso(new MaquinaDeParada(t1, 20000, folga, null), true);
            s.tickMs = r.nextInt(3);
            s.primeiroTickMs = desist ? r.nextInt(60) : -1;
            s.saveMs = r.nextInt(30);
            s.fechamentoMs = r.nextInt(40);
            s.fmlVoltaAoInicio = r.nextBoolean();
            if (comExcecao) {
                s.saveLancaNaPrincipal = r.nextInt(10) < 3;
                s.saveLancaNoGancho = r.nextInt(10) < 3;
            }
            final long aStop = r.nextInt(40);
            final long aTerm = r.nextInt(40);
            final long aOutro = r.nextInt(40);
            final long aG2 = r.nextInt(60);
            boolean comStop = r.nextInt(4) != 0;
            boolean comOutro = !comExcecao && r.nextInt(4) == 0;
            boolean comG2 = r.nextInt(10) == 0;
            Thread stop = new Thread(new Runnable() {
                @Override
                public void run() {
                    ServidorFalso.dormir(aStop);
                    s.pedirParada();
                }
            }, "stop");
            Thread term = new Thread(new Runnable() {
                @Override
                public void run() {
                    ServidorFalso.dormir(aTerm);
                    s.sigterm();
                }
            }, "sigterm");
            Thread outro = new Thread(new Runnable() {
                @Override
                public void run() {
                    ServidorFalso.dormir(aOutro);
                    s.stopServer();
                }
            }, "Outro");
            Thread g2 = new Thread(new Runnable() {
                @Override
                public void run() {
                    ServidorFalso.dormir(aG2);
                    s.ganchoDeNovo();
                }
            }, "Gancho-2");
            s.ligar();
            if (comStop) {
                stop.start();
            }
            term.start();
            if (comOutro) {
                outro.start();
                cTerceiro++;
            }
            if (comG2) {
                g2.start();
            }
            if (!s.esperarFim(30000)) {
                naoTerminou++;
                continue;
            }
            if (comOutro) {
                outro.join(30000);
            }
            if (comG2) {
                g2.join(30000);
            }
            if (s.maxSalvando.get() > 1) {
                simult++;
            }
            if (s.erroInesperado != null) {
                erros++;
            }
            maxG = Math.max(maxG, s.ganchoMs);
            if (s.ganchoMs > 1500) {
                lento++;
            }
            int m = s.motivoGancho;
            if (m == MaquinaDeParada.G_ESGOTOU_FOLGA) {
                cFolga++;
            } else if (m == MaquinaDeParada.G_ESGOTOU_LACO) {
                cLaco++;
            } else if (m == MaquinaDeParada.G_SAIU) {
                cSaiu++;
            } else if (m == MaquinaDeParada.G_JA_SAIU) {
                cJa++;
            }
            if (s.m.estado() == MaquinaDeParada.FALHOU) {
                cFalhouNaPrincipal++;
            }
            boolean fa = s.iniciados.get() > 0 && s.inicioFechamentoNs < s.fimDoUltimoSaveNs;
            if (!comExcecao) {
                if (s.iniciados.get() == 0) {
                    zero++;
                }
                if (s.iniciados.get() > 1) {
                    mais++;
                }
                if (fa) {
                    fechAntes++;
                    if (exemplo == null) {
                        exemplo = "rep " + i + " quem=" + s.quemSalvou + " motivo=" + m;
                    }
                }
            } else {
                if (s.completos.get() > 1) {
                    doisCompletos++;
                }
                if (fa) {
                    saveNoFechamento++;
                    if (exemplo == null) {
                        exemplo = "rep " + i + " quem=" + s.quemSalvou + " motivo=" + m + " t1=" + t1 + " save=" + s.saveMs;
                    }
                }
            }
        }
        String nome = comExcecao ? "adversario COM excecoes" : "adversario SEM excecoes";
        System.out.println("  " + nome + ": " + reps + " reps (semente " + semente + "); gancho: ja saiu " + cJa + ", saiu " + cSaiu + ", folga " + cFolga
                + ", laco " + cLaco + "; com 3a thread " + cTerceiro + "; terminou em FALHOU " + cFalhouNaPrincipal + "; maior gancho " + maxG
                + " ms; exemplo de defeito=" + exemplo);
        v.igual(0, naoTerminou, nome + ": todas terminaram");
        v.igual(0, simult, nome + ": nunca dois salvamentos ao mesmo tempo");
        v.igual(0, erros, nome + ": nenhum erro da maquina");
        v.igual(0, lento, nome + ": gancho nunca acima de 1500 ms (tempos de ms)");
        if (!comExcecao) {
            v.igual(0, zero, nome + ": nunca zero salvamentos");
            v.igual(0, mais, nome + ": nunca mais de um salvamento");
            v.igual(0, fechAntes, nome + ": o fechamento da thread do servidor nunca comeca antes do fim do salvamento");
            v.sim(reps < 200 || (cTerceiro > 0 && cLaco > 0 && cFolga > 0), nome + ": terceira thread, desistencia e folga foram exercitadas");
        } else {
            v.igual(0, doisCompletos, nome + ": nunca dois salvamentos completos");
            v.igual(0, saveNoFechamento, nome + ": nenhum salvamento durante o fechamento da thread do servidor (achado I3)");
            v.sim(reps < 200 || cFalhouNaPrincipal > 0, nome + ": a falha na thread do servidor foi exercitada (" + cFalhouNaPrincipal + ")");
        }
    }
}
