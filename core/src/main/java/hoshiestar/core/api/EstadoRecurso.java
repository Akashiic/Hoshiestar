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

import java.util.List;

/** O que o nucleo sabe de um recurso (so leitura). */
public interface EstadoRecurso {

    String id();

    /** Valor do interruptor "ligado" lido do config. */
    boolean ligado();

    /**
     * Ligado e nada foi recusado (nenhum mod ausente, nenhuma trava errada, nenhuma conferencia
     * falhou). Final a partir do FMLPreInitializationEvent; antes disso pode mudar de true para false
     * (quando a parte late e recusada).
     */
    boolean ativo();

    /** Motivo da recusa (mod ausente, trava de versao, ...), ou null. */
    String motivoRecusa();

    /** Nomes completos dos mixins registrados (early e late). */
    List<String> mixinsRegistrados();

    /** "mixin -&gt; classe alvo" de cada aplicacao vista pelo plugin do Mixin (postApply). */
    List<String> aplicacoes();

    /** Nomes completos dos mixins registrados que ja foram aplicados em pelo menos uma classe. */
    List<String> mixinsAplicados();
}
