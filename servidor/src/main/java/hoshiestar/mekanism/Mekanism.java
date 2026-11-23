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
package hoshiestar.mekanism;

import hoshiestar.api.HashClasse;
import hoshiestar.api.Limitador;
import hoshiestar.api.Log;
import hoshiestar.api.Recursos;
import hoshiestar.api.Trava;
import hoshiestar.config.HoshiestarConfig;
import hoshiestar.embutidos.Correcao;
import hoshiestar.embutidos.Correcoes;
import hoshiestar.embutidos.asm.Asm;
import hoshiestar.embutidos.asm.Gancho;
import hoshiestar.embutidos.asm.PadraoNaoEncontrado;
import hoshiestar.embutidos.asm.ResolvedorDeGanchos;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import java.util.Locale;

/**
 * Recurso "mekanism" (item 1.C): leitura e escrita rapidas da energia da matriz de inducao do
 * Mekanism ({@code SynchronizedMatrixData.getEnergy/setEnergy}).
 *
 * <p><b>O problema.</b> A matriz nao guarda total de energia: cada leitura percorre TODAS as
 * celulas e, para cada uma, transforma a coordenada no objeto da celula (tile entity) com buscas em
 * tabelas do mundo e do chunk. Isso e repetido ~30 vezes por tick e custou 8,27% do tick no perfil
 * de producao.
 *
 * <p><b>A correcao.</b> Guarda so "coordenada -&gt; objeto da celula" (nunca a energia). A cada
 * chamada confere, barato, que cada objeto guardado ainda e o que o mundo devolveria, e le/escreve a
 * energia AO VIVO nas proprias celulas, na mesma ordem e com a mesma conta do original. A logica
 * fica em {@code hoshiestar.mekanism.matriz.MatrizInducao} (src/mods/java, compila contra o jar do
 * Mekanism); esta classe so registra, guarda o modo e os contadores.
 *
 * <p><b>O que muda na classe do mod</b> (so com o modo diferente de "desligado" e a trava
 * conferindo): no inicio de getEnergy e setEnergy entra uma chamada a um gancho com retorno
 * antecipado (formato 1 da base) e a classe ganha um campo publico sintetico
 * {@value #CAMPO} (Object) onde o gancho guarda o estado daquela estrutura. Nada e salvo em disco.
 *
 * <p>Roda na fase de coremod: so JDK, ASM e hoshiestar.* (nada do Minecraft, Forge, Bukkit ou
 * Mekanism). Os contadores e o modo ficam aqui (e nao na classe da logica) para que a linha de
 * estado e o teste isolado os leiam sem carregar classes do Minecraft.
 */
public final class Mekanism {

    public static final String ID = "mekanism";

    /** Classe alvo (nome com pontos). */
    public static final String CLASSE = "mekanism.common.content.matrix.SynchronizedMatrixData";

    /** SHA-256 de SynchronizedMatrixData.class em Mekanism-1.7.10-9.10.48-AkashicPerf.3-ALL.jar. */
    public static final String SHA_MATRIZ = "34fa7fec1a2471a25d8af849954852e3f43174e0c3aaf6d29c66c1f2394b44f8";

    /**
     * Classes do Mekanism de cuja forma EXATA a logica depende, com o SHA-256 aceito de cada uma (do
     * mesmo jar). Conferidas quando a classe alvo passa pelo transformador, lendo o arquivo .class
     * (sem carregar). Qualquer uma diferente ou ausente: a correcao nao e aplicada.
     * <ul>
     * <li>TileEntityInductionCell: getEnergy/setEnergy/getMaxEnergy so leem e escrevem um campo (nada
     * de efeito colateral no meio da escrita), canUpdate() = false (nunca entra nas listas de tick).</li>
     * <li>TileEntityBasicBlock (superclasse da celula): invalidate() chama o do Minecraft primeiro
     * (entao isInvalid() fica true quando a celula sai do mundo).</li>
     * <li>Coord4D: getTileEntity = exists() (chunkExists) + World.getTileEntity; campos x/y/z.</li>
     * <li>MatrixUpdateProtocol: unico lugar que acrescenta coordenadas em {@code cells}, e so numa
     * estrutura nova (o conjunto nunca muda depois de formado).</li>
     * <li>UpdateProtocol: cria um Coord4D NOVO para cada coordenada interna (Coord4D e mutavel:
     * translate() muda o proprio objeto; nenhum codigo mexe nas coordenadas de {@code cells}).</li>
     * </ul>
     */
    static final String[][] DEPENDENCIAS = {
        {"mekanism.common.multiblock.UpdateProtocol", "3c74587e2f2b731499f9ff0afebd4708ae7c47a7d3c5bee88a20e829e80422ba"},
        {"mekanism.common.tile.TileEntityInductionCell", "79e03b7ef413d5b44480846b347a857b3a744b8012a9640822b829cd02299f0b"},
        {"mekanism.common.tile.TileEntityBasicBlock", "71c14cfb08bf6f6f00650f04874cc8f47e7a067c3824839701127e5f72e3a6a8"},
        {"mekanism.api.Coord4D", "6458d05a097ae29b08772a425606b514b36ac0bb6aaa281d7f14aacccf765801"},
        {"mekanism.common.content.matrix.MatrixUpdateProtocol", "081d53034a5063a190f0ba0f834794a1db90030368eb115cdfcab69d9177a8ed"},
    };

