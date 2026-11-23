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
package hoshiestar.medidor;

import hoshiestar.api.Log;
import hoshiestar.vigia.Marcas;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Medidor de carga por regiao (tarefa 0.9; porte do mod Hoshiestar-Medidor 1.0.4 para dentro do
 * server.jar). Estado da medicao e os metodos chamados pelos ganchos. Esta classe NAO cita classe do
 * Minecraft: os ganchos do fork (patches "// Hoshiestar - medidor" em World, EntityPlayerMP e
 * MinecraftServer) passam por {@link GanchosMedidor}, que tira do objeto do jogo o que importa
 * (dimensao, chunk, classe) e chama os metodos daqui com numeros e Object. O que o medidor precisa
 * perguntar ao jogo (entidade morta? quais mundos?) vem pela interface {@link Jogo}. Assim os testes
 * isolados rodam esta mesma classe, sem Minecraft.
 *
 * <p>Caminho quente: os patches leem {@link #ATIVO} e, se for falso, nao chamam nada (um teste de
 * campo estatico por gancho). {@code ATIVO} so e escrito pela thread do servidor (a mesma que roda os
 * ticks), por isso nao precisa ser volatile.
 *
 * <p>Com a medicao ligada, cada tick medido abre um "quadro" numa pilha pequena. Ao fechar, o tempo
 * proprio do quadro (tempo total menos o dos quadros aninhados dentro dele) e somado na {@link Coleta}.
 * Assim, entidade montada (tick do passageiro dentro do tick da montaria) ou qualquer aninhamento nao
 * conta tempo duas vezes.
 *
 * <p>O tick original pode lancar excecao e pular o gancho de fim. O laco do Crucible captura a excecao
 * e remove a entidade (isDead) ou invalida a tile entity. Por isso, ao abrir um quadro novo (ou no
 * inicio do laco de um mundo), os quadros de entidade morta ou de tile entity invalida que ficaram na
 * pilha sao descartados (ver {@link #descartarMortos}). Tambem sao descartados no fechamento de um
 * quadro de baixo e no inicio do proximo tick do servidor. O tempo de um quadro descartado nao conta.
 *
 * <p>Protecao do servidor enquanto mede: no inicio, calibra o custo de System.nanoTime e de um quadro
 * vazio (recusa medir se nanoTime for lento, salvo "forcar"); durante a medicao, uma valvula compara a
 * mediana do tick do servidor em janelas de 100 ticks com a mediana dos ultimos ticks antes de ligar,
 * e para sozinha (gravando o que ja coletou) se o tick piorar demais ou se o custo estimado do proprio
 * medidor passar do limite.
 *
 * <p>Relogio do tick do servidor: o vigia (recurso "vigia", patches no mesmo MinecraftServer.tick) ja le
 * System.nanoTime no inicio e no fim de cada tick; o medidor usa essas mesmas leituras
 * ({@link Marcas#nanoInicioTick()} / {@link Marcas#nanoFimTick()}) e so le o relogio por conta propria
 * quando o vigia esta desligado.
 */
public final class Medidor {

    private Medidor() {
    }

    /** Versao da logica do medidor (1.0.4 era o mod; 1.1.0 = porte para o server.jar com as correcoes 8b). */
    public static final String VERSAO = "1.1.0";
    public static final String ID = "medidor";
    static final Log LOG = Log.de(ID);

    /** Lido em todo tick de tile entity e de entidade (nos patches). Escrito so pela thread do servidor. */
    public static boolean ATIVO;

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
     * Valvula: custo estimado do medidor (quadros x custo calibrado x margem medidor.margem_custo) acima do limite
     * por tick, numa janela. O limite e {@link #VALVULA_CUSTO_PCT_REF}% da mediana do tick antes de ligar, mas
     * nunca menos que {@link #VALVULA_CUSTO_NS} nem mais que {@link #VALVULA_CUSTO_MAX_NS} (sem referencia: o
     * minimo). Assim, num servidor ja lento (tick de 84 ms na producao) a regra de custo nao para a medicao de pico
     * por uma estimativa de ~3% do tick; a protecao principal continua sendo a regra do tempo de tick.
     */
    static final long VALVULA_CUSTO_NS = 5000000L;
    static final long VALVULA_CUSTO_MAX_NS = 10000000L;
    static final int VALVULA_CUSTO_PCT_REF = 10;
    /**
     * Margem padrao da regra de custo (medidor.margem_custo no hoshiestar.yml). A calibracao e o melhor caso (cache
     * quente) e nao inclui a parte do gancho (conferencia da thread, caminho ate o metodo, descartarMortos, getClass,
     * indiceDim) nem o efeito no cache do resto do tick; por isso a regra compara o custo calibrado VEZES a margem
     * com o limite. Na 1.0.4 era 2; no teste do porte o custo real ligado deu 3 a 5 vezes a estimativa (revisao I2),
     * entao o padrao passou a 4.
     */
    static final double VALVULA_CUSTO_FATOR_PADRAO = 4.0;
    static final double MARGEM_MIN = 1.0;
    static final double MARGEM_MAX = 20.0;
    /** Tempo maximo da calibracao do relogio (a que decide a recusa), para relogio lento nao travar o tick. */
    static final long CALIB_MAX_NS = 20000000L;
    /**
     * Tempo maximo da calibracao do quadro (so roda se o relogio passou ou se foi forcado). Mais folga que a do
     * relogio: na primeira medicao depois do boot o JIT ainda nao compilou o caminho do quadro, e com 20 ms o
     * valor as vezes sai 2 a 3 vezes maior que o real (visto fora do servidor), o que faria a valvula de custo
     * parar a medicao sem motivo.
     */
    static final long CALIB_QUADRO_MAX_NS = 30000000L;
    /** Espera maxima pela gravacao no desligamento do servidor (antes do salvamento do mundo). */
    static final long ESPERA_GRAVACAO_PARADA_MS = 5000L;
    /** Referencia minima (ticks sem medicao vistos antes de ligar) para a valvula de tempo de tick valer. */
    static final int REF_MIN = 20;

    // ---- configuracao (escrita no registro, fase de coremod, e por GanchosMedidor.instalar) ----
    /** medidor.habilitado do hoshiestar.yml; false = o comando recusa e os ganchos de tick nao fazem nada. */
    private static volatile boolean habilitado = true;
    /** medidor.margem_custo do hoshiestar.yml (ja conferida); copiada na Coleta ao iniciar cada medicao. */
    private static volatile double margemCusto = VALVULA_CUSTO_FATOR_PADRAO;
    /**
     * Por que o medidor foi desabilitado sozinho (null = nao foi). Hoje so um caso: o jar do mod antigo
     * Hoshiestar-Medidor (1.0.x) em mods/. Ele tem classes com o mesmo nome das daqui (Medidor, Coleta, TabelaLong,
     * Relatorio) e chamaria membros que nao existem mais; os dois juntos dariam erro ou contariam em dobro.
     */
    private static volatile String motivoDesabilitado;
    /** Quem responde as perguntas ao jogo (null ate o servidor ligar). */
    private static volatile Jogo jogo;
    /** Que chamada de update de tile entity os ganchos envolvem: "firewall", "vanilla" ou "desconhecida". */
    private static volatile String chamadaTe = "desconhecida";
    /** Texto mais longo sobre a chamada (posicao do firewall na cadeia de transformadores). */
    private static volatile String chamadaTeDetalhe = "servidor ainda nao terminou de ligar";

    // ---- estado da medicao (so thread do servidor) ----
    private static Coleta coleta;
    private static Thread threadPrincipal;
    private static long fimPrevistoNano;
    private static long inicioTickNano;
    /**
     * O tick corrente esta sendo contado: verdadeiro do inicio do tick (ou do inicio da medicao, se ela ligou no
     * meio do tick) ate o fim do tick ou a parada da medicao.
     */
    private static boolean contarEsteTick;

    // ---- tempos de tick do servidor (so thread do servidor) ----
    // Fora da medicao: os ultimos JANELA ticks, para a referencia. Durante: a janela da valvula.
    private static final long[] refTicks = new long[JANELA];
    private static int refN;
    private static final long[] janTicks = new long[JANELA];
    private static int janN;
    private static final long[] ordenar = new long[JANELA];
    private static long sumidouro; // so para o JIT nao apagar o laco da calibracao
    private static final Object MARCA_CALIBRACAO = new Object();

    // ---- pilha de quadros (so thread do servidor) ----
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
     * Id da medicao em andamento (0 = nenhuma). Escrito so pela thread do servidor; e o que as outras threads
     * leem para saber se ha medicao (ATIVO nao e volatile).
     */
    private static volatile long idMedicao;
    private static long ultimoId;
    // Pedidos vindos de outra thread (RCON, por exemplo). Protegidos por TRAVA: a thread do servidor consome o
    // pedido e liga a medicao dentro da trava, entao o comando nunca responde "cancelado" para uma medicao que ja
    // comecou.
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
    // Configuracao
    // =====================================================================

    /** Registro (fase de coremod): valor de medidor.habilitado. */
    static void definirHabilitado(boolean h) {
        habilitado = h;
    }

    static boolean habilitado() {
        return habilitado;
    }

    /**
     * Registro: valor de medidor.margem_custo. Fora de {@link #MARGEM_MIN}..{@link #MARGEM_MAX} (ou NaN) usa o padrao
     * e avisa no log. Devolve o valor que ficou valendo.
     */
    static double definirMargemCusto(double m) {
        if (!(m >= MARGEM_MIN && m <= MARGEM_MAX)) {
            try {
                LOG.a("medidor.margem_custo = " + m + " fora de " + MARGEM_MIN + ".." + MARGEM_MAX + "; usando "
                        + VALVULA_CUSTO_FATOR_PADRAO);
            } catch (Throwable ignorado) {
                // log nunca derruba o boot
            }
            m = VALVULA_CUSTO_FATOR_PADRAO;
        }
        margemCusto = m;
        return m;
    }

    static double margemCusto() {
        return margemCusto;
    }

    /**
     * Desabilita o medidor por um conflito achado no boot (jar do mod antigo): o comando recusa com o motivo, os
     * ganchos de tick voltam na 1a linha e o status mostra o motivo. Sai um erro no log (uma vez por motivo).
     */
    static void desabilitarPorConflito(String motivo) {
        habilitado = false;
        if (motivo != null && !motivo.equals(motivoDesabilitado)) {
            motivoDesabilitado = motivo;
            try {
                LOG.e("MEDIDOR DESABILITADO: " + motivo, null);
            } catch (Throwable ignorado) {
                // log nunca derruba o boot
            }
        }
    }

    static String motivoDesabilitado() {
        return motivoDesabilitado;
    }

    /** Prefixo dos jars do mod antigo (Hoshiestar-Medidor-1.0.4-serveronly.jar e anteriores). */
    static final String PREFIXO_JAR_ANTIGO = "hoshiestar-medidor";

    /**
     * Nomes dos jars do mod antigo Hoshiestar-Medidor na pasta mods/ (separados por virgula), ou null se nao ha
     * nenhum. So lista a pasta (nao abre jar nem carrega classe): roda na fase de coremod, antes dos mods.
     */
    static String jarsAntigos(File pastaMods) {
        try {
            String[] nomes = pastaMods != null ? pastaMods.list() : null;
            if (nomes == null) {
                return null;
            }
            Arrays.sort(nomes);
            StringBuilder b = new StringBuilder();
            for (String n : nomes) {
                String m = n.toLowerCase(Locale.ROOT);
                if (m.startsWith(PREFIXO_JAR_ANTIGO) && m.endsWith(".jar")) {
                    if (b.length() > 0) {
                        b.append(", ");
                    }
                    b.append(n);
                }
            }
            return b.length() == 0 ? null : b.toString();
        } catch (Throwable t) {
            return null; // pasta ilegivel: nada a dizer (a conferencia pela classe, com o servidor de pe, ainda vale)
        }
    }

    /**
     * Servidor de pe (mods ja no classpath): se a classe hoshiestar.medidor.MedidorMod, que so existe no mod antigo,
     * esta no class loader, desabilita (pega o jar mesmo renomeado). getResource nao carrega a classe. Se o registro
     * ja desabilitou pelo nome do jar, nao repete o aviso. Devolve o nome do jar achado (ou null).
     */
    static String conferirClasseAntiga(ClassLoader cl) {
        try {
            java.net.URL u = cl != null ? cl.getResource("hoshiestar/medidor/MedidorMod.class") : null;
            if (u == null) {
                return null;
            }
            String s = u.toString();
            int fim = s.indexOf(".jar!");
            String nome = fim > 0 ? s.substring(s.lastIndexOf('/', fim) + 1, fim + 4) : "fora de jar";
            if (motivoDesabilitado == null) {
                desabilitarPorConflito(textoJarAntigo("classe hoshiestar.medidor.MedidorMod em " + nome));
            }
            return nome;
        } catch (Throwable t) {
            return null; // so diagnostico
        }
    }

    /** Texto do erro do jar antigo (o mesmo no boot, no comando e no status). */
    static String textoJarAntigo(String onde) {
        return "o mod antigo Hoshiestar-Medidor esta instalado (" + onde + "). O medidor agora faz parte do server.jar"
                + " e os dois juntos quebram (classes com o mesmo nome). Pare o servidor, APAGUE esse jar de mods/ e"
                + " ligue de novo; os comandos e os arquivos sao os mesmos.";
    }

    /** Servidor de pe (GanchosMedidor.instalar) ou teste: quem responde as perguntas ao jogo. */
    static void definirJogo(Jogo j, String chamada, String detalhe) {
        chamadaTe = chamada;
        chamadaTeDetalhe = detalhe;
        jogo = j;
    }

    static String chamadaTe() {
        return chamadaTe;
    }

    /** "tile entities=firewall, entidades=sim, jogadores pela rede=sim" (ganchos do fork; sempre no bytecode). */
    static String resumoGanchos() {
        return "tile entities=" + chamadaTe + ", entidades=sim, jogadores pela rede=sim";
    }

    // =====================================================================
    // Ganchos (chamados por GanchosMedidor so quando ATIVO == true; thread do servidor)
    // =====================================================================

    /** Inicio do laco de World.updateEntities: so conta, para o autoteste saber que o mundo tickou. */
    public static void inicioLacoMundo(Object mundo, int dimId, int nTe, int nEnt) {
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
            int d = c.indiceDim(mundo, dimId);
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

    /** Antes do update de uma tile entity (cx, cz = chunk da tile entity). */
    public static void inicioTE(Object mundo, int dimId, Object te, int cx, int cz) {
        try {
            if (Thread.currentThread() != threadPrincipal || te == null) {
                return;
            }
            Coleta c = coleta;
            if (c == null) {
                return;
            }
            if (prof > 0) {
                descartarMortos(c);
                // O laco de UM mundo nao aninha tile entity dentro de tile entity: um quadro TE do mesmo mundo no
                // topo e sobra de uma excecao que pulou o gancho de fim. TE de outro mundo (um mod que, dentro do
                // update de uma TE, roda o laco de outro mundo) e pai legitimo e fica.
                if (prof > 0 && qTipo[prof - 1] == Coleta.TE && qMundo[prof - 1] == mundo) {
                    descartarAte(prof - 1, c);
                }
            }
            empilhar(te, mundo, te.getClass(), Coleta.TE, c.indiceDim(mundo, dimId), cx, cz, false, c);
        } catch (Throwable t) {
            falha(t);
        }
    }

    /** Depois do update de uma tile entity. */
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

    /**
     * Antes do tick de uma entidade (so os ticks de verdade: World.updateEntityWithOptionalForce com forcar = true,
     * ou o tick do jogador pela rede). {@code montaria} = a montaria da entidade, so se ela e mesmo a montaria
     * (montaria.riddenByEntity == e); senao null. {@code rede} = tick do jogador pelo caminho da rede.
     */
    public static void inicioEntidade(Object mundo, int dimId, Object e, int cx, int cz, Object montaria, boolean rede) {
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
                boolean passageiro = montaria != null && qObj[prof - 1] == montaria;
                if (!passageiro) {
                    descartarMortos(c);
                }
            }
            empilhar(e, mundo, e.getClass(), Coleta.ENT, c.indiceDim(mundo, dimId), cx, cz, rede, c);
        } catch (Throwable t) {
            falha(t);
        }
    }

    /** Depois do tick de uma entidade (fecha o quadro mais alto desta entidade). */
    public static void fimEntidade(Object e) {
        long agora = System.nanoTime();
        try {
            if (e == null || Thread.currentThread() != threadPrincipal) {
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

    /** Arredonda para baixo (como MathHelper.floor_double), para posicao de entidade virar bloco. */
    static int piso(double v) {
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
     * Descarta, a partir do quadro mais baixo que for sobra de excecao, todos os quadros acima dele. Sobra de
     * excecao = quadro de entidade que ja esta morta (o laco do Crucible remove a entidade que lancou) ou de tile
     * entity invalida (o laco invalida a TE que lancou). Os quadros acima desse estavam aninhados nele e tambem
     * nunca vao fechar. Custa uma passada pela pilha, que quase sempre tem 0 ou 1 quadro.
     */
    private static void descartarMortos(Coleta c) {
        Jogo j = jogo;
        if (j == null) {
            return;
        }
        for (int i = 0; i < prof; i++) {
            if (j.morto(qObj[i], qTipo[i])) {
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
     * Erro no NOSSO codigo: desliga a medicao, registra uma vez por medicao (limitador) e deixa o tick seguir. Os
     * dados da medicao interrompida sao descartados (podem estar inconsistentes).
     *
     * <p>Nunca lanca, nem com um Throwable estranho (toString/getMessage que lancam, outro OutOfMemoryError): o
     * gancho de tile entity roda dentro do try do laco de tiles do World, e um erro que escapasse daqui seria tratado
     * como erro da tile entity da vez (invalidada ou servidor caido, conforme o forge.cfg). Por isso o corpo inteiro
     * fica num try/catch e o texto do erro e montado dentro dele (revisao M2).
     */
    public static void falha(Throwable t) {
        ATIVO = false;
        try {
            idMedicao = 0L;
            coleta = null;
            andamento = null;
            limparPilha();
            String txt;
            try {
                txt = String.valueOf(t);
            } catch (Throwable semTexto) {
                txt = t.getClass().getName() + " (sem texto: o toString lancou " + semTexto.getClass().getName() + ")";
            }
            erroInterno = txt;
            ultimoResultado = "medicao desligada por erro interno do medidor (dados descartados): " + txt;
            if (!erroLogado) {
                erroLogado = true;
                LOG.e("Erro interno; a medicao foi desligada e o servidor segue normal. Nenhum arquivo foi gravado.", t);
            }
        } catch (Throwable ignorado) {
            ATIVO = false; // nada mais a fazer; o tick segue
        }
    }

    // =====================================================================
    // Ciclo de vida (thread do servidor)
    // =====================================================================

    /** Primeira coisa de MinecraftServer.tick() depois das marcas do vigia (patch). Nunca lanca excecao. */
    public static void inicioTickServidor() {
        if (!habilitado) {
            return;
        }
        try {
            inicioTick(Marcas.nanoInicioTick());
        } catch (Throwable t) {
            falha(t);
        }
    }

    /** Ultima coisa de MinecraftServer.tick(), depois da marca de fim do vigia (patch). Nunca lanca excecao. */
    public static void fimTickServidor() {
        if (!habilitado) {
            return;
        }
        try {
            fimTick(Marcas.nanoFimTick());
        } catch (Throwable t) {
            falha(t);
        }
    }

    /** Inicio de cada tick do servidor; {@code agora} = System.nanoTime do inicio do tick. */
    static void inicioTick(long agora) {
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
        // o relogio do tick do servidor corre sempre (as 2 leituras por tick sao as do vigia, quando ligado): fora
        // da medicao alimenta a referencia da valvula
        if (!ATIVO) {
            inicioTickNano = agora;
            contarEsteTick = true;
            return;
        }
        // medicao que acabou de ligar neste tick (pedido acima) ja marcou o inicio do trecho contado
        if (!contarEsteTick || inicioTickNano - agora < 0L) {
            inicioTickNano = agora;
        }
        contarEsteTick = true;
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

    /** Fim de cada tick do servidor; {@code agora} = System.nanoTime do fim do tick. */
    static void fimTick(long agora) {
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
        boolean contar = contarEsteTick;
        contarEsteTick = false;
        if (!ATIVO) {
            if (contar) {
                refTicks[refN % JANELA] = Math.max(0L, agora - inicioTickNano);
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
            long dur = Math.max(0L, agora - inicioTickNano);
            c.ticksServidor++;
            c.nsTickServidor += dur;
            janTicks[janN++] = dur;
            if (janN == JANELA) {
                janN = 0;
                String motivo = avaliarJanela(c);
                if (motivo != null) {
                    c.valvulaDisparou = true;
                    c.motivoValvula = motivo;
                    LOG.a("Medicao parada pela valvula de sobrecarga: " + motivo + ". O que ja foi coletado vai ser gravado.");
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
     * Fecha uma janela de {@link #JANELA} ticks medidos. Devolve o motivo para parar, ou null. Duas regras: (1)
     * custo estimado do medidor (quadros da janela x custo calibrado de um quadro), vezes a margem
     * (medidor.margem_custo, guardada na Coleta), acima do limite da medicao ({@link #limiteCusto}) por tick; (2) mediana do tick
     * do servidor acima da referencia (mediana antes de ligar) em mais de 25% e de 15 ms, em
     * {@link #VALVULA_JANELAS} janelas seguidas. Mediana, e nao media, para um tick isolado lento (salvamento, GC)
     * nao disparar.
     */
    static String avaliarJanela(Coleta c) {
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
        if (custo * c.margemCusto > c.limiteCustoNs) {
            return String.format(Locale.ROOT, "custo estimado do medidor %.1f ms por tick, %.1f ms com a margem de %.1fx"
                    + " (limite %.1f ms; margem em medidor.margem_custo)", custo / 1e6, custo * c.margemCusto / 1e6,
                    c.margemCusto, c.limiteCustoNs / 1e6);
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
     * Custo de uma chamada de System.nanoTime (ns), o menor de varias rodadas curtas (as primeiras aquecem o JIT).
     * Para em {@link #CALIB_MAX_NS} no total: o tempo e conferido a cada rodada de 1.000 leituras, entao relogio
     * lento passa no maximo uma rodada do limite. Se as 2 primeiras rodadas ja derem mais de 3x
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
     * Custo de um quadro vazio, pelo mesmo caminho dos ganchos (abrir, ler o relogio, fechar e somar nas tabelas),
     * numa Coleta de rascunho que e jogada fora. Devolve {custo total por quadro, parte que cai DENTRO do tempo
     * medido de cada quadro (artefato)}, o menor de varias rodadas. Nao toca em objeto nenhum do jogo.
     */
    private static double[] calibrarQuadro() {
        final int n = 500;
        Coleta r = new Coleta(0);
        int d = r.indiceDim(null, Coleta.DIM_DESCONHECIDA);
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
     * Comeca uma medicao. Deve rodar na thread do servidor.
     *
     * @param resposta onde mandar mensagens extras (null = so o log)
     * @return texto para quem pediu, ou null se o inicio ja foi registrado no log
     */
    static String iniciar(int segundos, boolean forcar, List<String> resposta) {
        if (ATIVO) {
            return "Ja existe uma medicao em andamento. Use 'hoshie status' ou 'hoshie parar'.";
        }
        limparPilha();
        // Calibracao (uma vez, na thread do servidor; relogio ate CALIB_MAX_NS, quadro ate CALIB_QUADRO_MAX_NS, e a
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
                LOG.a(msg);
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
        c.margemCusto = margemCusto;
        c.chamadaTe = chamadaTe;
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
        String linha = "Medicao iniciada por " + segundos + " s (para sozinha). Ganchos: " + resumoGanchos()
                + ". Calibracao: " + ultimaCalibracao
                + (c.forcado ? " (ACIMA DO LIMITE, forcado)" : "") + ". Tick antes de ligar: "
                + (c.refMedianaNs > 0 ? String.format(Locale.ROOT, "mediana %.1f ms em %d ticks", c.refMedianaNs / 1e6, nRef)
                        : "sem referencia (poucos ticks vistos); a valvula usa so o custo estimado")
                + String.format(Locale.ROOT, "; limite do custo estimado %.1f ms por tick (custo x margem %.1f)",
                        c.limiteCustoNs / 1e6, c.margemCusto)
                + ".";
        LOG.i(linha);
        if (resposta != null) {
            resposta.add(linha);
        }
        // o trecho contado do tick corrente comeca depois da linha do log (que e do medidor, nao do tick)
        inicioTickNano = System.nanoTime();
        return null;
    }

    /** Para a medicao e entrega os dados a uma thread de gravacao. Thread do servidor. */
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
            // Parada no meio de um tick (comando): os quadros deste tick ja estao nos totais, entao o trecho do tick
            // ate aqui tambem conta (fora da janela da valvula, que nao avalia mais).
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
        // Sempre refeito no fim: o veredito do tick 100 pode ter sido tirado com o servidor ocioso (ou num inicio
        // atipico); o relatorio usa o da medicao inteira.
        try {
            c.autotesteFinal = autoteste(c, true);
        } catch (Throwable t) {
            LOG.a("Autoteste final falhou: " + t);
        }
        c.soltarMundo();
        File pasta = pastaDeSaida();
        ultimoResultado = "medicao encerrada (" + motivo + "); gravando arquivos...";
        Thread t = new Thread(new Relatorio(c, pasta), "Hoshiestar Medidor gravacao");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        ultimaGravacao = t;
        t.start();
        return t;
    }

    /** Pasta de saida: &lt;pasta do servidor&gt;/hoshiestar (pergunta ao jogo; senao, o diretorio de trabalho). */
    private static File pastaDeSaida() {
        try {
            Jogo j = jogo;
            File f = j != null ? j.pastaDeSaida() : null;
            if (f != null) {
                return f.getAbsoluteFile().toPath().normalize().toFile();
            }
        } catch (Throwable t) {
            // cai no diretorio de trabalho
        }
        return new File("hoshiestar").getAbsoluteFile();
    }

    /**
     * Desligamento normal do servidor (patch em MinecraftServer.run, logo antes do evento de parada do FML, na
     * thread do servidor): encerra e espera a gravacao um pouco (a JVM vai sair). Nunca lanca excecao.
     *
     * <p>A espera e de no maximo {@link #ESPERA_GRAVACAO_PARADA_MS} e acontece ANTES de o mundo ser salvo: um painel
     * que mata o processo poucos segundos depois do stop nao pode perder o salvamento por causa do medidor (revisao
     * M3; na 1.0.4 eram 20 s). Normalmente a gravacao leva menos de 1 s; se passar disso, os arquivos podem ficar
     * vazios (o conteudo entra de uma vez, por troca de um temporario) e o mundo e salvo do mesmo jeito.
     */
    public static void pararNoDesligamento() {
        try {
            Thread t = null;
            if (Thread.currentThread() == threadPrincipal) {
                t = parar("desligamento do servidor");
            }
            if (t == null) {
                t = ultimaGravacao;
            }
            if (t != null && t.isAlive()) {
                t.join(ESPERA_GRAVACAO_PARADA_MS);
                if (t.isAlive()) {
                    LOG.a("A gravacao da medicao nao terminou em " + ESPERA_GRAVACAO_PARADA_MS / 1000 + " s; o servidor segue"
                            + " desligando (os arquivos desta medicao podem nao sair).");
                }
            }
        } catch (Throwable t) {
            try {
                LOG.a("Falha ao encerrar a medicao no desligamento: " + t);
            } catch (Throwable ignorado) {
                // nada
            }
        }
    }

    /**
     * Autoteste: confere se os ganchos estao vivos. Se ha tile entities nas listas dos mundos cujo laco rodou e
     * nenhum tick de tile entity foi medido, o gancho nao esta ligado; isso e dito no log, no status e nos
     * arquivos, em vez de mostrar zero como se fosse dado. Roda no tick {@link #TICKS_AUTOTESTE} (aviso cedo) e de
     * novo no fim (vale para o relatorio). Tambem confere dimensao por dimensao (um mundo de mod com laco proprio
     * ficaria sem medicao sem que o total denunciasse).
     */
    private static Coleta.Autoteste autoteste(Coleta c, boolean fim) {
        Coleta.Autoteste a = new Coleta.Autoteste();
        int tes = 0;
        int ents = 0;
        try {
            Jogo j = jogo;
            List<Jogo.Mundo> mundos = j != null ? j.mundos() : null;
            if (mundos == null) {
                tes = -1;
                ents = -1;
            } else {
                for (Jogo.Mundo w : mundos) {
                    if (w == null) {
                        continue;
                    }
                    tes += w.nTe;
                    ents += w.nEnt;
                    conferirDimensao(c, w, a);
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
        a.ticksComLaco = c.ticksComLaco;
        a.tickSeq = c.tickSeq;
        // So da para cobrar ticks medidos de mundos cujo laco rodou: um WorldServer sem jogador (e sem chunk
        // forcado) ha 1200 ticks pula o laco inteiro (normal do jogo). O gancho do inicio do laco esta sempre no
        // fork, entao sempre se sabe se o laco rodou.
        if (c.teTicksTotal > 0) {
            a.te = "ok";
        } else if (c.lacosMundo == 0) {
            a.te = "sem_dados_nenhum_mundo_tickou";
        } else if (c.teNasListas == 0) {
            a.te = "sem_tile_entities_carregados";
        } else {
            a.te = "NAO_LIGADO";
        }
        if (c.entTicksTotal > 0) {
            a.ent = "ok";
        } else if (c.lacosMundo == 0) {
            a.ent = "sem_dados_nenhum_mundo_tickou";
        } else if (c.entNasListas == 0) {
            a.ent = "sem_entidades_carregadas";
        } else {
            a.ent = "NAO_LIGADO";
        }
        String r = "autoteste " + (fim ? "final" : "inicial") + " em " + c.ticksServidor + " ticks: tile entities " + a.te
                + " (chamada envolvida: " + c.chamadaTe + "; " + c.teTicksTotal + " ticks medidos; " + tes
                + " carregados), entidades " + a.ent + " (" + c.entTicksTotal + " ticks medidos; " + ents + " carregadas; "
                + c.ticksJogadorRede + " de jogador pela rede); lacos de mundo que rodaram: " + c.lacosMundo + " (em "
                + c.ticksComLaco + " de " + c.tickSeq + " ticks)"
                + (a.dimsSemMedicao.isEmpty() ? "" : "; dimensoes com problema: " + a.dimsSemMedicao.size());
        resumoAutoteste = r;
        // Tudo certo: nada no console (so as linhas de inicio e de fim); o resultado fica no 'hoshie status', na linha
        // de fim e nos arquivos. Problema: aviso no console.
        if ("NAO_LIGADO".equals(a.te) || "NAO_LIGADO".equals(a.ent) || !a.dimsSemMedicao.isEmpty()) {
            a.problema = true;
            LOG.a("ATENCAO: " + r + ". O que estiver NAO_LIGADO nao esta sendo medido; os zeros desta parte nao sao dado.");
            for (String d : a.dimsSemMedicao) {
                LOG.a("  " + d);
            }
        }
        return a;
    }

    /** Confere uma dimensao: devia tickar e o gancho nao viu? Ou o laco rodou e nada foi medido? */
    private static void conferirDimensao(Coleta c, Jogo.Mundo w, Coleta.Autoteste a) {
        int d = c.indiceDim(w.mundo, w.dimId);
        String dim = w.dimId == Coleta.DIM_DESCONHECIDA ? "?" : Integer.toString(w.dimId);
        if (c.dimLacos[d] == 0L) {
            if ((w.temJogador || w.temForcado) && (w.nTe > 0 || w.nEnt > 0)) {
                a.dimsSemMedicao.add("dimensao " + dim + ": tem " + (w.temJogador ? "jogador" : "chunk forcado") + " e "
                        + w.nTe + " tile entities / " + w.nEnt + " entidades carregados, mas o laco do mundo nunca passou"
                        + " pelo gancho (mundo com laco proprio?): esta dimensao pode NAO estar medida");
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
                        + " estimado nesta medicao: %.1f ms por tick, comparado com o custo x%.1f)", JANELA,
                        c.limiteCustoNs / 1e6, c.margemCusto)
                : String.format(Locale.ROOT, "valvula: mediana do tick na ultima janela %.1f ms (antes de ligar: %s),"
                        + " custo estimado do medidor %.2f ms por tick (x%.1f = %.2f ms; limite %.1f ms)",
                        c.ultimaMedianaJanelaNs / 1e6,
                        c.refMedianaNs > 0 ? String.format(Locale.ROOT, "%.1f ms", c.refMedianaNs / 1e6) : "sem referencia",
                        c.ultimoCustoEstNsPorTick / 1e6, c.margemCusto,
                        c.ultimoCustoEstNsPorTick * c.margemCusto / 1e6, c.limiteCustoNs / 1e6);
        return String.format(Locale.ROOT,
                "medicao em andamento: %d de %d s, %d ticks do servidor; ticks medidos: %d de tile entity, %d de entidade"
                        + " (%d de jogador pela rede); %s",
                dec, c.segundosPedidos, c.ticksServidor, c.teTicksTotal, c.entTicksTotal, c.ticksJogadorRede, valvula);
    }

    // =====================================================================
    // Comando (pode vir de qualquer thread)
    // =====================================================================

    /** O que "hoshie medir ..." pediu: segundos e forcar, ou o erro a mostrar. */
    static final class Pedido {
        final int segundos;
        final boolean forcar;
        final String erro;

        private Pedido(int segundos, boolean forcar, String erro) {
            this.segundos = segundos;
            this.forcar = forcar;
            this.erro = erro;
        }
    }

    static String usoMedir() {
        return "hoshie medir [segundos " + SEG_MIN + "-" + SEG_MAX + ", padrao " + SEG_PADRAO + "] [forcar]";
    }

    /**
     * Le os argumentos de "hoshie medir" (sem a palavra "medir"): nenhum, um numero, "forcar", ou um numero e
     * "forcar" (nessa ordem). Qualquer outra coisa e recusada com a explicacao (na 1.0.4 uma palavra desconhecida
     * depois do numero era ignorada sem aviso: "hoshie medir 30 forca" media sem forcar).
     */
    static Pedido interpretarMedir(String[] args) {
        int n = args == null ? 0 : args.length;
        boolean forcar = false;
        if (n >= 1 && "forcar".equalsIgnoreCase(args[n - 1].trim())) {
            forcar = true;
            n--;
        }
        if (n > 1) {
            return new Pedido(0, false, "Argumentos demais: '" + juntar(args) + "'. Uso: " + usoMedir() + ".");
        }
        int seg = SEG_PADRAO;
        if (n == 1) {
            String a = args[0].trim();
            try {
                seg = Integer.parseInt(a);
            } catch (NumberFormatException e) {
                return new Pedido(0, false, "Argumento desconhecido: '" + args[0] + "' (esperado o numero de segundos"
                        + (forcar ? "" : " ou 'forcar'") + "). Uso: " + usoMedir() + ".");
            }
            if (seg < SEG_MIN || seg > SEG_MAX) {
                return new Pedido(0, false, "Valor fora do limite: " + seg + " s. Use entre " + SEG_MIN + " e " + SEG_MAX
                        + " segundos (padrao " + SEG_PADRAO + ").");
            }
        }
        return new Pedido(seg, forcar, null);
    }

    private static String juntar(String[] args) {
        StringBuilder b = new StringBuilder();
        for (String s : args) {
            if (b.length() > 0) {
                b.append(' ');
            }
            b.append(s);
        }
        return b.toString();
    }

    static boolean naThreadDeTick() {
        return Thread.currentThread() == threadDeTick;
    }

    static List<String> comandoMedir(int segundos, boolean forcar) {
        List<String> r = new ArrayList<String>();
        if (!habilitado) {
            String m = motivoDesabilitado;
            r.add(m != null ? "O medidor esta DESABILITADO: " + m
                    : "O medidor esta desabilitado no hoshiestar.yml (medidor.habilitado: false). Para medir, mude para"
                    + " true e reinicie o servidor.");
            return r;
        }
        if (jogo == null) {
            r.add("O servidor ainda nao terminou de ligar; tente de novo depois do 'Done'.");
            return r;
        }
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
                // a thread do servidor ainda nao consumiu o pedido (ela consome dentro da trava)
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

    /**
     * Linha do medidor no 'hoshie status' (e no log do fim do boot): estado, ganchos, andamento ou ultimo
     * resultado, calibracao, autoteste e erro interno. Sem caminho de pasta (o status vai tambem para jogador).
     */
    static String linhaDeEstado() {
        StringBuilder b = new StringBuilder();
        String md = motivoDesabilitado;
        if (md != null) {
            b.append("DESABILITADO: ").append(md).append("; ");
        }
        String and = andamento;
        boolean pedida;
        synchronized (TRAVA) {
            pedida = pedidoSegundos != 0;
        }
        if (idMedicao != 0L && and != null) {
            b.append(and);
        } else if (pedida) {
            b.append("medicao pedida, comeca no proximo tick");
        } else if (jogo == null) {
            b.append("aguardando o servidor ligar");
        } else {
            b.append("pronto; nenhuma medicao em andamento (parado: so mede com 'hoshie medir <segundos>')");
        }
        b.append("; ganchos do fork: ").append(resumoGanchos()).append(" (").append(chamadaTeDetalhe).append(')');
        String cal = ultimaCalibracao;
        if (cal != null) {
            b.append("; ultima calibracao: ").append(cal).append(" (limite de nanoTime ").append((int) LIMITE_NANOTIME_NS)
                    .append(" ns)");
        }
        String a = resumoAutoteste;
        if (a != null) {
            b.append("; ultimo ").append(a);
        }
        b.append("; ultimo resultado: ").append(ultimoResultado);
        String e = erroInterno;
        if (e != null) {
            b.append("; erro interno registrado: ").append(e);
        }
        return b.toString();
    }

    static void registrarResultado(String texto) {
        ultimoResultado = texto;
    }

    // =====================================================================
    // Uso dos testes isolados
    // =====================================================================

    /** Testes: espera a ultima gravacao terminar (ms). */
    static boolean esperarGravacao(long ms) throws InterruptedException {
        Thread t = ultimaGravacao;
        if (t != null) {
            t.join(ms);
            return !t.isAlive();
        }
        return true;
    }

    /** Testes: profundidade da pilha de quadros agora. */
    static int profundidade() {
        return prof;
    }

    /** Testes: a coleta em andamento (null se nenhuma). */
    static Coleta coletaAtual() {
        return coleta;
    }

    static String ultimoResultado() {
        return ultimoResultado;
    }
}
