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
package hoshiestar;

/**
 * Identidade do Hoshiestar. Estas constantes sao a UNICA fonte da versao: o build.gradle.kts le
 * os valores deste arquivo (por expressao regular) para dar nome ao jar e preencher o manifesto.
 *
 * <p>Para mods e plugins o jar continua se apresentando como o Crucible do qual partiu
 * ({@link #CRUCIBLE_VERSAO}); a identidade Hoshiestar aparece so no nome do arquivo, numa linha
 * do log de boot e no comando "hoshie" (decisao 26 do PROGRESSO.md).
 *
 * <p>Esta classe so usa o JDK: pode ser carregada em qualquer fase.
 */
public final class Hoshiestar {

    /** Versao do Hoshiestar. Mude so aqui. */
    public static final String VERSAO = "1.0.0-dev";

    /** Branch do upstream (Crucible) de onde o fork partiu. */
    public static final String CRUCIBLE_BRANCH = "staging";

    /** Commit do upstream (Crucible) de onde o fork partiu (hash completo). */
    public static final String CRUCIBLE_COMMIT = "67b530340b080f94079793a15e5588c2dd6fc94b";

    /** Versao que o jar continua informando para mods e plugins (igual a do Crucible de producao). */
    public static final String CRUCIBLE_VERSAO = "1.7.10-staging-67b53034";

    /** Nome do arquivo de configuracao, na pasta do servidor (ao lado do Crucible.yml). */
    public static final String ARQUIVO_CONFIG = "hoshiestar.yml";

    private Hoshiestar() {
    }

    /** A linha de boot: "&lt;versao&gt; sobre Crucible &lt;versao do Crucible&gt;". */
    public static String descricao() {
        return VERSAO + " sobre Crucible " + CRUCIBLE_VERSAO;
    }
}
