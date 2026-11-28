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

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.ChunkProviderServer;

/**
 * Os pontos "A" da secao 4 do plano do Degrau B (passo 4): metodos {@code void} cujo efeito so vale no tick seguinte.
 * Cada patch "// Hoshiestar - paralelo" chama daqui com {@code if (Paralelo.LIGADO && GanchosAdiados.x(this, ...)) return;}.
 *
 * <p>Sempre: a guarda conta, como no passo 3 (com o contexto de um tile de regiao elegivel aberto, "adiado"). So com um
 * {@link Lote} aberto pela thread do servidor (modo {@code sombra_ordenada}, enquanto ela roda o lote): o metodo
 * original NAO roda agora; os argumentos vao para o lote como um {@link Adiado}, e o metodo devolve verdadeiro (o patch
 * faz {@code return}). A {@link Juncao} chama o metodo original inteiro, com o contexto fechado: ai este gancho conta e
 * devolve falso, e o metodo roda como em serie (com a deduplicacao e os mixins de terceiros dele).
 *
 * <p>No modo {@code sombra} nenhum lote abre ({@link GanchosParalelo#loteDaVez} so e escrito por {@code OrdenadaJogo}):
 * todo gancho daqui devolve falso e o jogo roda exatamente como no original (conferido no TesteParaleloBytecode).
 *
 * <p>Mixins que rodam ANTES deste gancho (conferido nas classes finais do passo 3): o akashicmixer poe um "enter" no
 * comeco dos tres metodos de som e um "exit" antes de cada {@code return}, inclusive o deste patch; o hodgepodge poe
 * "increment" e "decrement" do contador de recursao no {@code scheduleBlockUpdateWithPriority}, tambem em cada
 * {@code return}. Adiar roda esse par uma vez a mais (vazio, sem nada entre o "enter" e o "exit"); a juncao roda o par de
 * novo, com o corpo. Os dois sao contadores ou contexto em pares, sem efeito no mundo.
 */
public final class GanchosAdiados {

    private GanchosAdiados() {
    }

    /** O lote aberto, so para a thread do servidor (qualquer outra thread nunca adia). */
    private static Lote lote() {
        Lote l = GanchosParalelo.loteDaVez;
        return l != null && Thread.currentThread() == Guardas.servidor ? l : null;
    }

    private static String pos(int x, int y, int z) {
        return " (" + x + ", " + y + ", " + z + ")";
    }

    private static int f(double d) {
        return GanchosParalelo.floor(d);
    }

    // ---- World: os 11 metodos que percorrem worldAccesses (W:999 e familia)

    public static boolean marcarBloco(final World w, final int x, final int y, final int z) {
        GanchosParalelo.avisoCliente(w, x, z);
        Lote l = lote();
        if (l == null) {
            return false;
        }
        l.adiar(new Adiado() {
            @Override
            public void aplicar() {
                w.markBlockForUpdate(x, y, z);
            }

            @Override
            public String descrever() {
                return "markBlockForUpdate" + pos(x, y, z);
            }
        });
        return true;
    }

    public static boolean marcarFaixa(final World w, final int x1, final int y1, final int z1, final int x2, final int y2, final int z2) {
        GanchosParalelo.avisoCliente(w, x1, z1);
        Lote l = lote();
        if (l == null) {
            return false;
        }
        l.adiar(new Adiado() {
            @Override
            public void aplicar() {
                w.markBlockRangeForRenderUpdate(x1, y1, z1, x2, y2, z2);
            }

            @Override
            public String descrever() {
                return "markBlockRangeForRenderUpdate" + pos(x1, y1, z1) + pos(x2, y2, z2);
            }
        });
        return true;
    }

