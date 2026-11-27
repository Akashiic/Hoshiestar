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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Uma fase paralela: os lotes de UM tick, prontos para os trabalhadores (plano do Degrau B, secao 2). Um objeto novo por
 * tick, montado inteiro pela thread do servidor e so depois publicado (escrita {@code volatile} em {@link Barreira}).
 *
 * <p><b>Invariantes:</b>
 * <ul>
 * <li>A lista de lotes nunca muda depois da construcao (vetor {@code final}, nunca exposto).</li>
 * <li>Cada lote e pego por no maximo uma thread: quem recebe o indice {@code j} de {@link #proximo} (contador atomico) e
 * o unico que pode ticar ou fechar o lote {@code j}.</li>
 * <li>{@link #fechados} so sobe, uma vez por lote, e so DEPOIS da ultima escrita daquele lote (o {@code Fecho}). Por isso
 * quem le {@code fechados == total} enxerga tudo o que os trabalhadores escreveram nos lotes, e nenhum item esta
 * rodando.</li>
 * <li>{@link #semNovos} so passa de falso para verdadeiro. Depois disso nenhum lote que ainda nao ticou um item comeca:
 * ele e fechado como {@code SOBROU} (por quem o pegou, ou pela thread do servidor).</li>
 * </ul>
 *
 * <p><b>Quem escreve cada campo:</b> {@link #numero} e o vetor: {@code final}. {@link #proximo} e {@link #fechados}:
 * atomicos, trabalhadores e thread do servidor. {@link #semNovos}: qualquer um dos dois (so para verdadeiro).
 * {@link #abortada}: quem chama {@code Barreira.abortar}, com a trava da barreira. {@link #despejos}, {@link #mortos} e
 * {@link #nsBarreira}: so a thread do servidor. A fila de pedidos: cada trabalhador acrescenta o proprio pedido.
 */
public final class Fase {

    /** Estados da fase (plano, secao 2). Quem guarda o estado corrente e a {@link Barreira}. */
    public enum Estado {
        /** Fora da fase. */
        FECHADA,
        /** Entre {@code abrir} e {@code rodar}: so a thread do servidor sabe dela; nenhum trabalhador foi acordado. */
        MONTANDO,
        /** Lotes publicados: trabalhadores ticando, thread do servidor na barreira. */
        RODANDO,
        /** Depois da barreira: a thread do servidor aplica, em serie, o que ficou para depois. */
        JUNCAO
    }

    /** Numero da fase nesta barreira (1, 2, 3...). */
    public final long numero;

    private final Andamento[] lotes;

    final AtomicInteger proximo = new AtomicInteger();
    final AtomicInteger fechados = new AtomicInteger();
    volatile boolean semNovos;
    volatile boolean abortada;
    volatile int despejos;
    volatile int mortos;
    volatile long nsBarreira;
    final ConcurrentLinkedQueue<Pedido> pedidos = new ConcurrentLinkedQueue<Pedido>();

    Fase(long numero, Andamento[] lotes) {
        this.numero = numero;
        this.lotes = lotes;
    }

    /** Quantos lotes a fase tem. */
    public int total() {
        return lotes.length;
    }

    /** O lote {@code i} na ordem de pega (mais caro primeiro). */
    public Andamento lote(int i) {
        return lotes[i];
    }

    /** Os lotes na ordem da juncao (chave menor primeiro). Copia nova a cada chamada. */
    public Andamento[] porChave() {
        Andamento[] c = lotes.clone();
        Arrays.sort(c, Andamento.POR_CHAVE);
        return c;
    }

    /** Lotes ja fechados. Igual a {@link #total} = a barreira passou. */
    public int fechados() {
        return fechados.get();
    }

    /** A fase parou de entregar lotes novos (primeira violacao, erro num item, aborto ou morte de uma thread). */
    public boolean semNovos() {
        return semNovos;
    }

    /** {@code Barreira.abortar} foi chamado com esta fase rodando. */
    public boolean abortada() {
        return abortada;
    }

    /** Quantas fatias de espera venceram na barreira desta fase (cada uma gerou um despejo de pilhas no log). */
    public int despejos() {
        return despejos;
    }

    /** Quantas threads de regiao morreram durante esta fase. */
    public int mortos() {
        return mortos;
    }

    /** Tempo que a thread do servidor ficou em {@code Barreira.rodar}. */
    public long nsBarreira() {
        return nsBarreira;
    }

    /** Os pedidos delegados da fase (cada um e uma violacao, com a pilha de quem pediu), na ordem em que chegaram. */
    public List<Pedido> pedidos() {
        return Collections.unmodifiableList(new ArrayList<Pedido>(pedidos));
    }

    /** Fecha o lote e, se foi esta chamada que fechou, soma na contagem. A soma vem DEPOIS da escrita do fecho. */
    boolean fechar(Andamento a, Andamento.Fim fim, int feitos, Throwable erro, long ns, String thread) {
        if (a.fechar(fim, feitos, erro, ns, thread)) {
            fechados.incrementAndGet();
            return true;
        }
        return false;
    }

    /** Pega e fecha como SOBROU todo lote que ninguem pegou. So faz sentido com {@link #semNovos} ligado. */
    void drenar(String thread) {
        while (proximo.get() < lotes.length) {
            int j = proximo.getAndIncrement();
            if (j >= lotes.length) {
                return;
            }
            fechar(lotes[j], Andamento.Fim.SOBROU, 0, null, 0L, thread);
        }
    }

    /** O lote com esta chave, ou null. Busca linear: so e usada no caminho de falha (pedido delegado). */
    Andamento daChave(long chave) {
        for (Andamento a : lotes) {
            if (a.chave == chave) {
                return a;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return "fase " + numero + ": " + fechados.get() + "/" + lotes.length + " lotes fechados"
                + (semNovos ? ", sem lotes novos" : "") + (abortada ? ", abortada" : "") + ", " + pedidos.size() + " pedido(s)";
    }
}
