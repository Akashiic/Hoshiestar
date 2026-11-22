/*
 * Hoshiestar-Medidor: mede onde, no mapa, vai o tempo de tick.
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
package hoshiestar.medidor;

import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.ForgeChunkManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Estado da medicao e os metodos chamados pelos ganchos.
 *
 * Caminho quente: os ganchos leem {@link #ATIVO} e, se for falso, voltam. Nada mais.
 * {@code ATIVO} so e escrito pela thread principal do servidor (a mesma que roda os
 * ticks), por isso nao precisa ser volatile.
 *
 * Com a medicao ligada, cada tick medido abre um "quadro" numa pilha pequena. Ao fechar,
 * o tempo proprio do quadro (tempo total menos o dos quadros aninhados dentro dele) e
 * somado na {@link Coleta}. Assim, entidade montada (tick do passageiro dentro do tick da
 * montaria) ou qualquer aninhamento nao conta tempo duas vezes.
 *
 * O tick original pode lancar excecao e pular o gancho de fim. O laco do Crucible captura
 * a excecao e remove a entidade (isDead) ou invalida a tile entity. Por isso, ao abrir um
 * quadro novo (ou no inicio do laco de um mundo), os quadros de entidade morta ou de tile
 * entity invalida que ficaram na pilha sao descartados (ver {@link #descartarMortos}).
 * Tambem sao descartados no fechamento de um quadro de baixo e no inicio do proximo tick do
 * servidor. O tempo de um quadro descartado nao e contado.
 *
 * Protecao do servidor enquanto mede: no inicio, calibra o custo de System.nanoTime e de um
 * quadro vazio (recusa medir se nanoTime for lento, salvo "forcar"); durante a medicao, uma
 * valvula compara a mediana do tick do servidor em janelas de 100 ticks com a mediana dos
 * ultimos ticks antes de ligar, e para sozinha (gravando o que ja coletou) se o tick piorar
 * demais ou se o custo estimado do proprio medidor passar do limite.
 */
public final class Medidor {

    private Medidor() {
    }

    public static final String VERSAO = "1.0.4";
    static final Logger LOG = Ganchos.LOG;
    static final String P = "[Hoshiestar-Medidor] ";

    /** Lido em todo tick de tile entity e de entidade. Escrito so pela thread principal. */
    public static boolean ATIVO;

    public static final int VIA_VANILLA = 1;
    public static final int VIA_FIREWALL = 2;

    static final int SEG_PADRAO = 300;
    static final int SEG_MIN = 10;
    static final int SEG_MAX = 1800;
    static final int TICKS_AUTOTESTE = 100;

    // ---- calibracao e valvula de sobrecarga ----
    /** Acima disso (ns por chamada de System.nanoTime) a medicao so liga com "forcar". */
    static final double LIMITE_NANOTIME_NS = 150.0;
    /** Tamanho da janela da valvula e da referencia (ticks do servidor). */
    static final int JANELA = 100;
    /** Valvula: a mediana da janela passou da referencia em mais de 25% E em mais de 15 ms... */
    static final double VALVULA_FATOR = 1.25;
    static final long VALVULA_NS = 15000000L;
    /** ...em tantas janelas seguidas. */
    static final int VALVULA_JANELAS = 2;
    /**
     * Valvula: custo estimado do medidor (quadros x custo calibrado x {@link #VALVULA_CUSTO_FATOR}) acima do limite
     * por tick, numa janela. O limite e {@link #VALVULA_CUSTO_PCT_REF}% da mediana do tick antes de ligar, mas
     * nunca menos que {@link #VALVULA_CUSTO_NS} nem mais que {@link #VALVULA_CUSTO_MAX_NS} (sem referencia: o
     * minimo). Assim, num servidor ja lento (tick de 84 ms na producao) a regra de custo nao para a medicao de pico
     * por uma estimativa de ~3% do tick; a protecao principal continua sendo a regra do tempo de tick.
     */
    static final long VALVULA_CUSTO_NS = 5000000L;
    static final long VALVULA_CUSTO_MAX_NS = 10000000L;
    static final int VALVULA_CUSTO_PCT_REF = 10;
    /**
     * Margem da regra de custo: a calibracao e o melhor caso (cache quente) e nao inclui a parte do gancho
     * (conferencia da thread, caminho ate o handler, descartarMortos, getClass, indiceDim); por isso a regra
     * compara o dobro do custo calibrado com o limite.
     */
    static final double VALVULA_CUSTO_FATOR = 2.0;
    /** Tempo maximo da calibracao do relogio (a que decide a recusa), para relogio lento nao travar o tick. */
    static final long CALIB_MAX_NS = 20000000L;
    /**
     * Tempo maximo da calibracao do quadro (so roda se o relogio passou ou se foi forcado). Mais folga que a do
     * relogio: na primeira medicao depois do boot o JIT ainda nao compilou o caminho do quadro, e com 20 ms o
     * valor as vezes sai 2 a 3 vezes maior que o real (visto fora do servidor), o que faria a valvula de custo
     * parar a medicao sem motivo.
     */
    static final long CALIB_QUADRO_MAX_NS = 30000000L;
    /** Referencia minima (ticks sem medicao vistos antes de ligar) para a valvula de tempo de tick valer. */
    static final int REF_MIN = 20;

    // ---- estado da medicao (so thread principal) ----
    private static Coleta coleta;
    private static Thread threadPrincipal;
    private static long fimPrevistoNano;
    private static long inicioTickNano;
    /**
     * O tick corrente esta sendo contado: verdadeiro do inicio do tick (ou do inicio da medicao, se ela ligou no
     * meio do tick) ate o fim do tick ou a parada da medicao.
     */
    private static boolean contarEsteTick;

