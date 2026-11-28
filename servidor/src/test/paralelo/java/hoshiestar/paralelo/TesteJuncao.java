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
package hoshiestar.paralelo;

import hoshiestar.teste.Verifica;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Passo 4 do plano do Degrau B: {@link Lote}, {@link Ordenada} e {@link Juncao} num mundo falso, sem Minecraft. Molde dos
 * casos adversarios do orcamento: cada caso monta um cenario, roda e confere a contagem de itens antes e depois (nada
 * perdido nem duplicado), quantas vezes cada tile ticou (no maximo uma; exatamente uma quando nada falhou) e quantas
 * vezes cada adiado foi aplicado (exatamente uma). O diferencial sorteado compara o tick em serie (o original) com
 * lotes + juncao.
 *
 * <p>O mundo falso: inventarios com itens, o chao (onde cai o inventario de um tile quebrado), uma fila de eventos de bloco
 * com a deduplicacao do {@code addBlockEvent}, ticks agendados com a do {@code pendingTickListEntriesHashSet} (o primeiro
 * vence) e sons (sem deduplicacao). Cada tile move itens entre dois inventarios da propria regiao e anota eventos, ticks e
 * sons; com o contexto de lote aberto, essas tres operacoes sao adiadas (como os ganchos do jogo fazem).
 */
public final class TesteJuncao {

    // ---- o mundo falso

    static final class Mundo {
        final int[] inv;
        int chao;
        final List<String> eventos = new ArrayList<String>();
        final Map<String, String> agendados = new LinkedHashMap<String, String>();
        final List<String> sons = new ArrayList<String>();
        final Map<String, Integer> aplicacoes = new LinkedHashMap<String, Integer>();
        final Set<Tile> noChunk = new HashSet<Tile>();
        Lote loteDaVez;
        boolean violou;
        int adiadosCriados;
        /** Falha injetada ao aplicar o adiado com este texto (uma vez). */
        String lancarAoAplicar;
        /** Falha injetada no Contexto.abrir do lote desta regiao (erro nosso). */
        int erroNossoNaRegiao = Integer.MIN_VALUE;

        Mundo(int nInv, int itensPorInv) {
            inv = new int[nInv];
            java.util.Arrays.fill(inv, itensPorInv);
        }

        int total() {
            int s = chao;
            for (int x : inv) {
                s += x;
            }
            return s;
        }

        void evento(String e) {
            if (loteDaVez != null) {
                adiar("evento " + e, new Runnable() {
                    @Override
                    public void run() {
                        eventoOriginal(e);
                    }
                });
            } else {
                eventoOriginal(e);
            }
        }

        void eventoOriginal(String e) {
            if (!eventos.contains(e)) {
                eventos.add(e); // dedup do addBlockEvent
            }
        }

        void agendar(final String pos, final String quem) {
            if (loteDaVez != null) {
                adiar("tick " + pos + " de " + quem, new Runnable() {
                    @Override
                    public void run() {
                        agendarOriginal(pos, quem);
                    }
                });
            } else {
                agendarOriginal(pos, quem);
            }
        }

        void agendarOriginal(String pos, String quem) {
            if (!agendados.containsKey(pos)) {
                agendados.put(pos, quem); // o primeiro vence (HashSet do original)
            }
        }

        void som(final String s) {
            if (loteDaVez != null) {
                adiar("som " + s, new Runnable() {
                    @Override
                    public void run() {
                        sons.add(s);
                    }
                });
            } else {
                sons.add(s);
            }
        }

        private void adiar(final String d, final Runnable r) {
            final String chave = d + " #" + (adiadosCriados++);
            loteDaVez.adiar(new Adiado() {
                @Override
                public void aplicar() {
                    Integer n = aplicacoes.get(chave);
                    aplicacoes.put(chave, n == null ? 1 : n + 1);
                    if (d.equals(lancarAoAplicar)) {
                        lancarAoAplicar = null;
                        throw new IllegalStateException("falha injetada ao aplicar " + d);
                    }
                    r.run();
                }

                @Override
                public String descrever() {
                    return chave;
                }
            });
        }

        void quebrar(Tile t) {
            t.valido = false;
            noChunk.remove(t);
            chao += inv[t.de];
            inv[t.de] = 0;
        }
    }

    static final class Tile {
        final int id, regiao, de, para, k;
        boolean valido = true;
        int ticks;
        /** 0 nao lanca; 1 antes de tudo; 2 depois do movimento; 3 depois dos adiados. */
        int lancar;
        boolean viola;
        final List<String> eventos = new ArrayList<String>();
        final List<String> agendas = new ArrayList<String>();
        final List<String> sons = new ArrayList<String>();

        Tile(int id, int regiao, int de, int para, int k) {
            this.id = id;
            this.regiao = regiao;
            this.de = de;
            this.para = para;
            this.k = k;
        }

