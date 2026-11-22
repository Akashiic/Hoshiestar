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
package hoshiestar.embutidos.autoteste;

import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.entity.player.EntityPlayerMP;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Ganchos do autoteste, chamados DE DENTRO da classe do OpenBlocks depois de transformada. Sao
 * "public static" numa classe publica (exigencia do utilitario) e o receptor e declarado como
 * Object, entao esta classe compila sem o jar do OpenBlocks.
 */
public final class GanchoAutoteste {

    private static final AtomicLong CHAMADAS = new AtomicLong();
    private static final AtomicLong NUNCA = new AtomicLong();
    private static final AtomicLong KARMA = new AtomicLong();
    private static volatile String carregador = "ainda nao conferido (gancho nao foi chamado)";

    private GanchoAutoteste() {
    }

    /**
     * Gancho no inicio de ServerTickHandler.onServerTick (roda 2 vezes por tick: fases START e END).
     * Devolve false: nunca sai antes, o metodo original roda inteiro.
     */
    public static boolean inicioServerTick(Object handler, TickEvent.ServerTickEvent evento) {
        if (CHAMADAS.getAndIncrement() == 0) {
            conferirCarregador(handler);
        }
        return false;
    }

    /**
     * Gancho do formato 3 em ServerTickHandler.onPlayerTick: decide se a chamada original
     * FlimFlamEnchantmentsHandler.deliverKarma(jogador) acontece. Conta e devolve true (sempre
     * acontece). O parametro e um tipo do Minecraft: no arquivo deste gancho ele fica reofuscado.
     */
    public static boolean deveEntregarKarma(EntityPlayerMP jogador) {
        KARMA.incrementAndGet();
        return true;
    }

    /** Gancho da correcao com trava errada: nunca deve rodar. */
    public static void nuncaChamado(Object handler, TickEvent.ServerTickEvent evento) {
        NUNCA.incrementAndGet();
    }

    public static long chamadas() {
        return CHAMADAS.get();
    }

    public static long chamadasKarma() {
        return KARMA.get();
    }

    public static long nuncaChamadas() {
        return NUNCA.get();
    }

    public static String carregador() {
        return carregador;
    }

    /**
     * Prova (item 3b do projeto) de que a classe de gancho enxerga as classes do mod: o class loader
     * desta classe e o mesmo do OpenBlocks, e ele resolve o nome da classe do mod para a MESMA classe.
     */
    private static void conferirCarregador(Object handler) {
        try {
            ClassLoader meu = GanchoAutoteste.class.getClassLoader();
            Class<?> doMod = handler.getClass();
            Class<?> visto = Class.forName(doMod.getName(), false, meu);
            boolean ok = meu == doMod.getClassLoader() && visto == doMod;
            carregador = (ok ? "sim" : "NAO") + " (gancho: " + nome(meu) + ", mod: " + nome(doMod.getClassLoader())
                    + ", nome resolvido para a mesma classe: " + (visto == doMod ? "sim" : "nao") + ")";
        } catch (Throwable t) {
            carregador = "NAO (" + t + ")";
        }
    }

    private static String nome(ClassLoader cl) {
        return cl == null ? "bootstrap" : cl.getClass().getName();
    }
}