    // ---- tempos de tick do servidor (so thread principal) ----
    // Fora da medicao: os ultimos JANELA ticks, para a referencia. Durante: a janela da valvula.
    private static final long[] refTicks = new long[JANELA];
    private static int refN;
    private static final long[] janTicks = new long[JANELA];
    private static int janN;
    private static final long[] ordenar = new long[JANELA];
    private static long sumidouro; // so para o JIT nao apagar o laco da calibracao
    private static final Object MARCA_CALIBRACAO = new Object();

    // ---- pilha de quadros (so thread principal) ----
    private static final int MAX_PROF = 32;
    private static final Object[] qObj = new Object[MAX_PROF];
    private static final Object[] qMundo = new Object[MAX_PROF];
    private static final Class<?>[] qClasse = new Class<?>[MAX_PROF];
    private static final long[] qInicio = new long[MAX_PROF];
    private static final long[] qFilhos = new long[MAX_PROF];
    private static final int[] qTipo = new int[MAX_PROF];
    private static final int[] qDim = new int[MAX_PROF];
    private static final int[] qCx = new int[MAX_PROF];
    private static final int[] qCz = new int[MAX_PROF];
    private static final boolean[] qRede = new boolean[MAX_PROF];
    private static int prof;
    private static int pulados;

    // ---- visivel para outras threads (status, pedidos) ----
    private static volatile Thread threadDeTick;
    /**
     * Id da medicao em andamento (0 = nenhuma). Escrito so pela thread principal; e o que as
     * outras threads leem para saber se ha medicao (ATIVO nao e volatile).
     */
    private static volatile long idMedicao;
    private static long ultimoId;
    // Pedidos vindos de outra thread (RCON, por exemplo). Protegidos por TRAVA: a thread
    // principal consome o pedido e liga a medicao dentro da trava, entao o comando nunca
    // responde "cancelado" para uma medicao que ja comecou.
    private static final Object TRAVA = new Object();
    private static int pedidoSegundos;      // guardado por TRAVA
    private static long pedidoPararId;      // guardado por TRAVA: so para a medicao com este id
    private static boolean pedidoForcar;    // guardado por TRAVA
    private static volatile boolean haPedido;
    private static volatile String ultimoResultado = "nenhuma medicao desde o boot";
    private static volatile String erroInterno;
    private static volatile boolean erroLogado;
    private static volatile String resumoAutoteste;
    private static volatile Thread ultimaGravacao;
    // copia barata do andamento, para status lido de outra thread
    private static volatile String andamento;
    private static volatile String ultimaCalibracao;

    // =====================================================================
    // Ganchos (chamados pelos mixins so quando ATIVO == true)
    // =====================================================================

    /** Inicio do laco de World.func_72939_s: so conta, para o autoteste saber que o mundo tickou. */
    public static void inicioLacoMundo(World mundo) {
        try {
            if (Thread.currentThread() != threadPrincipal || mundo == null) {
                return;
            }
            Coleta c = coleta;
            if (c == null) {
                return;
            }
            if (prof > 0) {
                descartarMortos(c);
            }
            int d = c.indiceDim(mundo);
            int nTe = mundo.field_147482_g != null ? mundo.field_147482_g.size() : 0;
            int nEnt = mundo.field_72996_f != null ? mundo.field_72996_f.size() : 0;
            c.lacosMundo++;
            if (c.ultimoTickComLaco != c.tickSeq) {
                c.ultimoTickComLaco = c.tickSeq;
                c.ticksComLaco++;
            }
            c.teNasListas += nTe;
            c.entNasListas += nEnt;
            c.dimLacos[d]++;
            c.dimTeNasListas[d] += nTe;
            c.dimEntNasListas[d] += nEnt;
        } catch (Throwable t) {
            falha(t);
        }
    }

    public static void inicioTE(World mundo, TileEntity te, int via) {
        try {
            if (Thread.currentThread() != threadPrincipal || te == null) {
                return;
            }
            Coleta c = coleta;
            if (c == null) {
                return;
            }
            if (via == VIA_VANILLA) {
                c.teTicksViaVanilla++;
            } else {
                c.teTicksViaFirewall++;
            }
            if (prof > 0) {
                descartarMortos(c);
                // O laco de UM mundo nao aninha tile entity dentro de tile entity: um quadro TE do
                // mesmo mundo no topo e sobra de uma excecao que pulou o gancho de fim. TE de outro
                // mundo (um mod que, dentro do update de uma TE, roda o laco de outro mundo) e pai
                // legitimo e fica.
                if (prof > 0 && qTipo[prof - 1] == Coleta.TE && qMundo[prof - 1] == mundo) {
                    descartarAte(prof - 1, c);
                }
            }
            empilhar(te, mundo, te.getClass(), Coleta.TE, c.indiceDim(mundo), te.field_145851_c >> 4,
                    te.field_145849_e >> 4, false, c);
        } catch (Throwable t) {
            falha(t);
        }
    }

    public static void fimTE() {
        long agora = System.nanoTime();
        try {
            if (Thread.currentThread() != threadPrincipal) {
                return;
            }
            Coleta c = coleta;
            if (c == null) {
                return;
            }
            if (pulados > 0) {
                pulados--;
                return;
            }
            int i = prof - 1;
            while (i >= 0 && qTipo[i] != Coleta.TE) {
                i--;
            }
            if (i < 0) {
                return;
            }
            descartarAte(i + 1, c);
            fechar(i, agora, c);
        } catch (Throwable t) {
            falha(t);
        }
    }

    public static void inicioEntidade(World mundo, Entity e, boolean forcar) {
        // forcar == false e so contabilidade de chunk (teleporte, troca de dimensao): nao e tick
        if (forcar) {
            abrirEntidade(mundo, e, false);
        }
    }

    /** Tick do jogador pelo caminho da rede (EntityPlayerMP.func_71127_g). */
    public static void inicioJogadorRede(Entity jogador) {
        if (jogador != null) {
            abrirEntidade(jogador.field_70170_p, jogador, true);
        }
    }

