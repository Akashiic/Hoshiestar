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
package hoshiestar.betterquesting;

import hoshiestar.api.HashClasse;
import hoshiestar.api.Limitador;
import hoshiestar.api.Log;
import hoshiestar.api.Recursos;
import hoshiestar.api.Trava;
import hoshiestar.config.HoshiestarConfig;
import hoshiestar.embutidos.Correcao;
import hoshiestar.embutidos.Correcoes;
import hoshiestar.embutidos.asm.Asm;
import hoshiestar.embutidos.asm.Chamada;
import hoshiestar.embutidos.asm.Gancho;
import hoshiestar.embutidos.asm.PadraoNaoEncontrado;
import hoshiestar.embutidos.asm.ResolvedorDeGanchos;
import org.objectweb.asm.tree.ClassNode;

import java.util.Locale;

/**
 * Recurso "betterquesting" (item 1.D): filtro EXATO da checagem de inventario do bq_standard.
 *
 * <p><b>O problema.</b> {@code bq_standard.handlers.EventHandler.onServerTick} (fase START), para cada
 * jogador cujo inventario mudou no tick anterior, chama {@code ITaskInventory.onInventoryChange} em
 * TODAS as tarefas de inventario das quests "ativas" do grupo. No pacote todas as 8.939 quests tem
 * {@code lockedProgress=1}, entao "ativa" = toda quest nao completa: ~7.400 tarefas de retrieval por
 * checagem, cada uma recontando o inventario inteiro (40 espacos por membro online). Custou 2,95% do
 * tick no perfil de producao (o 1,66% restante do evento e o nucleo do BQ, que este recurso nao toca).
 *
 * <p><b>A correcao.</b> Antes de cada uma dessas chamadas, um gancho decide com fatos baratos se a
 * chamada original seria um "nao faz nada" COMPROVADO (regras em
 * {@code hoshiestar.betterquesting.filtro.FiltroInventario}). Se for, pula; na duvida, chama a original
 * com o mesmo receptor e os mesmos argumentos, no mesmo ponto do laco.
 *
 * <p><b>O que muda na classe do mod</b> (so com o modo diferente de "desligado" e as travas
 * conferindo), tudo dentro de {@code onServerTick}:
 * <ol>
 * <li>a UNICA chamada {@code invokeinterface ITaskInventory.onInventoryChange} vira
 * {@code invokestatic GanchoBQ.aoMudarInventario(task, quest, pInfo)} (formato 2 da base);</li>
 * <li>na entrada do metodo entra {@code GanchoBQ.inicioDoTick(this, evento)} (formato 1, sem retorno
 * antecipado): limpa o cache do tick;</li>
 * <li>a UNICA chamada {@code HashSet.clear()} (o {@code playerInventoryUpdates.clear()} do fim do laco)
 * vira {@code GanchoBQ.fimDoLaco(conjunto)}, que faz o mesmo clear() e depois limpa o cache. Uma
 * excecao no meio do laco pula este ponto; por isso o cache tambem e limpo na entrada.</li>
 * </ol>
 * As duas chamadas que o mod Akashic-BQRelief redireciona por Mixin no mesmo metodo
 * ({@code ParticipantInfo.getSharedQuests} e {@code IQuestDatabase.filterKeys}) nao sao tocadas (o
 * teste isolado confere no bytecode).
 *
 * <p>Roda na fase de coremod: so JDK, ASM e hoshiestar.* (nada do Minecraft, Forge ou BQ). O modo e
 * os contadores ficam aqui para a linha de estado e os testes os lerem sem carregar classes do jogo.
 */
public final class BetterQuesting {

    public static final String ID = "betterquesting";

    /** Classe alvo (nome com pontos). */
    public static final String CLASSE = "bq_standard.handlers.EventHandler";

    /** SHA-256 de bq_standard/handlers/EventHandler.class em BetterQuesting-3.8.79-GTNH.jar. */
    public static final String SHA_EVENTOS = "5d72d0c354131ed839acffe744e300411fa3edb1cdf6ff2a5a99cdaeb5e01116";

