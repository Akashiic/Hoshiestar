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
import java.util.Set;

/**
 * As recusas do recurso {@code paralelo}, como funcao pura (plano do Degrau B, secoes 7 e 10): dados os valores do
 * config e os fatos do servidor, devolve os motivos pelos quais o recurso NAO pode ligar. Lista vazia = nada impede.
 * Sem Minecraft, sem estado, sem relogio: o mesmo conjunto de entradas da sempre a mesma lista, na mesma ordem.
 *
 * <p>Quem chama: o registro (fase de coremod, so com os fatos do config) e, nos passos seguintes do plano, o boot do
 * servidor com os fatos que so o jogo sabe (dimensoes carregadas, transformadores, observadores). Enquanto ninguem
 * coleta os fatos do jogo ({@link Fatos#jogoConferido} falso, o valor inicial), isso mesmo e um motivo de recusa
 * (fail-closed): o recurso so fica aceito depois que alguem coletar os fatos, marcar {@code jogoConferido} e avaliar de
 * novo. Assim a fase nao pode abrir sem as recusas de dimensao, world.rand, hodgepodge, observador, firewall e veredito.
 */
public final class Recusas {

    /** Os fatos de fora do bloco {@code paralelo} do config. */
    public static final class Fatos {
        /** {@code orcamento.ligado}. */
        public boolean orcamentoLigado;
        /** {@code parada.ligado}. */
        public boolean paradaLigada;
        /** {@code chunkmap.cache4Seguro} (a correcao H01; ver {@link #avaliar}). */
        public boolean chunkmapCache4Seguro;
        /**
         * Os fatos do jogo abaixo foram coletados no servidor de verdade? Falso (valor inicial) = ninguem coletou: recusa.
         * Quem coleta (passo 3 do plano, ou no maximo o 6, antes de abrir a fase) preenche TODOS e so entao marca verdadeiro.
         */
        public boolean jogoConferido;
        /** Dimensoes que existem no servidor. Com {@link #jogoConferido} verdadeiro, nulo recusa (lista nao informada). */
        public Set<Integer> dimensoes;
        /** {@code world.rand} trocado por mod (BugTorched {@code replaceRandomInWorld}; conferido pela classe do objeto). */
        public boolean randTrocado;
        /** Distancia de simulacao do hodgepodge ligada ({@code addSimulationDistance_WIP}). */
        public boolean distanciaDeSimulacaoLigada;
        /** Observador do {@code ChunkRequestEvent} do DragonAPI que nao foi revisado no passo 0 (nomes). */
        public List<String> observadoresNaoRevisados = Collections.emptyList();
        /** Firewall presente sem o metodo {@code allowTileEntityTick} alcancavel. */
        public boolean firewallSemPortao;
        /** Transformadores e injecoes da rede sem veredito do passo 0 (nomes). */
        public List<String> semVeredito = Collections.emptyList();
        /** Hodgepodge {@code preventChunkLoadingFromBlockUpdates} ligado (decisao 39c; envolve updateEntities, nao verificado). */
        public boolean preventChunkLoadingLigado;
        /** Watchers do {@code TileUpdateEvent} do DragonAPI fora de {EMPTileWatcher} (decisao 39c). */
        public List<String> watchersNaoRevisados = Collections.emptyList();
        /** Fatos que a coleta do boot nao conseguiu ler (cada um recusa: nunca fail-open). */
        public List<String> naoColetados = Collections.emptyList();
        /** Classe do world.rand quando trocado (so para o texto). */
        public String classeRand;
    }

    /** O motivo de quando os fatos do jogo ainda nao foram coletados. */
    static final String FATOS_NAO_CONFERIDOS = "fatos do jogo ainda nao conferidos (dimensoes, world.rand, hodgepodge,"
            + " observadores e watchers do DragonAPI, firewall, vereditos do passo 0): a coleta roda quando o servidor fica pronto (\"Done\")";

    private Recusas() {
    }

