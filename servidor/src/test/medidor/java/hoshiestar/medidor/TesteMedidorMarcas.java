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

import hoshiestar.teste.Verifica;
import hoshiestar.vigia.Marcas;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Testes com o relogio de verdade e threads reais, pelo MESMO caminho dos patches de MinecraftServer.tick (Marcas do
 * vigia antes, Medidor depois). Vieram dos testes adversarios da revisao independente do porte (casos 1 a 3) e pegam
 * mutacoes que o {@link TesteMedidor} (relogio virtual, chama Medidor.inicioTick(long) direto) nao pega.
 * 1) Marcas + Medidor: o medidor usa as leituras de relogio do vigia (nanoInicioTick/nanoFimTick) sem leitura velha.
 * 2) Estresse: thread "RCON" mandando medir/parar/status sem parar enquanto a thread do tick roda ticks com laco e
 *    comandos "de console" no fim do tick. Invariantes: nenhum erro interno, pilha vazia no fim de cada tick, cada
 *    medicao vira um par de arquivos com JSON valido e contagem exata (te = 50 x ticks_com_laco, ent = 20 x ...).
 * 3) habilitado=false: os ganchos de tick nao mexem em nada (refN parado).
 * 4) A calibracao (10 a 40 ms, no comando) nao entra no tempo do 1o tick da medicao.
 *
 * <p>Uso: {@code java hoshiestar.medidor.TesteMedidorMarcas <pasta de trabalho> [segundos do estresse, padrao 10]}.
 */
public final class TesteMedidorMarcas {

    static Verifica v;
    static int segundosEstresse;

    static final class Te {
    }

    static final class Ent {
    }

    static final class JogoR implements Jogo {
        final Object mundo = new Object();
        volatile File pasta;

        public boolean morto(Object o, int tipo) {
            return false;
        }

        public List<Mundo> mundos() {
            List<Mundo> r = new ArrayList<Mundo>();
            r.add(new Mundo(mundo, 0, 50, 20, true, false));
            return r;
        }

        public File pastaDeSaida() {
            return pasta;
        }
    }

    static Object campo(String nome) throws Exception {
        Field f = Medidor.class.getDeclaredField(nome);
        f.setAccessible(true);
        return f.get(null);
    }

    public static void main(String[] args) throws Exception {
        File base = new File(args[0]).getAbsoluteFile();
        base.mkdirs();
        v = new Verifica("TesteMedidorMarcas");
        segundosEstresse = args.length > 1 ? Integer.parseInt(args[1]) : 10;
        JogoR jogo = new JogoR();
        Medidor.definirJogo(jogo, "vanilla", "teste");
        try {
            marcas(jogo, new File(base, "marcas"));
            calibracaoForaDoTick(jogo, new File(base, "calibracao"));
            estresse(jogo, new File(base, "estresse"));
            desabilitado();
        } catch (Throwable t) {
            v.erro("excecao inesperada", t);
        }
        v.fim();
    }

    // ---------------------------------------------------------------- 1
    static void marcas(JogoR jogo, File pasta) throws Exception {
        jogo.pasta = pasta;
        v.sim(Marcas.LIGADO, "vigia ligado por padrao no teste (Marcas.LIGADO): o medidor usa as leituras dele");
        // sem fimTick antes: nanoFimTick deve ler o relogio na hora (nada velho)
        long a = System.nanoTime();
        long f = Marcas.nanoFimTick();
        v.sim(f >= a, "nanoFimTick sem fimTick: leitura nova");
        Marcas.inicioTick();
        long i1 = Marcas.nanoInicioTick();
        long i2 = Marcas.nanoInicioTick();
        v.sim(i1 == i2, "nanoInicioTick devolve a leitura do inicioTick do vigia (sem leitura nova)");
        Thread.sleep(5);
        Marcas.fimTick();
        long f1 = Marcas.nanoFimTick();
        Thread.sleep(3);
        long f2 = Marcas.nanoFimTick();
        v.sim(f1 - i1 >= 4000000L && f1 - i1 < 1000000000L, "duracao do tick pelo vigia ~5 ms: " + (f1 - i1));
        v.sim(f2 - f1 >= 2000000L, "nanoFimTick consumido: a 2a chamada le de novo (nao reaproveita o valor velho)");
        // 300 ticks pelo caminho real dos patches (Marcas antes, Medidor depois), duracao conhecida
        for (int t = 0; t < 300; t++) {
            Marcas.inicioTick();
            Medidor.inicioTickServidor();
            espera(1000000L);
            Marcas.fimTick();
            Medidor.fimTickServidor();
        }
        long[] ref = (long[]) campo("refTicks");
        long min = Long.MAX_VALUE;
        long max = 0;
        for (long x : ref) {
            min = Math.min(min, x);
            max = Math.max(max, x);
        }
        v.sim(min >= 1000000L && max < 50000000L, "referencia da valvula com os ticks de ~1 ms medidos pelas leituras do"
                + " vigia: min " + min + " max " + max);
    }