        void tick(Mundo m) {
            ticks++;
            if (lancar == 1) {
                throw new RuntimeException("tile " + id + " lancou antes");
            }
            if (m.inv[de] >= k) {
                m.inv[de] -= k;
                m.inv[para] += k;
            }
            if (lancar == 2) {
                throw new RuntimeException("tile " + id + " lancou depois do movimento");
            }
            for (String e : eventos) {
                m.evento(e);
            }
            for (String a : agendas) {
                m.agendar(a, "t" + id);
            }
            for (String s : sons) {
                m.som(s);
            }
            if (viola) {
                m.violou = true;
            }
            if (lancar == 3) {
                throw new Error("tile " + id + " lancou depois dos adiados");
            }
        }
    }

    static Lote.Ticador ticador(final Mundo m) {
        return new Lote.Ticador() {
            @Override
            public boolean vivo(Object tile, boolean serie) {
                Tile t = (Tile) tile;
                return t.valido && (!serie || m.noChunk.contains(t));
            }

            @Override
            public void ticar(Object tile) {
                ((Tile) tile).tick(m);
            }

            @Override
            public String descrever(Object tile) {
                return "tile " + ((Tile) tile).id;
            }
        };
    }

    static Ordenada.Contexto contexto(final Mundo m) {
        return new Ordenada.Contexto() {
            @Override
            public void abrir(Lote l) {
                if (l.regiao == m.erroNossoNaRegiao) {
                    m.erroNossoNaRegiao = Integer.MIN_VALUE;
                    throw new IllegalStateException("erro nosso injetado ao abrir o contexto");
                }
                m.loteDaVez = l;
            }

            @Override
            public void fechar() {
                m.loteDaVez = null;
            }

            @Override
            public boolean violou() {
                return m.violou;
            }

            @Override
            public long agora() {
                return System.nanoTime();
            }
        };
    }

    /** Monta os lotes das regioes de {@code elegiveis} na ordem do laco; chave do lote = chaves[regiao]. */
    static List<Lote> lotes(Mundo m, List<Tile> laco, Set<Integer> elegiveis, long[] chaves) {
        Map<Integer, Lote> por = new LinkedHashMap<Integer, Lote>();
        Lote.Ticador tic = ticador(m);
        for (Tile t : laco) {
            m.noChunk.add(t);
            if (!elegiveis.contains(t.regiao)) {
                continue;
            }
            Lote l = por.get(t.regiao);
            if (l == null) {
                l = new Lote(t.regiao, tic);
                por.put(t.regiao, l);
            }
            l.adicionar(t, chaves == null ? t.regiao : chaves[t.regiao]);
        }
        return new ArrayList<Lote>(por.values());
    }

    /** O laco "original" de sombra_ordenada: tiles fora dos lotes ticam na hora, em serie; depois os lotes e a juncao. */
    static Juncao.Resultado rodarOrdenada(Mundo m, List<Tile> laco, List<Lote> lotes, Set<Integer> elegiveis) {
        for (Tile t : laco) {
            if (!elegiveis.contains(t.regiao) && t.valido) {
                t.tick(m);
            }
        }
        List<Andamento> and;
        try {
            and = Ordenada.rodar(lotes, contexto(m));
        } catch (RuntimeException e) {
            m.loteDaVez = null;
            return Juncao.emergencia(lotes);
        }
        return Juncao.juntar(and);
    }

    static void serie(Mundo m, List<Tile> laco) {
        for (Tile t : laco) {
            if (t.valido) {
                t.tick(m);
            }
        }
    }

    // ---- conferencias comuns

    static void conferir(Verifica v, String caso, Mundo m, int total, List<Tile> tiles) {
        v.igual(total, m.total(), caso + ": itens antes = depois (nada perdido nem duplicado)");
        int mais = 0;
        for (Tile t : tiles) {
            if (t.ticks > 1) {
                mais++;
            }
        }
        v.igual(0, mais, caso + ": nenhum tile ticou duas vezes");
        int repetidos = 0;
        for (Integer n : m.aplicacoes.values()) {
            if (n != 1) {
                repetidos++;
            }
        }
        v.igual(0, repetidos, caso + ": nenhum adiado aplicado duas vezes");
    }

    static void todosUmaVez(Verifica v, String caso, List<Tile> tiles) {
        List<Integer> errados = new ArrayList<Integer>();
        for (Tile t : tiles) {
            if (t.valido && t.ticks != 1) {
                errados.add(t.id);
            }
        }
        v.igual(new ArrayList<Integer>(), errados, caso + ": cada tile vivo ticou exatamente uma vez");
    }

    static Set<Integer> set(Integer... xs) {
        Set<Integer> s = new HashSet<Integer>();
        Collections.addAll(s, xs);
        return s;
    }

    /** Tres regioes (0, 1, 2), elegiveis 1 e 2, tres tiles cada; o inventario 2r e 2r+1 sao da regiao r. */
    static List<Tile> base(int nPorRegiao) {
        List<Tile> l = new ArrayList<Tile>();
        int id = 0;
        for (int i = 0; i < nPorRegiao; i++) {
            for (int r = 0; r < 3; r++) {
                Tile t = new Tile(id++, r, 2 * r, 2 * r + 1, 1 + (id % 3));
                t.eventos.add("r" + r + ":e" + (i % 2)); // duas vezes o mesmo evento por regiao: dedup
                t.agendas.add("r" + r + ":p0"); // a mesma posicao em toda a regiao: o primeiro vence
                t.sons.add("r" + r + ":som" + i);
                l.add(t);
            }
        }
        return l;
    }

