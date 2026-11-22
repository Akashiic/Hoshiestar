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

import java.util.regex.Pattern;

/**
 * Uma chave do config/hoshiestar.cfg, dentro da secao do recurso: nome, tipo, valor padrao e
 * comentario (em portugues, vai para o arquivo). O valor ja vem resolvido e tipado: depois que o
 * config e lido (na fase de coremod, antes de qualquer classe do Minecraft), {@code valor()} da
 * subclasse devolve o valor do admin ou o padrao. Nao ha recarga em tempo de execucao nesta etapa.
 *
 * <p>Subclasses: {@link OpcaoBool}, {@link OpcaoInt}, {@link OpcaoLong}, {@link OpcaoDouble},
 * {@link OpcaoTexto}. Use as fabricas {@code de(...)} de cada uma.
 *
 * <p>Caminho quente: nao leia {@code valor()} a cada tick por costume; copie o valor para um campo
 * estatico (final ou volatile) no inicio (por exemplo no inicializador estatico de uma classe
 * auxiliar, ou em {@link Recurso#aoPreInit()}). A leitura de {@code valor()} e so um campo volatile,
 * sem busca em mapa, mas copiar deixa o codigo do recurso independente do config.
 *
 * <p>Os metodos marcados "uso interno" existem para o leitor do config (pacote nucleo) e nao devem
 * ser chamados pelos recursos.
 */
public abstract class Opcao {

    private static final Pattern NOME = Pattern.compile("[A-Za-z][A-Za-z0-9_.]{0,63}");

    private final String nome;
    private final String comentario;

    protected Opcao(String nome, String comentario) {
        if (nome == null || !NOME.matcher(nome).matches()) {
            throw new IllegalArgumentException("nome de opcao invalido: " + nome);
        }
        this.nome = nome;
        this.comentario = comentario == null ? "" : comentario.replace('\n', ' ').replace('\r', ' ');
    }

    /** Nome da chave no arquivo (dentro da secao do recurso). */
    public final String nome() {
        return nome;
    }

    /** Comentario em portugues, uma linha. */
    public final String comentario() {
        return comentario;
    }

    /** Nome do tipo como aparece no comentario do arquivo: boolean, int, long, double, texto. */
    public abstract String tipo();

    /** Faixa aceita como texto ("100 a 60000"), ou null se nao ha faixa. */
    public abstract String faixa();

    /** Valor padrao como texto, do jeito que vai no arquivo. */
    public abstract String padraoComoTexto();

    /** Valor atual como texto, do jeito que vai no arquivo. */
    public abstract String valorComoTexto();

    /**
     * Uso interno: tenta usar o texto lido do arquivo. Devolve null se aceitou; senao a explicacao
     * do problema (e o valor atual nao muda).
     */
    public abstract String aplicarTexto(String texto);

    /** Uso interno: volta o valor ao padrao. */
    public abstract void voltarAoPadrao();

    @Override
    public String toString() {
        return nome + "=" + valorComoTexto();
    }
}
