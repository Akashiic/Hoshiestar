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
package hoshiestar.orcamento;

/**
 * Quais entidades nunca entram no corte (plano, secao 2): o jogador e toda entidade na cadeia de montaria de um jogador
 * (a montaria dele, a montaria da montaria, e quem ele carrega). No jogo, quem esta montado e ticado pela montaria (o laco
 * de entidades pula o montado); sem esta regra, um jogador num cavalo de uma regiao cortada ficaria parado junto. Nada do
 * Minecraft aqui: o {@link Montaria} le os campos {@code riddenByEntity}/{@code ridingEntity} da entidade.
 */
public final class Isencao {

    /** Profundidade maxima da cadeia (protege contra ciclo de montaria corrompido). */
    static final int PROFUNDIDADE = 16;

    public interface Montaria<T> {
        boolean ehJogador(T e);

        /** Quem esta montado nesta entidade (ou null). */
        T montadoPor(T e);

        /** Em quem esta entidade esta montada (ou null). */
        T montadoEm(T e);
    }

    private Isencao() {
    }

    /** A entidade e jogador, ou ha jogador na cadeia de montaria dela (para cima ou para baixo)? */
    public static <T> boolean isenta(T e, Montaria<T> m) {
        if (e == null) {
            return false;
        }
        if (m.ehJogador(e)) {
            return true;
        }
        T r = m.montadoPor(e);
        for (int k = 0; r != null && k < PROFUNDIDADE; k++) {
            if (m.ehJogador(r)) {
                return true;
            }
            r = m.montadoPor(r);
        }
        T s = m.montadoEm(e);
        for (int k = 0; s != null && k < PROFUNDIDADE; k++) {
            if (m.ehJogador(s)) {
                return true;
            }
            s = m.montadoEm(s);
        }
        return false;
    }
}
