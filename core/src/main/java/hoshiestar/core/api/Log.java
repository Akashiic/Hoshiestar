/*
 * Hoshiestar-Core: base comum dos recursos da Etapa 1 do projeto Hoshiestar.
 * Copyright (C) 2026 Projeto Hoshiestar
 *
 * Este programa e software livre: voce pode redistribui-lo e/ou modifica-lo
 * sob os termos da GNU General Public License publicada pela Free Software
 * Foundation, versao 3 da Licenca ou (a seu criterio) qualquer versao posterior.
 *
 * Este programa e distribuido na esperanca de que seja util, mas SEM QUALQUER
 * GARANTIA; sem mesmo a garantia implicita de COMERCIABILIDADE ou ADEQUACAO A
 * UM PROPOSITO ESPECIFICO. Veja a GNU General Public License para mais detalhes.
 *
 * Voce deve ter recebido uma copia da GNU General Public License junto com este
 * programa. Se nao, veja <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package hoshiestar.core.api;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Log do Hoshiestar-Core: toda linha sai com o prefixo "[Hoshiestar-Core] " no logger
 * "Hoshiestar-Core" (log4j, que ja existe na fase de coremod e nao e classe do Minecraft).
 * Recursos podem usar {@link #recurso(String)} para um prefixo "[Hoshiestar-Core] [id] ".
 *
 * <p>Para mensagens que podem repetir muito (por jogador, por bloco, por tick), use um
 * {@link Limitador} e {@link #avisoLimitado(Limitador, String, String)}.
 */
public final class Log {

    public static final String PREFIXO = "[Hoshiestar-Core] ";

    private static final Logger LOG = LogManager.getLogger("Hoshiestar-Core");

    private static final Log GERAL = new Log(PREFIXO);

    private final String prefixo;

    private Log(String prefixo) {
        this.prefixo = prefixo;
    }

    /** Log com prefixo do recurso: "[Hoshiestar-Core] [id] ". */
    public static Log recurso(String id) {
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

    /** Aviso que passa pelo limitador; se passar, diz quantas foram suprimidas antes. */
    public static void avisoLimitado(Limitador lim, String chave, String msg) {
        GERAL.aLim(lim, chave, msg);
    }

    /** O logger log4j por baixo (para quem precisa de outro nivel). */
    public static Logger log4j() {
        return LOG;
    }

    // --- metodos de instancia (prefixo do recurso) ---

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
            LOG.warn(prefixo + msg + (sup > 0 ? " (" + sup + " mensagem(ns) parecida(s) suprimida(s) antes desta)" : ""));
        }
    }

    public void iLim(Limitador lim, String chave, String msg) {
        long sup = lim.tentar(chave);
        if (sup >= 0) {
            LOG.info(prefixo + msg + (sup > 0 ? " (" + sup + " mensagem(ns) parecida(s) suprimida(s) antes desta)" : ""));
        }
    }
}
