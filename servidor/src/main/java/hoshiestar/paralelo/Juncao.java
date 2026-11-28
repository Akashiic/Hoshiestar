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
 * A juncao (plano do Degrau B, secoes 2, 8 e 9; passo 4): o trecho em serie, na thread do servidor, logo depois da barreira
 * (ou, em {@code sombra_ordenada}, logo depois de a thread do servidor rodar os lotes). Para cada lote, na ordem da chave:
 * <ol>
 * <li>aplica os adiados do lote, na ordem da anotacao, pelo metodo original ({@link Adiado#aplicar}): a deduplicacao do
 * original (evento de bloco, tick agendado) vale como no tick em serie, porque a chamada e a mesma;</li>
 * <li>roda em serie o resto do lote que nao rodou na fase (lote sujo, lote que sobrou), com a reconferencia forte, sem
 * contexto de lote (os efeitos sao diretos, como em qualquer tile em serie).</li>
 * </ol>
 * Os adiados de um lote vem ANTES do resto em serie do mesmo lote: o que rodou na fase veio antes no laco original, entao a
 * ordem dentro da regiao fica a do laco. (Entre regioes a ordem muda, como a secao 8 aceita.)
 *
 * <p><b>Nada perdido, nada duplicado.</b> Tudo anda pelos cursores do {@link Lote}, que sobem antes de cada passo: um
 * item ou um adiado que lancou nao roda de novo, e chamar a juncao (ou a {@link #emergencia}) outra vez nao repete nada.
 * Um adiado que lanca vira falha e os seguintes sao aplicados. Um item que lanca no resto em serie vira falha e o resto
 * daquele lote nao roda (secao 9). Lote que terminou com erro, abortado ou com a thread morta nao tem resto em serie.
 *
 * <p>Sem Minecraft. So a thread do servidor, com o contexto de lote fechado.
 */
public final class Juncao {

    /** O que fazer depois da juncao (secao 9). */
    public enum Acao {
        /** Nada a fazer. */
        SEGUE,
        /** {@code ao_falhar}/{@code ao_violar: parar}: o caminho de crash de hoje (crash-report e parada que salva). */
        PARAR,
        /** {@code rebaixar}: a dimensao roda em serie ate o proximo boot. */
        REBAIXAR
    }

    /** Uma falha da fase ou da juncao. */
    public static final class Falha {
        public final Lote lote;
        /** Indice do item (-1 quando e de um adiado). */
        public final int item;
        public final Throwable erro;
        /** Onde: "tile", "tile (em serie, na juncao)", "adiado", "thread de regiao morreu", "lote nao fechado". */
        public final String onde;
        public final String descricao;

        Falha(Lote lote, int item, Throwable erro, String onde, String descricao) {
            this.lote = lote;
            this.item = item;
            this.erro = erro;
            this.onde = onde;
            this.descricao = descricao;
        }

        @Override
        public String toString() {
            return onde + ": " + descricao + (erro == null ? "" : " (" + erro + ")");
        }
    }

    /** Numeros e falhas de uma juncao. */
    public static final class Resultado {
        public final List<Falha> falhas = new ArrayList<Falha>(0);
        public int adiadosAplicados, itensEmSerie, lotesEmSerie, lotesNaoFechados;

        public boolean temFalha() {
            return !falhas.isEmpty();
        }
    }

    private Juncao() {
    }

    /** A juncao normal: decide o resto de cada lote pelo {@link Andamento.Fecho} dele. */
    public static Resultado juntar(List<Andamento> andamentos) {
        List<Andamento> ord = new ArrayList<Andamento>(andamentos);
        Collections.sort(ord, Andamento.POR_CHAVE);
        Resultado r = new Resultado();
        for (Andamento a : ord) {
            Lote l = (Lote) a.trabalho;
            aplicarAdiados(l, r);
            Andamento.Fecho f = a.fecho();
            if (f == null) {
                r.lotesNaoFechados++;
                r.falhas.add(new Falha(l, l.proximo, null, "lote nao fechado", l.toString()));
                continue;
            }
            switch (f.fim) {
                case COMPLETO:
                case ABORTADO:
                    break;
                case ERRO:
                    r.falhas.add(new Falha(l, f.feitos, f.erro, "tile", descrever(l, f.feitos)));
                    break;
                case MORREU:
                    r.falhas.add(new Falha(l, f.feitos, f.erro, "thread de regiao morreu", descrever(l, f.feitos)));
                    break;
                case SUJO:
                case SOBROU:
                default:
                    rodarEmSerie(l, Math.max(f.feitos, l.proximo), r);
                    break;
            }
        }
        return r;
    }

    /**
     * Emergencia (erro no nosso codigo no meio da fase): sem olhar fecho nenhum, aplica o que nao foi aplicado e roda em
     * serie o que nao rodou, uma vez, pelos cursores. Cada tile despachado tica no maximo uma vez neste tick.
     */
    public static Resultado emergencia(List<Lote> lotes) {
        List<Lote> ord = new ArrayList<Lote>(lotes);
        Collections.sort(ord, Ordenada.POR_CHAVE);
        Resultado r = new Resultado();
        for (Lote l : ord) {
            aplicarAdiados(l, r);
            rodarEmSerie(l, l.proximo, r);
        }
        return r;
    }

    private static void aplicarAdiados(Lote l, Resultado r) {
        while (true) {
            try {
                if (!l.aplicarProximo()) {
                    return;
                }
                r.adiadosAplicados++;
            } catch (Throwable t) {
                Adiado a = l.ultimoAplicado();
                String d;
                try {
                    d = a == null ? "?" : a.descrever();
                } catch (Throwable t2) {
                    d = "(sem descricao)";
                }
                r.falhas.add(new Falha(l, -1, t, "adiado", d));
            }
        }
    }

    private static void rodarEmSerie(Lote l, int de, Resultado r) {
        if (de >= l.tamanho()) {
            return;
        }
        r.lotesEmSerie++;
        l.emSerie = true;
        for (int i = de; i < l.tamanho(); i++) {
            try {
                l.ticar(i);
                r.itensEmSerie++;
            } catch (Throwable t) {
                r.falhas.add(new Falha(l, i, t, "tile (em serie, na juncao)", descrever(l, i)));
                return;
            }
        }
    }

    private static String descrever(Lote l, int i) {
        return i >= 0 && i < l.tamanho() ? l.descrever(i) : "item " + i + " de " + l;
    }

    /**
     * A politica da secao 9: falha (tile, adiado, thread morta, lote nao fechado) segue {@code ao_falhar}; violacao de
     * guarda ou invariante da fase quebrado segue {@code ao_violar}. Qualquer "parar" vence.
     */
    public static Acao decidir(Resultado r, boolean violou, boolean invarianteQuebrado, String aoFalhar, String aoViolar) {
        boolean falha = r != null && r.temFalha();
        boolean viol = violou || invarianteQuebrado;
        if ((falha && !Parametros.REBAIXAR.equals(aoFalhar)) || (viol && !Parametros.REBAIXAR.equals(aoViolar))) {
            return Acao.PARAR; // so "rebaixar" escrito por extenso rebaixa; qualquer outro valor para (o mais seguro)
        }
        return falha || viol ? Acao.REBAIXAR : Acao.SEGUE;
    }
}
