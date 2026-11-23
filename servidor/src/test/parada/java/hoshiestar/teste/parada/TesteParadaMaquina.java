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
import hoshiestar.api.ThreadServidor;
import hoshiestar.parada.Parada;
import hoshiestar.teste.Verifica;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Cenarios da parada com threads de verdade e tempos curtos (milissegundos no lugar de segundos),
 * sobre a mesma {@link MaquinaDeParada} do servidor. Sem argumentos.
 *
 * <p>Folga de relogio: os limites de cima somam {@link #M} ms, porque a maquina de teste pode
 * estar ocupada; os limites de baixo sao exatos (uma espera nunca pode ser mais curta que o devido).
 */
public final class TesteParadaMaquina {

    /** Margem de relogio para os limites de cima. */
    static final long M = 1500;

    static final List<String> AVISOS = Collections.synchronizedList(new ArrayList<String>());

    static final MaquinaDeParada.Avisos GUARDA = new MaquinaDeParada.Avisos() {
        @Override
        public void info(String msg) {
            AVISOS.add("I " + msg);
        }

        @Override
        public void aviso(String msg, Thread principal) {
            AVISOS.add("A " + msg);
        }
    };

    static MaquinaDeParada maquina(long t1, long t2, long folga) {
        return new MaquinaDeParada(t1, t2, folga, GUARDA);
    }

    static boolean avisou(String trecho) {
        synchronized (AVISOS) {
            for (String a : AVISOS) {
                if (a.startsWith("A ") && a.contains(trecho)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static void main(String[] args) throws Exception {
        Verifica v = new Verifica("TesteParadaMaquina");
        try {
            paradaNormal(v);
            sigtermSaudavel(v);
            controleDesligado(v);
            travadoDesiste(v);
            travadoParaSempre(v);
            excecaoNaPrincipal(v);
            excecaoTardiaNaPrincipal(v);
            falhaAntigaNaPrincipal(v);
            excecaoNoGancho(v);
            ganchoFalhaServidorAssume(v);
            falhouDepoisSalvandoNoGanchoDepois(v);
            terceiroSalvaComServidorTravado(v);
            ganchoDuasVezes(v);
            doisGanchosJuntos(v);
            salvamentoPassaDeT2(v);
            ganchoSalvandoPassaDeT2(v);
            folgaDepoisDeSalvar(v);
            avisosAcordamQuemEspera(v);
            sigtermNoBoot(v);
            reentrada(v);
            interrupcao(v);
            fachadaDesligada(v);
        } catch (Throwable t) {
            v.erro("excecao inesperada no teste", t);
        }
        v.fim();
    }

    /** "stop" normal: a thread do servidor salva e sai; o gancho da JVM nao salva de novo nem espera. */
    static void paradaNormal(Verifica v) throws Exception {
        ServidorFalso s = new ServidorFalso(maquina(2000, 2000, 500), true);
        s.saveMs = 60;
        s.fmlVoltaAoInicio = true; // como no servidor real (visto no teste no modpack)
        s.ligar();
        Thread.sleep(30);
        s.pedirParada();
        v.sim(s.esperarFim(10000), "stop: tudo terminou");
        v.igual(1, s.iniciados.get(), "stop: um unico salvamento");
        v.igual("[Server thread]", s.quemSalvou.toString(), "stop: salvou na thread do servidor");
        v.igual(MaquinaDeParada.G_JA_SAIU, s.motivoGancho, "stop: o gancho ve que o servidor ja saiu");
        v.sim(s.ganchoMs < 300, "stop: o gancho nao espera (" + s.ganchoMs + " ms)");
        v.igual(MaquinaDeParada.FEITO, s.m.estado(), "stop: estado final FEITO");
        v.igual(0, s.ticksDuranteSave.get(), "stop: nenhum tick durante o salvamento");
        v.sim(s.erroInesperado == null, "stop: sem erro nosso");
    }

    /** SIGTERM com servidor saudavel: salva uma vez, na thread do servidor, depois do ultimo tick, e sai. */
    static void sigtermSaudavel(Verifica v) throws Exception {
        ServidorFalso s = new ServidorFalso(maquina(2000, 2000, 500), true);
        s.tickMs = 5;
        s.saveMs = 120;
        s.fechamentoMs = 40;
        s.ligar();
        Thread.sleep(40);
        s.sigterm();
        v.sim(s.esperarFim(10000), "sigterm: tudo terminou");
        v.igual(1, s.iniciados.get(), "sigterm: um unico salvamento");
        v.igual("[Server thread]", s.quemSalvou.toString(), "sigterm: salvou na thread do servidor (a certa)");
        v.igual(0, s.ticksDuranteSave.get(), "sigterm: nenhum tick durante o salvamento (sem D-24)");
        v.igual(MaquinaDeParada.G_SAIU, s.motivoGancho, "sigterm: o gancho esperou o servidor chegar na saida");
        v.sim(s.ganchoMs >= 160 && s.ganchoMs < 160 + M, "sigterm: o gancho esperou salvamento + fechamento (" + s.ganchoMs + " ms, minimo 160)");
        v.igual(1, s.m.salvamentos(), "sigterm: o stopServer() do gancho nao salvou de novo");
        v.sim(s.erroInesperado == null, "sigterm: sem erro nosso");
    }

    /** Controle: com o interruptor desligado o servidor de mentira mostra o defeito de hoje (D-24). */
    static void controleDesligado(Verifica v) throws Exception {
        ServidorFalso s = new ServidorFalso(maquina(2000, 2000, 500), false);
        s.tickMs = 5;
        s.saveMs = 150;
        s.ligar();
        Thread.sleep(40);
        s.sigterm(); // o gancho salva direto, com o laco rodando
        Thread.sleep(50);
        s.pedirParada(); // o "crash" do tick: a thread do servidor cai no finally e salva tambem
        v.sim(s.esperarFim(10000), "controle (desligado): tudo terminou");
        v.sim(s.ticksDuranteSave.get() > 0, "controle (desligado): houve tick durante o salvamento (" + s.ticksDuranteSave.get() + "), a causa do D-24");
        v.igual(2, s.maxSalvando.get(), "controle (desligado): dois salvamentos ao mesmo tempo (o teste enxerga o defeito)");
        v.igual(0, s.m.salvamentos(), "controle (desligado): a maquina nao foi tocada");
    }

    /**
     * Servidor travado num tick comprido: o gancho desiste depois de T1 e salva; a thread do servidor
     * destrava NO MEIO desse salvamento e nao pode seguir para o fechamento antes de ele acabar.
     */
    static void travadoDesiste(Verifica v) throws Exception {
        ServidorFalso s = new ServidorFalso(maquina(150, 3000, 800), true);
        s.primeiroTickMs = 350; // destrava em ~350 ms
        s.saveMs = 500; // o gancho salva de ~150 a ~650 ms
        s.fechamentoMs = 200; // avisos de "servidor parado", depois do salvamento
        s.ligar();
        Thread.sleep(10);
        long t0 = System.nanoTime();
        s.sigterm();
        v.sim(s.esperarFim(15000), "travado: tudo terminou");
        v.igual(MaquinaDeParada.G_ESGOTOU_LACO, s.motivoGancho, "travado: o gancho desistiu por T1");
        v.sim(s.m.desistiu(), "travado: bandeira de desistencia");
        v.igual(1, s.iniciados.get(), "travado: um unico salvamento");
        v.igual("[Gancho]", s.quemSalvou.toString(), "travado: quem salvou foi o gancho (caminho original)");
        v.igual(1, s.maxSalvando.get(), "travado: nunca dois salvamentos ao mesmo tempo");
        v.sim(s.inicioFechamentoNs - s.fimDoUltimoSaveNs >= 0, "travado: a thread do servidor so seguiu para o fechamento DEPOIS do fim do salvamento do gancho ("
                + (s.inicioFechamentoNs - s.fimDoUltimoSaveNs) / 1000 + " us depois)");
        long fechamentoMs = (s.inicioFechamentoNs - t0) / 1000000L;
        v.sim(fechamentoMs >= 640, "travado: o fechamento esperou o salvamento (comecou " + fechamentoMs + " ms depois do SIGTERM, minimo 640)");
        v.igual(1, s.m.pulos(), "travado: o stopServer() da thread do servidor foi pulado (ja FEITO)");
        v.sim(s.depoisSaiu, "travado: depois de salvar, o gancho viu a thread do servidor sair dentro da folga");
        v.sim(s.ganchoFimNs - s.saidaNs >= 0 && s.ganchoMs >= 150 + 500 + 200 - 15,
                "travado: como a thread do servidor ja estava no stopServer, o gancho esperou o fechamento dela (" + s.ganchoMs + " ms, minimo 835)");
        v.sim(s.ganchoMs < 150 + 500 + 800 + M, "travado: gancho dentro do limite (" + s.ganchoMs + " ms)");
    }

    /**
     * Travado de verdade: o gancho desiste por T1, salva e termina SEM esperar a folga, porque a
     * thread do servidor nem chegou ao stopServer (achado M2 da revisao e caso real do watchdog:
     * esperar so daria tempo de ela destravar e ticar em cima dos mundos ja descarregados).
     */
    static void travadoParaSempre(Verifica v) throws Exception {
        ServidorFalso s = new ServidorFalso(maquina(120, 3000, 3000), true);
        s.primeiroTickMs = 6000;
        s.saveMs = 100;
        s.ligar();
        Thread.sleep(10);
        s.sigterm();
        v.sim(s.ganchoTerminou.await(10, java.util.concurrent.TimeUnit.SECONDS), "travado para sempre: o gancho terminou");
        v.igual("[Gancho]", s.quemSalvou.toString(), "travado para sempre: o gancho salvou");
        v.sim(s.ganchoMs >= 120 + 100 && s.ganchoMs < 120 + 100 + M,
                "travado para sempre: gancho levou T1 + salvamento, sem a folga de 3 s (" + s.ganchoMs + " ms, de 220 a 1720)");
        v.sim(avisou("continua dentro de um tick"), "travado para sempre: aviso de que a JVM sai sem esperar a folga");
        s.esperarFim(10000); // a thread do servidor destrava aos 6 s, e pulada e termina
        v.igual(1, s.iniciados.get(), "travado para sempre: um unico salvamento, mesmo depois de destravar");
    }

    /** Excecao no salvamento da thread do servidor: estado FALHOU, o gancho nao trava e nao salva (nem em paralelo, nem depois). */
    static void excecaoNaPrincipal(Verifica v) throws Exception {
        ServidorFalso s = new ServidorFalso(maquina(2000, 2000, 300), true);
        s.saveMs = 60;
        s.saveLancaNaPrincipal = true;
        s.ligar();
        Thread.sleep(20);
        s.sigterm();
        v.sim(s.esperarFim(10000), "excecao (servidor): tudo terminou");
        v.igual(1, s.iniciados.get(), "excecao (servidor): uma tentativa so (o gancho nao salva, como no 'stop' de hoje)");
        v.igual(1, s.m.falhas(), "excecao (servidor): falha contada");
        v.igual(MaquinaDeParada.FALHOU, s.m.estado(), "excecao (servidor): estado FALHOU (so a thread do servidor poderia tentar de novo)");
        v.igual(MaquinaDeParada.G_SAIU, s.motivoGancho, "excecao (servidor): o gancho saiu pela saida do servidor");
        v.sim(s.ganchoMs < 60 + M, "excecao (servidor): sem espera longa (" + s.ganchoMs + " ms)");
    }

    /**
     * Achado I3 da revisao (teste A1 do revisor): o salvamento da thread do servidor sai com excecao
     * DEPOIS de T1 (contado do SIGTERM) e o fechamento dela demora. O gancho nao pode virar dono e
     * salvar durante esse fechamento: ele espera a saida pela folga e nao salva.
     */
    static void excecaoTardiaNaPrincipal(Verifica v) throws Exception {
        // (a) o fechamento demora MAIS que a folga: o gancho desiste pela folga e mesmo assim nao salva
        ServidorFalso s = new ServidorFalso(maquina(150, 5000, 300), true);
        s.saveMs = 400; // falha aos ~400 ms, depois de T1 = 150
        s.saveLancaNaPrincipal = true;
        s.fechamentoMs = 600; // avisos de "servidor parado" demoram
        s.ligar();
        Thread.sleep(20);
        s.sigterm();
        v.sim(s.esperarFim(15000), "excecao tardia (a): tudo terminou");
        v.igual("[Server thread]", s.quemSalvou.toString(), "excecao tardia (a): com excecao na thread do servidor o gancho NAO salva");
        v.sim(s.inicioFechamentoNs >= s.fimDoUltimoSaveNs, "excecao tardia (a): nenhum salvamento roda durante o fechamento da thread do servidor");
        v.igual(MaquinaDeParada.G_ESGOTOU_FOLGA, s.motivoGancho, "excecao tardia (a): o gancho esperou a saida pela folga (e nao disse 'o laco nao parou')");
        v.sim(s.ganchoMs >= 400 + 300 - 15 && s.ganchoMs < 400 + 300 + M, "excecao tardia (a): gancho = falha + folga (" + s.ganchoMs + " ms, minimo 685)");
        v.sim(!s.m.desistiu(), "excecao tardia (a): sem bandeira de desistencia por T1");
        v.igual(MaquinaDeParada.FALHOU, s.m.estado(), "excecao tardia (a): estado FALHOU");

        // (b) o fechamento cabe na folga: o gancho ve a saida
        s = new ServidorFalso(maquina(150, 5000, 2000), true);
        s.saveMs = 400;
        s.saveLancaNaPrincipal = true;
        s.fechamentoMs = 200;
        s.ligar();
        Thread.sleep(20);
        s.sigterm();
        v.sim(s.esperarFim(15000), "excecao tardia (b): tudo terminou");
        v.igual("[Server thread]", s.quemSalvou.toString(), "excecao tardia (b): o gancho nao salva");
        v.igual(MaquinaDeParada.G_SAIU, s.motivoGancho, "excecao tardia (b): o gancho viu a thread do servidor chegar na saida");
        v.sim(s.ganchoMs >= 590 && s.ganchoMs < 600 + M, "excecao tardia (b): gancho = falha + fechamento (" + s.ganchoMs + " ms, minimo 590)");
    }

    /**
     * FALHOU nao e para sempre para a propria thread do servidor: se a falha foi num stopServer()
     * chamado no meio de um tick (mod ou plugin) e o servidor seguiu, ela ainda pode salvar na parada
     * de verdade; as outras threads nao. E o gancho, que chega com a folga ja vencida, espera T1.
     */
    static void falhaAntigaNaPrincipal(Verifica v) throws Exception {
        final MaquinaDeParada m = maquina(3000, 3000, 50);
        final boolean[] pedido = new boolean[1];
        final int[] r = new int[3];
        Thread principal = new Thread(new Runnable() {
            @Override
            public void run() {
                r[0] = m.entrar(true, true);
                m.sair(false); // falhou no meio de um "tick"; o servidor segue
                while (!pedido[0]) {
                    synchronized (pedido) {
                        try {
                            pedido.wait(5);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                }
                ServidorFalso.dormir(150); // o resto do tick
                r[1] = m.entrar(true, true); // a parada de verdade
                ServidorFalso.dormir(60);
                m.sair(true);
                m.saindo();
            }
        }, "Server thread");
        principal.start();
        Thread.sleep(200); // a folga (50 ms) da falha ja venceu
        v.igual(MaquinaDeParada.FALHOU, m.estado(), "falha antiga: estado FALHOU");
        v.igual(MaquinaDeParada.PULAR, m.entrar(true, false), "falha antiga: outra thread nao salva");
        long t0 = System.nanoTime();
        int motivo = m.ganchoAntes(principal, new Runnable() {
            @Override
            public void run() {
                synchronized (pedido) {
                    pedido[0] = true;
                }
            }
        }, true);
        long ms = (System.nanoTime() - t0) / 1000000L;
        v.igual(MaquinaDeParada.G_SAIU, motivo, "falha antiga: o gancho esperou (T1) a thread do servidor parar o laco, salvar e sair");
        v.sim(ms >= 200 && ms < 210 + M, "falha antiga: espera = resto do tick + salvamento (" + ms + " ms, minimo 200)");
        v.igual(MaquinaDeParada.DONO, r[1], "falha antiga: a propria thread do servidor pode tentar de novo");
        v.igual(MaquinaDeParada.FEITO, m.estado(), "falha antiga: estado final FEITO");
        v.igual(2, m.salvamentos(), "falha antiga: duas tentativas, em sequencia");
        principal.join(5000);
    }

    /** Excecao no salvamento do gancho (desistencia): a thread do servidor tenta de novo, EM SEQUENCIA. */
    static void excecaoNoGancho(Verifica v) throws Exception {
        ServidorFalso s = new ServidorFalso(maquina(100, 3000, 500), true);
        s.primeiroTickMs = 200;
        s.saveMs = 250;
        s.saveLancaNoGancho = true;
        s.ligar();
        Thread.sleep(10);
        s.sigterm();
        v.sim(s.esperarFim(15000), "excecao (gancho): tudo terminou");
        v.igual("[Gancho, Server thread]", s.quemSalvou.toString(), "excecao (gancho): a thread do servidor refez o salvamento");
        v.igual(1, s.maxSalvando.get(), "excecao (gancho): nunca ao mesmo tempo");
        v.igual(1, s.completos.get(), "excecao (gancho): um salvamento completo");
        v.igual(MaquinaDeParada.FEITO, s.m.estado(), "excecao (gancho): estado final FEITO");
        v.sim(s.ganchoFimNs - s.fimDoUltimoSaveNs >= 0L,
                "excecao (gancho): o gancho (e com ele a JVM) so terminou depois do salvamento da thread do servidor (achado P1)");
        v.sim(s.depoisSaiu, "excecao (gancho): o gancho viu a thread do servidor chegar na saida");
    }

    /**
     * Achado P1 da revisao de concorrencia (teste RevGanchoFalha do revisor, com a maquina direto): o
     * salvamento do gancho sai com excecao, o estado volta a OCIOSO e a thread do servidor, que
     * esperava em entrar(), assume e salva. ganchoDepois tem de esperar esse salvamento (ate inicio +
     * T2) e a folga, e nao voltar na hora (a JVM sairia no meio dele). (a) ganchoDepois chamado com a
     * thread do servidor ja dona (SALVANDO); (b) chamado logo depois do sair(false), quando ela pode
     * ainda nem ter assumido (OCIOSO); (c) o salvamento dela passa de T2: o gancho desiste no prazo e
     * avisa que o salvamento sera cortado.
     */
    static void ganchoFalhaServidorAssume(Verifica v) throws Exception {
        for (int caso = 0; caso < 3; caso++) {
            final String n = "gancho falha, servidor assume (" + (char) ('a' + caso) + ")";
            final MaquinaDeParada m = maquina(200, caso == 2 ? 800 : 5000, 2000);
            final long saveMs = caso == 2 ? 3000 : 1200;
            final long[] fimServidor = new long[1];
            final int[] r = new int[1];
            Thread servidor = new Thread(new Runnable() {
                @Override
                public void run() {
                    r[0] = m.entrar(true, true); // espera o gancho (E1); depois da falha dele vira dono
                    if (r[0] == MaquinaDeParada.DONO) {
                        ServidorFalso.dormir(saveMs);
                        m.sair(true);
                        fimServidor[0] = System.nanoTime();
                        ServidorFalso.dormir(50); // fechamento
                        m.saindo();
                    }
                }
            }, "Server thread");
            AVISOS.clear();
            v.igual(MaquinaDeParada.DONO, m.entrar(true, false), n + ": o gancho e dono");
            servidor.start();
            Thread.sleep(200); // a thread do servidor chega e espera o salvamento do gancho
            m.sair(false); // o salvamento do gancho saiu com excecao
            if (caso != 1) {
                Thread.sleep(100); // ela assume
                v.igual(MaquinaDeParada.SALVANDO, m.estado(), n + ": a thread do servidor assumiu");
            }
            long t0 = System.nanoTime();
            boolean volta = m.ganchoDepois(servidor);
            long agora = System.nanoTime();
            long ms = (agora - t0) / 1000000L;
            if (caso == 2) {
                v.sim(!volta, n + ": ganchoDepois diz que o prazo acabou");
                v.sim(ms >= 800 - 100 - 15 && ms < 800 + M, n + ": esperou ate inicio do salvamento + T2 (" + ms + " ms, minimo 685)");
                v.sim(avisou("sera CORTADO no meio"), n + ": avisa que o salvamento em curso sera cortado");
                v.igual(MaquinaDeParada.SALVANDO, m.estado(), n + ": (controle) ela ainda estava salvando");
            } else {
                v.sim(volta, n + ": ganchoDepois viu a thread do servidor chegar na saida");
                v.sim(fimServidor[0] != 0L && agora - fimServidor[0] >= 0L, n + ": so voltou depois do fim do salvamento dela (" + ms + " ms)");
                v.sim(ms < 1200 + 50 + 100 + M, n + ": sem espera alem do salvamento + fechamento (" + ms + " ms)");
                v.igual(MaquinaDeParada.FEITO, m.estado(), n + ": estado final FEITO");
            }
            v.igual(MaquinaDeParada.DONO, r[0], n + ": a thread do servidor foi dona (em sequencia, nunca junto)");
            servidor.join(10000);
        }
    }

    /**
     * Achado P1, o mesmo buraco no laco de ganchoDepois: a thread do servidor ja estava no fechamento
     * com FALHOU e tenta de novo (FALHOU -> SALVANDO). O gancho espera esse salvamento tambem.
     */
    static void falhouDepoisSalvandoNoGanchoDepois(Verifica v) throws Exception {
        final MaquinaDeParada m = maquina(200, 5000, 300);
        final long[] fimServidor = new long[1];
        final int[] r = new int[2];
        final Object sinal = new Object();
        final boolean[] falhou = new boolean[1];
        Thread servidor = new Thread(new Runnable() {
            @Override
            public void run() {
                r[0] = m.entrar(true, true);
                m.sair(false); // FALHOU
                synchronized (sinal) {
                    falhou[0] = true;
                    sinal.notifyAll();
                }
                ServidorFalso.dormir(150); // dentro da folga (300)
                r[1] = m.entrar(true, true); // tenta de novo: SALVANDO
                ServidorFalso.dormir(600); // passa da folga contada do fim da falha
                m.sair(true);
                fimServidor[0] = System.nanoTime();
                m.saindo();
            }
        }, "Server thread");
        servidor.start();
        synchronized (sinal) {
            while (!falhou[0]) {
                sinal.wait(1000);
            }
        }
        long t0 = System.nanoTime();
        boolean volta = m.ganchoDepois(servidor);
        long agora = System.nanoTime();
        long ms = (agora - t0) / 1000000L;
        v.igual(MaquinaDeParada.DONO, r[1], "FALHOU -> SALVANDO: a thread do servidor tentou de novo");
        v.sim(volta, "FALHOU -> SALVANDO: ganchoDepois viu a saida");
        v.sim(fimServidor[0] != 0L && agora - fimServidor[0] >= 0L,
                "FALHOU -> SALVANDO: so voltou depois do fim da segunda tentativa (" + ms + " ms; antes da correcao voltava em ~150 ms)");
        v.igual(MaquinaDeParada.FEITO, m.estado(), "FALHOU -> SALVANDO: estado final FEITO");
        servidor.join(10000);
    }

    /**
     * Teste A8 do revisor: outra thread salva com a thread do servidor travada e depois a JVM sai. O
     * gancho espera no maximo a folga, nao salva de novo, e a thread do servidor, ao destravar, tambem nao.
     */
    static void terceiroSalvaComServidorTravado(Verifica v) throws Exception {
        final ServidorFalso s = new ServidorFalso(maquina(3000, 3000, 250), true);
        s.primeiroTickMs = 2500; // travada
        s.saveMs = 100;
        s.ligar();
        Thread.sleep(10);
        Thread outro = new Thread(new Runnable() {
            @Override
            public void run() {
                s.stopServer();
            }
        }, "Outro");
        outro.start();
        outro.join(5000);
        s.sigterm();
        v.sim(s.ganchoTerminou.await(10, java.util.concurrent.TimeUnit.SECONDS), "terceiro salva: o gancho terminou");
        v.igual(MaquinaDeParada.G_ESGOTOU_FOLGA, s.motivoGancho, "terceiro salva: FEITO por outra thread, o gancho espera so a folga");
        v.sim(s.ganchoMs < 250 + M, "terceiro salva: gancho <= folga, nao T1 = 3 s (" + s.ganchoMs + " ms)");
        v.igual("[Outro]", s.quemSalvou.toString(), "terceiro salva: um unico salvamento");
        s.esperarFim(10000);
        v.igual(1, s.iniciados.get(), "terceiro salva: a thread do servidor, ao destravar, nao salva de novo");
    }

    /** O gancho rodando duas vezes, uma depois da outra: a segunda nao salva nem espera. */
    static void ganchoDuasVezes(Verifica v) throws Exception {
        ServidorFalso s = new ServidorFalso(maquina(2000, 2000, 300), true);
        s.saveMs = 50;
        s.ligar();
        Thread.sleep(20);
        s.sigterm();
        v.sim(s.esperarFim(10000), "gancho 2x: primeira rodada terminou");
        long t0 = System.nanoTime();
        s.ganchoDeNovo();
        long ms = (System.nanoTime() - t0) / 1000000L;
        v.igual(1, s.iniciados.get(), "gancho 2x: continua um unico salvamento");
        v.sim(ms < 300, "gancho 2x: a segunda rodada volta na hora (" + ms + " ms)");
    }

    /** Duas threads de gancho ao mesmo tempo com o servidor saudavel. */
    static void doisGanchosJuntos(Verifica v) throws Exception {
        final ServidorFalso s = new ServidorFalso(maquina(2000, 2000, 300), true);
        s.tickMs = 3;
        s.saveMs = 80;
        s.ligar();
        Thread.sleep(20);
        Thread outro = new Thread(new Runnable() {
            @Override
            public void run() {
                s.ganchoDeNovo();
            }
        }, "Gancho-2");
        outro.start();
        s.sigterm();
        outro.join(10000);
        v.sim(s.esperarFim(10000) && !outro.isAlive(), "dois ganchos: tudo terminou");
        v.igual(1, s.iniciados.get(), "dois ganchos: um unico salvamento");
        v.igual("[Server thread]", s.quemSalvou.toString(), "dois ganchos: na thread do servidor");
    }

    /**
     * A thread do servidor salva por mais que T2: o gancho para de esperar em T2 e NAO salva junto.
     * T2 conta do INICIO do salvamento: o stopServer() do gancho, que chega com o prazo ja vencido,
     * volta na hora em vez de esperar outro T2 (mutante m8 da revisao).
     */
    static void salvamentoPassaDeT2(Verifica v) throws Exception {
        ServidorFalso s = new ServidorFalso(maquina(4000, 1000, 200), true);
        s.saveMs = 2600;
        s.ligar();
        Thread.sleep(20);
        s.sigterm();
        v.sim(s.ganchoTerminou.await(10, java.util.concurrent.TimeUnit.SECONDS), "T2: o gancho terminou");
        v.igual(MaquinaDeParada.G_ESGOTOU_SALVAR, s.motivoGancho, "T2: o gancho desistiu por T2");
        v.sim(s.ganchoMs >= 990 && s.ganchoMs < 1000 + 600, "T2: o gancho inteiro levou ~T2 uma vez so, nao duas (" + s.ganchoMs + " ms, de 990 a 1600)");
        v.sim(s.ganchoStopMs >= 0 && s.ganchoStopMs < 500,
                "T2: o stopServer() do gancho voltou na hora, sem esperar outro T2 = 1000 ms (" + s.ganchoStopMs + " ms)");
        v.sim(avisou("sera CORTADO no meio"), "T2: o aviso diz que a JVM vai sair e cortar o salvamento em curso");
        v.igual(1, s.iniciados.get(), "T2: o gancho nao iniciou um segundo salvamento");
        v.igual(1, s.maxSalvando.get(), "T2: nunca dois ao mesmo tempo");
        s.esperarFim(10000);
        v.igual(1, s.completos.get(), "T2: o salvamento da thread do servidor terminou sozinho");
    }

    /** O gancho (desistencia) salva por mais que T2: a thread do servidor espera ate T2 e segue SEM salvar. */
    static void ganchoSalvandoPassaDeT2(Verifica v) throws Exception {
        ServidorFalso s = new ServidorFalso(maquina(100, 400, 200), true);
        s.primeiroTickMs = 200; // destrava em 200; o gancho salva de ~100 a ~1300
        s.saveMs = 1200;
        s.ligar();
        Thread.sleep(10);
        long t0 = System.nanoTime();
        s.sigterm();
        v.sim(s.esperarFim(15000), "T2 (gancho salvando): tudo terminou");
        long fechamentoMs = (s.inicioFechamentoNs - t0) / 1000000L;
        v.sim(fechamentoMs >= 100 + 400 - 15 && fechamentoMs < 100 + 400 + M,
                "T2 (gancho salvando): a thread do servidor esperou ate inicio do salvamento + T2 e seguiu (" + fechamentoMs + " ms, minimo 485)");
        v.igual(1, s.iniciados.get(), "T2 (gancho salvando): sem segundo salvamento");
        v.igual(1, s.maxSalvando.get(), "T2 (gancho salvando): nunca dois ao mesmo tempo");
    }

    /** Depois de FEITO o gancho espera so a folga pela saida, nao T2. */
    static void folgaDepoisDeSalvar(Verifica v) throws Exception {
        ServidorFalso s = new ServidorFalso(maquina(2000, 5000, 250), true);
        s.saveMs = 100;
        s.fmlVoltaAoInicio = true;
        s.fechamentoMs = 1500; // um mod travado no aviso de "servidor parado"
        s.ligar();
        Thread.sleep(20);
        s.sigterm();
        v.sim(s.ganchoTerminou.await(10, java.util.concurrent.TimeUnit.SECONDS), "folga: o gancho terminou");
        v.igual(MaquinaDeParada.G_ESGOTOU_FOLGA, s.motivoGancho, "folga: o gancho desistiu pela folga");
        v.sim(s.ganchoMs >= 100 + 250 - 15 && s.ganchoMs < 100 + 250 + 30 + M, "folga: salvamento + folga, nao T2 = 5 s (" + s.ganchoMs + " ms, minimo 335)");
        v.igual(1, s.iniciados.get(), "folga: um unico salvamento");
        s.esperarFim(10000);
    }

    /**
     * Quem espera acorda por AVISO (notifyAll), nao so pelo passo de 50 ms: com um passo de 20 s,
     * sem o aviso de sair() ou de saindo() a espera so acabaria no prazo (mutante m5 da revisao).
     */
    static void avisosAcordamQuemEspera(Verifica v) throws Exception {
        final MaquinaDeParada m = new MaquinaDeParada(8000, 8000, 8000, GUARDA, 20000, null);
        v.igual(MaquinaDeParada.DONO, m.entrar(true, false), "avisos: a thread do teste vira dona");
        final AtomicInteger resposta = new AtomicInteger(-1);
        final long[] voltou = new long[1];
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                resposta.set(m.entrar(true, false));
                voltou[0] = System.nanoTime();
            }
        }, "Esperando");
        t.start();
        Thread.sleep(150);
        long t0 = System.nanoTime();
        m.sair(true);
        t.join(15000);
        long ms = (voltou[0] - t0) / 1000000L;
        v.igual(MaquinaDeParada.PULAR, resposta.get(), "avisos: quem esperava pulou depois do FEITO");
        v.sim(!t.isAlive() && ms < 2000, "avisos: sair() acordou quem esperava em entrar (" + ms + " ms; sem aviso seriam 8 s)");

        // o gancho esperando a saida (estado FEITO, folga de 8 s): saindo() tem de acordar
        final Thread principal = new Thread(new Runnable() {
            @Override
            public void run() {
                ServidorFalso.dormir(150);
                m.saindo();
                ServidorFalso.dormir(3000); // parada "dentro do System.exit": continua viva
            }
        }, "Server thread");
        principal.start();
        t0 = System.nanoTime();
        int motivo = m.ganchoAntes(principal, new Runnable() {
            @Override
            public void run() {
            }
        }, true);
        ms = (System.nanoTime() - t0) / 1000000L;
        v.igual(MaquinaDeParada.G_SAIU, motivo, "avisos: o gancho viu a saida");
        v.sim(ms >= 140 && ms < 2000, "avisos: saindo() acordou o gancho (" + ms + " ms; sem aviso seriam 8 s)");
        principal.join(10000);
    }

    /**
     * SIGTERM no boot (antes de SERVER_STARTED): o gancho nao pede a parada, nao espera e nao salva;
     * a JVM sai na hora, como no codigo original. Na janela logo depois de SERVER_STARTED ele ja espera.
     */
    static void sigtermNoBoot(Verifica v) throws Exception {
        ServidorFalso s = new ServidorFalso(maquina(1000, 2000, 300), true);
        s.iniciou = false;
        s.bootMs = 600;
        s.saveMs = 40;
        s.ligar();
        Thread.sleep(20);
        s.sigterm();
        v.sim(s.ganchoTerminou.await(10, java.util.concurrent.TimeUnit.SECONDS), "boot: o gancho terminou");
        v.igual(MaquinaDeParada.G_NO_BOOT, s.motivoGancho, "boot: o gancho ve que o servidor ainda nao iniciou");
        v.sim(s.ganchoMs < 300, "boot: sem espera, a JVM sai na hora (" + s.ganchoMs + " ms)");
        v.igual(0, s.iniciados.get(), "boot: nada salvo (o servidor nao tinha iniciado; igual a hoje)");
        v.sim(s.principal.isAlive(), "boot: o gancho NAO pediu a parada (o boot seguia quando a JVM saiu)");
        v.igual(MaquinaDeParada.OCIOSO, s.m.estado(), "boot: estado intocado");
        s.pedirParada(); // limpeza: na JVM de verdade o processo ja teria acabado
        s.esperarFim(10000);

        s = new ServidorFalso(maquina(1000, 2000, 300), true);
        s.saveMs = 40;
        s.ligar(); // ja iniciado (iniciou = true), nos primeiros ticks
        s.sigterm();
        v.sim(s.esperarFim(10000), "logo depois do boot: tudo terminou");
        v.igual("[Server thread]", s.quemSalvou.toString(), "logo depois do boot: a thread do servidor salvou");
        v.igual(MaquinaDeParada.G_SAIU, s.motivoGancho, "logo depois do boot: o gancho esperou a saida");
    }

    /** stopServer() de dentro do stopServer(), na mesma thread: roda como no original e nao bagunca o estado. */
    static void reentrada(Verifica v) throws Exception {
        ServidorFalso s = new ServidorFalso(maquina(2000, 2000, 300), true);
        s.saveMs = 30;
        s.chamaStopDeDentro = true;
        s.ligar();
        Thread.sleep(20);
        s.pedirParada();
        v.sim(s.esperarFim(10000), "reentrada: tudo terminou");
        v.igual(1, s.m.reentradas(), "reentrada: contada");
        v.igual(2, s.iniciados.get(), "reentrada: o corpo de dentro roda como no original");
        v.igual(1, s.m.salvamentos(), "reentrada: a maquina conta um dono so");
        v.igual(MaquinaDeParada.FEITO, s.m.estado(), "reentrada: estado final FEITO (so quando a chamada de fora terminou)");
    }

    /** Thread interrompida enquanto espera o salvamento de outra: continua esperando e devolve a interrupcao. */
    static void interrupcao(Verifica v) throws Exception {
        final MaquinaDeParada m = maquina(1000, 3000, 100);
        v.igual(MaquinaDeParada.DONO, m.entrar(true, false), "interrupcao: a thread do teste vira dona");
        final AtomicInteger resposta = new AtomicInteger(-1);
        final boolean[] bandeira = new boolean[1];
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                resposta.set(m.entrar(true, false));
                bandeira[0] = Thread.currentThread().isInterrupted();
            }
        }, "Esperando");
        t.start();
        Thread.sleep(80);
        t.interrupt();
        Thread.sleep(150);
        v.sim(t.isAlive() && resposta.get() == -1, "interrupcao: quem esperava continua esperando");
        m.sair(true);
        t.join(5000);
        v.igual(MaquinaDeParada.PULAR, resposta.get(), "interrupcao: depois do FEITO, pula");
        v.sim(bandeira[0], "interrupcao: a bandeira de interrupcao foi devolvida");
        v.igual(MaquinaDeParada.ORIGINAL, m.entrar(false, false), "vaiSalvar falso: a maquina nao se mete");
        m.sair(true); // sair de quem nao e dono: nada
        v.igual(MaquinaDeParada.FEITO, m.estado(), "sair() de quem nao e dono nao muda o estado");
    }

    /** A fachada com o interruptor desligado nao faz nada; ligada, repassa para a maquina. */
    static void fachadaDesligada(Verifica v) throws Exception {
        MaquinaDeParada m = maquina(1000, 1000, 100);
        Parada.configurarParaTeste(false, m);
        v.igual(0, Parada.entrar(true), "fachada desligada: entrar devolve 0 (caminho original)");
        Parada.sair(true);
        Parada.saindo();
        final int[] pedidos = new int[1];
        Thread viva = new Thread(new Runnable() {
            @Override
            public void run() {
                ServidorFalso.dormir(400);
            }
        });
        viva.start();
        long t0 = System.nanoTime();
        Parada.ganchoAntes(viva, new Runnable() {
            @Override
            public void run() {
                pedidos[0]++;
            }
        }, true);
        Parada.ganchoDepois(viva);
        v.sim((System.nanoTime() - t0) / 1000000L < 200 && pedidos[0] == 0, "fachada desligada: o gancho nao pede parada nem espera");
        v.sim(m.estado() == MaquinaDeParada.OCIOSO && !m.saida() && m.salvamentos() == 0 && m.ganchos() == 0, "fachada desligada: maquina intocada");
        Parada.configurarParaTeste(true, m);
        v.igual(1, Parada.entrar(true), "fachada ligada: entrar devolve 1 (dono)");
        Parada.sair(true);
        v.igual(MaquinaDeParada.FEITO, m.estado(), "fachada ligada: sair marca FEITO");
        v.sim(Parada.linhaDeEstado().contains("estado: feito"), "linha de estado: " + Parada.linhaDeEstado());
        viva.join();

        // a fachada diz a maquina quem e a thread do servidor (ThreadServidor): falha nela = FALHOU; em outra = OCIOSO
        Thread antes = ThreadServidor.thread();
        try {
            MaquinaDeParada m2 = maquina(1000, 1000, 100);
            Parada.configurarParaTeste(true, m2);
            ThreadServidor.definir(null);
            v.igual(1, Parada.entrar(true), "fachada: fora da thread do servidor, dono");
            Parada.sair(false);
            v.igual(MaquinaDeParada.OCIOSO, m2.estado(), "fachada: falha fora da thread do servidor volta a OCIOSO");
            ThreadServidor.definir(Thread.currentThread());
            v.igual(1, Parada.entrar(true), "fachada: na thread do servidor, dono");
            Parada.sair(false);
            v.igual(MaquinaDeParada.FALHOU, m2.estado(), "fachada: falha na thread do servidor vira FALHOU");
            v.sim(Parada.linhaDeEstado().contains("estado: falhou na thread do servidor"), "linha de estado: " + Parada.linhaDeEstado());
        } finally {
            ThreadServidor.definir(antes);
        }
    }
}
