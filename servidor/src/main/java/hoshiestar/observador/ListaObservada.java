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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.function.Consumer;

/**
 * Prototipo da "lista por regiao" (plano da Etapa 2, secao 4), so a parte que OBSERVA: com {@code observador.lista: true}
 * cada mundo cria {@code loadedTileEntityList} como {@link ListaObservadaHashed} (subclasse do {@code HashedArrayList} do
 * Crucible, o objeto original) e, quando o proprio World troca o objeto por um {@code ArrayList} comum ao descarregar tiles,
 * como {@link ListaObservadaArray}. As duas devolvem EXATAMENTE o que o objeto original devolveria (cada metodo chama o
 * mesmo metodo da superclasse) e, so enquanto ha observacao ({@link Observador#ATIVO}), contam quem as usa.
 *
 * <p><b>"Dentro" e "de fora".</b> O World marca os trechos em que ELE mexe na lista ({@link #mundo()}/{@link #fimMundo()}:
 * a limpeza dos tiles descarregados, o laco de tiles, os tiles pendentes, {@code func_147448_a}, {@code setTileEntity},
 * {@code addTileEntity}); dentro desses trechos, o codigo do mod chamado pelo World ({@code updateEntity} do tile) volta a
 * ser "de fora" ({@link #fora()}/{@link #fimFora()}, ligados pelo contexto de tile do observador). Toda chamada fora dos
 * trechos marcados e "de fora" e e contada por operacao e por tipo de contexto; algumas ganham amostra de pilha (classe
 * chamadora e mod). Chamada vinda de outra thread conta a parte (contadores atomicos).
 *
 * <p><b>Threads.</b> A pilha de marcas e a profundidade ficam em campos estaticos simples, escritos SO pela thread do
 * servidor (toda entrada confere a thread). De outra thread so se tocam contadores atomicos de {@link ContagemLista}.
 * Nada aqui escreve em objeto do jogo; o unico objeto escrito e a propria lista nossa (o campo {@code estado}, que so
 * existe nela) e as tabelas do observador.
 *
 * <p>Esta classe nao cita o Minecraft (os testes isolados a exercitam inteira).
 */
public final class ListaObservada {

    // ---- operacoes (6 bits na chave das amostras) ----
    static final int SIZE = 0;
    static final int IS_EMPTY = 1;
    static final int GET = 2;
    static final int CONTAINS = 3;
    static final int CONTAINS_ALL = 4;
    static final int INDEX_OF = 5;
    static final int LAST_INDEX_OF = 6;
    static final int TO_ARRAY = 7;
    static final int TO_ARRAY_T = 8;
    static final int ITERATOR = 9;
    static final int LIST_ITERATOR = 10;
    static final int LIST_ITERATOR_I = 11;
    static final int FOR_EACH = 12;
    static final int SPLITERATOR = 13;
    static final int STREAM = 14;
    static final int PARALLEL_STREAM = 15;
    static final int SUB_LIST = 16;
    static final int SET = 17;
    static final int ADD = 18;
    static final int ADD_I = 19;
    static final int ADD_ALL = 20;
    static final int ADD_ALL_I = 21;
    static final int REMOVE_I = 22;
    static final int REMOVE_O = 23;
    static final int REMOVE_ALL = 24;
    static final int RETAIN_ALL = 25;
    static final int REMOVE_IF = 26;
    static final int REPLACE_ALL = 27;
    static final int SORT = 28;
    static final int CLEAR = 29;
    static final int REMOVE_RANGE = 30;
    static final int IT_REMOVE = 31;
    static final int LIT_REMOVE = 32;
    static final int LIT_ADD = 33;
    static final int LIT_SET = 34;
    static final int TRIM_TO_SIZE = 35;
    static final int ENSURE_CAPACITY = 36;
    static final int CLONE = 37;
    static final int EQUALS = 38;
    static final int HASH_CODE = 39;
    static final int TO_STRING = 40;
    static final int NOPS = 41;

    static final String[] NOME_OP = {"size", "isEmpty", "get", "contains", "containsAll", "indexOf", "lastIndexOf",
        "toArray", "toArray(T[])", "iterator", "listIterator", "listIterator(int)", "forEach", "spliterator", "stream",
        "parallelStream", "subList", "set", "add", "add(int,E)", "addAll", "addAll(int,C)", "remove(int)",
        "remove(Object)", "removeAll", "retainAll", "removeIf", "replaceAll", "sort", "clear",
        "removeRange (via subList)", "iterator.remove", "listIterator.remove", "listIterator.add", "listIterator.set",
        "trimToSize", "ensureCapacity", "clone", "equals", "hashCode", "toString"};

    /** Categoria de cada operacao: L = leitura, I = iteracao/visao, M = modificacao, A = so de ArrayList, O = Object. */
    static final char[] CATEGORIA = "LLLLLLLLLIIIIIIIIMMMMMMMMMMMMMMMMMMAAAOOO".toCharArray();

    static boolean modifica(int op) {
        return op >= 0 && op < NOPS && CATEGORIA[op] == 'M';
    }

