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

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Testes isolados do medidor, sem Minecraft, com a MESMA logica que roda no servidor (Medidor, Coleta,
 * TabelaLong, Relatorio): a "thread do servidor" e a thread do teste, que chama os ganchos na mesma ordem
 * dos patches (inicioTick, laco do mundo, inicioTE/fimTE, inicioEntidade/fimEntidade, fimTick), com objetos
 * falsos no lugar das tile entities e entidades e um {@link Jogo} falso. O tempo do tick do servidor e um
 * relogio virtual (inicioTick/fimTick recebem o instante), o que deixa a valvula de sobrecarga
 * deterministica.
 *
 * <p>Uso: {@code java hoshiestar.medidor.TesteMedidor <pasta de trabalho>}.
 */
public final class TesteMedidor {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final long MS = 1000000L;

    private static Verifica v;
    private static File base;
    private static JogoFalso jogo;
    /** Relogio virtual do tick do servidor (ns). */
    private static long relogio;

    // ---- objetos falsos do jogo ----
    static final class TileEntityFalsa {
        boolean invalida;
    }

    static final class OutraTileEntityFalsa {
        boolean invalida;
    }

    static final class EntidadeFalsa {
        boolean morta;
        final String nome;

        EntidadeFalsa(String nome) {
            this.nome = nome;
        }

        @Override
        public String toString() {
            return nome; // o relatorio nunca deve usar isto (nome de jogador, UUID, IP)
        }
    }

    static final class MundoFalso {
        final int dim;

        MundoFalso(int dim) {
            this.dim = dim;
        }
    }

    static class JogoFalso implements Jogo {
        final List<Mundo> mundos = new ArrayList<Mundo>();
        File pasta;

        @Override
        public boolean morto(Object o, int tipo) {
            if (o instanceof TileEntityFalsa) {
                return ((TileEntityFalsa) o).invalida;
            }
            if (o instanceof OutraTileEntityFalsa) {
                return ((OutraTileEntityFalsa) o).invalida;
            }
            return o instanceof EntidadeFalsa && ((EntidadeFalsa) o).morta;
        }

        @Override
        public List<Mundo> mundos() {
            return new ArrayList<Mundo>(mundos);
        }

        @Override
        public File pastaDeSaida() {
            return pasta;
        }
    }

    public static void main(String[] args) throws Exception {
        base = new File(args.length > 0 ? args[0] : "medidor-teste").getAbsoluteFile();
        apagar(base);
        base.mkdirs();
        v = new Verifica("TesteMedidor");
        jogo = new JogoFalso();
        Medidor.definirJogo(jogo, "firewall", "teste");
        relogio = System.nanoTime();
        try {
            tabela();
            chavesEColeta();
            argumentos();
            contagemExataEChunks();
            aninhamentoEExcecao();
            pilhaCheia();
            autotesteSemLacoENaoLigado();
            robustez();
            pedidosDeOutraThread();
            valvulaTempoDeTick();
            valvulaCusto();
            desabilitado();
            pastaReserva();
            motivos();
            falhaInternaDesliga();
        } catch (Throwable t) {
            v.erro("excecao inesperada", t);
        }
        v.fim();
    }

    // =====================================================================
    // simulacao de ticks
    // =====================================================================

    private static void inicioTick() {
        Medidor.inicioTick(relogio);
    }

    private static void fimTick(long duracaoMs) {
        relogio += duracaoMs * MS;
        Medidor.fimTick(relogio);
    }

    /** Um tick do servidor com o laco de um mundo: cada TE e cada entidade tickada uma vez. */
    private static void tickComLaco(MundoFalso m, TileEntityFalsa[] tes, int[][] chunkTe, EntidadeFalsa[] ents,
            int[][] chunkEnt, long duracaoMs) {
        inicioTick();
        if (Medidor.ATIVO) {
            Medidor.inicioLacoMundo(m, m.dim, tes.length, ents.length);
        }
        for (int i = 0; i < ents.length; i++) {
            if (Medidor.ATIVO) {
                Medidor.inicioEntidade(m, m.dim, ents[i], chunkEnt[i][0], chunkEnt[i][1], null, false);
            }
            if (Medidor.ATIVO) {
                Medidor.fimEntidade(ents[i]);
            }
        }
        for (int i = 0; i < tes.length; i++) {
            if (Medidor.ATIVO) {
                Medidor.inicioTE(m, m.dim, tes[i], chunkTe[i][0], chunkTe[i][1]);
            }
            if (Medidor.ATIVO) {
                Medidor.fimTE();
            }
        }
        fimTick(duracaoMs);
    }

    /** Ticks sem medicao (alimentam a referencia da valvula). */
    private static void ticksOciosos(int n, long duracaoMs) {
        for (int i = 0; i < n; i++) {
            inicioTick();
            fimTick(duracaoMs);
        }
    }

    private static File pastaNova(String nome) {
        File p = new File(base, nome);
        apagar(p);
        jogo.pasta = p;
        // o relogio virtual anda 20 a 40 ms por tick simulado, bem mais rapido que o real; o fim da medicao por
        // tempo usa o relogio real do inicio: cada teste comeca com os dois juntos
        relogio = System.nanoTime();
        return p;
    }

    /** Espera a gravacao e devolve {json, txt} da pasta (o unico par que houver). */
    private static String[] lerArquivos(File pasta) throws Exception {
        v.sim(Medidor.esperarGravacao(30000L), "gravacao terminou em ate 30 s");
        File[] fs = pasta.listFiles();
        String json = null;
        String txt = null;
        int n = 0;
        if (fs != null) {
            for (File f : fs) {
                if (f.getName().endsWith(".json")) {
                    json = new String(Files.readAllBytes(f.toPath()), UTF8);
                    n++;
                } else if (f.getName().endsWith(".txt")) {
                    txt = new String(Files.readAllBytes(f.toPath()), UTF8);
                } else {
                    v.sim(false, "arquivo inesperado na pasta de saida: " + f.getName());
                }
            }
        }
        v.igual(1, n, "um par de arquivos medicao-*.json/.txt em " + pasta.getName());
        return new String[] {json, txt};
    }

