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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Base pronta para um {@link Recurso}: guarda id, descricao, a opcao "ligado" e a lista de opcoes.
 * Valem as mesmas regras de carregamento de {@link Recurso} (so tipos do JDK e da api).
 *
 * <pre>
 * public final class RecursoExemplo extends RecursoBase {
 *     public static final OpcaoBool LIGADO = OpcaoBool.de("ligado", false, "Liga o exemplo.");
 *     public static final OpcaoInt LIMITE = OpcaoInt.de("limiteMs", 2000, 100, 60000, "Limite em ms.");
 *
 *     public RecursoExemplo() {
 *         super("exemplo", "Exemplo de recurso.", LIGADO);
 *         opcao(LIMITE);
 *     }
 *
 *     public List&lt;String&gt; mixinsEarly() {
 *         return Collections.singletonList("exemplo.MixinAlgo");
 *     }
 * }
 * </pre>
 */
public abstract class RecursoBase implements Recurso {

    private final String id;
    private final String descricao;
    private final OpcaoBool ligado;
    private final List<Opcao> opcoes = new ArrayList<Opcao>();

    /**
     * @param ligado a opcao "ligado"; o nome tem que ser exatamente "ligado". O padrao dela e o
     *               padrao do recurso (todos desligados, menos o vigia).
     */
    protected RecursoBase(String id, String descricao, OpcaoBool ligado) {
        if (ligado == null || !"ligado".equals(ligado.nome())) {
            throw new IllegalArgumentException("o recurso " + id + " precisa de uma OpcaoBool chamada \"ligado\"");
        }
        this.id = id;
        this.descricao = descricao;
        this.ligado = ligado;
        this.opcoes.add(ligado);
    }

    /** Acrescenta uma opcao (na ordem em que vai no arquivo) e a devolve. Use so no construtor. */
    protected final <T extends Opcao> T opcao(T o) {
        opcoes.add(o);
        return o;
    }

    @Override
    public final String id() {
        return id;
    }

    @Override
    public final String descricao() {
        return descricao;
    }

    @Override
    public final OpcaoBool ligado() {
        return ligado;
    }

    @Override
    public final List<Opcao> opcoes() {
        return Collections.unmodifiableList(opcoes);
    }

    /** Estado deste recurso no nucleo (ligado, ativo, mixins registrados e aplicados). */
    public final EstadoRecurso estado() {
        return Recursos.estado(id);
    }
}