    private static void abrirEntidade(World mundo, Entity e, boolean rede) {
        try {
            if (e == null || Thread.currentThread() != threadPrincipal) {
                return;
            }
            Coleta c = coleta;
            if (c == null) {
                return;
            }
            if (prof > 0) {
                // Passageiro tickado de dentro da montaria: o quadro da montaria e o pai legitimo.
                // Fora esse caso, quadros de entidade morta / TE invalida sao sobra de excecao.
                Entity montaria = e.field_70154_o;
                boolean passageiro = montaria != null && montaria.field_70153_n == e && qObj[prof - 1] == montaria;
                if (!passageiro) {
                    descartarMortos(c);
                }
            }
            empilhar(e, mundo, e.getClass(), Coleta.ENT, c.indiceDim(mundo), piso(e.field_70165_t) >> 4,
                    piso(e.field_70161_v) >> 4, rede, c);
        } catch (Throwable t) {
            falha(t);
        }
    }

    public static void fimEntidade(Entity e, boolean forcar) {
        long agora = System.nanoTime();
        try {
            if (!forcar || e == null || Thread.currentThread() != threadPrincipal) {
                return;
            }
            Coleta c = coleta;
            if (c == null) {
                return;
            }
            if (pulados > 0) {
                pulados--;
                return;
            }
            int i = prof - 1;
            while (i >= 0 && !(qObj[i] == e && qTipo[i] == Coleta.ENT)) {
                i--;
            }
            if (i < 0) {
                return;
            }
            descartarAte(i + 1, c);
            fechar(i, agora, c);
        } catch (Throwable t) {
            falha(t);
        }
    }

    /** Abre um quadro. O relogio e lido por ultimo, para o custo do proprio quadro ficar o mais fora possivel. */
    private static void empilhar(Object o, Object mundo, Class<?> cls, int tipo, int d, int cx, int cz, boolean rede,
            Coleta c) {
        if (prof >= MAX_PROF) {
            pulados++;
            c.quadrosPulados++;
            return;
        }
        int i = prof++;
        qRede[i] = rede;
        qObj[i] = o;
        qMundo[i] = mundo;
        qClasse[i] = cls;
        qTipo[i] = tipo;
        qDim[i] = d;
        qCx[i] = cx;
        qCz[i] = cz;
        qFilhos[i] = 0L;
        qInicio[i] = System.nanoTime();
    }

