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

/**
 * Ferramenta de teste {@code hoshie paralelo congelar <tick>} (Degrau B, passo 2): quando o tempo total do mundo 0 chega ao
 * tick pedido, o servidor para de ticar TODOS os mundos e salva uma vez. Serve para comparar dois mundos salvos no mesmo
 * tick exato ({@code tools/mundo_comparar.py mca}), no lugar da foto que quebra com o EndlessIDs (D-58).
 *
 * <p>So existe com {@code -Dhoshiestar.paralelo.teste=true} ({@link #TESTE}, lido uma vez). Sem a propriedade, o gancho em
 * {@code MinecraftServer.updateTimeLightAndEntities} e um teste de campo {@code static final} falso: comportamento
 * original. Independe de {@code paralelo.ligado} (as execucoes de referencia do plano sao com o recurso desligado).
 *
 * <p>O que continua rodando depois de congelar (limite da ferramenta, dito no doc): rede, comandos, agendador do Bukkit
 * (tarefas de plugin), fila de tarefas do servidor, eventos de tick do servidor do FML (mods) e o autosave. So os mundos
 * param (tick, entidades, tiles, rastreador de entidades). Nao ha descongelar: depois de congelar, a saida e o
 * {@code stop}.
 *
 * <p>Uma thread so: tudo e chamado pela thread do servidor (o gancho do tick e o comando do console, que roda no fim do
 * tick). Os campos sao {@code volatile} so para o {@code hoshie status} de outra thread ler valores inteiros.
 *
 * <p>Esta classe nao cita o Minecraft nem o Bukkit.
 */
public final class Congelar {

    /** Propriedade da JVM que liga as ferramentas de teste do paralelo. */
    public static final String PROPRIEDADE = "hoshiestar.paralelo.teste";

    /** {@code -Dhoshiestar.paralelo.teste=true}, lido uma vez. Falso = nenhum gancho faz nada alem deste teste. */
    public static final boolean TESTE = lerTeste();

    private static volatile long alvo = -1;
    private static volatile long ultimo = -1;
    private static volatile boolean congelado;
    private static volatile long congeladoEm = -1;

    private Congelar() {
    }

    private static boolean lerTeste() {
        try {
            return Boolean.getBoolean(PROPRIEDADE);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Gancho, antes do laco dos mundos de cada tick (thread do servidor; so com {@link #TESTE}). {@code agoraMundo0} = tempo
     * total do mundo 0 antes de ticar os mundos neste tick (= ticks ja feitos). Devolve verdadeiro UMA vez, no tick em que
     * congela: quem chamou salva os mundos (os mundos ja nao ticam a partir deste tick).
     */
    public static boolean antesDosMundos(long agoraMundo0) {
        ultimo = agoraMundo0;
        if (congelado || alvo < 0 || agoraMundo0 < alvo) {
            return false;
        }
        congeladoEm = agoraMundo0;
        congelado = true;
        return true;
    }

    /** Os mundos estao congelados? (gancho, dentro do laco dos mundos). */
    public static boolean congelado() {
        return congelado;
    }

    /** Tempo do mundo 0 em que congelou (-1 = ainda nao). */
    public static long congeladoEm() {
        return congeladoEm;
    }

    /** Tick pedido (-1 = nenhum). */
    public static long alvo() {
        return alvo;
    }

    /** Ultimo tempo do mundo 0 visto pelo gancho (-1 = o gancho ainda nao rodou). */
    public static long ultimo() {
        return ultimo;
    }

    /**
     * Pedido do comando (thread do servidor). O comando roda depois do laco dos mundos; o proximo gancho le
     * {@code ultimo + 1}. Por isso o tick tem de ser pelo menos {@code ultimo + 2}: um tick que ja passou ou que acabaria
     * de passar daria um mundo congelado num tick diferente do pedido.
     */
    public static String pedir(long tick) {
        if (!TESTE) {
            return "congelar so existe com -D" + PROPRIEDADE + "=true";
        }
        if (congelado) {
            return "ja congelado no tick " + congeladoEm + " do mundo 0 (nao ha descongelar: pare com 'stop')";
        }
        long u = ultimo;
        if (u < 0) {
            return "o tick dos mundos ainda nao comecou; tente de novo em alguns segundos";
        }
        if (tick < u + 2) {
            return "tick " + tick + " ja passou ou e proximo demais (mundo 0 agora em " + u + "; use pelo menos " + (u + 2) + ")";
        }
        long antes = alvo;
        alvo = tick;
        return "congelar: os mundos param quando o mundo 0 chegar ao tick " + tick + " (agora " + u + ", faltam " + (tick - u)
                + " ticks) e o servidor salva uma vez" + (antes >= 0 ? "; pedido anterior (" + antes + ") trocado" : "");
    }

    /** Texto do estado, para {@code hoshie paralelo congelar} sem argumento. */
    public static String estado() {
        if (!TESTE) {
            return "congelar so existe com -D" + PROPRIEDADE + "=true";
        }
        if (congelado) {
            return "congelado no tick " + congeladoEm + " do mundo 0 (pedido: " + alvo + "); mundos parados; pare com 'stop'";
        }
        return "nao congelado; mundo 0 no tick " + ultimo + (alvo >= 0 ? "; pedido para o tick " + alvo : "; nenhum pedido")
                + " (uso: hoshie paralelo congelar <tick do mundo 0>)";
    }

    /** So para o teste isolado: volta ao estado inicial. */
    static void zerarParaTeste() {
        alvo = -1;
        ultimo = -1;
        congelado = false;
        congeladoEm = -1;
    }
}
