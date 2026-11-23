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
package hoshiestar.observador;

import hoshiestar.api.ThreadServidor;
import hoshiestar.teste.Verifica;
import io.github.crucible.util.HashedArrayList;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Random;
import java.util.Spliterator;
import java.util.stream.Collectors;

/**
 * Teste diferencial do prototipo da lista de tiles: a lista nossa contra o objeto original, em milhoes de operacoes
 * aleatorias, com resultado identico inclusive excecoes (ConcurrentModificationException, IndexOutOfBounds,
 * StackOverflowError do listIterator() do HashedArrayList...) e o estado escondido (o conjunto de hash do
 * HashedArrayList, conferido por contains).
 *
 * <p>Pares: {@link ListaObservadaArray} x {@code java.util.ArrayList} (o objeto que o World poe no campo depois da limpeza
 * de tiles) e {@link ListaObservadaHashed} x {@code HashedArrayList} (o objeto que o World cria). Modos: sem observacao;
 * observando, chamada "de fora" (iteradores embrulhados); observando, dentro de uma marca do World; observando, de outra
 * thread; observando numa lista aposentada; observando numa copia (clone). Depois: as contagens, as marcas do World, a
 * troca do objeto (embrulhar), o criterio e o relatorio.
 *
 * <p>Uso: {@code TesteListaObservada [operacoes por par e modo, padrao 250000] [semente]}.
 */
public final class TesteListaObservada {

    private static Verifica v;
    private static final Integer[] POOL = new Integer[13];

    static {
        for (int i = 0; i < 12; i++) {
            POOL[i] = Integer.valueOf(1000 + i); // fora do cache de Integer: igualdade por equals, nao por identidade
        }
        POOL[12] = null;
    }

    private static final int MODO_SEM = 0;
    private static final int MODO_FORA = 1;
    private static final int MODO_DENTRO = 2;
    private static final int MODO_OUTRA_THREAD = 3;
    private static final int MODO_APOSENTADA = 4;
    private static final int MODO_COPIA = 5;
    private static final String[] NOME_MODO = {"sem observacao", "observando, de fora", "observando, dentro do World",
        "observando, outra thread", "observando, lista aposentada", "observando, copia (clone)"};

    public static void main(String[] args) throws Exception {
        int ops = args.length > 0 ? Integer.parseInt(args[0]) : 250000;
        long semente = args.length > 1 ? Long.parseLong(args[1]) : 20261123L;
        v = new Verifica("TesteListaObservada");
        Observador.reiniciarParaTeste();
        ThreadServidor.definir(Thread.currentThread());
        Observador.configurar(true, 256, 5.0);
        Observador.configurarLista(true);
        Observador.definirDonos(new Pilha.Donos() {
            @Override
            public String dono(String classe) {
                return classe.startsWith("teste.") ? "mod-de-teste.jar" : null;
            }
        });

        v.igual(ListaObservada.NOPS, ListaObservada.NOME_OP.length, "um nome por operacao");
        v.igual(ListaObservada.NOPS, ListaObservada.CATEGORIA.length, "uma categoria por operacao");
        v.sim(ListaObservada.modifica(ListaObservada.ADD) && ListaObservada.modifica(ListaObservada.IT_REMOVE)
                && ListaObservada.modifica(ListaObservada.REMOVE_RANGE) && !ListaObservada.modifica(ListaObservada.SIZE)
                && !ListaObservada.modifica(ListaObservada.SUB_LIST) && !ListaObservada.modifica(ListaObservada.CLONE),
                "categorias: add, iterator.remove e removeRange modificam; size, subList e clone nao");

        long total = 0;
        long excecoes = 0;
        long soe = 0;
        for (int par = 0; par < 2; par++) {
            for (int modo = 0; modo < 6; modo++) {
                long[] r = diferencial(par, modo, ops, semente + par * 31 + modo);
                total += r[0];
                excecoes += r[1];
                soe += r[2];
            }
        }
        v.sim(total >= 12L * ops, "diferencial: " + total + " operacoes, " + excecoes + " com excecao (" + soe
                + " StackOverflowError), resultado e estado identicos ao original em todas");

        contagens();
        marcasDoMundo();
        embrulhar();
        criterio();
        relatorio();
        Observador.reiniciarParaTeste();
        v.fim();
    }

    // =====================================================================================================
    // Diferencial
    // =====================================================================================================