    static void espera(long ns) {
        long fim = System.nanoTime() + ns;
        while (System.nanoTime() < fim) {
            // nada
        }
    }

    // ---------------------------------------------------------------- 4
    /**
     * A medicao liga no meio de um tick (comando na thread do servidor) e calibra ali (10 a 40 ms). O 1o tick medido
     * conta so do fim da calibracao (e da linha do log) ao fim do tick: senao a calibracao inflaria o tick do servidor
     * e baixaria a cobertura no relatorio.
     */
    static void calibracaoForaDoTick(JogoR jogo, File pasta) throws Exception {
        jogo.pasta = pasta;
        Marcas.inicioTick();
        Medidor.inicioTickServidor();
        long t0 = System.nanoTime();
        List<String> r = Medidor.comandoMedir(10, false);
        long t1 = System.nanoTime();
        Marcas.fimTick();
        Medidor.fimTickServidor();
        Coleta c = Medidor.coletaAtual();
        v.sim(c != null && Medidor.ATIVO, "comando na thread do tick liga a medicao na hora: " + r);
        if (c != null) {
            v.sim(t1 - t0 >= 2000000L, "o comando (com a calibracao) levou mais de 2 ms: " + (t1 - t0) / 1000 + " us");
            v.sim(c.ticksServidor == 1 && c.nsTickServidor < (t1 - t0) / 2, "1o tick conta so depois da calibracao: "
                    + c.nsTickServidor / 1000 + " us contados, comando levou " + (t1 - t0) / 1000 + " us");
        }
        Medidor.comandoParar();
        v.sim(Medidor.esperarGravacao(30000L), "gravacao da medicao curta terminou");
    }