    private static double num(Object raiz, String... caminho) {
        Object o = Json.caminho(raiz, caminho);
        return o instanceof Double ? (Double) o : Double.NaN;
    }

    // =====================================================================
    // testes
    // =====================================================================

    private static void tabela() {
        TabelaLong t = new TabelaLong(16, 1000);
        for (int i = 0; i < 900; i++) {
            t.somar(i * 7919L - 3000L, TabelaLong.TE, 10);
            t.somar(i * 7919L - 3000L, TabelaLong.ENT, 5);
        }
        v.igual(900, t.tamanho(), "TabelaLong: 900 chaves distintas depois de crescer de 16");
        long te = 0;
        long ent = 0;
        long teT = 0;
        for (int i = 0; i < t.capacidade(); i++) {
            if (t.ocupado(i)) {
                te += t.teNs(i);
                ent += t.entNs(i);
                teT += t.teTicks(i);
            }
        }
        v.sim(te == 9000 && ent == 4500 && teT == 900, "TabelaLong: somas por chave preservadas no crescimento (te=" + te
                + ", ent=" + ent + ", ticks=" + teT + ")");
        for (int i = 0; i < 200; i++) {
            t.somar(1000000L + i, TabelaLong.TE, 1);
        }
        v.igual(1000, t.tamanho(), "TabelaLong: limite de 1000 chaves respeitado");
        v.sim(t.chavesRecusadas == 100 && t.outrosTeTicks == 100 && t.outrosTeNs == 100,
                "TabelaLong: as 100 chaves alem do limite foram para 'outros' (recusadas=" + t.chavesRecusadas + ")");
    }

    private static void chavesEColeta() {
        int[][] casos = {{0, 0}, {-1, -1}, {12345, -67890}, {-(1 << 26), (1 << 26) - 1}, {33554431, -33554432}};
        boolean ok = true;
        for (int[] c : casos) {
            long k = Coleta.chaveChunk(1023, c[0], c[1]);
            ok &= Coleta.xDaChave(k) == c[0] && Coleta.zDaChave(k) == c[1] && Coleta.dimDaChave(k) == 1023;
        }
        v.sim(ok, "chave de chunk: ida e volta com coordenadas negativas e nos extremos de 27 bits");
        long k = Coleta.chaveSecaoClasse(5, -300, 400, 8000);
        v.sim(Coleta.sxDaChaveSC(k) == -300 && Coleta.szDaChaveSC(k) == 400 && Coleta.classeDaChaveSC(k) == 8000,
                "chave secao/classe: ida e volta");

        Coleta c = new Coleta(30);
        Object w0 = new Object();
        Object w1 = new Object();
        int d0 = c.indiceDim(w0, 0);
        int d1 = c.indiceDim(w1, -1);
        int d7 = c.indiceDim(new Object(), 7000); // fora do cache rapido
        v.sim(d0 != d1 && c.indiceDim(w0, 0) == d0 && c.dimId[d1] == -1 && c.dimId[d7] == 7000,
                "Coleta: indice de dimensao por mundo (cache) e por numero, inclusive fora do cache rapido");
        c.adicionar(Coleta.TE, d0, 2, 5, String.class, 100, false);
        c.adicionar(Coleta.TE, d0, 2, 5, String.class, 50, false);
        c.adicionar(Coleta.ENT, d1, -17, 3, Integer.class, 70, true);
        c.adicionar(Coleta.TE, d0, 1 << 27, 0, String.class, 9, false); // fora da faixa de 27 bits
        v.sim(c.teNsTotal == 159 && c.teTicksTotal == 3 && c.entNsTotal == 70 && c.ticksJogadorRede == 1
                && c.posicoesForaDoLimite == 1 && c.chunks.tamanho() == 2,
                "Coleta: totais, tick pela rede e posicao fora da faixa no balde 'outros'");
        v.igual(3, c.secoes.tamanho(), "Coleta: 3 secoes distintas (chunk -17 cai na secao -2; o chunk fora da faixa de"
                + " 27 bits vai para 'outros' nos chunks mas a secao dele ainda conta)");
    }

    private static void argumentos() {
        Object[][] casos = {
            {new String[] {}, 300, false, null},
            {new String[] {"30"}, 30, false, null},
            {new String[] {"30", "forcar"}, 30, true, null},
            {new String[] {"forcar"}, 300, true, null},
            {new String[] {"60", "FORCAR"}, 60, true, null},
            {new String[] {"30", "xyz"}, 0, false, "Argumentos demais"},
            {new String[] {"30", "forca"}, 0, false, "Argumentos demais"},
            {new String[] {"xyz"}, 0, false, "Argumento desconhecido"},
            {new String[] {"forca"}, 0, false, "Argumento desconhecido"},
            {new String[] {"xyz", "forcar"}, 0, false, "Argumento desconhecido"},
            {new String[] {"5"}, 0, false, "fora do limite"},
            {new String[] {"1801"}, 0, false, "fora do limite"},
            {new String[] {"30", "forcar", "x"}, 0, false, "Argumentos demais"},
            {new String[] {"forcar", "30"}, 0, false, "Argumentos demais"},
        };
        for (Object[] c : casos) {
            String[] a = (String[]) c[0];
            Medidor.Pedido p = Medidor.interpretarMedir(a);
            String esperado = (String) c[3];
            boolean ok = esperado == null ? p.erro == null && p.segundos == (Integer) c[1] && p.forcar == (Boolean) c[2]
                    : p.erro != null && p.erro.contains(esperado);
            v.sim(ok, "hoshie medir " + String.join(" ", a) + " -> "
                    + (p.erro != null ? "recusado: " + p.erro : p.segundos + " s" + (p.forcar ? " forcado" : "")));
        }
    }

