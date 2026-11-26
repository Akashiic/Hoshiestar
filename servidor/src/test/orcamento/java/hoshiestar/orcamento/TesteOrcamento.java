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

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * O nucleo do orcamento com jogo falso e relogio falso (sem Minecraft): tempo por regiao, estourou/voltou com histerese,
 * isentos, modo medir, heranca na juncao e na divisao, mapa velho e suspensao, alertas, ferramentas de teste, falha,
 * texto do comando. Todo numero aqui e de simulacao (relogio falso), nao de servidor.
 */
public final class TesteOrcamento {

    static final Charset UTF8 = Charset.forName("UTF-8");

    static final class TempoFalso implements Tempo {
        long ns = 5_000_000_000_000L;
        long passo;
        int chamadas;

        @Override
        public long nano() {
            chamadas++;
            long r = ns;
            ns += passo;
            return r;
        }

        @Override
        public long milis() {
            return ns / 1_000_000L;
        }

        void ms(double ms) {
            ns += (long) Math.round(ms * 1e6);
        }
    }

    static final class JogoFalso implements Jogo {
        FotoMundo[] foto = new FotoMundo[0];
        int[][] spawns = new int[0][];
        int[][] jogadores = new int[0][];
        final Map<String, String> mods = new HashMap<String, String>();
        final List<String> logs = new ArrayList<String>();
        final List<String> ops = new ArrayList<String>();
        File pasta;
        boolean falhar;
        int fotos;

        @Override
        public FotoMundo[] fotografar() {
            if (falhar) {
                throw new IllegalStateException("foto falhou de proposito");
            }
            fotos++;
            return foto;
        }

        @Override
        public int[][] spawns() {
            return spawns;
        }

        @Override
        public int[][] jogadores() {
            return jogadores;
        }

        @Override
        public String modDaClasse(String c) {
            return mods.get(c);
        }

        @Override
        public File pastaDeSaida() {
            return pasta;
        }

        @Override
        public void log(String l) {
            logs.add(l);
        }

        @Override
        public void avisarOperadores(String l) {
            ops.add(l);
        }
    }

    /** Um chunk por secao pedida (secao sx: chunk sx*16+1), uma tile em (sx*256+20, 20). */
    static FotoMundo foto(int dim, String classe, int... secoes) {
        FotoMundo f = new FotoMundo(dim);
        for (int sx : secoes) {
            f.chunk(sx * 16 + 1, 1);
            f.tile(sx * 256 + 20, 20);
        }
        if (classe != null) {
            f.tilesClasse = new String[f.nTiles];
            java.util.Arrays.fill(f.tilesClasse, classe);
        }
        return f;
    }

    static Parametros params(double alvo, double reg, double pico, boolean spawn, String dims, String areas, String mods,
            boolean comJogador, double alerta) {
        List<String> av = new ArrayList<String>();
        Parametros p = Parametros.ler(alvo, reg, pico, 4, 40, 100, spawn, dims, areas, mods, "", comJogador, alerta, 5, true, av);
        if (!av.isEmpty()) {
            throw new IllegalStateException("avisos inesperados: " + av);
        }
        return p;
    }

    static Parametros padrao() {
        return Parametros.padrao();
    }

    /** Um servidor simulado: regioes em secoes separadas, cada uma com um custo (ms) por tick, ticks de 50 ms. */
    static final class Cen {
        final TempoFalso t = new TempoFalso();
        final JogoFalso j = new JogoFalso();
        final Nucleo n;
        final int[] secs;
        final double[] custo;
        double baseMs = 2.0;
        final int[] rodadas;
        int ticks;
        /** Atraso acumulado (ms): como o laco do servidor, um tick longo e compensado por ticks curtos sem dormir. */
        double atraso;

        Cen(Parametros p, boolean cortar, boolean teste, double[] custo, int... secs) {
            this.secs = secs;
            this.custo = custo;
            this.rodadas = new int[secs.length];
            j.foto = new FotoMundo[] {foto(0, null, secs)};
            n = new Nucleo(p, j, t, cortar, teste);
        }

        void tick() {
            long ini = t.ns;
            n.inicioTick();
            for (int i = 0; i < secs.length; i++) {
                boolean roda = n.tile(0, secs[i] * 256 + 20, 20);
                if (roda) {
                    t.ms(custo[i]);
                    rodadas[i]++;
                } else {
                    t.ms(0.01);
                }
            }
            n.fimLaco();
            t.ms(baseMs);
            double ms = (t.ns - ini) / 1e6;
            n.fimTick();
            ticks++;
            atraso += ms - 50.0;
            if (atraso < 0) {
                t.ms(-atraso); // adiantado: dorme o que sobra
                atraso = 0;
            }
        }

        void ticks(int k) {
            for (int i = 0; i < k; i++) {
                tick();
            }
        }

        Retrato.Regiao regiao(int sx) {
            return n.retrato().emBloco(0, sx * 256 + 20, 20);
        }
    }

    public static void main(String[] args) throws Exception {
        Verifica v = new Verifica("TesteOrcamento");
        File base = new File(args[0]);
        base.mkdirs();
        try {
            medicao(v);
            estoura(v);
            naoPico(v);
            folgado(v);
            volta(v, false);
            voltaSemFolga(v);
            regiaoPesadaNaoOscila(v);
            protegidos(v);
            modoMedir(v);
            heranca(v);
            mapaVelho(v);
            alertas(v);
            ferramentas(v, base);
            falha(v);
            atribuicao(v);
            percentilDaRegiao(v);
            comando(v, base);
        } catch (Throwable t) {
            v.erro("excecao no teste", t);
        }
        v.fim();
    }

