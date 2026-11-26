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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

/**
 * Testes isolados do CORTE do tick (Etapa 2.5, Degrau A), num mundo falso que roda os quatro lacos na mesma ordem e com o
 * mesmo formato dos patches (tick aleatorio por chunk, puxada e tick dos agendados, entidades, tiles), chamando o MESMO
 * {@link Nucleo} e a MESMA {@link PuxadaAgendada} do servidor. Relogio falso: numeros de simulacao, nao medicao.
 *
 * <ul>
 * <li>regiao cortada pula tudo junto (tile, entidade, agendado, aleatorio no mesmo tick); as outras nunca perdem tick;
 * <li>isentos nunca pulam (jogador, montaria do jogador, quem ele carrega; entidade morta e removida no mesmo tick);
 * <li>entidade que muda de regiao no meio do tick: um tick so naquele tick, depois segue a regiao nova;
 * <li>puxada por iterador contra um modelo independente em 1 milhao de operacoes aleatorias;
 * <li>diferencial dos agendados contra o jogo sem corte depois de "alcancar" (nada perdido nem duplicado, mesma ordem por
 * regiao, mesmo tick fora da regiao cortada, nunca mais que o teto por tick);
 * <li>chunk descarregando com a regiao em corte: as entradas guardadas sao gravadas e rodam uma vez so depois da recarga;
 * <li>desligado / modo medir / sem corte = o mesmo registro de execucao do jogo sem ganchos;
 * <li>erro num gancho: so aquela regiao volta a ticar normal, log com limitador; erros demais desligam tudo;
 * <li>ponte de chunks entre duas regioes (rede que passa a ligar duas bases): suspensao e depois juncao, cortadas juntas.
 * </ul>
 */
public final class TesteCorte {

    // ---------------------------------------------------------------- objetos do mundo falso

    /** Entrada de tick agendado: compara como NextTickListEntry (tempo, prioridade, numero); igual por posicao e bloco. */
    static final class Ag implements Comparable<Ag> {
        static long seq;
        final int x, y, z;
        final String bloco;
        final long t;
        final int prio;
        final long id;
        final long marca; // identidade logica (sobrevive a gravacao e recarga)

        Ag(int x, int y, int z, String bloco, long t, int prio, long marca) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.bloco = bloco;
            this.t = t;
            this.prio = prio;
            this.id = seq++;
            this.marca = marca;
        }