    /**
     * Equivalente do teste T2 do servidor: 104 "funis" em dois chunks e 300 entidades; mede K ticks e confere que a
     * contagem de atualizacoes bate EXATAMENTE com N x K, que cada chunk tem a sua parte e que os arquivos nao trazem
     * nome de jogador, UUID nem IP.
     */
    private static void contagemExataEChunks() throws Exception {
        File pasta = pastaNova("t2");
        MundoFalso m = new MundoFalso(0);
        jogo.mundos.clear();
        TileEntityFalsa[] tes = new TileEntityFalsa[104];
        int[][] cte = new int[104][];
        for (int i = 0; i < 104; i++) {
            tes[i] = new TileEntityFalsa();
            cte[i] = i < 40 ? new int[] {2, 5} : new int[] {-3, 18}; // secao (0,0) e secao (-1,1)
        }
        EntidadeFalsa[] ents = new EntidadeFalsa[300];
        int[][] cent = new int[300][];
        for (int i = 0; i < 300; i++) {
            ents[i] = new EntidadeFalsa("JogadorSecreto 0f8fad5b-d9cb-469f-a165-70867728950e 203.0.113.7");
            cent[i] = new int[] {40, 40}; // secao (2,2)
        }
        jogo.mundos.add(new Jogo.Mundo(m, 0, tes.length, ents.length, true, false));
        ticksOciosos(150, 20);
        final int k = 601;
        inicioTick();
        List<String> r = Medidor.comandoMedir(30, false);
        v.sim(Medidor.ATIVO && r.size() == 1 && r.get(0).startsWith("Medicao iniciada"),
                "comandoMedir na thread do tick liga na hora: " + r);
        v.sim(r.get(0).contains("tile entities=firewall") && r.get(0).contains("Tick antes de ligar: mediana 20.0 ms"),
                "linha de inicio diz a chamada envolvida e a referencia de 20 ms dos ticks ociosos");
        fimTick(20);
        for (int t = 0; t < k; t++) {
            tickComLaco(m, tes, cte, ents, cent, 20);
        }
        List<String> p = Medidor.comandoParar();
        v.sim(!Medidor.ATIVO && p.get(0).startsWith("Medicao encerrada"), "comandoParar na thread do tick: " + p);
        String[] arq = lerArquivos(pasta);
        Object j = Json.ler(arq[0]);
        v.sim(true, "o .json e JSON valido (leitor estrito)");
        v.igual((double) (104 * k), num(j, "meta", "te_ticks_total"), "te_ticks_total = 104 x " + k);
        v.igual((double) (300 * k), num(j, "meta", "ent_ticks_total"), "ent_ticks_total = 300 x " + k);
        v.igual((double) (k + 1), num(j, "meta", "ticks_servidor"), "ticks do servidor contados: os " + k
                + " ticks com laco + o tick em que a medicao ligou (contado do inicio da medicao ao fim)");
        long c40 = -1;
        long c64 = -1;
        @SuppressWarnings("unchecked")
        List<Object> chunks = (List<Object>) Json.caminho(j, "chunks");
        for (Object o : chunks) {
            if (num(o, "chunk_x") == 2 && num(o, "chunk_z") == 5) {
                c40 = (long) num(o, "te_ticks");
            }
            if (num(o, "chunk_x") == -3 && num(o, "chunk_z") == 18) {
                c64 = (long) num(o, "te_ticks");
            }
        }
        v.sim(c40 == 40L * k && c64 == 64L * k, "chunk (2,5) com 40 x " + k + " = " + 40 * k + " ticks (visto " + c40
                + ") e chunk (-3,18) com 64 x " + k + " = " + 64 * k + " (visto " + c64 + ")");
        v.igual(3.0, num(j, "meta", "secoes_distintas"), "3 secoes distintas (0,0), (-1,1), (2,2)");
        v.igual("ok", Json.caminho(j, "meta", "autoteste", "tile_entities"), "autoteste final: tile entities ok");
        v.igual(Boolean.TRUE, Json.caminho(j, "meta", "dados_validos"), "dados_validos = true");
        v.igual((double) (104 * k), num(j, "meta", "te_ticks_via_firewall"), "ticks atribuidos a chamada do firewall");
        v.sim(arq[1].contains("As 1, 3, 5, 10 e 20 secoes mais pesadas concentram"), "o .txt tem a linha de concentracao");
        v.sim(arq[1].contains("TileEntityFalsa") && arq[1].contains("EntidadeFalsa"), "o .txt cita as classes medidas");
        String tudo = arq[0] + arq[1];
        v.sim(!tudo.contains("JogadorSecreto"), "nenhum nome de jogador nos arquivos");
        v.sim(!Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").matcher(tudo).find(),
                "nenhum UUID nos arquivos");
        v.sim(!Pattern.compile("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b").matcher(tudo).find(),
                "nenhum endereco IP nos arquivos");
        String estado = Medidor.linhaDeEstado();
        v.sim(estado.startsWith("pronto; nenhuma medicao") && estado.contains("arquivos gravados: medicao-")
                && !estado.contains(pasta.getPath()), "linha do status: pronto, ultimo resultado so com o nome dos arquivos"
                + " (sem caminho de pasta): " + estado);
    }

    private static void espera(long ns) {
        long fim = System.nanoTime() + ns;
        while (System.nanoTime() < fim) {
            Thread.yield();
        }
    }

