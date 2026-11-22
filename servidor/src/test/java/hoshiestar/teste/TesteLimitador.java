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
package hoshiestar.teste;

import hoshiestar.api.Limitador;

/** Limitador de frequencia com relogio falso. */
public final class TesteLimitador {

    static long agora;

    public static void main(String[] a) {
        Verifica v = new Verifica("TesteLimitador");
        Limitador.Tempo t = new Limitador.Tempo() {
            @Override
            public long nanos() {
                return agora;
            }
        };
        Limitador l = new Limitador(2, 1000, 3, t); // 2 por segundo por chave, no maximo 3 chaves
        agora = 0;
        v.igual(0L, l.tentar("a"), "1a mensagem passa");
        v.igual(0L, l.tentar("a"), "2a mensagem passa");
        v.igual(-1L, l.tentar("a"), "3a suprimida");
        v.igual(-1L, l.tentar("a"), "4a suprimida");
        v.igual(0L, l.tentar("b"), "outra chave tem a propria janela");
        agora = 999000000L;
        v.igual(-1L, l.tentar("a"), "ainda na janela: suprimida");
        agora = 1000000000L;
        v.igual(3L, l.tentar("a"), "janela nova: passa e conta 3 suprimidas");
        v.igual(0L, l.tentar("a"), "contagem zerada depois de informada");
        v.sim(!l.permitir("a"), "permitir() respeita o limite");
        l.tentar("c");
        v.igual(3, l.chaves(), "3 chaves guardadas");
        agora = 5000000000L;
        l.tentar("d");
        v.sim(l.chaves() <= 3, "ao encher, esquece janelas vencidas (" + l.chaves() + " chaves)");
        v.igual(0L, l.tentar(null), "chave nula vira chave vazia");
        try {
            new Limitador(0, 10);
            v.sim(false, "limitador invalido recusado");
        } catch (IllegalArgumentException e) {
            v.sim(true, "limitador invalido recusado");
        }
        v.fim();
    }
}