    private static int piso(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    private static void fechar(int i, long agora, Coleta c) {
        long total = agora - qInicio[i];
        if (total < 0L) {
            total = 0L;
        }
        long proprio = total - qFilhos[i];
        if (proprio < 0L) {
            proprio = 0L;
        }
        Class<?> cls = qClasse[i];
        qObj[i] = null;
        qMundo[i] = null;
        qClasse[i] = null;
        prof = i;
        if (i > 0) {
            qFilhos[i - 1] += total;
        }
        c.adicionar(qTipo[i], qDim[i], qCx[i], qCz[i], cls, proprio, qRede[i]);
    }

    /**
     * Descarta, a partir do quadro mais baixo que for sobra de excecao, todos os quadros
     * acima dele. Sobra de excecao = quadro de entidade que ja esta morta (o laco do Crucible
     * remove a entidade que lancou) ou de tile entity invalida (o laco invalida a TE que
     * lancou). Os quadros acima desse estavam aninhados nele e tambem nunca vao fechar.
     * Custa uma passada pela pilha, que quase sempre tem 0 ou 1 quadro.
     */
    private static void descartarMortos(Coleta c) {
        for (int i = 0; i < prof; i++) {
            Object o = qObj[i];
            boolean morto;
            if (qTipo[i] == Coleta.TE) {
                morto = o instanceof TileEntity && ((TileEntity) o).func_145837_r();
            } else {
                morto = o instanceof Entity && ((Entity) o).field_70128_L;
            }
            if (morto) {
                descartarAte(i, c);
                return;
            }
        }
    }

    /** Fecha sem contar os quadros de indice >= nova profundidade. */
    private static void descartarAte(int novaProf, Coleta c) {
        while (prof > novaProf) {
            prof--;
            qObj[prof] = null;
            qMundo[prof] = null;
            qClasse[prof] = null;
            if (c != null) {
                c.quadrosDescartados++;
            }
        }
    }

    private static void limparPilha() {
        for (int i = 0; i < MAX_PROF; i++) {
            qObj[i] = null;
            qMundo[i] = null;
            qClasse[i] = null;
        }
        prof = 0;
        pulados = 0;
    }

    /**
     * Erro no NOSSO codigo: desliga a medicao, registra uma vez e deixa o tick seguir.
     * Os dados da medicao interrompida sao descartados (podem estar inconsistentes).
     */
    public static void falha(Throwable t) {
        ATIVO = false;
        idMedicao = 0L;
        coleta = null;
        try {
            limparPilha();
        } catch (Throwable ignorado) {
            // nada
        }
        erroInterno = String.valueOf(t);
        ultimoResultado = "medicao desligada por erro interno do medidor (dados descartados): " + t;
        andamento = null;
        if (!erroLogado) {
            erroLogado = true;
            try {
                LOG.error(P + "Erro interno; a medicao foi desligada e o servidor segue normal. "
                        + "Nenhum arquivo foi gravado.", t);
            } catch (Throwable ignorado) {
                // nada
            }
        }
    }

    // =====================================================================
    // Ciclo de vida (thread principal)
    // =====================================================================

    /** Inicio de cada tick do servidor (evento do FML, thread principal). */
    static void inicioTickServidor() {
        Thread atual = Thread.currentThread();
        if (threadDeTick != atual) {
            threadDeTick = atual;
        }
        if (haPedido) {
            synchronized (TRAVA) {
                int pedido = pedidoSegundos;
                if (pedido != 0) {
                    pedidoSegundos = 0;
                    iniciar(pedido, pedidoForcar, null);
                    pedidoForcar = false;
                }
                haPedido = pedidoSegundos != 0 || pedidoPararId != 0L;
            }
        }
        // o relogio do tick do servidor corre sempre (2 leituras por tick do servidor, fora do
        // caminho quente): fora da medicao alimenta a referencia da valvula
        inicioTickNano = System.nanoTime();
        contarEsteTick = true;
        if (!ATIVO) {
            return;
        }
        // quadros que sobraram do tick anterior sao lixo de excecao
        if (prof != 0 || pulados != 0) {
            Coleta c = coleta;
            if (c != null) {
                c.quadrosDescartados += prof;
            }
            limparPilha();
        }
        Coleta c = coleta;
        if (c != null) {
            c.tickSeq++;
        }
    }

    /** Fim de cada tick do servidor (evento do FML, thread principal). */
    static void fimTickServidor() {
        if (haPedido) {
            long alvo;
            synchronized (TRAVA) {
                alvo = pedidoPararId;
                pedidoPararId = 0L;
                haPedido = pedidoSegundos != 0;
            }
            // o pedido vale so para a medicao que estava em andamento quando foi feito
            if (alvo != 0L && ATIVO && alvo == idMedicao) {
                parar("comando 'hoshie parar'");
                return;
            }
        }
        long agora = System.nanoTime();
        boolean contar = contarEsteTick;
        contarEsteTick = false;
        if (!ATIVO) {
            if (contar) {
                refTicks[refN % JANELA] = agora - inicioTickNano;
                if (++refN >= 2 * JANELA) {
                    refN = JANELA; // so os ultimos JANELA importam; evita estouro
                }
            }
            return;
        }
        Coleta c = coleta;
        if (c == null) {
            return;
        }
        if (contar) {
            long dur = agora - inicioTickNano;
            c.ticksServidor++;
            c.nsTickServidor += dur;
            janTicks[janN++] = dur;
            if (janN == JANELA) {
                janN = 0;
                String motivo = avaliarJanela(c);
                if (motivo != null) {
                    c.valvulaDisparou = true;
                    c.motivoValvula = motivo;
                    LOG.warn(P + "Medicao parada pela valvula de sobrecarga: " + motivo
                            + ". O que ja foi coletado vai ser gravado.");
                    parar("valvula de sobrecarga (" + motivo + ")");
                    return;
                }
            }
        }
        if (c.ticksServidor == TICKS_AUTOTESTE && c.autotesteInicial == null) {
            c.autotesteInicial = autoteste(c, false);
        }
        if ((c.ticksServidor & 31) == 0) {
            andamento = textoAndamento(c, agora);
        }
        if (agora - fimPrevistoNano >= 0L) {
            parar("tempo pedido esgotado");
        }
    }

    private static long mediana(long[] origem, int n) {
        if (n <= 0) {
            return 0L;
        }
        System.arraycopy(origem, 0, ordenar, 0, n);
        Arrays.sort(ordenar, 0, n);
        return (n & 1) == 1 ? ordenar[n / 2] : (ordenar[n / 2 - 1] + ordenar[n / 2]) / 2;
    }

    /**
     * Fecha uma janela de {@link #JANELA} ticks medidos. Devolve o motivo para parar, ou null.
     * Duas regras: (1) custo estimado do medidor (quadros da janela x custo calibrado de um quadro),
     * vezes a margem {@link #VALVULA_CUSTO_FATOR}, acima do limite da medicao ({@link #limiteCusto}) por tick; (2) mediana do tick do servidor acima da
     * referencia (mediana antes de ligar) em mais de 25% e de 15 ms, em {@link #VALVULA_JANELAS}
     * janelas seguidas. Mediana, e nao media, para um tick isolado lento (salvamento, GC) nao disparar.
     */
    private static String avaliarJanela(Coleta c) {
        long med = mediana(janTicks, JANELA);
        long quadros = c.teTicksTotal + c.entTicksTotal + c.quadrosDescartados;
        long naJanela = quadros - c.quadrosAteUltimaJanela;
        c.quadrosAteUltimaJanela = quadros;
        long custo = (long) (naJanela * c.calibQuadroNs / JANELA);
        c.janelas++;
        c.ultimaMedianaJanelaNs = med;
        c.ultimoCustoEstNsPorTick = custo;
        if (med > c.maiorMedianaJanelaNs) {
            c.maiorMedianaJanelaNs = med;
        }
        if (custo > c.maiorCustoEstNsPorTick) {
            c.maiorCustoEstNsPorTick = custo;
        }
        if (custo * VALVULA_CUSTO_FATOR > c.limiteCustoNs) {
            return String.format(Locale.ROOT, "custo estimado do medidor %.1f ms por tick, %.1f ms com a margem de %.0fx"
                    + " (limite %.1f ms)", custo / 1e6, custo * VALVULA_CUSTO_FATOR / 1e6, VALVULA_CUSTO_FATOR,
                    c.limiteCustoNs / 1e6);
        }
        long ref = c.refMedianaNs;
        if (ref > 0L && med > ref * VALVULA_FATOR && med - ref > VALVULA_NS) {
            c.janelasAcima++;
            if (c.janelasAcima >= VALVULA_JANELAS) {
                return String.format(Locale.ROOT,
                        "mediana do tick %.1f ms contra %.1f ms antes de ligar, em %d janelas seguidas de %d ticks",
                        med / 1e6, ref / 1e6, c.janelasAcima, JANELA);
            }
        } else {
            c.janelasAcima = 0;
        }
        return null;
    }

    /** Limite da regra de custo: {@link #VALVULA_CUSTO_PCT_REF}% da referencia, entre o minimo e o maximo. */
    static long limiteCusto(long refMedianaNs) {
        long l = refMedianaNs / 100L * VALVULA_CUSTO_PCT_REF;
        return Math.min(VALVULA_CUSTO_MAX_NS, Math.max(VALVULA_CUSTO_NS, l));
    }

    /**
     * Custo de uma chamada de System.nanoTime (ns), o menor de varias rodadas curtas (as primeiras aquecem o
     * JIT). Para em {@link #CALIB_MAX_NS} no total: o tempo e conferido a cada rodada de 1.000 leituras, entao
     * relogio lento passa no maximo uma rodada do limite. Se as 2 primeiras rodadas ja derem mais de 3x
     * {@link #LIMITE_NANOTIME_NS}, para ali (o relogio e lento com certeza; o valor so serve para o aviso).
     */
    private static double calibrarNanoTime() {
        final int n = 1000;
        double melhor = Double.MAX_VALUE;
        long lixo = 0L;
        final long inicio = System.nanoTime();
        for (int r = 0; r < 200; r++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                lixo += System.nanoTime();
            }
            long t1 = System.nanoTime();
            melhor = Math.min(melhor, (t1 - t0) / (double) n);
            // relogio claramente lento (mais de 3x o limite ja nas 2 primeiras rodadas): nao precisa de mais rodadas
            if (t1 - inicio >= CALIB_MAX_NS || (r >= 1 && melhor > 3 * LIMITE_NANOTIME_NS)) {
                break;
            }
        }
        sumidouro += lixo;
        return melhor;
    }

