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
package hoshiestar.arsmagica;

import hoshiestar.api.Limitador;
import hoshiestar.api.Log;
import hoshiestar.api.Relogio;
import hoshiestar.api.ThreadServidor;

import java.util.Map;

/**
 * A logica de decisao dos atalhos do Ars Magica 2 (recurso 1.F). Nao conhece nenhuma classe do AM2,
 * do Minecraft nem do Forge: recebe o mapa que o gancho leu ({@code Map}) e responde "pode pular?".
 * E o MESMO codigo que roda no servidor e nos testes isolados; o gancho
 * ({@code hoshiestar.arsmagica.atalho.GanchoAM2}, em src/mods/java) so le o mapa do AM2 e delega para ca.
 *
 * <p><b>Regra unica dos dois atalhos.</b> O AM2 guarda, em dois mapas estaticos
 * ({@code acceleratedEntitiesUUIDs} e {@code slowedEntitiesUUIDs}), as entidades aceleradas e as
 * retardadas por feitico. Quando o mapa esta VAZIO, tudo o que o AM2 faria com ele (percorrer entidades,
 * montar texto de UUID, procurar no mapa) nao acha nada e nao muda nada. Entao: se o interruptor esta
 * ligado E o mapa nao e nulo E esta vazio, pula; em qualquer outro caso (interruptor desligado, mapa com
 * uma entrada que seja, mapa nulo, excecao nossa) manda rodar o original.
 *
 * <p><b>Threads.</b> Os dois mapas sao HashMap comuns que so o AMEventHandler le e escreve, na thread
 * do servidor, e os ganchos rodam na mesma thread e no mesmo ponto em que o original leria o mapa: nao
 * ha corrida nova. Para nao depender so dessa premissa, uma chamada de qualquer outra thread (ou antes de
 * a thread do servidor existir) roda sempre o original, sem olhar o mapa. Nada aqui guarda estado que a
 * decisao use (cada chamada decide sozinha, lendo o mapa ao vivo); so os contadores de estatistica, que
 * ficam ligeiramente errados se outra thread chamar ao mesmo tempo (nao muda nenhuma decisao).
 *
 * <p>Caminho quente: sem alocacao. Os interruptores sao lidos ao vivo (campos volatile) para que
 * possam ser trocados em execucao (comando {@code hoshie arsmagica}).
 */
public final class Atalhos {

    public static final String ID = "arsmagica";

    /** Varredura de todos os mundos pulada (mapa de aceleradas vazio). */
    public static final int VARRER_PULOU = 0;
    /** Varredura que rodou o original (mapa com entradas, interruptor desligado, mapa nulo ou erro). */
    public static final int VARRER_ORIGINAL = 1;
    /** Checagem do DiluteTime pulada (mapa de retardadas vazio). */
    public static final int DILUIR_PULOU = 2;
    /** Checagem do DiluteTime que rodou o original. */
    public static final int DILUIR_ORIGINAL = 3;
    /** Excecoes nossas (cada uma desliga o atalho em que aconteceu). */
    public static final int ERROS = 4;
    /** Chamadas de outra thread que nao a do servidor (ou antes de ela existir): rodaram o original. */
    public static final int OUTRA_THREAD = 5;
    /** Total de contadores. */
    public static final int N = 6;

    /**
     * Contadores desde o boot. Escritos pela thread do servidor (um unico escritor na pratica); lidos
     * pelo relatorio e pelo status. Publicos para os testes.
     */
    public static final long[] contagem = new long[N];

    /** Interruptor da varredura (F1) em vigor. Lido ao vivo; escrito no boot, pelo comando e pelos testes. */
    public static volatile boolean varredura;

    /** Interruptor da checagem do DiluteTime (F2) em vigor. */
    public static volatile boolean diluicao;

    /** Minutos entre linhas de estatistica no log (0 = nunca). */
    public static volatile int estatisticaMinutos;

