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

import java.util.Collections;
import java.util.List;

/**
 * Um recurso do Hoshiestar-Core (um item da Etapa 1), com seu interruptor no config.
 *
 * <h2>Como o nucleo acha o recurso</h2>
 * O nucleo tem uma lista FIXA de nomes de classe (um por recurso) e carrega cada uma por reflexao,
 * com um construtor publico sem argumentos. Classe que nao existe no jar e pulada em silencio. Assim
 * cada recurso e escrito criando so os proprios arquivos, sem editar arquivo comum. Nomes da lista:
 * hoshiestar.core.chromaticraft.RecursoChromaticraft, hoshiestar.core.mekanism.RecursoMekanism,
 * hoshiestar.core.betterquesting.RecursoBetterQuesting, hoshiestar.core.chunkmap.RecursoChunkMap,
 * hoshiestar.core.arsmagica.RecursoArsMagica, hoshiestar.core.parada.RecursoParada,
 * hoshiestar.core.vigia.RecursoVigia, hoshiestar.core.autoteste.RecursoAutoteste.
 *
 * <h2>REGRA DE CARREGAMENTO (importante)</h2>
 * A classe do recurso e carregada na FASE DE COREMOD, antes de qualquer classe do Minecraft, do
 * Forge ou de mods poder ser carregada com seguranca (carregar uma delas cedo demais a deixaria
 * sem os transformadores e quebraria o jogo). Por isso a classe que implementa Recurso:
 * <ul>
 * <li>so pode usar tipos do JDK e do pacote hoshiestar.core.api nas assinaturas, nos campos
 * (estaticos ou nao), no inicializador estatico e no construtor;</li>
 * <li>nao deve citar tipos do Minecraft/Forge/mods nem dentro do corpo dos metodos (o verificador da
 * JVM pode carregar tipos citados no corpo para conferir atribuicoes);</li>
 * <li>nos ganchos ({@link #aoPreInit()}, {@link #aoIniciarServidor()}, ...), que rodam quando o
 * Forge ja esta de pe, chame um metodo ESTATICO de outra classe do recurso (ex.:
 * {@code MekanismGanchos.registrar()}); essa outra classe pode usar Forge/Minecraft a vontade,
 * porque so e carregada quando o gancho roda.</li>
 * </ul>
 * O mesmo vale para os tipos de {@link Opcao}: declare as opcoes como campos {@code static final}
 * da classe do recurso, que sao so tipos da api.
 *
 * <h2>Ordem das chamadas</h2>
 * <ol>
 * <li>Fase de coremod: construtor; {@link #id()}, {@link #opcoes()}; o config e lido e cada
 * {@link Opcao} recebe o valor resolvido. Daqui em diante os valores nao mudam.</li>
 * <li>Ainda na fase de coremod, se ligado: {@link #mixinsEarly()}, {@link #travas()} (as EARLY sao
 * conferidas), {@link #verificar(Fase)} com EARLY. Tudo certo: os mixins early sao registrados.</li>
 * <li>Estado CONSTRUCTING do FML, se ligado e nada foi recusado: {@link #mixinsLate()} (modids
 * conferidos), travas LATE, {@link #verificar(Fase)} com LATE. Tudo certo: mixins late registrados.</li>
 * <li>Ganchos (so se o recurso esta ATIVO = ligado e nada recusado, ver {@link EstadoRecurso}):
 * {@link #aoPreInit()}, {@link #aoIniciarServidor()}, {@link #aoServidorPronto()},
 * {@link #aoPararServidor()}.</li>
 * <li>{@link #linhaDeStatus()}: no comando "hoshiecore status" e na linha do "Done", se ligado.</li>
 * </ol>
 * As listas de mixins sao "tudo ou nada" por fase: se faltar um modid de qualquer mixin late, ou uma
 * trava LATE nao conferir, NENHUM mixin late do recurso e registrado, e o recurso fica recusado. Um
 * recurso com mixins early E late cuja parte late foi recusada fica com os mixins early no lugar
 * (nao da para desfazer): os handlers desses mixins devem conferir {@link EstadoRecurso#ativo()} (copiado
 * para um campo estatico no {@link #aoPreInit()}), que sera false.
 *
 * <h2>Nomes de mixin</h2>
 * Os mixins early moram em {@value #PACOTE_EARLY} e os late em {@value #PACOTE_LATE}, cada recurso
 * num subpacote com o seu id (ex.: hoshiestar.core.mixins.early.chunkmap.MixinChunkBlockHashMap). Nas
 * listas, use o nome relativo ao pacote ("chunkmap.MixinChunkBlockHashMap"); o nome completo tambem
 * e aceito. Nenhuma outra classe pode morar nesses dois pacotes (o Mixin proibe carregar classe de
 * pacote de mixin), entao estado e logica do recurso ficam em hoshiestar.core.&lt;id&gt;.
 * Convencoes dos mixins: nomes SRG, {@code remap = false}, sem refmap; membros proprios com prefixo
 * {@code hoshiecore$}.
 *
 * <p>Use {@link RecursoBase} para nao repetir o basico.
 */