    /** Passageiro dentro da montaria (tempo nao conta duas vezes) e excecao que pula o gancho de fim. */
    private static void aninhamentoEExcecao() throws Exception {
        File pasta = pastaNova("aninhamento");
        MundoFalso m = new MundoFalso(-1);
        jogo.mundos.clear();
        EntidadeFalsa montaria = new EntidadeFalsa("montaria");
        EntidadeFalsa passageiro = new EntidadeFalsa("passageiro");
        TileEntityFalsa ruim = new TileEntityFalsa();
        OutraTileEntityFalsa boa = new OutraTileEntityFalsa();
        inicioTick();
        Medidor.comandoMedir(10, false);
        fimTick(20);
        final int k = 20;
        for (int t = 0; t < k; t++) {
            inicioTick();
            Medidor.inicioLacoMundo(m, m.dim, 2, 2);
            Medidor.inicioEntidade(m, m.dim, montaria, 0, 0, null, false);
            espera(1 * MS);
            Medidor.inicioEntidade(m, m.dim, passageiro, 0, 0, montaria, false); // tickado de dentro da montaria
            espera(2 * MS);
            Medidor.fimEntidade(passageiro);
            Medidor.fimEntidade(montaria);
            // TE que lanca excecao: o gancho de fim nao roda e o laco invalida a TE
            Medidor.inicioTE(m, m.dim, ruim, 1, 1);
            ruim.invalida = true;
            Medidor.inicioTE(m, m.dim, boa, 1, 1);
            Medidor.fimTE();
            ruim.invalida = false;
            fimTick(20);
        }
        Coleta c = Medidor.coletaAtual();
        long descartados = c.quadrosDescartados;
        Medidor.comandoParar();
        String[] arq = lerArquivos(pasta);
        Object j = Json.ler(arq[0]);
        double nsMont = -1;
        double nsPass = -1;
        double tickRuim = -1;
        double tickBoa = -1;
        @SuppressWarnings("unchecked")
        List<Object> classes = (List<Object>) Json.caminho(j, "classes");
        for (Object o : classes) {
            String nome = (String) Json.caminho(o, "classe");
            double ns = num(o, "total_ns");
            if (nome.endsWith("EntidadeFalsa")) {
                v.igual((double) (2 * k), num(o, "ent_ticks"), "montaria e passageiro: 2 ticks de entidade por tick");
                nsMont = ns; // a mesma classe para os dois
            }
            if (nome.endsWith("$TileEntityFalsa")) {
                tickRuim = num(o, "te_ticks");
            }
            if (nome.endsWith("OutraTileEntityFalsa")) {
                tickBoa = num(o, "te_ticks");
            }
        }
        // tempo proprio somado: ~1 ms da montaria + ~2 ms do passageiro = ~3 ms por tick; se o do passageiro contasse
        // duas vezes (dentro da montaria), daria ~5 ms
        double msPorTick = nsMont / k / 1e6;
        v.sim(msPorTick > 2.8 && msPorTick < 4.3, String.format("aninhamento: tempo proprio somado %.2f ms por tick"
                + " (esperado ~3; contado duas vezes daria ~5)", msPorTick));
        v.sim(tickRuim <= 0 && tickBoa == k, "TE que lancou: quadro descartado, nenhum tick contado (" + tickRuim
                + "); a TE seguinte contou " + tickBoa + " ticks");
        v.sim(descartados >= k, "quadros descartados contados: " + descartados);
        v.sim(Medidor.profundidade() == 0, "pilha vazia depois da parada");
    }

    private static void pilhaCheia() throws Exception {
        File pasta = pastaNova("pilha");
        MundoFalso m = new MundoFalso(0);
        inicioTick();
        Medidor.comandoMedir(10, false);
        Medidor.inicioLacoMundo(m, 0, 0, 40);
        EntidadeFalsa[] es = new EntidadeFalsa[40];
        for (int i = 0; i < 40; i++) {
            es[i] = new EntidadeFalsa("e" + i);
            Medidor.inicioEntidade(m, 0, es[i], i, 0, i > 0 ? es[i - 1] : null, false);
        }
        for (int i = 39; i >= 0; i--) {
            Medidor.fimEntidade(es[i]);
        }
        Coleta c = Medidor.coletaAtual();
        v.sim(c.quadrosPulados == 8 && c.entTicksTotal == 32 && Medidor.profundidade() == 0,
                "aninhamento de 40 (limite 32): 32 medidos, 8 pulados, pilha volta a zero (pulados=" + c.quadrosPulados
                        + ", medidos=" + c.entTicksTotal + ", prof=" + Medidor.profundidade() + ")");
        fimTick(20);
        Medidor.comandoParar();
        lerArquivos(pasta);
    }

