package hoshiestar.teste.vigia;

import hoshiestar.teste.Verifica;
import hoshiestar.vigia.CamposNumericos;
import hoshiestar.vigia.LimiteDeRelatorios;
import hoshiestar.vigia.Marcas;
import hoshiestar.vigia.NucleoVigia;
import hoshiestar.vigia.SaidaEmArquivo;
import hoshiestar.vigia.Vigia;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Nucleo do vigia sem Minecraft, com uma thread do servidor falsa de verdade e a thread do vigia de
 * verdade. Uso: TesteVigiaNucleo <pasta de trabalho>.
 */
public final class TesteVigiaNucleo {

    private static final Verifica V = new Verifica("TesteVigiaNucleo");
    private static File pasta;
    private static Vigia.DonosPorIndice donos;

    public static void main(String[] args) throws Exception {
        pasta = new File(args[0]);
        pasta.mkdirs();
        File mods = new File(pasta, "mods");
        Apoio.jar(mods, "ModFalso-1.0.jar", "modfalso/lento/Trabalho.class", "META-INF/MANIFEST.MF");
        Apoio.jar(mods, "IronChest.jar", "cpw/mods/ironchest/TileEntityIronChest.class"); // cpw.mods.* que nao e FML (revisao M6)
        donos = new Vigia.DonosPorIndice(Arrays.asList(mods, new File(pasta, "plugins-nao-existe")));
        V.sim(Marcas.LIGADO, "marcas ligadas no padrao (vigia.ligado = true)");

        tickNormal();
        tickDeDoisEMeio();
        travamentoLongo();
        limitadorAntesDeAmostrar();
        paradaDuranteTickLento();
        privacidade();
        gc();
        arquivoEReserva();
        donosDosQuadros();
        contextoPorAmostra();
        carencia();
        // testes adversarios da revisao (rev/vigia/adv/TesteRevVigia.java), com verificacao
        ocioso();
        nomeOculto();
        saidaTravada();
        amostradorTravado();
        paradaAposFim();
        paradaNoTravamento();
        amostraAtrasada();
        janela();
        classesNaoCarregadas();
        classeMalformada();
        suprimidoDepoisRelatado();
        gcOutrosColetores();
        V.fim();
    }

    // 1. tick normal: nenhum relatorio
    static void tickNormal() throws Exception {
        Apoio.ServidorFalso srv = new Apoio.ServidorFalso(null);
        Apoio.SaidaFalsa s = new Apoio.SaidaFalsa();
        Apoio.AmostradorContador am = new Apoio.AmostradorContador();
        NucleoVigia n = Apoio.nucleo(Apoio.ajustes(500, 3, 100, 1000), new Apoio.ContextoFalso(), am, s, null, donos,
                new LimiteDeRelatorios(0, 100, 60000));
        srv.start();
        n.iniciar();
        Apoio.esperar(3000);
        V.igual(0, s.rels.size(), "tick normal (50 ms, limite 500 ms, 3 s): nenhum relatorio");
        V.igual(0, am.n.get(), "tick normal: nenhuma amostra de pilha");
        V.igual(0L, n.ticksLentos(), "tick normal: nenhum tick lento contado");
        long t0 = System.nanoTime();
        V.sim(n.parar(), "tick normal: parar() terminou a thread");
        System.out.println("  tick normal: parar() em " + ms(t0) + " ms; linha: " + n.linha());
        srv.parar();
    }

    // 2. tick de 2,5 s com limite 2 s: um relatorio, com a pilha da thread do servidor
    static void tickDeDoisEMeio() throws Exception {
        Apoio.ServidorFalso srv = new Apoio.ServidorFalso(null);
        Apoio.SaidaFalsa s = new Apoio.SaidaFalsa();
        Apoio.AmostradorContador am = new Apoio.AmostradorContador();
        NucleoVigia n = Apoio.nucleo(Apoio.ajustes(2000, 5, 500, 10000), new Apoio.ContextoFalso(), am, s, null, donos,
                new LimiteDeRelatorios(60000, 10, 3600000));
        srv.start();
        n.iniciar();
        Apoio.esperar(300);
        long t0 = System.nanoTime();
        srv.lento(2500);
        V.sim(Apoio.ate(6000, () -> s.rels.size() >= 1), "2,5 s: saiu um relatorio");
        long dt = ms(t0);
        Apoio.esperar(1500); // mais ticks normais: nada novo
        V.igual(1, s.rels.size(), "2,5 s: exatamente um relatorio");
        String t = s.rels.isEmpty() ? "" : s.rels.get(0).texto;
        System.out.println("  2,5 s: relatorio " + dt + " ms depois do pedido; " + am.n.get() + " amostra(s). Texto:\n" + t);
        V.sim(t.contains("TICK LENTO #") && t.contains("(ja terminou)"), "2,5 s: cabecalho e 'ja terminou'");
        long dur = numeroDepois(t, "TICK LENTO #" + s.rels.get(0).seq + ": ");
        V.sim(dur >= 2490 && dur <= 2700, "2,5 s: duracao exata anotada pelo fimTick (" + dur + " ms)");
        V.sim(t.contains("thread 'Server thread (falsa)'"), "2,5 s: nome da thread do servidor");
        V.sim(t.contains("at modfalso.lento.Trabalho.metodoLentoDoMod"), "2,5 s: pilha tem o metodo lento");
        V.sim(t.contains("Apoio$ServidorFalso.run"), "2,5 s: pilha e da thread do servidor falsa");
        V.sim(!t.contains("NucleoVigia"), "2,5 s: pilha NAO e a da thread do vigia");
        V.sim(t.contains("ModFalso-1.0.jar (modfalso.lento.Trabalho.metodoLentoDoMod)"), "2,5 s: mod dono pelo indice de jars");
        V.sim(t.contains("Entidade em atualizacao: EntidadeFalsa id 7"), "2,5 s: entidade marcada no relatorio");
        V.sim(t.contains("Tile em atualizacao: nenhum"), "2,5 s: sem tile");
        V.sim(t.contains("Jogador do pacote: JogadorDeTeste"), "2,5 s: jogador (nome permitido)");
        V.sim(t.contains("indice de jars: 2 jars"), "2,5 s: resumo do indice");
        V.sim(am.n.get() >= 1 && am.n.get() <= 2, "2,5 s: 1 ou 2 amostras (o tick acabou durante a amostragem): " + am.n.get());
        V.igual(1L, n.relatorios(), "2,5 s: contador de relatorios");
        V.sim(s.linhas.isEmpty(), "2,5 s: nenhuma linha extra de 'terminou' (o relatorio ja disse)");
        n.parar();
        srv.parar();
    }