    public static void main(String[] args) {
        Verifica v = new Verifica("TesteJuncao");
        try {
            limpos(v);
            dedup(v);
            sujoAntes(v);
            quebradoDepoisDoDespacho(v);
            invalidoSemSujar(v);
            substituido(v);
            for (int onde = 1; onde <= 3; onde++) {
                erroNoMeio(v, onde);
            }
            erroNoPrimeiroENoUltimo(v);
            erroNoLoteSujo(v);
            erroAoAplicar(v);
            violacao(v);
            ordemPorChave(v);
            reentrada(v);
            erroNossoNoMeio(v);
            erroNossoAntes(v);
            fechosRaros(v);
            loteVazio(v);
            sujoNoMeio(v);
            ordemIndependente(v);
            diferencial(v);
            decidir(v);
        } catch (Throwable t) {
            v.erro("excecao no teste", t);
        }
        v.fim();
    }

    // ---- casos

    /** 1. Todos limpos, sem erro: igual ao tick em serie (diferencial simples). */
    static void limpos(Verifica v) {
        Mundo ref = new Mundo(6, 10);
        List<Tile> lr = base(3);
        serie(ref, lr);
        Mundo m = new Mundo(6, 10);
        List<Tile> lt = base(3);
        int total = m.total();
        Juncao.Resultado r = rodarOrdenada(m, lt, lotes(m, lt, set(1, 2), null), set(1, 2));
        conferir(v, "limpos", m, total, lt);
        todosUmaVez(v, "limpos", lt);
        v.sim(!r.temFalha(), "limpos: sem falha");
        v.igual(java.util.Arrays.toString(ref.inv), java.util.Arrays.toString(m.inv), "limpos: inventarios iguais ao tick em serie");
        v.igual(new HashSet<String>(ref.eventos), new HashSet<String>(m.eventos), "limpos: mesmos eventos de bloco");
        v.igual(ref.agendados, m.agendados, "limpos: mesmos ticks agendados (e o mesmo vencedor por posicao)");
        v.igual(ref.sons.size(), m.sons.size(), "limpos: mesmo numero de sons");
        v.igual(m.adiadosCriados, r.adiadosAplicados, "limpos: todo adiado aplicado na juncao");
    }

    /** 2. A mescla mantem a deduplicacao do original: o mesmo evento duas vezes no lote vira uma entrada. */
    static void dedup(Verifica v) {
        Mundo m = new Mundo(6, 10);
        List<Tile> lt = base(4);
        rodarOrdenada(m, lt, lotes(m, lt, set(1, 2), null), set(1, 2));
        int r1 = 0;
        for (String e : m.eventos) {
            if (e.startsWith("r1:")) {
                r1++;
            }
        }
        v.igual(2, r1, "dedup: dois eventos distintos da regiao 1 (quatro tiles, dois repetidos), sem duplicata na fila");
        v.igual(new HashSet<String>(m.eventos).size(), m.eventos.size(), "dedup: nenhuma entrada repetida na fila de eventos");
        v.igual("t1", m.agendados.get("r1:p0"), "dedup: tick agendado na mesma posicao: vence o primeiro tile da regiao no laco (t1)");
    }

    /** 3. Lote sujo antes da fase: roda inteiro em serie na juncao, sem adiar (efeitos diretos), com a reconferencia forte. */
    static void sujoAntes(Verifica v) {
        Mundo m = new Mundo(6, 10);
        List<Tile> lt = base(3);
        int total = m.total();
        List<Lote> ls = lotes(m, lt, set(1, 2), null);
        ls.get(0).sujar();
        Juncao.Resultado r = rodarOrdenada(m, lt, ls, set(1, 2));
        conferir(v, "sujo antes", m, total, lt);
        todosUmaVez(v, "sujo antes", lt);
        v.igual(1, r.lotesEmSerie, "sujo antes: um lote rodou em serie na juncao");
        v.igual(3, r.itensEmSerie, "sujo antes: os 3 tiles dele em serie");
        v.igual(0, ls.get(0).adiados().size(), "sujo antes: o lote em serie nao adiou nada (efeitos diretos)");
    }

    /** 4. Tile quebrado em serie depois do despacho (injetar quebrar): o lote fica sujo; o tile e pulado; os itens dele caem. */
    static void quebradoDepoisDoDespacho(Verifica v) {
        Mundo m = new Mundo(6, 10);
        List<Tile> lt = base(3);
        int total = m.total();
        List<Lote> ls = lotes(m, lt, set(1, 2), null);
        Tile alvo = (Tile) ls.get(0).tile(1);
        m.quebrar(alvo);
        ls.get(0).sujar(); // a escrita em serie numa regiao elegivel suja o lote (a guarda faz isso no jogo)
        Juncao.Resultado r = rodarOrdenada(m, lt, ls, set(1, 2));
        conferir(v, "quebrado", m, total, lt);
        v.igual(0, alvo.ticks, "quebrado: o tile quebrado nao ticou");
        v.igual(1, ls.get(0).pulados(), "quebrado: um pulado pela reconferencia");
        todosUmaVez(v, "quebrado", lt);
        v.sim(!r.temFalha(), "quebrado: sem falha");
    }

