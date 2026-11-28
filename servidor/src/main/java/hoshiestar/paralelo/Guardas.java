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

import java.util.concurrent.atomic.AtomicLongArray;

/**
 * As guardas do paralelo (plano do Degrau B, secoes 3 e 4) no modo "contar" do passo 3: cada ponto de entrada do mundo
 * pergunta {@link #decidir} o que seria desta operacao na fase paralela e soma o resultado num contador. Nada e barrado,
 * nada muda de thread, nenhum retorno muda: no modo {@code sombra} o jogo roda exatamente como no original. No modo
 * {@code sombra_ordenada} (passo 4) a decisao e a mesma; o que muda e que, com um lote aberto, os pontos "adiados" vao
 * para o lote ({@code GanchosAdiados}) em vez de rodar na hora.
 *
 * <p>Estado da fase (escrito so pela thread do servidor; lido por qualquer thread, por isso {@code volatile}):
 * {@link #fase} ({@link #FECHADA} ou {@link #MONTANDO}: entre a classificacao e o fim do laco de tiles do mundo escolhido)
 * e {@link #regiaoAberta} (a regiao do tile que a thread do servidor esta ticando, quando a regiao e elegivel; -1 fora
 * disso). Em {@code sombra} o "lote R" e so esse contexto: o tile roda pela chamada original.
 *
 * <p>Sem Minecraft: quem traduz a posicao em regiao e o adaptador ({@code GanchosParalelo}).
 */
public final class Guardas {

    /** Estados da fase vistos pelas guardas (os de {@code Fase} sao do nucleo e so valem a partir do passo 6). */
    public static final int FECHADA = 0, MONTANDO = 1;

    /** Quem chama. */
    public static final int SERVIDOR = 0, REGIAO = 1, ESTRANHA = 2;

    /** Categoria da operacao (secao 4 do plano). */
    public static final int LEITURA = 0, PROIBIDA = 1, DONO = 2, ADIADA = 3, EVENTO = 4;

    /** As operacoes guardadas. A ordem e a das colunas do comando e dos testes; nao reordene. */
    public enum Op {
        LER_BLOCO(LEITURA, false, "leitura de bloco (getBlock)"),
        LER_TILE(LEITURA, false, "leitura de tile (getTileEntity)"),
        ESCREVER_BLOCO(PROIBIDA, true, "escrita de bloco (setBlock)"),
        ESCREVER_META(PROIBIDA, true, "escrita de metadado (setBlockMetadataWithNotify)"),
        AVISAR_VIZINHO(PROIBIDA, true, "aviso a vizinho (notifyBlockOfNeighborChange)"),
        POR_TILE(PROIBIDA, true, "tile posto (setTileEntity)"),
        TIRAR_TILE(PROIBIDA, true, "tile tirado (removeTileEntity)"),
        ADD_TILE(PROIBIDA, true, "tile na lista (addTileEntity)"),
        ADD_TILES(PROIBIDA, false, "tiles na lista (func_147448_a)"),
        ADD_ENTIDADE(PROIBIDA, true, "entidade criada (spawnEntityInWorld)"),
        TIRAR_ENTIDADE(PROIBIDA, true, "entidade removida (removeEntity)"),
        VIZINHO_MARKDIRTY(DONO, true, "vizinhos do markDirty (func_147453_f)"),
        CHUNK_MODIFICADO(DONO, true, "chunk marcado como modificado (markTileEntityChunkModified)"),
        TICK_AGENDADO(ADIADA, false, "tick agendado (scheduleBlockUpdate e func_147446_b)"),
        EVENTO_BLOCO(ADIADA, false, "evento de bloco (addBlockEvent)"),
        AVISO_CLIENTE(ADIADA, false, "som, particula ou marca para o cliente (worldAccesses)"),
        SORTEIO(PROIBIDA, false, "sorteio no world.rand"),
        CARGA_CHUNK(PROIBIDA, false, "carga de chunk ou saida da fila de descarga (loadChunk)"),
        FALTA_CHUNK(PROIBIDA, false, "chunk pedido e nao carregado (provideChunk)"),
        EVENTO_BUKKIT(EVENTO, false, "evento do Bukkit (callEvent)"),
        EVENTO_FORGE(PROIBIDA, false, "evento do Forge (EventBus.post)"),
        PACOTE(PROIBIDA, false, "pacote para jogador (sendPacket)"),
        SALVAR(PROIBIDA, false, "salvamento (saveAllChunks, saveAllWorlds)"),
        // passo 4: loadChunk com o chunk ja carregado so tira o chunk da fila de descarga (adiado; plano, secao 4, K30)
        NAO_DESCARREGAR(ADIADA, false, "chunk presente pedido por loadChunk (sai da fila de descarga)");

        public final int categoria;
        /** A posicao da operacao vale para sujar o lote de uma regiao elegivel (escrita de bloco, tile ou entidade). */
        public final boolean sujaPorPosicao;
        public final String texto;