    // 3. travamento longo: relatorios espacados pelo limitador, depois a linha de fim
    static void travamentoLongo() throws Exception {
        Apoio.ServidorFalso srv = new Apoio.ServidorFalso(null);
        Apoio.SaidaFalsa s = new Apoio.SaidaFalsa();
        Apoio.AmostradorContador am = new Apoio.AmostradorContador();
        NucleoVigia n = Apoio.nucleo(Apoio.ajustes(1000, 2, 200, 500), new Apoio.ContextoFalso(), am, s, null, donos,
                new LimiteDeRelatorios(1500, 3, 60000));
        srv.start();
        n.iniciar();
        Apoio.esperar(300);
        srv.lento(7000);
        V.sim(Apoio.ate(12000, () -> !s.linhas.isEmpty()), "longo: saiu a linha de fim do tick travado");
        Apoio.esperar(300);
        List<Apoio.Rel> r = new ArrayList<Apoio.Rel>(s.rels);
        V.igual(3, r.size(), "longo: 3 relatorios (maximo da janela) num tick de 7 s");
        StringBuilder sp = new StringBuilder();
        boolean espacados = true;
        for (int i = 1; i < r.size(); i++) {
            long d = (r.get(i).nanos - r.get(i - 1).nanos) / 1000000L;
            sp.append(d).append(' ');
            espacados &= d >= 1400;
        }
        System.out.println("  longo: intervalos entre relatorios (ms): " + sp + "; linha: " + s.linhas);
        V.sim(espacados, "longo: relatorios espacados >= 1,5 s (limitador)");
        boolean todosTravado = true;
        for (Apoio.Rel x : r) {
            todosTravado &= x.texto.contains("CONTINUA TRAVADO");
            todosTravado &= x.seq == r.get(0).seq;
        }
        V.sim(todosTravado, "longo: todos 'CONTINUA TRAVADO', do mesmo tick");
        String fim = s.linhas.isEmpty() ? "" : s.linhas.get(0);
        long d = numeroDepois(fim, "terminou depois de ");
        V.sim(d >= 6990 && d <= 7300 && fim.contains("(3 relatorio(s))"), "longo: linha de fim com a duracao exata: " + fim);
        V.igual(1L, n.ticksLentos(), "longo: um tick lento so");
        V.igual(0L, n.suprimidos(), "longo: o mesmo tick nao conta como suprimido");
        V.sim(n.linha().contains("relatorios: 3 (3/3 na janela)"), "longo: status: " + n.linha());
        n.parar();
        srv.parar();
    }

    // 4. limitador negado: nenhuma amostra, so conta
    static void limitadorAntesDeAmostrar() throws Exception {
        Apoio.ServidorFalso srv = new Apoio.ServidorFalso(null);
        Apoio.SaidaFalsa s = new Apoio.SaidaFalsa();
        Apoio.AmostradorContador am = new Apoio.AmostradorContador();
        NucleoVigia n = Apoio.nucleo(Apoio.ajustes(500, 3, 100, 2000), new Apoio.ContextoFalso(), am, s, null, donos,
                new LimiteDeRelatorios(0, 1, 60000));
        srv.start();
        n.iniciar();
        Apoio.esperar(300);
        srv.lento(900);
        V.sim(Apoio.ate(5000, () -> s.rels.size() == 1), "limitador: primeiro tick lento gera relatorio");
        int amostrasDepoisDoPrimeiro = am.n.get();
        Apoio.esperar(300);
        srv.lento(900);
        Apoio.esperar(300);
        srv.lento(900);
        V.sim(Apoio.ate(5000, () -> srv.lentosFeitos >= 3), "limitador: os ticks lentos rodaram");
        Apoio.esperar(400);
        V.igual(1, s.rels.size(), "limitador: so 1 relatorio (max 1 por janela)");
        V.igual(amostrasDepoisDoPrimeiro, am.n.get(), "limitador: NENHUMA amostra de pilha nos ticks negados");
        V.igual(2L, n.suprimidos(), "limitador: 2 ticks contados como sem relatorio");
        V.igual(3L, n.ticksLentos(), "limitador: 3 ticks lentos vistos");
        System.out.println("  limitador: amostras " + am.n.get() + "; " + n.linha());
        n.parar();
        srv.parar();
    }