    /** 5. Tile invalidado sem sujar o lote (defensivo): a reconferencia do lote limpo tambem pula. */
    static void invalidoSemSujar(Verifica v) {
        Mundo m = new Mundo(6, 10);
        List<Tile> lt = base(3);
        int total = m.total();
        List<Lote> ls = lotes(m, lt, set(1, 2), null);
        Tile alvo = (Tile) ls.get(1).tile(0);
        m.quebrar(alvo);
        rodarOrdenada(m, lt, ls, set(1, 2));
        conferir(v, "invalido limpo", m, total, lt);
        v.igual(0, alvo.ticks, "invalido limpo: nao ticou");
        todosUmaVez(v, "invalido limpo", lt);
    }

    /**
     * 5b. Tile ainda valido, mas que nao e mais o do chunk naquela posicao (outro objeto no lugar): no lote sujo, que roda em
     * serie na juncao, a reconferencia forte pula; no lote limpo (reconferencia do trabalhador) ele tica, como no laco
     * original, que tambem nao olha o chunk.
     */
    static void substituido(Verifica v) {
        for (boolean sujo : new boolean[] {true, false}) {
            String caso = "substituido (lote " + (sujo ? "sujo" : "limpo") + ")";
            Mundo m = new Mundo(6, 10);
            List<Tile> lt = base(3);
            int total = m.total();
            List<Lote> ls = lotes(m, lt, set(1, 2), null);
            Tile alvo = (Tile) ls.get(0).tile(2);
            m.noChunk.remove(alvo);
            if (sujo) {
                ls.get(0).sujar();
            }
            rodarOrdenada(m, lt, ls, set(1, 2));
            conferir(v, caso, m, total, lt);
            v.igual(sujo ? 0 : 1, alvo.ticks, caso + (sujo ? ": pulado pela reconferencia forte" : ": tica (a reconferencia do lote limpo e a fraca)"));
        }
    }

    /** 6-8. Excecao no meio de um lote limpo (antes, depois do movimento, depois dos adiados). */
    static void erroNoMeio(Verifica v, int onde) {
        String caso = "erro no meio (" + onde + ")";
        Mundo m = new Mundo(6, 10);
        List<Tile> lt = base(3);
        int total = m.total();
        List<Lote> ls = lotes(m, lt, set(1, 2), null);
        Tile t = (Tile) ls.get(0).tile(1);
        t.lancar = onde;
        Juncao.Resultado r = rodarOrdenada(m, lt, ls, set(1, 2));
        conferir(v, caso, m, total, lt);
        v.igual(1, r.falhas.size(), caso + ": uma falha");
        v.igual("tile", r.falhas.get(0).onde, caso + ": falha de tile");
        v.igual(0, ((Tile) ls.get(0).tile(2)).ticks, caso + ": o item seguinte do mesmo lote nao rodou (secao 9)");
        v.igual(3, r.itensEmSerie, caso + ": o lote seguinte sobrou e rodou inteiro em serie na juncao");
        int adiados = ls.get(0).adiados().size();
        v.igual(onde == 3 ? 6 : 3, adiados, caso + ": adiados de tudo o que rodou no lote com erro (inclusive do item que lancou)");
        v.igual(ls.get(0).adiados().size() + ls.get(1).adiados().size(), r.adiadosAplicados, caso + ": e todos foram aplicados");
    }

    /** 9. Excecao no primeiro e no ultimo item. */
    static void erroNoPrimeiroENoUltimo(Verifica v) {
        for (int pos : new int[] {0, 2}) {
            String caso = "erro no item " + pos;
            Mundo m = new Mundo(6, 10);
            List<Tile> lt = base(3);
            int total = m.total();
            List<Lote> ls = lotes(m, lt, set(1, 2), null);
            ((Tile) ls.get(1).tile(pos)).lancar = 2;
            Juncao.Resultado r = rodarOrdenada(m, lt, ls, set(1, 2));
            conferir(v, caso, m, total, lt);
            v.igual(1, r.falhas.size(), caso + ": uma falha");
            v.igual(pos + 1, ls.get(1).proximo(), caso + ": cursor depois do item que lancou");
        }
    }