        Op(int categoria, boolean sujaPorPosicao, String texto) {
            this.categoria = categoria;
            this.sujaPorPosicao = sujaPorPosicao;
            this.texto = texto;
        }
    }

    /** O que seria da operacao na fase. */
    public enum Res {
        /** Fora da fase, ou nada a conferir. */
        FORA,
        /** Permitida (leitura ou escrita no proprio dono). */
        SEGUE,
        /** Proibida ou fora do dono: seria um pedido delegado e uma violacao. */
        VIOLACAO,
        /** Seria anotada no lote e aplicada na juncao. */
        ADIADO,
        /** Escrita em regiao elegivel por tile em serie antes da fase: o lote dessa regiao ficaria sujo. */
        SUJA,
        /** Thread estranha escrevendo com a fase aberta: esperaria no portao. */
        PORTAO
    }

    public static final int N_OPS = Op.values().length;
    public static final int N_RES = Res.values().length;

    /**
     * Decisao pura. {@code contexto}: regiao do tile que a thread esta ticando (-1 nenhuma). {@code alvo}: regiao do ponto
     * da operacao (-1 sem regiao; -2 = outro mundo ou operacao sem posicao). {@code alvoElegivel}: a regiao do alvo esta
     * elegivel neste tick. {@code temOuvinte}: so para {@link Op#EVENTO_BUKKIT}.
     */
    public static Res decidir(Op op, int fase, int quem, int contexto, int alvo, boolean alvoElegivel, boolean temOuvinte) {
        if (fase == FECHADA) {
            return Res.FORA;
        }
        if (quem == ESTRANHA) {
            switch (op.categoria) {
                case PROIBIDA:
                case DONO:
                case ADIADA:
                    // escrita, carga, salvamento ou aviso ao cliente vindo de thread de plugin: na fase, o portao a seguraria.
                    // Sorteio, evento e pacote de thread estranha nao sao do mundo da fase (ou sao assincronos por natureza)
                    return op == Op.SORTEIO || op == Op.EVENTO_FORGE || op == Op.PACOTE ? Res.FORA : Res.PORTAO;
                default:
                    return Res.FORA;
            }
        }
        if (contexto >= 0) {
            switch (op.categoria) {
                case LEITURA:
                case DONO:
                    return alvo == contexto ? Res.SEGUE : Res.VIOLACAO;
                case ADIADA:
                    return Res.ADIADO;
                case EVENTO:
                    return temOuvinte ? Res.VIOLACAO : Res.SEGUE;
                default:
                    return Res.VIOLACAO;
            }
        }
        // thread do servidor, fora de tile de regiao elegivel (tile em serie, entre a classificacao e o fim do laco)
        if (op.sujaPorPosicao && alvo >= 0 && alvoElegivel) {
            return Res.SUJA;
        }
        return Res.FORA;
    }

    // ---- estado da fase (thread do servidor escreve)

    public static volatile int fase = FECHADA;
    /** A thread do servidor e o {@code rand} do mundo da fase (escritos pelo adaptador ao abrir a fase). */
    public static volatile Thread servidor;
    public static volatile Object randDoMundo;
    public static volatile int regiaoAberta = -1;
    /**
     * Passo 4: um lote do modo {@code sombra_ordenada} esta aberto pela thread do servidor (os pontos "A" vao para ele).
     * Escrito so por {@code OrdenadaJogo}; lido pela guarda do evento do Bukkit.
     */
    public static volatile boolean loteAberto;

    // ---- contadores [op][res]

    private static final AtomicLongArray CONT = new AtomicLongArray(N_OPS * N_RES);
    private static final AtomicLongArray PRIMEIRO = new AtomicLongArray(N_OPS * N_RES);

    /** Soma 1 em [op][res]. Devolve verdadeiro se foi a primeira vez desta combinacao (para registrar a pilha uma vez). */
    public static boolean contar(Op op, Res res) {
        int k = op.ordinal() * N_RES + res.ordinal();
        CONT.incrementAndGet(k);
        return PRIMEIRO.compareAndSet(k, 0, 1);
    }

    public static long contagem(Op op, Res res) {
        return CONT.get(op.ordinal() * N_RES + res.ordinal());
    }

    /** Soma de uma coluna (todas as operacoes). */
    public static long total(Res res) {
        long s = 0;
        for (Op op : Op.values()) {
            s += contagem(op, res);
        }
        return s;
    }

    /** "Seria ilegal na fase": violacoes mais esperas de portao. */
    public static long seriaIlegal() {
        return total(Res.VIOLACAO) + total(Res.PORTAO);
    }

    /** So para teste. */
    static void zerar() {
        for (int i = 0; i < N_OPS * N_RES; i++) {
            CONT.set(i, 0);
            PRIMEIRO.set(i, 0);
        }
        fase = FECHADA;
        regiaoAberta = -1;
        loteAberto = false;
    }
}
