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

import java.util.Locale;

/** Opcao verdadeiro/falso. No arquivo: {@code true} ou {@code false} (maiusculas tanto faz). */
public final class OpcaoBool extends Opcao {

    private final boolean padrao;
    private volatile boolean valor;

    private OpcaoBool(String nome, boolean padrao, String comentario) {
        super(nome, comentario);
        this.padrao = padrao;
        this.valor = padrao;
    }

    public static OpcaoBool de(String nome, boolean padrao, String comentario) {
        return new OpcaoBool(nome, padrao, comentario);
    }

    /** Valor resolvido (do admin, ou o padrao). */
    public boolean valor() {
        return valor;
    }

    public boolean padrao() {
        return padrao;
    }

    @Override
    public String tipo() {
        return "boolean";
    }

    @Override
    public String faixa() {
        return "true ou false";
    }

    @Override
    public String padraoComoTexto() {
        return Boolean.toString(padrao);
    }

    @Override
    public String valorComoTexto() {
        return Boolean.toString(valor);
    }

    @Override
    public String aplicarTexto(String texto) {
        String t = texto == null ? "" : texto.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(t)) {
            valor = true;
            return null;
        }
        if ("false".equals(t)) {
            valor = false;
            return null;
        }
        return "esperava true ou false";
    }

    @Override
    public void voltarAoPadrao() {
        valor = padrao;
    }
}
