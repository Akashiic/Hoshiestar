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

/** Opcao inteira (int) com faixa fechada [min, max]. Fora da faixa = valor invalido. */
public final class OpcaoInt extends Opcao {

    private final int padrao;
    private final int min;
    private final int max;
    private volatile int valor;

    private OpcaoInt(String nome, int padrao, int min, int max, String comentario) {
        super(nome, comentario);
        if (min > max || padrao < min || padrao > max) {
            throw new IllegalArgumentException("faixa/padrao invalidos na opcao " + nome);
        }
        this.padrao = padrao;
        this.min = min;
        this.max = max;
        this.valor = padrao;
    }

    /** Opcao com faixa [min, max] (inclusive). */
    public static OpcaoInt de(String nome, int padrao, int min, int max, String comentario) {
        return new OpcaoInt(nome, padrao, min, max, comentario);
    }

    public int valor() {
        return valor;
    }

    public int padrao() {
        return padrao;
    }

    public int min() {
        return min;
    }

    public int max() {
        return max;
    }

    @Override
    public String tipo() {
        return "int";
    }

    @Override
    public String faixa() {
        return min + " a " + max;
    }

    @Override
    public String padraoComoTexto() {
        return Integer.toString(padrao);
    }

    @Override
    public String valorComoTexto() {
        return Integer.toString(valor);
    }

    @Override
    public String aplicarTexto(String texto) {
        int v;
        try {
            v = Integer.parseInt(texto == null ? "" : texto.trim());
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
