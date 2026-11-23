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
package hoshiestar.chromaticraft.shift;

import Reika.ChromatiCraft.Registry.ChromaSounds;
import Reika.ChromatiCraft.Registry.Chromabilities;
import Reika.DragonAPI.Instantiable.Data.Immutable.BlockBox;
import hoshiestar.chromaticraft.ChromatiCraft;
import hoshiestar.chromaticraft.DecisaoShift;
import hoshiestar.chromaticraft.DecisaoShift.Motivo;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.common.util.ForgeDirection;

/**
 * Ganchos chamados DE DENTRO de {@code Reika.ChromatiCraft.Auxiliary.Ability.AbilityCalls.shiftArea} depois de
 * transformado:
 *
 * <pre>
 * static boolean shiftArea(WorldServer mundo, BlockBox caixa, ForgeDirection dir, int dist, EntityPlayerMP ep) {
 *     if (GanchoShift.recusar(mundo, caixa, dir, dist, ep)) return GanchoShift.valorRecusado(mundo, caixa, dir, dist, ep); // novo (formato 1), false
 *     ...corpo original, sem nenhuma mudanca no laco...
 *     ChromaSounds.ERROR.playSound(ep)  ->  if (GanchoShift.antesDoSom(ERROR, ep)) ERROR.playSound(ep)             // so com a medicao ligada
 *     Chromabilities.SHIFT.setToPlayer(ep, false)  ->  if (GanchoShift.antesDeTerminar(SHIFT, ep, false)) SHIFT.setToPlayer(ep, false)
 * }
 * </pre>
 *
 * A primeira chamada (e as duas pontes da medicao) ficam dentro de um try/catch de Throwable posto no bytecode pelo
 * transformador: se um gancho nao ligar ou lancar, {@code ChromatiCraft.falhaGancho} anota e o original segue.
 *
 * A decisao mora em {@link DecisaoShift} (codigo puro, e onde o teste isolado a exercita); aqui so se extraem os
 * numeros dos tipos do mod e do Minecraft e se executa a recusa. Regras:
 * <ul>
 * <li>O nosso codigo fica dentro de try/catch: qualquer excecao dele vira "nao recusar" (o pedido segue pelo
 * original) e e contada e registrada com limitador ({@link ChromatiCraft#erro}).</li>
 * <li>Recusar so LE: o mapa de chunks carregados ({@code chunkExists}, que nunca carrega nem gera) e numeros da
 * caixa. A recusa repete o ramo original de "sem energia" (som de erro, habilidade desligada no jogador, devolve
 * false); cada passo tem o proprio try/catch para uma falha no som nao impedir o resto. Nenhum bloco, item ou energia
 * muda.</li>
 * <li>{@code antesDoSom} e {@code antesDeTerminar} so medem e respondem true ("chame a original"); a chamada ORIGINAL
 * e feita pela ponte da classe do mod, fora do try (o que ela lancar sai como sairia sem nos).</li>
 * </ul>
 * O estado da chamada em andamento (para a medicao) e estatico: {@code shiftArea} so roda na thread do servidor e nao
 * se chama sozinha; se algum dia fosse reentrante, so o texto da linha de log lenta ficaria errado.
 */
public final class GanchoShift {

    /**
     * Tudo o que o gancho pede ao ChromatiCraft e ao Minecraft vivos, num lugar so. No servidor e sempre {@link ModReal}; o
     * teste isolado troca (o ChromatiCraft de verdade nao inicia fora do servidor, e com isto o teste ve cada passo da
     * recusa: som, desligar a habilidade, mensagem). O campo e publico so por isso.
     */
    public interface Mod {
        /** {@code Chromabilities.SHIFT.getMaxPower()}: o maior valor de distancia que a habilidade aceita (24). */
        int potenciaMaxima();

        /** {@code ChromaSounds.ERROR.playSound(ep)}: o mesmo som do ramo original de "sem energia". */
        void somDeErro(EntityPlayerMP ep);

        /** {@code Chromabilities.SHIFT.setToPlayer(ep, false)}: o mesmo desligar do ramo original. */
        void desligarHabilidade(EntityPlayerMP ep);

        /** Mensagem de chat so para o jogador. */
        void avisar(EntityPlayerMP ep, String texto);

        /** Nome do jogador (para o log). */
        String nome(EntityPlayer ep);
    }

    private static final class ModReal implements Mod {
        @Override
        public int potenciaMaxima() {
            return Chromabilities.SHIFT.getMaxPower();
        }

        @Override
        public void somDeErro(EntityPlayerMP ep) {
            ChromaSounds.ERROR.playSound(ep);
        }

        @Override
        public void desligarHabilidade(EntityPlayerMP ep) {
            Chromabilities.SHIFT.setToPlayer(ep, false);
        }

        @Override
        public void avisar(EntityPlayerMP ep, String texto) {
            ep.addChatMessage(new ChatComponentText(texto));
        }

        @Override
        public String nome(EntityPlayer ep) {
            return ep.getCommandSenderName();
        }
    }

    /** O acesso ao mod em uso (so os testes isolados trocam). */
    public static volatile Mod mod = new ModReal();

    private static boolean medindo;
    private static long inicioNanos;
    private static boolean falhou;
    private static BlockBox caixaEmCurso;
    private static int distanciaEmCurso;