    /** 10. Excecao num lote sujo, rodando em serie na juncao: o resto dele nao roda; o outro lote segue. */
    static void erroNoLoteSujo(Verifica v) {
        Mundo m = new Mundo(6, 10);
        List<Tile> lt = base(3);
        int total = m.total();
        List<Lote> ls = lotes(m, lt, set(1, 2), null);
        ls.get(0).sujar();
        ((Tile) ls.get(0).tile(1)).lancar = 2;
        Juncao.Resultado r = rodarOrdenada(m, lt, ls, set(1, 2));
        conferir(v, "erro no sujo", m, total, lt);
        v.igual(1, r.falhas.size(), "erro no sujo: uma falha");
        v.igual("tile (em serie, na juncao)", r.falhas.get(0).onde, "erro no sujo: falha do resto em serie");
        v.igual(0, ((Tile) ls.get(0).tile(2)).ticks, "erro no sujo: o item seguinte nao rodou");
        v.igual(1, ((Tile) ls.get(1).tile(2)).ticks, "erro no sujo: o outro lote rodou inteiro");
    }

    /** 11. Excecao ao aplicar um adiado: vira falha; os outros sao aplicados; nenhum duas vezes. */
    static void erroAoAplicar(Verifica v) {
        Mundo m = new Mundo(6, 10);
        List<Tile> lt = base(3);
        int total = m.total();
        m.lancarAoAplicar = "som r1:som1";
        List<Lote> ls = lotes(m, lt, set(1, 2), null);
        Juncao.Resultado r = rodarOrdenada(m, lt, ls, set(1, 2));
        conferir(v, "erro ao aplicar", m, total, lt);
        v.igual(1, r.falhas.size(), "erro ao aplicar: uma falha");
        v.igual("adiado", r.falhas.get(0).onde, "erro ao aplicar: falha de adiado");
        v.igual(m.adiadosCriados - 1, r.adiadosAplicados, "erro ao aplicar: todos os outros adiados aplicados");
        v.igual(m.adiadosCriados, m.aplicacoes.size(), "erro ao aplicar: cada adiado tentado uma vez");
        todosUmaVez(v, "erro ao aplicar", lt);
    }

    /** 12. Violacao no lote 1: ele termina; o lote 2 sobra e roda em serie na juncao (secao 3). */
    static void violacao(Verifica v) {
        Mundo m = new Mundo(6, 10);
        List<Tile> lt = base(3);
        int total = m.total();
        List<Lote> ls = lotes(m, lt, set(1, 2), null);
        ((Tile) ls.get(0).tile(0)).viola = true;
        List<Andamento> and = null;
        for (Tile t : lt) {
            if (t.regiao == 0) {
                t.tick(m);
            }
        }
        and = Ordenada.rodar(ls, contexto(m));
        v.igual(Andamento.Fim.COMPLETO, and.get(0).fecho().fim, "violacao: o lote que violou termina");
        v.igual(Andamento.Fim.SOBROU, and.get(1).fecho().fim, "violacao: o lote seguinte sobra");
        Juncao.Resultado r = Juncao.juntar(and);
        conferir(v, "violacao", m, total, lt);
        todosUmaVez(v, "violacao", lt);
        v.igual(3, r.itensEmSerie, "violacao: o que sobrou rodou em serie");
        v.igual(Juncao.Acao.PARAR, Juncao.decidir(r, m.violou, false, "parar", "parar"), "violacao: ao_violar parar -> PARAR");
        v.igual(Juncao.Acao.REBAIXAR, Juncao.decidir(r, m.violou, false, "parar", "rebaixar"), "violacao: ao_violar rebaixar -> REBAIXAR");
    }

    /** 13. Lotes montados fora da ordem da chave: a juncao aplica por chave. */
    static void ordemPorChave(Verifica v) {
        Mundo m = new Mundo(6, 10);
        List<Tile> lt = base(2);
        long[] chaves = {0, 900, 100}; // regiao 2 tem chave menor que a 1
        List<Lote> ls = lotes(m, lt, set(1, 2), chaves);
        rodarOrdenada(m, lt, ls, set(1, 2));
        int p1 = -1, p2 = -1;
        for (int i = 0; i < m.sons.size(); i++) {
            if (p1 < 0 && m.sons.get(i).startsWith("r1:")) {
                p1 = i;
            }
            if (p2 < 0 && m.sons.get(i).startsWith("r2:")) {
                p2 = i;
            }
        }
        v.sim(p2 >= 0 && p1 > p2, "ordem: os adiados da regiao de chave menor (2) vem antes (" + p2 + " < " + p1 + ")");
        // revisao do passo 4 (m3): os lotes tambem TICAM por chave, nao so a juncao aplica por chave
        Mundo m2 = new Mundo(6, 10);
        List<Tile> lt2 = base(2);
        List<Andamento> and = Ordenada.rodar(lotes(m2, lt2, set(1, 2), chaves), contexto(m2));
        v.sim(and.size() == 2 && and.get(0).chave == 100 && and.get(1).chave == 900,
                "ordem: o lote de chave menor (regiao 2) ticou primeiro, mesmo montado depois");
    }

