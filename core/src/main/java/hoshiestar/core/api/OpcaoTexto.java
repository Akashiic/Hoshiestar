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

/**
 * Opcao de texto, uma linha. No arquivo, tudo depois do primeiro "=" (sem os espacos das pontas)
 * e o valor; nao use aspas. Texto vazio e aceito. Quebra de linha nao existe (o arquivo e por linha).
 */
public final class OpcaoTexto extends Opcao {

    private final String padrao;
    private final int tamanhoMax;
    private volatile String valor;

    private OpcaoTexto(String nome, String padrao, int tamanhoMax, String comentario) {
        super(nome, comentario);
        if (padrao == null || padrao.indexOf('\n') >= 0 || padrao.indexOf('\r') >= 0 || padrao.length() > tamanhoMax) {
            throw new IllegalArgumentException("padrao invalido na opcao " + nome);
        }
        this.padrao = padrao;
        this.tamanhoMax = tamanhoMax;
        this.valor = padrao;
    }

    /** Texto com no maximo 1000 caracteres. */
    public static OpcaoTexto de(String nome, String padrao, String comentario) {
        return new OpcaoTexto(nome, padrao, 1000, comentario);
    }

    public String valor() {
        return valor;
    }

    public String padrao() {
        return padrao;
    }

    @Override
    public String tipo() {
        return "texto";
    }

    @Override
    public String faixa() {
        return null;
    }

    @Override
    public String padraoComoTexto() {
        return padrao;
    }

    @Override
    public String valorComoTexto() {
        return valor;
    }

    @Override
    public String aplicarTexto(String texto) {
        String t = texto == null ? "" : texto.trim();
        if (t.length() > tamanhoMax) {
            return "texto maior que " + tamanhoMax + " caracteres";
        }
        valor = t;
        return null;
    }

    @Override
    public void voltarAoPadrao() {
        valor = padrao;
    }
}
