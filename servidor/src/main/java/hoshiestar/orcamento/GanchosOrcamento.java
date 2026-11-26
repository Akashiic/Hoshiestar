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
package hoshiestar.orcamento;

import hoshiestar.regioes.FotoMundo;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.NextTickListEntry;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.DimensionManager;

import java.io.File;
import java.net.URL;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Os pontos de entrada que os patches "// Hoshiestar - orcamento" chamam (MinecraftServer.tick, World.updateEntities,
 * WorldServer.tickUpdates, WorldServer.func_147456_g). Os patches so chamam daqui quando {@link Orcamento#LIGADO} e
 * verdadeiro (um teste de campo estatico por gancho). Nenhum metodo daqui lanca excecao: erro nosso num gancho faz a
 * regiao do ponto voltar a ticar normal ({@link Nucleo#falhaNoPonto}; erros demais desligam tudo) e o gancho devolve
 * "roda". Os patches chamam SEM try/catch no ponto de chamada (um try/catch poria um quadro de pilha no bytecode de
 * World.updateEntities, que o Opis casa por sequencia exata de instrucoes; mesma regra do medidor e do vigia).
 *
 * <p><b>O corte:</b> {@link #tile}, {@link #entidade} e {@link #chunkAleatorio} devolvem "a regiao roda neste tick?"; o
 * patch pula o objeto quando e falso. A decisao e fixa no tick (feita no {@link #inicioTick}), entao a regiao pula tudo
 * junto. {@link #puxar} seleciona, pela {@link PuxadaAgendada}, as entradas agendadas vencidas das regioes que rodam (as de
 * regiao cortada ficam na fila) e o patch as tira com as chamadas do original. Nada daqui escreve em objeto do jogo.
 */
public final class GanchosOrcamento {

    private GanchosOrcamento() {
    }

    private static int dim(World w) {
        return w != null && w.provider != null ? w.provider.dimensionId : Integer.MIN_VALUE;
    }

    /** Primeira linha do tick do servidor (depois dos contadores do Hoshiestar). */
    public static void inicioTick() {
        Nucleo n = Orcamento.N;
        if (n != null) {
            n.inicioTick(); // nunca lanca
        }
    }

    /** Ultima linha do tick do servidor. */
    public static void fimTick() {
        Nucleo n = Orcamento.N;
        if (n != null) {
            n.fimTick(); // nunca lanca
        }
    }

    /**
     * Laco de tiles de World.updateEntities, depois do teste de blockExists (fora do trecho que o DragonAPI reescreve) e
     * antes dos ganchos do medidor e do observador: troca a regiao da vez e devolve "roda neste tick?". Falso = o patch
     * pula o update desta tile neste tick (a limpeza de tile invalida/descarregada ja rodou antes).
     */
    public static boolean tile(World w, TileEntity te) {
        Nucleo n = Orcamento.N;
        if (n == null || te == null) {
            return true;
        }
        try {
            return n.tile(dim(w), te.xCoord, te.zCoord);
        } catch (Throwable t) {
            n.falhaNoPonto(dim(w), te.xCoord, te.zCoord, t);
            return true;
        }
    }

    /**
     * No topo do laco de entidades de World.updateEntities: troca a regiao da vez e devolve "roda neste tick?". Falso = o
     * patch pula a entidade neste tick ({@code continue}, antes do teste de montaria e do update). Sempre roda: entidade
     * morta (a remocao do laco segue como no original), jogador e a cadeia de montaria dele ({@link Isencao}).
     */
    public static boolean entidade(World w, Entity e) {
        Nucleo n = Orcamento.N;
        if (n == null || e == null) {
            return true;
        }
        int x = 0, z = 0;
        try {
            x = floor(e.posX);
            z = floor(e.posZ);
            return n.decidirEntidade(dim(w), x, z, e.isDead, Isencao.isenta(e, MONTARIA));
        } catch (Throwable t) {
            n.falhaNoPonto(dim(w), x, z, t);
            return true;
        }
    }

    /** Antes do tick de uma entrada agendada ja puxada (WorldServer.tickUpdates): so mede (toda entrada puxada roda). */
    public static void agendado(World w, int x, int z) {
        Nucleo n = Orcamento.N;
        if (n == null) {
            return;
        }
        try {
            n.agendado(dim(w), x, z);
        } catch (Throwable t) {
            n.falhaNoPonto(dim(w), x, z, t);
        }
    }

    /**
     * A selecao da puxada da fila de ticks agendados (WorldServer.tickUpdates, so com forcar = false). Devolve true se ha
     * corte neste tick e a selecao foi feita: o patch pula o laco original e tira as selecionadas com {@link #proxima},
     * usando as mesmas chamadas do original (TreeSet.remove, HashSet.remove, List.add). False: o patch roda o laco
     * original, sem mudanca. Nunca lanca; nao mexe na fila.
     */
    public static boolean puxar(World w, TreeSet fila, int teto, long agora) {
        Nucleo n = Orcamento.N;
        if (n == null || fila == null) {
            return false;
        }
        return n.selecionarAgendados(dim(w), fila, teto, agora, ENTRADA); // nunca lanca
    }

    /** A proxima entrada selecionada pela {@link #puxar}, ou null no fim. Nunca lanca. */
    public static NextTickListEntry proxima() {
        Nucleo n = Orcamento.N;
        if (n == null) {
            return null;
        }
        try {
            return (NextTickListEntry) n.proximaAgendada();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Antes do tick aleatorio de um chunk (WorldServer.func_147456_g, depois do teste de descarga): troca a regiao da vez e
     * devolve "roda neste tick?". Falso = o patch pula o chunk neste tick (luz, clima, raio e ticks aleatorios dele).
     */
    public static boolean chunkAleatorio(World w, int cx, int cz) {
        Nucleo n = Orcamento.N;
        if (n == null) {
            return true;
        }
        try {
            return n.chunkAleatorio(dim(w), cx, cz);
        } catch (Throwable t) {
            n.falhaNoPonto(dim(w), cx << 4, cz << 4, t);
            return true;
        }
    }

    /** Fim de um laco. */
    public static void fimLaco() {
        Nucleo n = Orcamento.N;
        if (n != null) {
            n.fimLaco(); // nunca lanca
        }
    }

    /** Leitura da entrada da fila de ticks agendados (campos publicos de NextTickListEntry). */
    static final PuxadaAgendada.Acesso<NextTickListEntry> ENTRADA = new PuxadaAgendada.Acesso<NextTickListEntry>() {
        @Override
        public long quando(NextTickListEntry e) {
            return e.scheduledTime;
        }

        @Override
        public int x(NextTickListEntry e) {
            return e.xCoord;
        }

        @Override
        public int z(NextTickListEntry e) {
            return e.zCoord;
        }
    };

    /** Cadeia de montaria (campos publicos de Entity). */
    static final Isencao.Montaria<Entity> MONTARIA = new Isencao.Montaria<Entity>() {
        @Override
        public boolean ehJogador(Entity e) {
            return e instanceof EntityPlayer;
        }

        @Override
        public Entity montadoPor(Entity e) {
            return e.riddenByEntity;
        }

        @Override
        public Entity montadoEm(Entity e) {
            return e.ridingEntity;
        }
    };

    /** Aviso de boot: limitador do Spigot (max-tick-time) abaixo de 1000 ms pode parar o laco no meio de uma rodada. */
    static void avisarLimitadorSpigot() {
        try {
            WorldServer[] ws = DimensionManager.getWorlds();
            if (ws == null) {
                return;
            }
            for (WorldServer w : ws) {
                if (w == null || w.spigotConfig == null || w.provider == null) {
                    continue;
                }
                if (w.spigotConfig.tileMaxTickTime < 1000 || w.spigotConfig.entityMaxTickTime < 1000) {
                    hoshiestar.api.Log.aviso("orcamento: o mundo da dimensao " + w.provider.dimensionId
                            + " tem max-tick-time do Spigot abaixo de 1000 ms; o limitador pode parar o laco no meio de"
                            + " uma rodada (o resto da regiao roda no tick seguinte em que ela rodar)");
                }
            }
        } catch (Throwable t) {
            // so um aviso
        }
    }

    static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    /** O jogo de verdade, visto pelo {@link Nucleo}. */
    static final class JogoMinecraft implements Jogo {

        private static final int TETO = 4000000;
        private final FerramentasMinecraft ferramentas = new FerramentasMinecraft();

        FerramentasMinecraft ferramentas() {
            return ferramentas;
        }

        @Override
        public FotoMundo[] fotografar() {
            WorldServer[] ws = DimensionManager.getWorlds();
            if (ws == null) {
                return new FotoMundo[0];
            }
            ArrayList<FotoMundo> r = new ArrayList<FotoMundo>();
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
                hoshiestar.observador.ListaObservada.mundo(); // a foto e nossa: nao conta como uso de fora da lista
                if (tiles != null) {
                    int tam = Math.min(tiles.size(), TETO);
                    f.tilesX = new int[tam];
                    f.tilesZ = new int[tam];
                    f.tilesClasse = new String[tam];
                    int i = 0;
                    for (Object o : tiles) {
                        if (i >= tam) {
                            break;
                        }
                        if (o instanceof TileEntity) {
                            f.tilesX[i] = ((TileEntity) o).xCoord;
                            f.tilesZ[i] = ((TileEntity) o).zCoord;
                            f.tilesClasse[i++] = o.getClass().getName();
                        }
                    }
                    f.nTiles = i;
                }
                hoshiestar.observador.ListaObservada.fimMundo();
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
                            f.entX[i] = floor(((Entity) o).posX);
                            f.entZ[i++] = floor(((Entity) o).posZ);
                        }
                    }
                    f.nEnt = i;
                }
                r.add(f);
            }
            return r.toArray(new FotoMundo[0]);
        }

        @Override
        public int[][] spawns() {
            ArrayList<int[]> r = new ArrayList<int[]>();
            WorldServer[] ws = DimensionManager.getWorlds();
            if (ws != null) {
                for (WorldServer w : ws) {
                    if (w != null && w.provider != null && w.getWorldInfo() != null) {
                        r.add(new int[] {w.provider.dimensionId, w.getWorldInfo().getSpawnX(), w.getWorldInfo().getSpawnZ()});
                    }
                }
            }
            return r.toArray(new int[0][]);
        }

        @Override
        public int[][] jogadores() {
            ArrayList<int[]> r = new ArrayList<int[]>();
            WorldServer[] ws = DimensionManager.getWorlds();
            if (ws != null) {
                for (WorldServer w : ws) {
                    if (w == null || w.provider == null || w.playerEntities == null) {
                        continue;
                    }
                    for (Object o : w.playerEntities) {
                        if (o instanceof EntityPlayer) {
                            EntityPlayer p = (EntityPlayer) o;
                            r.add(new int[] {w.provider.dimensionId, floor(p.posX), floor(p.posZ)});
                        }
                    }
                }
            }
            return r.toArray(new int[0][]);
        }

        @Override
        public String modDaClasse(String nomeDaClasse) {
            try {
                Class<?> c = Class.forName(nomeDaClasse, false, Launch.classLoader);
                CodeSource cs = c.getProtectionDomain() == null ? null : c.getProtectionDomain().getCodeSource();
                URL u = cs == null ? null : cs.getLocation();
                if (u == null) {
                    return null;
                }
                String p = u.getPath();
                String nome = p.substring(p.lastIndexOf('/') + 1);
                if (!nome.toLowerCase().endsWith(".jar")) {
                    return null; // pasta de classes (servidor em desenvolvimento) ou desconhecido
                }
                return nome.substring(0, nome.length() - 4);
            } catch (Throwable t) {
                return null;
            }
        }

        @Override
        public File pastaDeSaida() {
            MinecraftServer s = MinecraftServer.getServer();
            return s != null ? s.getFile("hoshiestar") : null;
        }

        @Override
        public void log(String linha) {
            hoshiestar.api.Log.info(linha);
        }

        @Override
        public void avisarOperadores(String linha) {
            MinecraftServer s = MinecraftServer.getServer();
            if (s == null || s.getConfigurationManager() == null) {
                return;
            }
            for (Object o : s.getConfigurationManager().playerEntityList) {
                if (o instanceof EntityPlayer && ((EntityPlayer) o).canCommandSenderUseCommand(4, "hoshie")) {
                    ((EntityPlayer) o).addChatMessage(new ChatComponentText(linha));
                }
            }
        }

        @Override
        public void aoFimDoTick(long tick, Retrato retrato) {
            ferramentas.aoFimDoTick(retrato);
        }
    }
}
