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
package hoshiestar.observador;

import hoshiestar.api.ThreadServidor;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.DimensionManager;

import java.io.File;
import java.util.List;

/**
 * Os pontos de entrada que os patches "// Hoshiestar - observador" chamam. Os patches so chamam daqui quando
 * {@link Observador#ATIVO} e verdadeiro. Esta e a unica classe do observador que cita classes do Minecraft: tira do
 * objeto do jogo so numeros (dimensao, chunk) e a classe do objeto que executa, e passa para o {@link Nucleo}.
 *
 * <p>So leitura: nenhum metodo daqui escreve em objeto do jogo nem muda o que um metodo devolve. Nenhum metodo lanca:
 * tudo fica em try/catch, que para a observacao ({@link Observador#falha}) e deixa o tick seguir. Os patches chamam
 * SEM try/catch no ponto de chamada (excecao a regra da base, como o medidor e as marcas do vigia): um try/catch ali poe um
 * quadro de pilha no bytecode de World.updateEntities e o transformador do Opis, que procura uma sequencia exata de
 * instrucoes, quebra o boot. Esta classe nao tem inicializacao estatica que possa falhar.
 */
public final class GanchosObservador {

    private GanchosObservador() {
    }

    static int dim(World w) {
        return w != null && w.provider != null ? w.provider.dimensionId : Integer.MIN_VALUE;
    }

    /** O nucleo, se a observacao esta de pe e esta e a thread do servidor; senao null. */
    private static Nucleo servidor() {
        Nucleo n = Observador.N;
        return n != null && ThreadServidor.souAThreadDoServidor() ? n : null;
    }

    private static int piso(double v) {
        int i = (int) v;
        return v < (double) i ? i - 1 : i;
    }

    // ---------------------------------------------------------------- contextos

    public static void tile(World w, TileEntity te) {
        try {
            ListaObservada.fora(); // prototipo da lista: o update do tile e codigo de mod ("de fora"), mesmo dentro do laco do World
            Nucleo n = servidor();
            if (n != null && te != null) {
                n.entrar(Nucleo.C_TILE, w, dim(w), te.xCoord >> 4, te.zCoord >> 4, te.getClass());
            }
        } catch (Throwable t) {
            Observador.falha("contexto de tile", t);
        }
    }

    public static void fimTile() {
        sair(Nucleo.C_TILE);
        listaFimFora();
    }

    private static void listaFimFora() {
        try {
            ListaObservada.fimFora();
        } catch (Throwable t) {
            Observador.falha("fim de contexto da lista", t);
        }
    }

    /** Inicio de World.updateEntityWithOptionalForce; {@code forcar = false} e so contabilidade de chunk: nao e tick. */
    public static void entidade(World w, Entity e, boolean forcar) {
        try {
            Nucleo n = servidor();
            if (n != null && forcar && e != null) {
                n.entrar(Nucleo.C_ENTIDADE, w, dim(w), piso(e.posX) >> 4, piso(e.posZ) >> 4, e.getClass());
            }
        } catch (Throwable t) {
            Observador.falha("contexto de entidade", t);
        }
    }

    public static void fimEntidade(boolean forcar) {
        if (forcar) {
            sair(Nucleo.C_ENTIDADE);
        }
    }

    /** Inicio de EntityPlayerMP.onUpdateEntity. O jogador entra SEM objeto: so o chunk e o mundo (nenhum dado pessoal). */
    public static void jogadorRede(EntityPlayerMP p) {
        try {
            Nucleo n = servidor();
            if (n != null && p != null) {
                n.entrar(Nucleo.C_JOGADOR_REDE, p.worldObj, dim(p.worldObj), piso(p.posX) >> 4, piso(p.posZ) >> 4, null);
            }
        } catch (Throwable t) {
            Observador.falha("contexto de jogador", t);
        }
    }

    public static void fimJogadorRede() {
        sair(Nucleo.C_JOGADOR_REDE);
    }

    /** Antes do updateTick de um bloco agendado (por entrada); o contexto fica ate o fim do laco. */
    public static void tickAgendado(World w, int x, int z, Block b) {
        try {
            Nucleo n = servidor();
            if (n != null) {
                n.entrar(Nucleo.C_TICK_AGENDADO, w, dim(w), x >> 4, z >> 4, b != null ? b.getClass() : null);
            }
        } catch (Throwable t) {
            Observador.falha("contexto de tick agendado", t);
        }
    }

    public static void fimTickAgendado() {
        sair(Nucleo.C_TICK_AGENDADO);
    }

    /** Antes do updateTick aleatorio de um bloco (por bloco sorteado). */
    public static void tickAleatorio(World w, int cx, int cz, Block b) {
        try {
            Nucleo n = servidor();
            if (n != null) {
                n.entrar(Nucleo.C_TICK_ALEATORIO, w, dim(w), cx, cz, b != null ? b.getClass() : null);
            }
        } catch (Throwable t) {
            Observador.falha("contexto de tick aleatorio", t);
        }
    }