    /** Motivo do desligamento automatico da varredura (primeira excecao nossa nela), ou null. */
    public static volatile String varreduraDesligadaPorErro;

    /** Idem para a checagem do DiluteTime. */
    public static volatile String diluicaoDesligadaPorErro;

    /**
     * Motivo pelo qual a auto-checagem de ao ligar o servidor ({@code ArsMagica.verificarGanchos}) reprovou os
     * ganchos, ou null. Com defeito, os dois atalhos ficam desligados e o comando nao deixa ligar.
     */
    public static volatile String ganchoComDefeito;

    /** Quantas entradas ha em cada mapa do AM2 (so para status e relatorio; quem le o AM2 e o gancho). */
    public interface Mapas {
        int aceleradas();

        int retardadas();
    }

    /** Preenchido pelo gancho quando a classe dele carrega (antes disso, os tamanhos aparecem como "?"). */
    public static volatile Mapas mapas;

    private static final Limitador LIM_ERRO = new Limitador(5, 60000L);

    private Atalhos() {
    }

    // ---------------------------------------------------------------- decisoes (caminho quente)

    /**
     * F1: o AM2 vai chamar {@code DimensionManager.getWorlds()} para varrer todas as entidades de todos
     * os mundos. Devolve true = chame o original; false = pule (o gancho devolve uma lista de mundos vazia).
     *
     * @param aceleradas o mapa {@code AMEventHandler.acceleratedEntitiesUUIDs}, lido AGORA pelo gancho
     */
    public static boolean varrerMundos(Map<?, ?> aceleradas) {
        try {
            if (!ThreadServidor.souAThreadDoServidor()) {
                contagem[OUTRA_THREAD]++;
                return true;
            }
            if (varredura && aceleradas != null && aceleradas.isEmpty()) {
                contagem[VARRER_PULOU]++;
                talvezRelatar();
                return false;
            }
            contagem[VARRER_ORIGINAL]++;
            talvezRelatar();
            return true;
        } catch (Throwable t) {
            erro(true, "varrerMundos", t);
            return true;
        }
    }

    /**
     * F2, primeiro passo: o AM2 vai chamar {@code SkillManager.getSkill("DiluteTime")}. Devolve true =
     * chame o original; false = pule (o gancho devolve null). Cada passo decide sozinho (nao ha estado
     * entre os dois): ver a analise no doc.
     *
     * @param retardadas o mapa {@code AMEventHandler.slowedEntitiesUUIDs}, lido AGORA pelo gancho
     */
    public static boolean consultarSkill(Map<?, ?> retardadas) {
        try {
            if (!ThreadServidor.souAThreadDoServidor()) {
                return true; // outra thread: nem olha o mapa (nao conta: o passo que conta e o seguinte)
            }
            return !(diluicao && retardadas != null && retardadas.isEmpty());
        } catch (Throwable t) {
            erro(false, "consultarSkill", t);
            return true;
        }
    }

    /**
     * F2, segundo passo: o AM2 vai chamar {@code SkillTreeManager.isSkillDisabled(skill)}. Devolve true =
     * chame o original; false = pule (o gancho devolve true, "desativada", que faz o AM2 seguir pelo mesmo
     * desvio de quando a habilidade esta desativada, sem olhar o mapa vazio). E aqui que se conta.
     */
    public static boolean consultarDesativada(Map<?, ?> retardadas) {
        try {
            if (!ThreadServidor.souAThreadDoServidor()) {
                contagem[OUTRA_THREAD]++;
                return true;
            }
            if (diluicao && retardadas != null && retardadas.isEmpty()) {
                contagem[DILUIR_PULOU]++;
                talvezRelatar();
                return false;
            }
            contagem[DILUIR_ORIGINAL]++;
            talvezRelatar();
            return true;
        } catch (Throwable t) {
            erro(false, "consultarDesativada", t);
            return true;
        }
    }