    private static void autotesteSemLacoENaoLigado() throws Exception {
        // (a) nenhum mundo rodou o laco (servidor ocioso, sem jogador)
        File pasta = pastaNova("ocioso");
        MundoFalso m = new MundoFalso(0);
        jogo.mundos.clear();
        jogo.mundos.add(new Jogo.Mundo(m, 0, 500, 20, false, false));
        inicioTick();
        Medidor.comandoMedir(10, false);
        fimTick(20);
        ticksOciosos(150, 20);
        Medidor.comandoParar();
        String[] arq = lerArquivos(pasta);
        Object j = Json.ler(arq[0]);
        v.igual("sem_dados_nenhum_mundo_tickou", Json.caminho(j, "meta", "autoteste", "tile_entities"),
                "ocioso: autoteste diz 'sem dados' em vez de zero como dado");
        v.igual(Boolean.FALSE, Json.caminho(j, "meta", "dados_validos"), "ocioso: dados_validos = false");
        v.sim(arq[1].startsWith("HOSHIESTAR-MEDIDOR") && arq[1].contains("ESTA MEDICAO NAO TEM DADOS UTEIS"),
                "ocioso: o .txt avisa no topo");

        // (b) o laco rodou com tile entities na lista e nenhum tick medido: gancho NAO_LIGADO
        pasta = pastaNova("naoligado");
        inicioTick();
        Medidor.comandoMedir(10, false);
        fimTick(20);
        for (int t = 0; t < 120; t++) {
            inicioTick();
            Medidor.inicioLacoMundo(m, 0, 500, 0);
            fimTick(20);
        }
        Medidor.comandoParar();
        arq = lerArquivos(pasta);
        j = Json.ler(arq[0]);
        v.igual("NAO_LIGADO", Json.caminho(j, "meta", "autoteste", "tile_entities"), "laco com TEs e nada medido: NAO_LIGADO");
        v.igual("NAO_LIGADO", Json.caminho(j, "meta", "autoteste_no_tick_100", "tile_entities"),
                "o autoteste do tick 100 tambem pegou");
        v.sim(arq[1].contains("PARTE DA MEDICAO NAO ESTAVA LIGADA"), "o .txt avisa no topo");

        // (c) dimensao com jogador cujo laco nunca passou pelo gancho
        pasta = pastaNova("dimsem");
        MundoFalso outro = new MundoFalso(7);
        jogo.mundos.add(new Jogo.Mundo(outro, 7, 10, 3, true, false));
        TileEntityFalsa[] tes = {new TileEntityFalsa()};
        inicioTick();
        Medidor.comandoMedir(10, false);
        fimTick(20);
        for (int t = 0; t < 50; t++) {
            tickComLaco(m, tes, new int[][] {{0, 0}}, new EntidadeFalsa[0], new int[0][], 20);
        }
        Medidor.comandoParar();
        arq = lerArquivos(pasta);
        j = Json.ler(arq[0]);
        Object dims = Json.caminho(j, "meta", "autoteste", "dimensoes_com_problema");
        v.sim(dims instanceof List && ((List<?>) dims).size() == 1 && String.valueOf(((List<?>) dims).get(0)).startsWith("dimensao 7"),
                "dimensao 7 com jogador e sem laco medido aparece como problema: " + dims);
        jogo.mundos.clear();
    }

    /** T3 isolado: medir duas vezes, parar sem medicao, parar no meio do tick, sem jogo. */
    private static void robustez() throws Exception {
        File pasta = pastaNova("robustez");
        inicioTick();
        List<String> r1 = Medidor.comandoMedir(10, false);
        List<String> r2 = Medidor.comandoMedir(20, false);
        v.sim(Medidor.ATIVO && r2.size() == 1 && r2.get(0).startsWith("Ja existe uma medicao"),
                "segunda medicao recusada enquanto a primeira corre: " + r2);
        // parar no MEIO de um tick (comando do console roda dentro do tick)
        MundoFalso m = new MundoFalso(0);
        Medidor.inicioLacoMundo(m, 0, 1, 0);
        Medidor.inicioTE(m, 0, new TileEntityFalsa(), 0, 0);
        Medidor.fimTE();
        List<String> p = Medidor.comandoParar();
        v.sim(!Medidor.ATIVO && p.get(0).startsWith("Medicao encerrada"), "parada no meio do tick: " + p);
        fimTick(20); // o resto do tick nao conta
        String[] arq = lerArquivos(pasta);
        Object j = Json.ler(arq[0]);
        v.igual(1.0, num(j, "meta", "ticks_servidor"), "tick parado no meio conta como 1 tick (do inicio da medicao a parada)");
        v.igual(1.0, num(j, "meta", "te_ticks_total"), "o tick medido antes da parada esta nos totais");
        List<String> p2 = Medidor.comandoParar();
        v.sim(p2.get(0).startsWith("Nenhuma medicao"), "parar sem medicao: " + p2);
        v.sim(r1.size() == 1, "primeira medicao respondeu uma linha");
        // jogo ainda nao instalado (servidor nao terminou de ligar)
        Medidor.definirJogo(null, "desconhecida", "teste");
        List<String> r3 = Medidor.comandoMedir(10, false);
        v.sim(!Medidor.ATIVO && r3.get(0).contains("ainda nao terminou de ligar"), "sem jogo: recusa: " + r3);
        Medidor.definirJogo(jogo, "firewall", "teste");
    }

    /** Pedido vindo de outra thread (RCON): vira pedido, a thread do tick liga no proximo tick e para no fim. */
    private static void pedidosDeOutraThread() throws Exception {
        File pasta = pastaNova("rcon");
        inicioTick();
        fimTick(20); // a thread do teste e a "thread do tick"
        final List<String> resp = new ArrayList<String>();
        Thread rcon = new Thread(new Runnable() {
            @Override
            public void run() {
                resp.addAll(Medidor.comandoMedir(10, false));
            }
        });
        rcon.start();
        rcon.join();
        v.sim(!Medidor.ATIVO && resp.size() == 1 && resp.get(0).startsWith("Pedido registrado"),
                "comando de outra thread vira pedido: " + resp);
        v.sim(Medidor.linhaDeEstado().startsWith("medicao pedida"), "status mostra o pedido");
        inicioTick();
        v.sim(Medidor.ATIVO, "a thread do tick ligou a medicao no inicio do tick seguinte");
        fimTick(20);
        // pedido de cancelamento antes de comecar (outra medicao) nao se aplica; parada vinda de outra thread:
        resp.clear();
        Thread rcon2 = new Thread(new Runnable() {
            @Override
            public void run() {
                resp.addAll(Medidor.comandoParar());
            }
        });
        rcon2.start();
        rcon2.join();
        v.sim(Medidor.ATIVO && resp.get(0).startsWith("Pedido registrado: a medicao para"), "parar de outra thread: " + resp);
        inicioTick();
        fimTick(20);
        v.sim(!Medidor.ATIVO, "a medicao parou no fim do tick corrente");
        lerArquivos(pasta);
        // pedido cancelado antes de comecar
        resp.clear();
        Thread rcon3 = new Thread(new Runnable() {
            @Override
            public void run() {
                resp.addAll(Medidor.comandoMedir(10, false));
                resp.addAll(Medidor.comandoParar());
            }
        });
        rcon3.start();
        rcon3.join();
        inicioTick();
        fimTick(20);
        v.sim(!Medidor.ATIVO && resp.size() == 2 && resp.get(1).startsWith("Pedido de medicao cancelado"),
                "pedido cancelado antes de comecar nao liga nada: " + resp);
    }