    // 5. parada durante um tick lento: termina em menos de 1 s e grava JA o que colheu (revisao I1)
    static void paradaDuranteTickLento() throws Exception {
        for (int caso = 0; caso < 3; caso++) {
            Apoio.ServidorFalso srv = new Apoio.ServidorFalso(null);
            Apoio.SaidaFalsa s = new Apoio.SaidaFalsa();
            Apoio.AmostradorContador am = new Apoio.AmostradorContador();
            // caso 0: parada no meio das amostras (20 x 500 ms); caso 1: parada esperando o fim do tick (espera de
            // 10 s pedida); caso 2: intervalo de 5 s entre amostras: so volta rapido se parar() acordar a thread
            NucleoVigia.Ajustes a = caso == 0 ? Apoio.ajustes(500, 20, 500, 10000)
                    : caso == 1 ? Apoio.ajustes(500, 1, 500, 10000) : Apoio.ajustes(500, 3, 5000, 0);
            NucleoVigia n = Apoio.nucleo(a, new Apoio.ContextoFalso(), am, s, null, donos, new LimiteDeRelatorios(0, 100, 60000));
            srv.start();
            n.iniciar();
            Apoio.esperar(300);
            srv.lento(10000);
            V.sim(am.primeira.await(5, TimeUnit.SECONDS), "parada " + caso + ": vigia comecou a amostrar");
            Apoio.esperar(caso == 0 ? 700 : 300);
            long t0 = System.nanoTime();
            boolean terminou = n.parar();
            long dt = ms(t0);
            Thread th = n.thread();
            System.out.println("  parada " + (caso == 0 ? "no meio das amostras" : caso == 1 ? "esperando o fim do tick" : "entre amostras de 5 s")
                    + ": parar() em " + dt + " ms; " + am.n.get() + " amostra(s)");
            V.sim(terminou && dt < 1000, "parada " + caso + ": parar() terminou a thread em menos de 1 s (" + dt + " ms)");
            if (caso == 2) {
                V.sim(dt < 300, "parada 2: parar() acorda a thread que dorme entre amostras (" + dt + " ms)");
            }
            V.sim(th != null && !th.isAlive(), "parada " + caso + ": thread do vigia morta");
            V.igual(1, s.rels.size(), "parada " + caso + ": o relatorio com as amostras colhidas foi gravado na parada");
            String tp = s.rels.isEmpty() ? "" : s.rels.get(0).texto;
            V.sim(tp.contains("SERVIDOR PARANDO") && tp.contains("CONTINUA TRAVADO") && tp.contains("at modfalso.lento.Trabalho"),
                    "parada " + caso + ": marcado 'SERVIDOR PARANDO', 'CONTINUA TRAVADO', com a pilha");
            V.sim(srv.isAlive(), "parada " + caso + ": a thread do servidor nao foi tocada (segue no tick lento)");
            srv.fim = true;
            srv.join(15000);
        }
    }

    // 6. privacidade: pacote com String nao aparece
    static void privacidade() {
        // classe de "mod" (fora de net.minecraft./cpw.mods.fml.): nem os campos sao lidos (revisao M4)
        String d0 = CamposNumericos.descrever(new Apoio.PacoteTeste());
        System.out.println("  privacidade (padrao): " + d0);
        V.sim(d0.contains("PacoteTeste") && !d0.contains("x=42") && !d0.contains("SEGREDO") && d0.contains("campos de classe de mod nao lidos"),
                "privacidade: pacote de mod sai so com o nome da classe");
        // com o prefixo da classe de teste liberado (como um pacote do Minecraft): so numeros
        String d = CamposNumericos.descrever(new Apoio.PacoteTeste(), new String[] {"hoshiestar.teste."});
        System.out.println("  privacidade: " + d);
        V.sim(!d.contains("SEGREDO"), "privacidade: nenhum texto do pacote (String, char[], Object)");
        V.sim(d.contains("PacoteTeste") && d.contains("x=42") && d.contains("b=true") && d.contains("d=1.5"),
                "privacidade: classe e campos numericos/booleanos");
        V.sim(!d.contains("letras") && !d.contains("objeto") && !d.contains("texto"), "privacidade: nem o NOME dos campos de texto");
        V.igual("nenhum", CamposNumericos.descrever(null), "privacidade: sem pacote");
    }

    // ------------------------------------------------------------------ adversarios da revisao

    // servidor parado 1,5 s ENTRE ticks (fora de tick()): nao e tick lento (mutacao M6 da revisao)
    static void ocioso() throws Exception {
        Apoio.SaidaFalsa s = new Apoio.SaidaFalsa();
        Apoio.AmostradorContador am = new Apoio.AmostradorContador();
        NucleoVigia n = Apoio.nucleo(Apoio.ajustes(500, 1, 100, 300), new Apoio.ContextoFalso(), am, s, null, null,
                new LimiteDeRelatorios(0, 1000, 60000));
        Thread srv = new Thread("Server thread (falsa ociosa)") {
            @Override
            public void run() {
                hoshiestar.api.ThreadServidor.definir(this);
                for (int i = 0; i < 3; i++) {
                    ++hoshiestar.api.Relogio.ticks;
                    Marcas.inicioTick();
                    Marcas.fimTick();
                    Apoio.esperar(1500);
                }
            }
        };
        srv.setDaemon(true);
        n.iniciar();
        srv.start();
        srv.join();
        V.sim(s.rels.isEmpty() && n.ticksLentos() == 0 && am.n.get() == 0,
                "ocioso: 1,5 s parado fora de tick() (limite 500 ms): nenhum relatorio, tick lento ou amostra");
        n.parar();
    }