    // ---------------------------------------------------------------- erros

    /**
     * Chamado pelo tratador das pontes (formato "envolver chamada protegida") quando um gancho da varredura
     * (F1) FALHOU: nao ligou (classe ou metodo ausente, descritor diferente) ou lancou. A ponte segue pela
     * chamada original; aqui so se desliga o atalho e se avisa no log (com limite). Nunca lanca.
     * Fica em Atalhos (so JDK) de proposito: nao depende de nenhum tipo do Minecraft nem do AM2.
     */
    public static void falhaVarredura(Throwable t) {
        erro(true, "ponte da varredura (gancho nao ligou ou lancou)", t);
    }

    /** Idem para os dois passos do DiluteTime (F2). */
    public static void falhaDiluicao(Throwable t) {
        erro(false, "ponte do DiluteTime (gancho nao ligou ou lancou)", t);
    }

    /**
     * Excecao do nosso codigo de decisao: desliga o atalho em que ocorreu (o AM2 segue pelo caminho
     * original) e escreve no log, com limitador. Nunca lanca excecao.
     */
    public static synchronized void erro(boolean ehVarredura, String onde, Throwable t) {
        try {
            contagem[ERROS]++;
            String motivo = onde + ": " + t;
            if (ehVarredura) {
                varredura = false;
                if (varreduraDesligadaPorErro == null) {
                    varreduraDesligadaPorErro = motivo;
                }
            } else {
                diluicao = false;
                if (diluicaoDesligadaPorErro == null) {
                    diluicaoDesligadaPorErro = motivo;
                }
            }
            if (LIM_ERRO.permitir("erro")) {
                Log.de(ID).e("erro no atalho do Ars Magica 2 (" + onde + "); atalho '" + (ehVarredura ? "varredura" : "diluicao")
                        + "' DESLIGADO ate reiniciar ou ate 'hoshie arsmagica'; o AM2 segue pelo caminho original", t);
            }
        } catch (Throwable ignorado) {
            // nunca deixar escapar
        }
    }

    // ---------------------------------------------------------------- estatistica

    private static long proximoTick;
    private static long proximoMs;
    /** false ate a primeira chamada que arma o relogio (nanoTime pode ser zero ou negativo: nao serve de marca). */
    private static boolean armado;
    private static final long[] ultimo = new long[N];
    private static volatile Saida saida;

    /** Para onde vai a linha de estatistica (os testes trocam). */
    public interface Saida {
        void linha(String texto);
    }

    private static final Saida LOG_PADRAO = new Saida() {
        @Override
        public void linha(String texto) {
            Log.de(ID).i(texto);
        }
    };

    /** Troca a saida da estatistica (null = o log). So os testes. */
    public static void saidaParaTeste(Saida s) {
        saida = s;
    }

    /**
     * Fonte do relogio em ms (os testes trocam). O padrao e MONOTONICO ({@code System.nanoTime()}): se o
     * relogio do sistema voltar (NTP, ajuste manual) a linha de estatistica nao fica calada ate ele alcancar o
     * prazo antigo.
     */
    public interface RelogioDeParede {
        long ms();
    }

    private static final RelogioDeParede RELOGIO_PADRAO = new RelogioDeParede() {
        @Override
        public long ms() {
            return System.nanoTime() / 1000000L;
        }
    };

    private static volatile RelogioDeParede relogio = RELOGIO_PADRAO;

    public static void relogioParaTeste(RelogioDeParede r) {
        relogio = r == null ? RELOGIO_PADRAO : r;
    }