    /** Regra do tempo de tick: referencia de 10 ms; medindo, ticks de 40 ms; para na 2a janela seguida (tick 200). */
    private static void valvulaTempoDeTick() throws Exception {
        File pasta = pastaNova("valvula");
        ticksOciosos(150, 10);
        inicioTick();
        Medidor.comandoMedir(60, false);
        fimTick(10);
        int t = 0;
        while (Medidor.ATIVO && t < 400) {
            inicioTick();
            fimTick(40);
            t++;
        }
        v.sim(!Medidor.ATIVO && t >= 198 && t <= 200, "valvula parou a medicao na 2a janela de 100 ticks lentos (tick " + t
                + ")");
        String[] arq = lerArquivos(pasta);
        Object j = Json.ler(arq[0]);
        v.igual(Boolean.TRUE, Json.caminho(j, "meta", "valvula", "disparou"), "valvula.disparou = true no .json");
        v.sim(String.valueOf(Json.caminho(j, "meta", "valvula", "motivo")).contains("mediana do tick 40.0 ms contra 10.0 ms"),
                "motivo da valvula: " + Json.caminho(j, "meta", "valvula", "motivo"));
        v.sim(arq[1].contains("PARADA ANTES DO TEMPO PELA VALVULA"), "o .txt avisa no topo");

        // um pico de uma janela so nao dispara
        pasta = pastaNova("valvula-pico");
        ticksOciosos(150, 10);
        inicioTick();
        Medidor.comandoMedir(10, false);
        fimTick(10);
        for (int i = 0; i < 300 && Medidor.ATIVO; i++) {
            inicioTick();
            fimTick(i >= 100 && i < 200 ? 40 : 10);
        }
        v.sim(Medidor.ATIVO, "um pico de UMA janela lenta nao dispara (regra pede 2 seguidas)");
        Medidor.comandoParar();
        lerArquivos(pasta);
    }

    /** Regra do custo estimado (direto em avaliarJanela, com a coleta montada a mao). */
    private static void valvulaCusto() {
        v.igual(5000000L, Medidor.limiteCusto(0L), "limite de custo sem referencia: 5 ms");
        v.igual(5000000L, Medidor.limiteCusto(20000000L), "tick de 20 ms: 10% = 2 ms, piso 5 ms");
        v.igual(8400000L, Medidor.limiteCusto(84000000L), "tick de 84 ms (producao): 8,4 ms");
        v.igual(10000000L, Medidor.limiteCusto(500000000L), "tick de 500 ms: teto 10 ms");
        // margem padrao (medidor.margem_custo = 4, revisao I2: o custo real deu 3 a 5x a estimativa)
        Coleta c = new Coleta(30);
        v.sim(c.margemCusto == 4.0, "margem padrao da regra de custo = 4: " + c.margemCusto);
        c.calibQuadroNs = 100.0;
        c.limiteCustoNs = 5000000L;
        c.teTicksTotal = 1200000L; // 12.000 quadros por tick x 100 ns = 1,2 ms; x4 = 4,8 ms < 5 ms
        v.sim(Medidor.avaliarJanela(c) == null, "custo 1,2 ms x4 = 4,8 ms < 5 ms: nao dispara");
        c.teTicksTotal += 1300000L; // 13.000 por tick = 1,3 ms; x4 = 5,2 ms > 5 ms
        String m = Medidor.avaliarJanela(c);
        v.sim(m != null && m.startsWith("custo estimado do medidor 1.3 ms") && m.contains("margem de 4.0x"),
                "custo 1,3 ms x4 = 5,2 ms > 5 ms: dispara: " + m);
        // margem escolhida pelo admin (2, a da 1.0.4)
        c = new Coleta(30);
        c.margemCusto = 2.0;
        c.calibQuadroNs = 100.0;
        c.limiteCustoNs = 5000000L;
        c.teTicksTotal = 2400000L; // 24.000 quadros por tick x 100 ns = 2,4 ms; x2 = 4,8 ms < 5 ms
        v.sim(Medidor.avaliarJanela(c) == null, "margem 2: custo 2,4 ms x2 = 4,8 ms < 5 ms: nao dispara");
        c.teTicksTotal += 2600000L; // 26.000 por tick = 2,6 ms; x2 = 5,2 ms > 5 ms
        m = Medidor.avaliarJanela(c);
        v.sim(m != null && m.startsWith("custo estimado do medidor 2.6 ms"), "margem 2: custo 2,6 ms x2 = 5,2 ms: dispara: " + m);
        // valor do config conferido no registro
        v.sim(Medidor.definirMargemCusto(25.0) == 4.0 && Medidor.definirMargemCusto(Double.NaN) == 4.0
                && Medidor.definirMargemCusto(0.5) == 4.0, "margem fora de 1..20 (ou NaN): usa o padrao 4");
        v.sim(Medidor.definirMargemCusto(2.5) == 2.5 && Medidor.margemCusto() == 2.5, "margem 2,5 do admin aceita");
        // a margem do config vale para a medicao que comeca (fica na Coleta, sai no .json)
        File pasta = pastaNova("margem");
        ticksOciosos(30, 20);
        inicioTick();
        Medidor.comandoMedir(10, false);
        Coleta atual = Medidor.coletaAtual();
        v.sim(atual != null && atual.margemCusto == 2.5, "medicao iniciada com a margem do config (2,5)");
        fimTick(20);
        Medidor.comandoParar();
        try {
            Object j = Json.ler(lerArquivos(pasta)[0]);
            v.sim(num(j, "meta", "valvula", "margem_custo_estimado") == 2.5, ".json: valvula.margem_custo_estimado = 2,5");
        } catch (Exception e) {
            v.erro("lendo o .json da medicao com margem 2,5", e);
        }
        Medidor.definirMargemCusto(Medidor.VALVULA_CUSTO_FATOR_PADRAO);
    }