    static void medicao(Verifica v) {
        Cen c = new Cen(padrao(), false, false, new double[] {12, 3}, 0, 20);
        c.ticks(100);
        Retrato r = c.n.retrato();
        v.sim(r != null && r.regioes.length == 2, "100 ticks: duas regioes no retrato");
        Retrato.Regiao a = c.regiao(0), b = c.regiao(20);
        v.sim(Math.abs(a.custoRodadaMs - 12.0) < 0.05 && Math.abs(b.custoRodadaMs - 3.0) < 0.05,
                "custo por rodada medido por regiao: A " + a.custoRodadaMs + ", B " + b.custoRodadaMs);
        v.sim(Math.abs(a.tps - 20.0) < 0.6 && Math.abs(b.tps - 20.0) < 0.6, "TPS por regiao ~20 com ticks de 50 ms: " + a.tps);
        v.sim(Math.abs(r.servidorMediaMs - 17.0) < 0.1 && Math.abs(r.servidorP95Ms - 17.0) < 0.1,
                "servidor: media e p95 = 17 ms (12 + 3 + 2): " + r.servidorMediaMs + " " + r.servidorP95Ms);
        v.sim(Math.abs(r.servidorTps - 20.0) < 0.6, "TPS do servidor ~20: " + r.servidorTps);
        v.igual("normal", a.estado, "A: normal");
        v.sim(a.id != b.id && a.n == 1 && b.n == 1, "ids diferentes, N = 1");
        v.sim(a.caixa[0] == 0 && a.caixa[2] == 255, "caixa de A em blocos: x 0..255");
        v.igual(1, c.n.recalculos(), "um recalculo do mapa em 100 ticks");
        v.sim(r.mapa != null && r.emBloco(0, 20, 20) == a && r.emBloco(0, 99999, 0) == null, "emBloco acha a regiao; fora do mapa: null");
        c.ticks(150);
        v.igual(3, c.n.recalculos(), "250 ticks: recalculos nos ticks 1, 101 e 201");
    }

    static void estoura(Verifica v) {
        Cen c = new Cen(padrao(), true, false, new double[] {60, 3, 3}, 0, 20, 40);
        c.ticks(100);
        v.sim(c.regiao(0).n == 25 && c.regiao(20).n == 1 && c.regiao(40).n == 1,
                "servidor a 68 ms, A a 60 ms (acima do pico de 40): so A corta, direto para N = 25 (A " + c.regiao(0).n + ")");
        v.igual("cortada", c.regiao(0).estado, "A: estado cortada");
        v.igual(100, c.rodadas[0], "nos 100 primeiros ticks A rodou todos");
        c.ticks(1000);
        int rodouA = c.rodadas[0] - 100;
        v.sim(rodouA >= 38 && rodouA <= 42, "depois do corte A roda ~1 a cada 25,06 ticks: " + rodouA + " em 1000");
        v.igual(1100, c.rodadas[1], "B nunca perde um tick");
        v.igual(1100, c.rodadas[2], "C nunca perde um tick");
        c.ticks(400);
        Retrato r = c.n.retrato();
        v.sim(r.servidorP95Ms <= 50.0, "p95 do servidor (ultimos 1200 ticks) <= 50 ms: " + r.servidorP95Ms);
        v.sim(r.servidorMaxMs >= 60.0, "o tick em que A roda continua longo (custo do degrau): maximo " + r.servidorMaxMs);
        v.sim(c.regiao(20).tps > 19.0 && c.regiao(40).tps > 19.0, "as demais regioes >= 19 rodadas/s: " + c.regiao(20).tps);
        v.sim(c.regiao(0).tps < 2.0, "TPS de A cai para ~0,8: " + c.regiao(0).tps);
        v.sim(Math.abs(c.regiao(0).custoRodadaMs - 60.0) < 0.5, "o custo por rodada de A continua 60 ms (nao some)");
        // todo tick e rodada ou pulo: nenhuma tile perdida de vez
        v.igual(1500, c.ticks, "1500 ticks simulados");
    }

    static void naoPico(Verifica v) {
        Cen c = new Cen(padrao(), true, false, new double[] {30, 30, 3}, 0, 20, 40);
        c.ticks(100);
        v.sim(c.regiao(0).n == 2 && c.regiao(20).n == 2 && c.regiao(40).n == 1,
                "30 ms (nao e pico): N sobe no maximo ao dobro por avaliacao: 1 -> 2 (A " + c.regiao(0).n + ")");
        c.ticks(300);
        v.sim(c.regiao(0).n == 2 && c.regiao(20).n == 2, "com o servidor de volta abaixo do alvo, N fica em 2 (nao sobe mais)");
        Cen d = new Cen(padrao(), true, false, new double[] {30, 30, 30}, 0, 20, 40);
        d.ticks(100);
        int n1 = d.regiao(0).n;
        d.ticks(100);
        int n2 = d.regiao(0).n;
        d.ticks(100);
        int n3 = d.regiao(0).n;
        v.sim(n1 == 2 && n2 == 3 && n3 == 3, "tres regioes de 30 ms: N 2, depois 3 (ceil(30/10)) e para: " + n1 + "," + n2 + "," + n3);
    }

    static void folgado(Verifica v) {
        Cen c = new Cen(padrao(), true, false, new double[] {15, 15}, 0, 20);
        c.ticks(1500);
        v.sim(c.regiao(0).n == 1 && c.regiao(20).n == 1 && c.rodadas[0] == 1500,
                "servidor folgado (32 ms < 45): ninguem entra em corte, mesmo acima de 10 ms");
        // so a regiao pesada e acima do orcamento corta; leve nao corta "para abrir espaco"
        Cen d = new Cen(padrao(), true, false, new double[] {70, 9, 9, 9}, 0, 20, 40, 60);
        d.ticks(400);
        v.sim(d.regiao(0).n >= 2 && d.regiao(20).n == 1 && d.regiao(40).n == 1 && d.regiao(60).n == 1,
                "so a regiao acima do orcamento corta; as de 9 ms nao");
    }

    /** Depois do corte, o custo cai: N desce passo a passo (metade a cada 200 ticks) ate 1; nunca sobe. */
    static void volta(Verifica v, boolean x) {
        Cen c = new Cen(padrao(), true, false, new double[] {60, 3}, 0, 20);
        c.ticks(100);
        v.igual(25, c.regiao(0).n, "(volta) A cortada em N = 25");
        c.custo[0] = 1.0;
        List<Integer> serie = new ArrayList<Integer>();
        int ultimo = 25;
        boolean subiu = false;
        for (int i = 0; i < 20; i++) {
            c.ticks(100);
            int n = c.regiao(0).n;
            serie.add(n);
            if (n > ultimo) {
                subiu = true;
            }
            ultimo = n;
        }
        v.sim(!subiu, "(volta) N nunca sobe enquanto o custo esta baixo: " + serie);
        v.sim(serie.get(0) == 25 && serie.get(1) == 12 && serie.get(3) == 6 && serie.get(5) == 3 && serie.get(7) == 1,
                "(volta) N cai a metade a cada 200 ticks (25, 12, 6, 3, 1): " + serie);
        v.igual("normal", c.regiao(0).estado, "(volta) no fim A esta normal");
        int antes = c.rodadas[0];
        c.ticks(100);
        v.igual(antes + 100, c.rodadas[0], "(volta) voltou a rodar todo tick");
    }

