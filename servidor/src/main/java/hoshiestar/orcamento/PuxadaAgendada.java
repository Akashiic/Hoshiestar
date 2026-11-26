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
package hoshiestar.orcamento;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.TreeSet;

/**
 * A puxada da fila de ticks de bloco agendados com o corte ligado (WorldServer.tickUpdates, parte "cleaning"). Nada do
 * Minecraft aparece aqui: o patch passa a TreeSet do WorldServer e um {@link Acesso} para ler a entrada.
 *
 * <p>O laco original faz, ate {@code teto} vezes: pega a primeira entrada da {@code TreeSet}; se ela ainda nao venceu,
 * para; senao tira da {@code TreeSet} e do {@code HashSet} e poe na lista do tick. Com corte, a puxada tem duas partes:
 * <ol>
 * <li>{@link #selecionar} (aqui) anda pela {@code TreeSet} com um iterador, na mesma ordem, SEM mexer nela: entrada vencida
 * de regiao que NAO roda neste tick e pulada (fica onde esta, nas duas colecoes; o salvamento do chunk continua vendo e
 * gravando, e {@code scheduleBlockUpdate} continua sem duplicar); as outras vao para a lista de saida, ate o {@code teto}
 * (que conta so as que saem);
 * <li>o proprio patch, dentro de WorldServer.tickUpdates, tira cada selecionada com as MESMAS tres chamadas do original
 * ({@code TreeSet.remove}, {@code HashSet.remove}, {@code List.add}). Isso importa: o Hodgepodge
 * ({@code speedupPendingTickLookup}, ligado por padrao) mantem um indice por chunk com um {@code @Redirect} em todo
 * {@code TreeSet.remove} de {@code tickUpdates}; tirar pela nossa classe deixaria o indice velho.
 * </ol>
 * Quando a regiao volta a rodar, as entradas dela sao as primeiras da fila e saem na ordem da {@code TreeSet} (tempo,
 * prioridade, numero de criacao), no maximo {@code teto} por tick, como no jogo.
 *
 * <p>Seguranca: a selecao so le; uma excecao nela nao muda nada no jogo. A decisao ({@link Decisor#roda}) do jogo nunca
 * lanca (erro vira "roda").
 */
public final class PuxadaAgendada {

    /** Leitura de uma entrada da fila (NextTickListEntry no jogo). */
    public interface Acesso<T> {
        long quando(T e);

        int x(T e);

        int z(T e);
    }

    /** A regiao do ponto roda neste tick? */
    public interface Decisor {
        boolean roda(int x, int z);

        /**
         * Chamada logo depois de {@link #roda} ter dito sim, para o mesmo ponto: a regiao esta em corte (N &gt; 1) e esta e
         * a rodada dela? As entradas dela sao o acumulo de N ticks e so usam o que sobra do teto depois das regioes que
         * rodam normal (achado 3 da revisao). Padrao: nao.
         */
        default boolean acumulada(int x, int z) {
            return false;
        }
    }

    private PuxadaAgendada() {
    }