    private static void desabilitado() {
        Medidor.definirHabilitado(false);
        List<String> r = Medidor.comandoMedir(30, false);
        v.sim(!Medidor.ATIVO && r.get(0).contains("medidor.habilitado: false"), "medidor.habilitado = false: comando recusa: " + r);
        Medidor.definirHabilitado(true);
    }

    private static Coleta coletaPequena() {
        Coleta c = new Coleta(10);
        int d = c.indiceDim(new Object(), 0);
        c.adicionar(Coleta.TE, d, 0, 0, String.class, 1000, false);
        c.inicioMs = System.currentTimeMillis();
        c.fimMs = c.inicioMs + 10000;
        c.inicioNano = 0;
        c.fimNano = 10000000000L;
        c.ticksServidor = 200;
        return c;
    }

    /** As tres correcoes da pasta reserva (8b) e as recusas da 1.0.4 que continuam valendo. */
    private static void pastaReserva() throws Exception {
        File tmp = new File(base, "tmp-reserva");
        apagar(tmp);
        tmp.mkdirs();
        // 8b-3 isolado: a pasta ja NASCE rwx------ (antes de qualquer soDono). Comparada com uma pasta criada do jeito
        // comum, que segue o umask do processo (o script roda com umask 022): se as duas saissem iguais, o teste nao
        // distinguiria a correcao do comportamento da 1.0.4 (revisao M1, mutacao A).
        java.nio.file.Path fechada = new File(tmp, "nasce-fechada").toPath();
        java.nio.file.Path comum = new File(tmp, "nasce-comum").toPath();
        Relatorio.criarFechada(fechada);
        Files.createDirectory(comum);
        String pf = PosixFilePermissions.toString(Files.getPosixFilePermissions(fechada, LinkOption.NOFOLLOW_LINKS));
        String pc = PosixFilePermissions.toString(Files.getPosixFilePermissions(comum, LinkOption.NOFOLLOW_LINKS));
        v.igual("rwx------", pf, "criarFechada: a pasta nasce rwx------ (sem passar pelo soDono)");
        v.sim(!pc.equals("rwx------"), "controle: pasta comum nasce " + pc + " (umask aberto; se fosse rwx------ o teste"
                + " acima nao provaria nada)");
        boolean jaExiste = false;
        try {
            Relatorio.criarFechada(fechada);
        } catch (java.nio.file.FileAlreadyExistsException e) {
            jaExiste = true;
        }
        v.sim(jaExiste, "criarFechada nao reaproveita nome que ja existe (FileAlreadyExistsException)");
        String tmpAntes = System.getProperty("java.io.tmpdir");
        System.setProperty("java.io.tmpdir", tmp.getPath());
        try {
            File reserva = Relatorio.pastaReserva();
            v.sim(reserva.getParentFile().equals(tmp.getAbsoluteFile()) && reserva.getName().startsWith("hoshiestar-"),
                    "pasta reserva = <tmpdir>/hoshiestar-<usuario>: " + reserva);
            // pasta principal impossivel: um ARQUIVO no lugar da pasta (o teste roda como root, entao permissao nao
            // serve para provocar a falha)
            File principal = new File(base, "principal-e-arquivo");
            apagar(principal);
            Files.write(principal.toPath(), new byte[] {1});
            Relatorio rel = new Relatorio(coletaPequena(), principal);
            File[] arqs = rel.gravar();
            v.sim(arqs[0].getParentFile().equals(reserva), "principal falhou: arquivos foram para a reserva");
            String perm = PosixFilePermissions.toString(Files.getPosixFilePermissions(reserva.toPath(), LinkOption.NOFOLLOW_LINKS));
            v.igual("rwx------", perm, "reserva criada ja so para o dono");
            String permArq = PosixFilePermissions.toString(Files.getPosixFilePermissions(arqs[0].toPath()));
            v.igual("rw-------", permArq, "arquivos da reserva so para o dono");

            // pasta reserva ja existe, do dono certo, e o /tmp NAO aceita escrita: na 1.0.4 era recusada; agora vale
            // (simulado com a sonda de dono dentro da propria pasta: o pai nao e mais usado)
            File[] arqs2 = new Relatorio(coletaPequena(), principal).gravar();
            v.sim(arqs2[0].getParentFile().equals(reserva) && !arqs2[0].equals(arqs[0]),
                    "reserva existente do dono certo aceita de novo (nome novo, sem sobrescrever)");

            // reserva e link simbolico: recusada, e a mensagem diz o motivo da principal e da reserva
            apagar(reserva);
            File alvo = new File(tmp, "alvo");
            alvo.mkdirs();
            Files.createSymbolicLink(reserva.toPath(), alvo.toPath());
            String msg = null;
            try {
                new Relatorio(coletaPequena(), principal).gravar();
            } catch (IOException e) {
                msg = e.getMessage();
            }
            v.sim(msg != null && msg.contains("link simbolico") && msg.contains("ja existe ["),
                    "reserva link simbolico: recusada, com o motivo da principal e da reserva: " + msg);
            // revisao M4: cada caminho aparece uma vez so (antes: 'a pasta X falhou (nao consegui criar a pasta X: ja
            // existe (X) [...])')
            v.sim(msg != null && ocorrencias(msg, principal.getPath()) == 1 && ocorrencias(msg, reserva.getPath()) == 1,
                    "mensagem com cada caminho uma vez so: " + msg);
            String[] dentro = alvo.list();
            v.sim(dentro != null && dentro.length == 0, "nada foi gravado no alvo do link");

            // reserva de outro dono: recusada
            Files.delete(reserva.toPath());
            reserva.mkdirs();
            UserPrincipal ninguem = null;
            try {
                ninguem = reserva.toPath().getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName("nobody");
                Files.setOwner(reserva.toPath(), ninguem);
            } catch (Throwable t) {
                ninguem = null;
            }
            if (ninguem != null && Files.getOwner(reserva.toPath()).equals(ninguem)) {
                msg = null;
                try {
                    new Relatorio(coletaPequena(), principal).gravar();
                } catch (IOException e) {
                    msg = e.getMessage();
                }
                v.sim(msg != null && msg.contains("pertence a outro usuario"), "reserva de outro usuario: recusada: " + msg);
            } else {
                System.out.println("  AVISO teste de reserva de outro dono PULADO (sem permissao para trocar o dono)");
            }
            // reserva e um arquivo
            apagar(reserva);
            Files.write(reserva.toPath(), new byte[] {1});
            msg = null;
            try {
                new Relatorio(coletaPequena(), principal).gravar();
            } catch (IOException e) {
                msg = e.getMessage();
            }
            v.sim(msg != null && msg.contains("nao e uma pasta"), "reserva e um arquivo: recusada: " + msg);
            apagar(reserva);
            apagar(principal);
            // pasta principal normal
            File ok = new File(base, "principal-ok/sub");
            apagar(ok.getParentFile());
            File[] a3 = new Relatorio(coletaPequena(), ok).gravar();
            v.sim(a3[0].getParentFile().equals(ok.getAbsoluteFile()) && a3[1].getName().endsWith(".txt"),
                    "pasta principal criada com as pastas-mae e usada");
        } finally {
            System.setProperty("java.io.tmpdir", tmpAntes);
        }
    }