    /** 14. Juncao chamada duas vezes, e emergencia depois da juncao: nada se repete. */
    static void reentrada(Verifica v) {
        Mundo m = new Mundo(6, 10);
        List<Tile> lt = base(3);
        int total = m.total();
        List<Lote> ls = lotes(m, lt, set(1, 2), null);
        ls.get(1).sujar();
        for (Tile t : lt) {
            if (t.regiao == 0) {
                t.tick(m);
            }
        }
        List<Andamento> and = Ordenada.rodar(ls, contexto(m));
        Juncao.juntar(and);
        Juncao.Resultado r2 = Juncao.juntar(and);
        Juncao.Resultado r3 = Juncao.emergencia(ls);
        try {
            ls.get(0).ticar(0); // abaixo do cursor: o nucleo nunca pede, mas se pedir nao tica de novo
        } catch (Throwable t) {
            v.erro("reentrada", t);
        }
        conferir(v, "reentrada", m, total, lt);
        todosUmaVez(v, "reentrada", lt);
        v.igual(0, r2.adiadosAplicados + r2.itensEmSerie, "reentrada: a segunda juncao nao aplica nem tica nada");
        v.igual(0, r3.adiadosAplicados + r3.itensEmSerie, "reentrada: a emergencia depois da juncao nao repete nada");
    }

    /** 15. Erro nosso no meio (o contexto do segundo lote lanca): a emergencia roda o resto uma vez. */
    static void erroNossoNoMeio(Verifica v) {
        Mundo m = new Mundo(6, 10);
        List<Tile> lt = base(3);
        int total = m.total();
        List<Lote> ls = lotes(m, lt, set(1, 2), null);
        m.erroNossoNaRegiao = 2;
        Juncao.Resultado r = rodarOrdenada(m, lt, ls, set(1, 2));
        conferir(v, "erro nosso no meio", m, total, lt);
        todosUmaVez(v, "erro nosso no meio", lt);
        v.igual(3, r.itensEmSerie, "erro nosso no meio: os 3 tiles do lote que nao rodou, em serie");
        v.igual(m.adiadosCriados, m.aplicacoes.size(), "erro nosso no meio: os adiados do primeiro lote foram aplicados");
    }

    /** 16. Erro nosso antes de qualquer lote: tudo em serie, uma vez. */
    static void erroNossoAntes(Verifica v) {
        Mundo m = new Mundo(6, 10);
        List<Tile> lt = base(3);
        int total = m.total();
        List<Lote> ls = lotes(m, lt, set(1, 2), null);
        m.erroNossoNaRegiao = 1;
        Juncao.Resultado r = rodarOrdenada(m, lt, ls, set(1, 2));
        conferir(v, "erro nosso antes", m, total, lt);
        todosUmaVez(v, "erro nosso antes", lt);
        v.igual(6, r.itensEmSerie, "erro nosso antes: os 6 tiles dos dois lotes em serie");
        v.igual(0, m.adiadosCriados, "erro nosso antes: nada adiado (tudo direto)");
    }

    /** 17. Fechos que so o modo paralelo produz: MORREU, ABORTADO e lote nao fechado. */
    static void fechosRaros(Verifica v) {
        for (Andamento.Fim fim : new Andamento.Fim[] {Andamento.Fim.MORREU, Andamento.Fim.ABORTADO, null}) {
            String caso = "fecho " + fim;
            Mundo m = new Mundo(6, 10);
            List<Tile> lt = base(3);
            int total = m.total();
            List<Lote> ls = lotes(m, lt, set(1), null);
            Lote l = ls.get(0);
            m.loteDaVez = l;
            try {
                l.ticar(0);
                l.ticar(1); // o item 1 "ficou pela metade" quando a thread morreu
            } catch (Throwable t) {
                v.erro(caso, t);
            }
            m.loteDaVez = null;
            Andamento a = new Andamento(l);
            if (fim != null) {
                a.fechar(fim, 1, null, 0, "teste");
            }
            List<Andamento> and = new ArrayList<Andamento>();
            and.add(a);
            Juncao.Resultado r = Juncao.juntar(and);
            conferir(v, caso, m, total, lt);
            v.igual(0, ((Tile) l.tile(2)).ticks, caso + ": o resto do lote nao roda em serie");
            v.igual(1, ((Tile) l.tile(1)).ticks, caso + ": o item que estava rodando nao roda de novo");
            v.igual(m.adiadosCriados, r.adiadosAplicados, caso + ": adiados de tudo o que rodou aplicados");
            v.igual(fim == Andamento.Fim.ABORTADO ? 0 : 1, r.falhas.size(), caso + ": falha registrada (menos no abortado: parada)");
        }
    }

    /** 18. Lote vazio: nada. */
    static void loteVazio(Verifica v) {
        Mundo m = new Mundo(2, 5);
        Lote l = new Lote(7, ticador(m));
        List<Lote> ls = new ArrayList<Lote>();
        ls.add(l);
        List<Andamento> and = Ordenada.rodar(ls, contexto(m));
        Juncao.Resultado r = Juncao.juntar(and);
        v.igual(Andamento.Fim.COMPLETO, and.get(0).fecho().fim, "lote vazio: completo");
        v.sim(!r.temFalha() && r.itensEmSerie == 0 && r.adiadosAplicados == 0, "lote vazio: nada a fazer");
    }

