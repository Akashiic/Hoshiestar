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
 * Um trabalhador que nao parou no prazo de {@link Barreira#abortar} ou de {@link Barreira#encerrar}: quem e, em que lote
 * e em que item estava. Imutavel (uma foto tirada na hora).
 */
public final class Preso {

    /** Nome da thread de regiao. */
    public final String thread;
    /** Estado do trabalhador na hora da foto: "rodando" (dentro de um item) ou "pedindo" (esperando um pedido delegado). */
    public final String estado;
    /** Falso se a thread nao tinha lote aberto (so aparece em {@code encerrar}: thread que nao terminou). */
    public final boolean temLote;
    public final long chave;
    /** Indice do item em que ficou (pode estar pela metade: conservacao suspeita nessa regiao). */
    public final int item;
    public final int tamanho;
    public final String descricao;
    public final StackTraceElement[] pilha;

    Preso(String thread, String estado, boolean temLote, long chave, int item, int tamanho, String descricao,
            StackTraceElement[] pilha) {
        this.thread = thread;
        this.estado = estado;
        this.temLote = temLote;
        this.chave = chave;
        this.item = item;
        this.tamanho = tamanho;
        this.descricao = descricao;
        this.pilha = pilha;
    }

    @Override
    public String toString() {
        return thread + " " + estado + (temLote ? " no lote " + chave + ", item " + item + " de " + tamanho + " (" + descricao + ")"
                : " sem lote aberto");
    }
}