    // vigia.nome_do_jogador = false: o nome nao aparece (mutacao M10 da revisao)
    static void nomeOculto() throws Exception {
        Apoio.ServidorFalso srv = new Apoio.ServidorFalso(null);
        Apoio.SaidaFalsa s = new Apoio.SaidaFalsa();
        NucleoVigia.Ajustes a = Apoio.ajustes(500, 1, 100, 3000);
        a.nomeDoJogador = false;
        NucleoVigia n = Apoio.nucleo(a, new Apoio.ContextoFalso(), new Apoio.AmostradorContador(), s, null, null, new LimiteDeRelatorios(0, 1000, 60000));
        srv.start();
        n.iniciar();
        Apoio.esperar(200);
        srv.lento(900);
        V.sim(Apoio.ate(4000, () -> s.rels.size() == 1), "nome oculto: relatorio");
        String t = s.rels.isEmpty() ? "" : s.rels.get(0).texto;
        V.sim(!t.contains("JogadorDeTeste") && t.contains("Jogador do pacote: (nome oculto)"), "nome oculto: o nome do jogador nao aparece");
        n.parar();
        srv.parar();
    }

    // a saida (log/arquivo) trava para sempre: parar() volta em ~0,8 s (mutacao M12: join sem limite)
    static void saidaTravada() throws Exception {
        Apoio.ServidorFalso srv = new Apoio.ServidorFalso(null);
        final java.util.concurrent.CountDownLatch entrou = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch soltar = new java.util.concurrent.CountDownLatch(1);
        NucleoVigia.Saida s = new NucleoVigia.Saida() {
            @Override
            public void relatorio(long seq, String texto) {
                entrou.countDown();
                try {
                    soltar.await();
                } catch (InterruptedException e) {
                    // fim
                }
            }

            @Override
            public void linha(String texto) {
            }

            @Override
            public void erro(String texto, Throwable t) {
            }
        };
        NucleoVigia n = Apoio.nucleo(Apoio.ajustes(500, 1, 100, 0), new Apoio.ContextoFalso(), new Apoio.AmostradorContador(), s, null, null,
                new LimiteDeRelatorios(0, 1000, 60000));
        srv.start();
        n.iniciar();
        Apoio.esperar(200);
        srv.lento(1500);
        V.sim(entrou.await(8, TimeUnit.SECONDS), "saida travada: vigia preso dentro da saida");
        long t0 = System.nanoTime();
        boolean ok = n.parar();
        long dt = ms(t0);
        System.out.println("  saida travada: parar() devolveu " + ok + " em " + dt + " ms");
        V.sim(!ok && dt >= 700 && dt < 1000, "saida travada: parar() limitado a ~0,8 s e diz que a thread nao terminou (" + dt + " ms)");
        soltar.countDown();
        srv.parar();
    }

    // o amostrador (getStackTrace) trava: parar() limitado; depois de solto, nada e gravado (desistiu)
    static void amostradorTravado() throws Exception {
        Apoio.ServidorFalso srv = new Apoio.ServidorFalso(null);
        final java.util.concurrent.CountDownLatch entrou = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch soltar = new java.util.concurrent.CountDownLatch(1);
        Apoio.SaidaFalsa s = new Apoio.SaidaFalsa();
        NucleoVigia.Amostrador am = t -> {
            entrou.countDown();
            try {
                soltar.await();
            } catch (InterruptedException e) {
                // fim
            }
            return new StackTraceElement[0];
        };
        NucleoVigia n = Apoio.nucleo(Apoio.ajustes(500, 3, 100, 0), new Apoio.ContextoFalso(), am, s, null, null, new LimiteDeRelatorios(0, 1000, 60000));
        srv.start();
        n.iniciar();
        Apoio.esperar(200);
        srv.lento(1500);
        V.sim(entrou.await(8, TimeUnit.SECONDS), "amostrador travado: preso no getStackTrace");
        long t0 = System.nanoTime();
        boolean ok = n.parar();
        long dt = ms(t0);
        V.sim(!ok && dt < 1000, "amostrador travado: parar() limitado (" + dt + " ms)");
        soltar.countDown();
        Apoio.esperar(300);
        V.igual(0, s.rels.size(), "amostrador travado: solto depois do prazo da parada, nao grava nada (a JVM pode estar saindo)");
        srv.parar();
    }