    /**
     * Classes do BQ de cuja forma EXATA a regra do filtro depende, com o SHA-256 aceito de cada uma
     * (todas de BetterQuesting-3.8.79-GTNH.jar; conferencia C3). Lidas como arquivo (sem carregar) quando
     * a classe alvo passa pelo transformador. Qualquer uma diferente ou ausente: nada e aplicado.
     * <ul>
     * <li>TaskRetrieval e TaskRetrieval$Detector: o que onInventoryChange/detect/Detector/checkAndComplete
     * fazem (so mudam alguma coisa quando um item casa, quando ha progresso parcial a zerar ou quando a
     * conclusao fica satisfeita);</li>
     * <li>TaskBase e TaskProgressableBase: isComplete e getBulkProgress so leem; os campos lidos pelo
     * filtro (completeUsers, userProgress);</li>
     * <li>ItemComparison: StackMatch exige o mesmo Item; OreDictionaryMatch exige que a lista do ore case;</li>
     * <li>BigItemStack e OreIngredient: o ingrediente de ore e a lista viva do Forge ({@code ores});</li>
     * <li>ParticipantInfo: os campos PLAYER, UUID, ALL_UUIDS, ACTIVE_PLAYERS (a mais que a lista da
     * conferencia: o filtro le esses campos diretamente).</li>
     * </ul>
     */
    static final String[][] DEPENDENCIAS = {
        {"bq_standard.tasks.TaskRetrieval", "aed46e60ac45cb3ed85cad35fd6a1280c5259121ec15cdcf8f761a8d9f2642b6"},
        {"bq_standard.tasks.TaskRetrieval$Detector", "ee40ecb6876490368d0417681df00ca044f2b549f1f46c02886dcb6cc7e133c4"},
        {"bq_standard.tasks.base.TaskBase", "b3e53f06e38878843a572b24dd72e386329f6f4ea5acc9ebb1dec419a8863fbe"},
        {"bq_standard.tasks.base.TaskProgressableBase", "d6fa2da434ca21c7e3e2c6164a5a65416f9268149c77160f60455aff49192274"},
        {"betterquesting.api.utils.ItemComparison", "521dc25c2977cde7c9cccd43f5ed7d208c1d278915c15ed29b29a2c542f0949e"},
        {"betterquesting.api.utils.BigItemStack", "2de8c55c349fef0a6f40ee76eec6670746615bc22b337f406c5070fc055e142d"},
        {"betterquesting.api2.utils.OreIngredient", "9ba755adf1674c9eeba9fa44332fe1e499ffd522a8883784798ed4e25d05d51c"},
        {"betterquesting.api2.utils.ParticipantInfo", "01e024694f9a440f56e770f262564c73775a653001b49e3e3f29fa34d70d1847"},
    };

    /** Classe dos ganchos (src/mods/java). */
    public static final String GANCHO = "hoshiestar/betterquesting/filtro/GanchoBQ";

    static final String METODO = "onServerTick";
    static final String DESC_TICK = "(Lcpw/mods/fml/common/gameevent/TickEvent$ServerTickEvent;)V";
    static final Chamada CHAMADA_INVENTARIO = Chamada.de("bq_standard/tasks/ITaskInventory", "onInventoryChange",
            "(Ljava/util/Map$Entry;Lbetterquesting/api2/utils/ParticipantInfo;)V");
    static final Chamada CHAMADA_CLEAR = Chamada.de("java/util/HashSet", "clear", "()V");

    // ---------------------------------------------------------------- modo

    public static final int DESLIGADO = 0;
    public static final int VERIFICAR = 1;
    public static final int LIGADO = 2;

    /**
     * Modo em vigor (lido do hoshiestar.yml no boot). Escrito no boot e por {@link #desligarPorErro}
     * (vira DESLIGADO); os testes isolados tambem trocam. Lido so pela thread do servidor.
     */
    public static volatile int modo = DESLIGADO;

    /** Segundos entre linhas de relatorio no log (0 = nunca). */
    public static int relatorioSegundos = 0;

    /** Motivo do desligamento automatico (primeira excecao nossa), ou null. */
    public static volatile String desligadoPorErro;

    /**
     * A correcao registrada no boot (para a linha de estado dizer se ela foi aplicada). Escrita so em
     * {@link #registrar}; null antes do registro.
     */
    static volatile Correcao correcao;

    // ---------------------------------------------------------------- contadores
    // Indices de contagem[]. 0..2: chamadas puladas (por regra); 3..: chamadas que foram para o original
    // (por motivo). Todos escritos so pela thread do servidor, dentro do synchronized do proprio BQ;
    // lidos pela linha de estado (um valor um pouco atrasado nao faz mal). Publicos para os testes.