    /** Campo acrescentado na classe alvo (guarda o estado da estrutura; Object). */
    public static final String CAMPO = "hoshiestar$matriz";

    /** Classe dos ganchos (src/mods/java). */
    public static final String GANCHO = "hoshiestar/mekanism/matriz/GanchoMatriz";

    static final String DESC_LER = "(Lnet/minecraft/world/World;)D";
    static final String DESC_ESCREVER = "(Lnet/minecraft/world/World;D)V";

    // ---------------------------------------------------------------- modo

    public static final int DESLIGADO = 0;
    public static final int VERIFICAR = 1;
    public static final int LIGADO = 2;

    /**
     * Modo em vigor (lido do hoshiestar.yml no boot). Escrito no boot e por {@link #desligarPorErro}
     * (vira DESLIGADO); os testes isolados tambem trocam. Uma thread que leia um valor atrasado nao faz
     * mal: fora da thread do servidor o gancho roda sempre o original.
     */
    public static int modo = DESLIGADO;

    /** Ticks entre reconstrucoes da lista de celulas (1 a 20). */
    public static int intervalo = 1;

    /** Tentativa de reconstrucao de estrutura incompleta: no maximo uma a cada tantos ticks. */
    public static final int ESPERA_INCOMPLETA = 20;

    /** Motivo do desligamento automatico (primeira excecao nossa), ou null. */
    public static volatile String desligadoPorErro;

    // ---------------------------------------------------------------- contadores
    // Lidos pela linha de estado (um valor um pouco atrasado nao faz mal). Todos sao escritos so pela
    // thread do servidor, MENOS foraDaThread e erros, que qualquer thread pode contar: esses dois so
    // mudam dentro de metodos synchronized (nenhuma contagem se perde). Publicos para o teste isolado.

    /** Leituras respondidas pelo caminho rapido. */
    public static long leiturasRapidas;
    /** Reconstrucoes da lista (cada uma custa uma leitura original e devolve o mesmo valor). */
    public static long reconstrucoes;
    /** Reconstrucoes que acharam a estrutura incompleta (coordenada sem celula, chunk descarregado...). */
    public static long incompletas;
    /** Leituras que foram para o original sem reconstruir (estrutura incompleta, esperando a vez). */
    public static long leiturasOriginais;
    /** Escritas feitas pelo caminho rapido. */
    public static long escritasRapidas;
    /** Escritas que foram para o original (estado invalido, conferencia falhou...). */
    public static long escritasOriginais;
    /** Escritas recusadas porque a identidade de alguma celula nao conferiu no mapa do chunk. */
    public static long recusasIdentidade;
    /** Chamadas feitas de dentro de uma reconstrucao (rodaram o original). */
    public static long reentradas;
    /** Chamadas de OUTRA thread que nao a do servidor (rodaram o original). Muda so em {@link #contarOutraThread}. */
    public static volatile long foraDaThread;
    /** Chamadas na thread do servidor com mundo nulo/de cliente ou antes do primeiro tick (rodaram o original). */
    public static long semMundoOuAntesDoTick;
    /** Excecoes de outro mod que sairam de dentro da busca original e foram relancadas (como o original faria). */
    public static long excecoesDeTerceiros;
    /** Modo verificar: leituras comparadas. */
    public static long comparacoes;
    /** Modo verificar: leituras em que algum objeto guardado nao era o que o mundo devolve. */
    public static long divergenciasIdentidade;
    /** Modo verificar: leituras em que a soma rapida diferia da original (bit a bit). */
    public static long divergenciasSoma;
    /** Modo verificar: escritas que o caminho rapido aceitaria, comparadas com o mundo. */
    public static long comparacoesEscrita;
    /** Modo verificar: escritas que o caminho rapido aceitaria mas com identidade divergente. */
    public static long divergenciasEscrita;
    /** Excecoes nossas (cada uma desliga o recurso). Muda so em {@link #desligarPorErro}. */
    public static volatile long erros;