    // tick de 2,5 s; a parada chega 0 a 8 ms depois do fim (um 'stop' na fila): o relatorio nao se perde (I1)
    static void paradaAposFim() throws Exception {
        int gravados = 0;
        int rodadas = 5;
        for (int k = 0; k < rodadas; k++) {
            final Apoio.ServidorFalso srv = new Apoio.ServidorFalso(null);
            Apoio.SaidaFalsa s = new Apoio.SaidaFalsa();
            NucleoVigia n = Apoio.nucleo(Apoio.ajustes(2000, 5, 500, 10000), new Apoio.ContextoFalso(), new Apoio.AmostradorContador(), s, null, null,
                    new LimiteDeRelatorios(0, 1000, 60000));
            srv.start();
            n.iniciar();
            Apoio.esperar(300 + 13 * k);
            srv.lento(2500);
            Apoio.ate(6000, () -> srv.lentosFeitos >= 1);
            Thread.sleep(2 * k);
            n.parar();
            if (s.rels.size() == 1 && s.rels.get(0).texto.contains("at modfalso.lento.Trabalho")) {
                gravados++;
            }
            srv.parar();
        }
        V.igual(rodadas, gravados, "parada logo depois do fim de um tick de 2,5 s: relatorio gravado em todas as rodadas");
    }

    // travamento; a parada chega aos ~3 s (SIGTERM/watchdog em outra thread): sai um relatorio (I1)
    static void paradaNoTravamento() throws Exception {
        Apoio.ServidorFalso srv = new Apoio.ServidorFalso(null);
        Apoio.SaidaFalsa s = new Apoio.SaidaFalsa();
        Apoio.AmostradorContador am = new Apoio.AmostradorContador();
        NucleoVigia n = Apoio.nucleo(Apoio.ajustes(2000, 5, 500, 0), new Apoio.ContextoFalso(), am, s, null, null,
                new LimiteDeRelatorios(60000, 10, 3600000));
        srv.start();
        n.iniciar();
        Apoio.esperar(300);
        srv.lento(8000);
        Apoio.esperar(3000);
        long t0 = System.nanoTime();
        boolean ok = n.parar();
        long dt = ms(t0);
        String t = s.rels.isEmpty() ? "" : s.rels.get(0).texto;
        System.out.println("  parada no travamento: parar()=" + ok + " em " + dt + " ms; amostras " + am.n.get() + "; relatorios " + s.rels.size());
        V.sim(ok && dt < 1000, "parada no travamento: parar() em menos de 1 s");
        V.sim(s.rels.size() == 1 && t.contains("SERVIDOR PARANDO") && t.contains("CONTINUA TRAVADO"),
                "parada no travamento: as amostras colhidas viraram relatorio 'SERVIDOR PARANDO'");
        srv.fim = true;
        srv.join(15000);
    }

    // a 1a amostra atrasa 300 ms e o tick acaba antes dela ser conferida: o texto avisa e nao soma os mods (M5)
    static void amostraAtrasada() throws Exception {
        Apoio.ServidorFalso srv = new Apoio.ServidorFalso(null);
        Apoio.SaidaFalsa s = new Apoio.SaidaFalsa();
        NucleoVigia.Amostrador am = t -> {
            Apoio.esperar(300);
            return t.getStackTrace();
        };
        NucleoVigia n = Apoio.nucleo(Apoio.ajustes(2000, 1, 500, 0), new Apoio.ContextoFalso(), am, s, null, donos, new LimiteDeRelatorios(0, 1000, 60000));
        srv.start();
        n.iniciar();
        Apoio.esperar(300);
        srv.lento(2150);
        V.sim(Apoio.ate(5000, () -> s.rels.size() == 1), "amostra atrasada: relatorio");
        String t = s.rels.isEmpty() ? "" : s.rels.get(0).texto;
        System.out.println("  amostra atrasada: " + linhaCom(t, "Amostra 1 (") + " / " + linhaCom(t, "Mods na pilha"));
        V.sim(linhaCom(t, "Amostra 1 (").contains("ATENCAO: o tick acabou antes") && linhaCom(t, "Mods na pilha").contains("nao somados"),
                "amostra atrasada: amostra marcada 'depois do fim' e mods nao somados");
        n.parar();
        srv.parar();
    }

    // ticks de 2040 ms com limite 2000 e poll de 100 ms: todos contados, mesmo os que o laco nao viu (M1)
    static void janela() throws Exception {
        Apoio.ServidorFalso srv = new Apoio.ServidorFalso(null);
        Apoio.SaidaFalsa s = new Apoio.SaidaFalsa();
        NucleoVigia n = Apoio.nucleo(Apoio.ajustes(2000, 1, 500, 0), new Apoio.ContextoFalso(), new Apoio.AmostradorContador(), s, null, null,
                new LimiteDeRelatorios(0, 1000, 60000));
        srv.start();
        n.iniciar();
        java.util.Random r = new java.util.Random(7);
        int total = 6;
        for (int i = 0; i < total; i++) {
            Apoio.esperar(150 + r.nextInt(100));
            final int alvo = i + 1;
            srv.lento(2040);
            Apoio.ate(6000, () -> srv.lentosFeitos >= alvo);
            Apoio.esperar(200);
        }
        Apoio.esperar(300);
        System.out.println("  janela: " + total + " ticks de 2040 ms: relatorios " + s.rels.size() + "; " + n.linha());
        V.igual((long) total, n.ticksLentos(), "janela: todos os ticks acima do limite contados (relatados ou so vistos pelo fim)");
        V.igual((long) total, (long) s.rels.size() + n.soNoFim(), "janela: cada um ou gerou relatorio ou foi contado 'so pelo fim'");
        n.parar();
        srv.parar();
    }

