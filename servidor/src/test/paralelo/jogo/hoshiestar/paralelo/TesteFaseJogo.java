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

import com.akashic.worldfirewall.WorldMutationGuard;
import hoshiestar.teste.Verifica;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import static hoshiestar.paralelo.ApoioJogo.REG;
import static hoshiestar.paralelo.ApoioJogo.campo;
import static hoshiestar.paralelo.ApoioJogo.cru;
import static hoshiestar.paralelo.ApoioJogo.por;

/**
 * Passo 4 (revisao, I1): {@code OrdenadaJogo} de ponta a ponta nas classes REAIS do build rapido, sem subir o servidor:
 * despacho com um firewall falso (pergunta uma vez por tile; "nao" = nao tica e nao entra no lote), lote limpo
 * (reconferencia fraca: tile invalidado depois do despacho e pulado), lote sujo (em serie na juncao, reconferencia forte
 * pelo mapa do chunk), ordem dos ticks e da juncao por chave, erro nosso no despacho (emergencia: os ja despachados rodam em
 * serie uma vez), fase esquecida, violacao e excecao de tile com {@code parar} (a excecao volta para o jogo; o
 * {@code GanchosParalelo.fimLaco} a lanca mesmo quando o crash-report nao pode ser montado, como fora do jogo).
 *
 * <p>Precisa do hoshiestar.yml da pasta de trabalho com paralelo.ligado (o script escreve um) e do firewall falso
 * ({@code com.akashic.worldfirewall.WorldMutationGuard}) no classpath.
 */
public final class TesteFaseJogo {

    static Verifica v;
    static WorldServer ws;
    static CpsTeste cps;
    static thermos.wrapper.ChunkBlockHashMap mapa;
    static final List<String> ORDEM = new ArrayList<String>();

    /** Tile do teste: conta ticks e anota em que contexto ticou. */
    public static class Tile extends TileEntity {
        public String nome;
        public int ticks;
        public boolean noLote;
        public int faseNoTick = -1;
        public boolean lanca, viola, som;

        @Override
        public void updateEntity() {
            ticks++;
            noLote = GanchosParalelo.loteDaVez != null;
            faseNoTick = Guardas.fase;
            ORDEM.add(nome);
            if (som) {
                worldObj.playRecord("som de " + nome, xCoord, yCoord, zCoord);
            }
            if (viola) {
                GanchosParalelo.escreverBloco(worldObj, xCoord, zCoord); // o que o patch do setBlock chama
            }
            if (lanca) {
                throw new IllegalStateException("tile " + nome + " lancou");
            }
        }
    }

    static Tile tile(String nome, int x, int z) throws Exception {
        Tile t = cru(Tile.class);
        t.nome = nome;
        t.xCoord = x;
        t.yCoord = 4;
        t.zCoord = z;
        t.setWorldObj(ws);
        Chunk c = mapa.get(x >> 4, z >> 4);
        if (c == null) {
            c = cru(Chunk.class);
            por(c, "xPosition", x >> 4);
            por(c, "zPosition", z >> 4);
            c.chunkTileEntityMap = new HashMap<Object, Object>();
            mapa.put(c);
        }
        c.chunkTileEntityMap.put(new ChunkPosition(x & 15, 4, z & 15), t);
        return t;
    }

    /** Provedor de chunks que anota cada carga pedida (a reconferencia nunca pode carregar chunk). */
    public static class CpsTeste extends ChunkProviderServer {
        public int cargas;

        public CpsTeste() {
            super(null, null, null); // nunca chamado: o teste cria sem construtor
        }

        @Override
        public Chunk loadChunk(int x, int z) {
            cargas++;
            return null;
        }
    }

    static void novoTick() {
        ORDEM.clear();
        REG.clear();
        Guardas.servidor = Thread.currentThread();
        Guardas.fase = Guardas.MONTANDO;
        Guardas.regiaoAberta = -1;
        Registro.novoTick(8);
        OrdenadaJogo.novoTick(8, 0);
    }

    static boolean temNaCausa(Throwable t, Class<?> c, String texto) {
        for (Throwable x = t; x != null; x = x.getCause()) {
            if (c.isInstance(x) && (texto == null || String.valueOf(x.getMessage()).contains(texto))) {
                return true;
            }
            if (x.getCause() == x) {
                break;
            }
        }
        return false;
    }