    public static void fimTickAleatorio() {
        sair(Nucleo.C_TICK_ALEATORIO);
    }

    /** Eventos de bloco (func_147488_Z): cada evento tem a posicao dele, mas o laco nao e por posicao: sem origem. */
    public static void eventoBloco(World w) {
        entrarSemOrigem(Nucleo.C_EVENTO_BLOCO, w);
    }

    public static void fimEventoBloco() {
        sair(Nucleo.C_EVENTO_BLOCO);
    }

    /** Handler global do servidor (antes/depois do tick), tick de mundo (com o mundo), rede, agendador de plugins. */
    public static void global(int tipo, World w) {
        entrarSemOrigem(tipo, w);
    }

    public static void fimGlobal(int tipo) {
        sair(tipo);
    }

    private static void entrarSemOrigem(int tipo, World w) {
        try {
            Nucleo n = servidor();
            if (n != null) {
                n.entrar(tipo, w, dim(w), Nucleo.SEM_ORIGEM, 0, null);
            }
        } catch (Throwable t) {
            Observador.falha("contexto global", t);
        }
    }

    private static void sair(int tipo) {
        try {
            Nucleo n = servidor();
            if (n != null) {
                n.sair(tipo);
            }
        } catch (Throwable t) {
            Observador.falha("fim de contexto", t);
        }
    }

    // ---------------------------------------------------------------- acessos

    /** World.getBlock: o caminho mais quente. Mesma secao do mesmo mundo = so conta; sem origem = so conta por contexto. */
    public static void lerBloco(World w, int x, int z) {
        try {
            Nucleo n = Observador.N;
            if (n == null) {
                return;
            }
            if (!ThreadServidor.souAThreadDoServidor()) {
                n.outraThread(Nucleo.A_LER_BLOCO);
                return;
            }
            int cx = x >> 4;
            int cz = z >> 4;
            if (n.leituraRapida(w, cx, cz)) {
                return;
            }
            n.acesso(Nucleo.A_LER_BLOCO, w, dim(w), cx, cz);
        } catch (Throwable t) {
            Observador.falha("leitura de bloco", t);
        }
    }

    private static void acesso(int tipo, World w, int x, int z) {
        try {
            Nucleo n = Observador.N;
            if (n == null) {
                return;
            }
            if (!ThreadServidor.souAThreadDoServidor()) {
                n.outraThread(tipo);
                return;
            }
            n.acesso(tipo, w, dim(w), x >> 4, z >> 4);
        } catch (Throwable t) {
            Observador.falha("acesso " + tipo, t);
        }
    }

    public static void escreverBloco(World w, int x, int z) {
        try {
            Nucleo n = servidor();
            if (n != null) {
                n.escritaViaMundo(x >> 4, z >> 4);
            }
        } catch (Throwable t) {
            Observador.falha("escrita de bloco", t);
        }
        acesso(Nucleo.A_ESCREVER_BLOCO, w, x, z);
    }

    public static void escreverMeta(World w, int x, int z) {
        acesso(Nucleo.A_ESCREVER_META, w, x, z);
    }

    /** World.getTileEntity, no fim (antes do return): o tile devolvido diz se e "provavel inventario". */
    public static void lerTile(World w, int x, int z, TileEntity te) {
        acesso(te instanceof IInventory ? Nucleo.A_LER_TILE_INVENTARIO : Nucleo.A_LER_TILE, w, x, z);
    }

    public static void porTile(World w, int x, int z) {
        acesso(Nucleo.A_POR_TILE, w, x, z);
    }

    public static void removerTile(World w, int x, int z) {
        acesso(Nucleo.A_REMOVER_TILE, w, x, z);
    }

    public static void adicionarTile(World w, TileEntity te) {
        if (te != null) {
            acesso(Nucleo.A_ADICIONAR_TILE, w, te.xCoord, te.zCoord);
        }
    }

    public static void adicionarEntidade(World w, Entity e) {
        if (e != null) {
            acesso(Nucleo.A_ADICIONAR_ENTIDADE, w, piso(e.posX), piso(e.posZ));
        }
    }

    public static void removerEntidade(World w, Entity e) {
        if (e != null) {
            acesso(Nucleo.A_REMOVER_ENTIDADE, w, piso(e.posX), piso(e.posZ));
        }
    }

    public static void avisarVizinho(World w, int x, int z) {
        acesso(Nucleo.A_AVISAR_VIZINHO, w, x, z);
    }

    public static void agendarTick(World w, int x, int z) {
        acesso(Nucleo.A_AGENDAR_TICK, w, x, z);
    }

