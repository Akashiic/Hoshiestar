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

import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import java.util.ArrayList;

/**
 * A lista de tiles do mundo depois que o proprio World a troca por um {@code ArrayList} comum (limpeza dos tiles
 * descarregados em World.updateEntities: o Crucible poe ali um ArrayList novo). Mesmo comportamento de {@code ArrayList}.
 *
 * <p>Cada metodo publico (e o {@code removeRange} protegido, que o {@code subList().clear()} usa) chama o MESMO metodo da
 * superclasse com os mesmos argumentos e devolve o que ele devolveu, inclusive excecoes (ConcurrentModificationException,
 * IndexOutOfBoundsException, StackOverflowError...). Sem observacao ({@link Observador#ATIVO} falso) e so isso, depois de
 * um teste de campo estatico. Com observacao, antes da chamada {@link ListaObservada#entrar} anota quem chamou; o
 * iterador devolvido a quem chamou de fora e embrulhado num que delega tudo e so conta as modificacoes. Metodos novos do
 * JDK 21 (getFirst, addFirst...) nao estao aqui: vao direto para a superclasse, sem contar.
 *
 * <p>Gerado do mesmo modelo que {@link ListaObservadaHashed} (as duas tem o mesmo corpo; muda so a superclasse).
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class ListaObservadaArray extends ArrayList implements ListaObservada.Observada {

    private static final long serialVersionUID = 1L;

    /** {@link ListaObservada#ATUAL}, {@link ListaObservada#APOSENTADA} ou {@link ListaObservada#COPIA}. */
    private transient int estado;

    public ListaObservadaArray() {
        super();
    }

    /** Mesmos elementos, mesma ordem (o construtor de copia do ArrayList). */
    public ListaObservadaArray(Collection c) {
        super(c);
    }

    @Override
    public int estado() {
        return estado;
    }

    @Override
    public void estado(int e) {
        estado = e;
    }

    @Override
    public void trimToSize() {
        if (!Observador.ATIVO) {
            super.trimToSize();
            return;
        }
        int k = ListaObservada.entrar(this, ListaObservada.TRIM_TO_SIZE);
        try {
            super.trimToSize();
        } finally {
            if (k > 0) {
                ListaObservada.prof--;
            }
        }
    }

    @Override
    public void ensureCapacity(int minCapacity) {
        if (!Observador.ATIVO) {
            super.ensureCapacity(minCapacity);
            return;
        }
        int k = ListaObservada.entrar(this, ListaObservada.ENSURE_CAPACITY);
        try {
            super.ensureCapacity(minCapacity);
        } finally {
            if (k > 0) {
                ListaObservada.prof--;
            }
        }
    }

    @Override
    public int size() {
        if (!Observador.ATIVO) {
            return super.size();
        }
        int k = ListaObservada.entrar(this, ListaObservada.SIZE);
        try {
            return super.size();
        } finally {
            if (k > 0) {
                ListaObservada.prof--;
            }
        }
    }

    @Override
    public boolean isEmpty() {
        if (!Observador.ATIVO) {
            return super.isEmpty();
        }
        int k = ListaObservada.entrar(this, ListaObservada.IS_EMPTY);
        try {
            return super.isEmpty();
        } finally {
            if (k > 0) {
                ListaObservada.prof--;
            }
        }
    }

    @Override
    public boolean contains(Object o) {
        if (!Observador.ATIVO) {
            return super.contains(o);
        }
        int k = ListaObservada.entrar(this, ListaObservada.CONTAINS);
        try {
            return super.contains(o);
        } finally {
            if (k > 0) {
                ListaObservada.prof--;
            }
        }
    }

    @Override
    public boolean containsAll(Collection c) {
        if (!Observador.ATIVO) {
            return super.containsAll(c);
        }
        int k = ListaObservada.entrar(this, ListaObservada.CONTAINS_ALL);
        try {
            return super.containsAll(c);
        } finally {
            if (k > 0) {
                ListaObservada.prof--;
            }
        }
    }

    @Override
    public int indexOf(Object o) {
        if (!Observador.ATIVO) {
            return super.indexOf(o);
        }
        int k = ListaObservada.entrar(this, ListaObservada.INDEX_OF);
        try {
            return super.indexOf(o);
        } finally {
            if (k > 0) {
                ListaObservada.prof--;
            }
        }
    }

    @Override
    public int lastIndexOf(Object o) {
        if (!Observador.ATIVO) {
            return super.lastIndexOf(o);
        }
        int k = ListaObservada.entrar(this, ListaObservada.LAST_INDEX_OF);
        try {
            return super.lastIndexOf(o);
        } finally {
            if (k > 0) {
                ListaObservada.prof--;
            }
        }
    }

    @Override
    public Object clone() {
        if (!Observador.ATIVO) {
            return marcarCopia(super.clone());
        }
        int k = ListaObservada.entrar(this, ListaObservada.CLONE);
        try {
            return marcarCopia(super.clone());
        } finally {
            if (k > 0) {
                ListaObservada.prof--;
            }
        }
    }

    /** A copia e da mesma classe (Object.clone copia a classe): marca para nao contar. So escreve na copia, que e nossa. */
    private static Object marcarCopia(Object c) {
        if (c instanceof ListaObservada.Observada) {
            ((ListaObservada.Observada) c).estado(ListaObservada.COPIA);
        }
        return c;
    }

    @Override
    public Object[] toArray() {
        if (!Observador.ATIVO) {
            return super.toArray();
        }
        int k = ListaObservada.entrar(this, ListaObservada.TO_ARRAY);
        try {
            return super.toArray();
        } finally {
            if (k > 0) {
                ListaObservada.prof--;
            }
        }
    }

    @Override
    public Object[] toArray(Object[] a) {
        if (!Observador.ATIVO) {
            return super.toArray(a);
        }
        int k = ListaObservada.entrar(this, ListaObservada.TO_ARRAY_T);
        try {
            return super.toArray(a);
        } finally {
            if (k > 0) {
                ListaObservada.prof--;
            }
        }
    }

    @Override
    public Object get(int index) {
        if (!Observador.ATIVO) {
            return super.get(index);
        }
        int k = ListaObservada.entrar(this, ListaObservada.GET);
        try {
            return super.get(index);
        } finally {
            if (k > 0) {
                ListaObservada.prof--;
            }
        }
    }

    @Override
    public Object set(int index, Object element) {
        if (!Observador.ATIVO) {
            return super.set(index, element);
        }
        int k = ListaObservada.entrar(this, ListaObservada.SET);
        try {
            return super.set(index, element);
        } finally {
            if (k > 0) {
                ListaObservada.prof--;
            }
        }
    }

    @Override
    public boolean add(Object e) {
        if (!Observador.ATIVO) {
            return super.add(e);
        }
        int k = ListaObservada.entrar(this, ListaObservada.ADD);
        try {
            return super.add(e);
        } finally {
            if (k > 0) {
                ListaObservada.prof--;
            }
        }
    }

    @Override
    public void add(int index, Object element) {
        if (!Observador.ATIVO) {
            super.add(index, element);
            return;
        }
        int k = ListaObservada.entrar(this, ListaObservada.ADD_I);
        try {
            super.add(index, element);
        } finally {
            if (k > 0) {
                ListaObservada.prof--;
            }
        }
    }

    @Override
    public Object remove(int index) {
        if (!Observador.ATIVO) {
            return super.remove(index);
        }
        int k = ListaObservada.entrar(this, ListaObservada.REMOVE_I);
        try {
            return super.remove(index);
        } finally {
            if (k > 0) {
                ListaObservada.prof--;
            }
        }
    }

    @Override
    public boolean remove(Object o) {
        if (!Observador.ATIVO) {
            return super.remove(o);
        }
        int k = ListaObservada.entrar(this, ListaObservada.REMOVE_O);
        try {
            return super.remove(o);
        } finally {
            if (k > 0) {
                ListaObservada.prof--;
            }
        }
    }

    @Override
    public void clear() {
        if (!Observador.ATIVO) {
            super.clear();
            return;
        }
        int k = ListaObservada.entrar(this, ListaObservada.CLEAR);
        try {
            super.clear();
        } finally {
            if (k > 0) {
                ListaObservada.prof--;
            }
        }
    }

    @Override
    public boolean addAll(Collection c) {
        if (!Observador.ATIVO) {
            return super.addAll(c);
        }
        int k = ListaObservada.entrar(this, ListaObservada.ADD_ALL);
        try {
            return super.addAll(c);
        } finally {
            if (k > 0) {
                ListaObservada.prof--;
            }
        }
    }

    @Override
    public boolean addAll(int index, Collection c) {
        if (!Observador.ATIVO) {
            return super.addAll(index, c);
        }
        int k = ListaObservada.entrar(this, ListaObservada.ADD_ALL_I);
        try {
            return super.addAll(index, c);
        } finally {
            if (k > 0) {
                ListaObservada.prof--;
            }
        }
    }

    @Override
    protected void removeRange(int fromIndex, int toIndex) {
        if (!Observador.ATIVO) {
            super.removeRange(fromIndex, toIndex);
            return;
        }
        int k = ListaObservada.entrar(this, ListaObservada.REMOVE_RANGE);
        try {
            super.removeRange(fromIndex, toIndex);
        } finally {
            if (k > 0) {
                ListaObservada.prof--;
            }
        }
    }

    @Override
    public boolean removeAll(Collection c) {
        if (!Observador.ATIVO) {
            return super.removeAll(c);
        }
        int k = ListaObservada.entrar(this, ListaObservada.REMOVE_ALL);
        try {
            return super.removeAll(c);
        } finally {
            if (k > 0) {
                ListaObservada.prof--;
            }
        }
    }

    @Override
    public boolean retainAll(Collection c) {
        if (!Observador.ATIVO) {
            return super.retainAll(c);
        }
        int k = ListaObservada.entrar(this, ListaObservada.RETAIN_ALL);
        try {
            return super.retainAll(c);
        } finally {
            if (k > 0) {
                ListaObservada.prof--;
            }
        }
    }

    @Override
    public ListIterator listIterator(int index) {
        if (!Observador.ATIVO) {
            return super.listIterator(index);
        }
        int k = ListaObservada.entrar(this, ListaObservada.LIST_ITERATOR_I);
        try {
            ListIterator it = super.listIterator(index);
            return k == ListaObservada.K_FORA ? ListaObservada.iteradorDeLista(this, it) : it;
        } finally {
            if (k > 0) {
                ListaObservada.prof--;
            }
        }
    }

    @Override
    public ListIterator listIterator() {
        if (!Observador.ATIVO) {
            return super.listIterator();
        }
        int k = ListaObservada.entrar(this, ListaObservada.LIST_ITERATOR);
        try {
            ListIterator it = super.listIterator();
            return k == ListaObservada.K_FORA ? ListaObservada.iteradorDeLista(this, it) : it;
        } finally {
            if (k > 0) {
                ListaObservada.prof--;
            }
        }
    }

    @Override
    public Iterator iterator() {
        if (!Observador.ATIVO) {
            return super.iterator();
        }
        int k = ListaObservada.entrar(this, ListaObservada.ITERATOR);
        try {
            Iterator it = super.iterator();
            return k == ListaObservada.K_FORA ? ListaObservada.iterador(this, it) : it;
        } finally {
            if (k > 0) {
                ListaObservada.prof--;
            }
        }
    }

    @Override
    public List subList(int fromIndex, int toIndex) {
        if (!Observador.ATIVO) {
            return super.subList(fromIndex, toIndex);
        }
        int k = ListaObservada.entrar(this, ListaObservada.SUB_LIST);
        try {
            return super.subList(fromIndex, toIndex);
        } finally {
            if (k > 0) {
                ListaObservada.prof--;
            }
        }
    }

    @Override
    public void forEach(Consumer action) {
        if (!Observador.ATIVO) {
            super.forEach(action);
            return;
        }
        int k = ListaObservada.entrar(this, ListaObservada.FOR_EACH);
        try {
            super.forEach(action);
        } finally {
            if (k > 0) {
                ListaObservada.prof--;
            }
        }
    }

    @Override
    public Spliterator spliterator() {
        if (!Observador.ATIVO) {
            return super.spliterator();
        }
        int k = ListaObservada.entrar(this, ListaObservada.SPLITERATOR);
        try {
            return super.spliterator();
        } finally {
            if (k > 0) {
                ListaObservada.prof--;
            }
        }
    }

    @Override
    public Stream stream() {
        if (!Observador.ATIVO) {
            return super.stream();
        }
        int k = ListaObservada.entrar(this, ListaObservada.STREAM);
        try {
            return super.stream();
        } finally {
            if (k > 0) {
                ListaObservada.prof--;
            }
        }
    }

    @Override
    public Stream parallelStream() {
        if (!Observador.ATIVO) {
            return super.parallelStream();
        }
        int k = ListaObservada.entrar(this, ListaObservada.PARALLEL_STREAM);
        try {
            return super.parallelStream();
        } finally {
            if (k > 0) {
                ListaObservada.prof--;
            }
        }
    }

    @Override
    public boolean removeIf(Predicate filter) {
        if (!Observador.ATIVO) {
            return super.removeIf(filter);
        }
        int k = ListaObservada.entrar(this, ListaObservada.REMOVE_IF);
        try {
            return super.removeIf(filter);
        } finally {
            if (k > 0) {
                ListaObservada.prof--;
            }
        }
    }

    @Override
    public void replaceAll(UnaryOperator operator) {
        if (!Observador.ATIVO) {
            super.replaceAll(operator);
            return;
        }
        int k = ListaObservada.entrar(this, ListaObservada.REPLACE_ALL);
        try {
            super.replaceAll(operator);
        } finally {
            if (k > 0) {
                ListaObservada.prof--;
            }
        }
    }

    @Override
    public void sort(Comparator c) {
        if (!Observador.ATIVO) {
            super.sort(c);
            return;
        }
        int k = ListaObservada.entrar(this, ListaObservada.SORT);
        try {
            super.sort(c);
        } finally {
            if (k > 0) {
                ListaObservada.prof--;
            }
        }
    }

    @Override
    public boolean equals(Object o) {
        if (!Observador.ATIVO) {
            return super.equals(o);
        }
        int k = ListaObservada.entrar(this, ListaObservada.EQUALS);
        try {
            return super.equals(o);
        } finally {
            if (k > 0) {
                ListaObservada.prof--;
            }
        }
    }

    @Override
    public int hashCode() {
        if (!Observador.ATIVO) {
            return super.hashCode();
        }
        int k = ListaObservada.entrar(this, ListaObservada.HASH_CODE);
        try {
            return super.hashCode();
        } finally {
            if (k > 0) {
                ListaObservada.prof--;
            }
        }
    }

    @Override
    public String toString() {
        if (!Observador.ATIVO) {
            return super.toString();
        }
        int k = ListaObservada.entrar(this, ListaObservada.TO_STRING);
        try {
            return super.toString();
        } finally {
            if (k > 0) {
                ListaObservada.prof--;
            }
        }
    }
}