public interface Recurso {

    /** Pacote dos mixins early (config mixins.hoshiestar_core.early.json). */
    String PACOTE_EARLY = "hoshiestar.core.mixins.early";

    /** Pacote dos mixins late (config mixins.hoshiestar_core.late.json). */
    String PACOTE_LATE = "hoshiestar.core.mixins.late";

    /** Identificador = nome da secao no config. So [a-z0-9_], unico. Ex.: "mekanism". */
    String id();

    /** Uma linha em portugues; vai como comentario da secao no config. */
    String descricao();

    /** A opcao "ligado" (chave "ligado", tipo boolean). Tem que estar tambem em {@link #opcoes()}. */
    OpcaoBool ligado();

    /** Todas as opcoes do recurso, na ordem em que vao no arquivo, incluindo {@link #ligado()}. */
    List<Opcao> opcoes();

    /** Nomes dos mixins early. Chamado so se ligado, depois do config lido. */
    default List<String> mixinsEarly() {
        return Collections.emptyList();
    }

    /** Mixins late com os modids exigidos. Chamado so se ligado, depois do config lido. */
    default List<MixinTardio> mixinsLate() {
        return Collections.emptyList();
    }

    /** Travas de versao (classe -> SHA-256 aceitos). Chamado so se ligado, depois do config lido. */
    default List<Trava> travas() {
        return Collections.emptyList();
    }

    /**
     * Conferencia extra antes de registrar os mixins da fase (ex.: ler o bytecode original com
     * {@link HashClasse#bytesOriginais(String)} e procurar uma chamada). Devolve null se pode
     * aplicar; senao o motivo, que vai para o log e para o status. Nao carregue classes aqui.
     */
    default String verificar(Fase fase) {
        return null;
    }

    /**
     * Gancho do FMLPreInitializationEvent do Hoshiestar-Core (Forge de pe, mundos ainda nao
     * carregados). Lugar para registrar handlers no barramento do FML/Forge e copiar opcoes para
     * campos estaticos. A thread do servidor ja esta em {@link ThreadServidor}.
     */
    default void aoPreInit() {
    }

    /** Gancho do FMLServerStartingEvent (mundos carregados; comandos podem ser registrados). */
    default void aoIniciarServidor() {
    }

    /** Gancho do FMLServerStartedEvent (logo depois do "Done"). */
    default void aoServidorPronto() {
    }

    /** Gancho do FMLServerStoppingEvent (servidor parando; ainda vai salvar os mundos). */
    default void aoPararServidor() {
    }

    /**
     * Uma linha curta para o comando "hoshiecore status" e para a linha do "Done" (contadores,
     * estado). null = nada a dizer. Pode ser chamada de qualquer thread: so leia campos.
     */
    default String linhaDeStatus() {
        return null;
    }
}