    // ---------------------------------------------------------------- 2
    static void estresse(final JogoR jogo, File pasta) throws Exception {
        jogo.pasta = pasta;
        final Te[] tes = new Te[50];
        for (int i = 0; i < tes.length; i++) {
            tes[i] = new Te();
        }
        final Ent[] ents = new Ent[20];
        for (int i = 0; i < ents.length; i++) {
            ents[i] = new Ent();
        }
        final AtomicBoolean fim = new AtomicBoolean();
        final AtomicReference<Throwable> erroRcon = new AtomicReference<Throwable>();
        final int[] pedidosRcon = new int[3];
        Thread rcon = new Thread(new Runnable() {
            public void run() {
                Random r = new Random(7);
                try {
                    while (!fim.get()) {
                        int op = r.nextInt(3);
                        if (op == 0) {
                            Medidor.comandoMedir(10, false);
                        } else if (op == 1) {
                            Medidor.comandoParar();
                        } else {
                            Medidor.linhaDeEstado();
                        }
                        pedidosRcon[op]++;
                        Thread.sleep(r.nextInt(40));
                    }
                } catch (Throwable t) {
                    erroRcon.set(t);
                }
            }
        }, "rcon-falso");
        Random r = new Random(11);
        Set<Long> ids = new HashSet<Long>();
        int pilhaSuja = 0;
        long fimEm = System.nanoTime() + segundosEstresse * 1000000000L;
        rcon.start();
        int ticks = 0;
        while (System.nanoTime() < fimEm) {
            Marcas.inicioTick();
            Medidor.inicioTickServidor();
            long id = (Long) campo("idMedicao");
            if (id != 0) {
                ids.add(id);
            }
            if (Medidor.ATIVO) {
                Medidor.inicioLacoMundo(jogo.mundo, 0, tes.length, ents.length);
                for (int i = 0; i < ents.length; i++) {
                    if (Medidor.ATIVO) {
                        Medidor.inicioEntidade(jogo.mundo, 0, ents[i], i, 3, null, false);
                    }
                    if (Medidor.ATIVO) {
                        Medidor.fimEntidade(ents[i]);
                    }
                }
                for (int i = 0; i < tes.length; i++) {
                    if (Medidor.ATIVO) {
                        Medidor.inicioTE(jogo.mundo, 0, tes[i], i & 7, -(i & 3));
                    }
                    if (Medidor.ATIVO) {
                        Medidor.fimTE();
                    }
                }
            }
            espera(200000L + r.nextInt(300000));
            // "console" no fim do tick (como executePendingCommands)
            int x = r.nextInt(400);
            if (x == 0) {
                Medidor.comandoMedir(10, false);
            } else if (x == 1) {
                Medidor.comandoParar();
            }
            id = (Long) campo("idMedicao");
            if (id != 0) {
                ids.add(id);
            }
            if (Medidor.profundidade() != 0) {
                pilhaSuja++;
            }
            Marcas.fimTick();
            Medidor.fimTickServidor();
            ticks++;
        }
        fim.set(true);
        rcon.join(5000);
        // encerra o que estiver em andamento
        if (Medidor.ATIVO) {
            Medidor.comandoParar();
        }
        // pedido pendente do rcon: consome
        Marcas.inicioTick();
        Medidor.inicioTickServidor();
        long id = (Long) campo("idMedicao");
        if (id != 0) {
            ids.add(id);
        }
        Marcas.fimTick();
        Medidor.fimTickServidor();
        if (Medidor.ATIVO) {
            Medidor.comandoParar();
        }
        v.sim(Medidor.esperarGravacao(30000L), "ultima gravacao terminou");
        Thread.sleep(2000); // gravacoes anteriores (threads daemon) terminam
        v.sim(erroRcon.get() == null, "thread rcon sem excecao: " + erroRcon.get());
        v.sim(rcon.getState() == Thread.State.TERMINATED, "thread rcon terminou (sem impasse)");
        String est = Medidor.linhaDeEstado();
        v.sim(!est.contains("erro interno"), "nenhum erro interno: " + est.substring(0, Math.min(200, est.length())));
        v.igual(0, pilhaSuja, "pilha de quadros vazia no fim de todo tick");
        File[] fs = pasta.listFiles();
        int json = 0;
        int ruins = 0;
        if (fs != null) {
            for (File f : fs) {
                if (!f.getName().endsWith(".json")) {
                    continue;
                }
                json++;
                Object j = Json.ler(new String(Files.readAllBytes(f.toPath()), "UTF-8"));
                double te = ((Double) Json.caminho(j, "meta", "te_ticks_total"));
                double ent = ((Double) Json.caminho(j, "meta", "ent_ticks_total"));
                double laco = ((Double) Json.caminho(j, "meta", "autoteste", "ticks_com_laco"));
                if (te != 50 * laco || ent != 20 * laco) {
                    ruins++;
                    System.out.println("  contagem inexata em " + f.getName() + ": te=" + te + " ent=" + ent + " laco=" + laco);
                }
            }
        }
        System.out.println("  ticks=" + ticks + " medicoes(ids)=" + ids.size() + " json=" + json + " pedidos rcon medir/parar/status="
                + pedidosRcon[0] + "/" + pedidosRcon[1] + "/" + pedidosRcon[2]);
        v.sim(ids.size() >= 5, "varias medicoes no estresse: " + ids.size());
        v.igual(ids.size(), json, "cada medicao virou um .json (medicoes vistas = arquivos)");
        v.igual(0, ruins, "toda medicao com contagem exata (te = 50 x ticks_com_laco, ent = 20 x)");
    }

    // ---------------------------------------------------------------- 3
    static void desabilitado() throws Exception {
        Medidor.definirHabilitado(false);
        int antes = (Integer) campo("refN");
        for (int t = 0; t < 50; t++) {
            Marcas.inicioTick();
            Medidor.inicioTickServidor();
            Marcas.fimTick();
            Medidor.fimTickServidor();
        }
        int depois = (Integer) campo("refN");
        v.igual(antes, depois, "habilitado=false: ganchos de tick voltam na 1a linha (referencia nao anda)");
        Medidor.definirHabilitado(true);
    }
}