    // pacote de mod com campo de tipo nao carregado: descrever() nao carrega nada na thread do vigia (M4)
    static void classesNaoCarregadas() throws Exception {
        ClassesGeradas.CarregadorQueAnota cl = new ClassesGeradas.CarregadorQueAnota();
        final Object pacote = cl.loadClass("revpk.Pacote").newInstance();
        cl.pedidos.clear();
        final String[] txt = new String[1];
        Thread t = new Thread(() -> txt[0] = CamposNumericos.descrever(pacote), "thread-do-vigia-falsa");
        t.start();
        t.join();
        System.out.println("  classes: descrever -> " + txt[0] + "; pedidos ao carregador: " + cl.pedidos);
        V.sim(cl.pedidos.isEmpty(), "classes: pacote de mod descrito sem pedir classe nenhuma ao carregador do jogo");
        V.sim(txt[0] != null && txt[0].startsWith("Pacote (revpk.Pacote)"), "classes: so o nome da classe");
    }

    // classe aninhada de nome malformado: getSimpleName lanca no Java 8; o vigia nao (M7)
    static void classeMalformada() throws Exception {
        final Object estranho = ClassesGeradas.malformada();
        String d;
        try {
            d = CamposNumericos.descrever(estranho);
        } catch (Throwable e) {
            d = "LANCOU " + e;
        }
        String jv = System.getProperty("java.version");
        System.out.println("  malformada (Java " + jv + "): descrever -> " + d + "; nomeSimples -> " + CamposNumericos.nomeSimples(estranho.getClass()));
        V.sim(!d.startsWith("LANCOU") && d.contains("revgen.Estranha"), "malformada: descrever nao lanca");
        Apoio.SaidaFalsa s = new Apoio.SaidaFalsa();
        Thread srv = new Thread("Server thread (falsa malformada)") {
            @Override
            public void run() {
                hoshiestar.api.ThreadServidor.definir(this);
                ++hoshiestar.api.Relogio.ticks;
                Marcas.inicioTick();
                Marcas.entidade(estranho);
                Apoio.esperar(1500);
                Marcas.entidade(null);
                Marcas.fimTick();
            }
        };
        srv.setDaemon(true);
        NucleoVigia n = Apoio.nucleo(Apoio.ajustes(500, 2, 100, 3000), new Apoio.ContextoFalso(), new Apoio.AmostradorContador(), s, null, null,
                new LimiteDeRelatorios(0, 1000, 60000));
        n.iniciar();
        srv.start();
        srv.join();
        Apoio.esperar(400);
        V.sim(s.rels.size() == 1 && n.errosInternos() == 0, "malformada: entidade de classe malformada nao derruba o relatorio (relatorios "
                + s.rels.size() + ", erros " + n.errosInternos() + ")");
        n.parar();
    }

    // tick negado pelo limitador e relatado depois (mesmo tick): sai da conta 'sem relatorio' (mutacao M14)
    static void suprimidoDepoisRelatado() throws Exception {
        Apoio.ServidorFalso srv = new Apoio.ServidorFalso(null);
        Apoio.SaidaFalsa s = new Apoio.SaidaFalsa();
        NucleoVigia n = Apoio.nucleo(Apoio.ajustes(500, 1, 100, 0), new Apoio.ContextoFalso(), new Apoio.AmostradorContador(), s, null, null,
                new LimiteDeRelatorios(2500, 100, 60000));
        srv.start();
        n.iniciar();
        Apoio.esperar(200);
        srv.lento(700);    // relatado
        srv.lento(4000);   // logo depois: negado no comeco (intervalo minimo 2,5 s), relatado quando o limitador deixa
        V.sim(Apoio.ate(9000, () -> srv.lentosFeitos >= 2), "suprimido: os dois ticks rodaram");
        Apoio.esperar(300);
        System.out.println("  suprimido depois relatado: " + n.linha());
        V.igual(2L, n.ticksLentos(), "suprimido: 2 ticks lentos");
        V.igual(2, s.rels.size(), "suprimido: 2 relatorios (o 2o tick ganhou o seu quando o limitador deixou)");
        V.igual(0L, n.suprimidos(), "suprimido: o tick relatado depois nao fica contado 'sem relatorio'");
        n.parar();
        srv.parar();
    }

    // coletores fora do G1: ZGC/Shenandoah ("Pauses"/"Cycles") e CMS (M8)
    static void gcOutrosColetores() {
        V.sim(NucleoVigia.contaComoPausa("G1 Young Generation", false) && NucleoVigia.contaComoPausa("G1 Old Generation", false)
                && !NucleoVigia.contaComoPausa("G1 Concurrent GC", false), "gc: G1 (pausa, pausa, concorrente)");
        V.sim(NucleoVigia.contaComoPausa("ZGC Pauses", true) && !NucleoVigia.contaComoPausa("ZGC Cycles", true)
                && NucleoVigia.contaComoPausa("Shenandoah Pauses", true) && !NucleoVigia.contaComoPausa("Shenandoah Cycles", true),
                "gc: ZGC/Shenandoah: so 'Pauses' conta");
        V.sim(NucleoVigia.contaComoPausa("ConcurrentMarkSweep", false) && NucleoVigia.contaComoPausa("ParNew", false)
                && NucleoVigia.contaComoPausa("PS MarkSweep", false), "gc: CMS/ParNew/Parallel contam como pausa");
    }