        @Override
        public int compareTo(Ag o) {
            if (t != o.t) {
                return t < o.t ? -1 : 1;
            }
            if (prio != o.prio) {
                return prio - o.prio;
            }
            return id < o.id ? -1 : (id > o.id ? 1 : 0);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Ag)) {
                return false;
            }
            Ag a = (Ag) o;
            return x == a.x && y == a.y && z == a.z && bloco.equals(a.bloco);
        }

        @Override
        public int hashCode() {
            return (x * 1024 * 1024 + z * 1024 + y) * 256;
        }
    }

    static final PuxadaAgendada.Acesso<Ag> ACESSO = new PuxadaAgendada.Acesso<Ag>() {
        @Override
        public long quando(Ag e) {
            return e.t;
        }

        @Override
        public int x(Ag e) {
            return e.x;
        }

        @Override
        public int z(Ag e) {
            return e.z;
        }
    };

    static final class Ent {
        final String nome;
        double x, z;
        boolean jogador, morto;
        Ent montadoPor, montadoEm;
        int ticks;
        /** Ao ticar, vai para este x (entidade que muda de regiao no meio do tick). */
        Double moverPara;
        int ultimoTick = -1;
        int ticksNoMesmoTick;

        Ent(String nome, double x, double z) {
            this.nome = nome;
            this.x = x;
            this.z = z;
        }
    }

    static final Isencao.Montaria<Ent> MONTARIA = new Isencao.Montaria<Ent>() {
        @Override
        public boolean ehJogador(Ent e) {
            return e.jogador;
        }

        @Override
        public Ent montadoPor(Ent e) {
            return e.montadoPor;
        }

        @Override
        public Ent montadoEm(Ent e) {
            return e.montadoEm;
        }
    };

    static final class Tile {
        final String nome;
        final int x, z;
        double custoMs;
        boolean invalida;
        int ticks;

        Tile(String nome, int x, int z, double custoMs) {
            this.nome = nome;
            this.x = x;
            this.z = z;
            this.custoMs = custoMs;
        }
    }

    static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    static long chaveChunk(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xffffffffL);
    }

    /** O mundo falso: os lacos do WorldServer/World na ordem e no formato dos patches. */
    static final class Mundo implements Jogo {
        final TesteOrcamento.TempoFalso t = new TesteOrcamento.TempoFalso();
        final Nucleo n;
        long tempoMundo;
        int tick;
        /**
         * Indice por fora da fila, como o do Hodgepodge (speedupPendingTickLookup): acompanha so as chamadas
         * TreeSet.add/TreeSet.remove (o @Redirect dele), nao o iterator().remove(). Por identidade: entrada velha conta.
         */
        final Set<Ag> indice = Collections.newSetFromMap(new java.util.IdentityHashMap<Ag, Boolean>());
        final TreeSet<Ag> fila = new TreeSet<Ag>() {
            @Override
            public boolean add(Ag e) {
                boolean r = super.add(e);
                if (r) {
                    indice.add(e);
                }
                return r;
            }

            @Override
            public boolean remove(Object o) {
                boolean r = super.remove(o);
                if (r) {
                    indice.remove(o);
                }
                return r;
            }
        };
        final Set<Ag> conj = Collections.synchronizedSet(new LinkedHashSet<Ag>());
        final List<Ag> doTick = new ArrayList<Ag>();
        final List<Tile> tiles = new ArrayList<Tile>();
        final List<Ent> ents = new ArrayList<Ent>();
        final List<int[]> chunks = new ArrayList<int[]>();
        final Set<Long> carregados = new HashSet<Long>();
        final List<String> logs = new ArrayList<String>();
        final List<String> exec = new ArrayList<String>();
        /** Entradas agendadas executadas: marca -> quantas vezes. */
        final Map<Long, Integer> execAg = new HashMap<Long, Integer>();
        /** Por tick: o que rodou (rotulo da regiao + tipo). */
        Set<String> rodouNoTick = new HashSet<String>();
        /** Agendados que se reagendam a +1 tick quando rodam (relogio de redstone): posicao -> rotulo. */
        final Set<String> relogios = new HashSet<String>();
        int puxadasNoTick;
        int maxPuxadas;
        int selecaoSobrou;
        int[][] spawns = new int[0][];
        double baseMs = 2.0;
        double atraso;
        boolean semDormir;

        Mundo(Parametros p, boolean comNucleo, boolean cortar) {
            n = comNucleo ? new Nucleo(p, this, t, cortar, false) : null;
        }

        static String rotulo(int x) {
            int s = x >> 8;
            return s < 10 ? "A" : (s < 30 ? "B" : "C");
        }

        void chunk(int cx, int cz) {
            chunks.add(new int[] {cx, cz});
            carregados.add(chaveChunk(cx, cz));
        }

        boolean carregado(int x, int z) {
            return carregados.contains(chaveChunk(x >> 4, z >> 4));
        }

        /** scheduleBlockUpdate: so com o chunk carregado e se a mesma posicao/bloco nao esta na fila (HashSet). */
        boolean agendar(int x, int y, int z, String bloco, long atraso, int prio, long marca) {
            if (!carregado(x, z)) {
                return false;
            }
            Ag e = new Ag(x, y, z, bloco, tempoMundo + atraso, prio, marca);
            if (conj.contains(e)) {
                return false;
            }
            conj.add(e);
            fila.add(e);
            return true;
        }

        void tick() {
            long ini = t.ns;
            tick++;
            tempoMundo++;
            rodouNoTick = new HashSet<String>();
            if (n != null) {
                n.inicioTick();
            }
            // ---- WorldServer.func_147456_g: por chunk carregado
            for (int[] c : chunks) {
                if (!carregados.contains(chaveChunk(c[0], c[1]))) {
                    continue;
                }
                if (n != null && !n.chunkAleatorio(0, c[0], c[1])) {
                    continue;
                }
                rodouNoTick.add(rotulo(c[0] << 4) + ":aleatorio");
                exec.add(tick + " aleatorio " + c[0] + "," + c[1]);
                t.ms(0.05);
            }
            if (n != null) {
                n.fimLaco();
            }
            // ---- WorldServer.tickUpdates(false)
            int i = PuxadaAgendada.teto(fila.size());
            int antes = doTick.size();
            if (n == null || !n.selecionarAgendados(0, fila, i, tempoMundo, ACESSO)) {
                for (int j = 0; j < i; ++j) {
                    Ag e = fila.first();
                    if (e.t > tempoMundo) {
                        break;
                    }
                    fila.remove(e);
                    conj.remove(e);
                    doTick.add(e);
                }
            } else {
                Ag e;
                while ((e = (Ag) n.proximaAgendada()) != null) {
                    fila.remove(e);
                    conj.remove(e);
                    doTick.add(e);
                }
                if (n.selecaoPendente() != 0) {
                    selecaoSobrou++;
                }
            }
            puxadasNoTick = doTick.size() - antes;
            maxPuxadas = Math.max(maxPuxadas, puxadasNoTick);
            for (Iterator<Ag> it = doTick.iterator(); it.hasNext();) {
                Ag e = it.next();
                it.remove();
                if (carregado(e.x, e.z)) {
                    if (n != null) {
                        n.agendado(0, e.x, e.z);
                    }
                    rodouNoTick.add(rotulo(e.x) + ":agendado");
                    exec.add(tick + " agendado " + e.marca);
                    Integer k = execAg.get(e.marca);
                    execAg.put(e.marca, k == null ? 1 : k + 1);
                    t.ms(0.05);
                    if (relogios.contains(e.x + "," + e.z)) {
                        agendar(e.x, e.y, e.z, e.bloco, 1, 0, e.marca);
                    }
                } else {
                    agendar(e.x, e.y, e.z, e.bloco, 0, e.prio, e.marca); // como o jogo: recusado se o chunk nao existe
                }
            }
            if (n != null) {
                n.fimLaco();
            }
            doTick.clear();
            // ---- World.updateEntities: entidades
            for (int k = 0; k < ents.size(); k++) {
                Ent e = ents.get(k);
                if (n != null && !n.decidirEntidade(0, floor(e.x), floor(e.z), e.morto, Isencao.isenta(e, MONTARIA))) {
                    continue;
                }
                if (e.montadoEm != null) {
                    if (!e.montadoEm.morto && e.montadoEm.montadoPor == e) {
                        continue; // ticado pela montaria
                    }
                    e.montadoEm.montadoPor = null;
                    e.montadoEm = null;
                }
                if (!e.morto) {
                    tickEnt(e);
                }
                if (e.morto) {
                    ents.remove(k--);
                    exec.add(tick + " removida " + e.nome);
                }
            }
            if (n != null) {
                n.fimLaco();
            }
            // ---- World.updateEntities: tiles (a limpeza de invalida vem antes do gancho)
            for (int k = 0; k < tiles.size(); k++) {
                Tile te = tiles.get(k);
                if (te.invalida) {
                    tiles.remove(k--);
                    continue;
                }
                if (n != null && !n.tile(0, te.x, te.z)) {
                    continue;
                }
                te.ticks++;
                rodouNoTick.add(rotulo(te.x) + ":tile");
                exec.add(tick + " tile " + te.nome);
                t.ms(te.custoMs);
            }
            if (n != null) {
                n.fimLaco();
            }
            t.ms(baseMs);
            double ms = (t.ns - ini) / 1e6;
            if (n != null) {
                n.fimTick();
            }
            if (!semDormir) {
                atraso += ms - 50.0;
                if (atraso < 0) {
                    t.ms(-atraso);
                    atraso = 0;
                }
            }
        }

        void tickEnt(Ent e) {
            if (e.ultimoTick == tick) {
                e.ticksNoMesmoTick++;
            }
            e.ultimoTick = tick;
            e.ticks++;
            rodouNoTick.add(rotulo(floor(e.x)) + ":entidade");
            exec.add(tick + " entidade " + e.nome);
            t.ms(0.05);
            if (e.moverPara != null) {
                e.x = e.moverPara;
                e.moverPara = null;
            }
            if (e.montadoPor != null) {
                Ent r = e.montadoPor;
                if (!r.morto && r.montadoEm == e) {
                    r.x = e.x;
                    r.z = e.z;
                    tickEnt(r);
                }
            }
        }

        void ticks(int k) {
            for (int i = 0; i < k; i++) {
                tick();
            }
        }

        // ---- Jogo
        @Override
        public FotoMundo[] fotografar() {
            FotoMundo f = new FotoMundo(0);
            for (int[] c : chunks) {
                if (carregados.contains(chaveChunk(c[0], c[1]))) {
                    f.chunk(c[0], c[1]);
                }
            }
            for (Tile te : tiles) {
                if (!te.invalida) {
                    f.tile(te.x, te.z);
                }
            }
            return new FotoMundo[] {f};
        }

        @Override
        public int[][] spawns() {
            return spawns;
        }

        @Override
        public int[][] jogadores() {
            return new int[0][];
        }

        @Override
        public String modDaClasse(String c) {
            return null;
        }

        @Override
        public File pastaDeSaida() {
            return null;
        }

        @Override
        public void log(String l) {
            logs.add(l);
        }

        @Override
        public void avisarOperadores(String l) {
        }
    }

    /** Tres bases: A (secao 0, pesada), B (secao 20), C (secao 40); cada uma com tile, entidade, relogio agendado e chunk. */
    static Mundo tresBases(boolean comNucleo, boolean cortar, double custoA) {
        Mundo m = new Mundo(Parametros.padrao(), comNucleo, cortar);
        String[] nomes = {"A", "B", "C"};
        int[] secs = {0, 20, 40};
        for (int r = 0; r < 3; r++) {
            int bx = secs[r] * 256;
            m.chunk(bx >> 4, 1);
            m.chunk((bx >> 4) + 1, 1);
            m.tiles.add(new Tile("t" + nomes[r], bx + 20, 20, r == 0 ? custoA : 2.0));
            m.ents.add(new Ent("e" + nomes[r], bx + 21.5, 21.5));
            m.relogios.add((bx + 22) + "," + 22);
            m.agendar(bx + 22, 64, 22, "relogio", 1, 0, 1000 + r);
        }
        return m;
    }

    public static void main(String[] args) throws Exception {
        Verifica v = new Verifica("TesteCorte");
        try {
            tudoJunto(v);
            diferencialDesligado(v);
            puxadaAleatoria(v, 1000000);
            diferencialAgendados(v, false);
            diferencialAgendados(v, true);
            descarga(v);
            erroNoGancho(v);
            ponte(v);
            isencao(v);
        } catch (Throwable t) {
            v.erro("excecao no teste", t);
        }
        v.fim();
    }

    // ---------------------------------------------------------------- 1. tudo junto, isentos, mudanca de regiao

    static void tudoJunto(Verifica v) {
        Mundo m = tresBases(true, true, 60.0);
        // isentos em A: jogador num cavalo, alguem montado no jogador; e uma entidade que vai morrer
        Ent jog = new Ent("jogador", 30.5, 30.5);
        jog.jogador = true;
        Ent cavalo = new Ent("cavalo", 30.5, 30.5);
        Ent carregado = new Ent("carregado", 30.5, 30.5);
        cavalo.montadoPor = jog;
        jog.montadoEm = cavalo;
        jog.montadoPor = carregado;
        carregado.montadoEm = jog;
        Ent vaiMorrer = new Ent("vaiMorrer", 40.5, 40.5);
        Ent viajante = new Ent("viajante", 20 * 256 + 50.5, 50.5);
        m.ents.add(cavalo);
        m.ents.add(jog);
        m.ents.add(carregado);
        m.ents.add(vaiMorrer);
        m.ents.add(viajante);
        m.ticks(100);
        Retrato.Regiao a = m.n.retrato().emBloco(0, 20, 20);
        v.sim(a != null && a.n == 25 && "cortada".equals(a.estado), "(junto) A (60 ms) cortada em N = 25: " + (a == null ? null : a.n));
        int ticksA = 0, quebras = 0, bcPerdeu = 0, isentoPerdeu = 0, filaDesigual = 0, indiceVelho = 0;
        int cavaloAntes = cavalo.ticks, jogAntes = jog.ticks, carrAntes = carregado.ticks;
        int mortaRemovida = -1;
        int viajanteNoTick = 0, viajanteDepois = 0, rodadasADepois = 0;
        for (int k = 0; k < 1000; k++) {
            if (k == 500) {
                vaiMorrer.morto = true; // morta por fora (ex.: o jogador matou): o laco remove no mesmo tick
            }
            if (k == 600) {
                viajante.moverPara = 30.5; // no tick dele, vai de B para A
            }
            m.tick();
            boolean tA = m.rodouNoTick.contains("A:tile"), eA = m.rodouNoTick.contains("A:entidade"),
                    gA = m.rodouNoTick.contains("A:agendado"), cA = m.rodouNoTick.contains("A:aleatorio");
            if (tA) {
                ticksA++;
            }
            // A:entidade aparece todo tick por causa dos isentos (jogador, cavalo, carregado); o que conta e eA da entidade comum
            boolean eComum = m.exec.contains(m.tick + " entidade eA");
            if (!(tA == eComum && tA == gA && tA == cA)) {
                quebras++;
            }
            for (String r : new String[] {"B", "C"}) {
                if (!(m.rodouNoTick.contains(r + ":tile") && m.rodouNoTick.contains(r + ":agendado")
                        && m.rodouNoTick.contains(r + ":aleatorio") && m.rodouNoTick.contains(r + ":entidade"))) {
                    bcPerdeu++;
                }
            }
            if (m.fila.size() != m.conj.size()) {
                filaDesigual++;
            }
            if (m.indice.size() != m.fila.size() || !m.fila.containsAll(m.indice)) {
                indiceVelho++;
            }
            if (k == 500 && !m.ents.contains(vaiMorrer)) {
                mortaRemovida = m.tick;
            }
            if (k == 600) {
                viajanteNoTick = viajante.ticks;
            }
            if (k > 600 && k <= 900) {
                if (tA) {
                    rodadasADepois++;
                }
            }
            if (k == 900) {
                viajanteDepois = viajante.ticks - viajanteNoTick;
            }
            if (!eA) {
                isentoPerdeu++;
            }
        }
        v.sim(ticksA >= 38 && ticksA <= 42, "(junto) A roda ~1 a cada 25 ticks: " + ticksA + " rodadas em 1000");
        v.igual(0, quebras, "(junto) em todo tick, A roda tudo (tile, entidade, agendado, aleatorio) ou nada");
        v.igual(0, bcPerdeu, "(junto) B e C nunca perdem nada em 1000 ticks");
        v.igual(0, filaDesigual, "(junto) TreeSet e HashSet dos agendados sempre do mesmo tamanho");
        v.igual(0, m.selecaoSobrou, "(junto) a selecao da puxada termina vazia (nenhuma referencia guardada)");
        v.igual(0, indiceVelho, "(junto) um indice que so ve TreeSet.add/remove (como o do Hodgepodge) fica igual a fila em todo tick");
        v.igual(1000, cavalo.ticks - cavaloAntes, "(junto) a montaria do jogador roda todo tick");
        v.igual(1000, jog.ticks - jogAntes, "(junto) o jogador (montado) roda todo tick, pela montaria");
        v.igual(1000, carregado.ticks - carrAntes, "(junto) quem o jogador carrega roda todo tick");
        v.igual(0, isentoPerdeu, "(junto) ha entidade de A rodando em todo tick (os isentos)");
        v.sim(mortaRemovida == m.tick - 499, "(junto) entidade morta em regiao cortada e removida no mesmo tick (" + mortaRemovida + ")");
        v.igual(0, viajante.ticksNoMesmoTick, "(junto) entidade que muda de regiao no meio nunca tica duas vezes no mesmo tick");
        v.igual(rodadasADepois, viajanteDepois, "(junto) depois de ir para A a entidade segue o ritmo de A (" + viajanteDepois + " = "
                + rodadasADepois + " rodadas)");
        // a entrada agendada de A guardada fica nas duas colecoes (o salvamento a ve) entre as rodadas
        boolean guardada = false;
        for (Ag e : m.fila) {
            if (e.marca == 1000 && e.t < m.tempoMundo && m.conj.contains(e)) {
                guardada = true;
            }
        }
        v.sim(guardada || m.rodouNoTick.contains("A:tile"), "(junto) entrada vencida de A fica guardada na TreeSet e no HashSet");
        Integer relA = m.execAg.get(1000L), relB = m.execAg.get(1001L);
        v.sim(relA != null && relA == 100 + ticksA, "(junto) o relogio agendado de A roda 1 vez por rodada, sem rajada: " + relA);
        v.sim(relB != null && relB == 1100, "(junto) o relogio de B roda todo tick: " + relB);
    }

    // ---------------------------------------------------------------- 2. desligado / medir / sem corte = original

    static void diferencialDesligado(Verifica v) {
        Mundo orig = tresBases(false, false, 60.0);
        Mundo medir = tresBases(true, false, 60.0);
        Mundo semCorte = tresBases(true, true, 5.0); // servidor folgado: ninguem entra em corte
        for (Mundo m : new Mundo[] {orig, medir, semCorte}) {
            Ent e = new Ent("andarilho", 25.5, 25.5);
            m.ents.add(e);
            m.ticks(700);
        }
        v.sim(orig.exec.size() > 10000, "(desligado) o mundo sem ganchos rodou: " + orig.exec.size() + " eventos");
        v.sim(orig.exec.equals(medir.exec), "(desligado) modo medir = mesmo registro de execucao do jogo sem ganchos");
        // semCorte tem a tile A de outro custo, mas o registro de execucao nao depende do custo
        v.sim(orig.exec.equals(semCorte.exec), "(desligado) modo cortar sem estouro = mesmo registro do jogo sem ganchos");
        v.sim(!medir.n.cortaNesteTick() && !semCorte.n.cortaNesteTick(), "(desligado) sem corte, a puxada usa o laco original");
    }

    // ---------------------------------------------------------------- 3. puxada contra modelo independente

    static void puxadaAleatoria(Verifica v, int operacoes) {
        Random r = new Random(20261125L);
        TreeSet<Ag> fila = new TreeSet<Ag>();
        Set<Ag> conj = new HashSet<Ag>();
        long agora = 0;
        int erros = 0, puxadas = 0, guardadas = 0, chamadas = 0, acimaDoTeto = 0, difOriginal = 0;
        final boolean[] cortada = new boolean[4];
        PuxadaAgendada.Decisor dec = new PuxadaAgendada.Decisor() {
            @Override
            public boolean roda(int x, int z) {
                return !cortada[(x >> 8) & 3];
            }
        };
        for (int op = 0; op < operacoes; op++) {
            if (r.nextInt(10) < 6) {
                Ag e = new Ag(r.nextInt(4) * 256 + r.nextInt(16), 64, r.nextInt(16), "b" + r.nextInt(3), agora + r.nextInt(30),
                        r.nextInt(3) - 1, op);
                if (!conj.contains(e)) {
                    conj.add(e);
                    fila.add(e);
                }
                continue;
            }
            agora++;
            for (int k = 0; k < 4; k++) {
                cortada[k] = r.nextBoolean();
            }
            boolean nenhumCorte = r.nextInt(8) == 0;
            if (nenhumCorte) {
                java.util.Arrays.fill(cortada, false);
            }
            int teto = r.nextBoolean() ? PuxadaAgendada.teto(fila.size()) : 1 + r.nextInt(40);
            // modelo: em ordem, ate o primeiro nao vencido; so as de regiao que roda; ate o teto
            List<Ag> ordem = new ArrayList<Ag>(fila);
            Collections.sort(ordem);
            List<Ag> esperado = new ArrayList<Ag>();
            for (Ag e : ordem) {
                if (e.t > agora || esperado.size() >= teto) {
                    break;
                }
                if (!cortada[(e.x >> 8) & 3]) {
                    esperado.add(e);
                }
            }
            List<Ag> controle = null;
            if (nenhumCorte && !fila.isEmpty()) {
                TreeSet<Ag> f2 = new TreeSet<Ag>(fila);
                Set<Ag> c2 = new HashSet<Ag>(conj);
                controle = new ArrayList<Ag>();
                PuxadaAgendada.original(f2, c2, controle, Math.min(teto, f2.size()), agora, ACESSO);
            }
            List<Ag> doTick = new ArrayList<Ag>();
            int n = PuxadaAgendada.puxar(fila, conj, doTick, teto, agora, ACESSO, dec);
            chamadas++;
            puxadas += n;
            if (n > teto) {
                acimaDoTeto++;
            }
            if (!doTick.equals(esperado) || n != doTick.size()) {
                erros++;
            }
            if (controle != null && !controle.equals(doTick)) {
                difOriginal++;
            }
            for (Ag e : doTick) {
                if (fila.contains(e) || conj.contains(e)) {
                    erros++;
                }
            }
            if (fila.size() != conj.size()) {
                erros++;
            }
            for (Ag e : ordem) {
                if (e.t <= agora && cortada[(e.x >> 8) & 3]) {
                    guardadas++;
                    if (!fila.contains(e) || !conj.contains(e)) {
                        erros++; // guardada sumiu de uma das colecoes
                    }
                }
            }
        }
        v.igual(0, erros, "(puxada) " + operacoes + " operacoes: puxadas = modelo (mesma ordem), guardadas ficam nas duas colecoes, tamanhos iguais");
        v.igual(0, acimaDoTeto, "(puxada) nunca mais que o teto por chamada");
        v.igual(0, difOriginal, "(puxada) sem corte, igual ao laco original do jogo");
        v.sim(puxadas > 1000 && guardadas > 1000 && chamadas > 1000, "(puxada) o teste exercitou: " + chamadas + " puxadas, "
                + puxadas + " entradas puxadas, " + guardadas + " vistas guardadas");
    }

    // ---------------------------------------------------------------- 4. diferencial dos agendados depois de alcancar

    /**
     * Dois agendadores com a mesma lista de entradas (por tick, posicoes unicas): um com a regiao 0 cortada (cadencia de
     * Beatty, N = 7) nos primeiros 2000 ticks, outro sem corte. Depois do corte, os dois rodam ate esvaziar a fila.
     */
    static void diferencialAgendados(Verifica v, boolean rajada) {
        Random r = new Random(rajada ? 7L : 3L);
        Map<Integer, List<long[]>> plano = new HashMap<Integer, List<long[]>>(); // tick -> {regiao, atraso, prio, marca}
        long marca = 0;
        for (int tk = 1; tk <= 2000; tk++) {
            List<long[]> l = new ArrayList<long[]>();
            int k = rajada && tk % 500 == 0 ? 3000 : r.nextInt(6);
            for (int i = 0; i < k; i++) {
                l.add(new long[] {r.nextInt(4), rajada && tk % 500 == 0 ? 5 : r.nextInt(40), r.nextInt(3) - 1, marca++});
            }
            plano.put(tk, l);
        }
        String nome = rajada ? "(alcancar, rajada) " : "(alcancar) ";
        List<List<Long>> ordemCorte = new ArrayList<List<Long>>(), ordemRef = new ArrayList<List<Long>>();
        Map<Long, Integer> tickCorte = new HashMap<Long, Integer>(), tickRef = new HashMap<Long, Integer>();
        int maxCorte = 0, maxTetoViolado = 0;
        for (int modo = 0; modo < 2; modo++) {
            final boolean comCorte = modo == 0;
            TreeSet<Ag> fila = new TreeSet<Ag>();
            Set<Ag> conj = new HashSet<Ag>();
            List<List<Long>> ordem = comCorte ? ordemCorte : ordemRef;
            Map<Long, Integer> quando = comCorte ? tickCorte : tickRef;
            for (int i = 0; i < 4; i++) {
                ordem.add(new ArrayList<Long>());
            }
            double acc = 0.3;
            for (int tk = 1; tk < 20000; tk++) {
                List<long[]> l = plano.get(tk);
                if (l != null) {
                    for (long[] p : l) {
                        // posicao unica por marca (sem a deduplicacao do HashSet, que e outra regra: testada a parte)
                        Ag e = new Ag((int) p[0] * 256 + (int) (p[3] % 200), (int) (p[3] / 200) % 256, (int) (p[3] / 51200), "b",
                                tk + p[1], (int) p[2], p[3]);
                        conj.add(e);
                        fila.add(e);
                    }
                }
                final boolean rodaZero;
                if (comCorte && tk <= 2000) {
                    rodaZero = Cadencia.tica(acc, 7);
                    acc = Cadencia.depois(acc, 7);
                } else {
                    rodaZero = true;
                }
                List<Ag> doTick = new ArrayList<Ag>();
                int teto = PuxadaAgendada.teto(fila.size());
                if (fila.isEmpty()) {
                    if (tk > 2100) {
                        break;
                    }
                    continue;
                }
                if (comCorte && !rodaZero) {
                    PuxadaAgendada.puxar(fila, conj, doTick, teto, tk, ACESSO, new PuxadaAgendada.Decisor() {
                        @Override
                        public boolean roda(int x, int z) {
                            return (x >> 8) != 0;
                        }
                    });
                } else {
                    PuxadaAgendada.original(fila, conj, doTick, teto, tk, ACESSO);
                }
                if (doTick.size() > teto) {
                    maxTetoViolado++;
                }
                if (comCorte) {
                    maxCorte = Math.max(maxCorte, doTick.size());
                }
                for (Ag e : doTick) {
                    ordem.get(e.x >> 8).add(e.marca);
                    if (quando.put(e.marca, tk) != null) {
                        quando.put(-1L - e.marca, tk); // duplicada
                    }
                }
            }
            v.igual(0, fila.size(), nome + (comCorte ? "com corte" : "sem corte") + ": a fila esvazia");
        }
        v.igual(marca, (long) tickRef.size(), nome + "sem corte: todas as " + marca + " entradas rodaram uma vez");
        v.igual(tickRef.keySet(), tickCorte.keySet(), nome + "com corte: as mesmas entradas rodaram, nenhuma perdida nem duplicada");
        boolean ordemIgual = true;
        for (int i = 0; i < 4; i++) {
            ordemIgual &= ordemCorte.get(i).equals(ordemRef.get(i));
        }
        v.sim(ordemIgual, nome + "em cada regiao, a mesma ordem de execucao do jogo sem corte");
        v.igual(0, maxTetoViolado, nome + "nunca mais que o teto do CraftBukkit por tick");
        if (!rajada) {
            int dif = 0, fora = 0;
            for (int i = 1; i < 4; i++) {
                for (Long m : ordemRef.get(i)) {
                    fora++;
                    if (!tickRef.get(m).equals(tickCorte.get(m))) {
                        dif++;
                    }
                }
            }
            v.sim(fora > 1000, nome + "entradas fora da regiao cortada: " + fora);
            v.igual(0, dif, nome + "fora da regiao cortada, cada entrada roda no mesmo tick do jogo sem corte");
        } else {
            v.sim(maxCorte <= 1000, nome + "na volta da regiao cortada, no maximo 1000 por tick (" + maxCorte + ")");
        }
    }

    // ---------------------------------------------------------------- 5. chunk descarregando com a regiao em corte

    /** Entradas vencidas ainda na fila no chunk (2,1) (guardadas pelo corte). */
    static int vencidasGuardadas(Mundo m) {
        int held = 0;
        for (Ag e : m.fila) {
            if ((e.x >> 4) == 2 && (e.z >> 4) == 1 && e.t <= m.tempoMundo) {
                held++;
            }
        }
        return held;
    }

    static void descarga(Verifica v) {
        Map<Long, Integer> execCorte = null, execRef = null;
        int heldNaGravacao = 0, gravadasNaGravacao = 0, rodadasAntes = 0, tileDepois = 0, ticksAteGravar = 20;
        for (int modo = 0; modo < 2; modo++) {
            boolean comCorte = modo == 0;
            Mundo m = tresBases(true, comCorte, 60.0);
            Tile tc = new Tile("tC1", 2 * 16 + 3, 20, 0.1); // chunk (2,1) de A
            m.tiles.add(tc);
            m.chunk(2, 1);
            m.ticks(150); // A cortada (com corte) a partir do tick 100
            // entradas unicas no chunk (2,1) de A, vencendo nos proximos 30 ticks
            for (int i = 0; i < 40; i++) {
                m.agendar(2 * 16 + (i % 16), 10 + i / 16, 16 + (i % 7), "pedra", 1 + i % 30, 0, 5000 + i);
            }
            // ate haver entrada vencida guardada pelo corte (depende da fase de A: pelo menos 20 ticks, no maximo 60); a
            // referencia anda o mesmo tanto
            if (comCorte) {
                ticksAteGravar = 0;
                do {
                    m.tick();
                    ticksAteGravar++;
                } while (ticksAteGravar < 20 || (vencidasGuardadas(m) == 0 && ticksAteGravar < 60));
            } else {
                m.ticks(ticksAteGravar);
            }
            // grava e descarrega o chunk (2,1): getPendingBlockUpdates(chunk, false) le a TreeSet (e a lista do tick)
            List<long[]> gravadas = new ArrayList<long[]>();
            int held = 0;
            for (Ag e : m.fila) {
                if ((e.x >> 4) == 2 && (e.z >> 4) == 1) {
                    gravadas.add(new long[] {e.x, e.y, e.z, e.t - m.tempoMundo, e.prio, e.marca});
                    if (e.t <= m.tempoMundo) {
                        held++;
                    }
                }
            }
            if (comCorte) {
                heldNaGravacao = held;
                gravadasNaGravacao = gravadas.size();
                for (Long k : m.execAg.keySet()) {
                    if (k >= 5000) {
                        rodadasAntes++;
                    }
                }
            }
            m.carregados.remove(chaveChunk(2, 1));
            tc.invalida = true; // onChunkUnload: a tile sai na limpeza do laco (antes do gancho)
            int tilesAntes = tc.ticks;
            m.ticks(80); // a regiao roda algumas vezes com o chunk fora: o jogo descarta as entradas (chunk nao existe)
            tileDepois += tc.ticks - tilesAntes;
            // recarga: as entradas gravadas voltam (func_147446_b; a mesma posicao ainda na fila e recusada)
            m.carregados.add(chaveChunk(2, 1));
            for (long[] g : gravadas) {
                m.agendar((int) g[0], (int) g[1], (int) g[2], "pedra", g[3], (int) g[4], g[5]);
            }
            m.ticks(200);
            Map<Long, Integer> ex = new HashMap<Long, Integer>();
            for (Map.Entry<Long, Integer> e : m.execAg.entrySet()) {
                if (e.getKey() >= 5000) {
                    ex.put(e.getKey(), e.getValue());
                }
            }
            if (comCorte) {
                execCorte = ex;
                Retrato.Regiao a = m.n.retrato().emBloco(0, 20, 20);
                v.sim(a != null && a.n > 1, "(descarga) A continuou cortada durante o teste (N " + (a == null ? 0 : a.n) + ")");
            } else {
                execRef = ex;
            }
        }
        v.sim(heldNaGravacao > 0, "(descarga) havia entradas vencidas guardadas no chunk na hora de gravar: " + heldNaGravacao);
        v.igual(40, gravadasNaGravacao + rodadasAntes, "(descarga) na gravacao, toda entrada ou ja tinha rodado ou foi gravada com o chunk ("
                + rodadasAntes + " + " + gravadasNaGravacao + ")");
        boolean umaVez = execCorte != null && execCorte.size() == 40;
        for (Integer k : execCorte.values()) {
            umaVez &= k == 1;
        }
        v.sim(umaVez, "(descarga) com corte: cada uma das 40 entradas rodou uma vez so (" + execCorte.size() + ")");
        v.igual(execRef.keySet(), execCorte.keySet(), "(descarga) as mesmas entradas rodaram com e sem corte");
        v.igual(0, tileDepois, "(descarga) a tile do chunk descarregado nunca mais rodou (saiu na limpeza, antes do gancho)");
    }

    // ---------------------------------------------------------------- 6. erro num gancho

    static void erroNoGancho(Verifica v) {
        Mundo m = tresBases(true, true, 60.0);
        m.ticks(100);
        v.sim(m.n.cortaNesteTick() || m.n.retrato().emBloco(0, 20, 20).n > 1, "(erro) A cortada antes do erro");
        for (int i = 0; i < 5; i++) {
            m.n.falhaNoPonto(0, 20, 20, new IllegalStateException("erro de proposito " + i));
        }
        int logs = 0;
        for (String l : m.logs) {
            if (l.contains("erro interno no gancho")) {
                logs++;
            }
        }
        v.igual(1, logs, "(erro) 5 erros na mesma regiao: 1 linha de log (limitador)");
        // num tick em que A nao roda: o erro libera A no mesmo tick (o resto do tick roda)
        Mundo q = tresBases(true, true, 60.0);
        q.ticks(101);
        boolean achou = false, liberou = false;
        for (int k = 0; k < 60 && !achou; k++) {
            q.n.inicioTick();
            if (!q.n.rodaEm(0, 20, 20)) {
                achou = true;
                q.n.falhaNoPonto(0, 20, 20, new IllegalStateException("no meio do tick"));
                liberou = q.n.rodaEm(0, 20, 20) && q.n.tile(0, 20, 20);
            }
            q.n.fimLaco();
            q.n.fimTick();
        }
        v.sim(achou && liberou, "(erro) erro no meio do tick: a regiao volta a rodar ja no resto daquele tick");
        v.sim(m.n.ativo(), "(erro) o orcamento continua ligado para as outras regioes");
        int perdeu = 0;
        for (int k = 0; k < 300; k++) {
            m.tick();
            if (!m.rodouNoTick.contains("A:tile")) {
                perdeu++;
            }
        }
        v.igual(0, perdeu, "(erro) a regiao do erro volta a ticar todo tick, inclusive depois de 3 recalculos do mapa");
        Retrato.Regiao a = m.n.retrato().emBloco(0, 20, 20);
        v.sim(a != null && "protegida".equals(a.estado) && "erro interno".equals(a.protecao), "(erro) a regiao aparece como protegida (erro interno)");
        for (int i = 0; i < Nucleo.LIMITE_ERROS_GANCHO; i++) {
            m.n.falhaNoPonto(0, 20 * 256 + 20, 20, new IllegalStateException("mais um"));
        }
        v.sim(!m.n.ativo(), "(erro) mais de " + Nucleo.LIMITE_ERROS_GANCHO + " erros: o orcamento inteiro desliga");
        v.sim(m.n.tile(0, 20, 20) && m.n.decidirEntidade(0, 20, 20, false, false) && !m.n.cortaNesteTick(),
                "(erro) desligado: tudo roda e a puxada usa o laco original");
        Mundo d = tresBases(true, true, 60.0);
        d.ticks(5);
        d.n.falhaNoPonto(0, 99999, 99999, new IllegalStateException("fora do mapa"));
        v.sim(!d.n.ativo(), "(erro) erro num ponto sem regiao: desliga tudo (seguranca primeiro)");
    }

    // ---------------------------------------------------------------- 7. ponte entre duas regioes

    static void ponte(Verifica v) {
        Mundo m = tresBases(true, true, 60.0);
        m.ticks(105); // A cortada no fim do tick 100; mapa recalculado no 101 (o proximo pedido so vale 20 ticks depois)
        v.sim(m.n.retrato().emBloco(0, 20, 20).n == 25 && m.n.retrato().emBloco(0, 20 * 256 + 20, 20).n == 1,
                "(ponte) antes: A cortada, B normal");
        // carrega uma linha de chunks com tiles de A ate B (secoes 3, 6, ..., 18): uma rede que passa a ligar as bases
        for (int s = 3; s <= 18; s += 3) {
            m.chunk(s * 16, 1);
            m.tiles.add(new Tile("ponte" + s, s * 256 + 5, 20, 0.05));
        }
        int aTodoTick = 0, ticksSusp = 0;
        int k = 0;
        long recAntes = m.n.recalculos();
        for (; k < 30 && m.n.recalculos() == recAntes; k++) {
            m.tick();
            if (k >= 1 && m.n.recalculos() == recAntes) {
                ticksSusp++;
                if (m.exec.contains(m.tick + " tile tA")) { // a tile de A (as da ponte tambem tem rotulo "A")
                    aTodoTick++;
                }
            }
        }
        v.sim(m.n.recalculos() > recAntes && k <= 22, "(ponte) a secao nova pede recalculo do mapa (em " + k + " ticks)");
        v.sim(ticksSusp > 0 && aTodoTick == ticksSusp, "(ponte) ate o recalculo, A fica suspensa e roda todo tick (" + aTodoTick + "/" + ticksSusp + ")");
        Retrato.Regiao a = m.n.retrato().emBloco(0, 20, 20), b = m.n.retrato().emBloco(0, 20 * 256 + 20, 20);
        v.sim(a != null && a == b, "(ponte) depois do recalculo, A e B sao uma regiao so");
        v.sim(a != null && a.n == 25, "(ponte) a regiao junta herda o maior N (25): " + (a == null ? 0 : a.n));
        int juntos = 0, rodou = 0;
        for (int i = 0; i < 200; i++) {
            m.tick();
            boolean ra = m.exec.contains(m.tick + " tile tA"), rb = m.exec.contains(m.tick + " tile tB");
            if (ra == rb) {
                juntos++;
            }
            if (ra) {
                rodou++;
            }
        }
        v.igual(200, juntos, "(ponte) A e B (ligadas) rodam e pulam juntas: nada da rede fica cortado pela metade");
        v.sim(rodou >= 6 && rodou <= 10, "(ponte) e a regiao junta roda ~1 a cada 25: " + rodou + " em 200");
    }

    // ---------------------------------------------------------------- 8. isencao (cadeia de montaria)

    static void isencao(Verifica v) {
        Ent p = new Ent("p", 0, 0);
        p.jogador = true;
        Ent a = new Ent("a", 0, 0), b = new Ent("b", 0, 0), c = new Ent("c", 0, 0), solta = new Ent("solta", 0, 0);
        // c monta em b, b monta em a, p monta em c: a e b e c sao montarias do jogador (para baixo)
        a.montadoPor = b;
        b.montadoEm = a;
        b.montadoPor = c;
        c.montadoEm = b;
        c.montadoPor = p;
        p.montadoEm = c;
        Ent q = new Ent("q", 0, 0);
        p.montadoPor = q;
        q.montadoEm = p;
        v.sim(Isencao.isenta(p, MONTARIA), "(isencao) o jogador");
        v.sim(Isencao.isenta(a, MONTARIA) && Isencao.isenta(b, MONTARIA) && Isencao.isenta(c, MONTARIA),
                "(isencao) a montaria, a montaria da montaria e a de baixo de todas");
        v.sim(Isencao.isenta(q, MONTARIA), "(isencao) quem o jogador carrega");
        v.sim(!Isencao.isenta(solta, MONTARIA) && !Isencao.isenta(null, MONTARIA), "(isencao) entidade sem jogador na cadeia: nao");
        Ent x = new Ent("x", 0, 0), y = new Ent("y", 0, 0);
        x.montadoPor = y;
        y.montadoPor = x; // ciclo corrompido
        v.sim(!Isencao.isenta(x, MONTARIA), "(isencao) ciclo de montaria corrompido nao trava (profundidade limitada)");
    }
}