    /** Servidor na faixa entre 80% e 100% do alvo (sem folga): so a regra do custo da propria regiao baixa o N. */
    static void voltaSemFolga(Verifica v) {
        Cen c = new Cen(padrao(), true, false, new double[] {60, 3}, 0, 20);
        c.baseMs = 0;
        c.ticks(100);
        v.igual(25, c.regiao(0).n, "(volta sem folga) A cortada em 25");
        c.custo[0] = 1.0;
        c.baseMs = 38.0; // servidor em ~42 ms: dentro da faixa entre 36 e 45, sem folga
        List<Integer> serie = new ArrayList<Integer>();
        for (int i = 0; i < 20; i++) {
            c.ticks(100);
            serie.add(c.regiao(0).n);
        }
        double media = c.n.retrato().servidorMediaMs;
        v.sim(media > 36.0 && media < 45.0, "(volta sem folga) o servidor ficou na faixa 36..45 ms: " + media);
        v.sim(serie.get(serie.size() - 1) == 1 && serie.get(0) == 25, "(volta sem folga) mesmo assim A desce a 1 pelo custo dela: " + serie);
    }

    /** Regiao cujo custo continua alto nao e solta pela folga do servidor (senao o corte oscilaria). */
    static void regiaoPesadaNaoOscila(Verifica v) {
        Cen c = new Cen(padrao(), true, false, new double[] {150, 2}, 0, 20);
        c.ticks(100);
        v.sim(c.regiao(0).n == 25, "A forcada a 150 ms: N = 25 (" + c.regiao(0).n + ")");
        int menor = 99;
        for (int i = 0; i < 30; i++) {
            c.ticks(100);
            menor = Math.min(menor, c.regiao(0).n);
        }
        v.igual(25, menor, "3000 ticks com o servidor folgado: N nunca cai abaixo de 25 (a regiao ainda custa 150 ms)");
        v.sim(c.n.retrato().servidorP95Ms <= 50.0, "p95 do servidor <= 50 ms durante todo o tempo: " + c.n.retrato().servidorP95Ms);
        v.sim(c.regiao(20).tps > 19.0, "a outra regiao segue com >= 19 rodadas/s");
        v.sim(c.regiao(0).pesada, "A esta marcada como pesada (150 ms > alerta de 25)");
    }

    static void protegidos(Verifica v) {
        // spawn da dimensao 0
        Cen c = new Cen(padrao(), true, false, new double[] {60, 40}, 0, 20);
        c.j.spawns = new int[][] {{0, 20, 20}};
        c.ticks(600);
        v.sim(c.regiao(0).estado.equals("protegida") && "spawn".equals(c.regiao(0).protecao) && c.regiao(0).n == 1,
                "regiao do spawn: protegida, N = 1 mesmo a 60 ms com o servidor a 100 ms");
        v.igual(600, c.rodadas[0], "regiao do spawn rodou todos os ticks");
        v.sim(c.regiao(20).n >= 2, "outra regiao de 40 ms com o servidor estourado: cortada (N " + c.regiao(20).n + ")");
        // spawn de outra dimensao nao protege a dimensao 0
        Cen c1 = new Cen(padrao(), true, false, new double[] {60, 3}, 0, 20);
        c1.j.spawns = new int[][] {{1, 20, 20}};
        c1.ticks(200);
        v.sim(c1.regiao(0).n == 25, "spawn listado na dimensao 1 nao protege a regiao da dimensao 0");
        // proteger_spawn = false
        Cen c2 = new Cen(params(45, 10, 40, false, "", "", "", true, 25), true, false, new double[] {60, 3}, 0, 20);
        c2.j.spawns = new int[][] {{0, 20, 20}};
        c2.ticks(200);
        v.sim(c2.regiao(0).n == 25, "proteger_spawn = false: a regiao do spawn corta");
        // area
        Cen a = new Cen(params(45, 10, 40, true, "", "0:5000,0,5300,100", "", true, 25), true, false, new double[] {3, 60}, 0, 20);
        a.ticks(200);
        v.sim("area protegida".equals(a.regiao(20).protecao) && a.regiao(20).n == 1 && a.regiao(0).protecao == null,
                "area: a regiao que toca a caixa 5000..5300 fica protegida");
        // area de outra dimensao nao vale
        Cen a2 = new Cen(params(45, 10, 40, true, "", "7:0,0,99999,99999", "", true, 25), true, false, new double[] {3, 60}, 0, 20);
        a2.ticks(200);
        v.sim(a2.regiao(20).n == 25, "area de outra dimensao nao protege");
        // dimensao
        Cen d = new Cen(params(45, 10, 40, true, "1", "", "", true, 25), true, false, new double[] {60, 3}, 0, 20);
        d.j.foto = new FotoMundo[] {foto(0, null, 0, 20), foto(1, null, 0)};
        d.ticks(100);
        d.n.retrato(); // (so as secoes da dimensao 0 sao varridas pelo cenario)
        v.sim(d.n.retrato().regioes.length == 3, "tres regioes (2 na dim 0, 1 na dim 1)");
        int protegidas = 0;
        for (Retrato.Regiao r : d.n.retrato().regioes) {
            if ("dimensao protegida".equals(r.protecao)) {
                protegidas++;
                v.igual(1, r.dim, "a protegida e a da dimensao 1");
            }
        }
        v.igual(1, protegidas, "uma regiao protegida por dimensao");
        // mod
        Cen m = new Cen(params(45, 10, 40, true, "", "", "ic2, Mekanism", true, 25), true, false, new double[] {60, 60}, 0, 20);
        m.j.foto = new FotoMundo[] {foto(0, "ic2.tileentity.TileX", 0), foto(0, "outra.Classe", 20)};
        m.j.foto[0].tilesX[0] = 20;
        m.j.foto[1].tilesX[0] = 20 * 256 + 20;
        m.j.mods.put("ic2.tileentity.TileX", "IC2-1.2.3");
        m.ticks(200);
        v.sim("mod ic2".equals(m.regiao(0).protecao) && m.regiao(0).n == 1, "regiao com tile de mod listado (ic2): protegida");
        v.sim(m.regiao(20).protecao == null && m.regiao(20).n == 25, "regiao sem tile desse mod: corta normalmente");
        // jogador
        Cen p = new Cen(params(45, 10, 40, true, "", "", "", false, 25), true, false, new double[] {60, 3}, 0, 20);
        p.j.jogadores = new int[][] {{0, 30, 30}};
        p.ticks(200);
        v.sim("jogador".equals(p.regiao(0).protecao) && p.regiao(0).n == 1, "cortar_com_jogador = false: regiao com jogador protegida");
        Cen p2 = new Cen(padrao(), true, false, new double[] {60, 3}, 0, 20);
        p2.j.jogadores = new int[][] {{0, 30, 30}};
        p2.ticks(200);
        v.sim(p2.regiao(0).protecao == null && p2.regiao(0).n == 25, "cortar_com_jogador = true (padrao): regiao com jogador corta");
        // protegida depois de cortada: tira o corte no proximo recalculo
        Cen q = new Cen(padrao(), true, false, new double[] {60, 3}, 0, 20);
        q.ticks(150);
        v.igual(25, q.regiao(0).n, "(protecao tardia) A cortada");
        q.j.spawns = new int[][] {{0, 20, 20}};
        q.ticks(60);
        v.sim(q.regiao(0).n == 1 && "spawn".equals(q.regiao(0).protecao), "spawn passou a valer: o corte de A cai a 1 no proximo recalculo");
    }