    /** Pulada: consume e nao autoConsume (onInventoryChange nao faz nada nesse caso). */
    public static final int PULA_CONSUMO = 0;
    /** Pulada: a tarefa ja esta completa para o jogador (detect sai na primeira linha). */
    public static final int PULA_COMPLETA = 1;
    /** Pulada: nenhum item exigido nos inventarios, nada a zerar, nada a completar. */
    public static final int PULA_SEM_ITEM = 2;
    /** Original: a tarefa nao e exatamente TaskRetrieval (TaskFluid, TaskOptionalRetrieval, de addon...). */
    public static final int PASSA_CLASSE = 3;
    /** Original: lista de itens vazia, item nulo, quantidade &lt;= 0 ou item sem Item. */
    public static final int PASSA_ITEM_INVALIDO = 4;
    /** Original: algum item exigido usa ore dictionary com lista nao vazia (ou ingrediente nulo). */
    public static final int PASSA_ORE = 5;
    /** Original: algum Item exigido esta nos inventarios varridos. */
    public static final int PASSA_PRESENTE = 6;
    /** Original: progresso parcial a zerar (ou groupDetect com progresso diferente de zero). */
    public static final int PASSA_PARCIAL = 7;
    /** Original: o progresso salvo ja satisfaz a conclusao. */
    public static final int PASSA_SATISFEITA = 8;
    /** Original: jogador ou inventario nulo no grupo (o original segue, com o comportamento dele). */
    public static final int PASSA_INVENTARIO = 9;
    /** Original: uuid nulo, progresso de tipo inesperado ou lista de jogadores nula. */
    public static final int PASSA_DADO_ESTRANHO = 10;
    /** Original: a regra 3 esta desligada (o acesso aos campos privados falhou no boot). */
    public static final int PASSA_SEM_REGRA3 = 11;
    /** Original: excecao no nosso codigo de decisao (o recurso desliga). */
    public static final int PASSA_ERRO = 12;
    /** Reconstrucoes do conjunto de Items presentes. */
    public static final int RECONSTRUCOES = 13;
    /** Ticks em que o laco processou pelo menos uma chamada. */
    public static final int TICKS = 14;
    /** Nanossegundos somados do laco (da entrada do onServerTick ao fim do laco) nesses ticks. */
    public static final int NANOS = 15;
    /** Modo verificar: chamadas que o filtro pularia, comparadas com o original. */
    public static final int COMPARADAS = 16;
    /** Modo verificar: dessas, quantas o original mudou alguma coisa (o filtro estaria ERRADO). */
    public static final int DIVERGENCIAS = 17;
    /** Chamadas de outra thread que nao a do servidor (rodaram o original, sem tocar no cache). */
    public static final int OUTRA_THREAD = 18;
    /** Excecoes nossas (cada uma desliga o recurso). */
    public static final int ERROS = 19;
    static final int N_CONTADORES = 20;

    public static final long[] contagem = new long[N_CONTADORES];

    private static final Limitador LIM_ERRO = new Limitador(5, 60000L);

