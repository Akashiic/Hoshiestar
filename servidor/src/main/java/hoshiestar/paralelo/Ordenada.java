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
import java.util.Comparator;
import java.util.List;

/**
 * O modo {@code sombra_ordenada} (plano do Degrau B, secao 2; passo 4): a thread do servidor roda os lotes SOZINHA, um
 * por vez, com os adiamentos, depois do laco em serie. Prova a ordem e a juncao sem concorrencia, com o mesmo
 * {@link Lote} e a mesma {@link Juncao} que o modo {@code paralelo} vai usar. O nucleo de concorrencia (passo 5) nao e
 * usado aqui: quem roda e esta classe, e ela devolve os mesmos {@link Andamento} que a {@code Barreira} devolveria.
 *
 * <p>Regras (as da fase paralela, sem threads): lote sujo antes da fase nao roda aqui (vai inteiro para a juncao, em
 * serie); um item que lanca para o lote (o resto nao roda: secao 9) e depois dele nenhum lote novo comeca (ficam
 * {@link Andamento.Fim#SOBROU} e rodam em serie na juncao); depois da primeira violacao do tick, idem (secao 3). Ordem
 * entre lotes: chave menor primeiro (a ordem entre regioes nao importa: elas nao se tocam; secao 8).
 *
 * <p>Sem Minecraft. So a thread do servidor.
 */
public final class Ordenada {

    /** O que o jogo faz em volta de cada lote (adaptador). */
    public interface Contexto {
        /** Abre o contexto "lote R": as guardas sabem a regiao e os adiados vao para este lote. */
        void abrir(Lote l);

        /** Fecha o contexto. */
        void fechar();

        /** Houve violacao de guarda desde o comeco da fase. */
        boolean violou();

        long agora();
    }

    static final Comparator<Lote> POR_CHAVE = new Comparator<Lote>() {
        @Override
        public int compare(Lote a, Lote b) {
            return a.chave() < b.chave() ? -1 : a.chave() > b.chave() ? 1 : 0;
        }
    };

    private Ordenada() {
    }

    /**
     * Roda os lotes e devolve um {@link Andamento} fechado por lote (na ordem da chave). Um erro do proprio
     * {@link Contexto} sobe (e erro nosso: quem chama faz a emergencia, que comeca dos cursores do lote).
     */
    public static List<Andamento> rodar(List<Lote> lotes, Contexto c) {
        List<Lote> ord = new ArrayList<Lote>(lotes);
        Collections.sort(ord, POR_CHAVE);
        String eu = Thread.currentThread().getName();
        List<Andamento> r = new ArrayList<Andamento>(ord.size());
        boolean parar = false;
        for (Lote l : ord) {
            Andamento a = new Andamento(l);
            r.add(a);
            if (l.sujo) {
                a.fechar(Andamento.Fim.SUJO, l.proximo, null, 0, eu);
                continue;
            }
            if (parar) {
                a.fechar(Andamento.Fim.SOBROU, l.proximo, null, 0, eu);
                continue;
            }
            int n = l.tamanho();
            int i = l.proximo;
            Throwable erro = null;
            long t0 = c.agora();
            c.abrir(l);
            try {
                for (; i < n; i++) {
                    if (l.sujo) {
                        break; // sujo no meio (no passo 6: pedido delegado que escreveu nesta regiao): o resto vai para a juncao
                    }
                    l.ticar(i);
                    a.feitos = i + 1;
                }
            } catch (Throwable t) {
                erro = t;
            } finally {
                c.fechar();
            }
            long ns = c.agora() - t0;
            l.ns += ns;
            if (erro != null) {
                a.fechar(Andamento.Fim.ERRO, i, erro, ns, eu);
                parar = true;
            } else if (i < n) {
                a.fechar(Andamento.Fim.SUJO, i, null, ns, eu);
            } else {
                a.fechar(Andamento.Fim.COMPLETO, n, null, ns, eu);
            }
            if (c.violou()) {
                parar = true;
            }
        }
        return r;
    }
}
