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
package hoshiestar.api;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Log do Hoshiestar: toda linha sai com o prefixo "[Hoshiestar] " no logger log4j "Hoshiestar".
 * O prefixo vai no texto porque o logs/latest.log do Crucible nao imprime o nome do logger.
 * Um recurso pode usar {@link #de(String)} para um prefixo "[Hoshiestar] [id] ".
 *
 * <p>log4j ja existe na fase de coremod e nao e classe do Minecraft: este log pode ser usado
 * em qualquer fase. Para mensagens que podem repetir muito (por jogador, por bloco, por tick),
 * use um {@link Limitador} com {@link #avisoLimitado}.
 */
public final class Log {

    public static final String PREFIXO = "[Hoshiestar] ";

    private static final Logger LOG = LogManager.getLogger("Hoshiestar");

    private static final Log GERAL = new Log(PREFIXO);

    private final String prefixo;

    private Log(String prefixo) {
        this.prefixo = prefixo;
    }

    /** Log com o prefixo de um recurso: "[Hoshiestar] [id] ". */
    public static Log de(String id) {
        return new Log(PREFIXO + "[" + id + "] ");
    }

    public static void info(String msg) {
        GERAL.i(msg);
    }

    public static void aviso(String msg) {
        GERAL.a(msg);
    }

    public static void erro(String msg, Throwable t) {
        GERAL.e(msg, t);
    }

    /** Aviso que passa pelo limitador; quando passa, diz quantas parecidas foram suprimidas antes. */
    public static void avisoLimitado(Limitador lim, String chave, String msg) {
        GERAL.aLim(lim, chave, msg);
    }

    /** O logger log4j por baixo (para outro nivel). */
    public static Logger log4j() {
        return LOG;
    }

    public void i(String msg) {
        LOG.info(prefixo + msg);
    }

    public void a(String msg) {
        LOG.warn(prefixo + msg);
    }

    public void e(String msg, Throwable t) {
        if (t == null) {
            LOG.error(prefixo + msg);
        } else {
            LOG.error(prefixo + msg, t);
        }
    }

    public void aLim(Limitador lim, String chave, String msg) {
        long sup = lim.tentar(chave);
        if (sup >= 0) {
            LOG.warn(prefixo + msg + sufixo(sup));
        }
    }

    public void iLim(Limitador lim, String chave, String msg) {
        long sup = lim.tentar(chave);
        if (sup >= 0) {
            LOG.info(prefixo + msg + sufixo(sup));
        }
    }

    private static String sufixo(long suprimidas) {
        return suprimidas > 0 ? " (" + suprimidas + " mensagem(ns) parecida(s) suprimida(s) antes desta)" : "";
    }
}