    // 7. GC: pausa grande vira aviso; coletor concorrente rotulado
    static void gc() throws Exception {
        Apoio.GcFalso g = new Apoio.GcFalso();
        Apoio.ServidorFalso srv = new Apoio.ServidorFalso(g);
        Apoio.SaidaFalsa s = new Apoio.SaidaFalsa();
        NucleoVigia n = Apoio.nucleo(Apoio.ajustes(500, 1, 100, 3000), new Apoio.ContextoFalso(), new Apoio.AmostradorContador(), s, g, donos,
                new LimiteDeRelatorios(0, 100, 60000));
        srv.start();
        n.iniciar();
        Apoio.esperar(300);
        srv.fila.add(new Apoio.TickLento(900, 700, 300));
        V.sim(Apoio.ate(5000, () -> s.rels.size() == 1), "gc: relatorio do tick com GC");
        Apoio.esperar(300);
        srv.lento(900);
        V.sim(Apoio.ate(5000, () -> s.rels.size() == 2), "gc: relatorio do tick sem GC");
        String a = s.rels.get(0).texto;
        String b = s.rels.size() > 1 ? s.rels.get(1).texto : "";
        System.out.println("  gc: " + linhaCom(a, "GC durante") + " / " + linhaCom(a, "ATENCAO"));
        V.sim(a.contains("G1 Young Generation 700 ms em 70 coleta(s)"), "gc: tempo por coletor");
        V.sim(a.contains("G1 Concurrent GC 300 ms") && a.contains("(concorrente"), "gc: coletor concorrente rotulado");
        V.sim(a.contains("ATENCAO: pausas de GC somam 700 ms"), "gc: aviso de que a causa provavel e GC");
        V.sim(!b.contains("ATENCAO") && b.contains("G1 Young Generation 0 ms"), "gc: tick sem GC nao culpa o GC");
        n.parar();
        srv.parar();
    }

    // 8. saida em arquivo: pasta principal, reserva, limpeza dos velhos
    static void arquivoEReserva() throws Exception {
        File principalRuim = new File(pasta, "nao-e-pasta");
        new FileOutputStream(principalRuim).close(); // um ARQUIVO no lugar da pasta: mkdirs falha
        File reserva = new File(pasta, "reserva");
        SaidaEmArquivo s = new SaidaEmArquivo(principalRuim, reserva, 10);
        s.relatorio(77, "TICK LENTO #77: teste\n");
        String u = s.ultimoArquivo();
        V.sim(u != null && u.startsWith(reserva.getPath()), "arquivo: foi para a pasta de reserva: " + u);
        V.sim(u != null && new String(Files.readAllBytes(new File(u).toPath()), Charset.forName("UTF-8")).equals("TICK LENTO #77: teste\n"),
                "arquivo: conteudo igual ao relatorio");
        File boa = new File(pasta, "hoshiestar");
        SaidaEmArquivo s2 = new SaidaEmArquivo(boa, reserva, 10);
        for (int i = 0; i < 13; i++) {
            // nomes com a data ate o segundo: cria velhos a mao para nao esperar 13 s
            boa.mkdirs();
            new FileOutputStream(new File(boa, "vigia-2000-01-01_00-00-" + (10 + i) + "-t" + i + ".txt")).close();
        }
        new FileOutputStream(new File(boa, "outro-arquivo.txt")).close();
        s2.relatorio(78, "x\n");
        String[] nossos = boa.list((dir, nome) -> nome.startsWith("vigia-") && nome.endsWith(".txt"));
        V.igual(10, nossos == null ? -1 : nossos.length, "arquivo: guarda no maximo 10 vigia-*.txt");
        V.sim(new File(boa, "outro-arquivo.txt").exists(), "arquivo: nao apaga arquivo que nao e do vigia");
        V.sim(s2.ultimoArquivo() != null && new File(s2.ultimoArquivo()).exists(), "arquivo: o novo ficou (os apagados sao os mais velhos)");
        V.sim(!new File(boa, "vigia-2000-01-01_00-00-10-t0.txt").exists(), "arquivo: o mais velho foi apagado");
    }

    // 9. dono pelo nome de metodo de mixin; quadros neutros
    static void donosDosQuadros() {
        NucleoVigia n = Apoio.nucleo(Apoio.ajustes(500, 1, 100, 100), new Apoio.ContextoFalso(), new Apoio.AmostradorContador(),
                new Apoio.SaidaFalsa(), null, donos, new LimiteDeRelatorios(0, 1, 1000));
        StackTraceElement[] p = {
            new StackTraceElement("java.util.HashMap", "putVal", "HashMap.java", 1),
            new StackTraceElement("net.minecraft.world.World", "redirect$bao000$hodgepodge_2$noSleep", "World.java", 2),
            new StackTraceElement("modfalso.lento.Trabalho", "metodoLentoDoMod", "Trabalho.java", 3),
            new StackTraceElement("modfalso.lento.sub.Gerada", "x", null, -1),
            new StackTraceElement("net.minecraft.server.MinecraftServer", "tick", "MinecraftServer.java", 4),
            new StackTraceElement("desconhecido.Classe", "y", null, -1),
            new StackTraceElement("cpw.mods.fml.common.FMLCommonHandler", "onPostServerTick", null, -1),
        };
        List<String> m = n.modsNaPilha(p);
        System.out.println("  donos: " + m);
        V.igual(2, m.size(), "donos: mixin e ModFalso (neutros e desconhecidos fora; subpacote cai no mesmo jar)");
        V.sim(m.size() == 2 && m.get(0).startsWith("mixin hodgepodge_2 (net.minecraft.world.World.redirect$"), "donos: mixin pelo nome do metodo, primeiro");
        V.sim(m.size() == 2 && m.get(1).startsWith("ModFalso-1.0.jar (modfalso.lento.Trabalho.metodoLentoDoMod)"), "donos: jar do mod depois");
        List<String> m2 = n.modsNaPilha(new StackTraceElement[] {new StackTraceElement("cpw.mods.ironchest.TileEntityIronChest", "func_145845_h", null, -1)});
        V.sim(m2.size() == 1 && m2.get(0).startsWith("IronChest.jar"), "donos: cpw.mods.ironchest e do IronChest (so cpw.mods.fml. e neutro): " + m2);
    }