    public static boolean marcarRender(final World w, final int x, final int y, final int z) {
        GanchosParalelo.avisoCliente(w, x, z);
        Lote l = lote();
        if (l == null) {
            return false;
        }
        l.adiar(new Adiado() {
            @Override
            public void aplicar() {
                w.func_147479_m(x, y, z);
            }

            @Override
            public String descrever() {
                return "func_147479_m" + pos(x, y, z);
            }
        });
        return true;
    }

    public static boolean somNaEntidade(final World w, final Entity e, final String som, final float vol, final float tom) {
        GanchosParalelo.avisoCliente(w, f(e.posX), f(e.posZ));
        Lote l = lote();
        if (l == null) {
            return false;
        }
        l.adiar(new Adiado() {
            @Override
            public void aplicar() {
                w.playSoundAtEntity(e, som, vol, tom);
            }

            @Override
            public String descrever() {
                return "playSoundAtEntity " + som + " em " + e.getClass().getName();
            }
        });
        return true;
    }

    public static boolean somPertoMenos(final World w, final EntityPlayer p, final String som, final float vol, final float tom) {
        GanchosParalelo.avisoCliente(w, f(p.posX), f(p.posZ));
        Lote l = lote();
        if (l == null) {
            return false;
        }
        l.adiar(new Adiado() {
            @Override
            public void aplicar() {
                w.playSoundToNearExcept(p, som, vol, tom);
            }

            @Override
            public String descrever() {
                return "playSoundToNearExcept " + som;
            }
        });
        return true;
    }

    public static boolean som(final World w, final double x, final double y, final double z, final String som, final float vol, final float tom) {
        GanchosParalelo.avisoCliente(w, f(x), f(z));
        Lote l = lote();
        if (l == null) {
            return false;
        }
        l.adiar(new Adiado() {
            @Override
            public void aplicar() {
                w.playSoundEffect(x, y, z, som, vol, tom);
            }

            @Override
            public String descrever() {
                return "playSoundEffect " + som + pos(f(x), f(y), f(z));
            }
        });
        return true;
    }

    public static boolean disco(final World w, final String disco, final int x, final int y, final int z) {
        GanchosParalelo.avisoCliente(w, x, z);
        Lote l = lote();
        if (l == null) {
            return false;
        }
        l.adiar(new Adiado() {
            @Override
            public void aplicar() {
                w.playRecord(disco, x, y, z);
            }

            @Override
            public String descrever() {
                return "playRecord" + pos(x, y, z);
            }
        });
        return true;
    }

    public static boolean particula(final World w, final String nome, final double x, final double y, final double z, final double vx,
            final double vy, final double vz) {
        GanchosParalelo.avisoCliente(w, f(x), f(z));
        Lote l = lote();
        if (l == null) {
            return false;
        }
        l.adiar(new Adiado() {
            @Override
            public void aplicar() {
                w.spawnParticle(nome, x, y, z, vx, vy, vz);
            }

            @Override
            public String descrever() {
                return "spawnParticle " + nome + pos(f(x), f(y), f(z));
            }
        });
        return true;
    }

    public static boolean somGeral(final World w, final int id, final int x, final int y, final int z, final int dado) {
        GanchosParalelo.avisoCliente(w, x, z);
        Lote l = lote();
        if (l == null) {
            return false;
        }
        l.adiar(new Adiado() {
            @Override
            public void aplicar() {
                w.playBroadcastSound(id, x, y, z, dado);
            }

            @Override
            public String descrever() {
                return "playBroadcastSound " + id + pos(x, y, z);
            }
        });
        return true;
    }

    public static boolean efeito(final World w, final EntityPlayer p, final int id, final int x, final int y, final int z, final int dado) {
        GanchosParalelo.avisoCliente(w, x, z);
        Lote l = lote();
        if (l == null) {
            return false;
        }
        l.adiar(new Adiado() {
            @Override
            public void aplicar() {
                w.playAuxSFXAtEntity(p, id, x, y, z, dado);
            }

            @Override
            public String descrever() {
                return "playAuxSFXAtEntity " + id + pos(x, y, z);
            }
        });
        return true;
    }

