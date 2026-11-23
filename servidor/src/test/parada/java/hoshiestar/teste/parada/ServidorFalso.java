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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Um servidor de mentira para os testes da parada: uma thread "Server thread" com laco de tick, um
 * stopServer(), um systemExitNow() e um gancho de desligamento. A cola entre eles e a
 * {@link MaquinaDeParada} copia, linha por linha, a dos patches do fork (MinecraftServer.stopServer,
 * DedicatedServer.systemExitNow e ServerShutdownThread.run); a maquina e a MESMA classe do servidor.
 * Com {@code ligado = false} roda o fluxo original (para o teste de controle, que mostra o defeito).
 */
final class ServidorFalso {

    final MaquinaDeParada m;
    final boolean ligado;

    // ---- o "servidor"
    private boolean rodando = true; // serverRunning (campo comum, como no original)
    private boolean serverStopped; // idem
    volatile boolean iniciou = true; // Loader.hasReachedState(SERVER_STARTED)
    volatile long bootMs;
    volatile long tickMs = 1;
    volatile long primeiroTickMs = -1; // um primeiro tick comprido (servidor "travado")
    volatile long saveMs = 20;
    volatile long fechamentoMs; // tempo entre o stopServer() e o systemExitNow() (avisos de "servidor parado")
    volatile boolean saveLancaNaPrincipal;
    volatile boolean saveLancaNoGancho;
    volatile boolean chamaStopDeDentro; // reentrada: stopServer() de dentro do corpo
    volatile boolean fmlVoltaAoInicio; // depois de parar, hasReachedState(SERVER_STARTED) volta a dar falso

    // ---- o que o teste observa
    final AtomicInteger salvando = new AtomicInteger();
    final AtomicInteger maxSalvando = new AtomicInteger();
    final AtomicInteger iniciados = new AtomicInteger();
    final AtomicInteger completos = new AtomicInteger();
    final AtomicInteger ticksDuranteSave = new AtomicInteger();
    final List<String> quemSalvou = Collections.synchronizedList(new ArrayList<String>());
    volatile long fimDoUltimoSaveNs;
    volatile long inicioFechamentoNs;
    volatile long saidaNs;
    volatile int motivoGancho = -1;
    volatile long ganchoMs = -1;
    volatile long ganchoStopMs = -1; // quanto durou o stopServer() chamado pelo gancho
    volatile long ganchoFimNs;
    volatile boolean depoisSaiu = true;
    volatile Throwable erroInesperado;

    final Thread principal;
    final CountDownLatch ganchoTerminou = new CountDownLatch(1);
    private final AtomicBoolean ganchosDisparados = new AtomicBoolean();
    private volatile Thread gancho;

    ServidorFalso(MaquinaDeParada m, boolean ligado) {
        this.m = m;
        this.ligado = ligado;
        this.principal = new Thread(new Runnable() {
            @Override
            public void run() {
                laco();
            }
        }, "Server thread");
    }

    void ligar() {
        principal.start();
    }

    /** O comando "stop" (initiateShutdown), de qualquer thread. */
    void pedirParada() {
        rodando = false;
    }

    /** SIGTERM: a JVM dispara os ganchos (uma vez so, como a JVM de verdade). */
    void sigterm() {
        dispararGanchos();
    }

    /** Roda o gancho de novo, na thread que chama (teste "gancho chamado duas vezes"). */
    void ganchoDeNovo() {
        corpoDoGancho();
    }

    boolean esperarFim(long ms) throws InterruptedException {
        boolean g = ganchoTerminou.await(ms, TimeUnit.MILLISECONDS);
        principal.join(ms);
        return g && !principal.isAlive();
    }

    // ---- MinecraftServer.run()
    private void laco() {
        try {
            if (bootMs > 0) {
                dormir(bootMs);
                iniciou = true;
            }
            boolean primeiro = true;
            while (rodando) {
                if (salvando.get() > 0) {
                    ticksDuranteSave.incrementAndGet();
                }
                dormir(primeiro && primeiroTickMs >= 0 ? primeiroTickMs : tickMs);
                primeiro = false;
            }
        } finally {
            try {
                stopServer();
                serverStopped = true;
            } catch (Throwable t) {
                // "Exception stopping the server"
            } finally {
                inicioFechamentoNs = System.nanoTime();
                dormir(fechamentoMs); // handleServerStopped
                if (fmlVoltaAoInicio) {
                    iniciou = false; // o FML de verdade volta ao estado AVAILABLE: "nao iniciou" de novo
                }
                serverStopped = true;
                systemExitNow();
            }
        }
    }

