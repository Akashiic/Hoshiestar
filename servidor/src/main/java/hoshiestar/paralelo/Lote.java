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
package hoshiestar.paralelo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * O lote de uma regiao num tick (plano do Degrau B, secoes 2 e 4; passo 4): os tiles da regiao elegivel na ordem do laco
 * original, as operacoes adiadas que eles anotaram ({@link Adiado}, na ordem da anotacao), o tempo e os cursores que
 * garantem "cada tile no maximo uma vez" e "cada adiado no maximo uma vez". Implementa o {@link Trabalho} do nucleo.
 *
 * <p><b>Cursores.</b> {@link #proximo}: indice do proximo item a ticar; sobe ANTES do tick (um item que lancou conta como
 * feito e nunca roda de novo). {@link #aplicados}: quantos adiados ja foram aplicados; sobe ANTES de aplicar. Quem roda o
 * resto (a juncao, ou a emergencia depois de um erro nosso) comeca sempre do cursor: nada roda duas vezes.
 *
 * <p><b>Reconferencia</b> (secao 2): antes de CADA tick o {@link Ticador} confere que o tile continua vivo (nao
 * invalidado, com mundo). No lote que roda em serie na juncao ({@link #emSerie}), confere tambem que o tile ainda e o do
 * chunk naquela posicao. Falhou: o tile e pulado (conta em {@link #pulados}).
 *
 * <p><b>Threads.</b> No passo 4 ({@code sombra_ordenada}) tudo roda na thread do servidor. No passo 6, {@link #ticar} e
 * {@link #adiar} rodam na thread de regiao que pegou o lote, e a juncao le o lote depois da barreira (o nucleo da a
 * visibilidade: o trabalhador so soma na contagem de lotes feitos depois da ultima escrita). Sem Minecraft.
 */
public final class Lote implements Trabalho {

    /** O que o jogo faz com cada tile (adaptador). */
    public interface Ticador {
        /**
         * O tile continua vivo? {@code serie} verdadeiro: o lote roda em serie na juncao, e tambem se confere que o tile e o
         * do chunk na posicao dele (um tile quebrado e reposto no mesmo lugar e outro objeto).
         */
        boolean vivo(Object tile, boolean serie);

        /** O tick do tile (a chamada {@code updateEntity}). Qualquer {@code Throwable} sobe. */
        void ticar(Object tile) throws Throwable;

        /** Classe e posicao do tile, para o log. Qualquer thread; nao pode travar. */
        String descrever(Object tile);
    }

    public final int regiao;
    private long chave = Long.MAX_VALUE;
    private final ArrayList<Object> tiles = new ArrayList<Object>();
    private final ArrayList<Adiado> adiados = new ArrayList<Adiado>(0);
    private final Ticador ticador;

    /** Proximo item a ticar (sobe antes do tick). */
    int proximo;
    /** Adiados ja aplicados (sobe antes de aplicar). */
    int aplicados;
    /** Escrita em serie nesta regiao depois da classificacao (secao 3): o lote inteiro roda em serie na juncao. */
    boolean sujo;
    /** O item corrente roda em serie (juncao): reconferencia forte e sem contexto de lote (efeitos diretos, sem adiar). */
    boolean emSerie;
    /** Tiles pulados pela reconferencia. */
    int pulados;
    /** Tempo dos itens rodados no contexto do lote (ns). */
    long ns;

    public Lote(int regiao, Ticador ticador) {
        if (ticador == null) {
            throw new IllegalArgumentException("lote sem ticador");
        }
        this.regiao = regiao;
        this.ticador = ticador;
    }

    /** Despacho (thread do servidor, no laco): mais um tile, na ordem do laco. {@code chaveSecao}: a secao do tile. */
    public void adicionar(Object tile, long chaveSecao) {
        tiles.add(tile);
        if (chaveSecao < chave) {
            chave = chaveSecao;
        }
    }

    /** O tile da vez anotou uma operacao adiada. */
    public void adiar(Adiado a) {
        if (a == null) {
            throw new IllegalArgumentException("adiado nulo");
        }
        adiados.add(a);
    }

    public void sujar() {
        sujo = true;
    }

    public boolean sujo() {
        return sujo;
    }

    public Object tile(int i) {
        return tiles.get(i);
    }

    public List<Adiado> adiados() {
        return Collections.unmodifiableList(adiados);
    }

    public int proximo() {
        return proximo;
    }

    public int aplicados() {
        return aplicados;
    }

    public int pulados() {
        return pulados;
    }

    public long ns() {
        return ns;
    }

    @Override
    public long chave() {
        return chave;
    }

    @Override
    public int tamanho() {
        return tiles.size();
    }

    /** O tempo do orcamento entra com o passo 6; ate la todos os lotes tem custo 0 (so ordena). */
    @Override
    public long custo() {
        return 0;
    }

    /**
     * Tica o item {@code i}, se ele ainda nao rodou: o cursor sobe antes, a reconferencia vem depois. Um item abaixo do
     * cursor (ja rodou, ou lancou) nunca roda de novo.
     */
    @Override
    public void ticar(int i) throws Throwable {
        if (i < proximo) {
            return;
        }
        proximo = i + 1;
        Object t = tiles.get(i);
        if (!ticador.vivo(t, emSerie)) {
            pulados++;
            return;
        }
        ticador.ticar(t);
    }

    /**
     * Aplica o proximo adiado (cursor sobe antes). Devolve falso se nao ha mais. A excecao do metodo original sobe: quem
     * chama registra e segue para o proximo (o que lancou nao e aplicado de novo).
     */
    boolean aplicarProximo() {
        if (aplicados >= adiados.size()) {
            return false;
        }
        Adiado a = adiados.get(aplicados++);
        a.aplicar();
        return true;
    }

    /** O adiado que acabou de ser aplicado (para o log da falha). */
    Adiado ultimoAplicado() {
        return aplicados == 0 ? null : adiados.get(aplicados - 1);
    }

    @Override
    public String descrever(int i) {
        try {
            return ticador.descrever(tiles.get(i));
        } catch (Throwable t) {
            return "item " + i + " (sem descricao: " + t + ")";
        }
    }

    @Override
    public String toString() {
        return "lote da regiao " + regiao + " (chave " + chave + "): " + proximo + "/" + tiles.size() + " itens, " + aplicados + "/"
                + adiados.size() + " adiados" + (sujo ? ", sujo" : "") + (pulados > 0 ? ", " + pulados + " pulado(s)" : "");
    }
}