    static void modoMedir(Verifica v) {
        Cen c = new Cen(padrao(), false, false, new double[] {60, 3}, 0, 20);
        c.ticks(500);
        v.igual(500, c.rodadas[0], "modo medir: A nunca perde tick");
        v.sim(c.regiao(0).n > 1 && c.regiao(0).estado.equals("estouraria"), "modo medir: A 'estouraria' (N " + c.regiao(0).n + ")");
        v.sim(c.n.rodaEm(0, 20, 20) && c.n.rodaNesteTick(0) && c.n.rodaNesteTick(99) && c.n.rodaEm(0, 999999, 0),
                "rodaEm / rodaNesteTick: sempre true no modo medir, e true para ponto sem regiao");
        c.n.pedirModo(true);
        c.ticks(1);
        v.sim(c.n.cortar(), "o pedido vale no proximo tick");
        int antes = c.rodadas[0];
        c.ticks(1000);
        int rodou = c.rodadas[0] - antes;
        v.sim(rodou >= 35 && rodou <= 45, "modo cortar: A roda ~1 a cada 25 ticks: " + rodou);
        v.igual("cortada", c.regiao(0).estado, "estado vira 'cortada'");
        // rodaEm bate com o que o gancho recebe no mesmo tick
        boolean confere = true;
        for (int i = 0; i < 200; i++) {
            c.n.inicioTick();
            boolean a = c.n.rodaEm(0, 20, 20);
            boolean b = c.n.tile(0, 20, 20);
            confere &= a == b;
            c.n.fimLaco();
            c.n.fimTick();
        }
        v.sim(confere, "rodaEm (pura, sem relogio) e a resposta do gancho concordam em 200 ticks");
        c.n.pedirModo(false);
        c.ticks(2);
        v.sim(!c.n.cortar() && c.n.rodaEm(0, 20, 20), "de volta ao modo medir: volta a rodar tudo");
    }

    static Retrato.Regiao unica(Cen c) {
        return c.n.retrato().regioes.length == 1 ? c.n.retrato().regioes[0] : null;
    }

    static void heranca(Verifica v) {
        Cen c = new Cen(padrao(), true, false, new double[] {60, 3}, 0, 20);
        c.ticks(100);
        Retrato.Regiao a = c.regiao(0), b = c.regiao(20);
        long idA = a.id, idB = b.id;
        v.igual(25, a.n, "(heranca) A cortada em 25");
        // sem mudanca de forma, o recalculo periodico preserva id e estatistica
        c.ticks(5); // passa o recalculo do tick 101
        v.igual(2, c.n.recalculos(), "(heranca) recalculo periodico no tick 101");
        c.ticks(95);
        v.sim(c.regiao(0).id == idA && c.regiao(20).id == idB, "(heranca) regiao identica mantem o id apos o recalculo");
        v.sim(Math.abs(c.regiao(0).custoRodadaMs - 60.0) < 0.5, "(heranca) e a estatistica continua");
        // juncao: chunks ligando A (secao 0) e B (secao 20)
        FotoMundo ponte = foto(0, null, 0, 3, 6, 9, 12, 15, 18, 20);
        c.j.foto = new FotoMundo[] {ponte};
        c.ticks(10);
        v.igual(1, c.n.retrato().regioes.length, "(heranca) ponte de secoes: uma regiao so");
        Retrato.Regiao m = unica(c);
        v.sim(m != null && m.n == 25, "(heranca) na juncao vale o maior N (25): " + (m == null ? -1 : m.n));
        v.igual(Math.min(idA, idB), m == null ? -1 : m.id, "(heranca) a regiao nova fica com o menor id das velhas");
        v.sim(m != null && m.custoRodadaMs >= 59.0, "(heranca) e com a maior medida de custo");
        // divisao: tira a ponte do meio; a parte maior fica com o id, a outra recebe copia com id novo e o mesmo N
        c.j.foto = new FotoMundo[] {foto(0, null, 0, 12, 15, 18, 20)};
        c.ticks(100);
        v.igual(2, c.n.retrato().regioes.length, "(heranca) sem a ponte: duas regioes");
        Retrato.Regiao pequena = c.regiao(0), grande = c.regiao(20);
        v.sim(pequena.id != grande.id && grande.id == m.id, "(heranca) a parte com mais secoes fica com o id da regiao velha");
        v.sim(pequena.n == 25 && grande.n == 25, "(heranca) as duas partes herdam N = 25 (" + pequena.n + "," + grande.n + ")");
        // a histerese tira o corte de quem nao pesa (as duas custam 1 ms agora)
        c.custo[0] = 1.0;
        c.custo[1] = 1.0;
        c.ticks(2000);
        v.sim(c.regiao(0).n == 1 && c.regiao(20).n == 1, "(heranca) sem peso, as duas voltam a N = 1");
        // regiao nova sem velha: N = 1
        c.j.foto = new FotoMundo[] {foto(0, null, 0, 12, 15, 18, 20, 60)};
        c.ticks(100);
        v.sim(c.n.retrato().regioes.length == 3 && c.regiao(60).n == 1, "(heranca) regiao nova comeca em N = 1");
    }

