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

import hoshiestar.api.Limitador;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * O nucleo do vigia: o laco da thread "Hoshiestar Vigia" e a montagem do relatorio. Nao depende do
 * Minecraft: tudo o que vem do jogo chega pelas interfaces abaixo (no servidor, {@link FonteServidor}
 * e {@code ContextoMinecraft}; nos testes isolados, fontes falsas com threads de verdade).
 *
 * <p>O laco: a cada {@link Ajustes#pollMs} ms le o inicio do tick em andamento. Se o tick passou do
 * limite, pergunta ao limitador ANTES de amostrar (se ele nega, so conta). Se pode, tira ate N
 * amostras da pilha da thread do servidor ({@code Thread.getStackTrace}: nao interrompe, nao suspende,
 * nao para a thread; a JVM faz uma pausa curta para copiar a pilha) e grava UM relatorio logo depois
 * (padrao: sem esperar o tick acabar, {@link Ajustes#esperaFimMs} = 0; se o tick ainda roda, sai
 * "CONTINUA TRAVADO" e, quando ele acabar, uma linha com a duracao total). Num travamento longo,
 * enquanto o mesmo tick continua, sai outro relatorio cada vez que o limitador deixa. Se a parada do
 * servidor chega com amostras ja colhidas, o relatorio e gravado na hora, marcado "SERVIDOR PARANDO".
 *
 * <p><b>Threads e campos</b> (concorrencia; revisar com o doc):
 * <ul>
 * <li>so a thread do vigia escreve o estado do laco (campos sem volatile) e os contadores
 * (volatile, para o "hoshie status" ler de outra thread);</li>
 * <li>{@link #parar} e escrito por quem para (stopServer, na thread do servidor ou no gancho de
 * desligamento) e lido pela thread do vigia em toda espera; {@link #parar()} nunca espera mais que
 * {@link Ajustes#esperaPararMs} pela thread do vigia (ela e daemon);</li>
 * <li>o que vem do jogo (inicio do tick, entidade, tile, pacote, jogador, campos deles) e escrito pela
 * thread do servidor e so LIDO aqui: valor velho ou rasgado so muda o texto do relatorio.</li>
 * </ul>
 * Este codigo nunca chama interrupt/stop/suspend em thread nenhuma, nem System.exit.
 */
public final class NucleoVigia implements Runnable {

    /** Onde o vigia le o estado do tick (escrito so pela thread do servidor). */
    public interface FonteDeTick {
        long seq();

        long inicioNanos();

        long seqFim();

        long duracaoFim();

        Thread thread();

        Object entidade();

        Object tile();
    }

    /** O que so o jogo sabe descrever. Nenhum metodo pode lancar para fora (o nucleo protege mesmo assim). */
    public interface Contexto {
        Object ultimoPacote();

        Object jogadorDaRede();

        String entidade(Object e, boolean nomeDoJogador);

        String tile(Object t);

        String jogador(Object p, boolean nomeDoJogador);
    }

    /** Copia a pilha de uma thread (troca nos testes para contar quantas vezes amostrou). */
    public interface Amostrador {
        StackTraceElement[] pilha(Thread t);
    }

    /** Para onde vai o relatorio. */
    public interface Saida {
        /** Grava o relatorio (arquivo + log). O texto ja vem pronto, sem o caminho do arquivo. */
        void relatorio(long seq, String texto);

        /** Uma linha curta no log (ex.: "o tick que travou terminou"). */
        void linha(String texto);

        /** Erro interno do vigia (a saida limita a frequencia). */
        void erro(String texto, Throwable t);
    }

    /** Coletores de lixo (GC): nome, tempo acumulado (ms) e numero de coletas. */
    public interface FonteGc {
        int quantos();

        String nome(int i);

        long tempoMs(int i);

        long coletas(int i);
    }

    /** Dono (jar) de uma classe, ou null. Montado so quando o primeiro relatorio precisa. */
    public interface Donos {
        String dono(String classe);

        /** Uma linha sobre o indice (quantos jars, tempo para montar) ou null se ainda nao montado. */
        String resumo();
    }

    /** Valores do hoshiestar.yml ja conferidos, mais os tempos internos. */
    public static final class Ajustes {
        public long limiteMs = 2000;
        public int amostras = 5;
        public long intervaloAmostrasMs = 500;
        public int quadros = 40;
        public boolean nomeDoJogador = true;
        public long pollMs = 100;
        /** Quanto esperar o tick acabar depois das amostras antes de gravar (0 = grava ja; revisao I1). */
        public long esperaFimMs = 0;
        public long esperaPararMs = 800;
        /** Ticks lentos nos primeiros N ms depois de a thread do vigia comecar (fim do boot) so sao contados. */
        public long carenciaMs = 0;
    }

    /** Prefixos de classe que nao sao "de um mod" (JDK, Minecraft, Forge, Bukkit, Crucible, bibliotecas). */
    static final String[] NEUTROS = {
        "java.", "javax.", "jdk.", "sun.", "com.sun.", "it.unimi.", "gnu.trove.", "com.google.",
        "org.apache.", "io.netty.", "scala.", "kotlin.", "org.objectweb.", "com.typesafe.",
        "net.minecraft.", "net.minecraftforge.", "cpw.mods.fml.", "org.bukkit.", "org.spigotmc.",
        "io.github.crucible.", "co.aikar.", "net.md_5.", "com.mojang.", "org.spongepowered.",
        "LZMA.", "paulscode.", "joptsimple.", "org.yaml.", "org.json.", "com.gtnewhorizons.retrofuturabootstrap",
        "thermos.", "kcauldron.", "org.ultramine.", "com.llamalad7.mixinextras."
    };

    /** Prefixos de nome de metodo que o Mixin usa para codigo injetado ("redirect$bao000$hodgepodge_2$x"). */
    static final String[] MIXIN = {"handler$", "redirect$", "wrapOperation$", "modify$", "localvar$", "constant$", "wrapMethod$"};

    private final Ajustes a;
    private final FonteDeTick fonte;
    private final Contexto ctx;
    private final Amostrador amostrador;
    private final Saida saida;
    private final FonteGc gc;
    private final Donos donos;
    private final LimiteDeRelatorios limite;
    private final Limitador.Tempo tempo;

    private final Object espera = new Object();
    private volatile boolean parar;
    /** parar() esperou o prazo todo e a thread do vigia nao terminou: ela nao grava mais nada (escrito por quem para). */
    private volatile boolean desistiu;
    private volatile Thread thread;

    // --- contadores (escritos so pela thread do vigia; lidos pelo status)
    private volatile long relatorios;
    private volatile long ticksLentos;
    private volatile long suprimidos;
    private volatile long maiorMs;
    private volatile long errosInternos;
    private volatile boolean desligadoPorErro;
    private volatile long ultimoRelatorioNanos;
    private volatile long naCarencia;
    private volatile long maiorNaCarenciaMs;
    /** Ticks acima do limite que o vigia so viu pelo fim anotado (passaram do limite por menos de um poll). */
    private volatile long soNoFim;

    // --- estado do laco (so a thread do vigia)
    private long seqDoPollAnterior = Long.MIN_VALUE;
    private long[] gcPollAnterior;
    private long[] gcBase; // GC antes do tick em andamento comecar
    private long seqLento = Long.MIN_VALUE;
    private long inicioLento;
    private long[] gcBaseLento;
    private int vezesRelatado;
    private boolean contadoSuprimido;
    private boolean fimJaInformado;
    private long suprimidosDesdeUltimo;
    private long maiorSuprimidoMs;
    private long inicioThreadNanos = Long.MIN_VALUE;
    private long seqCarencia = Long.MIN_VALUE;
    private boolean carenciaInformada;
    private long seqFimVisto = Long.MIN_VALUE; // ultimo Marcas.seqFim ja conferido
    private long ultimoSeqContado = Long.MIN_VALUE; // ultimo tick lento contado pelo laco (normal ou carencia)
    private long soNoFimDesdeUltimo;
    private long maiorSoNoFimMs;

    public NucleoVigia(Ajustes a, FonteDeTick fonte, Contexto ctx, Amostrador amostrador, Saida saida,
            FonteGc gc, Donos donos, LimiteDeRelatorios limite, Limitador.Tempo tempo) {
        this.a = a;
        this.fonte = fonte;
        this.ctx = ctx;
        this.amostrador = amostrador;
        this.saida = saida;
        this.gc = gc;
        this.donos = donos;
        this.limite = limite;
        this.tempo = tempo;
    }

    /** Cria e inicia a thread daemon "Hoshiestar Vigia" (prioridade normal). */
    public synchronized Thread iniciar() {
        if (thread != null) {
            return thread;
        }
        Thread t = new Thread(this, "Hoshiestar Vigia");
        t.setDaemon(true);
        thread = t;
        t.start();
        return t;
    }

    /**
     * Pede a thread do vigia que pare e espera no maximo {@link Ajustes#esperaPararMs} ms (nunca um join
     * sem limite: a parada do servidor nao pode atrasar). Pode ser chamado de qualquer thread, varias vezes.
     * Devolve true se a thread terminou dentro do prazo (ou nunca existiu).
     */
    public boolean parar() {
        parar = true;
        synchronized (espera) {
            espera.notifyAll();
        }
        Thread t = thread;
        if (t == null || t == Thread.currentThread()) {
            return true;
        }
        try {
            t.join(Math.max(1L, a.esperaPararMs));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // devolve o pedido de interrupcao a quem chamou; nao espera mais
        }
        boolean terminou = !t.isAlive();
        if (!terminou) {
            desistiu = true; // passou o prazo: se a thread do vigia se soltar depois, nao grava mais nada
        }
        return terminou;
    }

    public boolean parando() {
        return parar;
    }

    @Override
    public void run() {
        inicioThreadNanos = tempo.nanos();
        int errosSeguidos = 0;
        while (!parar) {
            dormir(a.pollMs);
            if (parar) {
                break;
            }
            try {
                passo();
                errosSeguidos = 0;
            } catch (Throwable t) {
                errosInternos++;
                errosSeguidos++;
                erro("erro interno no vigia", t);
                if (errosSeguidos >= 3) {
                    desligadoPorErro = true;
                    erro("tres erros internos seguidos: o vigia se desligou (o jogo segue normal)", null);
                    break;
                }
            }
        }
    }

    /** Uma volta do laco (pacote: os testes chamam direto quando querem passo a passo). */
    void passo() {
        long[] gcAgora = lerGc();
        long s1 = fonte.seq();
        long ini = fonte.inicioNanos();
        long s2 = fonte.seq();
        if (s1 != seqDoPollAnterior) {
            // o tick s1 comecou depois do poll anterior: o GC daquele poll e de antes deste tick
            gcBase = gcPollAnterior != null ? gcPollAnterior : gcAgora;
        }
        seqDoPollAnterior = s1;
        gcPollAnterior = gcAgora;

        boolean emTick = ini != 0L && s1 == s2;
        long agora = tempo.nanos();
        if (inicioThreadNanos == Long.MIN_VALUE) {
            inicioThreadNanos = agora; // testes que chamam passo() direto
        }
        boolean naCarenciaAgora = agora - inicioThreadNanos < a.carenciaMs * 1000000L;
        if (seqCarencia != Long.MIN_VALUE && (!emTick || s1 != seqCarencia)) {
            long d = duracaoSeTerminou(seqCarencia, 0L);
            if (d > maiorNaCarenciaMs) {
                maiorNaCarenciaMs = d;
            }
            seqCarencia = Long.MIN_VALUE;
        }
        contarTickSoVistoNoFim(naCarenciaAgora);
        if (!naCarenciaAgora && !carenciaInformada) {
            carenciaInformada = true;
            if (naCarencia > 0) {
                saida.linha("nos primeiros " + (a.carenciaMs / 1000L) + " s depois do boot (carencia) houve " + naCarencia
                        + " tick(s) lento(s), sem relatorio; o maior: " + maiorNaCarenciaMs
                        + " ms (o primeiro tick depois do boot costuma ser lento)");
            }
        }
        if (seqLento != Long.MIN_VALUE && (!emTick || s1 != seqLento || ini != inicioLento)) {
            terminouTickLento();
        }
        if (!emTick) {
            return;
        }
        long durMs = (agora - ini) / 1000000L;
        if (durMs < a.limiteMs) {
            return;
        }
        if (naCarenciaAgora) {
            // logo depois do boot: so conta (um tick que continuar travado depois da carencia e tratado normalmente)
            if (s1 != seqCarencia) {
                seqCarencia = s1;
                ultimoSeqContado = s1;
                naCarencia++;
            }
            if (durMs > maiorNaCarenciaMs) {
                maiorNaCarenciaMs = durMs;
            }
            return;
        }
        if (s1 != seqLento || ini != inicioLento) {
            seqLento = s1;
            inicioLento = ini;
            gcBaseLento = gcBase;
            vezesRelatado = 0;
            contadoSuprimido = false;
            fimJaInformado = false;
            ultimoSeqContado = s1;
            ticksLentos++;
        }
        if (durMs > maiorMs) {
            maiorMs = durMs;
        }
        // limitador ANTES de amostrar (correcao do conferente): negado = so conta, nenhuma amostra
        if (!limite.permitir(agora)) {
            if (!contadoSuprimido && vezesRelatado == 0) {
                contadoSuprimido = true;
                suprimidos++;
                suprimidosDesdeUltimo++;
            }
            if (durMs > maiorSuprimidoMs) {
                maiorSuprimidoMs = durMs;
            }
            return;
        }
        if (contadoSuprimido) {
            // este mesmo tick tinha sido contado como sem relatorio; agora ganha um
            contadoSuprimido = false;
            suprimidos--;
            suprimidosDesdeUltimo--;
        }
        relatar(s1, ini);
    }

    /**
     * Marcas.fimTick anota todo tick que passou do limite (seqFim/duracaoFim). Um tick que passou do limite
     * por menos que um poll (100 ms) pode acabar antes de o laco o ver: aqui ele e ao menos contado, pelo
     * fim anotado (revisao M1). Se varios assim acabarem entre dois polls (so durante uma amostragem com
     * intervalo longo), so o ultimo e contado.
     */
    private void contarTickSoVistoNoFim(boolean naCarenciaAgora) {
        long sf = fonte.seqFim();
        if (sf < 0L) {
            return; // o servidor esta gravando o fim agora; o proximo poll ve
        }
        if (seqFimVisto == Long.MIN_VALUE) {
            seqFimVisto = sf; // primeira volta: o que ja estava anotado e de antes da thread do vigia
            return;
        }
        if (sf == seqFimVisto) {
            return;
        }
        seqFimVisto = sf;
        if (sf == ultimoSeqContado) {
            return; // o laco ja tinha visto e contado este tick
        }
        long d = duracaoSeTerminou(sf, 0L);
        if (naCarenciaAgora) {
            naCarencia++;
            if (d > maiorNaCarenciaMs) {
                maiorNaCarenciaMs = d;
            }
            return;
        }
        ticksLentos++;
        soNoFim++;
        soNoFimDesdeUltimo++;
        if (d > maiorSoNoFimMs) {
            maiorSoNoFimMs = d;
        }
        if (d > maiorMs) {
            maiorMs = d;
        }
    }

    private void terminouTickLento() {
        if (vezesRelatado > 0 && !fimJaInformado) {
            long d = duracaoSeTerminou(seqLento, inicioLento);
            saida.linha("o tick #" + seqLento + " que gerou relatorio terminou"
                    + (d >= 0 ? " depois de " + d + " ms" : "") + " (" + vezesRelatado + " relatorio(s))");
            if (d > maiorMs) {
                maiorMs = d;
            }
        } else if (contadoSuprimido) {
            long d = duracaoSeTerminou(seqLento, inicioLento);
            if (d > maiorSuprimidoMs) {
                maiorSuprimidoMs = d;
            }
            if (d > maiorMs) {
                maiorMs = d;
            }
        }
        seqLento = Long.MIN_VALUE;
        vezesRelatado = 0;
        contadoSuprimido = false;
        fimJaInformado = false;
    }

    /** Duracao exata (ms) do tick, se ele foi o ultimo tick lento (acima do limite) a terminar; senao -1. */
    private long duracaoSeTerminou(long seq, long ini) {
        long s = fonte.seqFim();
        long d = fonte.duracaoFim();
        long s2 = fonte.seqFim();
        if (s == seq && s2 == seq && d > 0L) {
            return d / 1000000L;
        }
        return -1L;
    }

    /** Uma amostra da pilha, com o contexto lido antes e depois. */
    static final class Amostra {
        long msDesdeInicio;
        StackTraceElement[] pilha;
        Object entidade;
        Object tile;
        Object pacote;
        Object jogador;
        boolean contextoMudou;
        /** O tick ja tinha acabado quando a amostra foi conferida: a pilha pode ser de outro momento. */
        boolean depoisDoFim;
    }

    private void relatar(long seq, long ini) {
        Thread alvo = fonte.thread();
        List<Amostra> amostras = new ArrayList<Amostra>();
        for (int i = 0; i < Math.max(1, a.amostras) && !parar; i++) {
            if (i > 0) {
                dormir(a.intervaloAmostrasMs);
                if (parar || !mesmoTick(seq, ini)) {
                    break;
                }
            }
            Amostra am = amostrar(alvo, ini);
            if (!mesmoTick(seq, ini)) {
                if (i > 0) {
                    break; // a pilha pode ja ser do tick seguinte
                }
                // 1a amostra: fica (e a unica prova), marcada; seus mods e contexto nao contam (revisao M5)
                am.depoisDoFim = true;
                amostras.add(am);
                break;
            }
            amostras.add(am);
        }
        if (amostras.isEmpty()) {
            return; // parada pedida antes da 1a amostra: nada a relatar
        }
        // espera o tick acabar (olhando a flag de parada a cada volta). Se a parada chegar, grava JA o que
        // tem (revisao I1: e quando o admin reage ao travamento que a prova mais importa); o parar() espera
        // no maximo esperaPararMs por isso.
        long limiteEspera = tempo.nanos() + a.esperaFimMs * 1000000L;
        while (!parar && mesmoTick(seq, ini) && tempo.nanos() - limiteEspera < 0L) {
            dormir(Math.min(a.pollMs, 50L));
        }
        boolean parando = parar;
        boolean terminou = !mesmoTick(seq, ini);
        long durMs = terminou ? duracaoSeTerminou(seq, ini) : -1L;
        boolean exato = durMs >= 0;
        if (!exato) {
            durMs = (tempo.nanos() - ini) / 1000000L; // ainda rodando, ou fim nao anotado: o tempo visto pelo vigia
        }
        long[] gcFim = lerGc();
        String texto = montarTexto(seq, terminou, exato, durMs, amostras, gcBaseLento, gcFim, parando);
        if (desistiu) {
            return; // quem parou ja desistiu de esperar: a JVM pode estar saindo
        }
        vezesRelatado++;
        if (terminou) {
            fimJaInformado = true;
        }
        relatorios++;
        ultimoRelatorioNanos = tempo.nanos();
        suprimidosDesdeUltimo = 0;
        maiorSuprimidoMs = 0;
        soNoFimDesdeUltimo = 0;
        maiorSoNoFimMs = 0;
        saida.relatorio(seq, texto);
        if (terminou && durMs > maiorMs) {
            maiorMs = durMs;
        }
    }

    private boolean mesmoTick(long seq, long ini) {
        long s1 = fonte.seq();
        long i = fonte.inicioNanos();
        long s2 = fonte.seq();
        return s1 == seq && s2 == seq && i == ini;
    }

    private Amostra amostrar(Thread alvo, long ini) {
        Amostra am = new Amostra();
        Object e1 = fonte.entidade();
        Object t1 = fonte.tile();
        Object p1 = protegido(true);
        Object j1 = protegido(false);
        StackTraceElement[] pilha;
        try {
            pilha = alvo == null ? new StackTraceElement[0] : amostrador.pilha(alvo);
        } catch (Throwable t) {
            pilha = new StackTraceElement[0];
        }
        am.msDesdeInicio = (tempo.nanos() - ini) / 1000000L;
        Object e2 = fonte.entidade();
        Object t2 = fonte.tile();
        Object p2 = protegido(true);
        Object j2 = protegido(false);
        am.pilha = pilha == null ? new StackTraceElement[0] : pilha;
        am.contextoMudou = e1 != e2 || t1 != t2 || p1 != p2 || j1 != j2;
        am.entidade = e1 == e2 ? e1 : null;
        am.tile = t1 == t2 ? t1 : null;
        am.pacote = p1 == p2 ? p1 : null;
        am.jogador = j1 == j2 ? j1 : null;
        return am;
    }

    private Object protegido(boolean pacote) {
        try {
            return pacote ? ctx.ultimoPacote() : ctx.jogadorDaRede();
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ texto do relatorio

    String montarTexto(long seq, boolean terminou, boolean exato, long durMs, List<Amostra> amostras, long[] gcIni, long[] gcFim,
            boolean parando) {
        StringBuilder sb = new StringBuilder(4096);
        long dur = durMs;
        sb.append("TICK LENTO #").append(seq).append(": ");
        if (!terminou) {
            sb.append("CONTINUA TRAVADO ha ").append(dur).append(" ms");
        } else if (!exato) {
            sb.append("cerca de ").append(dur).append(" ms (ja terminou; fim visto pelo vigia, nao anotado pelo tick)");
        } else {
            sb.append(dur).append(" ms (ja terminou)");
        }
        sb.append("; limite ").append(a.limiteMs).append(" ms; thread '")
                .append(fonte.thread() == null ? "?" : fonte.thread().getName()).append("'.\n");
        if (parando) {
            sb.append("  SERVIDOR PARANDO: relatorio gravado na parada, com as amostras ja colhidas (sem esperar o tick acabar).\n");
        }

        // GC
        long pausas = gcTexto(sb, gcIni, gcFim);
        if (dur > 0 && pausas * 2 >= dur) {
            sb.append("  ATENCAO: pausas de GC somam ").append(pausas).append(" ms de ").append(dur)
                    .append(" ms: a causa provavel e coleta de lixo (memoria), nao o codigo da pilha abaixo.\n");
        }

        // contexto: pacote e jogador da primeira amostra com contexto estavel; entidade e tile de todas
        Amostra base = null;
        for (Amostra am : amostras) {
            if (!am.contextoMudou && !am.depoisDoFim) {
                base = am;
                break;
            }
        }
        if (base == null) {
            for (Amostra am : amostras) {
                if (!am.depoisDoFim) {
                    base = am;
                    break;
                }
            }
        }
        int n = amostras.size();
        if (base != null) {
            sb.append("  Ultimo pacote visto (o Crucible nao limpa se o pacote falhar): ")
                    .append(base.pacote == null ? "nenhum" : CamposNumericos.descrever(base.pacote))
                    .append(contagem(amostras, base.pacote, 2)).append('\n');
            sb.append("  Jogador do pacote: ").append(base.jogador == null ? "nenhum" : seguro(base.jogador, 2))
                    .append(contagem(amostras, base.jogador, 3)).append('\n');
        }
        sb.append("  Entidade em atualizacao: ").append(distintos(amostras, 0)).append('\n');
        sb.append("  Tile em atualizacao: ").append(distintos(amostras, 1)).append('\n');

        // mods na pilha (somando as amostras)
        List<String> mods = modsNasAmostras(amostras);
        boolean todasDepois = !amostras.isEmpty();
        for (Amostra am : amostras) {
            todasDepois &= am.depoisDoFim;
        }
        if (todasDepois) {
            sb.append("  Mods na pilha: nao somados (a unica amostra pode ser de depois do fim do tick; veja a pilha abaixo)\n");
        } else if (mods.isEmpty()) {
            sb.append("  Mods na pilha: nenhum mod identificado (so Minecraft/Forge/Bukkit/JDK, ou classe fora dos jars)\n");
        } else {
            sb.append("  Mods na pilha (mais amostras primeiro; empate: o mais perto do topo): ");
            for (int i = 0; i < mods.size(); i++) {
                sb.append(i == 0 ? "" : " | ").append(mods.get(i));
            }
            sb.append('\n');
        }
        String ind = resumoDonos();
        if (ind != null) {
            sb.append("  (").append(ind).append(")\n");
        }

        // amostras
        int mesmoTopo = 0;
        String topo0 = n > 0 ? topo(amostras.get(0).pilha) : null;
        for (Amostra am : amostras) {
            if (topo0 != null && topo0.equals(topo(am.pilha))) {
                mesmoTopo++;
            }
        }
        sb.append("  Amostras da pilha: ").append(n).append(" (a cada ").append(a.intervaloAmostrasMs)
                .append(" ms); mesmo quadro no topo em ").append(mesmoTopo).append('/').append(n).append('\n');
        StackTraceElement[] anterior = null;
        for (int i = 0; i < n; i++) {
            Amostra am = amostras.get(i);
            sb.append("  Amostra ").append(i + 1).append(" (").append(am.msDesdeInicio).append(" ms depois do inicio do tick")
                    .append(am.depoisDoFim ? "; ATENCAO: o tick acabou antes de a amostra ser conferida, a pilha pode ser de"
                            + " depois do fim (nao entra nos mods nem no contexto)" : "")
                    .append(am.contextoMudou ? "; o contexto mudou durante a amostra" : "").append("; entidade: ")
                    .append(am.entidade == null ? "nenhuma" : nomeCurto(am.entidade)).append("; tile: ")
                    .append(am.tile == null ? "nenhum" : nomeCurto(am.tile)).append("):");
            if (anterior != null && igual(anterior, am.pilha)) {
                sb.append(" pilha igual a anterior\n");
                continue;
            }
            sb.append('\n');
            int q = Math.min(am.pilha.length, Math.max(1, a.quadros));
            for (int k = 0; k < q; k++) {
                StackTraceElement f = am.pilha[k];
                sb.append("    at ").append(f);
                String d = donoDoQuadro(f);
                if (d != null) {
                    sb.append("  [").append(d).append(']');
                }
                sb.append('\n');
            }
            if (am.pilha.length > q) {
                sb.append("    ... mais ").append(am.pilha.length - q).append(" quadro(s)\n");
            }
            anterior = am.pilha;
        }
        if (suprimidosDesdeUltimo > 0) {
            sb.append("  Ticks lentos sem relatorio (limitador) desde o ultimo relatorio: ").append(suprimidosDesdeUltimo)
                    .append(" (o maior visto: ").append(maiorSuprimidoMs).append(" ms)\n");
        }
        if (soNoFimDesdeUltimo > 0) {
            sb.append("  Ticks acima do limite vistos so pelo fim (passaram do limite por menos de ").append(a.pollMs)
                    .append(" ms; sem amostra) desde o ultimo relatorio: ").append(soNoFimDesdeUltimo)
                    .append(" (o maior: ").append(maiorSoNoFimMs).append(" ms)\n");
        }
        return sb.toString();
    }

    /**
     * Se o coletor (bean de GC) mede pausa do jogo. Conferido para G1 (o coletor do servidor): "G1 Young/Old
     * Generation" = pausa, "G1 Concurrent GC" = concorrente. Outros, por nome (nao verificado em servidor):
     * com ZGC/Shenandoah ha beans "... Pauses" (pausa) e "... Cycles" (trabalho concorrente): se existe algum
     * "Pauses", so eles contam. "ConcurrentMarkSweep" (CMS, Java 8) conta como pausa porque uma coleta
     * completa parada cai nele (pode incluir trabalho concorrente: o texto avisa). Revisao M8.
     */
    public static boolean contaComoPausa(String nome, boolean existePauses) {
        String n = nome == null ? "" : nome.toLowerCase(Locale.ROOT);
        if (existePauses) {
            return n.contains("pauses");
        }
        if (n.contains("concurrentmarksweep")) {
            return true;
        }
        return !n.contains("concurrent") && !n.contains("cycles");
    }

    /** Escreve a linha de GC e devolve a soma das pausas (ver {@link #contaComoPausa}). */
    private long gcTexto(StringBuilder sb, long[] ini, long[] fim) {
        int q;
        try {
            q = gc == null ? 0 : gc.quantos();
        } catch (Throwable t) {
            q = 0;
        }
        if (q == 0 || ini == null || fim == null || ini.length != 2 * q || fim.length != 2 * q) {
            sb.append("  GC durante o tick: nao medido\n");
            return 0L;
        }
        long pausas = 0L;
        String[] nomes = new String[q];
        boolean existePauses = false;
        for (int i = 0; i < q; i++) {
            try {
                nomes[i] = gc.nome(i);
            } catch (Throwable t) {
                nomes[i] = "?";
            }
            existePauses |= nomes[i] != null && nomes[i].toLowerCase(Locale.ROOT).contains("pauses");
        }
        sb.append("  GC durante o tick (por coletor; desde antes do tick comecar): ");
        for (int i = 0; i < q; i++) {
            long dt = Math.max(0L, fim[2 * i] - ini[2 * i]);
            long dc = Math.max(0L, fim[2 * i + 1] - ini[2 * i + 1]);
            String nome = nomes[i];
            boolean pausa = contaComoPausa(nome, existePauses);
            sb.append(i == 0 ? "" : "; ").append(nome).append(' ').append(dt).append(" ms em ").append(dc).append(" coleta(s)");
            if (!pausa) {
                sb.append(" (concorrente: roda junto com o jogo; pode incluir pausas curtas, nao verificado)");
            } else {
                pausas += dt;
                if (nome != null && nome.toLowerCase(Locale.ROOT).contains("concurrentmarksweep")) {
                    sb.append(" (CMS: contado como pausa; pode incluir trabalho concorrente, nao verificado)");
                }
            }
        }
        sb.append('\n');
        return pausas;
    }

    private long[] lerGc() {
        try {
            if (gc == null) {
                return null;
            }
            int q = gc.quantos();
            long[] v = new long[2 * q];
            for (int i = 0; i < q; i++) {
                v[2 * i] = gc.tempoMs(i);
                v[2 * i + 1] = gc.coletas(i);
            }
            return v;
        } catch (Throwable t) {
            return null;
        }
    }

    /** " (o mesmo em k/N amostras)" para o objeto de contexto. */
    private static String contagem(List<Amostra> amostras, Object o, int tipo) {
        if (o == null || amostras.size() < 2) {
            return "";
        }
        int k = 0;
        for (Amostra am : amostras) {
            Object x = tipo == 0 ? am.entidade : tipo == 1 ? am.tile : tipo == 2 ? am.pacote : am.jogador;
            if (x == o) {
                k++;
            }
        }
        return " (o mesmo em " + k + "/" + amostras.size() + " amostras)";
    }

    /** Descricao do objeto pelo Contexto do jogo; erro vira "?" com o nome da classe. tipo: 0 entidade, 1 tile, 2 jogador. */
    private String seguro(Object o, int tipo) {
        try {
            String s = tipo == 0 ? ctx.entidade(o, a.nomeDoJogador) : tipo == 1 ? ctx.tile(o) : ctx.jogador(o, a.nomeDoJogador);
            String d = donoDaClasse(o.getClass().getName());
            return d != null ? s + " [" + d + "]" : s;
        } catch (Throwable t) {
            return o.getClass().getName() + " (? " + t.getClass().getName() + ")";
        }
    }

    /** Entidades (tipo 0) ou tiles (tipo 1) distintos das amostras, descritos, com "em k/N amostras". */
    private String distintos(List<Amostra> amostras, int tipo) {
        List<Object> vistos = new ArrayList<Object>();
        List<Integer> vezes = new ArrayList<Integer>();
        for (Amostra am : amostras) {
            Object o = tipo == 0 ? am.entidade : am.tile;
            if (o == null || am.depoisDoFim) {
                continue;
            }
            int k = -1;
            for (int i = 0; i < vistos.size(); i++) {
                if (vistos.get(i) == o) {
                    k = i;
                    break;
                }
            }
            if (k < 0) {
                vistos.add(o);
                vezes.add(1);
            } else {
                vezes.set(k, vezes.get(k) + 1);
            }
        }
        if (vistos.isEmpty()) {
            return tipo == 0 ? "nenhuma" : "nenhum";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < vistos.size() && i < 4; i++) {
            sb.append(i == 0 ? "" : " | ").append(seguro(vistos.get(i), tipo));
            if (amostras.size() > 1) {
                sb.append(" (em ").append(vezes.get(i)).append('/').append(amostras.size()).append(" amostras)");
            }
        }
        if (vistos.size() > 4) {
            sb.append(" | ... mais ").append(vistos.size() - 4);
        }
        return sb.toString();
    }

    private static String nomeCurto(Object o) {
        return CamposNumericos.nomeSimples(o.getClass());
    }

    /** Donos somando as amostras: os que aparecem em mais amostras primeiro; empate, o mais perto do topo. */
    List<String> modsNasAmostras(List<Amostra> amostras) {
        final Map<String, int[]> conta = new LinkedHashMap<String, int[]>(); // {amostras, menor profundidade}
        Map<String, String> quadro = new LinkedHashMap<String, String>();
        for (Amostra am : amostras) {
            if (am.depoisDoFim) {
                continue; // pilha talvez de outro momento (revisao M5)
            }
            Map<String, Integer> nesta = new LinkedHashMap<String, Integer>();
            for (int k = 0; k < am.pilha.length; k++) {
                StackTraceElement f = am.pilha[k];
                String d = donoDoQuadro(f);
                if (d != null && !nesta.containsKey(d)) {
                    nesta.put(d, k);
                    if (!quadro.containsKey(d)) {
                        quadro.put(d, f.getClassName() + "." + f.getMethodName());
                    }
                }
            }
            for (Map.Entry<String, Integer> e : nesta.entrySet()) {
                int[] c = conta.get(e.getKey());
                if (c == null) {
                    conta.put(e.getKey(), new int[] {1, e.getValue()});
                } else {
                    c[0]++;
                    c[1] = Math.min(c[1], e.getValue());
                }
            }
        }
        List<String> chaves = new ArrayList<String>(conta.keySet());
        java.util.Collections.sort(chaves, new java.util.Comparator<String>() {
            @Override
            public int compare(String x, String y) {
                int[] a1 = conta.get(x);
                int[] a2 = conta.get(y);
                return a1[0] != a2[0] ? a2[0] - a1[0] : a1[1] - a2[1];
            }
        });
        List<String> l = new ArrayList<String>();
        for (int i = 0; i < chaves.size() && i < 6; i++) {
            String k = chaves.get(i);
            l.add(k + " (" + quadro.get(k) + (amostras.size() > 1 ? "; em " + conta.get(k)[0] + "/" + amostras.size() + " amostras" : "") + ")");
        }
        return l;
    }

    /** Os donos distintos dos quadros da pilha, de cima para baixo (no maximo 6), como "jar (classe.metodo)". */
    public List<String> modsNaPilha(StackTraceElement[] pilha) {
        Map<String, String> vistos = new LinkedHashMap<String, String>();
        for (StackTraceElement f : pilha) {
            String d = donoDoQuadro(f);
            if (d != null && !vistos.containsKey(d)) {
                vistos.put(d, f.getClassName() + "." + f.getMethodName());
                if (vistos.size() >= 6) {
                    break;
                }
            }
        }
        List<String> l = new ArrayList<String>();
        for (Map.Entry<String, String> e : vistos.entrySet()) {
            l.add(e.getKey() + " (" + e.getValue() + ")");
        }
        return l;
    }

    /** Dono de um quadro da pilha: codigo de mixin pelo nome do metodo, jar pela classe, ou null se neutro/desconhecido. */
    String donoDoQuadro(StackTraceElement f) {
        String m = f.getMethodName();
        for (String p : MIXIN) {
            if (m.startsWith(p)) {
                String[] partes = m.split("\\$");
                return partes.length >= 4 ? "mixin " + partes[2] : "mixin";
            }
        }
        String c = f.getClassName();
        if (neutro(c)) {
            return null;
        }
        return donoDaClasse(c);
    }

    private String donoDaClasse(String c) {
        if (c.startsWith("hoshiestar.")) {
            return "Hoshiestar (server.jar)";
        }
        if (neutro(c)) {
            return null;
        }
        try {
            return donos == null ? null : donos.dono(c);
        } catch (Throwable t) {
            return null;
        }
    }

    private String resumoDonos() {
        try {
            return donos == null ? null : donos.resumo();
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean neutro(String c) {
        for (String p : NEUTROS) {
            if (c.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    private static String topo(StackTraceElement[] p) {
        return p == null || p.length == 0 ? null : p[0].toString();
    }

    private static boolean igual(StackTraceElement[] x, StackTraceElement[] y) {
        if (x.length != y.length) {
            return false;
        }
        for (int i = 0; i < x.length; i++) {
            if (!x[i].equals(y[i])) {
                return false;
            }
        }
        return true;
    }

    private void dormir(long ms) {
        if (ms <= 0) {
            return;
        }
        synchronized (espera) {
            if (parar) {
                return;
            }
            try {
                espera.wait(ms);
            } catch (InterruptedException e) {
                parar = true; // ninguem do jogo interrompe esta thread; se alguem interromper, ela para
            }
        }
    }

    private void erro(String msg, Throwable t) {
        try {
            saida.erro(msg, t);
        } catch (Throwable ignorado) {
            // o log nunca derruba o vigia
        }
    }

    // ------------------------------------------------------------------ status

    /** Linha do "hoshie status". */
    public String linha() {
        Thread t = thread;
        StringBuilder sb = new StringBuilder();
        sb.append("limite ").append(a.limiteMs).append(" ms; thread ")
                .append(t == null ? "nao iniciada" : t.isAlive() ? "viva" : "parada")
                .append(desligadoPorErro ? " (SE DESLIGOU por erros internos)" : "")
                .append("; ticks lentos: ").append(ticksLentos)
                .append("; relatorios: ").append(relatorios)
                .append(" (").append(limite.usadosNaJanela()).append('/').append(limite.maxPorJanela()).append(" na janela)")
                .append("; sem relatorio (limitador): ").append(suprimidos);
        if (soNoFim > 0) {
            sb.append("; vistos so pelo fim (sem amostra): ").append(soNoFim);
        }
        long m = maiorMs;
        if (m > 0) {
            sb.append("; maior tick lento: ").append(m).append(" ms");
        }
        long u = ultimoRelatorioNanos;
        if (relatorios > 0) {
            sb.append("; ultimo relatorio ha ").append(Math.max(0L, (tempo.nanos() - u) / 1000000000L)).append(" s");
        }
        if (naCarencia > 0) {
            sb.append("; na carencia do boot: ").append(naCarencia).append(" tick(s) lento(s), o maior ").append(maiorNaCarenciaMs).append(" ms");
        }
        if (errosInternos > 0) {
            sb.append("; ERROS INTERNOS: ").append(errosInternos);
        }
        return sb.toString();
    }

    public long relatorios() {
        return relatorios;
    }

    public long suprimidos() {
        return suprimidos;
    }

    public long ticksLentos() {
        return ticksLentos;
    }

    public long soNoFim() {
        return soNoFim;
    }

    public long naCarencia() {
        return naCarencia;
    }

    public long errosInternos() {
        return errosInternos;
    }

    public Thread thread() {
        return thread;
    }
}