    /** 19. Lote que fica sujo no meio (passo 6: pedido delegado noutra regiao): adiados de 0..k-1 antes do resto em serie. */
    static void sujoNoMeio(Verifica v) {
        final Mundo m = new Mundo(6, 10);
        List<Tile> lt = base(3);
        int total = m.total();
        final List<Lote> ls = lotes(m, lt, set(1), null);
        final Lote l = ls.get(0);
        Tile t0 = (Tile) l.tile(0);
        t0.sons.clear();
        t0.sons.add("primeiro");
        Tile t1 = (Tile) l.tile(1);
        t1.sons.clear();
        t1.sons.add("segundo");
        Tile t2 = (Tile) l.tile(2);
        t2.sons.clear();
        t2.sons.add("terceiro");
        // o tile 1 suja o proprio lote ao ticar (simula a escrita delegada): o lote para no fim do item 1
        final Lote.Ticador base = ticador(m);
        Lote l2 = new Lote(1, new Lote.Ticador() {
            @Override
            public boolean vivo(Object tile, boolean serie) {
                return base.vivo(tile, serie);
            }

            @Override
            public void ticar(Object tile) throws Throwable {
                base.ticar(tile);
                if (((Tile) tile).sons.contains("segundo") && m.loteDaVez != null) {
                    m.loteDaVez.sujar();
                }
            }

            @Override
            public String descrever(Object tile) {
                return base.descrever(tile);
            }
        });
        for (int i = 0; i < l.tamanho(); i++) {
            l2.adicionar(l.tile(i), 1);
        }
        List<Lote> ls2 = new ArrayList<Lote>();
        ls2.add(l2);
        for (Tile t : lt) {
            if (t.regiao != 1) {
                t.tick(m);
            }
        }
        List<Andamento> and = Ordenada.rodar(ls2, contexto(m));
        v.igual(Andamento.Fim.SUJO, and.get(0).fecho().fim, "sujo no meio: fecho SUJO");
        v.igual(2, and.get(0).fecho().feitos, "sujo no meio: parou no fim do item corrente (2 feitos)");
        Juncao.juntar(and);
        conferir(v, "sujo no meio", m, total, lt);
        todosUmaVez(v, "sujo no meio", lt);
        int a = m.sons.indexOf("primeiro"), b = m.sons.indexOf("segundo"), c = m.sons.indexOf("terceiro");
        v.sim(a >= 0 && a < b && b < c, "sujo no meio: a ordem da regiao fica a do laco (" + a + ", " + b + ", " + c + ")");
    }

    /** 20. Ordem independente da thread: a ordem em que os lotes rodam nao muda o estado final (a juncao ordena). */
    static void ordemIndependente(Verifica v) {
        Random rnd = new Random(42);
        String ref = null;
        boolean iguais = true;
        for (int rep = 0; rep < 60; rep++) {
            Mundo m = new Mundo(10, 50);
            List<Tile> lt = new ArrayList<Tile>();
            int id = 0;
            for (int i = 0; i < 4; i++) {
                for (int r = 0; r < 5; r++) {
                    Tile t = new Tile(id++, r, 2 * r, 2 * r + 1, 1 + i);
                    t.eventos.add("r" + r + ":e" + i);
                    t.agendas.add("r" + r + ":p" + (i % 2));
                    t.sons.add("r" + r + ":s" + i);
                    lt.add(t);
                }
            }
            List<Lote> ls = lotes(m, lt, set(0, 1, 2, 3, 4), new long[] {50, 10, 40, 20, 30});
            Collections.shuffle(ls, rnd);
            // roda numa ordem sorteada (como threads pegariam), sem a Ordenada (que ordena)
            List<Andamento> and = new ArrayList<Andamento>();
            for (Lote l : ls) {
                Andamento a = new Andamento(l);
                m.loteDaVez = l;
                try {
                    for (int i = 0; i < l.tamanho(); i++) {
                        l.ticar(i);
                    }
                } catch (Throwable t) {
                    v.erro("ordem independente", t);
                }
                m.loteDaVez = null;
                a.fechar(Andamento.Fim.COMPLETO, l.tamanho(), null, 0, "t" + rnd.nextInt(4));
                and.add(a);
            }
            Collections.shuffle(and, rnd);
            Juncao.juntar(and);
            String estado = java.util.Arrays.toString(m.inv) + m.eventos + m.agendados + m.sons;
            if (ref == null) {
                ref = estado;
            } else if (!ref.equals(estado)) {
                iguais = false;
            }
        }
        v.sim(iguais, "ordem independente: 60 ordens sorteadas de execucao e de juncao dao o mesmo estado (inventarios, filas, sons)");
    }