    static void mapaVelho(Verifica v) {
        // A cortada; aparece uma secao desconhecida perto dela (a 3 secoes): A fica suspensa ate o recalculo
        Cen c = new Cen(padrao(), true, false, new double[] {60, 3}, 0, 20);
        c.ticks(105);
        v.igual(25, c.regiao(0).n, "(mapa velho) A cortada");
        v.igual(2, c.n.recalculos(), "(mapa velho) dois recalculos ate o tick 105 (1 e 101)");
        int antes = c.rodadas[0];
        // um tick com um tile numa secao desconhecida (secao 3: a 3 de A)
        c.n.inicioTick();
        c.n.tile(0, 3 * 256 + 20, 20);
        c.n.fimLaco();
        c.n.fimTick();
        v.igual(1, c.n.desconhecidasVistas(), "(mapa velho) uma secao desconhecida vista");
        c.n.inicioTick(); // repete: nao conta de novo
        c.n.tile(0, 3 * 256 + 21, 20);
        c.n.tile(0, 3 * 256 + 22, 21);
        c.n.fimLaco();
        c.n.fimTick();
        v.igual(1, c.n.desconhecidasVistas(), "(mapa velho) a mesma secao nao conta duas vezes");
        c.t.ms(50);
        int rodou = 0;
        for (int i = 0; i < 12; i++) { // ate o tick 120: antes do minimo de 20 ticks depois do recalculo 101
            c.tick();
        }
        rodou = c.rodadas[0] - antes;
        v.sim(rodou >= 12, "(mapa velho) A suspensa roda todo tick ate o recalculo: " + rodou + " em 12");
        v.igual(2, c.n.recalculos(), "(mapa velho) o recalculo antecipado espera 20 ticks depois do anterior");
        c.ticks(10);
        v.igual(3, c.n.recalculos(), "(mapa velho) recalculo antecipado no tick 121");
        // sem a secao 3 na foto, A volta ao corte (a suspensao so dura ate o recalculo)
        int a2 = c.rodadas[0];
        c.ticks(500);
        int r2 = c.rodadas[0] - a2;
        v.sim(r2 >= 15 && r2 <= 25, "(mapa velho) depois do recalculo A volta a 1 a cada 25: " + r2 + " em 500");
        // variante: a secao entra na foto -> a regiao A ganha a secao e mantem N
        Cen d = new Cen(padrao(), true, false, new double[] {60, 3}, 0, 20);
        d.ticks(105);
        d.n.inicioTick();
        d.n.tile(0, 2 * 256 + 20, 20);
        d.n.fimLaco();
        d.n.fimTick();
        d.j.foto = new FotoMundo[] {foto(0, null, 0, 2, 20)};
        d.ticks(30);
        v.igual(25, d.regiao(0).n, "(mapa velho) A com a secao nova no mapa mantem N = 25");
        v.igual(2, d.regiao(0).secoes, "(mapa velho) e agora tem 2 secoes");
        // secao desconhecida longe de tudo: so pede recalculo, ninguem e suspenso
        Cen e = new Cen(padrao(), true, false, new double[] {60, 3}, 0, 20);
        e.ticks(105);
        int antesE = e.rodadas[0];
        e.n.inicioTick();
        e.n.tile(0, 10 * 256 + 20, 20);
        e.n.fimLaco();
        e.n.fimTick();
        e.ticks(15);
        v.sim(e.rodadas[0] - antesE <= 2, "(mapa velho) secao desconhecida longe de A (a 10 secoes): A segue cortada");
        e.ticks(10);
        v.igual(3, e.n.recalculos(), "(mapa velho) mas o recalculo antecipado acontece");
        // dimensao nova (que o mapa nao conhece): pede recalculo
        Cen f = new Cen(padrao(), true, false, new double[] {3}, 0);
        f.ticks(3);
        f.n.inicioTick();
        f.n.tile(9, 20, 20);
        f.n.fimLaco();
        f.n.fimTick();
        f.ticks(25);
        v.igual(2, f.n.recalculos(), "(mapa velho) tile em dimensao nova pede recalculo (tick 21 depois do primeiro)");
        // tabela de desconhecidas cheia: suspende tudo
        Cen g = new Cen(padrao(), true, false, new double[] {60, 3}, 0, 20);
        g.ticks(105);
        g.n.inicioTick();
        for (int i = 0; i < 5000; i++) {
            g.n.tile(0, (500 + i * 8) * 256, 0);
        }
        g.n.fimLaco();
        g.n.fimTick();
        int antesG = g.rodadas[0];
        for (int i = 0; i < 10; i++) {
            g.tick();
        }
        v.sim(g.rodadas[0] - antesG == 10, "(mapa velho) tabela cheia de secoes desconhecidas: tudo roda todo tick (seguranca)");
    }

