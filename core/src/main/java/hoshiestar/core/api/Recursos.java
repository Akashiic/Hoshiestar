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

import hoshiestar.core.nucleo.Registro;

/** Acesso ao estado dos recursos (para um recurso consultar o proprio estado ou o de outro). */
public final class Recursos {

    private Recursos() {
    }

    /** Estado do recurso com este id, ou null se ele nao existe neste jar. */
    public static EstadoRecurso estado(String id) {
        return Registro.estado(id);
    }

    /** true se o recurso existe, esta ligado e nada dele foi recusado. */
    public static boolean ativo(String id) {
        EstadoRecurso e = Registro.estado(id);
        return e != null && e.ativo();
    }
}