    public static boolean quebrando(final World w, final int entidade, final int x, final int y, final int z, final int progresso) {
        GanchosParalelo.avisoCliente(w, x, z);
        Lote l = lote();
        if (l == null) {
            return false;
        }
        l.adiar(new Adiado() {
            @Override
            public void aplicar() {
                w.destroyBlockInWorldPartially(entidade, x, y, z, progresso);
            }

            @Override
            public String descrever() {
                return "destroyBlockInWorldPartially" + pos(x, y, z);
            }
        });
        return true;
    }

    // ---- WorldServer: ticks agendados (WS:596, WS:643) e evento de bloco (WS:1256)

    public static boolean tickAgendado(final WorldServer w, final int x, final int y, final int z, final Block b, final int atraso, final int prioridade) {
        GanchosParalelo.tickAgendado(w, x, z);
        Lote l = lote();
        if (l == null) {
            return false;
        }
        l.adiar(new Adiado() {
            @Override
            public void aplicar() {
                w.scheduleBlockUpdateWithPriority(x, y, z, b, atraso, prioridade);
            }

            @Override
            public String descrever() {
                return "scheduleBlockUpdateWithPriority" + pos(x, y, z);
            }
        });
        return true;
    }

    public static boolean tickAgendadoB(final WorldServer w, final int x, final int y, final int z, final Block b, final int atraso, final int prioridade) {
        GanchosParalelo.tickAgendado(w, x, z);
        Lote l = lote();
        if (l == null) {
            return false;
        }
        l.adiar(new Adiado() {
            @Override
            public void aplicar() {
                w.func_147446_b(x, y, z, b, atraso, prioridade);
            }

            @Override
            public String descrever() {
                return "func_147446_b" + pos(x, y, z);
            }
        });
        return true;
    }

    public static boolean eventoDeBloco(final WorldServer w, final int x, final int y, final int z, final Block b, final int evento, final int dado) {
        GanchosParalelo.eventoDeBloco(w, x, z);
        Lote l = lote();
        if (l == null) {
            return false;
        }
        l.adiar(new Adiado() {
            @Override
            public void aplicar() {
                w.addBlockEvent(x, y, z, b, evento, dado);
            }

            @Override
            public String descrever() {
                return "addBlockEvent " + evento + "/" + dado + pos(x, y, z);
            }
        });
        return true;
    }

    // ---- ChunkProviderServer.loadChunk (CPS:174): chunk presente sai da fila de descarga

    /**
     * {@code loadChunk} com o chunk ja carregado (o que um tile liberado alcanca, sob G1) so tira o chunk da fila de
     * descarga: adiado ("nao descarregar"); a juncao chama o {@code loadChunk} original, que faz isso. Chunk ausente, ou
     * pedido com {@code Runnable} (carga assincrona): proibido na fase, conta como antes e roda o original. Devolve
     * verdadeiro quando adiou: o patch devolve o chunk presente.
     */
    public static boolean naoDescarregar(final ChunkProviderServer cps, final int x, final int z, Runnable r) {
        if (Guardas.fase == Guardas.FECHADA) {
            return false; // fora da fase: nem a leitura extra do mapa
        }
        boolean presente = r == null && cps.loadedChunkHashMap_KC != null && cps.loadedChunkHashMap_KC.rawThermos().get(x, z) != null;
        if (!presente) {
            GanchosParalelo.cargaChunk(cps.worldObj, x, z);
            return false;
        }
        GanchosParalelo.naoDescarregar(cps.worldObj, x, z);
        Lote l = lote();
        if (l == null) {
            return false;
        }
        l.adiar(new Adiado() {
            @Override
            public void aplicar() {
                cps.loadChunk(x, z);
            }

            @Override
            public String descrever() {
                return "loadChunk (chunk presente: sai da fila de descarga) (" + x + ", " + z + ")";
            }
        });
        return true;
    }
}
