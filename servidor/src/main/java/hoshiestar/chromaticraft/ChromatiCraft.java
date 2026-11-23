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
package hoshiestar.chromaticraft;

import hoshiestar.api.HashClasse;
import hoshiestar.api.Limitador;
import hoshiestar.api.Log;
import hoshiestar.api.Recursos;
import hoshiestar.api.Trava;
import hoshiestar.chromaticraft.DecisaoShift.Motivo;
import hoshiestar.config.HoshiestarConfig;
import hoshiestar.embutidos.Correcao;
import hoshiestar.embutidos.Correcoes;
import hoshiestar.embutidos.asm.Asm;
import hoshiestar.embutidos.asm.Chamada;
import hoshiestar.embutidos.asm.Gancho;
import hoshiestar.embutidos.asm.PadraoNaoEncontrado;
import hoshiestar.embutidos.asm.ResolvedorDeGanchos;
import org.objectweb.asm.tree.ClassNode;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Recurso "chromaticraft" (item 1.B): limites na habilidade "mover area" (SHIFT) do ChromatiCraft, que travou
 * o servidor por mais de 30 s (D-21).
 *
 * <p><b>O problema.</b> {@code AbilityCalls.shiftArea} percorre a caixa que o jogador marcou com dois cliques
 * (sem limite de tamanho; o primeiro canto fica guardado para sempre; com a habilidade REACH o clique alcanca 192
 * blocos), na thread do servidor, e so DEPOIS calcula custo e permissao. A conta de custo da 1 para caixa de uma
 * camada, de so ar, de distancia negativa ou grande demais para caber em int. O pacote que arma a habilidade
 * nao confere a distancia. O original tambem apaga a origem antes de descobrir que o destino sai de y 0..255
 * (bloco perdido) e le (e portanto carrega ou gera) chunks de qualquer lugar.
 *
 * <p><b>A correcao</b> (formato 1 PROTEGIDO da base). Uma chamada ao gancho {@code GanchoShift.recusar} entra no INICIO de
 * {@code shiftArea}, com retorno antecipado (false), dentro de um try/catch de {@code Throwable} posto no bytecode: se o
 * gancho nao ligar em tempo de execucao ({@code NoClassDefFoundError}, {@code NoSuchMethodError}) ou lancar, o
 * {@code shiftArea} segue pelo corpo original ({@link #falhaGancho} so conta e registra). Ela so le: {@link DecisaoShift} confere intervalo minimo por
 * jogador, distancia, volume, altura do destino e chunks carregados. Se recusa, repete o ramo original de "sem
 * energia" (som de erro, habilidade desligada no jogador, devolve false), manda uma mensagem no chat e escreve uma
 * linha de log com limite de frequencia. Se aceita, o original roda INTEIRO, sem nenhuma mudanca no laco.
 * Uma segunda correcao, opcional ({@code chromaticraft.loglento} &gt; 0), so mede e registra no log as chamadas lentas:
 * envolve (formato 3 PROTEGIDO) a chamada final {@code Chromabilities.setToPlayer} (a ultima coisa que
 * {@code shiftArea} faz antes de devolver) e a chamada do som de erro; o gancho so mede e responde "chame a original",
 * e a chamada original fica fora do try. Se o gancho falhar, a chamada original acontece do mesmo jeito.
 * Se a auto-checagem do boot achar defeito nos ganchos ({@link #ganchoComDefeito}), nenhuma das duas correcoes e
 * aplicada na classe do mod, mesmo que ela carregue depois.
 *
 * <p><b>Trava de versao</b>: a classe {@value #CLASSE} e as classes de cuja forma a logica depende
 * ({@link #DEPENDENCIAS}). Qualquer uma diferente ou ausente: a correcao nao e aplicada (o mod fica original).
 *
 * <p>Roda na fase de coremod: so JDK, ASM e hoshiestar.* (nada do Minecraft, Forge, Bukkit ou ChromatiCraft). O
 * estado e os textos ficam aqui (e nao no gancho) para o teste isolado e a linha de estado os usarem sem carregar
 * classes do Minecraft.
 */
public final class ChromatiCraft {

    public static final String ID = "chromaticraft";

    /** Classe alvo (nome com pontos). */
    public static final String CLASSE = "Reika.ChromatiCraft.Auxiliary.Ability.AbilityCalls";

    /** SHA-256 de AbilityCalls.class em "ChromatiCraft 1.7.10 V33a.jar". */
    public static final String SHA_ABILITYCALLS = "8bcbe85ec63bca6a6146081f93b1ea4202dcd7fd9d3b06f635649df8a80be7a5";

    /**
     * Classes de cuja forma EXATA a logica depende, com o SHA-256 aceito de cada uma (das jars "ChromatiCraft 1.7.10
     * V33a.jar" e "DragonAPI 1.7.10 V33b-ForbiddenFix1.jar"). Conferidas quando a classe alvo passa pelo
     * transformador, lendo o arquivo .class (sem carregar).
     * <ul>
     * <li>BlockBox (DragonAPI): campos publicos minX..maxZ e {@code getSizeX/Y/Z() = max - min}, o que o laco do
     * {@code shiftArea} consulta; o volume que calculamos tem de ser igual ao numero de voltas.</li>
     * <li>BlockArray e FilledBlockArray (DragonAPI): {@code offset(dir, dist)} desloca cada eixo em
     * {@code direcao.offsetX/Y/Z * dist}; e a conta do destino que repetimos.</li>
     * <li>Chromabilities (ChromatiCraft): {@code SHIFT.getMaxPower()} = 24; {@code setToPlayer(ep, boolean)} liga
     * e desliga a habilidade no jogador (o que o ramo de recusa chama).</li>
     * <li>AbilityHelper (ChromatiCraft): unico chamador de {@code shiftArea}; ignora o valor devolvido e depois
     * tira o primeiro clique guardado (por isso recusar nao deixa estado preso).</li>
     * </ul>
     */
    static final String[][] DEPENDENCIAS = {
        {"Reika.DragonAPI.Instantiable.Data.Immutable.BlockBox", "e5175ca3fe44a9de1cbd78c1dedb2c0a148d870891d17a35eaa3259e59c7b1d8"},
        {"Reika.DragonAPI.Instantiable.Data.BlockStruct.BlockArray", "1dd1a2f75e9fe94517050b9cc4f905dede8178576ccfb492c532738f245264ab"},
        {"Reika.DragonAPI.Instantiable.Data.BlockStruct.FilledBlockArray", "1476505c756c6c346324eb85151feea34b01cda50a57b60cba7f27cdf4507bb8"},
        {"Reika.ChromatiCraft.Registry.Chromabilities", "0d4ce3736ea1cb3fb9360582579975f1458dd2bce239a44abd9e688015694981"},
        {"Reika.ChromatiCraft.Auxiliary.Ability.AbilityHelper", "3bbcc0779892a586eee1eb4908c1bea09dcbc85f533f136a7539ad44df7f6706"},
    };

    /** Classe dos ganchos (src/mods/java). */
    public static final String GANCHO = "hoshiestar/chromaticraft/shift/GanchoShift";

    /**
     * Classe (so JDK, sem tipo do Minecraft nem do mod: tem de ligar mesmo quando o {@link #GANCHO} nao liga) com o
     * gancho "aoFalhar" das duas correcoes.
     */
    public static final String FALHAS = "hoshiestar/chromaticraft/ChromatiCraft";

    static final String METODO = "shiftArea";
    static final String DESC_SHIFT = "(Lnet/minecraft/world/WorldServer;LReika/DragonAPI/Instantiable/Data/Immutable/BlockBox;"
            + "Lnet/minecraftforge/common/util/ForgeDirection;ILnet/minecraft/entity/player/EntityPlayerMP;)Z";

    /** A chamada final do metodo (so uma): Chromabilities.SHIFT.setToPlayer(ep, false). */
    static final Chamada SET_TO_PLAYER = Chamada.de("Reika/ChromatiCraft/Registry/Chromabilities", "setToPlayer",
            "(Lnet/minecraft/entity/player/EntityPlayer;Z)V");
    /** O som do ramo de falha (so uma chamada): ChromaSounds.ERROR.playSound(ep). */
    static final Chamada SOM_DE_ERRO = Chamada.de("Reika/ChromatiCraft/Registry/ChromaSounds", "playSound",
            "(Lnet/minecraft/entity/Entity;)V");

    static final String MENSAGEM_PADRAO = "Mover area recusado: {motivo}.";

    // ---------------------------------------------------------------- estado

    /** A decisao em vigor (criada no boot se o recurso esta ligado); null se desligado. */
    public static volatile DecisaoShift decisao;

    /** Interruptor em execucao: o comando "hoshie chromaticraft desligar" o baixa; os ganchos passam a seguir o original. */
    public static volatile boolean ativo;

    /** Modelo da mensagem do chat ({motivo}); vazio = nao manda mensagem. */
    public static volatile String mensagem = MENSAGEM_PADRAO;

    /** Chamadas mais lentas que isto (ms) vao para o log (0 = a segunda correcao nem entra). */
    public static volatile int lentoMs;

    /** Segundos entre duas linhas de log de recusa do mesmo jogador (minimo 1). */
    public static volatile int logSegundos = 10;

    /** As correcoes registradas no boot (a linha de estado diz se foram aplicadas). Null antes do registro. */
    static volatile Correcao correcaoDecisao;
    static volatile Correcao correcaoLog;

    /** Motivo do problema de ligacao dos ganchos achado ao ligar o servidor, ou null. */
    public static volatile String ganchoComDefeito;

    /** Quantas vezes um gancho falhou em execucao (nao ligou ou lancou) e o pedido seguiu pelo original; e a ultima falha. */
    public static volatile long falhasDeGancho;
    public static volatile String ultimaFalhaDeGancho;

    // contadores (escritos pela thread do servidor, exceto erros: synchronized)
    public static volatile long erros;
    public static volatile long lentas;
    public static volatile long maiorLentoMs;
    public static volatile long recusasLogadas;

    private static volatile Limitador limRecusa = new Limitador(1, 10000L);
    private static final Limitador LIM_LENTA = new Limitador(10, 10000L);
    private static final Limitador LIM_ERRO = new Limitador(5, 60000L);

    private ChromatiCraft() {
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
        boolean ligado = cfg.chromaticraft_ligado;
        long intervalo = cfg.chromaticraft_intervalo;
        if (intervalo < 0) {
            Log.de(ID).a("chromaticraft.intervalo = " + intervalo + " negativo; usando 1000 (ms)");
            intervalo = 1000;
        }
        long volume = cfg.chromaticraft_volume;
        if (volume < 0) {
            Log.de(ID).a("chromaticraft.volume = " + volume + " negativo; usando 4096");
            volume = 4096;
        }
        int lento = cfg.chromaticraft_loglento;
        if (lento < 0) {
            Log.de(ID).a("chromaticraft.loglento = " + lento + " negativo; usando 0 (desligado)");
            lento = 0;
        }
        int logSeg = cfg.chromaticraft_logsegundos;
        if (logSeg < 1) {
            Log.de(ID).a("chromaticraft.logsegundos = " + logSeg + " menor que 1; usando 1");
            logSeg = 1;
        }
        decisao = ligado ? new DecisaoShift(intervalo, cfg.chromaticraft_distancia, volume, cfg.chromaticraft_altura,
                cfg.chromaticraft_chunks) : null;
        ativo = ligado;
        mensagem = cfg.chromaticraft_mensagem == null ? MENSAGEM_PADRAO : cfg.chromaticraft_mensagem;
        lentoMs = lento;
        logSegundos = logSeg;
        limRecusa = new Limitador(1, logSeg * 1000L);
        ganchoComDefeito = null;
        falhasDeGancho = 0;
        ultimaFalhaDeGancho = null;
        erros = lentas = maiorLentoMs = recusasLogadas = 0;
        Recursos.registrar(ID, "ChromatiCraft: limites na habilidade de mover area (intervalo, distancia, volume, altura, chunks carregados)", ligado)
                .estado(new Recursos.Estado() {
                    @Override
                    public String linha() {
                        return linhaDeEstado();
                    }
                });
        final ClassLoader cl = carregador;
        Correcao decide = Correcao.nova(ID, "gancho no inicio de AbilityCalls.shiftArea (recusa antes de ler ou mexer em bloco)",
                Trava.de(CLASSE, SHA_ABILITYCALLS), ligado, new Correcao.Transformacao() {
                    @Override
                    public void aplicar(ClassNode classe, ResolvedorDeGanchos ganchos) throws PadraoNaoEncontrado {
                        transformarDecisao(classe, ganchos, cl != null ? cl : HashClasse.carregadorDosMods());
                    }
                });
        Correcao mede = Correcao.nova(ID, "mede o tempo de AbilityCalls.shiftArea e registra no log as chamadas lentas",
                Trava.de(CLASSE, SHA_ABILITYCALLS), ligado && lento > 0, new Correcao.Transformacao() {
                    @Override
                    public void aplicar(ClassNode classe, ResolvedorDeGanchos ganchos) throws PadraoNaoEncontrado {
                        transformarMedicao(classe, ganchos);
                    }
                });
        correcaoDecisao = decide;
        correcaoLog = mede;
        correcoes.adicionar(decide);
        correcoes.adicionar(mede);
    }

    /**
     * A transformacao principal: confere as travas das dependencias (sem carregar classe) e poe o gancho com
     * retorno antecipado no inicio de shiftArea. Qualquer problema lanca PadraoNaoEncontrado antes de a base
     * aceitar a arvore (a classe fica original).
     */
    static void transformarDecisao(ClassNode cn, ResolvedorDeGanchos ganchos, ClassLoader carregador) throws PadraoNaoEncontrado {
        recusarSeGanchoComDefeito();
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
        Asm.ganchoNoInicioProtegido(cn, METODO, DESC_SHIFT, Gancho.de(GANCHO, "recusar"), Gancho.de(GANCHO, "valorRecusado"), true,
                Gancho.de(FALHAS, "falhaGancho"), ganchos);
    }

    /** Se a auto-checagem do boot reprovou os ganchos, a classe do mod fica original (o gancho nao ligaria). */
    private static void recusarSeGanchoComDefeito() throws PadraoNaoEncontrado {
        String defeito = ganchoComDefeito;
        if (defeito != null) {
            throw new PadraoNaoEncontrado("a auto-checagem dos ganchos reprovou neste boot (" + defeito + ")");
        }
    }

    /**
     * A medicao: as DUAS chamadas tem de aparecer exatamente uma vez em shiftArea; senao nada e mudado. Cada uma passa por
     * uma ponte protegida (formato 3): o gancho so mede e responde true ("chame a original"); a chamada original fica fora
     * do try, e acontece mesmo se o gancho falhar.
     */
    static void transformarMedicao(ClassNode cn, ResolvedorDeGanchos ganchos) throws PadraoNaoEncontrado {
        recusarSeGanchoComDefeito();
        Asm.envolverChamadaProtegida(cn, METODO, DESC_SHIFT, SOM_DE_ERRO, 0, 1, Gancho.de(GANCHO, "antesDoSom"), null,
                Gancho.de(FALHAS, "falhaGancho"), ganchos);
        Asm.envolverChamadaProtegida(cn, METODO, DESC_SHIFT, SET_TO_PLAYER, 0, 1, Gancho.de(GANCHO, "antesDeTerminar"), null,
                Gancho.de(FALHAS, "falhaGancho"), ganchos);
    }

    // ---------------------------------------------------------------- textos

    /** O texto curto do motivo (vai no chat e no log). */
    public static String textoDoMotivo(Motivo m, long volume, long volumeMaximo, int dist, int potenciaMaxima) {
        switch (m) {
            case INTERVALO:
                return "aguarde um instante antes de mover outra area";
            case DISTANCIA:
                return "distancia " + dist + " invalida (de 0 a " + potenciaMaxima + ")";
            case VOLUME:
                return "area grande demais (" + volume + " blocos; limite " + volumeMaximo + ")";
            case ALTURA:
                return "o destino sairia da altura do mundo (y de 0 a " + DecisaoShift.ALTURA_MAXIMA + ")";
            case CHUNKS:
                return "parte da area ou do destino esta em chunks nao carregados";
            default:
                return "ok";
        }
    }

    /** A mensagem para o jogador (modelo configuravel com {motivo}); null ou vazio = nao manda nada. */
    public static String mensagemParaJogador(String modelo, String motivo) {
        if (modelo == null || modelo.isEmpty()) {
            return null;
        }
        return modelo.replace("{motivo}", motivo);
    }

    private static String caixa(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return "(" + minX + "," + minY + "," + minZ + ")-(" + maxX + "," + maxY + "," + maxZ + ")";
    }

    /** A linha de log de uma recusa. */
    public static String linhaDeRecusa(Motivo m, String jogador, int dimensao, int minX, int minY, int minZ, int maxX,
            int maxY, int maxZ, int dx, int dy, int dz, int dist, int potenciaMaxima, long volumeMaximo) {
        long vol = DecisaoShift.volume(minX, minY, minZ, maxX, maxY, maxZ);
        return "mover area RECUSADO (" + m.nome + "): jogador " + jogador + ", dimensao " + dimensao
                + ", caixa " + caixa(minX, minY, minZ, maxX, maxY, maxZ) + ", volume " + vol
                + (volumeMaximo > 0 ? " (limite " + volumeMaximo + ")" : "")
                + ", distancia " + dist + " (maximo " + potenciaMaxima + "), direcao (" + dx + "," + dy + "," + dz + "): "
                + textoDoMotivo(m, vol, volumeMaximo, dist, potenciaMaxima);
    }

    /** Escreve a linha da recusa no log, no maximo uma por jogador a cada {@link #logSegundos}. Nunca lanca. */
    public static void registrarRecusa(Motivo m, String jogador, int dimensao, int minX, int minY, int minZ, int maxX,
            int maxY, int maxZ, int dx, int dy, int dz, int dist, int potenciaMaxima) {
        try {
            long sup = limRecusa.tentar(jogador); // o limitador vem antes: recusa suprimida nao monta texto
            if (sup >= 0) {
                DecisaoShift d = decisao;
                String linha = linhaDeRecusa(m, jogador, dimensao, minX, minY, minZ, maxX, maxY, maxZ, dx, dy, dz, dist,
                        potenciaMaxima, d != null ? d.volumeMaximo : 0);
                recusasLogadas++;
                Log.de(ID).i(linha + (sup > 0 ? " (" + sup + " recusa(s) parecida(s) deste jogador suprimida(s) antes desta)" : ""));
            }
        } catch (Throwable t) {
            erro("registrarRecusa", t);
        }
    }

    /** Registra uma chamada lenta (medida pelo gancho de medicao), no maximo 10 linhas por 10 s por jogador. Nunca lanca. */
    public static void registrarLenta(String jogador, int dimensao, int minX, int minY, int minZ, int maxX, int maxY,
            int maxZ, int dist, long ms, boolean falhou) {
        try {
            lentas++;
            if (ms > maiorLentoMs) {
                maiorLentoMs = ms;
            }
            long sup = LIM_LENTA.tentar(jogador);
            if (sup >= 0) {
                Log.de(ID).i("mover area LENTO: " + ms + " ms, jogador " + jogador + ", dimensao " + dimensao
                        + ", caixa " + caixa(minX, minY, minZ, maxX, maxY, maxZ)
                        + ", volume " + DecisaoShift.volume(minX, minY, minZ, maxX, maxY, maxZ) + ", distancia " + dist
                        + ", resultado " + (falhou ? "falhou (sem energia ou sem permissao)" : "bloco(s) movido(s)")
                        + (sup > 0 ? " (" + sup + " parecida(s) suprimida(s) antes desta)" : ""));
            }
        } catch (Throwable t) {
            erro("registrarLenta", t);
        }
    }

    /** Excecao do nosso codigo: o pedido segue pelo original; sai um registro no log (com limitador). Nunca lanca. */
    public static void erro(String onde, Throwable t) {
        registrarErro(onde, t, "este pedido de mover area segue pelo caminho original do ChromatiCraft");
    }

    /**
     * Excecao do nosso codigo DENTRO do ramo de recusa (som, desligar, mensagem, log): o pedido ja estava recusado e
     * continua recusado; so aquele passo falhou. Nunca lanca.
     */
    public static void erroNaRecusa(String onde, Throwable t) {
        registrarErro(onde, t, "o pedido de mover area continua RECUSADO; so este passo da recusa falhou");
    }

    private static void registrarErro(String onde, Throwable t, String consequencia) {
        try {
            synchronized (ChromatiCraft.class) {
                erros++;
            }
            long sup = LIM_ERRO.tentar("erro");
            if (sup >= 0) {
                Log.de(ID).e("erro em " + onde + "; " + consequencia
                        + (sup > 0 ? " (" + sup + " erro(s) suprimido(s) antes deste)" : ""), t);
            }
        } catch (Throwable ignorado) {
            // nunca deixar escapar
        }
    }

    /**
     * O gancho "aoFalhar" das duas correcoes (chamado de dentro do bytecode de {@code shiftArea}, no tratador do try/catch
     * posto pelo transformador): um gancho nao ligou ({@code NoClassDefFoundError}, {@code NoSuchMethodError}) ou lancou
     * uma excecao que o proprio gancho nao pegou. O {@code shiftArea} segue pelo corpo original; aqui so se conta e se
     * registra (com limitador). So JDK e hoshiestar.api: tem de ligar mesmo quando o GanchoShift nao liga. Nunca lanca.
     */
    public static void falhaGancho(Throwable t) {
        try {
            synchronized (ChromatiCraft.class) {
                falhasDeGancho++;
                ultimaFalhaDeGancho = String.valueOf(t);
            }
            long sup = LIM_ERRO.tentar("gancho");
            if (sup >= 0) {
                Log.de(ID).e("um gancho do mover area FALHOU em execucao; este pedido segue pelo caminho original do ChromatiCraft"
                        + (sup > 0 ? " (" + sup + " falha(s) suprimida(s) antes desta)" : ""), t);
            }
        } catch (Throwable ignorado) {
            // nunca deixar escapar
        }
    }

    // ---------------------------------------------------------------- estado, servidor de pe

    /** Linha do "hoshie status" e do fim do boot. */
    public static String linhaDeEstado() {
        DecisaoShift d = decisao;
        StringBuilder sb = new StringBuilder();
        sb.append(ativo ? "ativo" : (d == null ? "desligado" : "DESLIGADO em execucao (hoshie chromaticraft)"));
        if (d == null) {
            return sb.toString();
        }
        sb.append(correcaoAplicada(correcaoDecisao) ? "; gancho aplicado"
                : (aguardandoClasse(correcaoDecisao) ? "; gancho ainda nao aplicado (a classe AbilityCalls ainda nao carregou: entra quando carregar)"
                        : "; gancho NAO aplicado (a classe do mod nao foi alterada: veja a correcao abaixo)"));
        if (ganchoComDefeito != null) {
            sb.append("; GANCHOS COM DEFEITO: ").append(ganchoComDefeito);
        }
        sb.append("; limites: intervalo ").append(d.intervaloMs > 0 ? d.intervaloMs + " ms" : "off")
                .append(", distancia ").append(d.validarDistancia ? "0..maximo da habilidade" : "off")
                .append(", volume ").append(d.volumeMaximo > 0 ? String.valueOf(d.volumeMaximo) : "sem limite")
                .append(", altura ").append(d.validarAltura ? "y 0..255" : "off")
                .append(", chunks ").append(d.exigirChunks ? "so carregados" : "off");
        long[] r = d.retrato(); // um retrato so (uma trava): a soma fecha
        sb.append("; pedidos ").append(r[0]).append(": aceitos ").append(r[1])
                .append(", recusados ").append(r[2])
                .append(" (intervalo ").append(r[3 + Motivo.INTERVALO.ordinal()])
                .append(", distancia ").append(r[3 + Motivo.DISTANCIA.ordinal()])
                .append(", volume ").append(r[3 + Motivo.VOLUME.ordinal()])
                .append(", altura ").append(r[3 + Motivo.ALTURA.ordinal()])
                .append(", chunks ").append(r[3 + Motivo.CHUNKS.ordinal()]).append(")");
        sb.append("; jogadores no mapa do intervalo ").append(d.jogadoresGuardados());
        if (lentoMs > 0) {
            sb.append("; medicao (>= ").append(lentoMs).append(" ms): ")
                    .append(correcaoAplicada(correcaoLog) ? "aplicada" : "NAO aplicada")
                    .append(", chamadas lentas ").append(lentas).append(", maior ").append(maiorLentoMs).append(" ms");
        } else {
            sb.append("; medicao de chamadas lentas desligada");
        }
        sb.append("; linhas de recusa no log ").append(recusasLogadas).append("; erros ").append(erros);
        long falhas = falhasDeGancho;
        if (falhas > 0) {
            sb.append("; GANCHOS FALHARAM EM EXECUCAO ").append(falhas).append(" vez(es) (os pedidos seguiram pelo original); ultima: ")
                    .append(ultimaFalhaDeGancho);
        }
        return sb.toString();
    }

    static boolean correcaoAplicada(Correcao c) {
        return c != null && c.estado() == Correcao.Estado.APLICADA;
    }

    /** Ligada, mas a classe alvo ainda nao passou pelo transformador (o gancho entra quando ela carregar). */
    static boolean aguardandoClasse(Correcao c) {
        return c != null && c.estado() == Correcao.Estado.AGUARDANDO;
    }

    /** Chamado por hoshiestar.Servidor.iniciando (servidor de pe): confere os ganchos e registra o subcomando. */
    public static void aoIniciarServidor() {
        if (HashClasse.bytesDoArquivo(CLASSE, HashClasse.carregadorDosMods()) == null) {
            Log.de(ID).i("ChromatiCraft ausente: subcomando 'hoshie chromaticraft' nao registrado");
            return;
        }
        if (decisao == null) {
            // recurso desligado: nada a conferir, mas o subcomando existe (mostra "desligado"; 'ligar' recusa e explica)
            hoshiestar.comando.ComandoHoshie.registrar(new ComandoChromatiCraft());
            return;
        }
        String motivo = verificarGanchos(HashClasse.carregadorDosMods());
        if (motivo == null) {
            Log.de(ID).i("ganchos conferidos com as classes de tempo de execucao: ligam com os descritores usados em shiftArea");
        } else {
            ganchoComDefeito = motivo;
            ativo = false;
            Log.de(ID).e("GANCHOS COM DEFEITO: " + motivo + ". Os limites ficam DESLIGADOS e a classe AbilityCalls NAO sera"
                    + " transformada se ainda nao carregou; se ja foi transformada, o mover area segue pelo caminho original (uma"
                    + " falha capturada por pedido, contada no 'hoshie chromaticraft'). Desligue chromaticraft.ligado no hoshiestar.yml"
                    + " e avise o projeto.", null);
        }
        hoshiestar.comando.ComandoHoshie.registrar(new ComandoChromatiCraft());
    }

    /**
     * Confere por reflexao, no class loader dado (o dos mods), que os ganchos ligam com os tipos de TEMPO DE
     * EXECUCAO: e o que a JVM faz ao ligar a chamada que a transformacao poe em shiftArea.
     *
     * @return null se tudo confere; senao o motivo (uma linha)
     */
    static String verificarGanchos(ClassLoader cl) {
        String passo = "carregar o gancho";
        try {
            Class<?> g = Class.forName(GANCHO.replace('/', '.'), true, cl);
            passo = "carregar as classes do Minecraft, do Forge e do ChromatiCraft";
            Class<?> mundo = Class.forName("net.minecraft.world.WorldServer", false, cl);
            Class<?> caixa = Class.forName("Reika.DragonAPI.Instantiable.Data.Immutable.BlockBox", false, cl);
            Class<?> direcao = Class.forName("net.minecraftforge.common.util.ForgeDirection", false, cl);
            Class<?> jogadorMp = Class.forName("net.minecraft.entity.player.EntityPlayerMP", false, cl);
            Class<?> jogador = Class.forName("net.minecraft.entity.player.EntityPlayer", false, cl);
            Class<?> entidade = Class.forName("net.minecraft.entity.Entity", false, cl);
            Class<?> habil = Class.forName("Reika.ChromatiCraft.Registry.Chromabilities", false, cl);
            Class<?> sons = Class.forName("Reika.ChromatiCraft.Registry.ChromaSounds", false, cl);
            passo = "conferir os ganchos";
            gancho(g, "recusar", boolean.class, mundo, caixa, direcao, int.class, jogadorMp);
            gancho(g, "valorRecusado", boolean.class, mundo, caixa, direcao, int.class, jogadorMp);
            if (lentoMs > 0) {
                gancho(g, "antesDoSom", boolean.class, sons, entidade);
                gancho(g, "antesDeTerminar", boolean.class, habil, jogador, boolean.class);
            }
            passo = "conferir o gancho 'aoFalhar' (so JDK)";
            gancho(Class.forName(FALHAS.replace('/', '.'), false, cl), "falhaGancho", void.class, Throwable.class);
            passo = "conferir as chamadas do ChromatiCraft que os ganchos usam";
            if (habil.getMethod("getMaxPower").getReturnType() != int.class) {
                return "Chromabilities.getMaxPower() nao devolve int";
            }
            habil.getMethod("setToPlayer", jogador, boolean.class);
            sons.getMethod("playSound", entidade);
            return null;
        } catch (Throwable t) {
            return "falhou ao " + passo + ": " + t;
        }
    }

    private static Method gancho(Class<?> dono, String nome, Class<?> retorno, Class<?>... params) throws NoSuchMethodException {
        Method m = dono.getMethod(nome, params);
        if (!Modifier.isStatic(m.getModifiers()) || m.getReturnType() != retorno) {
            throw new NoSuchMethodException("gancho " + nome + " nao e estatico com retorno " + retorno);
        }
        return m;
    }

    /** Zera os contadores do status, o estado do gancho e o limitador do log de recusas (so os testes isolados usam). */
    public static void zerarContadores() {
        erros = lentas = maiorLentoMs = recusasLogadas = 0;
        falhasDeGancho = 0;
        ultimaFalhaDeGancho = null;
        ganchoComDefeito = null;
        limRecusa = new Limitador(1, Math.max(1, logSegundos) * 1000L);
    }
}