    private static long[] diferencial(final int par, final int modo, final int ops, final long semente) throws Exception {
        final long[] r = new long[3];
        final String[] falha = new String[1];
        Nucleo n = iniciarObservacao(modo != MODO_SEM);
        Runnable corpo = new Runnable() {
            @Override
            public void run() {
                try {
                    rodar(par, modo, ops, semente, r, falha);
                } catch (Throwable t) {
                    falha[0] = "erro do teste: " + t;
                }
            }
        };
        if (modo == MODO_OUTRA_THREAD) {
            Thread t = new Thread(corpo, "outra thread");
            t.setDaemon(true);
            t.start();
            t.join();
        } else {
            corpo.run();
        }
        String nome = (par == 0 ? "ListaObservadaArray x ArrayList" : "ListaObservadaHashed x HashedArrayList") + ", "
                + NOME_MODO[modo];
        v.sim(falha[0] == null, nome + ": " + r[0] + " operacoes (" + r[1] + " excecoes, " + r[2] + " StackOverflowError) identicas"
                + (falha[0] == null ? "" : " -- DIFERENCA: " + falha[0]));
        v.igual(0, ListaObservada.prof, nome + ": a profundidade volta a 0 depois de tudo (inclusive excecoes)");
        if (n != null) {
            ContagemLista c = n.lista;
            long fora = c.totalFora();
            long dentro = c.totalDentro();
            long velha = c.totalVelha();
            long outra = c.outraTotal.get();
            if (modo == MODO_FORA) {
                v.sim(fora > 0 && dentro == 0 && velha == 0 && outra == 0 && c.amostrasFeitas > 0,
                        nome + ": tudo contado como de fora (" + fora + "), com amostras (" + c.amostrasFeitas + ")");
                v.sim(c.foraPorOp(ListaObservada.IT_REMOVE) + c.foraPorOp(ListaObservada.LIT_REMOVE) > 0
                        && c.foraPorOp(ListaObservada.REMOVE_RANGE) > 0,
                        nome + ": iterator.remove, listIterator.remove e removeRange (subList.clear) contados");
            } else if (modo == MODO_DENTRO) {
                v.sim(fora == 0 && dentro > 0 && outra == 0, nome + ": tudo contado como do World (" + dentro + "), nada de fora");
            } else if (modo == MODO_OUTRA_THREAD) {
                v.sim(fora == 0 && dentro == 0 && outra > 0, nome + ": tudo contado como outra thread (" + outra + ")");
            } else if (modo == MODO_APOSENTADA) {
                v.sim(velha > 0 && velha <= fora, nome + ": chamadas na lista aposentada contadas (" + velha + ")");
            } else if (modo == MODO_COPIA) {
                v.sim(fora == 0 && dentro == 0 && velha == 0 && outra == 0, nome + ": copia (clone) nao conta nada");
            }
        }
        pararObservacao();
        return r;
    }

    private static Nucleo iniciarObservacao(boolean ativo) {
        Observador.ATIVO = false;
        Observador.N = null;
        ListaObservada.inicioTick();
        if (!ativo) {
            return null;
        }
        Nucleo n = new Nucleo(new Pilha.Donos() {
            @Override
            public String dono(String classe) {
                return classe.startsWith("teste.") ? "mod-de-teste.jar" : null;
            }
        }, 64, 5.0, null);
        Observador.N = n;
        Observador.ATIVO = true;
        ListaObservada.inicioTick();
        return n;
    }

    private static void pararObservacao() {
        Observador.ATIVO = false;
        Observador.N = null;
        ListaObservada.inicioTick();
    }

    /** Um lado do teste: a lista, os iteradores vivos e as visoes (subList) dela. */
    private static final class Lado {
        List lista;
        final Object[] its = new Object[4];
        final List[] subs = new List[2];
    }

    private static void rodar(int par, int modo, int ops, long semente, long[] r, String[] falha) {
        Random rnd = new Random(semente);
        Lado a = new Lado();
        Lado b = new Lado();
        novasListas(par, modo, a, b);
        if (modo == MODO_DENTRO) {
            ListaObservada.mundo();
        }
        for (int i = 0; i < ops && falha[0] == null; i++) {
            if (i % 20000 == 0) {
                ListaObservada.inicioTick(); // como o servidor: o teto de amostras e por tick
                if (modo == MODO_DENTRO) {
                    ListaObservada.mundo();
                }
            }
            if (rnd.nextInt(5000) == 0) {
                novasListas(par, modo, a, b); // de vez em quando, comeca de novo (inclusive estado escondido)
            }
            int op = escolher(rnd, a.lista.size(), par);
            long semOp = rnd.nextLong();
            String ra = aplicar(op, a, new Random(semOp), par);
            String rb = aplicar(op, b, new Random(semOp), par);
            r[0]++;
            if (ra.startsWith("!")) {
                r[1]++;
                if (ra.contains("StackOverflowError")) {
                    r[2]++;
                }
            }
            if (!ra.equals(rb)) {
                falha[0] = "op " + i + " (" + op + "): nossa=" + ra + " original=" + rb;
                break;
            }
            String ea = estado(a.lista, i % 16 == 0);
            String eb = estado(b.lista, i % 16 == 0);
            if (!ea.equals(eb)) {
                falha[0] = "estado depois da op " + i + " (" + op + "): nossa=" + ea + " original=" + eb;
            }
            if (modo != MODO_OUTRA_THREAD && ListaObservada.prof != 0) {
                falha[0] = "profundidade " + ListaObservada.prof + " depois da op " + i + " (" + op + ")";
            }
        }
        if (modo == MODO_DENTRO) {
            ListaObservada.fimMundo();
        }
    }