    public static void eventoDeBloco(World w, int x, int z) {
        acesso(Nucleo.A_EVENTO_BLOCO, w, x, z);
    }

    /** Chunk.func_150807_a: escrita direta no chunk. Nao conta se acabou de vir de World.setBlock no mesmo chunk. */
    public static void escritaChunk(Chunk c) {
        try {
            Nucleo n = Observador.N;
            if (n == null || c == null) {
                return;
            }
            if (!ThreadServidor.souAThreadDoServidor()) {
                n.outraThread(Nucleo.A_ESCRITA_CHUNK);
                return;
            }
            if (n.escritaDiretaNova(c.xPosition, c.zPosition)) {
                n.acesso(Nucleo.A_ESCRITA_CHUNK, c.worldObj, dim(c.worldObj), c.xPosition, c.zPosition);
            }
        } catch (Throwable t) {
            Observador.falha("escrita no chunk", t);
        }
    }

    /** TileEntityHopper.func_145893_b: inventario por posicao (coordenadas double). */
    public static void inventarioFunil(World w, double x, double z) {
        acesso(Nucleo.A_INVENTARIO_FUNIL, w, piso(x), piso(z));
    }

    /** ChunkProviderServer.originalLoadChunk, quando o chunk entra no mapa: carga de chunk (T3). */
    public static void cargaChunk(World w, int cx, int cz) {
        try {
            Nucleo n = servidor();
            if (n != null) {
                n.cargaChunk(w, dim(w), cx, cz);
            }
        } catch (Throwable t) {
            Observador.falha("carga de chunk", t);
        }
    }

    // ---------------------------------------------------------------- o jogo, visto pelo observador

    /** O jogo de verdade: fotos das regioes e pasta de saida. */
    static final class JogoMinecraft implements Jogo {

        private static final int TETO = 4000000;

        @Override
        public FotoMundo[] fotografar() {
            WorldServer[] ws = DimensionManager.getWorlds();
            if (ws == null) {
                return new FotoMundo[0];
            }
            java.util.ArrayList<FotoMundo> r = new java.util.ArrayList<FotoMundo>();
            for (WorldServer w : ws) {
                if (w == null || w.provider == null) {
                    continue;
                }
                FotoMundo f = new FotoMundo(w.provider.dimensionId);
                List chunks = w.theChunkProviderServer != null ? w.theChunkProviderServer.loadedChunks : null;
                if (chunks != null) {
                    int tam = Math.min(chunks.size(), TETO);
                    f.chunksX = new int[tam];
                    f.chunksZ = new int[tam];
                    int i = 0;
                    for (Object o : chunks) {
                        if (i >= tam) {
                            break;
                        }
                        if (o instanceof Chunk) {
                            f.chunksX[i] = ((Chunk) o).xPosition;
                            f.chunksZ[i++] = ((Chunk) o).zPosition;
                        }
                    }
                    f.nChunks = i;
                }
                List tiles = w.loadedTileEntityList;
                ListaObservada.mundo(); // a foto e do proprio observador: nao conta como uso de fora
                if (tiles != null) {
                    int tam = Math.min(tiles.size(), TETO);
                    f.tilesX = new int[tam];
                    f.tilesZ = new int[tam];
                    int i = 0;
                    for (Object o : tiles) {
                        if (i >= tam) {
                            break;
                        }
                        if (o instanceof TileEntity) {
                            f.tilesX[i] = ((TileEntity) o).xCoord;
                            f.tilesZ[i++] = ((TileEntity) o).zCoord;
                        }
                    }
                    f.nTiles = i;
                }
                ListaObservada.fimMundo();
                List ents = w.loadedEntityList;
                if (ents != null) {
                    int tam = Math.min(ents.size(), TETO);
                    f.entX = new int[tam];
                    f.entZ = new int[tam];
                    int i = 0;
                    for (Object o : ents) {
                        if (i >= tam) {
                            break;
                        }
                        if (o instanceof Entity) {
                            f.entX[i] = piso(((Entity) o).posX);
                            f.entZ[i++] = piso(((Entity) o).posZ);
                        }
                    }
                    f.nEnt = i;
                }
                r.add(f);
            }
            return r.toArray(new FotoMundo[0]);
        }

        @Override
        public void conferirListas(ContagemLista c) {
            WorldServer[] ws = DimensionManager.getWorlds();
            if (ws == null) {
                return;
            }
            for (WorldServer w : ws) {
                if (w == null) {
                    continue;
                }
                c.mundosConferidos++;
                Object l = w.loadedTileEntityList;
                if (!(l instanceof ListaObservada.Observada)) {
                    c.campoTrocado(l == null ? null : l.getClass().getName());
                }
            }
        }

        @Override
        public File pastaDeSaida() {
            MinecraftServer s = MinecraftServer.getServer();
            return s != null ? s.getFile("hoshiestar") : null;
        }
    }
}
