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

/** Opcao inteira longa (long) com faixa fechada [min, max]. Fora da faixa = valor invalido. */
public final class OpcaoLong extends Opcao {

    private final long padrao;
    private final long min;
    private final long max;
    private volatile long valor;

    private OpcaoLong(String nome, long padrao, long min, long max, String comentario) {
        super(nome, comentario);
        if (min > max || padrao < min || padrao > max) {
            throw new IllegalArgumentException("faixa/padrao invalidos na opcao " + nome);
        }
        this.padrao = padrao;
        this.min = min;
        this.max = max;
        this.valor = padrao;
    }

    public static OpcaoLong de(String nome, long padrao, long min, long max, String comentario) {
        return new OpcaoLong(nome, padrao, min, max, comentario);
    }

    public long valor() {
        return valor;
    }

    public long padrao() {
        return padrao;
    }

    @Override
    public String tipo() {
        return "long";
    }

    @Override
    public String faixa() {
        return min + " a " + max;
    }

    @Override
    public String padraoComoTexto() {
        return Long.toString(padrao);
    }

    @Override
    public String valorComoTexto() {
        return Long.toString(valor);
    }

    @Override
    public String aplicarTexto(String texto) {
        long v;
        try {
            v = Long.parseLong(texto == null ? "" : texto.trim());
        } catch (NumberFormatException e) {
            return "esperava um numero inteiro";
        }
        if (v < min || v > max) {
            return "fora da faixa " + faixa();
        }
        valor = v;
        return null;
    }

    @Override
    public void voltarAoPadrao() {
        valor = padrao;
    }
}