    /** 21. Diferencial sorteado: tick em serie x lotes + juncao, sem erro: mesmo mundo final. */
    static void diferencial(Verifica v) {
        Random rnd = new Random(7);
        int casos = 400, ruins = 0;
        String exemplo = null;
        for (int c = 0; c < casos; c++) {
            long semente = rnd.nextLong();
            Mundo a = new Mundo(16, 30);
            Mundo b = new Mundo(16, 30);
            List<Tile> la = sortear(new Random(semente));
            List<Tile> lb = sortear(new Random(semente));
            Random re = new Random(semente ^ 0x5DEECE66DL);
            Set<Integer> eleg = new HashSet<Integer>();
            for (int r = 1; r < 8; r++) {
                if (re.nextBoolean()) {
                    eleg.add(r);
                }
            }
            serie(a, la);
            List<Lote> ls = lotes(b, lb, eleg, null);
            if (!ls.isEmpty() && re.nextInt(4) == 0) {
                ls.get(re.nextInt(ls.size())).sujar();
            }
            int total = b.total();
            Juncao.Resultado r = rodarOrdenada(b, lb, ls, eleg);
            boolean ok = java.util.Arrays.equals(a.inv, b.inv) && new HashSet<String>(a.eventos).equals(new HashSet<String>(b.eventos))
                    && a.eventos.size() == b.eventos.size() && a.agendados.equals(b.agendados) && sorted(a.sons).equals(sorted(b.sons))
                    && !r.temFalha() && b.total() == total;
            for (Tile t : lb) {
                ok &= t.ticks == 1;
            }
            if (!ok) {
                ruins++;
                if (exemplo == null) {
                    exemplo = "semente " + semente;
                }
            }
        }
        v.igual(0, ruins, "diferencial: " + casos + " mundos sorteados, serie = lotes + juncao (inventarios, eventos sem duplicata, vencedor do tick agendado, sons, cada tile uma vez)"
                + (exemplo == null ? "" : "; primeiro ruim: " + exemplo));
    }

    static List<String> sorted(List<String> l) {
        List<String> c = new ArrayList<String>(l);
        Collections.sort(c);
        return c;
    }

    /** Regioes 0..7 (inventarios 2r e 2r+1); 0 a 6 tiles por regiao, em ordem de laco embaralhada. */
    static List<Tile> sortear(Random rnd) {
        List<Tile> l = new ArrayList<Tile>();
        int id = 0;
        for (int r = 0; r < 8; r++) {
            int n = rnd.nextInt(7);
            for (int i = 0; i < n; i++) {
                boolean ida = rnd.nextBoolean();
                Tile t = new Tile(id++, r, ida ? 2 * r : 2 * r + 1, ida ? 2 * r + 1 : 2 * r, rnd.nextInt(8));
                for (int k = rnd.nextInt(3); k > 0; k--) {
                    t.eventos.add("r" + r + ":e" + rnd.nextInt(3));
                }
                for (int k = rnd.nextInt(3); k > 0; k--) {
                    t.agendas.add("r" + r + ":p" + rnd.nextInt(3));
                }
                for (int k = rnd.nextInt(2); k > 0; k--) {
                    t.sons.add("r" + r + ":s" + rnd.nextInt(5));
                }
                l.add(t);
            }
        }
        // a ordem do laco embaralha as regioes, mas mantem a ordem relativa dentro de cada uma (como a lista de tiles)
        List<Tile> out = new ArrayList<Tile>();
        List<List<Tile>> por = new ArrayList<List<Tile>>();
        for (int r = 0; r < 8; r++) {
            por.add(new ArrayList<Tile>());
        }
        for (Tile t : l) {
            por.get(t.regiao).add(t);
        }
        while (out.size() < l.size()) {
            int r = rnd.nextInt(8);
            if (!por.get(r).isEmpty()) {
                out.add(por.get(r).remove(0));
            }
        }
        return out;
    }

    /** 22. A politica da secao 9. */
    static void decidir(Verifica v) {
        Juncao.Resultado vazio = new Juncao.Resultado();
        Juncao.Resultado falha = new Juncao.Resultado();
        falha.falhas.add(new Juncao.Falha(null, 0, new RuntimeException(), "tile", "x"));
        v.igual(Juncao.Acao.SEGUE, Juncao.decidir(vazio, false, false, "parar", "parar"), "decidir: nada -> SEGUE");
        v.igual(Juncao.Acao.PARAR, Juncao.decidir(falha, false, false, "parar", "rebaixar"), "decidir: falha com ao_falhar parar -> PARAR");
        v.igual(Juncao.Acao.REBAIXAR, Juncao.decidir(falha, false, false, "rebaixar", "parar"), "decidir: falha com ao_falhar rebaixar -> REBAIXAR");
        v.igual(Juncao.Acao.PARAR, Juncao.decidir(vazio, false, true, "rebaixar", "parar"), "decidir: invariante com ao_violar parar -> PARAR");
        v.igual(Juncao.Acao.PARAR, Juncao.decidir(falha, true, false, "rebaixar", "parar"), "decidir: qualquer parar vence");
        v.igual(Juncao.Acao.PARAR, Juncao.decidir(falha, false, false, "lixo", "rebaixar"), "decidir: valor estranho para (o mais seguro)");
        v.igual(Juncao.Acao.REBAIXAR, Juncao.decidir(vazio, true, true, "parar", "rebaixar"), "decidir: violacao com ao_violar rebaixar -> REBAIXAR");
    }
}