    /**
     * Custo de um quadro vazio, pelo mesmo caminho dos ganchos (abrir, ler o relogio, fechar e somar
     * nas tabelas), numa Coleta de rascunho que e jogada fora. Devolve {custo total por quadro,
     * parte que cai DENTRO do tempo medido de cada quadro (artefato)}, o menor de varias rodadas.
     * Nao toca em objeto nenhum do jogo.
     */
    private static double[] calibrarQuadro() {
        final int n = 500;
        Coleta r = new Coleta(0);
        int d = r.indiceDim(null);
        double melhorTotal = Double.MAX_VALUE;
        double melhorDentro = Double.MAX_VALUE;
        final long inicio = System.nanoTime();
        for (int k = 0; k < 200; k++) {
            long medidoAntes = r.teNsTotal;
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                empilhar(MARCA_CALIBRACAO, null, Medidor.class, Coleta.TE, d, 0, 0, false, r);
                long agora = System.nanoTime();
                fechar(prof - 1, agora, r);
            }
            long t1 = System.nanoTime();
            melhorTotal = Math.min(melhorTotal, (t1 - t0) / (double) n);
            melhorDentro = Math.min(melhorDentro, (r.teNsTotal - medidoAntes) / (double) n);
            if (t1 - inicio >= CALIB_QUADRO_MAX_NS) {
                break;
            }
        }
        limparPilha();
        return new double[] {melhorTotal, melhorDentro};
    }

    /**
     * Comeca uma medicao. Deve rodar na thread principal.
     *
     * @param resposta onde mandar mensagens extras (null = so o log)
     * @return texto para quem pediu, ou null se o inicio ja foi registrado no log
     */
    static String iniciar(int segundos, boolean forcar, List<String> resposta) {
        if (ATIVO) {
            return "Ja existe uma medicao em andamento. Use 'hoshie status' ou 'hoshie parar'.";
        }
        limparPilha();
        // Calibracao (uma vez, na thread principal; relogio ate CALIB_MAX_NS, quadro ate CALIB_QUADRO_MAX_NS, e a
        // parte do quadro so roda se o relogio passar ou se for forcado). O tempo dela fica fora da conta.
        double nano = calibrarNanoTime();
        if (nano > LIMITE_NANOTIME_NS && !forcar) {
            ultimaCalibracao = String.format(Locale.ROOT, "nanoTime %.0f ns por chamada (quadro nao calibrado:"
                    + " recusado antes)", nano);
            String msg = String.format(Locale.ROOT, "Medicao RECUSADA: System.nanoTime custa %.0f ns por chamada nesta"
                    + " maquina (limite %.0f ns); medir assim pesaria demais no tick. Confira a fonte de relogio em"
                    + " /sys/devices/system/clocksource/clocksource0/current_clocksource (tsc ou kvm-clock sao"
                    + " rapidas). Para medir mesmo assim: 'hoshie medir %d forcar' (a valvula de sobrecarga continua"
                    + " valendo).", nano, LIMITE_NANOTIME_NS, segundos);
            ultimoResultado = msg;
            if (resposta == null) {
                LOG.warn(P + msg);
            }
            return msg;
        }
        double[] quadro = calibrarQuadro();
        ultimaCalibracao = String.format(Locale.ROOT, "nanoTime %.0f ns por chamada; quadro vazio %.0f ns (%.0f ns dentro"
                + " do tempo medido)", nano, quadro[0], quadro[1]);
        Coleta c = new Coleta(segundos);
        c.calibNanoNs = nano;
        c.calibQuadroNs = quadro[0];
        c.calibArtefatoNs = quadro[1];
        c.forcado = forcar && nano > LIMITE_NANOTIME_NS;
        int nRef = Math.min(refN, JANELA);
        c.refAmostras = nRef;
        c.refMedianaNs = nRef >= REF_MIN ? mediana(refTicks, nRef) : 0L;
        c.limiteCustoNs = limiteCusto(c.refMedianaNs);
        refN = 0;
        janN = 0;
        threadPrincipal = Thread.currentThread();
        coleta = c;
        long agora = System.nanoTime();
        c.inicioNano = agora;
        c.inicioMs = System.currentTimeMillis();
        fimPrevistoNano = agora + segundos * 1000000000L;
        // O tick corrente conta so do inicio da medicao (depois da calibracao) ao fim do tick: todo quadro medido
        // neste tick cai dentro desse trecho, e a parte medida nunca passa de 100% do tick contado.
        inicioTickNano = agora;
        contarEsteTick = true;
        erroInterno = null;
        erroLogado = false;
        resumoAutoteste = null;
        andamento = textoAndamento(c, agora);
        idMedicao = ++ultimoId;
        ATIVO = true;
        String linha = P + "Medicao iniciada por " + segundos + " s (para sozinha). Ganchos: tile entities="
                + Ganchos.resumoTE() + ", entidades=" + (Ganchos.entidadesOk() ? "sim" : "NAO")
                + ", jogadores pela rede=" + (Ganchos.jogadoresOk() ? "sim" : "NAO") + ". Calibracao: " + ultimaCalibracao
                + (c.forcado ? " (ACIMA DO LIMITE, forcado)" : "") + ". Tick antes de ligar: "
                + (c.refMedianaNs > 0 ? String.format(Locale.ROOT, "mediana %.1f ms em %d ticks", c.refMedianaNs / 1e6, nRef)
                        : "sem referencia (poucos ticks vistos); a valvula usa so o custo estimado")
                + String.format(Locale.ROOT, "; limite do custo estimado %.1f ms por tick", c.limiteCustoNs / 1e6)
                + ".";
        LOG.info(linha);
        if (resposta != null) {
            resposta.add(linha.substring(P.length()));
        }
        // o trecho contado do tick corrente comeca depois da linha do log (que e do medidor, nao do tick)
        inicioTickNano = System.nanoTime();
        return null;
    }

    /** Para a medicao e entrega os dados a uma thread de gravacao. Thread principal. */
    static Thread parar(String motivo) {
        if (!ATIVO) {
            return null;
        }
        ATIVO = false;
        idMedicao = 0L;
        Coleta c = coleta;
        coleta = null;
        limparPilha();
        andamento = null;
        long agoraParar = System.nanoTime();
        if (contarEsteTick && c != null) {
            // Parada no meio de um tick (comando): os quadros deste tick ja estao nos totais, entao o trecho
            // do tick ate aqui tambem conta (fora da janela da valvula, que nao avalia mais).
            c.ticksServidor++;
            c.nsTickServidor += Math.max(0L, agoraParar - inicioTickNano);
        }
        contarEsteTick = false; // tick pela metade, com parte medida: nao vai para a referencia
        if (c == null) {
            return null;
        }
        c.fimNano = agoraParar;
        c.fimMs = System.currentTimeMillis();
        c.motivoParada = motivo;
        // Sempre refeito no fim: o veredito do tick 100 pode ter sido tirado com o servidor
        // ocioso (ou num inicio atipico); o relatorio usa o da medicao inteira.
        try {
            c.autotesteFinal = autoteste(c, true);
        } catch (Throwable t) {
            LOG.warn(P + "Autoteste final falhou: " + t);
        }
        c.soltarMundo();
        File pasta = pastaDeSaida();
        ultimoResultado = "medicao encerrada (" + motivo + "); gravando arquivos...";
        Thread t = new Thread(new Relatorio(c, pasta), "Hoshiestar-Medidor-gravacao");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        ultimaGravacao = t;
        t.start();
        return t;
    }

    /** Pasta de saida: &lt;pasta do servidor&gt;/hoshiestar (pergunta ao proprio servidor; thread principal). */
    private static File pastaDeSaida() {
        try {
            MinecraftServer s = MinecraftServer.func_71276_C();
            if (s != null) {
                File f = s.func_71209_f("hoshiestar");
                if (f != null) {
                    return f.getAbsoluteFile().toPath().normalize().toFile();
                }
            }
        } catch (Throwable t) {
            // cai no diretorio de trabalho
        }
        return new File("hoshiestar").getAbsoluteFile();
    }

    /** Desligamento do servidor: encerra e espera a gravacao um pouco (a JVM vai sair). */
    static void pararNoDesligamento() {
        try {
            Thread t = parar("desligamento do servidor");
            if (t == null) {
                t = ultimaGravacao;
            }
            if (t != null && t.isAlive()) {
                t.join(20000L);
            }
        } catch (Throwable t) {
            LOG.warn(P + "Falha ao encerrar a medicao no desligamento: " + t);
        }
    }

    /**
     * Autoteste: confere se os ganchos estao vivos. Se ha tile entities nas listas dos mundos
     * cujo laco rodou e nenhum tick de tile entity foi medido, o gancho nao esta ligado; isso
     * e dito no log, no status e nos arquivos, em vez de mostrar zero como se fosse dado.
     * Roda no tick {@link #TICKS_AUTOTESTE} (aviso cedo) e de novo no fim (vale para o relatorio).
     * Tambem confere dimensao por dimensao (um mundo de mod com laco proprio ficaria sem medicao
     * sem que o total denunciasse).
     */
    private static Coleta.Autoteste autoteste(Coleta c, boolean fim) {
        Coleta.Autoteste a = new Coleta.Autoteste();
        int tes = 0;
        int ents = 0;
        try {
            WorldServer[] mundos = DimensionManager.getWorlds();
            if (mundos != null) {
                for (WorldServer w : mundos) {
                    if (w == null) {
                        continue;
                    }
                    int nTe = w.field_147482_g != null ? w.field_147482_g.size() : 0;
                    int nEnt = w.field_72996_f != null ? w.field_72996_f.size() : 0;
                    tes += nTe;
                    ents += nEnt;
                    if (Ganchos.lacoMundo) {
                        conferirDimensao(c, w, nTe, nEnt, a);
                    }
                }
            }
        } catch (Throwable t) {
            tes = -1;
            ents = -1;
        }
        a.teCarregados = tes;
        a.entCarregadas = ents;
        a.noTick = c.ticksServidor;
        a.lacos = c.lacosMundo;
        a.teTicks = c.teTicksTotal;
        a.entTicks = c.entTicksTotal;
        a.teViaVanilla = c.teTicksViaVanilla;
        a.teViaFirewall = c.teTicksViaFirewall;
        a.ticksComLaco = c.ticksComLaco;
        a.tickSeq = c.tickSeq;
        // So da para cobrar ticks medidos de mundos cujo laco rodou: um WorldServer sem
        // jogador (e sem chunk forcado) ha 1200 ticks pula o laco inteiro (normal do jogo).
        boolean sabeLaco = Ganchos.lacoMundo;
        long teQueDeviamTickar = sabeLaco ? c.teNasListas : tes;
        long entQueDeviamTickar = sabeLaco ? c.entNasListas : ents;
        if (c.teTicksTotal > 0) {
            a.te = "ok";
        } else if (sabeLaco && c.lacosMundo == 0) {
            a.te = "sem_dados_nenhum_mundo_tickou";
        } else if (teQueDeviamTickar == 0) {
            a.te = "sem_tile_entities_carregados";
        } else {
            a.te = "NAO_LIGADO";
        }
        if (c.entTicksTotal > 0) {
            a.ent = "ok";
        } else if (sabeLaco && c.lacosMundo == 0) {
            a.ent = "sem_dados_nenhum_mundo_tickou";
        } else if (entQueDeviamTickar == 0) {
            a.ent = "sem_entidades_carregadas";
        } else {
            a.ent = "NAO_LIGADO";
        }
        String gancho = a.teViaFirewall > 0 && a.teViaVanilla > 0 ? "vanilla e firewall"
                : a.teViaFirewall > 0 ? "firewall (Akashic-World-Firewall)"
                : a.teViaVanilla > 0 ? "vanilla" : "nenhum";
        String r = "autoteste " + (fim ? "final" : "inicial") + " em " + c.ticksServidor + " ticks: tile entities " + a.te
                + " (gancho vivo: " + gancho + "; " + c.teTicksTotal + " ticks medidos; " + tes + " carregados), entidades "
                + a.ent + " (" + c.entTicksTotal + " ticks medidos; " + ents + " carregadas; " + c.ticksJogadorRede
                + " de jogador pela rede); lacos de mundo que rodaram: "
                + (Ganchos.lacoMundo ? c.lacosMundo + " (em " + c.ticksComLaco + " de " + c.tickSeq + " ticks)"
                        : "desconhecido")
                + (a.dimsSemMedicao.isEmpty() ? "" : "; dimensoes com problema: " + a.dimsSemMedicao.size());
        resumoAutoteste = r;
        // Tudo certo: nada no console (a especificacao pede so as linhas de inicio e de fim); o
        // resultado fica no 'hoshie status', na linha de fim e nos arquivos. Problema: aviso no console.
        if ("NAO_LIGADO".equals(a.te) || "NAO_LIGADO".equals(a.ent) || !a.dimsSemMedicao.isEmpty()) {
            a.problema = true;
            LOG.warn(P + "ATENCAO: " + r + ". O que estiver NAO_LIGADO nao esta sendo medido; os zeros desta parte"
                    + " nao sao dado.");
            for (String d : a.dimsSemMedicao) {
                LOG.warn(P + "  " + d);
            }
        }
        return a;
    }

    /** Confere uma dimensao: devia tickar e o gancho nao viu? Ou o laco rodou e nada foi medido? */
    private static void conferirDimensao(Coleta c, WorldServer w, int nTe, int nEnt, Coleta.Autoteste a) {
        int d = c.indiceDim(w);
        String dim = w.field_73011_w != null ? Integer.toString(w.field_73011_w.field_76574_g) : "?";
        if (c.dimLacos[d] == 0L) {
            boolean temJogador = w.field_73010_i != null && !w.field_73010_i.isEmpty();
            boolean temForcado = false;
            try {
                temForcado = !ForgeChunkManager.getPersistentChunksFor(w).isEmpty();
            } catch (Throwable t) {
                // so diagnostico
            }
            if ((temJogador || temForcado) && (nTe > 0 || nEnt > 0)) {
                a.dimsSemMedicao.add("dimensao " + dim + ": tem " + (temJogador ? "jogador" : "chunk forcado") + " e " + nTe
                        + " tile entities / " + nEnt + " entidades carregados, mas o laco do mundo nunca passou pelo"
                        + " gancho (mundo com laco proprio?): esta dimensao pode NAO estar medida");
            }
            return;
        }
        if (c.dimTeNasListas[d] > 0L && c.dimTeTicks[d] == 0L) {
            a.dimsSemMedicao.add("dimensao " + dim + ": o laco rodou " + c.dimLacos[d]
                    + " vezes com tile entities na lista e nenhum tick de tile entity foi medido");
        }
        if (c.dimEntNasListas[d] > 0L && c.dimEntTicks[d] == 0L) {
            a.dimsSemMedicao.add("dimensao " + dim + ": o laco rodou " + c.dimLacos[d]
                    + " vezes com entidades na lista e nenhum tick de entidade foi medido");
        }
    }

    private static String textoAndamento(Coleta c, long agora) {
        long dec = Math.max(0L, (agora - c.inicioNano) / 1000000000L);
        String valvula = c.janelas == 0
                ? String.format(Locale.ROOT, "valvula: primeira janela de %d ticks ainda nao fechou (limite do custo"
                        + " estimado nesta medicao: %.1f ms por tick, comparado com o custo x%.0f)", JANELA,
                        c.limiteCustoNs / 1e6, VALVULA_CUSTO_FATOR)
                : String.format(Locale.ROOT, "valvula: mediana do tick na ultima janela %.1f ms (antes de ligar: %s),"
                        + " custo estimado do medidor %.2f ms por tick (x%.0f = %.2f ms; limite %.1f ms)",
                        c.ultimaMedianaJanelaNs / 1e6,
                        c.refMedianaNs > 0 ? String.format(Locale.ROOT, "%.1f ms", c.refMedianaNs / 1e6) : "sem referencia",
                        c.ultimoCustoEstNsPorTick / 1e6, VALVULA_CUSTO_FATOR,
                        c.ultimoCustoEstNsPorTick * VALVULA_CUSTO_FATOR / 1e6, c.limiteCustoNs / 1e6);
        return String.format(Locale.ROOT,
                "Medicao em andamento: %d de %d s, %d ticks do servidor; ticks medidos: %d de tile entity"
                        + " (vanilla %d, firewall %d), %d de entidade; %s.",
                dec, c.segundosPedidos, c.ticksServidor, c.teTicksTotal, c.teTicksViaVanilla, c.teTicksViaFirewall,
                c.entTicksTotal, valvula);
    }

    // =====================================================================
    // Comando (pode vir de qualquer thread)
    // =====================================================================

    static boolean naThreadDeTick() {
        return Thread.currentThread() == threadDeTick;
    }

    static List<String> comandoMedir(int segundos, boolean forcar) {
        List<String> r = new ArrayList<String>();
        synchronized (TRAVA) {
            if (idMedicao != 0L || pedidoSegundos != 0) {
                r.add("Ja existe uma medicao em andamento. Use 'hoshie status' ou 'hoshie parar'.");
                return r;
            }
            if (naThreadDeTick()) {
                String msg = iniciar(segundos, forcar, r);
                if (msg != null) {
                    r.add(msg);
                }
            } else {
                pedidoSegundos = segundos;
                pedidoForcar = forcar;
                haPedido = true;
                r.add("Pedido registrado: a medicao de " + segundos + " s comeca no proximo tick do servidor"
                        + " (se a calibracao recusar, o motivo sai no console e em 'hoshie status').");
            }
        }
        return r;
    }

    static List<String> comandoParar() {
        List<String> r = new ArrayList<String>();
        synchronized (TRAVA) {
            if (pedidoSegundos != 0) {
                // a thread principal ainda nao consumiu o pedido (ela consome dentro da trava)
                pedidoSegundos = 0;
                pedidoForcar = false;
                haPedido = pedidoPararId != 0L;
                r.add("Pedido de medicao cancelado antes de comecar.");
                return r;
            }
            long id = idMedicao;
            if (id == 0L) {
                r.add("Nenhuma medicao em andamento.");
                return r;
            }
            if (naThreadDeTick()) {
                parar("comando 'hoshie parar'");
                r.add("Medicao encerrada. Os arquivos estao sendo gravados; o caminho sai no console.");
            } else {
                pedidoPararId = id;
                haPedido = true;
                r.add("Pedido registrado: a medicao para no fim do tick corrente.");
            }
        }
        return r;
    }

    static List<String> comandoStatus() {
        List<String> r = new ArrayList<String>();
        r.add("Hoshiestar-Medidor " + VERSAO + " (so leitura; desligado por padrao).");
        r.add("Ganchos no carregamento: tile entities=" + Ganchos.resumoTE()
                + (Ganchos.firewallInstalado ? " (Akashic-World-Firewall instalado)" : " (Akashic-World-Firewall ausente)")
                + ", entidades=" + (Ganchos.entidadesOk() ? "sim" : "NAO") + ", jogadores pela rede="
                + (Ganchos.jogadoresOk() ? "sim" : "NAO") + ".");
        if ("NENHUM".equals(Ganchos.resumoTE())) {
            r.add("ATENCAO: o tempo de tile entities NAO esta ligado. Uma medicao mostraria zero que nao e dado.");
        }
        String metades = Ganchos.metades();
        if (metades != null) {
            r.add("ATENCAO: gancho pela metade (" + metades + "). Esse par conta como NAO ligado.");
        }
        String and = andamento;
        boolean pedida;
        synchronized (TRAVA) {
            pedida = pedidoSegundos != 0;
        }
        if (idMedicao != 0L && and != null) {
            r.add(and);
        } else if (pedida) {
            r.add("Medicao pedida, comeca no proximo tick.");
        } else {
            r.add("Nenhuma medicao em andamento.");
        }
        String cal = ultimaCalibracao;
        r.add("Calibracao: " + (cal != null ? cal + " (limite de nanoTime: " + (int) LIMITE_NANOTIME_NS + " ns)"
                : "ainda nao rodou (roda no inicio de cada medicao)") + ". Valvula de sobrecarga: para sozinha se a"
                + " mediana do tick, em " + VALVULA_JANELAS + " janelas seguidas de " + JANELA + " ticks, passar a de antes"
                + " de ligar em mais de " + Math.round((VALVULA_FATOR - 1) * 100) + "% e de " + VALVULA_NS / 1000000L
                + " ms, ou se o custo estimado do medidor, com margem de " + Math.round(VALVULA_CUSTO_FATOR) + "x, passar de "
                + VALVULA_CUSTO_PCT_REF + "% do tick de antes de ligar (no minimo " + VALVULA_CUSTO_NS / 1000000L
                + " ms e no maximo " + VALVULA_CUSTO_MAX_NS / 1000000L + " ms por tick).");
        String a = resumoAutoteste;
        if (a != null) {
            r.add("Ultimo " + a + ".");
        } else {
            r.add("Autoteste: ainda nao rodou (roda apos " + TICKS_AUTOTESTE + " ticks de medicao).");
        }
        r.add("Ultimo resultado: " + ultimoResultado);
        String e = erroInterno;
        if (e != null) {
            r.add("Erro interno registrado: " + e);
        }
        return r;
    }

    static void registrarResultado(String texto) {
        ultimoResultado = texto;
    }
}
