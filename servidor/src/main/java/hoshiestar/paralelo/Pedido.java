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

/**
 * Um pedido delegado (plano do Degrau B, secao 3): a operacao proibida que um trabalhador entregou para a thread do
 * servidor rodar no lugar dele. Cada pedido e uma violacao; a juncao le a lista em {@link Fase#pedidos()}.
 *
 * <p><b>Invariante:</b> a operacao roda no maximo uma vez, na thread do servidor, com nenhum trabalhador no estado
 * RODANDO; quem pediu so volta a rodar depois de {@link #pronto} e de o mundo ser liberado.
 *
 * <p><b>Quem escreve cada campo:</b> os campos {@code final} sao preenchidos pelo trabalhador que pede, antes de o pedido
 * entrar na fila. {@link #seq} e {@link #pronto}: com a trava da barreira (o primeiro por quem pede, o segundo pela
 * thread do servidor). {@link #resultado} e {@link #erro}: a thread do servidor, antes de marcar {@code pronto}; quem
 * pediu so le depois de ver {@code pronto} com a trava (a trava da a ordem entre as duas threads).
 */
public final class Pedido {

    final Operacao<?> op;
    /** Monitor que a operacao toma logo na entrada (ou null). Ver {@link Barreira#pedir}. */
    final Object monitor;
    /** Chaves dos lotes que a operacao suja (ou null). */
    final long[] sujar;
    final Trabalhador quem;

    /** O lote de quem pediu. */
    public final Andamento lote;
    /** Chave do lote de quem pediu (a ordem de atendimento: menor primeiro). */
    public final long chave;
    /** Indice do item que estava rodando quando o pedido foi feito. */
    public final int item;
    /** Descricao do item ({@link Trabalho#descrever}). */
    public final String descricao;
    /** Nome da thread de regiao que pediu. */
    public final String thread;
    /** Pilha de quem pediu, no momento do pedido. */
    public final StackTraceElement[] pilha;

    long seq;
    boolean pronto;
    Object resultado;
    Throwable erro;

    Pedido(Operacao<?> op, Object monitor, long[] sujar, Trabalhador quem, Andamento lote, int item, String descricao,
            String thread, StackTraceElement[] pilha) {
        this.op = op;
        this.monitor = monitor;
        this.sujar = sujar;
        this.quem = quem;
        this.lote = lote;
        this.chave = lote.chave;
        this.item = item;
        this.descricao = descricao;
        this.thread = thread;
        this.pilha = pilha;
    }

    /** As chaves dos lotes que este pedido sujou (copia; vazio se nenhum). */
    public long[] sujou() {
        return sujar == null ? new long[0] : sujar.clone();
    }

    @Override
    public String toString() {
        return "pedido de " + thread + " (lote " + chave + ", item " + item + ": " + descricao + ")";
    }
}