    private static final Limitador LIM_ERRO = new Limitador(5, 60000L);

    private Mekanism() {
    }

    // ---------------------------------------------------------------- registro

    /** Chamado por hoshiestar.Catalogo na fase de coremod. */
    public static void registrar(HoshiestarConfig cfg, Correcoes correcoes) {
        registrar(cfg, correcoes, null);
    }

    /**
     * @param carregador onde ler os .class das dependencias para conferir as travas; null = o class
     *        loader dos mods ({@link HashClasse#carregadorDosMods()}) na hora da transformacao
     */
    public static void registrar(HoshiestarConfig cfg, Correcoes correcoes, final ClassLoader carregador) {
        int m = lerModo(cfg.mekanism_modo);
        int iv = cfg.mekanism_intervalo;
        if (iv < 1 || iv > 20) {
            Log.de(ID).a("mekanism.intervalo = " + iv + " fora de 1..20; usando " + (iv < 1 ? 1 : 20));
            iv = iv < 1 ? 1 : 20;
        }
        modo = m;
        intervalo = iv;
        boolean ligado = m != DESLIGADO;
        Recursos.registrar(ID, "matriz de inducao do Mekanism: energia lida/escrita sem refazer a busca de cada celula", ligado)
                .estado(new Recursos.Estado() {
                    @Override
                    public String linha() {
                        return linhaDeEstado();
                    }
                });
        correcoes.adicionar(Correcao.nova(ID, "gancho no inicio de getEnergy e setEnergy da matriz de inducao",
                Trava.de(CLASSE, SHA_MATRIZ), ligado, new Correcao.Transformacao() {
                    @Override
                    public void aplicar(ClassNode classe, ResolvedorDeGanchos ganchos) throws PadraoNaoEncontrado {
                        transformar(classe, ganchos, carregador != null ? carregador : HashClasse.carregadorDosMods());
                    }
                }));
    }

    /** "desligado" / "verificar" / "ligado" (sem diferenciar maiusculas). Outro valor: desligado + aviso. */
    public static int lerModo(String s) {
        String v = s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
        if (v.equals("ligado")) {
            return LIGADO;
        }
        if (v.equals("verificar")) {
            return VERIFICAR;
        }
        if (!v.equals("desligado")) {
            Log.de(ID).a("mekanism.modo = '" + s + "' nao e desligado/verificar/ligado; usando desligado");
        }
        return DESLIGADO;
    }

    public static String nomeDoModo(int m) {
        return m == LIGADO ? "ligado" : m == VERIFICAR ? "verificar" : "desligado";
    }