    /**
     * Os motivos de recusa, um texto por motivo, em ordem fixa. {@code ligado == false}: lista vazia (desligado nao recusa
     * nada; nenhum gancho roda).
     *
     * <p>Alem das recusas da secao 10 do plano, uma que vem da secao 4 (linha do H01) e do texto da chave: o modo
     * {@code paralelo} (trabalhadores de verdade) exige {@code chunkmap.cache4Seguro}. Sem a correcao, duas threads gravando
     * o cache de 4 chunks deixam chunk descarregado vivo (item perdido ou duplicado). Em {@code sombra} e
     * {@code sombra_ordenada} so a thread do servidor roda, e a exigencia nao protegeria nada.
     */
    public static List<String> avaliar(boolean ligado, Parametros p, Fatos f) {
        List<String> r = new ArrayList<String>();
        if (!ligado) {
            return r;
        }
        r.addAll(p.problemas);
        if (!f.orcamentoLigado) {
            r.add("orcamento desligado (o paralelo precisa de orcamento.ligado: true; o modo 'medir' basta)");
        }
        boolean modoValido = Parametros.MODOS.contains(p.modo);
        if (modoValido && !p.modo.equals(Parametros.MODO_SOMBRA) && !f.paradaLigada) {
            r.add("modo '" + p.modo + "' exige parada.ligado: true (sem a parada, o SIGTERM salva o mundo com a fase aberta)");
        }
        if (modoValido && p.modo.equals(Parametros.MODO_PARALELO) && !f.chunkmapCache4Seguro) {
            r.add("modo 'paralelo' exige chunkmap.cache4Seguro: true (correcao H01 do cache de 4 chunks)");
        }
        if (modoValido && p.modo.equals(Parametros.MODO_PARALELO)) {
            // Hoshiestar passo 4: sombra e sombra_ordenada existem; paralelo (trabalhadores de verdade) vem no passo 6
            r.add("modo 'paralelo' ainda nao implementado neste jar (so 'sombra' e '" + Parametros.MODO_SOMBRA_ORDENADA
                    + "'; paralelo vem no passo 6 do plano)");
        }
        if (!f.jogoConferido) {
            r.add(FATOS_NAO_CONFERIDOS);
        } else if (f.dimensoes == null) {
            r.add("lista de dimensoes do servidor nao informada (fatos do jogo marcados como conferidos sem ela)");
        }
        for (String n : f.naoColetados) {
            r.add("fato do jogo nao lido no boot (" + n + ")");
        }
        if (f.dimensoes != null && !f.dimensoes.contains(p.dimensao)) {
            r.add("dimensao " + p.dimensao + " inexistente neste servidor (uma dimensao so, e ela tem de existir)");
        }
        for (String c : p.classesForaDaLista) {
            r.add("classe '" + c + "' fora da lista liberada " + Parametros.CLASSES_PERMITIDAS);
        }
        if (f.randTrocado) {
            r.add("world.rand trocado por mod (BugTorched replaceRandomInWorld)" + (f.classeRand != null ? ": " + f.classeRand : ""));
        }
        if (f.distanciaDeSimulacaoLigada) {
            r.add("distancia de simulacao do hodgepodge ligada (addSimulationDistance_WIP envolve a mesma chamada updateEntity)");
        }
        if (f.preventChunkLoadingLigado) {
            r.add("hodgepodge preventChunkLoadingFromBlockUpdates ligado (envolve World.updateEntities; sem veredito no passo 0)");
        }
        for (String o : f.observadoresNaoRevisados) {
            r.add("observador nao revisado no ChunkRequestEvent do DragonAPI: " + o);
        }
        for (String o : f.watchersNaoRevisados) {
            r.add("watcher nao revisado no TileUpdateEvent do DragonAPI (so EMPTileWatcher tem veredito): " + o);
        }
        if (f.firewallSemPortao) {
            r.add("firewall presente sem o metodo allowTileEntityTick alcancavel");
        }
        for (String s : f.semVeredito) {
            r.add("transformador ou injecao sem veredito do passo 0: " + s);
        }
        return r;
    }
}