    // ---- estado de uma lista nossa (campo "estado" dela) ----
    /** O objeto que esta no campo do mundo (ou ja esteve e ainda nao foi trocado). */
    static final int ATUAL = 0;
    /** O World trocou o campo por outra lista nossa: quem ainda chama esta guardou a referencia antiga. */
    static final int APOSENTADA = 1;
    /** Copia feita por {@code clone()}: nao e a lista do mundo; nao conta. */
    static final int COPIA = 2;

    /** O que as duas listas nossas expoem para esta classe. */
    interface Observada {
        int estado();

        void estado(int e);
    }

    // ---- marcas (so a thread do servidor) ----
    private static final int MARCA_MUNDO = 1;
    private static final int MARCA_FORA = 2;
    static final int MAX_MARCAS = 64;
    private static final byte[] marcas = new byte[MAX_MARCAS];
    private static int topo;
    private static int alemDoTopo;
    /** Profundidade de chamadas da lista na thread do servidor (as chamadas internas da superclasse nao contam de novo). */
    static int prof;

    // ---- codigos que entrar() devolve ----
    /** Nao contou (sem observacao, copia, outra thread ou erro): a lista nao mexe em {@link #prof}. */
    static final int K_NADA = 0;
    /** Thread do servidor, chamada interna da superclasse ou do World: so {@link #prof}. */
    static final int K_INTERNA = 1;
    /** Thread do servidor, chamada de fora contada: a lista embrulha o iterador que devolve. */
    static final int K_FORA = 2;

    private ListaObservada() {
    }

    // =====================================================================================================
    // Marcas que o World poe (so tem efeito na thread do servidor, com a lista ligada)
    // =====================================================================================================

    /** O World vai mexer na lista dele (patch "// Hoshiestar - observador"). */
    public static void mundo() {
        if (Observador.LISTA && ThreadServidor.souAThreadDoServidor()) {
            empilhar(MARCA_MUNDO);
        }
    }

    /** Fim de um trecho do World. */
    public static void fimMundo() {
        if (Observador.LISTA && ThreadServidor.souAThreadDoServidor()) {
            desempilhar(MARCA_MUNDO);
        }
    }

    /** Dentro de um trecho do World, o codigo do mod volta a ser "de fora" (o contexto de tile do observador liga isto). */
    static void fora() {
        if (Observador.LISTA && ThreadServidor.souAThreadDoServidor()) {
            empilhar(MARCA_FORA);
        }
    }

    static void fimFora() {
        if (Observador.LISTA && ThreadServidor.souAThreadDoServidor()) {
            desempilhar(MARCA_FORA);
        }
    }

    private static void empilhar(int m) {
        if (m == MARCA_FORA && topo > 0 && alemDoTopo == 0 && marcas[topo - 1] == MARCA_FORA) {
            return; // sobra de um update que lancou excecao: substitui, nao empilha
        }
        if (topo >= MAX_MARCAS) {
            alemDoTopo++;
            ContagemLista c = contagem();
            if (c != null) {
                c.estourosMarca++;
            }
            return;
        }
        marcas[topo++] = (byte) m;
    }

    private static void desempilhar(int m) {
        if (alemDoTopo > 0) {
            alemDoTopo--;
            return;
        }
        int j = topo - 1;
        while (j >= 0 && marcas[j] != m) {
            j--;
        }
        if (j >= 0) {
            topo = j;
        }
    }

    /** true se o World esta mexendo na lista agora (a marca de cima e do World). Thread do servidor. */
    static boolean dentroDoMundo() {
        return topo > 0 && marcas[topo - 1] == MARCA_MUNDO;
    }

    /** Inicio de cada tick do servidor: zera marcas e profundidade (sobra de excecao perde no maximo um tick). */
    static void inicioTick() {
        topo = 0;
        alemDoTopo = 0;
        prof = 0;
        ContagemLista c = contagem();
        if (c != null) {
            c.inicioTick();
        }
    }

    private static ContagemLista contagem() {
        Nucleo n = Observador.N;
        return n != null ? n.lista : null;
    }

    // =====================================================================================================
    // Troca do objeto pelo World (World.updateEntities, limpeza dos tiles descarregados)
    // =====================================================================================================

    /**
     * O World vai pôr {@code nova} (um ArrayList comum) no campo no lugar de {@code velha}. Devolve uma
     * {@link ListaObservadaArray} com os mesmos elementos na mesma ordem (ou {@code nova}, se algo der errado: o
     * comportamento original). Marca a velha (se for nossa) como aposentada.
     */
    public static List embrulhar(Object velha, List nova) {
        try {
            if (nova == null || nova instanceof Observada) {
                return nova;
            }
            ListaObservadaArray r = new ListaObservadaArray(nova);
            if (velha instanceof Observada) {
                ((Observada) velha).estado(APOSENTADA);
            }
            if (ThreadServidor.souAThreadDoServidor()) {
                ContagemLista c = contagem();
                if (c != null && Observador.ATIVO) {
                    c.trocasPeloMundo++;
                }
            }
            return r;
        } catch (Throwable t) {
            return nova;
        }
    }

