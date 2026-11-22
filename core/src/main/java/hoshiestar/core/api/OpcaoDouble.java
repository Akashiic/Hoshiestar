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
 * Opcao decimal (double) com faixa fechada [min, max]. No arquivo use ponto como separador
 * decimal (0.5, nao 0,5). NaN e infinito sao recusados.
 */
public final class OpcaoDouble extends Opcao {

    private final double padrao;
    private final double min;
    private final double max;
    private volatile double valor;

    private OpcaoDouble(String nome, double padrao, double min, double max, String comentario) {
        super(nome, comentario);
        if (!(min <= max) || !(padrao >= min) || !(padrao <= max)) {
            throw new IllegalArgumentException("faixa/padrao invalidos na opcao " + nome);
        }
        this.padrao = padrao;
        this.min = min;
        this.max = max;
        this.valor = padrao;
    }

    public static OpcaoDouble de(String nome, double padrao, double min, double max, String comentario) {
        return new OpcaoDouble(nome, padrao, min, max, comentario);
    }

    public double valor() {
        return valor;
    }

    public double padrao() {
        return padrao;
    }

    @Override
    public String tipo() {
        return "double";
    }

    @Override
    public String faixa() {
        return min + " a " + max;
    }

    @Override
    public String padraoComoTexto() {
        return Double.toString(padrao);
    }

    @Override
    public String valorComoTexto() {
        return Double.toString(valor);
    }

    @Override
    public String aplicarTexto(String texto) {
        double v;
        try {
            v = Double.parseDouble(texto == null ? "" : texto.trim());
        } catch (NumberFormatException e) {
            return "esperava um numero (use ponto: 0.5)";
        }
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return "esperava um numero finito";
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