    /**
     * Seleciona, em ordem, as entradas vencidas (tempo &lt;= {@code agora}) das regioes que rodam, ate {@code teto}, e as
     * poe em {@code saida}, na ordem da fila. Nao mexe na fila. Devolve quantas selecionou.
     *
     * <p>O teto e primeiro das regioes que rodam normal (e dos pontos fora de regiao); as entradas de regiao em corte na
     * rodada dela ({@link Decisor#acumulada}) usam so a sobra, as mais velhas primeiro. Sem isso, na rodada de uma regiao
     * cortada o acumulo dela (as entradas mais velhas da fila) ocupava o teto inteiro e atrasava os ticks agendados de
     * regioes que nao estao em corte. O que nao cabe fica na fila (nada se perde) e sai nos ticks seguintes.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static <T> int selecionar(TreeSet fila, int teto, long agora, Acesso<T> a, Decisor d, List saida) {
        if (teto <= 0) {
            return 0;
        }
        int inicio = saida.size();
        int normais = 0;
        int pos = 0;
        ArrayList acumulo = null;
        int[] posAcumulo = null;
        int[] posNormal = null;
        int normaisAntes = 0;
        for (Iterator it = fila.iterator(); it.hasNext(); pos++) {
            T e = (T) it.next();
            if (a.quando(e) > agora) {
                break; // a fila e ordenada pelo tempo: dali para frente nada venceu
            }
            int x = a.x(e), z = a.z(e);
            if (!d.roda(x, z)) {
                continue; // regiao em corte neste tick: a entrada fica guardada na fila
            }
            if (d.acumulada(x, z)) {
                if (acumulo == null) {
                    acumulo = new ArrayList();
                    posAcumulo = new int[16];
                }
                if (acumulo.size() < teto) {
                    if (acumulo.size() == posAcumulo.length) {
                        posAcumulo = Arrays.copyOf(posAcumulo, posAcumulo.length * 2);
                    }
                    posAcumulo[acumulo.size()] = pos;
                    acumulo.add(e);
                }
                continue;
            }
            if (acumulo != null) {
                // posicao so das normais que vieram depois da primeira do acumulo (as de antes vem todas antes dele)
                int j = normais - normaisAntes;
                if (posNormal == null) {
                    posNormal = new int[16];
                }
                if (j == posNormal.length) {
                    posNormal = Arrays.copyOf(posNormal, posNormal.length * 2);
                }
                posNormal[j] = pos;
            } else {
                normaisAntes++;
            }
            saida.add(e);
            if (++normais >= teto) {
                break;
            }
        }
        if (acumulo == null) {
            return normais;
        }
        int k = Math.min(teto - normais, acumulo.size());
        if (k <= 0) {
            return normais;
        }
        // junta as normais e as k primeiras do acumulo na ordem da fila (as duas listas ja estao em ordem)
        ArrayList soNormais = new ArrayList(saida.subList(inicio, inicio + normais));
        while (saida.size() > inicio) {
            saida.remove(saida.size() - 1);
        }
        int in = 0, ia = 0;
        while (in < normais || ia < k) {
            boolean pegaNormal = ia >= k
                    || (in < normais && (in < normaisAntes || posNormal[in - normaisAntes] < posAcumulo[ia]));
            if (pegaNormal) {
                saida.add(soNormais.get(in++));
            } else {
                saida.add(acumulo.get(ia++));
            }
        }
        return normais + k;
    }

    /**
     * A puxada inteira como o patch faz (para os testes): seleciona e depois tira cada selecionada com as tres chamadas do
     * original, na mesma ordem. Devolve quantas puxou.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static <T> int puxar(TreeSet fila, Collection conjunto, List doTick, int teto, long agora, Acesso<T> a,
            Decisor d) {
        List sel = new ArrayList();
        selecionar(fila, teto, agora, a, d, sel);
        for (Object e : sel) {
            fila.remove(e);
            conjunto.remove(e);
            doTick.add(e);
        }
        return sel.size();
    }

    /** O laco original (para os testes diferenciais): mesma conta do WorldServer.tickUpdates com forcar = false. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static <T> int original(TreeSet fila, Collection conjunto, List doTick, int teto, long agora, Acesso<T> a) {
        int puxadas = 0;
        for (int j = 0; j < teto; ++j) {
            T e = (T) fila.first();
            if (a.quando(e) > agora) {
                break;
            }
            fila.remove(e);
            conjunto.remove(e);
            doTick.add(e);
            puxadas++;
        }
        return puxadas;
    }

    /** O teto do CraftBukkit (o mesmo calculo do tickUpdates): ate 1000; acima de 20 mil, um vigesimo. */
    public static int teto(int tamanho) {
        int i = tamanho;
        if (i > 1000) {
            i = i > 20 * 1000 ? i / 20 : 1000;
        }
        return i;
    }
}