    static void alertas(Verifica v) {
        Cen c = new Cen(padrao(), false, false, new double[] {30, 2}, 0, 20);
        c.ticks(100);
        v.igual(1, c.j.logs.size(), "(alerta) uma linha no log quando a regiao passa de 25 ms");
        String s = c.j.logs.isEmpty() ? "" : c.j.logs.get(0);
        v.sim(s.contains("comecou a pesar") && s.contains("dim 0") && s.contains("x 0 a 255") && s.contains("z 0 a 255")
                && s.contains("centro 127,127") && s.contains("chunks") && s.contains("tiles") && s.contains("30.0 ms por rodada")
                && s.contains("so medindo"), "(alerta) texto: dimensao, caixa, centro, contagens, ms e acao: " + s);
        v.sim(c.j.ops.size() == 1 && c.j.ops.get(0).equals(s), "(alerta) os operadores recebem o mesmo texto");
        c.ticks(300);
        v.igual(1, c.j.logs.size(), "(alerta) nao repete enquanto a regiao continua pesada");
        c.custo[0] = 1.0;
        c.ticks(100);
        v.igual(1, c.j.logs.size(), "(alerta) uma avaliacao leve so nao basta para 'voltou'");
        c.ticks(300);
        v.sim(c.j.logs.size() == 2 && c.j.logs.get(1).contains("voltou ao normal"), "(alerta) 'voltou ao normal' depois de duas avaliacoes leves");
        // limitador: o mesmo par (regiao, tipo) so a cada 5 minutos
        c.custo[0] = 30.0;
        c.ticks(100);
        v.igual(2, c.j.logs.size(), "(alerta) 'comecou a pesar' de novo dentro de 5 min: barrado pelo limitador");
        c.ticks(6000); // 5 minutos de relogio falso
        v.sim(c.j.logs.size() >= 2, "(alerta) segue sem excesso");
        // corte: mensagem diz quanto
        Cen d = new Cen(padrao(), true, false, new double[] {60, 3}, 0, 20);
        d.ticks(100);
        boolean corte = false;
        for (String l : d.j.logs) {
            corte |= l.contains("passou a ticar 1 a cada 25");
        }
        v.sim(corte, "(alerta) no modo cortar, a mensagem diz 'passou a ticar 1 a cada 25': " + d.j.logs);
        Cen e = new Cen(padrao(), false, false, new double[] {60, 3}, 0, 20);
        e.ticks(100);
        boolean passaria = false;
        for (String l : e.j.logs) {
            passaria |= l.contains("passaria a ticar 1 a cada");
        }
        v.sim(passaria, "(alerta) no modo medir, a mensagem diz 'passaria a ticar': " + e.j.logs);
        // alerta_ops = false: so o log
        List<String> av = new ArrayList<String>();
        Parametros semOps = Parametros.ler(45, 10, 40, 4, 40, 100, true, "", "", "", "", true, 25, 5, false, av);
        Cen f = new Cen(semOps, false, false, new double[] {30, 2}, 0, 20);
        f.ticks(100);
        v.sim(f.j.logs.size() == 1 && f.j.ops.isEmpty(), "(alerta) alerta_ops = false: so o log");
        // no maximo 10 alertas por minuto no total, com muitas regioes pesadas
        double[] pesos = new double[30];
        int[] secs = new int[30];
        for (int i = 0; i < 30; i++) {
            pesos[i] = 30;
            secs[i] = i * 20;
        }
        Cen g = new Cen(padrao(), false, false, pesos, secs);
        g.baseMs = 0;
        g.ticks(100);
        v.igual(10, g.j.logs.size(), "(alerta) 30 regioes pesadas ao mesmo tempo: no maximo 10 alertas no minuto");
        for (String l : g.j.logs) {
            v.sim(!l.toLowerCase().contains("jogador") && !l.matches(".*\\d+\\.\\d+\\.\\d+\\.\\d+.*"), "(alerta) sem nome de jogador nem IP");
        }
    }

    static void ferramentas(Verifica v, File base) throws Exception {
        File pasta = new File(base, "hoshiestar");
        // com a ferramenta de teste: a primeira tile da regiao gasta X ms por rodada
        Cen c = new Cen(padrao(), false, true, new double[] {0, 0}, 0, 20);
        c.j.pasta = pasta;
        c.ticks(3);
        long idA = c.regiao(0).id;
        c.t.passo = 100_000; // cada leitura do relogio anda 0,1 ms, para a espera ocupada terminar
        c.n.pedirEspera(idA, 5.0);
        c.ticks(100);
        c.t.passo = 0;
        Retrato.Regiao a = c.regiao(0);
        v.sim(a.custoRodadaMs >= 5.0 && a.custoRodadaMs < 6.0, "(ferramenta) a regiao gasta ~5 ms por rodada: " + a.custoRodadaMs);
        v.sim(c.regiao(20).custoRodadaMs < 0.5, "(ferramenta) a outra nao");
        v.sim(a.esperaMs == 5.0, "(ferramenta) o retrato mostra a espera");
        // gasta uma vez por rodada, so na primeira tile
        Cen u = new Cen(padrao(), false, true, new double[] {0}, 0);
        u.ticks(2);
        u.t.passo = 100_000;
        u.n.pedirEspera(u.regiao(0).id, 2.0);
        u.n.inicioTick();
        long ini = u.t.ns;
        u.n.tile(0, 20, 20);
        u.n.tile(0, 21, 20);
        u.n.tile(0, 22, 20);
        double gasto = (u.t.ns - ini) / 1e6;
        u.n.fimLaco();
        u.n.fimTick();
        u.t.passo = 0;
        v.sim(gasto >= 2.0 && gasto < 3.0, "(ferramenta) tres tiles na mesma rodada: gasta 2 ms uma vez so (" + gasto + ")");
        u.n.pedirEspera(-1, 0);
        u.ticks(2);
        v.igual(0.0, u.regiao(0).esperaMs, "(ferramenta) 'parar' zera a espera");
        // sem a propriedade de teste, a espera nao existe
        Cen s = new Cen(padrao(), false, false, new double[] {0}, 0);
        s.ticks(2);
        s.t.passo = 100_000;
        s.n.pedirEspera(s.regiao(0).id, 5.0);
        s.ticks(10);
        s.t.passo = 0;
        v.sim(s.regiao(0).custoRodadaMs < 0.5, "(ferramenta) sem -Dhoshiestar.orcamento.teste nada e gasto: " + s.regiao(0).custoRodadaMs);
        // registro CSV
        Cen r = new Cen(padrao(), false, true, new double[] {4, 1}, 0, 20);
        r.j.pasta = pasta;
        r.ticks(2);
        r.n.pedirRegistro(1, "teste-registro.csv");
        r.ticks(10); // dura 1 s de relogio falso = 20 ticks de 50 ms
        r.ticks(30);
        List<String> linhas = Files.readAllLines(new File(pasta, "teste-registro.csv").toPath(), UTF8);
        v.igual("tick,regiao,rodou,ms", linhas.get(0), "(registro) cabecalho");
        int servidor = 0, regs = 0;
        for (String l : linhas.subList(1, linhas.size())) {
            String[] p = l.split(",");
            if (p[1].equals("-1")) {
                servidor++;
            } else {
                regs++;
            }
        }
        v.sim(servidor >= 18 && servidor <= 24 && regs == servidor * 2, "(registro) uma linha por tick (" + servidor + ") e por regiao (" + regs + ")");
        v.sim(linhas.get(1).split(",").length == 4, "(registro) quatro colunas");
        boolean temMs = false;
        for (String l : linhas) {
            temMs |= l.endsWith(",1,4.000") || l.endsWith(",1,1.000");
        }
        v.sim(temMs, "(registro) custo por regiao gravado (4.000 e 1.000 ms)");
        v.sim(r.j.logs.toString().contains("registro fechado"), "(registro) fecha sozinho");
    }