    private BetterQuesting() {
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
        int m = lerModo(cfg.betterquesting_modo);
        int rel = cfg.betterquesting_relatorio;
        if (rel < 0) {
            Log.de(ID).a("betterquesting.relatorio = " + rel + " negativo; usando 0 (nunca)");
            rel = 0;
        }
        modo = m;
        relatorioSegundos = rel;
        boolean ligado = m != DESLIGADO;
        Recursos.registrar(ID, "BetterQuesting: pula as checagens de inventario que comprovadamente nao mudam nada", ligado)
                .estado(new Recursos.Estado() {
                    @Override
                    public String linha() {
                        return linhaDeEstado();
                    }
                });
        Correcao k = Correcao.nova(ID, "filtro exato antes de ITaskInventory.onInventoryChange em onServerTick",
                Trava.de(CLASSE, SHA_EVENTOS), ligado, new Correcao.Transformacao() {
                    @Override
                    public void aplicar(ClassNode classe, ResolvedorDeGanchos ganchos) throws PadraoNaoEncontrado {
                        transformar(classe, ganchos, carregador != null ? carregador : HashClasse.carregadorDosMods());
                    }
                });
        correcao = k;
        correcoes.adicionar(k);
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
            Log.de(ID).a("betterquesting.modo = '" + s + "' nao e desligado/verificar/ligado; usando desligado");
        }
        return DESLIGADO;
    }

    public static String nomeDoModo(int m) {
        return m == LIGADO ? "ligado" : m == VERIFICAR ? "verificar" : "desligado";
    }

    /**
     * A transformacao: (1) confere as travas das dependencias (sem carregar classe); (2) as tres
     * alteracoes em onServerTick. Cada utilitario confere tudo antes de mudar a arvore; qualquer
     * problema lanca PadraoNaoEncontrado e a base devolve os bytes originais (nada fica meio aplicado).
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
        Asm.trocarChamada(cn, METODO, DESC_TICK, CHAMADA_INVENTARIO, 0, 1, Gancho.de(GANCHO, "aoMudarInventario"), ganchos);
        Asm.trocarChamada(cn, METODO, DESC_TICK, CHAMADA_CLEAR, 0, 1, Gancho.de(GANCHO, "fimDoLaco"), ganchos);
        Asm.ganchoNoInicio(cn, METODO, DESC_TICK, Gancho.de(GANCHO, "inicioDoTick"), null, false, ganchos);
    }

    // ---------------------------------------------------------------- erros

    /**
     * Excecao do nosso codigo: o recurso desliga ate reiniciar (os ganchos passam a chamar so o
     * original) e sai um registro no log (com limitador). Nunca lanca excecao.
     */
    public static synchronized void desligarPorErro(String onde, Throwable t) {
        try {
            contagem[ERROS]++;
            modo = DESLIGADO;
            if (desligadoPorErro == null) {
                desligadoPorErro = onde + ": " + t;
            }
            if (LIM_ERRO.permitir("erro")) {
                Log.de(ID).e("erro no filtro de inventario do BetterQuesting (" + onde + "); recurso DESLIGADO ate reiniciar;"
                        + " o BetterQuesting segue pelo caminho original", t);
            }
        } catch (Throwable ignorado) {
            // nunca deixar escapar
        }
    }

    // ---------------------------------------------------------------- estado

    /** Motivo de a regra 3 estar desligada (acesso aos campos privados falhou), ou null. */
    public static volatile String regra3Desligada;

    /** Linha do "hoshie status", do fim do boot e do relatorio periodico. */
    public static String linhaDeEstado() {
        long[] c = contagem;
        long puladas = c[PULA_CONSUMO] + c[PULA_COMPLETA] + c[PULA_SEM_ITEM];
        long passadas = 0;
        for (int i = PASSA_CLASSE; i <= PASSA_ERRO; i++) {
            passadas += c[i];
        }
        StringBuilder sb = new StringBuilder();
        sb.append("modo ").append(nomeDoModo(modo));
        sb.append(textoDaCorrecao(correcao));
        if (desligadoPorErro != null) {
            sb.append(" (DESLIGADO POR ERRO: ").append(desligadoPorErro).append(")");
        }
        if (regra3Desligada != null) {
            sb.append(" (REGRA 3 DESLIGADA: ").append(regra3Desligada).append(")");
        }
        sb.append("; chamadas ").append(puladas + passadas)
                .append(modo == VERIFICAR ? ", que seriam puladas (o original rodou) " : ", puladas ").append(puladas)
                .append(" (consume ").append(c[PULA_CONSUMO])
                .append(", completa ").append(c[PULA_COMPLETA])
                .append(", sem item ").append(c[PULA_SEM_ITEM]).append(")")
                .append(", original ").append(passadas)
                .append(" (classe ").append(c[PASSA_CLASSE])
                .append(", item invalido ").append(c[PASSA_ITEM_INVALIDO])
                .append(", ore ").append(c[PASSA_ORE])
                .append(", item presente ").append(c[PASSA_PRESENTE])
                .append(", parcial ").append(c[PASSA_PARCIAL])
                .append(", satisfeita ").append(c[PASSA_SATISFEITA])
                .append(", inventario nulo ").append(c[PASSA_INVENTARIO])
                .append(", dado estranho ").append(c[PASSA_DADO_ESTRANHO])
                .append(", sem regra 3 ").append(c[PASSA_SEM_REGRA3])
                .append(", erro ").append(c[PASSA_ERRO]).append(")")
                .append("; reconstrucoes ").append(c[RECONSTRUCOES])
                .append("; ticks com checagem ").append(c[TICKS]);
        if (c[TICKS] > 0) {
            sb.append(" (media ").append(c[NANOS] / c[TICKS] / 1000L).append(" us por tick)");
        }
        if (modo == VERIFICAR || c[COMPARADAS] > 0) {
            sb.append("; verificar: comparadas ").append(c[COMPARADAS]).append(", divergencias ").append(c[DIVERGENCIAS]);
        }
        sb.append("; outra thread ").append(c[OUTRA_THREAD]).append("; erros ").append(c[ERROS]);
        return sb.toString();
    }

    /**
     * Com o recurso ligado ou em verificar mas a correcao NAO aplicada (trava, dependencia, gancho
     * ausente, erro) ou ainda aguardando a classe: um aviso de que o filtro nao roda. Aplicada ou
     * desligada no boot: nada (a linha da correcao, a parte, tem os detalhes).
     */
    static String textoDaCorrecao(Correcao k) {
        if (k == null || !k.ligada) {
            return "";
        }
        Correcao.Estado e = k.estado();
        if (e == Correcao.Estado.APLICADA) {
            return " (correcao aplicada)";
        }
        if (e == Correcao.Estado.AGUARDANDO) {
            return " (correcao aguardando a classe do BQ ser carregada: o filtro ainda NAO roda)";
        }
        String d = k.detalhe();
        if (d != null && d.length() > 160) {
            d = d.substring(0, 160) + "...";
        }
        return " (correcao " + e.texto + (d == null ? "" : ": " + d) + "; o filtro NAO roda, o BQ segue original)";
    }

    /** Zera os contadores e o desligamento por erro (so os testes isolados usam). */
    public static void zerarContadores() {
        for (int i = 0; i < contagem.length; i++) {
            contagem[i] = 0;
        }
        desligadoPorErro = null;
    }
}