    public static void main(String[] args) throws Exception {
        ApoioJogo.carregarConfig();
        v = new Verifica("TesteFaseJogo (Paralelo.LIGADO=" + Paralelo.LIGADO + ")");
        v.sim(Paralelo.LIGADO, "o hoshiestar.yml da pasta de trabalho liga o paralelo (senao os patches nem chamam os ganchos)");
        ws = cru(WorldServer.class);
        cps = cru(CpsTeste.class);
        por(cps, "loadChunkOnProvideRequest", true); // um provideChunk de chunk ausente carregaria (anotado em cargas)
        mapa = new thermos.wrapper.ChunkBlockHashMap();
        por(ws, "worldAccesses", new ArrayList<Object>(Arrays.asList(ApoioJogo.ACESSO)));
        por(ws, "loadedTileEntityList", new ArrayList<Object>());
        por(ws, "loadedEntityList", new ArrayList<Object>());
        Object holder = cru(campo(WorldServer.class, "worldChangeHolder").getType());
        por(holder, "world", ws);
        por(ws, "worldChangeHolder", holder);
        por(ws, "pendingTickListEntriesTreeSet", new java.util.TreeSet<Object>());
        por(cps, "loadedChunkHashMap_KC", new thermos.wrapper.VanillaChunkHashMap(mapa));
        por(cps, "worldObj", ws);
        por(ws, "chunkProvider", cps);
        por(ws, "theChunkProviderServer", cps);
        Painel P = GanchosParalelo.PAINEL;

        // ---- 1. firewall no despacho + lote limpo
        novoTick();
        Tile a1 = tile("a1", 10, 10), a2 = tile("a2", 12, 10), a3 = tile("a3", 14, 10);
        a1.som = true;
        WorldMutationGuard.NEGADOS.add(a2);
        boolean d1 = OrdenadaJogo.despachar(ws, a1, 1), d2 = OrdenadaJogo.despachar(ws, a2, 1), d3 = OrdenadaJogo.despachar(ws, a3, 1);
        v.sim(d1 && d2 && d3, "despacho: os tres devolvem verdadeiro (o laco faz continue)");
        v.igual(3, WorldMutationGuard.perguntas, "despacho: o firewall foi perguntado uma vez por tile");
        v.sim(OrdenadaJogo.LOTES.size() == 1 && OrdenadaJogo.LOTES.get(0).tamanho() == 2, "despacho: o tile que o firewall negou NAO entrou no lote");
        v.igual(1L, P.barradosNoDespacho, "despacho: o painel contou um barrado pelo firewall");
        v.sim(a1.ticks == 0 && a3.ticks == 0, "despacho: nenhum tile ticou no despacho");
        RuntimeException r = OrdenadaJogo.fimDoLaco(ws);
        v.sim(r == null, "lote limpo: sem crash (" + r + ")");
        v.sim(a1.ticks == 1 && a3.ticks == 1 && a2.ticks == 0, "lote limpo: os dois do lote ticaram uma vez; o negado pelo firewall nao ticou");
        v.sim(a1.noLote && a3.noLote && a1.faseNoTick == Guardas.MONTANDO, "lote limpo: ticaram com o contexto de lote aberto e a fase em MONTANDO");
        v.sim(REG.size() == 1 && REG.get(0).startsWith("playRecord som de a1"), "lote limpo: o som adiado foi aplicado na juncao, uma vez (" + REG + ")");
        v.sim(GanchosParalelo.loteDaVez == null && !Guardas.loteAberto && Guardas.fase == Guardas.FECHADA, "depois da fase: contexto fechado, fase FECHADA");
        v.sim(P.emergencias == 0 && P.errosNossos == 0, "nenhum erro nosso ate aqui (emergencias " + P.emergencias + ", erros " + P.errosNossos + ")");

        // ---- 2. tile invalidado depois do despacho, lote limpo (reconferencia fraca, a do jogo)
        novoTick();
        Tile b1 = tile("b1", 20, 10), b2 = tile("b2", 22, 10);
        OrdenadaJogo.despachar(ws, b1, 1);
        OrdenadaJogo.despachar(ws, b2, 1);
        b2.invalidate();
        r = OrdenadaJogo.fimDoLaco(ws);
        v.sim(r == null && b1.ticks == 1 && b2.ticks == 0, "invalidado depois do despacho (lote limpo): pulado, o outro ticou");
        // tile sem mundo depois do despacho: tambem pulado
        novoTick();
        Tile b3 = tile("b3", 24, 10);
        OrdenadaJogo.despachar(ws, b3, 1);
        b3.setWorldObj(null);
        r = OrdenadaJogo.fimDoLaco(ws);
        v.sim(r == null && b3.ticks == 0, "tile sem mundo depois do despacho (lote limpo): pulado");

        // ---- 3. lote sujo: em serie na juncao, reconferencia forte; os outros lotes seguem no lote
        novoTick();
        Tile c1 = tile("c1", 300, 10), c2 = tile("c2", 302, 10), c3 = tile("c3", 304, 10), e1 = tile("e1", 30, 10), f1 = tile("f1", 600, 10);
        Tile c4 = tile("c4", 306, 10);
        c1.som = true;
        e1.som = true;
        f1.som = true;
        OrdenadaJogo.despachar(ws, c1, 2);
        OrdenadaJogo.despachar(ws, c2, 2);
        OrdenadaJogo.despachar(ws, c3, 2);
        OrdenadaJogo.despachar(ws, c4, 2);
        OrdenadaJogo.despachar(ws, e1, 1);
        OrdenadaJogo.despachar(ws, f1, 3);
        Registro.sujas[2] = true; // uma escrita em serie na regiao 2 depois da classificacao
        Tile outro = cru(Tile.class);
        mapa.get(302 >> 4, 0).chunkTileEntityMap.put(new ChunkPosition(302 & 15, 4, 10), outro); // c2 trocado por outro objeto no chunk
        c3.invalidate();
        mapa.remove(mapa.get(306 >> 4, 0)); // o chunk de c4 saiu do mapa (o tile segue valido)
        r = OrdenadaJogo.fimDoLaco(ws);
        v.sim(r == null, "lote sujo: sem crash (" + r + ")");
        v.sim(c1.ticks == 1 && !c1.noLote && c1.faseNoTick == Guardas.FECHADA, "lote sujo: c1 ticou em serie, sem contexto de lote, com a fase FECHADA");
        v.igual(0, c2.ticks, "lote sujo: c2 (trocado por outro objeto no chunk) foi pulado pela reconferencia forte");
        v.igual(0, c3.ticks, "lote sujo: c3 (invalidado) foi pulado");
        v.igual(0, c4.ticks, "lote sujo: c4 (chunk fora do mapa) foi pulado pela reconferencia forte");
        v.igual(0, cps.cargas, "lote sujo: a reconferencia forte nao pediu carga do chunk ausente");
        v.sim(e1.ticks == 1 && e1.noLote && f1.ticks == 1 && f1.noLote, "lote sujo: os lotes limpos das outras regioes rodaram no lote");
        v.igual(Arrays.asList("e1", "f1", "c1"), new ArrayList<String>(ORDEM), "ordem dos ticks: lotes limpos por chave, depois o sujo na juncao");
        v.igual("[playRecord som de e1 30,4,10, playRecord som de c1 300,4,10, playRecord som de f1 600,4,10]", REG.toString(),
                "juncao por chave: adiado de e1, efeito direto de c1 (em serie), adiado de f1");

        // ---- 4. erro nosso no despacho: emergencia (todos em serie, uma vez)
        novoTick();
        Tile g1 = tile("g1", 40, 10), g2 = tile("g2", 42, 10);
        OrdenadaJogo.despachar(ws, g1, 1);
        OrdenadaJogo.despachar(ws, g2, 1);
        OrdenadaJogo.falhouNoTick = true;
        Tile g3 = tile("g3", 44, 10);
        v.sim(!OrdenadaJogo.despachar(ws, g3, 1), "erro nosso: depois dele nenhum despacho (o tile tica pelo laco original)");
        long em = P.emergencias;
        r = OrdenadaJogo.fimDoLaco(ws);
        v.sim(r == null && g1.ticks == 1 && g2.ticks == 1 && !g1.noLote && P.emergencias == em + 1,
                "erro nosso: os ja despachados rodam em serie, uma vez (emergencia)");

        // ---- 5. fase esquecida: lotes descartados no proximo tick
        novoTick();
        Tile h1 = tile("h1", 50, 10);
        OrdenadaJogo.despachar(ws, h1, 1);
        novoTick();
        v.sim(OrdenadaJogo.LOTES.isEmpty() && h1.ticks == 0, "fase esquecida: lote descartado, o tile nao ticou");
        v.sim(OrdenadaJogo.fimDoLaco(ws) == null && h1.ticks == 0, "fase esquecida: o fim do laco seguinte nao roda o lote velho");

        // ---- 6. violacao no lote: politica parar (padrao) e os lotes seguintes em serie
        novoTick();
        Tile i1 = tile("i1", 60, 10), i2 = tile("i2", 62, 10), j1 = tile("j1", 310, 10);
        i1.viola = true;
        OrdenadaJogo.despachar(ws, i1, 1);
        OrdenadaJogo.despachar(ws, i2, 1);
        OrdenadaJogo.despachar(ws, j1, 2);
        r = null;
        Throwable fora = null;
        try {
            r = OrdenadaJogo.fimDoLaco(ws);
        } catch (Throwable t) {
            fora = t;
        }
        v.sim(fora == null, "violacao: fimDoLaco nao lanca (devolve o crash) (" + fora + ")");
        v.sim(i1.ticks == 1 && i2.ticks == 1 && i2.noLote, "violacao: o lote da violacao termina no lote");
        v.sim(j1.ticks == 1 && !j1.noLote, "violacao: o lote seguinte sobrou e rodou em serie na juncao");
        v.sim(r != null && temNaCausa(r, IllegalStateException.class, "ao_violar: parar"),
                "violacao com ao_violar: parar (padrao): devolve a excecao para o jogo, com o motivo (" + r + ")");

        // ---- 7. excecao de tile no lote: resto do lote nao roda, seguintes em serie, politica parar
        novoTick();
        Tile k1 = tile("k1", 70, 10), k2 = tile("k2", 72, 10), k3 = tile("k3", 74, 10), m1 = tile("m1", 320, 10);
        k2.lanca = true;
        OrdenadaJogo.despachar(ws, k1, 1);
        OrdenadaJogo.despachar(ws, k2, 1);
        OrdenadaJogo.despachar(ws, k3, 1);
        OrdenadaJogo.despachar(ws, m1, 2);
        r = null;
        fora = null;
        try {
            r = OrdenadaJogo.fimDoLaco(ws);
        } catch (Throwable t) {
            fora = t;
        }
        v.sim(fora == null, "excecao de tile: fimDoLaco nao lanca (" + fora + ")");
        v.sim(k1.ticks == 1 && k2.ticks == 1 && k3.ticks == 0, "excecao de tile: o resto do lote nao roda (secao 9)");
        v.sim(m1.ticks == 1 && !m1.noLote, "excecao de tile: o lote seguinte rodou em serie na juncao");
        v.sim(r != null && temNaCausa(r, IllegalStateException.class, "tile k2 lancou"),
                "excecao de tile com ao_falhar: parar: devolve a excecao para o jogo, com a do tile na causa (" + r + ")");
        v.sim(GanchosParalelo.loteDaVez == null && !Guardas.loteAberto, "excecao de tile: contexto fechado");

        // ---- 8. o caminho inteiro por GanchosParalelo.fimLaco: o 'parar' chega ao jogo (fora do jogo o crash-report nem
        //         pode ser montado: o FML nao inicializa; a excecao tem de sair assim mesmo)
        novoTick();
        Tile n1 = tile("n1", 80, 10);
        n1.lanca = true;
        OrdenadaJogo.despachar(ws, n1, 1);
        Field fm = campo(GanchosParalelo.class, "mundo");
        fm.set(null, ws);
        GanchosParalelo.ordenada = true;
        long en = P.errosNossos;
        Throwable saiu = null;
        try {
            GanchosParalelo.fimLaco(ws);
        } catch (Throwable t) {
            saiu = t;
        }
        v.sim(saiu != null && temNaCausa(saiu, IllegalStateException.class, "tile n1 lancou"),
                "fimLaco com ao_falhar: parar: a excecao sai para o jogo, com a do tile na causa (" + saiu + ")");
        v.igual(en, P.errosNossos, "fimLaco com ao_falhar: parar: nenhum erro nosso contado (a politica nao foi engolida)");
        v.igual(1, n1.ticks, "fimLaco: o tile ticou uma vez");
        v.fim();
    }
}
