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

import org.bukkit.event.Event;

/**
 * As guardas que nao precisam de classe do Minecraft (plano do Degrau B, secao 4): evento do Forge
 * ({@code EventBus.post}), evento do Bukkit ({@code SimplePluginManager.callEvent}), pacote ({@code sendPacket}) e
 * sorteio no {@code world.rand} ({@code ThermiteRandom.next}). Ficam fora de {@code GanchosParalelo} de proposito: o
 * {@code EventBus} e o {@code ThermiteRandom} sao usados cedo, e carregar uma classe que cita {@code World} nessa hora
 * poderia carregar o {@code World} antes dos mixins.
 *
 * <p>Modo "contar" (passo 3): nada muda o retorno nem barra. Fase FECHADA = um teste de campo e volta. Passo 4: o
 * {@code callEvent} sem ouvinte, com um lote do modo {@code sombra_ordenada} aberto, volta sem entrar no
 * {@code synchronized} ({@link #eventoBukkit}).
 */
public final class GanchosEventos {

    private GanchosEventos() {
    }

    private static int quem() {
        return Thread.currentThread() == Guardas.servidor ? Guardas.SERVIDOR : Guardas.ESTRANHA;
    }

    /** Topo de {@code EventBus.post}. */
    public static void eventoForge() {
        if (Guardas.fase == Guardas.FECHADA) {
            return;
        }
        int quem = quem();
        Guardas.Res r = Guardas.decidir(Guardas.Op.EVENTO_FORGE, Guardas.fase, quem,
                quem == Guardas.SERVIDOR ? Guardas.regiaoAberta : -1, -2, false, false);
        Registro.registrar(Guardas.Op.EVENTO_FORGE, r, 0, 0, -1);
    }

    /**
     * Topo de {@code SimplePluginManager.callEvent} (antes de qualquer synchronized). Devolve verdadeiro so com um lote do
     * modo {@code sombra_ordenada} aberto pela thread do servidor, evento sincrono e nenhum ouvinte: o patch volta sem
     * entrar no {@code synchronized} (plano, secao 4: efeito igual, o {@code fireEvent} nao teria ninguem a chamar). Em
     * {@code sombra} sempre falso.
     */
    public static boolean eventoBukkit(Event e) {
        if (Guardas.fase == Guardas.FECHADA) {
            return false;
        }
        int quem = quem();
        int ctx = quem == Guardas.SERVIDOR ? Guardas.regiaoAberta : -1;
        if (quem == Guardas.SERVIDOR && ctx < 0) {
            return false; // fora de tile de regiao elegivel: evento de tile em serie, nada a conferir
        }
        boolean ouvinte = true; // na duvida, conta como "tem ouvinte" (nunca a favor de liberar)
        try {
            ouvinte = e == null || e.getHandlers().getRegisteredListeners().length > 0;
        } catch (Throwable t) {
            // fica true
        }
        Guardas.Res r = Guardas.decidir(Guardas.Op.EVENTO_BUKKIT, Guardas.fase, quem, ctx, -2, false, ouvinte);
        Registro.registrar(Guardas.Op.EVENTO_BUKKIT, r, 0, 0, -1);
        if (!Guardas.loteAberto || quem != Guardas.SERVIDOR || ouvinte) {
            return false;
        }
        try {
            return !e.isAsynchronous();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Topo de {@code NetHandlerPlayServer.sendPacket}. */
    public static void pacote() {
        if (Guardas.fase == Guardas.FECHADA) {
            return;
        }
        int quem = quem();
        if (quem != Guardas.SERVIDOR || Guardas.regiaoAberta < 0) {
            return; // rede de outra thread ou de tile em serie: fora da conta
        }
        Guardas.Res r = Guardas.decidir(Guardas.Op.PACOTE, Guardas.fase, quem, Guardas.regiaoAberta, -2, false, false);
        Registro.registrar(Guardas.Op.PACOTE, r, 0, 0, -1);
    }

    /** Topo de {@code ThermiteRandom.next}: so conta o {@code rand} do mundo da fase, sorteado dentro de tile de regiao elegivel. */
    public static void sorteio(Object rand) {
        if (Guardas.fase == Guardas.FECHADA || rand != Guardas.randDoMundo) {
            return;
        }
        int quem = quem();
        if (quem != Guardas.SERVIDOR || Guardas.regiaoAberta < 0) {
            return;
        }
        Guardas.Res r = Guardas.decidir(Guardas.Op.SORTEIO, Guardas.fase, quem, Guardas.regiaoAberta, -2, false, false);
        Registro.registrar(Guardas.Op.SORTEIO, r, 0, 0, -1);
    }
}