    /**
     * A transformacao: (1) confere as travas das dependencias (sem carregar classe); (2) gancho no
     * inicio de getEnergy (com valor) e de setEnergy; (3) acrescenta o campo de estado. Qualquer
     * problema lanca PadraoNaoEncontrado antes de a base aceitar a arvore (a classe fica original).
     */
    static void transformar(ClassNode cn, ResolvedorDeGanchos ganchos, ClassLoader carregador) throws PadraoNaoEncontrado {
        for (String[] d : DEPENDENCIAS) {
            byte[] b = HashClasse.bytesDoArquivo(d[0], carregador);
            if (b == null) {
                throw new PadraoNaoEncontrado("dependencia ausente: " + d[0]);
            }
            String h = HashClasse.sha256(b);
            if (!h.equals(d[1])) {
                throw new PadraoNaoEncontrado("trava de dependencia nao confere: " + d[0] + " tem sha256 " + h + ", aceito " + d[1]);
            }
        }
        for (Object o : cn.fields) {
            if (CAMPO.equals(((FieldNode) o).name)) {
                throw new PadraoNaoEncontrado("a classe ja tem o campo " + CAMPO + " (transformada duas vezes?)");
            }
        }
        Asm.ganchoNoInicio(cn, "getEnergy", DESC_LER, Gancho.de(GANCHO, "antesDeLer"), Gancho.de(GANCHO, "valorLido"),
                true, ganchos);
        Asm.ganchoNoInicio(cn, "setEnergy", DESC_ESCREVER, Gancho.de(GANCHO, "antesDeEscrever"), null, true, ganchos);
        cn.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_TRANSIENT | Opcodes.ACC_SYNTHETIC, CAMPO,
                "Ljava/lang/Object;", null, null));
    }

    // ---------------------------------------------------------------- contagem de qualquer thread

    /** Uma chamada de outra thread (sincronizado: varias threads podem contar ao mesmo tempo). */
    public static synchronized void contarOutraThread() {
        foraDaThread++;
    }

    /**
     * Excecao do nosso codigo: o recurso desliga ate reiniciar (todos os ganchos passam a rodar o
     * original na primeira linha) e sai um registro no log (com limitador). Nunca lanca excecao.
     */
    public static synchronized void desligarPorErro(String onde, Throwable t) {
        try {
            erros++;
            modo = DESLIGADO;
            if (desligadoPorErro == null) {
                desligadoPorErro = onde + ": " + t;
            }
            if (LIM_ERRO.permitir("erro")) {
                Log.de(ID).e("erro no caminho rapido da matriz de inducao (" + onde + "); recurso DESLIGADO ate reiniciar;"
                        + " o Mekanism segue pelo caminho original", t);
            }
        } catch (Throwable ignorado) {
            // nunca deixar escapar
        }
    }

    // ---------------------------------------------------------------- servidor de pe

    /**
     * Chamado por hoshiestar.Servidor.iniciando (servidor de pe): registra o subcomando
     * "hoshie mekanism" se o Mekanism esta no servidor (em qualquer modo: em "desligado" ele serve de
     * referencia, lendo a matriz pelo caminho original). A classe do subcomando fica em src/mods/java,
     * por isso a reflexao.
     */
    public static void aoIniciarServidor() {
        if (HashClasse.bytesDoArquivo(CLASSE, HashClasse.carregadorDosMods()) == null) {
            Log.de(ID).i("Mekanism ausente: subcomando 'hoshie mekanism' nao registrado");
            return;
        }
        try {
            Class.forName("hoshiestar.mekanism.matriz.ComandoMatriz", true, Mekanism.class.getClassLoader())
                    .getMethod("registrar").invoke(null);
        } catch (Throwable t) {
            Log.de(ID).a("nao consegui registrar o subcomando 'hoshie mekanism' (" + t + ")");
        }
    }

    /** Linha do "hoshie status" e do fim do boot. */
    public static String linhaDeEstado() {
        StringBuilder sb = new StringBuilder();
        sb.append("modo ").append(nomeDoModo(modo));
        if (desligadoPorErro != null) {
            sb.append(" (DESLIGADO POR ERRO: ").append(desligadoPorErro).append(")");
        }
        sb.append("; leituras rapidas ").append(leiturasRapidas)
                .append(", reconstrucoes ").append(reconstrucoes).append(" (incompletas ").append(incompletas).append(")")
                .append(", leituras no original ").append(leiturasOriginais)
                .append("; escritas rapidas ").append(escritasRapidas)
                .append(", no original ").append(escritasOriginais)
                .append(" (recusadas por identidade ").append(recusasIdentidade).append(")")
                .append("; reentradas ").append(reentradas)
                .append("; outra thread ").append(foraDaThread)
                .append("; sem mundo/antes do 1o tick ").append(semMundoOuAntesDoTick)
                .append("; excecoes de outros mods relancadas ").append(excecoesDeTerceiros);
        if (modo == VERIFICAR || comparacoes > 0 || comparacoesEscrita > 0) {
            sb.append("; verificar: leituras comparadas ").append(comparacoes)
                    .append(", divergencias de identidade ").append(divergenciasIdentidade)
                    .append(", de soma ").append(divergenciasSoma)
                    .append("; escritas comparadas ").append(comparacoesEscrita)
                    .append(", divergencias ").append(divergenciasEscrita);
        }
        sb.append("; erros ").append(erros);
        return sb.toString();
    }

    /** Zera os contadores e o desligamento por erro (so os testes isolados usam). */
    public static void zerarContadores() {
        leiturasRapidas = reconstrucoes = incompletas = leiturasOriginais = 0;
        escritasRapidas = escritasOriginais = recusasIdentidade = reentradas = 0;
        foraDaThread = 0;
        semMundoOuAntesDoTick = excecoesDeTerceiros = 0;
        comparacoes = divergenciasIdentidade = divergenciasSoma = comparacoesEscrita = divergenciasEscrita = 0;
        erros = 0;
        desligadoPorErro = null;
    }
}