    // =====================================================================================================
    // Entrada de cada metodo das listas (so chamado com Observador.ATIVO)
    // =====================================================================================================

    /**
     * Conta uma chamada. Devolve {@link #K_NADA}, {@link #K_INTERNA} ou {@link #K_FORA}. Se devolve algo maior que
     * {@link #K_NADA}, ja somou 1 em {@link #prof} e a lista tira 1 no {@code finally}. Nunca lanca (a nao ser
     * StackOverflowError ao chamar este metodo, que a chamada original tambem daria).
     */
    static int entrar(Object lista, int op) {
        if (!ThreadServidor.souAThreadDoServidor()) {
            outraThread(lista, op);
            return K_NADA;
        }
        if (prof++ > 0) {
            return K_INTERNA;
        }
        try {
            int estado = ((Observada) lista).estado();
            if (estado == COPIA) {
                return K_INTERNA;
            }
            Nucleo n = Observador.N;
            if (n == null) {
                return K_INTERNA;
            }
            ContagemLista c = n.lista;
            c.chamadasTick++;
            if (dentroDoMundo()) {
                c.dentro[op]++;
                return K_INTERNA;
            }
            if (estado == APOSENTADA) {
                c.velha[op]++;
            }
            c.contarFora(n, op);
            return K_FORA;
        } catch (Throwable t) {
            return K_INTERNA;
        }
    }

    private static void outraThread(Object lista, int op) {
        try {
            if (((Observada) lista).estado() == COPIA) {
                return;
            }
            Nucleo n = Observador.N;
            if (n != null) {
                n.lista.contarOutraThread(n, op);
            }
        } catch (Throwable t) {
            // so conta; nunca atrapalha a chamada
        }
    }

    // =====================================================================================================
    // Iteradores de chamada de fora: delegam tudo e contam so as modificacoes
    // =====================================================================================================

    static Iterator iterador(Object lista, Iterator it) {
        return it == null ? null : new IteradorObservado(lista, it);
    }

    static ListIterator iteradorDeLista(Object lista, ListIterator it) {
        return it == null ? null : new IteradorDeListaObservado(lista, it);
    }

    static final class IteradorObservado implements Iterator {
        private final Object lista;
        private final Iterator it;

        IteradorObservado(Object lista, Iterator it) {
            this.lista = lista;
            this.it = it;
        }

        @Override
        public boolean hasNext() {
            return it.hasNext();
        }

        @Override
        public Object next() {
            return it.next();
        }

        @Override
        public void remove() {
            if (!Observador.ATIVO) {
                it.remove();
                return;
            }
            int k = entrar(lista, IT_REMOVE);
            try {
                it.remove();
            } finally {
                if (k > K_NADA) {
                    prof--;
                }
            }
        }

        @Override
        public void forEachRemaining(Consumer acao) {
            it.forEachRemaining(acao);
        }
    }

    static final class IteradorDeListaObservado implements ListIterator {
        private final Object lista;
        private final ListIterator it;

        IteradorDeListaObservado(Object lista, ListIterator it) {
            this.lista = lista;
            this.it = it;
        }

        @Override
        public boolean hasNext() {
            return it.hasNext();
        }

        @Override
        public Object next() {
            return it.next();
        }

        @Override
        public boolean hasPrevious() {
            return it.hasPrevious();
        }

        @Override
        public Object previous() {
            return it.previous();
        }

        @Override
        public int nextIndex() {
            return it.nextIndex();
        }

        @Override
        public int previousIndex() {
            return it.previousIndex();
        }

        @Override
        public void remove() {
            if (!Observador.ATIVO) {
                it.remove();
                return;
            }
            int k = entrar(lista, LIT_REMOVE);
            try {
                it.remove();
            } finally {
                if (k > K_NADA) {
                    prof--;
                }
            }
        }

        @Override
        public void set(Object e) {
            if (!Observador.ATIVO) {
                it.set(e);
                return;
            }
            int k = entrar(lista, LIT_SET);
            try {
                it.set(e);
            } finally {
                if (k > K_NADA) {
                    prof--;
                }
            }
        }

        @Override
        public void add(Object e) {
            if (!Observador.ATIVO) {
                it.add(e);
                return;
            }
            int k = entrar(lista, LIT_ADD);
            try {
                it.add(e);
            } finally {
                if (k > K_NADA) {
                    prof--;
                }
            }
        }

        @Override
        public void forEachRemaining(Consumer acao) {
            it.forEachRemaining(acao);
        }
    }

    /** Para os testes: estado das marcas. */
    static int topoParaTeste() {
        return topo;
    }

    /** Para os testes: lista de nomes de operacao (confere o tamanho das tabelas). */
    static List<String> nomes() {
        List<String> r = new ArrayList<String>();
        for (String s : NOME_OP) {
            r.add(s);
        }
        return r;
    }
}
