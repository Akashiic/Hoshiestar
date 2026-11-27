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
 * Thread de regiao (plano do Degrau B, secao 2): {@code daemon}, nome "Hoshiestar Regiao #i", estacionada fora da fase.
 * Roda o laco de um {@link Trabalhador}. Ninguem do nucleo interrompe esta thread, nunca.
 *
 * <p><b>Invariante:</b> uma thread de regiao pertence a uma {@link Barreira} e a um {@link Trabalhador} pela vida toda
 * (campo {@code final}, escrito no construtor, antes de a thread comecar).
 */
public final class ThreadRegiao extends Thread {

    final Trabalhador trabalhador;

    ThreadRegiao(Trabalhador trabalhador, String nome, long tamanhoDaPilha) {
        super(null, trabalhador, nome, tamanhoDaPilha);
        this.trabalhador = trabalhador;
        setDaemon(true);
    }

    /** A barreira a que esta thread pertence. */
    public Barreira barreira() {
        return trabalhador.barreira;
    }

    /** O lote que esta thread esta ticando agora, ou null fora de um lote. E o "dono" da regra da secao 3 do plano. */
    public Andamento loteDaVez() {
        return trabalhador.daVez;
    }

    /** O {@link Trabalho} do lote da vez, ou null. */
    public Trabalho trabalhoDaVez() {
        Andamento a = trabalhador.daVez;
        return a == null ? null : a.trabalho;
    }
}