    private GanchoShift() {
    }

    /** O mapa de chunks carregados do mundo, so para leitura. */
    private static final class Mapa implements DecisaoShift.Chunks {
        private final ChunkProviderServer provedor;

        Mapa(ChunkProviderServer provedor) {
            this.provedor = provedor;
        }

        @Override
        public boolean carregado(int chunkX, int chunkZ) {
            return provedor.chunkExists(chunkX, chunkZ);
        }
    }

    /** true = recuse (saia de shiftArea devolvendo {@link #valorRecusado}); false = siga pelo original. */
    public static boolean recusar(WorldServer mundo, BlockBox caixa, ForgeDirection dir, int dist, EntityPlayerMP ep) {
        medindo = false;
        DecisaoShift d = ChromatiCraft.decisao;
        if (d == null || !ChromatiCraft.ativo) {
            return false;
        }
        Motivo m;
        int potencia;
        try {
            potencia = mod.potenciaMaxima();
            m = d.decidir(ep.getUniqueID(), caixa.minX, caixa.minY, caixa.minZ, caixa.maxX, caixa.maxY, caixa.maxZ,
                    dir.offsetX, dir.offsetY, dir.offsetZ, dist, potencia, new Mapa(mundo.theChunkProviderServer));
        } catch (Throwable t) {
            ChromatiCraft.erro("decisao do mover area", t);
            return false;
        }
        if (m == Motivo.PASSA) {
            if (ChromatiCraft.lentoMs > 0) {
                caixaEmCurso = caixa;
                distanciaEmCurso = dist;
                falhou = false;
                inicioNanos = System.nanoTime();
                medindo = true;
            }
            return false;
        }
        recusarPedido(m, caixa, dir, dist, ep, potencia, d);
        return true;
    }

    /** O valor que shiftArea devolve quando recusa: false, como o ramo original de "sem energia". */
    public static boolean valorRecusado(WorldServer mundo, BlockBox caixa, ForgeDirection dir, int dist, EntityPlayerMP ep) {
        return false;
    }

    /** O ramo de recusa: som de erro, habilidade desligada no jogador, mensagem no chat e linha de log. Nunca lanca. */
    private static void recusarPedido(Motivo m, BlockBox c, ForgeDirection dir, int dist, EntityPlayerMP ep, int potencia, DecisaoShift d) {
        try {
            mod.somDeErro(ep);
        } catch (Throwable t) {
            ChromatiCraft.erroNaRecusa("som da recusa", t);
        }
        try {
            mod.desligarHabilidade(ep);
        } catch (Throwable t) {
            ChromatiCraft.erroNaRecusa("desligar a habilidade na recusa", t);
        }
        try {
            long vol = DecisaoShift.volume(c.minX, c.minY, c.minZ, c.maxX, c.maxY, c.maxZ);
            String msg = ChromatiCraft.mensagemParaJogador(ChromatiCraft.mensagem,
                    ChromatiCraft.textoDoMotivo(m, vol, d.volumeMaximo, dist, potencia));
            if (msg != null) {
                mod.avisar(ep, msg);
            }
        } catch (Throwable t) {
            ChromatiCraft.erroNaRecusa("mensagem da recusa", t);
        }
        try {
            ChromatiCraft.registrarRecusa(m, mod.nome(ep), ep.dimension, c.minX, c.minY, c.minZ, c.maxX,
                    c.maxY, c.maxZ, dir.offsetX, dir.offsetY, dir.offsetZ, dist, potencia);
        } catch (Throwable t) {
            ChromatiCraft.erroNaRecusa("log da recusa", t);
        }
    }

    // ------------------------------------------------------------------ medicao (segunda correcao)

    /**
     * Antes de {@code ChromaSounds.ERROR.playSound(ep)} (ramo de falha de shiftArea): marca que falhou. Devolve true: a
     * ponte chama o original.
     */
    public static boolean antesDoSom(ChromaSounds self, Entity ep) {
        falhou = true;
        return true;
    }

    /**
     * Antes de {@code Chromabilities.SHIFT.setToPlayer(ep, false)} (a ultima coisa de shiftArea antes de devolver):
     * registra no log se a chamada demorou. Devolve true: a ponte chama o original.
     */
    public static boolean antesDeTerminar(Chromabilities self, EntityPlayer ep, boolean ligar) {
        fimDaChamada(ep);
        return true;
    }

    /** A parte nossa de {@link #aoTerminar}: se estava medindo e demorou, registra no log. Nunca lanca. */
    public static void fimDaChamada(EntityPlayer ep) {
        if (medindo) {
            medindo = false;
            try {
                long ms = (System.nanoTime() - inicioNanos) / 1000000L;
                BlockBox c = caixaEmCurso;
                int limite = ChromatiCraft.lentoMs;
                if (c != null && limite > 0 && ms >= limite) {
                    ChromatiCraft.registrarLenta(mod.nome(ep), ep.dimension, c.minX, c.minY, c.minZ, c.maxX,
                            c.maxY, c.maxZ, distanciaEmCurso, ms, falhou);
                }
            } catch (Throwable t) {
                ChromatiCraft.erro("medicao do mover area", t);
            }
        }
    }
}
