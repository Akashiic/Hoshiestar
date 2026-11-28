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

import hoshiestar.api.Log;
import hoshiestar.orcamento.Nucleo;
import hoshiestar.orcamento.Orcamento;
import hoshiestar.regioes.Mapa;
import hoshiestar.regioes.MapaSecoes;
import net.minecraft.block.Block;
import net.minecraft.block.BlockChest;
import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.tileentity.TileEntityEnchantmentTable;
import net.minecraft.tileentity.TileEntityEnderChest;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Os pontos de entrada que os patches "// Hoshiestar - paralelo" chamam (plano do Degrau B, passo 3: classificacao,
 * guardas e modo {@code sombra}). Todo patch chama daqui so com {@link Paralelo#LIGADO} verdadeiro (campo
 * {@code static final}); desligado, cada gancho e um teste de campo e nada mais.
 *
 * <p><b>Modo sombra.</b> No comeco do laco de tiles do mundo escolhido, {@link #abrir} classifica as regioes (so leitura) e
 * abre a fase em MONTANDO. Para cada tile, {@link #despachar} (uma linha antes do gancho do orcamento) SEMPRE devolve
 * falso: o tile roda pela chamada original, em serie, na ordem de hoje. Se o tile e de regiao elegivel, abre o contexto
 * "lote R" e tira a foto dos invariantes; {@link #fimTile}, logo depois do update, confere a foto e fecha o contexto. As
 * guardas so contam ({@link Guardas}, {@link Registro}). {@link #fimLaco} fecha a fase.
 *
 * <p><b>Modo sombra_ordenada</b> (passo 4): a mesma classificacao; {@link #despachar} manda o tile para o lote da regiao
 * ({@code OrdenadaJogo}) e devolve verdadeiro (o patch faz {@code continue}); {@link #fimLaco} roda os lotes, sozinho, e a
 * juncao. Todo o codigo que muda o jogo nesse modo fica em {@code OrdenadaJogo} e {@code GanchosAdiados}, e esta classe
 * so chega la com {@link #ordenada} verdadeiro (conferido no TesteParaleloBytecode).
 *
 * <p>Nada daqui lanca para o jogo (a nao ser o crash pedido por {@code ao_falhar}/{@code ao_violar: parar}): erro nosso
 * fecha a fase do tick (em sombra_ordenada, os tiles ja despachados rodam em serie, uma vez), soma em "erros nossos" e
 * escreve uma linha no log (a primeira com a pilha). Nada daqui escreve em objeto do jogo. Nunca chama
 * {@code TileEntity.hasWorldObj} (o AM2 troca esse metodo inteiro e escreve num mapa estatico; passo 2, secao 6.3).
 *
 * <p>Thread: tudo daqui roda na thread do servidor, menos as guardas chamadas por thread estranha, que so somam contador.
 */
public final class GanchosParalelo {

    private GanchosParalelo() {
    }

    static final Classificador CLASSIFICADOR = new Classificador();
    static final Painel PAINEL = Painel.ATUAL;
    private static final VisaoMinecraft VISAO = new VisaoMinecraft();
    private static final Invariantes.Foto ANTES = new Invariantes.Foto();
    private static final Invariantes.Foto DEPOIS = new Invariantes.Foto();

    // ---- estado do tick (so a thread do servidor)
    /** O mundo com a fase em MONTANDO, ou null. */
    private static World mundo;
    private static Mapa mapa;
    private static int dimIdx = -1;
    private static final boolean[] LIBERADO = new boolean[Classificador.N_TIPOS];
    private static long aberturas;
    private static long sinalAte = -1;
    private static String sinalMotivo;
    private static long chaveCache = Long.MIN_VALUE;
    private static int regiaoCache = -1;
    private static int erros;
    /** Passo 4: o modo efetivo deste tick e {@code sombra_ordenada} (escrito so em {@link #abrir}). */
    static boolean ordenada;
    /** Passo 4: o lote que a thread do servidor esta rodando em sombra_ordenada (escrito so por {@code OrdenadaJogo}). */
    static Lote loteDaVez;

    // ---- abrir, despachar, fim

    /** Antes do laco de tiles (W:2533). So no mundo da dimensao escolhida e com o recurso aceito. */
    public static void abrir(World w) {
        if (!Paralelo.aceito() || w == null || w.provider == null || w.provider.dimensionId != Paralelo.parametros().dimensao) {
            return;
        }
        long t0 = System.nanoTime();
        try {
            if (Guardas.fase != Guardas.FECHADA) {
                fecharFase(); // fase esquecida em MONTANDO (excecao de tile em serie no tick anterior): descartada
            }
            if (OrdenadaJogo.temLotes()) {
                OrdenadaJogo.descartar(); // lotes da fase esquecida: os tiles deles nao ticaram naquele tick (como no original)
            }
            String reb = OrdenadaJogo.rebaixado;
            if (reb != null) {
                ordenada = false;
                PAINEL.modoEfetivo = "serie (rebaixado ate o proximo boot: " + reb + ")";
                return; // secao 9, rebaixar: nenhuma fase abre; tudo como o original
            }
            Thread eu = Thread.currentThread();
            Guardas.servidor = eu;
            aberturas++;
            PAINEL.tick = aberturas;
            Parametros p = Paralelo.parametros();
            Nucleo n = Orcamento.nucleo();
            Mapa m = n == null ? null : n.mapa();
            String portao = portao(w, p, m);
            mapa = m;
            dimIdx = m == null ? -1 : m.indiceDaDim(w.provider.dimensionId);
            chaveCache = Long.MIN_VALUE;
            java.util.Arrays.fill(LIBERADO, false);
            for (String c : Paralelo.classesEfetivas()) {
                if (c.equals("mesa")) {
                    LIBERADO[Classificador.MESA] = true;
                } else if (c.equals("bau")) {
                    LIBERADO[Classificador.BAU] = true;
                } else if (c.equals("bau_ender")) {
                    LIBERADO[Classificador.BAU_ENDER] = true;
                }
            }
            VISAO.preparar(w, m, dimIdx, n);
            hoshiestar.observador.ListaObservada.mundo(); // a leitura da lista e nossa: nao conta como uso de fora
            try {
                CLASSIFICADOR.classificar(VISAO, LIBERADO, portao);
            } finally {
                hoshiestar.observador.ListaObservada.fimMundo();
                VISAO.soltar();
            }
            Registro.novoTick(CLASSIFICADOR.nRegioes());
            int nr = CLASSIFICADOR.nRegioes();
            for (int r = 0; r < nr; r++) {
                int mot = CLASSIFICADOR.motivo(r);
                if (CLASSIFICADOR.temTile(r) && mot != Classificador.ELEGIVEL) {
                    String d = CLASSIFICADOR.detalhe(r);
                    int[] ex = CLASSIFICADOR.exemplo(r);
                    if (mot == Classificador.PORTAO && d != null) {
                        d = d.replaceAll(" \\(.*\\)$", ""); // sem os numeros do aquecimento e do sinal: um texto por portao
                    }
                    PAINEL.motivo(Classificador.texto(mot) + (d != null ? ": " + d : ""), ex[0], ex[1], ex[2]);
                }
            }
            if (nr == 0 && portao != null) {
                PAINEL.motivo(Classificador.texto(Classificador.PORTAO) + ": " + portao.replaceAll(" \\(.*\\)$", ""), 0, 0, 0);
            }
            ordenada = Parametros.MODO_SOMBRA_ORDENADA.equals(p.modo);
            PAINEL.lotesTick = 0;
            PAINEL.tilesLotesTick = 0;
            if (ordenada) {
                OrdenadaJogo.novoTick(nr, dimIdx);
                PAINEL.modoEfetivo = portao == null ? "sombra_ordenada (a thread do servidor roda os lotes sozinha, com adiados e juncao)"
                        : "sombra_ordenada, portao fechado (tudo em serie): " + portao;
            } else {
                PAINEL.modoEfetivo = portao == null ? "sombra (tudo em serie; guardas contando)" : "sombra, portao fechado: " + portao;
            }
            Guardas.randDoMundo = w.rand;
            mundo = w;
            Registro.tileDaVez = null;
            Guardas.regiaoAberta = -1;
            Guardas.fase = Guardas.MONTANDO;
            PAINEL.fimDoTick(CLASSIFICADOR, System.nanoTime() - t0);
        } catch (Throwable t) {
            erroNosso("abrir", t);
        }
    }

    /**
     * Uma linha antes do gancho do orcamento no laco de tiles. Devolve "o tile foi para um lote" (o patch faz
     * {@code continue}); no modo sombra devolve SEMPRE falso: o tile tica pela chamada original. Abre o contexto "lote R"
     * se o tile e de regiao elegivel.
     */
    public static boolean despachar(World w, TileEntity te) {
        if (w != mundo || Guardas.fase != Guardas.MONTANDO) {
            return false;
        }
        try {
            if (Registro.tileDaVez != null) {
                PAINEL.contextosDescartados++; // o tile anterior nao passou por fimTile (o orcamento o pulou, ou continue)
                fecharContexto(false);
            }
            if (te == null) {
                return false;
            }
            int r = regiaoDoBloco(te.xCoord, te.zCoord);
            if (!CLASSIFICADOR.elegivel(r) || !LIBERADO[VisaoMinecraft.tipoDe(te)] || te.isInvalid() || te.getWorldObj() == null) {
                return false;
            }
            if (ordenada) {
                return OrdenadaJogo.despachar(w, te, r); // passo 4: o tile vai para o lote (ou o firewall nao deixa ticar)
            }
            contextoTile(w, te, r);
        } catch (Throwable t) {
            erroNosso("despachar", t);
        }
        return false;
    }

    /** Abre o contexto "lote R" de um tile: posicao da vez, foto dos invariantes e a regiao para as guardas. */
    static void contextoTile(World w, TileEntity te, int r) {
        Registro.tileDaVez = te;
        Registro.vezX = te.xCoord;
        Registro.vezY = te.yCoord;
        Registro.vezZ = te.zCoord;
        Registro.violouNaVez = false;
        foto(w, ANTES);
        Guardas.regiaoAberta = r;
    }

    /** Passo 4: fim do tick de um tile do lote (confere os invariantes do tile), sem soltar a regiao do lote. */
    static void fimTileDoLote() {
        if (Registro.tileDaVez != null) {
            fecharContexto(true, false);
        }
    }

    /** Passo 4: o contexto do lote fechou (inclusive por excecao do tile): esquece o tile da vez sem conferir. */
    static void esquecerTile() {
        ANTES.esquecer();
        Registro.tileDaVez = null;
        Registro.violouNaVez = false;
    }

    /** Logo depois do update do tile (e no catch do laco): confere os invariantes e fecha o contexto. */
    public static void fimTile(World w) {
        if (w != mundo || Registro.tileDaVez == null) {
            return;
        }
        try {
            fecharContexto(true);
        } catch (Throwable t) {
            erroNosso("fimTile", t);
        }
    }

    /** Fim do laco de tiles (W:2595): fecha a fase. */
    public static void fimLaco(World w) {
        if (w != mundo) {
            return;
        }
        RuntimeException parar = null;
        try {
            if (Registro.tileDaVez != null) {
                PAINEL.contextosDescartados++;
                fecharContexto(false);
            }
            if (ordenada) {
                parar = OrdenadaJogo.fimDoLaco(w); // passo 4: lotes, juncao e politica (nunca lanca; devolve o crash pedido)
            }
            PAINEL.sujasTick = Registro.nSujasTick;
            fecharFase();
            PAINEL.publicarSePreciso(parar != null);
        } catch (Throwable t) {
            erroNosso("fimLaco", t);
        }
        if (parar != null) {
            throw parar; // ao_falhar/ao_violar: parar -> o caminho de crash de hoje (crash-report e parada que salva)
        }
    }

    private static void fecharContexto(boolean conferir) {
        fecharContexto(conferir, true);
    }

    private static void fecharContexto(boolean conferir, boolean soltarRegiao) {
        if (soltarRegiao) {
            Guardas.regiaoAberta = -1;
        }
        Object te = Registro.tileDaVez;
        if (conferir && te != null && mundo != null && ANTES.tirada()) {
            foto(mundo, DEPOIS);
            List<String> dif = Invariantes.comparar(ANTES, DEPOIS);
            if (!dif.isEmpty() && !Registro.violouNaVez) {
                PAINEL.invariantesQuebrados++;
                if (PAINEL.invariantesQuebrados <= 5) {
                    Log.aviso("paralelo (" + (ordenada ? "sombra_ordenada" : "sombra") + "): invariante quebrado no tick de " + te.getClass().getName() + " em (" + Registro.vezX
                            + ", " + Registro.vezY + ", " + Registro.vezZ + "): " + dif + " (na fase seria violacao; ocorrencia "
                            + PAINEL.invariantesQuebrados + ", as 5 primeiras vao ao log)");
                }
            }
        }
        ANTES.esquecer();
        Registro.tileDaVez = null;
        Registro.violouNaVez = false;
    }

    private static void fecharFase() {
        loteDaVez = null;
        Guardas.loteAberto = false;
        Guardas.regiaoAberta = -1;
        Guardas.fase = Guardas.FECHADA;
        Registro.tileDaVez = null;
        mundo = null;
        Guardas.randDoMundo = null;
        ANTES.esquecer();
    }

    static void foto(World w, Invariantes.Foto f) {
        List tiles = w.loadedTileEntityList;
        List pend = w.hoshieTilesPendentes();
        List ents = w.loadedEntityList;
        long versao = -1;
        int agendados = -1;
        if (w instanceof WorldServer) {
            WorldServer ws = (WorldServer) w;
            agendados = ws.hoshieAgendados();
            ChunkProviderServer cps = ws.theChunkProviderServer;
            if (cps != null && cps.loadedChunkHashMap_KC != null) {
                versao = cps.loadedChunkHashMap_KC.rawThermos().hoshieVersao();
            }
        }
        f.tirar(tiles, tiles == null ? -1 : tiles.size(), pend, pend == null ? -1 : pend.size(), ents,
                ents == null ? -1 : ents.size(), versao, agendados, Orcamento.nucleo() == null ? null : Orcamento.nucleo().mapa(),
                captura(w));
    }

    private static boolean captura(World w) {
        return w.captureBlockSnapshots || w.captureItemDrops || w.captureAndProxyBlocks || w.worldChangeHolder.isCapturing();
    }

    /** Os portoes da dimensao (G5): null = abertos. Sinal = fica fechado por {@code sinal_ticks}. */
    private static String portao(World w, Parametros p, Mapa m) {
        if (aberturas <= p.aquecimentoTicks) {
            return "aquecimento (" + aberturas + " de " + p.aquecimentoTicks + " ticks)";
        }
        String s = sinal(w, m);
        if (s != null) {
            sinalAte = aberturas + p.sinalTicks;
            sinalMotivo = s;
        }
        if (aberturas <= sinalAte) {
            return "sinal: " + sinalMotivo + " (ate o tick " + sinalAte + " do mundo da fase)";
        }
        return null;
    }

    private static String sinal(World w, Mapa m) {
        if (hoshiestar.medidor.Medidor.ATIVO) {
            return "medidor medindo";
        }
        if (hoshiestar.observador.Observador.ATIVO) {
            return "observador ligado";
        }
        if (m == null) {
            return "mapa de regioes ausente (orcamento parado ou ainda sem mapa)";
        }
        if (captura(w)) {
            return "captura de blocos ou de drops ligada";
        }
        if (w instanceof WorldServer && ((WorldServer) w).spigotConfig != null && ((WorldServer) w).spigotConfig.tileMaxTickTime < 1000) {
            return "limitador do Spigot pode cortar o laco de tiles (max-tick-time.tile abaixo de 1000 ms)";
        }
        String d = DragonApi.sinal();
        if (d != null) {
            return d;
        }
        return null;
    }

    static int regiaoDoBloco(int bx, int bz) {
        Mapa m = mapa;
        if (m == null || dimIdx < 0) {
            return -1;
        }
        long ch = MapaSecoes.chave(dimIdx, bx >> 8, bz >> 8);
        if (ch != chaveCache) {
            chaveCache = ch;
            regiaoCache = m.regiaoDaChave(ch);
        }
        return regiaoCache;
    }

    private static void erroNosso(String onde, Throwable t) {
        PAINEL.errosNossos++;
        erros++;
        boolean comLotes = ordenada && OrdenadaJogo.temLotes() && !onde.equals("fimLaco");
        try {
            if (comLotes) {
                OrdenadaJogo.falhouNoTick = true; // os tiles ja despachados rodam em serie no fim do laco; nada novo e despachado
            } else {
                fecharFase();
            }
        } catch (Throwable t2) {
            // nada
        }
        if (erros <= 3) {
            Log.erro("paralelo: erro no nosso codigo (" + onde + "); a fase deste tick foi fechada (tudo em serie, como o original"
                    + (comLotes ? "; os tiles ja despachados rodam em serie no fim do laco, uma vez" : "") + ")", t);
        } else if (erros == 4) {
            Log.aviso("paralelo: mais erros no nosso codigo; so o contador sobe a partir daqui (hoshie paralelo estado)");
        }
    }

    // ---- guardas

    /** Conferencia comum das guardas com posicao no mundo {@code w}. */
    private static void checar(Guardas.Op op, Object w, int x, int z) {
        if (Guardas.fase == Guardas.FECHADA) {
            return;
        }
        try {
            boolean servidor = Thread.currentThread() == Guardas.servidor;
            if (!servidor) {
                Guardas.Res r = Guardas.decidir(op, Guardas.MONTANDO, Guardas.ESTRANHA, -1, -2, false, false);
                Registro.registrar(op, r, x, z, -1);
                return;
            }
            int ctx = Guardas.regiaoAberta;
            if (ctx < 0 && !op.sujaPorPosicao) {
                return; // tile em serie fazendo leitura, aviso ou outra operacao sem lote a sujar
            }
            int alvo = w == mundo ? regiaoDoBloco(x, z) : -2;
            Guardas.Res r = Guardas.decidir(op, Guardas.MONTANDO, Guardas.SERVIDOR, ctx, alvo, alvo >= 0 && CLASSIFICADOR.elegivel(alvo), false);
            Registro.registrar(op, r, x, z, alvo);
        } catch (Throwable t) {
            PAINEL.errosNossos++;
        }
    }

    public static void lerBloco(World w, int x, int z) {
        if (Guardas.fase != Guardas.FECHADA && Guardas.regiaoAberta >= 0) {
            checar(Guardas.Op.LER_BLOCO, w, x, z);
        }
    }

    public static void lerTile(World w, int x, int z) {
        if (Guardas.fase != Guardas.FECHADA && Guardas.regiaoAberta >= 0) {
            checar(Guardas.Op.LER_TILE, w, x, z);
        }
    }

    public static void escreverBloco(World w, int x, int z) {
        checar(Guardas.Op.ESCREVER_BLOCO, w, x, z);
    }

    public static void escreverMeta(World w, int x, int z) {
        checar(Guardas.Op.ESCREVER_META, w, x, z);
    }

    public static void avisarVizinho(World w, int x, int z) {
        checar(Guardas.Op.AVISAR_VIZINHO, w, x, z);
    }

    public static void adicionarEntidade(World w, Entity e) {
        if (Guardas.fase != Guardas.FECHADA && e != null) {
            checar(Guardas.Op.ADD_ENTIDADE, w, floor(e.posX), floor(e.posZ));
        }
    }

    public static void removerEntidade(World w, Entity e) {
        if (Guardas.fase != Guardas.FECHADA && e != null) {
            checar(Guardas.Op.TIRAR_ENTIDADE, w, floor(e.posX), floor(e.posZ));
        }
    }

    public static void porTile(World w, int x, int z) {
        checar(Guardas.Op.POR_TILE, w, x, z);
    }

    public static void tirarTile(World w, int x, int z) {
        checar(Guardas.Op.TIRAR_TILE, w, x, z);
    }

    public static void adicionarTile(World w, TileEntity te) {
        if (Guardas.fase != Guardas.FECHADA && te != null) {
            checar(Guardas.Op.ADD_TILE, w, te.xCoord, te.zCoord);
        }
    }

    public static void adicionarTiles(World w) {
        checar(Guardas.Op.ADD_TILES, w, 0, 0);
    }

    public static void vizinhosMarkDirty(World w, int x, int z) {
        checar(Guardas.Op.VIZINHO_MARKDIRTY, w, x, z);
    }

    public static void chunkModificado(World w, int x, int z) {
        checar(Guardas.Op.CHUNK_MODIFICADO, w, x, z);
    }

    public static void avisoCliente(World w, int x, int z) {
        checar(Guardas.Op.AVISO_CLIENTE, w, x, z);
    }

    public static void tickAgendado(World w, int x, int z) {
        checar(Guardas.Op.TICK_AGENDADO, w, x, z);
    }

    public static void eventoDeBloco(World w, int x, int z) {
        checar(Guardas.Op.EVENTO_BLOCO, w, x, z);
    }

    public static void cargaChunk(World w, int cx, int cz) {
        checar(Guardas.Op.CARGA_CHUNK, w, cx << 4, cz << 4);
    }

    /** Passo 4: {@code loadChunk} com o chunk presente (sai da fila de descarga): adiado. */
    public static void naoDescarregar(World w, int cx, int cz) {
        checar(Guardas.Op.NAO_DESCARREGAR, w, cx << 4, cz << 4);
    }

    public static void faltaChunk(World w, int cx, int cz) {
        checar(Guardas.Op.FALTA_CHUNK, w, cx << 4, cz << 4);
    }

    public static void salvar(World w) {
        checar(Guardas.Op.SALVAR, w, 0, 0);
    }

    public static void salvarTudo() {
        checar(Guardas.Op.SALVAR, null, 0, 0);
    }

    static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    // ---- a Visao do classificador sobre o mundo de verdade

    static final class VisaoMinecraft implements Classificador.Visao {
        private World w;
        private List lista;
        private Mapa m;
        private int di;
        private Nucleo n;
        private ChunkProviderServer cps;

        void preparar(World w, Mapa m, int di, Nucleo n) {
            this.w = w;
            this.lista = w.loadedTileEntityList;
            this.m = m;
            this.di = di;
            this.n = n;
            this.cps = w instanceof WorldServer ? ((WorldServer) w).theChunkProviderServer : null;
        }

        void soltar() {
            w = null;
            lista = null;
            m = null;
            n = null;
            cps = null;
        }

        static int tipoDe(Object o) {
            if (o == null) {
                return Classificador.OUTRO;
            }
            Class<?> c = o.getClass();
            if (c == TileEntityChest.class) {
                return Classificador.BAU;
            }
            if (c == TileEntityEnderChest.class) {
                return Classificador.BAU_ENDER;
            }
            if (c == TileEntityEnchantmentTable.class) {
                return Classificador.MESA;
            }
            return Classificador.OUTRO;
        }

        private TileEntity te(int i) {
            Object o = lista.get(i);
            return o instanceof TileEntity ? (TileEntity) o : null;
        }

        @Override
        public int tamanho() {
            return lista == null || m == null || di < 0 ? 0 : lista.size();
        }

        @Override
        public Object tile(int i) {
            return te(i);
        }

        @Override
        public boolean valido(int i) {
            TileEntity t = te(i);
            return t != null && !t.isInvalid() && t.getWorldObj() != null;
        }

        @Override
        public int tipo(int i) {
            return tipoDe(lista.get(i));
        }

        @Override
        public String classe(int i) {
            Object o = lista.get(i);
            return o == null ? "null" : o.getClass().getName();
        }

        @Override
        public int x(int i) {
            return te(i).xCoord;
        }

        @Override
        public int y(int i) {
            return te(i).yCoord;
        }

        @Override
        public int z(int i) {
            return te(i).zCoord;
        }

        @Override
        public boolean fechado(int i) {
            TileEntity t = te(i);
            if (t instanceof TileEntityChest) {
                TileEntityChest c = (TileEntityChest) t;
                return c.numPlayersUsing == 0 && c.lidAngle == 0.0F && c.prevLidAngle == 0.0F;
            }
            if (t instanceof TileEntityEnderChest) {
                TileEntityEnderChest c = (TileEntityEnderChest) t;
                return c.field_145973_j == 0 && c.field_145972_a == 0.0F && c.field_145975_i == 0.0F;
            }
            return false;
        }

        @Override
        public boolean vizinhosComTile(int i) {
            TileEntity t = te(i);
            int x = t.xCoord, y = t.yCoord, z = t.zCoord;
            int[][] d = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
            for (int[] o : d) {
                int nx = x + o[0], nz = z + o[1];
                if (!chunkCarregado(nx >> 4, nz >> 4)) {
                    return false; // nunca le bloco de chunk descarregado (a leitura carregaria o chunk)
                }
                Block b = w.getBlock(nx, y, nz);
                if (!(b instanceof BlockChest)) {
                    continue;
                }
                Chunk c = cps.provideChunk(nx >> 4, nz >> 4);
                Object vt = c == null ? null : c.chunkTileEntityMap.get(new ChunkPosition(nx & 15, y, nz & 15));
                if (vt instanceof TileEntity && !((TileEntity) vt).isInvalid()) {
                    continue;
                }
                if (!pendenteValido(nx, y, nz)) {
                    return false;
                }
            }
            return true;
        }

        private boolean pendenteValido(int x, int y, int z) {
            List p = w.hoshieTilesPendentes();
            if (p == null) {
                return false;
            }
            for (int k = 0; k < p.size(); k++) {
                Object o = p.get(k);
                if (o instanceof TileEntity) {
                    TileEntity t = (TileEntity) o;
                    if (!t.isInvalid() && t.xCoord == x && t.yCoord == y && t.zCoord == z) {
                        return true;
                    }
                }
            }
            return false;
        }

        @Override
        public boolean firewallDeixa(int i) {
            // Modo sombra: o tile roda pela chamada original, que ja pergunta ao firewall. Perguntar aqui de novo
            // rodaria o codigo dele duas vezes (caches e log). A pergunta no despacho entra no passo 4 (sombra_ordenada)
            return true;
        }

        @Override
        public boolean chunkCarregado(int cx, int cz) {
            return cps != null && cps.chunkExists(cx, cz);
        }

        @Override
        public int regiaoDoChunk(int cx, int cz) {
            return m == null || di < 0 ? -1 : m.regiaoDaChave(MapaSecoes.chave(di, cx >> 4, cz >> 4));
        }

        @Override
        public int nRegioes() {
            return m == null ? 0 : m.nRegioes;
        }

        @Override
        public boolean regiaoLivre(int r) {
            return n != null && n.regiaoLivre(r);
        }
    }

    // ---- DragonAPI, lido por reflexao (sem depender do jar)

    static final class DragonApi {
        private static boolean lido;
        private static Field observadores, watchers;
        private static Method profiler;

        private static void ler() {
            if (lido) {
                return;
            }
            lido = true;
            try {
                ClassLoader cl = net.minecraft.launchwrapper.Launch.classLoader != null ? net.minecraft.launchwrapper.Launch.classLoader
                        : GanchosParalelo.class.getClassLoader();
                if (cl.getResource(Travas.RECURSO_DRAGONAPI) == null) {
                    return;
                }
                observadores = campo(Class.forName(Travas.CLASSE_CHUNK_REQUEST, false, cl), "listeners");
                watchers = campo(Class.forName(Travas.CLASSE_TILE_UPDATE, false, cl), "watchers");
                try {
                    profiler = Class.forName(Travas.CLASSE_PROFILER_GERACAO, false, cl).getMethod("profilingEnabled");
                } catch (Throwable t) {
                    profiler = null;
                }
            } catch (Throwable t) {
                observadores = null;
                watchers = null;
            }
        }

        static Field campo(Class<?> c, String nome) throws Exception {
            Field f = c.getDeclaredField(nome);
            f.setAccessible(true);
            return f;
        }

        /** Classes nao nulas do vetor estatico, ou null se nao deu para ler (valor de outro tipo tambem: revisao do passo 3). */
        static List<String> classes(Field f) {
            if (f == null) {
                return null;
            }
            try {
                return ColetaFatos.classesDoVetor(f.get(null)); // Hoshiestar
            } catch (Throwable t) {
                return null;
            }
        }

        static List<String> observadores() {
            ler();
            return classes(observadores);
        }

        static List<String> watchers() {
            ler();
            return classes(watchers);
        }

        static boolean presente() {
            ler();
            return observadores != null || watchers != null;
        }

        /**
         * Sinal do tick (G5): profiler de geracao ligado, ou observador/watcher fora da lista revisada registrado depois do
         * boot (um tile EMP do RotaryCraft posto agora carrega um watcher revisado; outro mod, nao). Sem DragonAPI: null.
         */
        static String sinal() {
            ler();
            if (observadores == null && watchers == null) {
                return null;
            }
            try {
                if (profiler != null && Boolean.TRUE.equals(profiler.invoke(null))) {
                    return "profiler de geracao do DragonAPI ligado";
                }
            } catch (Throwable t) {
                return "profiler de geracao do DragonAPI ilegivel";
            }
            List<String> o = classes(observadores);
            if (o == null || !ColetaFatos.fora(o, Travas.OBSERVADORES_REVISADOS).isEmpty()) {
                return "observador nao revisado no ChunkRequestEvent do DragonAPI (ou ilegivel)";
            }
            List<String> wt = classes(watchers);
            if (wt == null || !ColetaFatos.fora(wt, Travas.WATCHERS_REVISADOS).isEmpty()) {
                return "watcher nao revisado no TileUpdateEvent do DragonAPI (ou ilegivel)";
            }
            return null;
        }
    }
}
