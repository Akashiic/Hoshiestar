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

import hoshiestar.api.Log;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Registro do que as guardas viram no modo "contar" (passo 3): soma nos contadores de {@link Guardas}, marca o tile da vez
 * (houve violacao nele: a quebra de invariante do mesmo tile nao conta de novo), marca a regiao suja, e escreve no log a
 * primeira ocorrencia de cada combinacao (operacao, resultado) com a pilha. Sem Minecraft.
 *
 * <p>Os campos "da vez" sao escritos e lidos so pela thread do servidor; a thread estranha so soma contador e escreve log.
 */
public final class Registro {

    /** O tile que a thread do servidor esta ticando no contexto "lote R" (null fora dele) e a posicao dele. */
    static Object tileDaVez;
    static int vezX, vezY, vezZ;
    /** Houve violacao dentro do tile da vez. */
    static boolean violouNaVez;
    /** Regioes sujas neste tick (vetor por regiao) e quantas. */
    static boolean[] sujas = new boolean[0];
    static int nSujasTick;
    /** Violacoes da thread do servidor neste tick (passo 4: a primeira faz os lotes seguintes rodarem em serie na juncao). */
    static int violacoesTick;
    static long lotesSujos;
    /** Saida do log (troca nos testes). */
    static volatile Saida saida = new Saida() {
        @Override
        public void linha(String s) {
            Log.aviso(s);
        }
    };

    interface Saida {
        void linha(String s);
    }

    private Registro() {
    }

    /** Soma e registra. {@code regiaoAlvo}: regiao do ponto (para marcar o lote sujo); -1 se nao se aplica. */
    static void registrar(Guardas.Op op, Guardas.Res r, int x, int z, int regiaoAlvo) {
        if (r == Guardas.Res.FORA) {
            return;
        }
        boolean primeiro = Guardas.contar(op, r);
        boolean servidor = Thread.currentThread() == Guardas.servidor;
        if (r == Guardas.Res.VIOLACAO && servidor) {
            violouNaVez = true;
            violacoesTick++;
        }
        if (r == Guardas.Res.SUJA && servidor && regiaoAlvo >= 0 && regiaoAlvo < sujas.length && !sujas[regiaoAlvo]) {
            sujas[regiaoAlvo] = true;
            nSujasTick++;
            lotesSujos++;
        }
        if (primeiro && (r == Guardas.Res.VIOLACAO || r == Guardas.Res.PORTAO || r == Guardas.Res.SUJA)) {
            StringBuilder b = new StringBuilder();
            b.append("paralelo: seria ").append(nome(r)).append(" na fase: ").append(op.texto);
            b.append(" em (").append(x).append(", ").append(z).append(')');
            if (servidor && tileDaVez != null) {
                b.append("; tile da vez ").append(tileDaVez.getClass().getName()).append(" em (").append(vezX).append(", ")
                        .append(vezY).append(", ").append(vezZ).append(')');
            }
            b.append("; thread '").append(Thread.currentThread().getName()).append("'. Primeira vez desta combinacao;")
                    .append(" as proximas so somam no contador (hoshie paralelo estado). Pilha:");
            StringWriter sw = new StringWriter();
            new Throwable("pilha da guarda").printStackTrace(new PrintWriter(sw));
            b.append('\n').append(sw);
            try {
                saida.linha(b.toString());
            } catch (Throwable t) {
                // log nunca derruba a guarda
            }
        }
    }

    static String nome(Guardas.Res r) {
        switch (r) {
            case VIOLACAO:
                return "VIOLACAO (pedido delegado)";
            case PORTAO:
                return "espera no portao (thread estranha)";
            case SUJA:
                return "lote sujo (escrita de tile em serie em regiao elegivel)";
            case ADIADO:
                return "adiado";
            default:
                return r.name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** Comeco de cada tick classificado. */
    static void novoTick(int nRegioes) {
        if (sujas.length < nRegioes) {
            sujas = new boolean[Math.max(nRegioes, sujas.length * 2)];
        } else {
            java.util.Arrays.fill(sujas, 0, nRegioes, false);
        }
        nSujasTick = 0;
        violacoesTick = 0;
    }

    /** A regiao ficou suja neste tick (escrita em serie depois da classificacao). */
    static boolean sujo(int r) {
        return r >= 0 && r < sujas.length && sujas[r];
    }
}