    // ---- MinecraftServer.stopServer(), com a cola do patch
    void stopServer() {
        int hoshieParada = 0;
        try {
            if (ligado) {
                // a fachada Parada passa "sou a thread do servidor?" (ThreadServidor) junto com a condicao do patch
                hoshieParada = m.entrar(iniciou && !serverStopped, Thread.currentThread() == principal);
            }
        } catch (Throwable hoshieErro) {
            hoshieParada = 0;
            erroInesperado = hoshieErro;
        }
        if (hoshieParada == 2) {
            return;
        }
        boolean hoshieOk = false;
        try {
            if (iniciou && !serverStopped) {
                corpo();
            }
            hoshieOk = true;
        } finally {
            if (hoshieParada == 1) {
                try {
                    m.sair(hoshieOk);
                } catch (Throwable hoshieErro) {
                    erroInesperado = hoshieErro;
                }
            }
        }
    }

    /** O corpo original: salva jogadores e mundos e descarrega os mundos. */
    private void corpo() {
        int c = salvando.incrementAndGet();
        for (;;) {
            int mx = maxSalvando.get();
            if (c <= mx || maxSalvando.compareAndSet(mx, c)) {
                break;
            }
        }
        iniciados.incrementAndGet();
        quemSalvou.add(Thread.currentThread().getName());
        try {
            dormir(saveMs);
            if (chamaStopDeDentro) {
                chamaStopDeDentro = false;
                stopServer();
            }
            boolean naPrincipal = Thread.currentThread() == principal;
            if (naPrincipal ? saveLancaNaPrincipal : saveLancaNoGancho) {
                throw new IllegalStateException("salvamento falhou (de proposito)");
            }
            completos.incrementAndGet();
        } finally {
            fimDoUltimoSaveNs = System.nanoTime();
            salvando.decrementAndGet();
        }
    }

    // ---- DedicatedServer.systemExitNow(), com a cola do patch
    private void systemExitNow() {
        try {
            if (ligado) {
                m.saindo();
            }
        } catch (Throwable hoshieErro) {
            erroInesperado = hoshieErro;
        }
        saidaNs = System.nanoTime();
        // System.exit: dispara os ganchos (se o SIGTERM ainda nao disparou) e fica parado ate eles terminarem
        dispararGanchos();
        try {
            ganchoTerminou.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            // fim
        }
    }

    private void dispararGanchos() {
        if (!ganchosDisparados.compareAndSet(false, true)) {
            return;
        }
        Thread g = new Thread(new Runnable() {
            @Override
            public void run() {
                corpoDoGancho();
            }
        }, "Gancho");
        gancho = g;
        g.start();
    }

    // ---- ServerShutdownThread.run(), com a cola do patch
    private void corpoDoGancho() {
        long t0 = System.nanoTime();
        try {
            if (ligado) {
                motivoGancho = m.ganchoAntes(principal, new Runnable() {
                    @Override
                    public void run() {
                        pedirParada();
                    }
                }, iniciou);
            }
        } catch (Throwable hoshieErro) {
            erroInesperado = hoshieErro;
        }
        long tStop = System.nanoTime();
        try {
            stopServer();
        } catch (IllegalStateException doSave) {
            // o original imprime e segue
        } finally {
            ganchoStopMs = (System.nanoTime() - tStop) / 1000000L;
            try {
                if (ligado) {
                    depoisSaiu = m.ganchoDepois(principal);
                }
            } catch (Throwable hoshieErro) {
                erroInesperado = hoshieErro;
            }
            ganchoFimNs = System.nanoTime();
            ganchoMs = (ganchoFimNs - t0) / 1000000L;
            ganchoTerminou.countDown();
        }
    }

    static void dormir(long ms) {
        if (ms <= 0) {
            return;
        }
        long fim = System.nanoTime() + ms * 1000000L;
        boolean interrompida = false;
        for (;;) {
            long falta = fim - System.nanoTime();
            if (falta <= 0) {
                break;
            }
            try {
                Thread.sleep(Math.max(1L, falta / 1000000L));
            } catch (InterruptedException e) {
                interrompida = true;
            }
        }
        if (interrompida) {
            Thread.currentThread().interrupt();
        }
    }
}