    static void falha(Verifica v) {
        Cen c = new Cen(padrao(), true, false, new double[] {60}, 0);
        c.j.falhar = true;
        c.n.inicioTick();
        v.sim(!c.n.ativo(), "(falha) erro da foto desliga o orcamento");
        v.sim(c.n.tile(0, 20, 20) && c.n.entidade(0, 5, 5) && c.n.agendado(0, 5, 5) && c.n.chunkAleatorio(0, 1, 1)
                && c.n.rodaEm(0, 20, 20), "(falha) desligado: todos os ganchos devolvem 'roda' e nada quebra");
        c.n.fimLaco();
        c.n.fimTick();
        c.n.inicioTick();
        v.sim(c.n.retrato() == null, "(falha) nenhum retrato");
        int n = 0;
        for (String l : c.j.logs) {
            if (l.contains("DESLIGADO")) {
                n++;
            }
        }
        v.igual(1, n, "(falha) uma linha de log so");
        String[] resp = ComandoRegioes.executar(c.n, new String[0], null, null);
        v.sim(resp.length == 1 && resp[0].contains("DESLIGADO"), "(falha) o comando diz que desligou");
        // antes do primeiro tick (sem mapa): os ganchos so devolvem true
        Cen d = new Cen(padrao(), true, false, new double[] {60}, 0);
        v.sim(d.n.tile(0, 20, 20) && d.n.rodaEm(0, 20, 20) && d.n.retrato() == null, "(falha) antes do primeiro tick os ganchos nao fazem nada");
        d.n.fimLaco();
        d.n.fimTick();
        v.sim(d.n.retrato() == null, "(falha) fimTick antes do inicio: nada");
    }

    static void atribuicao(Verifica v) {
        // tempo atribuido a regiao da vez, com trocas A, B, A, e sem regiao no meio
        Cen c = new Cen(padrao(), false, false, new double[] {0, 0}, 0, 20);
        c.ticks(1);
        for (int i = 0; i < 100; i++) {
            c.n.inicioTick();
            c.n.tile(0, 20, 20);
            c.t.ms(5);
            c.n.tile(0, 20 * 256 + 20, 20);
            c.t.ms(7);
            c.n.tile(0, 21, 20);
            c.t.ms(3);
            c.n.entidade(0, 90 * 256, 0); // sem regiao: o tempo dele nao e de ninguem
            c.t.ms(100);
            c.n.fimLaco();
            c.t.ms(50); // depois do laco: de ninguem
            c.n.fimTick();
            c.t.ms(10);
        }
        Retrato.Regiao a = c.regiao(0), b = c.regiao(20);
        v.sim(Math.abs(a.custoRodadaMs - 8.0) < 0.15 && Math.abs(b.custoRodadaMs - 7.0) < 0.15,
                "(tempo) A 5+3 = 8 ms, B 7 ms; o tempo fora de regiao nao e de ninguem: " + a.custoRodadaMs + " " + b.custoRodadaMs);
        // o relogio so e lido quando a regiao troca
        Cen d = new Cen(padrao(), false, false, new double[] {0}, 0);
        d.ticks(1);
        d.n.inicioTick();
        int antes = d.t.chamadas;
        for (int i = 0; i < 1000; i++) {
            d.n.tile(0, 20 + (i % 200), 20);
        }
        int lidas = d.t.chamadas - antes;
        d.n.fimLaco();
        d.n.fimTick();
        v.sim(lidas <= 1, "(tempo) 1000 tiles na mesma regiao: o relogio foi lido " + lidas + " vez(es) no laco");
        // entidade e tile na mesma regiao seguidos (dois lacos): sem troca
        d.n.inicioTick();
        int a0 = d.t.chamadas;
        d.n.entidade(0, 20, 20);
        d.n.fimLaco();
        d.n.tile(0, 20, 20);
        d.n.fimLaco();
        v.sim(d.t.chamadas - a0 <= 4, "(tempo) dois lacos seguidos: leituras do relogio so nas trocas e nos fins de laco");
        d.n.fimTick();
        // as 4 familias de gancho trocam a regiao
        Cen e = new Cen(padrao(), false, false, new double[] {0, 0}, 0, 20);
        e.ticks(1);
        for (int i = 0; i < 100; i++) {
            e.n.inicioTick();
            e.n.tile(0, 20, 20);
            e.t.ms(1);
            e.n.fimLaco();
            e.n.entidade(0, 20, 20);
            e.t.ms(2);
            e.n.fimLaco();
            e.n.agendado(0, 20, 20);
            e.t.ms(3);
            e.n.fimLaco();
            e.n.chunkAleatorio(0, 1, 1);
            e.t.ms(4);
            e.n.fimLaco();
            e.n.fimTick();
            e.t.ms(40);
        }
        v.sim(Math.abs(e.regiao(0).custoRodadaMs - 10.0) < 0.15, "(tempo) tile 1 + entidade 2 + agendado 3 + chunk aleatorio 4 = 10 ms: " + e.regiao(0).custoRodadaMs);
    }

    static void percentilDaRegiao(Verifica v) {
        Cen c = new Cen(padrao(), false, false, new double[] {1}, 0);
        for (int i = 0; i < 100; i++) {
            c.custo[0] = i < 95 ? 1.0 : 100.0;
            c.tick();
        }
        v.sim(Math.abs(c.regiao(0).p95RodadaMs - 1.0) < 0.01, "(p95) 95 rodadas de 1 ms e 5 de 100: p95 = 1 ms: " + c.regiao(0).p95RodadaMs);
        Cen d = new Cen(padrao(), false, false, new double[] {1}, 0);
        for (int i = 0; i < 100; i++) {
            d.custo[0] = i < 94 ? 1.0 : 100.0;
            d.tick();
        }
        v.sim(Math.abs(d.regiao(0).p95RodadaMs - 100.0) < 0.01, "(p95) 94 de 1 ms e 6 de 100: p95 = 100 ms: " + d.regiao(0).p95RodadaMs);
        v.sim(d.regiao(0).maxRodadaMs >= 100.0, "(p95) maior rodada vista: 100 ms");
    }

