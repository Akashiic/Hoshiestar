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

import java.util.Comparator;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * O andamento de UM lote dentro de UMA fase: quanto ja rodou e como terminou. Um objeto novo por lote e por fase.
 *
 * <p><b>Invariante:</b> um lote e fechado exatamente uma vez ({@link #fecho} sai de {@code null} por uma troca atomica e
 * nunca mais muda), e so quem pegou o lote na fase (pelo contador atomico de {@link Fase}) tica os itens dele. Os itens
 * rodam em ordem: quando {@link #feitos} vale {@code k}, os itens {@code 0..k-1} terminaram e nenhum outro comecou, a
 * nao ser o item {@code k}, que pode estar rodando.
 *
 * <p><b>Quem escreve cada campo:</b>
 * <ul>
 * <li>{@link #trabalho}, {@link #chave}, {@link #tamanho}, {@link #custo}: {@code final}, lidos do {@link Trabalho} pela
 * thread do servidor antes de a fase ser publicada.</li>
 * <li>{@link #feitos} ({@code volatile}): so a thread que pegou o lote (um escritor). Os outros so leem (despejo de
 * pilhas, abortar, juncao).</li>
 * <li>{@link #sujo} ({@code volatile}): so a thread do servidor, ao atender um pedido delegado, com todos os
 * trabalhadores parados. O dono do lote le antes de cada item.</li>
 * <li>{@link #dono} ({@code volatile}): escrito uma vez pelo trabalhador que pegou o lote, antes do primeiro item.</li>
 * <li>{@link #fecho} ({@code volatile}, troca atomica de {@code null} para o valor final): o trabalhador dono; ou a
 * thread do servidor, quando o lote sobrou sem dono ou o dono morreu. Como so um deles pegou o lote, so um tenta; a troca
 * atomica garante "uma vez" mesmo se essa regra for quebrada por engano.</li>
 * </ul>
 */
public final class Andamento {

    /** Como o lote terminou na fase. */
    public enum Fim {
        /** Todos os itens rodaram. */
        COMPLETO,
        /** Um item lancou; o resto do lote nao rodou (plano, secao 9). */
        ERRO,
        /** Um pedido delegado escreveu nesta regiao: parou no fim do item corrente; o resto roda em serie na juncao. */
        SUJO,
        /** Nenhum item rodou: a fase parou de entregar lotes (primeira violacao, erro, aborto ou morte). Roda na juncao. */
        SOBROU,
        /** A fase foi abortada (parada do servidor): parou entre dois itens. */
        ABORTADO,
        /** A thread de regiao morreu com o lote aberto: o item {@code feitos} pode ter ficado pela metade. */
        MORREU
    }

    /** O fim de um lote: imutavel, publicado de uma vez pelo campo {@code volatile} {@link Andamento#fecho}. */
    public static final class Fecho {
        public final Fim fim;
        /** Itens que terminaram (com {@link Fim#ERRO}, e tambem o indice do item que lancou). */
        public final int feitos;
        /** O que o item lancou ({@link Fim#ERRO}) ou o que matou a thread ({@link Fim#MORREU}, pode ser null). */
        public final Throwable erro;
        /** Tempo do lote no trabalhador, sem as esperas em ponto seguro e em pedido delegado. */
        public final long ns;
        /** Nome da thread que fechou. */
        public final String thread;

        Fecho(Fim fim, int feitos, Throwable erro, long ns, String thread) {
            this.fim = fim;
            this.feitos = feitos;
            this.erro = erro;
            this.ns = ns;
            this.thread = thread;
        }

        @Override
        public String toString() {
            return fim + " feitos=" + feitos + (erro == null ? "" : " erro=" + erro) + " por " + thread;
        }
    }

    /** Ordem de pega: custo maior primeiro; no empate, chave menor primeiro (nao depende da ordem de montagem). */
    static final Comparator<Andamento> MAIS_CARO_PRIMEIRO = new Comparator<Andamento>() {
        @Override
        public int compare(Andamento a, Andamento b) {
            if (a.custo != b.custo) {
                return a.custo > b.custo ? -1 : 1;
            }
            return a.chave < b.chave ? -1 : a.chave > b.chave ? 1 : 0;
        }
    };

    /** Ordem da juncao (plano, secao 8): chave menor primeiro. */
    static final Comparator<Andamento> POR_CHAVE = new Comparator<Andamento>() {
        @Override
        public int compare(Andamento a, Andamento b) {
            return a.chave < b.chave ? -1 : a.chave > b.chave ? 1 : 0;
        }
    };

    private static final AtomicReferenceFieldUpdater<Andamento, Fecho> FECHO =
            AtomicReferenceFieldUpdater.newUpdater(Andamento.class, Fecho.class, "fecho");

    public final Trabalho trabalho;
    public final long chave;
    public final int tamanho;
    public final long custo;

    volatile int feitos;
    volatile boolean sujo;
    volatile Trabalhador dono;
    private volatile Fecho fecho;

    Andamento(Trabalho trabalho) {
        this.trabalho = trabalho;
        this.chave = trabalho.chave();
        this.tamanho = trabalho.tamanho();
        this.custo = trabalho.custo();
        if (tamanho < 0) {
            throw new IllegalArgumentException("lote " + chave + " com tamanho negativo: " + tamanho);
        }
    }

    /** Itens que ja terminaram. Durante a fase, o item de indice {@code feitos()} pode estar rodando. */
    public int feitos() {
        return feitos;
    }

    /** Um pedido delegado escreveu nesta regiao durante a fase. */
    public boolean sujo() {
        return sujo;
    }

    /** O fim do lote, ou null enquanto ele esta aberto. Depois da barreira nunca e null. */
    public Fecho fecho() {
        return fecho;
    }

    /** Nome da thread de regiao que pegou o lote, ou null se ninguem pegou. */
    public String nomeDoDono() {
        Trabalhador d = dono;
        return d == null ? null : d.nome;
    }

    /** Fecha o lote se ele ainda esta aberto. Devolve true so para quem fechou. */
    boolean fechar(Fim fim, int feitosNoFim, Throwable erro, long ns, String thread) {
        return FECHO.compareAndSet(this, null, new Fecho(fim, feitosNoFim, erro, ns, thread));
    }

    @Override
    public String toString() {
        Fecho f = fecho;
        return "lote " + chave + " " + feitos + "/" + tamanho + (sujo ? " sujo" : "") + (f == null ? " aberto" : " " + f);
    }
}