    private static int ocorrencias(String s, String sub) {
        int n = 0;
        for (int i = s.indexOf(sub); i >= 0; i = s.indexOf(sub, i + sub.length())) {
            n++;
        }
        return n;
    }

    private static void motivos() {
        v.igual("sem permissao [AccessDeniedException]",
                Relatorio.motivo(new java.nio.file.AccessDeniedException("/srv/x"), new File("/srv/x")),
                "motivo sobre a propria pasta: sem repetir o caminho");
        v.igual("sem permissao (/srv) [AccessDeniedException]",
                Relatorio.motivo(new java.nio.file.AccessDeniedException("/srv"), new File("/srv/x")),
                "motivo sobre outra pasta (a mae): com o caminho dela");
        v.igual("sem permissao (/srv/x) [AccessDeniedException]", Relatorio.motivo(new java.nio.file.AccessDeniedException("/srv/x")),
                "motivo: AccessDeniedException so com o caminho vira 'sem permissao (caminho)'");
        v.igual("disco cheio (/srv/x) [FileSystemException]",
                Relatorio.motivo(new java.nio.file.FileSystemException("/srv/x", null, "disco cheio")),
                "motivo: FileSystemException usa o motivo do sistema");
        v.igual("IOException", Relatorio.motivo(new IOException()), "motivo: excecao sem mensagem diz ao menos o tipo");
        v.igual("motivo desconhecido", Relatorio.motivo(null), "motivo: null");
    }

    /** Erro no nosso codigo desliga a medicao e nao lanca para o jogo. */
    private static void falhaInternaDesliga() {
        relogio = System.nanoTime();
        inicioTick();
        Medidor.comandoMedir(10, false);
        JogoFalso quebrado = new JogoFalso() {
            @Override
            public boolean morto(Object o, int tipo) {
                throw new IllegalStateException("erro de proposito");
            }
        };
        Medidor.definirJogo(quebrado, "firewall", "teste");
        MundoFalso m = new MundoFalso(0);
        boolean lancou = false;
        try {
            Medidor.inicioEntidade(m, 0, new EntidadeFalsa("a"), 0, 0, null, false);
            Medidor.inicioEntidade(m, 0, new EntidadeFalsa("b"), 0, 0, null, false); // prof > 0: chama morto()
        } catch (Throwable t) {
            lancou = true;
        }
        v.sim(!lancou && !Medidor.ATIVO && Medidor.profundidade() == 0,
                "excecao nossa: nada lancado para o jogo, medicao desligada, pilha limpa");
        v.sim(Medidor.linhaDeEstado().contains("erro interno registrado"), "status mostra o erro interno");
        fimTick(20);
        Medidor.definirJogo(jogo, "firewall", "teste");

        // revisao M2: falha() com um Throwable cujo toString e getMessage lancam nao deixa nada escapar (o gancho de TE
        // fica dentro do try do laco de tiles: um erro que escapasse viraria erro da tile entity da vez)
        inicioTick();
        Medidor.comandoMedir(10, false);
        v.sim(Medidor.ATIVO, "medicao ligada de novo para o caso do Throwable estranho");
        Throwable estranho = new RuntimeException() {
            @Override
            public String getMessage() {
                throw new IllegalStateException("getMessage quebrado");
            }

            @Override
            public String toString() {
                throw new IllegalStateException("toString quebrado");
            }
        };
        lancou = false;
        try {
            Medidor.falha(estranho);
        } catch (Throwable t) {
            lancou = true;
        }
        v.sim(!lancou && !Medidor.ATIVO && Medidor.profundidade() == 0,
                "falha() com toString/getMessage que lancam: nada escapa, medicao desligada");
        v.sim(Medidor.linhaDeEstado().contains("sem texto: o toString lancou"), "status diz que o erro nao tinha texto");
        fimTick(20);
    }

    private static void apagar(File f) {
        if (f == null) {
            return;
        }
        if (Files.isSymbolicLink(f.toPath())) {
            f.delete();
            return;
        }
        File[] fs = f.listFiles();
        if (fs != null) {
            for (File x : fs) {
                apagar(x);
            }
        }
        f.delete();
    }

    @SuppressWarnings("unused")
    private static Map<String, Object> mapa(Object o) {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) o;
        return m;
    }
}