    private static void novasListas(int par, int modo, Lado a, Lado b) {
        int prevAtivo = Observador.ATIVO ? 1 : 0;
        if (par == 0) {
            a.lista = new ListaObservadaArray();
            b.lista = new ArrayList();
        } else {
            a.lista = new ListaObservadaHashed();
            b.lista = new HashedArrayList();
        }
        if (modo == MODO_APOSENTADA) {
            ((ListaObservada.Observada) a.lista).estado(ListaObservada.APOSENTADA);
        } else if (modo == MODO_COPIA) {
            ((ListaObservada.Observada) a.lista).estado(ListaObservada.COPIA);
        }
        Arrays.fill(a.its, null);
        Arrays.fill(b.its, null);
        Arrays.fill(a.subs, null);
        Arrays.fill(b.subs, null);
        Observador.ATIVO = prevAtivo == 1;
    }

    private static final int NOPS_TESTE = 58;

    /** Pesos: as operacoes caras (StackOverflowError) sao raras; listas grandes tendem a encolher. */
    private static int escolher(Random rnd, int tamanho, int par) {
        while (true) {
            int op = rnd.nextInt(NOPS_TESTE);
            if (par == 1 && (op == 30 || op == 31) && rnd.nextInt(400) != 0) {
                continue; // listIterator() e equals do HashedArrayList estouram a pilha: caro, poucas vezes
            }
            if (tamanho > 40 && (op == 7 || op == 8 || op == 12 || op == 13) && rnd.nextInt(4) != 0) {
                continue;
            }
            return op;
        }
    }

    private static Integer el(Random r) {
        return POOL[r.nextInt(POOL.length)];
    }

    private static int idx(Random r, int tam) {
        return r.nextInt(tam + 5) - 2;
    }

    private static List colecao(Random r) {
        if (r.nextInt(30) == 0) {
            return null;
        }
        int n = r.nextInt(5);
        List c = new ArrayList();
        for (int i = 0; i < n; i++) {
            c.add(el(r));
        }
        return c;
    }

    private static final Comparator ORDEM = Comparator.nullsFirst(Comparator.naturalOrder());