    // 10. contexto por amostra: a entidade so aparece nas amostras em que estava marcada
    static void contextoPorAmostra() throws Exception {
        Apoio.ServidorFalso srv = new Apoio.ServidorFalso(null);
        Apoio.SaidaFalsa s = new Apoio.SaidaFalsa();
        NucleoVigia n = Apoio.nucleo(Apoio.ajustes(500, 3, 300, 3000), new Apoio.ContextoFalso(), new Apoio.AmostradorContador(), s, null, donos,
                new LimiteDeRelatorios(0, 100, 60000));
        srv.start();
        n.iniciar();
        Apoio.esperar(300);
        Apoio.TickLento l = new Apoio.TickLento(1700, 0, 0);
        l.entidadeDepoisMs = 750;
        srv.fila.add(l);
        V.sim(Apoio.ate(5000, () -> s.rels.size() == 1), "contexto: relatorio");
        String t = s.rels.isEmpty() ? "" : s.rels.get(0).texto;
        System.out.println("  contexto: " + linhaCom(t, "Entidade em atualizacao") + " / " + linhaCom(t, "Amostra 1 (") + " / "
                + linhaCom(t, "Mods na pilha"));
        V.sim(t.contains("Entidade em atualizacao: EntidadeFalsa id 7") && t.contains("(em 2/3 amostras)"),
                "contexto: entidade das amostras 2 e 3 aparece, com a contagem");
        V.sim(linhaCom(t, "Amostra 1 (").contains("entidade: nenhuma") && linhaCom(t, "Amostra 2 (").contains("entidade: EntidadeFalsa"),
                "contexto: cada amostra diz a sua entidade");
        V.sim(linhaCom(t, "Mods na pilha").contains("ModFalso-1.0.jar (modfalso.lento.Trabalho.metodoLentoDoMod; em 3/3 amostras)"),
                "contexto: mods somados nas amostras");
        n.parar();
        srv.parar();
    }

    // 11. carencia depois do boot: tick lento so contado; depois da carencia, relatorio normal
    static void carencia() throws Exception {
        Apoio.ServidorFalso srv = new Apoio.ServidorFalso(null);
        Apoio.SaidaFalsa s = new Apoio.SaidaFalsa();
        Apoio.AmostradorContador am = new Apoio.AmostradorContador();
        NucleoVigia.Ajustes a = Apoio.ajustes(500, 1, 100, 3000);
        a.carenciaMs = 3000;
        NucleoVigia n = Apoio.nucleo(a, new Apoio.ContextoFalso(), am, s, null, donos, new LimiteDeRelatorios(0, 100, 60000));
        srv.start();
        n.iniciar();
        Apoio.esperar(200);
        srv.lento(1500);
        Apoio.esperar(2000);
        V.igual(0, s.rels.size(), "carencia: tick lento logo depois do boot nao gera relatorio");
        V.igual(0, am.n.get(), "carencia: nenhuma amostra de pilha");
        V.igual(1L, n.naCarencia(), "carencia: contado");
        V.sim(Apoio.ate(3000, () -> !s.linhas.isEmpty()), "carencia: uma linha quando a carencia acaba");
        String lin = s.linhas.isEmpty() ? "" : s.linhas.get(0);
        System.out.println("  carencia: " + lin + " | " + n.linha());
        V.sim(lin.contains("1 tick(s) lento(s)") && numeroDepois(lin, "o maior: ") >= 1490 && numeroDepois(lin, "o maior: ") <= 1700,
                "carencia: a linha tem a contagem e a duracao exata do maior");
        srv.lento(900);
        V.sim(Apoio.ate(4000, () -> s.rels.size() == 1), "carencia: depois dela, tick lento gera relatorio");
        n.parar();
        srv.parar();
    }

    // ------------------------------------------------------------------
    static long ms(long t0) {
        return (System.nanoTime() - t0) / 1000000L;
    }

    static long numeroDepois(String t, String marca) {
        int i = t.indexOf(marca);
        if (i < 0) {
            return -1;
        }
        int j = i + marca.length();
        int k = j;
        while (k < t.length() && Character.isDigit(t.charAt(k))) {
            k++;
        }
        return k == j ? -1 : Long.parseLong(t.substring(j, k));
    }

    static String linhaCom(String t, String parte) {
        for (String l : t.split("\n")) {
            if (l.contains(parte)) {
                return l.trim();
            }
        }
        return "(sem linha com '" + parte + "')";
    }
}