    /**
     * Barato: uma leitura do relogio de ticks e uma comparacao. A cada 200 ticks (10 s nominais) olha o
     * relogio (monotonico); se ja passaram os minutos configurados, escreve a linha. Roda na thread do
     * servidor, dentro do try dos chamadores.
     */
    private static void talvezRelatar() {
        long t = Relogio.ticks;
        if (t < proximoTick) {
            return;
        }
        proximoTick = t + 200;
        int min = estatisticaMinutos;
        if (min <= 0) {
            return;
        }
        long agora = relogio.ms();
        if (!armado) {
            armado = true;
            proximoMs = agora + min * 60000L; // primeira vez: comeca a contar agora
            System.arraycopy(contagem, 0, ultimo, 0, N);
            return;
        }
        if (agora - proximoMs < 0) { // diferenca, nao comparacao direta: vale mesmo se o contador do relogio der a volta
            return;
        }
        proximoMs = agora + min * 60000L;
        Saida s = saida;
        (s != null ? s : LOG_PADRAO).linha(linhaDeEstatistica(min));
        System.arraycopy(contagem, 0, ultimo, 0, N);
    }

    /** A linha de estatistica: a janela desde a ultima linha e os totais desde o boot. */
    static String linhaDeEstatistica(int minutos) {
        long[] c = contagem;
        StringBuilder sb = new StringBuilder();
        sb.append("estatistica (ultimos ~").append(minutos).append(" min): ");
        if (varredura || c[VARRER_PULOU] + c[VARRER_ORIGINAL] > 0) {
            sb.append("varredura de mundos: puladas ").append(c[VARRER_PULOU] - ultimo[VARRER_PULOU])
                    .append(", rodou o original ").append(c[VARRER_ORIGINAL] - ultimo[VARRER_ORIGINAL]).append("; ");
        }
        if (diluicao || c[DILUIR_PULOU] + c[DILUIR_ORIGINAL] > 0) {
            sb.append("checagem DiluteTime: puladas ").append(c[DILUIR_PULOU] - ultimo[DILUIR_PULOU])
                    .append(", rodou o original ").append(c[DILUIR_ORIGINAL] - ultimo[DILUIR_ORIGINAL]).append("; ");
        }
        sb.append("mapas do AM2 agora: ").append(tamanhos());
        sb.append(" | totais desde o boot: varredura puladas ").append(c[VARRER_PULOU]).append(" / original ").append(c[VARRER_ORIGINAL])
                .append(", DiluteTime puladas ").append(c[DILUIR_PULOU]).append(" / original ").append(c[DILUIR_ORIGINAL])
                .append(", erros ").append(c[ERROS]).append(", outra thread ").append(c[OUTRA_THREAD]);
        return sb.toString();
    }

    /** "aceleradas N, retardadas M" (ou "?" antes de o gancho ter carregado). */
    static String tamanhos() {
        Mapas m = mapas;
        if (m == null) {
            return "aceleradas ?, retardadas ? (gancho ainda nao carregou)";
        }
        try {
            return "aceleradas " + m.aceleradas() + ", retardadas " + m.retardadas();
        } catch (Throwable t) {
            return "aceleradas ?, retardadas ? (" + t + ")";
        }
    }

    /** Parte da linha do "hoshie status": contadores e tamanhos dos mapas. */
    public static String resumoDosContadores() {
        long[] c = contagem;
        return "varredura de mundos: puladas " + c[VARRER_PULOU] + ", original " + c[VARRER_ORIGINAL]
                + "; checagem DiluteTime: puladas " + c[DILUIR_PULOU] + ", original " + c[DILUIR_ORIGINAL]
                + "; mapas do AM2: " + tamanhos() + "; outra thread " + c[OUTRA_THREAD] + "; erros " + c[ERROS];
    }

    /** Zera contadores, erros e a estatistica (so os testes isolados usam). */
    public static void zerarParaTeste() {
        for (int i = 0; i < N; i++) {
            contagem[i] = 0;
            ultimo[i] = 0;
        }
        varreduraDesligadaPorErro = null;
        diluicaoDesligadaPorErro = null;
        ganchoComDefeito = null;
        proximoTick = 0;
        proximoMs = 0;
        armado = false;
    }
}