    static void comando(Verifica v, File base) {
        Cen c = new Cen(padrao(), false, true, new double[] {60, 3}, 0, 20);
        c.j.foto = new FotoMundo[] {foto(0, "mekanism.TileA", 0, 20)};
        c.j.pasta = new File(base, "hoshiestar");
        c.ticks(100);
        String[] r = ComandoRegioes.executar(c.n, new String[0], null, null);
        v.sim(r[0].contains("modo medir") && r[0].contains("TPS") && r[0].contains("p95") && r[1].contains("2 regiao(oes)"),
                "(comando) resumo: modo, TPS e p95 do servidor, numero de regioes: " + r[0]);
        v.sim(r.length >= 4 && r[2].contains("#") && r[2].contains("rodada") && r[2].contains("estouraria"), "(comando) lista as mais pesadas primeiro: " + r[2]);
        long idA = c.regiao(0).id;
        r = ComandoRegioes.executar(c.n, new String[] {String.valueOf(idA)}, null, null);
        v.sim(String.join("\n", r).contains("Regiao " + idA) && String.join("\n", r).contains("mekanism.TileA=1"),
                "(comando) detalhe por id mostra as classes de tile mais comuns");
        r = ComandoRegioes.executar(c.n, new String[] {"99"}, null, null);
        v.sim(r[0].contains("nao ha regiao 99"), "(comando) id que nao existe");
        r = ComandoRegioes.executar(c.n, new String[] {"aqui"}, null, null);
        v.sim(r[0].contains("so vale para jogador"), "(comando) 'aqui' no console");
        r = ComandoRegioes.executar(c.n, new String[] {"aqui"}, new ComandoRegioes.Posicao(0, 30, 30), null);
        v.sim(String.join("\n", r).contains("Regiao " + idA), "(comando) 'aqui' acha a regiao do jogador");
        r = ComandoRegioes.executar(c.n, new String[] {"aqui"}, new ComandoRegioes.Posicao(0, 99999, 30), null);
        v.sim(r[0].contains("aqui nao ha regiao"), "(comando) 'aqui' fora do mapa: nunca cortado");
        r = ComandoRegioes.executar(c.n, new String[] {"modo", "cortar"}, null, null);
        v.sim(r[0].contains("modo cortar pedido") && r[0].contains("1 a cada N") && !r[0].contains("AVISO"), "(comando) modo cortar: pedido, explica o corte (os ganchos cortam)");
        c.ticks(1);
        v.sim(c.n.cortar(), "(comando) o modo mudou");
        r = ComandoRegioes.executar(c.n, new String[] {"modo", "xis"}, null, null);
        v.sim(r[0].startsWith("uso:"), "(comando) modo invalido");
        // ferramentas de teste
        r = ComandoRegioes.executar(c.n, new String[] {"teste", String.valueOf(idA), "5"}, null, null);
        v.sim(r[0].contains("passa a gastar 5.0 ms"), "(comando) teste <id> <ms>: " + r[0]);
        r = ComandoRegioes.executar(c.n, new String[] {"teste", "aqui", "7"}, new ComandoRegioes.Posicao(0, 30, 30), null);
        v.sim(r[0].contains("regiao " + idA), "(comando) teste aqui <ms>");
        r = ComandoRegioes.executar(c.n, new String[] {"teste", "0", "0", "3"}, null, null);
        v.sim(r[0].contains("passa a gastar"), "(comando) teste x z <ms> (dimensao 0)");
        r = ComandoRegioes.executar(c.n, new String[] {"teste", String.valueOf(idA), "9999"}, null, null);
        v.sim(r[0].contains("de 0 a 2000"), "(comando) teste com ms fora da faixa");
        r = ComandoRegioes.executar(c.n, new String[] {"teste", "parar"}, null, null);
        v.sim(r[0].contains("desligadas"), "(comando) teste parar");
        r = ComandoRegioes.executar(c.n, new String[] {"registro", "5"}, null, null);
        v.sim(r[0].contains("orcamento-registro-") && r[0].contains(".csv"), "(comando) registro <s>");
        r = ComandoRegioes.executar(c.n, new String[] {"registro", "0"}, null, null);
        v.sim(r[0].startsWith("uso:"), "(comando) registro com 0 s: uso");
        final List<String> chamadas = new ArrayList<String>();
        ComandoRegioes.FerramentasJogo fj = new ComandoRegioes.FerramentasJogo() {
            @Override
            public String contar(long id, String arq) {
                chamadas.add("contar " + id + " " + arq);
                return "ok contar";
            }

            @Override
            public String foto(String arq, long tick, long[] ex) {
                chamadas.add("foto " + arq + " " + tick + " " + ex.length);
                return "ok foto";
            }
        };
        r = ComandoRegioes.executar(c.n, new String[] {"contar", "3", "a.txt"}, null, fj);
        v.sim(r[0].equals("ok contar") && chamadas.get(0).equals("contar 3 a.txt"), "(comando) contar entrega ao jogo");
        r = ComandoRegioes.executar(c.n, new String[] {"foto", "f.txt", "6000", "1", "2"}, null, fj);
        v.sim(r[0].equals("ok foto") && chamadas.get(1).equals("foto f.txt 6000 2"), "(comando) foto <arq> <tick> [excluir]");
        r = ComandoRegioes.executar(c.n, new String[] {"contar", "3", "../x"}, null, fj);
        v.sim(r[0].startsWith("uso:") && chamadas.size() == 2, "(comando) nome de arquivo com '..' e recusado (so grava em hoshiestar/)");
        r = ComandoRegioes.executar(c.n, new String[] {"foto", "a/b", "5"}, null, fj);
        v.sim(r[0].startsWith("uso:") && chamadas.size() == 2, "(comando) nome com barra e recusado");
        // sem a propriedade de teste os subcomandos nem existem
        Cen s = new Cen(padrao(), false, false, new double[] {3}, 0);
        s.ticks(100);
        for (String sub : new String[] {"teste", "registro", "contar", "foto"}) {
            r = ComandoRegioes.executar(s.n, new String[] {sub, "1", "2"}, null, fj);
            v.sim(r[0].startsWith("uso:") && !r[0].contains("teste <id"), "(comando) sem -D de teste: '" + sub + "' nao existe");
        }
        v.sim(!ComandoRegioes.ajuda(false).contains("contar") && ComandoRegioes.ajuda(true).contains("contar"), "(comando) a ajuda so cita as ferramentas com a propriedade");
        // desligado (nucleo nulo)
        r = ComandoRegioes.executar(null, new String[0], null, null);
        v.sim(r[0].contains("orcamento.ligado: false"), "(comando) orcamento desligado: diz como ligar");
        // status
        v.sim(c.n.linhaDeEstado().contains("regiao(oes)") && c.n.linhaDeEstado().contains("modo cortar"), "(status) linha de estado: " + c.n.linhaDeEstado());
    }
}
