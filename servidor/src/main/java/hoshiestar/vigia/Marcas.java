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
package hoshiestar.vigia;

import hoshiestar.api.Relogio;
import hoshiestar.config.HoshiestarConfig;

import java.util.concurrent.atomic.AtomicReference;

/**
 * As marcacoes do vigia no caminho quente do jogo (patches "// Hoshiestar" em MinecraftServer.tick,
 * World.updateEntities e EntityPlayerMP.onUpdateEntity). Entidade e tile: anotados no comeco de cada volta
 * do laco (depois do get da lista) e limpos (null) no fim do laco, uma escrita por entidade/tile. So a thread do servidor chama estes metodos;
 * a thread do vigia so le os campos.
 *
 * <p>Custo, por desenho (pedido do coordenador: o vigia e ligado por padrao):
 * <ul>
 * <li>por tick: {@link #inicioTick()} e {@link #fimTick()}: um System.nanoTime cada, uma comparacao e
 * quatro escritas de campo no total (mais tres so quando o tick foi lento);</li>
 * <li>por entidade e por tile: uma escrita de campo (mais uma por laco, para limpar; no jogador, duas) ({@code lazySet}: escrita ordenada, sem barreira
 * completa; no x86 e uma escrita comum) e nada mais: nenhuma alocacao, nenhum mapa, nenhum
 * System.nanoTime;</li>
 * <li>com o vigia desligado no hoshiestar.yml, {@link #LIGADO} e uma constante false: o JIT apaga o
 * corpo dos metodos e sobra a chamada vazia (que ele tambem apaga ao embutir).</li>
 * </ul>
 * Nenhum metodo daqui lanca excecao (escrita em campo de objeto que nunca e nulo) e a inicializacao
 * da classe nao pode falhar (o interruptor e lido dentro de try/catch). Por isso, e so por isso, os
 * patches chamam SEM try/catch no ponto de chamada (excecao a regra da base): o try/catch cria um quadro
 * de pilha no bytecode do World.updateEntities e o transformador do Opis (MobiusCore), que procura uma
 * sequencia exata de instrucoes ali, nao achou o padrao e derrubou o boot (provado no modpack). Pelo mesmo
 * motivo a marca de entidade/tile fica no comeco da volta do laco, longe da chamada de update que Opis,
 * DragonAPI e Akashic-WorldFirewall reescrevem.
 *
 * <p>Leituras rasgadas: a thread do vigia pode ver um campo velho ou uma combinacao de campos de
 * momentos diferentes (ex.: a entidade de agora com o inicio do tick seguinte). Isso so muda o
 * TEXTO do relatorio; nada daqui e lido pelo jogo.
 */
public final class Marcas {

    /**
     * Interruptor, lido uma vez quando a classe carrega (no registro, fase de coremod, por
     * {@link #anotarTicksAcimaDe}). Se o hoshiestar.yml nunca foi lido, HoshiestarConfig.atual() devolve os
     * padroes e as marcas ficam LIGADAS (sem a thread do vigia so custam as escritas).
     */
    public static final boolean LIGADO = lerInterruptor();

    /** System.nanoTime do inicio do tick em andamento; 0 = fora de tick. So a thread do servidor escreve. */
    static volatile long inicioTick;

    /**
     * Numero (Relogio.ticks) do ultimo tick LENTO (duracao &gt;= {@link #guardarAcimaDeNanos}) que terminou
     * normalmente. So a thread do servidor escreve. Guarda so os lentos para o vigia achar a duracao exata
     * mesmo depois que os ticks seguintes (que o servidor roda em sequencia para recuperar o atraso) terminam.
     */
    static volatile long seqFim;

    /** Duracao (ns) do tick {@link #seqFim}. Ordem de escrita: seqFim = -1, duracaoFim, seqFim = numero. */
    static volatile long duracaoFim;

    /** Tick com pelo menos esta duracao (ns) e anotado em seqFim/duracaoFim. Definido no registro (= limite). */
    static long guardarAcimaDeNanos = 2000L * 1000000L;

    /**
     * System.nanoTime lido por {@link #fimTick()} no fim do ultimo tick (0 = nao leu). So a thread do servidor le e
     * escreve: o medidor (recurso "medidor") usa esta leitura em vez de ler o relogio de novo ({@link #nanoFimTick()}).
     */
    private static long fimTickNanos;

    /** Entidade sendo atualizada agora (ou null). So a thread do servidor escreve. */
    static final AtomicReference<Object> ENTIDADE = new AtomicReference<Object>();

    /** Tile entity sendo atualizado agora (ou null). So a thread do servidor escreve. */
    static final AtomicReference<Object> TILE = new AtomicReference<Object>();

    private Marcas() {
    }

    /** Define o limite (ms) a partir do qual o fim do tick e anotado (o registro passa vigia.limite_ms; os testes, o deles). */
    public static void anotarTicksAcimaDe(long ms) {
        guardarAcimaDeNanos = ms * 1000000L;
    }

    private static boolean lerInterruptor() {
        try {
            return HoshiestarConfig.atual().vigia_ligado;
        } catch (Throwable t) {
            return false; // sem config legivel: nao anota nada (caminho original)
        }
    }

    /** Primeira coisa de MinecraftServer.tick(), logo depois do ++Relogio.ticks. */
    public static void inicioTick() {
        if (LIGADO) {
            ENTIDADE.lazySet(null);
            TILE.lazySet(null);
            inicioTick = System.nanoTime();
        }
    }

    /** Ultima coisa de MinecraftServer.tick() (um tick que lanca excecao nao passa aqui; o proximo inicio corrige). */
    public static void fimTick() {
        if (LIGADO) {
            long ini = inicioTick;
            if (ini != 0L) {
                long agora = System.nanoTime();
                fimTickNanos = agora; // o medidor reaproveita esta leitura (nanoFimTick)
                long d = agora - ini;
                if (d >= guardarAcimaDeNanos) {
                    // -1 antes da duracao: quem le (seq, duracao, seq) nunca ve o seq antigo duas vezes com a
                    // duracao nova (revisao M9); so nos ticks lentos, entao nao pesa
                    seqFim = -1L;
                    duracaoFim = d;
                    seqFim = Relogio.ticks;
                }
            }
            inicioTick = 0L;
        }
    }

    /**
     * System.nanoTime do inicio do tick em andamento, para o medidor nao ler o relogio de novo: a leitura do
     * {@link #inicioTick()} se o vigia esta ligado; senao, uma leitura nova. So a thread do servidor chama.
     */
    public static long nanoInicioTick() {
        long v = LIGADO ? inicioTick : 0L;
        return v != 0L ? v : System.nanoTime();
    }

    /**
     * System.nanoTime do fim do tick, para o medidor: a leitura que {@link #fimTick()} acabou de fazer (consumida:
     * vale uma vez) se o vigia esta ligado; senao, uma leitura nova. Chamar logo depois de fimTick(). So a thread do
     * servidor chama.
     */
    public static long nanoFimTick() {
        long v = fimTickNanos;
        if (v != 0L) {
            fimTickNanos = 0L;
            return v;
        }
        return System.nanoTime();
    }

    /** Antes (com a entidade) e depois (com null) da atualizacao de uma entidade. */
    public static void entidade(Object e) {
        if (LIGADO) {
            ENTIDADE.lazySet(e);
        }
    }

    /** Antes (com o tile) e depois (com null) da atualizacao de um tile entity. */
    public static void tile(Object t) {
        if (LIGADO) {
            TILE.lazySet(t);
        }
    }
}