    /** Aplica a operacao {@code op} (parametros tirados de {@code r}) num lado e descreve o resultado. */
    private static String aplicar(int op, Lado s, Random r, int par) {
        final List l = s.lista;
        try {
            switch (op) {
                case 0: return "" + l.size();
                case 1: return "" + l.isEmpty();
                case 2: return "" + l.get(idx(r, l.size()));
                case 3: return "" + l.contains(el(r));
                case 4: return "" + l.containsAll(colecao(r));
                case 5: return "" + l.indexOf(el(r));
                case 6: return "" + l.lastIndexOf(el(r));
                case 7: return "" + l.add(el(r));
                case 8: l.add(idx(r, l.size()), el(r)); return "ok";
                case 9: return "" + l.remove(idx(r, l.size()));
                case 10: return "" + l.remove((Object) el(r));
                case 11: return "" + l.set(idx(r, l.size()), el(r));
                case 12: return "" + l.addAll(colecao(r));
                case 13: return "" + l.addAll(idx(r, l.size()), colecao(r));
                case 14: return "" + l.removeAll(colecao(r));
                case 15: return "" + l.retainAll(colecao(r));
                case 16: if (r.nextInt(8) == 0) { l.clear(); } return "ok";
                case 17: return Arrays.toString(l.toArray());
                case 18: {
                    Object[] dado = new Object[Math.max(0, r.nextInt(l.size() + 3))];
                    Arrays.fill(dado, "x");
                    Object[] volta = l.toArray(dado);
                    return (volta == dado) + Arrays.toString(volta);
                }
                case 19: { // novo iterador
                    s.its[r.nextInt(s.its.length)] = l.iterator();
                    return "iterador";
                }
                case 20: { // novo iterador de lista
                    int i = idx(r, l.size());
                    int vaga = r.nextInt(s.its.length);
                    s.its[vaga] = null;
                    s.its[vaga] = l.listIterator(i);
                    return "iterador de lista";
                }
                case 21: case 22: case 23: case 24: case 25: case 26: case 27:
                    return usarIterador(op, s, r);
                case 28: { // nova subList
                    int de = idx(r, l.size());
                    int ate = idx(r, l.size());
                    int vaga = r.nextInt(s.subs.length);
                    s.subs[vaga] = null;
                    s.subs[vaga] = l.subList(de, ate);
                    return "sublista";
                }
                case 29: return usarSubLista(s, r);
                case 30: return "" + l.listIterator().hasNext(); // HashedArrayList: StackOverflowError
                case 31: { // equals: com uma copia, consigo, com nao-lista
                    int q = r.nextInt(3);
                    Object o = q == 0 ? new ArrayList(Arrays.asList(l.toArray())) : q == 1 ? l : "nao sou lista";
                    return "" + l.equals(o);
                }
                case 32: return "" + l.hashCode();
                case 33: return l.toString();
                case 34: {
                    final StringBuilder sb = new StringBuilder();
                    final boolean mexer = r.nextInt(6) == 0;
                    final Integer e = el(r);
                    l.forEach(x -> {
                        sb.append(x).append(',');
                        if (mexer && sb.length() < 12) {
                            l.add(e); // mexe na lista no meio do forEach: ConcurrentModificationException no fim
                        }
                    });
                    return sb.toString();
                }
                case 35: {
                    final Integer alvo = el(r);
                    final boolean mexer = r.nextInt(8) == 0;
                    return "" + l.removeIf(x -> {
                        if (mexer) {
                            l.size();
                        }
                        return alvo == null ? x == null : alvo.equals(x);
                    });
                }
                case 36: {
                    final int desloca = r.nextInt(3);
                    l.replaceAll(x -> x == null ? POOL[desloca] : desloca == 2 ? null : x);
                    return "ok";
                }
                case 37: l.sort(r.nextBoolean() ? ORDEM : ORDEM.reversed()); return "ok";
                case 38: {
                    Spliterator sp = l.spliterator();
                    final StringBuilder sb = new StringBuilder();
                    sp.tryAdvance(x -> sb.append(x).append(';'));
                    if (r.nextInt(4) == 0 && l.size() < 40) {
                        l.add(el(r)); // modificacao com o spliterator vivo
                    }
                    sp.forEachRemaining(x -> sb.append(x).append(','));
                    return sb.toString() + sp.characteristics();
                }
                case 39: return "" + l.stream().map(String::valueOf).collect(Collectors.joining(","));
                case 40: return "" + l.parallelStream().filter(x -> x != null).count();
                case 41: {
                    if (l instanceof ArrayList) {
                        ((ArrayList) l).trimToSize();
                    }
                    return "ok";
                }
                case 42: {
                    if (l instanceof ArrayList) {
                        ((ArrayList) l).ensureCapacity(r.nextInt(100) - 10);
                    }
                    return "ok";
                }
                case 43: { // clone: conteudo igual; nao mexe na copia (no HashedArrayList ela compartilha o conjunto de hash)
                    Object c = ((ArrayList) l).clone();
                    return (c instanceof HashedArrayList ? "clone-hashed" : "clone-arraylist") + c;
                }
                case 44: { // laco do World (indice, como World.updateEntities)
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < l.size(); i++) {
                        Object x = l.get(i);
                        if (x != null && ((Integer) x) % 5 == 0 && r.nextInt(3) == 0) {
                            l.remove(i--);
                            sb.append('-');
                        }
                        sb.append(x);
                    }
                    return sb.toString();
                }
                case 45: { // for-each com remocao no meio: ConcurrentModificationException no proximo next
                    StringBuilder sb = new StringBuilder();
                    boolean tirar = r.nextInt(5) == 0;
                    for (Object x : l) {
                        sb.append(x).append(',');
                        if (tirar && sb.length() > 6) {
                            l.remove(x);
                            tirar = false;
                        }
                    }
                    return sb.toString();
                }
                case 46: { // iterator com remocao pelo iterador (como mods que limpam a lista)
                    StringBuilder sb = new StringBuilder();
                    Integer alvo = el(r);
                    Iterator it = l.iterator();
                    while (it.hasNext()) {
                        Object x = it.next();
                        if (alvo == null ? x == null : alvo.equals(x)) {
                            it.remove();
                            sb.append('-');
                        }
                        sb.append(x);
                    }
                    return sb.toString();
                }
                case 47: { // ListIterator para tras com set
                    StringBuilder sb = new StringBuilder();
                    ListIterator it = l.listIterator(l.size());
                    while (it.hasPrevious()) {
                        Object x = it.previous();
                        if (r.nextInt(4) == 0) {
                            it.set(el(r));
                        }
                        sb.append(x).append(it.previousIndex());
                    }
                    return sb.toString();
                }
                case 48: { // Collections/Arrays por cima (um mod que copia a lista)
                    return new ArrayList(l).toString() + Arrays.asList(l.toArray(new Object[0]));
                }
                case 49: { // forEachRemaining num iterador vivo
                    int vaga = r.nextInt(s.its.length);
                    Object it = s.its[vaga];
                    if (it == null) {
                        return "sem iterador";
                    }
                    final StringBuilder sb = new StringBuilder();
                    ((Iterator) it).forEachRemaining(x -> sb.append(x).append(','));
                    return sb.toString();
                }
                case 50: { // remove do iterador da subList (passa pelo removeRange/remove da lista mae)
                    List sub = s.subs[r.nextInt(s.subs.length)];
                    if (sub == null) {
                        return "sem sublista";
                    }
                    Iterator it = sub.iterator();
                    StringBuilder sb = new StringBuilder();
                    while (it.hasNext()) {
                        Object x = it.next();
                        if (r.nextInt(3) == 0) {
                            it.remove();
                            sb.append('-');
                        }
                        sb.append(x);
                    }
                    return sb.toString();
                }
                case 51: { // subList(...).clear(): removeRange
                    int de = Math.max(0, idx(r, l.size()));
                    int ate = Math.min(l.size(), de + r.nextInt(4));
                    l.subList(de, ate).clear();
                    return "ok";
                }
                case 52: return "" + l.containsAll(Arrays.asList(l.toArray()));
                case 53: { // add/remove do mesmo objeto (o padrao "tile se tira e se poe")
                    Integer e = el(r);
                    boolean a1 = l.remove((Object) e);
                    boolean a2 = l.add(e);
                    return a1 + "/" + a2;
                }
                case 54: { // subList de subList
                    List sub = s.subs[r.nextInt(s.subs.length)];
                    if (sub == null) {
                        return "sem sublista";
                    }
                    List sub2 = sub.subList(idx(r, sub.size()), idx(r, sub.size()));
                    if (r.nextBoolean()) {
                        sub2.add(el(r));
                    }
                    return sub2.toString();
                }
                case 55: return "" + l.stream().sorted(ORDEM).collect(Collectors.toList());
                case 56: { // listIterator(int).add e remove alternados
                    ListIterator it = l.listIterator(Math.max(0, Math.min(l.size(), idx(r, l.size()))));
                    StringBuilder sb = new StringBuilder();
                    for (int k = 0; k < 3 && it.hasNext(); k++) {
                        sb.append(it.next());
                        if (r.nextBoolean()) {
                            it.add(el(r));
                        } else {
                            it.remove();
                        }
                    }
                    return sb.toString();
                }
                case 57: { // modificar a lista com um iterador vivo e usa-lo depois
                    int vaga = r.nextInt(s.its.length);
                    if (s.its[vaga] == null) {
                        s.its[vaga] = l.iterator();
                    }
                    l.add(r.nextInt(Math.max(1, l.size())) == 0 ? 0 : l.size(), el(r));
                    return "" + ((Iterator) s.its[vaga]).hasNext() + ((Iterator) s.its[vaga]).next();
                }
                default: return "?";
            }
        } catch (Throwable t) {
            return "!" + t.getClass().getName() + ": " + t.getMessage();
        }
    }

    private static String usarIterador(int op, Lado s, Random r) {
        int vaga = r.nextInt(s.its.length);
        Object o = s.its[vaga];
        if (o == null) {
            return "sem iterador";
        }
        Iterator it = (Iterator) o;
        boolean lista = o instanceof ListIterator;
        switch (op) {
            case 21: return "" + it.hasNext();
            case 22: return "" + it.next();
            case 23: it.remove(); return "ok";
            case 24: if (!lista) { return "nao e de lista"; } return "" + ((ListIterator) it).previous();
            case 25: if (!lista) { return "nao e de lista"; } ((ListIterator) it).set(el(r)); return "ok";
            case 26: if (!lista) { return "nao e de lista"; } ((ListIterator) it).add(el(r)); return "ok";
            default: if (!lista) { return "" + it.hasNext(); }
                return ((ListIterator) it).hasPrevious() + "/" + ((ListIterator) it).nextIndex() + "/"
                        + ((ListIterator) it).previousIndex();
        }
    }

    private static String usarSubLista(Lado s, Random r) {
        List sub = s.subs[r.nextInt(s.subs.length)];
        if (sub == null) {
            return "sem sublista";
        }
        switch (r.nextInt(8)) {
            case 0: return "" + sub.size();
            case 1: return "" + sub.get(idx(r, sub.size()));
            case 2: return "" + sub.set(idx(r, sub.size()), el(r));
            case 3: sub.add(idx(r, sub.size()), el(r)); return "ok";
            case 4: return "" + sub.remove(idx(r, sub.size()));
            case 5: sub.clear(); return "ok";
            case 6: return "" + sub.contains(el(r));
            default: return sub.toString();
        }
    }

    /** O estado de uma lista: tamanho, elementos na ordem e (de vez em quando) contains de cada valor do pool. */
    private static String estado(List l, boolean completo) {
        int prev = Observador.ATIVO ? 1 : 0;
        Observador.ATIVO = false; // a leitura do estado nao entra nas contagens
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(l.size()).append(Arrays.toString(l.toArray()));
            if (completo) {
                for (Integer e : POOL) {
                    sb.append(l.contains(e) ? '1' : '0');
                }
            }
            return sb.toString();
        } catch (Throwable t) {
            return "!" + t;
        } finally {
            Observador.ATIVO = prev == 1;
        }
    }

    // =====================================================================================================
    // Contagens, marcas, troca do objeto, criterio, relatorio
    // =====================================================================================================

    private static void contagens() throws Exception {
        Nucleo n = iniciarObservacao(true);
        ContagemLista c = n.lista;
        ListaObservadaHashed l = new ListaObservadaHashed();
        l.add(POOL[0]);
        l.add(POOL[1]);
        teste.moda.Chamador.mexerNaLista(l); // "mod" (pacote teste.*, dono mod-de-teste.jar): size, iterator, remove, add
        v.igual(1L, c.foraPorOp(ListaObservada.REMOVE_O), "contagem: remove(Object) do mod contado uma vez");
        v.igual(1L, c.foraPorOp(ListaObservada.ADD) - 2, "contagem: add do mod contado (2 do teste + 1 do mod)");
        v.igual(0L, c.foraPorOp(ListaObservada.LIST_ITERATOR), "contagem: nenhuma chamada interna contada em dobro");
        boolean achou = false;
        for (int i = 0; i < c.amostras.capacidade(); i++) {
            if (c.amostras.ocupado(i)) {
                long k = c.amostras.chave(i);
                String ch = c.chamadores.nome((int) ((k >> 34) & 0xFFF));
                String mp = n.mods.nome((int) ((k >> 22) & 0xFFF));
                if ((k & 63) == ListaObservada.REMOVE_O && "teste.moda.Chamador.mexerNaLista".equals(ch) && "mod-de-teste.jar".equals(mp)) {
                    achou = true;
                }
            }
        }
        v.sim(achou, "amostra: a modificacao do mod tem chamador 'teste.moda.Chamador.mexerNaLista' e mod 'mod-de-teste.jar'");
        // contexto de tile: a remocao feita "pelo tile" cai no contexto tile
        n.entrar(Nucleo.C_TILE, "mundo", 0, 0, 0, teste.moda.Chamador.class);
        l.remove(POOL[0]);
        n.sair(Nucleo.C_TILE);
        v.igual(1L, c.fora[ListaObservada.REMOVE_O * 16 + Nucleo.C_TILE], "contagem: remove feito em contexto de tile cai em 'tile'");
        // amostras de leitura: 1 em amostraPilha (64), no maximo 20 por tick
        ListaObservada.inicioTick();
        long antes = c.amostrasFeitas;
        for (int i = 0; i < 64 * 10; i++) {
            l.size();
        }
        v.igual(10L, c.amostrasFeitas - antes, "amostras de leitura: 1 em 64 (640 leituras = 10 amostras)");
        for (int i = 0; i < 100; i++) {
            l.add(POOL[2]);
        }
        v.igual(20L, c.amostrasFeitas - antes, "amostras: no maximo 20 por tick (modificacoes sempre amostram ate o teto)");
        v.sim(c.custoTick() > 0, "custo da lista entra na conta da valvula (" + c.custoTick() + " ns estimados neste tick)");
        // copia nao conta
        long f0 = c.totalFora();
        Object copia = l.clone();
        v.igual(f0 + 1, c.totalFora(), "clone conta uma vez na lista original");
        ((List) copia).add(POOL[3]);
        ((List) copia).size();
        v.igual(f0 + 1, c.totalFora(), "chamadas na copia nao contam");
        v.igual(ListaObservada.COPIA, ((ListaObservada.Observada) copia).estado(), "a copia fica marcada como copia");
        // desligado: nada conta e o iterador nao e embrulhado
        Observador.ATIVO = false;
        long f1 = c.totalFora();
        Iterator it = l.iterator();
        l.size();
        v.igual(f1, c.totalFora(), "sem observacao: nada conta");
        v.sim(!(it instanceof ListaObservada.IteradorObservado), "sem observacao: o iterador e o original (nao embrulhado)");
        Observador.ATIVO = true;
        Iterator it2 = l.iterator();
        v.sim(it2 instanceof ListaObservada.IteradorObservado, "observando, de fora: o iterador e embrulhado (so conta as modificacoes)");
        ListaObservada.mundo();
        Iterator it3 = l.iterator();
        ListaObservada.fimMundo();
        v.sim(!(it3 instanceof ListaObservada.IteradorObservado), "observando, dentro do World: o iterador e o original");
        pararObservacao();
    }

    private static void marcasDoMundo() {
        Nucleo n = iniciarObservacao(true);
        ContagemLista c = n.lista;
        ListaObservadaHashed l = new ListaObservadaHashed();
        l.add(POOL[0]);
        long f0 = c.totalFora();
        // o laco de tiles: o World mexe (dentro), o update do tile e de fora
        ListaObservada.mundo();
        l.size();
        l.get(0);
        ListaObservada.fora(); // GanchosObservador.tile
        l.remove(POOL[0]); // o mod se tira da lista no update
        ListaObservada.fora(); // sobra de excecao: o proximo tile substitui, nao empilha
        l.add(POOL[0]);
        ListaObservada.fimFora(); // GanchosObservador.fimTile
        l.size();
        ListaObservada.fimFora(); // a chamada depois do laco (nao acha FORA: nao faz nada)
        l.size();
        ListaObservada.fimMundo();
        v.igual(f0 + 2, c.totalFora(), "marcas: so o remove e o add do update do tile sao de fora");
        v.igual(3L, c.dentro[ListaObservada.SIZE], "marcas: os 3 size do World sao do World");
        v.igual(0, ListaObservada.topoParaTeste(), "marcas: a pilha de marcas volta a zero");
        // outra thread nao mexe nas marcas da thread do servidor
        ListaObservada.mundo();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                ListaObservada.fimMundo();
                ListaObservada.fora();
            }
        });
        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        v.igual(1, ListaObservada.topoParaTeste(), "marcas: outra thread nao mexe na pilha de marcas da thread do servidor");
        ListaObservada.inicioTick();
        v.igual(0, ListaObservada.topoParaTeste(), "marcas: o inicio do tick zera a pilha (sobra de excecao)");
        for (int i = 0; i < ListaObservada.MAX_MARCAS + 10; i++) {
            ListaObservada.mundo();
        }
        v.igual(10L, c.estourosMarca, "marcas: passar de " + ListaObservada.MAX_MARCAS + " niveis conta estouro, sem erro");
        for (int i = 0; i < ListaObservada.MAX_MARCAS + 10; i++) {
            ListaObservada.fimMundo();
        }
        v.igual(0, ListaObservada.topoParaTeste(), "marcas: desempilhar depois do estouro volta a zero");
        // lista desligada: as marcas nao fazem nada
        Observador.configurarLista(false);
        ListaObservada.mundo();
        v.igual(0, ListaObservada.topoParaTeste(), "lista desligada: a marca do World nao faz nada");
        Observador.configurarLista(true);
        pararObservacao();
    }

    private static void embrulhar() {
        Nucleo n = iniciarObservacao(true);
        ContagemLista c = n.lista;
        ListaObservadaHashed velha = new ListaObservadaHashed();
        for (int i = 0; i < 10; i++) {
            velha.add(POOL[i]);
        }
        long foraAntes = c.totalFora();
        // como o World: copia os nao descarregados para um ArrayList novo e troca o campo
        ListaObservada.mundo();
        List temp = new ArrayList(velha.size());
        for (Object o : velha) {
            if (((Integer) o) % 3 != 0) {
                temp.add(o);
            }
        }
        List nova = ListaObservada.embrulhar(velha, temp);
        ListaObservada.fimMundo();
        v.igual(foraAntes, c.totalFora(), "embrulhar: a copia do World nao conta como uso de fora");
        v.sim(nova instanceof ListaObservadaArray, "embrulhar: o ArrayList novo vira ListaObservadaArray");
        v.igual(temp.toString(), nova.toString(), "embrulhar: mesmos elementos na mesma ordem");
        v.igual(ListaObservada.APOSENTADA, velha.estado(), "embrulhar: a lista velha fica aposentada");
        v.igual(1L, c.trocasPeloMundo, "embrulhar: troca pelo World contada");
        velha.size();
        v.igual(1L, c.velha[ListaObservada.SIZE], "lista aposentada: quem guardou a referencia antiga e contado");
        v.sim(ListaObservada.embrulhar(null, null) == null, "embrulhar(null) devolve null");
        v.sim(ListaObservada.embrulhar(velha, nova) == nova, "embrulhar uma lista que ja e nossa devolve a mesma");
        Observador.ATIVO = false;
        List semObs = ListaObservada.embrulhar(new ListaObservadaHashed(), new ArrayList(Arrays.asList(POOL[1])));
        v.sim(semObs instanceof ListaObservadaArray && c.trocasPeloMundo == 1,
                "embrulhar sem observacao: troca o objeto (a lista fica nossa) mas nao conta");
        pararObservacao();
    }

    private static void criterio() {
        Nucleo n = iniciarObservacao(true);
        ContagemLista c = n.lista;
        v.sim(c.indicacao(n).startsWith("criterio 2"), "criterio: sem acesso de fora = criterio 2 (" + c.indicacao(n) + ")");
        c.fora[ListaObservada.SIZE * 16 + Nucleo.C_GLOBAL_PRE] = 1000;
        c.fora[ListaObservada.GET * 16 + Nucleo.C_TILE] = 5;
        v.sim(c.indicacao(n).startsWith("criterio 2"), "criterio: 99,5% serial = criterio 2");
        c.fora[ListaObservada.GET * 16 + Nucleo.C_TILE] = 50;
        c.amostras.somar(ContagemLista.chave(ListaObservada.GET, Nucleo.C_TILE, 0, n.mods.id("a.jar"), 0));
        c.amostras.somar(ContagemLista.chave(ListaObservada.SIZE, Nucleo.C_ENTIDADE, n.mods.id("b.jar"), 0, 0));
        v.sim(c.indicacao(n).startsWith("criterio 3"), "criterio: leitura em contexto de regiao de 2 mods = criterio 3 ("
                + c.indicacao(n) + ")");
        for (int i = 0; i < 6; i++) {
            c.amostras.somar(ContagemLista.chave(ListaObservada.GET, Nucleo.C_TILE, 0, n.mods.id("m" + i + ".jar"), 0));
        }
        v.sim(c.indicacao(n).startsWith("inconclusivo"), "criterio: leitura de mais de 5 mods = inconclusivo");
        c.fora[ListaObservada.REMOVE_O * 16 + Nucleo.C_TILE] = 1;
        c.amostras.somar(ContagemLista.chave(ListaObservada.REMOVE_O, Nucleo.C_TILE, 0, n.mods.id("x.jar"), 0));
        v.sim(c.indicacao(n).startsWith("criterio 1") && c.indicacao(n).contains("x.jar"),
                "criterio: modificacao de fora em contexto de tile = criterio 1, com o mod (" + c.indicacao(n) + ")");
        c.campoTrocado("com.exemplo.OutraLista");
        v.sim(c.indicacao(n).contains("trocado"), "criterio: campo trocado fora do World = criterio 1");
        v.igual("{com.exemplo.OutraLista=1}", c.classesNoCampo.toString(), "campo trocado: classe anotada");
        pararObservacao();
    }

    private static void relatorio() {
        Nucleo n = iniciarObservacao(true);
        ListaObservadaHashed l = new ListaObservadaHashed();
        teste.moda.Chamador.mexerNaLista(l);
        Relatorio.Meta m = new Relatorio.Meta();
        m.versao = "teste";
        m.inicio = "agora";
        m.motivoFim = "teste";
        String j = Relatorio.json(n, m);
        Object raiz = JsonLeitor.ler(j);
        v.igual(Boolean.TRUE, JsonLeitor.caminho(raiz, "lista_tiles", "ligada"), "relatorio json: lista_tiles.ligada = true");
        Object ind = JsonLeitor.caminho(raiz, "lista_tiles", "indicacao");
        v.sim(ind instanceof String && ((String) ind).startsWith("inconclusivo") && ((String) ind).contains("sem contexto conhecido"),
                "relatorio json: so chamadas sem contexto = inconclusivo, mostrando a parte sem contexto (" + ind + ")");
        v.sim(JsonLeitor.caminho(raiz, "lista_tiles", "de_fora_sem_contexto_conhecido") != null,
                "relatorio json: traz de_fora_sem_contexto_conhecido");
        v.sim(j.contains("teste.moda.Chamador.mexerNaLista") && j.contains("mod-de-teste.jar"),
                "relatorio json: amostras com chamador e mod");
        String t = Relatorio.texto(n, m);
        v.sim(t.contains("Prototipo da lista de tiles (observador.lista): ligado") && t.contains("INDICACAO"),
                "relatorio texto: secao do prototipo com a indicacao");
        Observador.configurarLista(false);
        Object raiz2 = JsonLeitor.ler(Relatorio.json(n, m));
        v.igual(Boolean.FALSE, JsonLeitor.caminho(raiz2, "lista_tiles", "ligada"), "relatorio com a lista desligada: ligada = false");
        v.sim(Relatorio.texto(n, m).contains("observador.lista): desligado"), "relatorio texto com a lista desligada");
        Observador.configurarLista(true);
        pararObservacao();
    }
}
