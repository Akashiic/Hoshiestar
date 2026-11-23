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
package hoshiestar.parada;

import hoshiestar.api.Log;
import hoshiestar.comando.ComandoHoshie;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;

/**
 * Subcomando de TESTE do recurso "parada": {@code hoshie parada-travar <segundos>} segura a thread
 * do servidor parada por N segundos (um "tick travado"), para testar o caminho de desistencia do
 * gancho de desligamento numa copia de teste.
 *
 * <p>So existe se a JVM for ligada com {@code -Dhoshiestar.parada.teste=true}; sem isso o
 * subcomando nem e registrado. So o console pode usar. Nunca ligue isso em producao.
 */
public final class ComandoTesteParada implements ComandoHoshie.Subcomando {

    public static final String PROPRIEDADE = "hoshiestar.parada.teste";

    private static final int MAX_SEGUNDOS = 120;

    private ComandoTesteParada() {
    }

    /** Chamado por hoshiestar.Servidor.iniciando (servidor de pe). */
    public static void registrarSePedido() {
        if (Boolean.getBoolean(PROPRIEDADE)) {
            ComandoHoshie.registrar(new ComandoTesteParada());
            Log.de(Parada.ID).a("subcomando de TESTE 'hoshie parada-travar' registrado (-D" + PROPRIEDADE + "=true); nao use em producao");
        }
    }

    @Override
    public String nome() {
        return "parada-travar";
    }

    @Override
    public String ajuda() {
        return "parada-travar <segundos> - TESTE: segura a thread do servidor parada (so console)";
    }

    @Override
    public void executar(CommandSender quem, String[] args) {
        if (!(quem instanceof ConsoleCommandSender)) {
            quem.sendMessage("parada-travar: so no console.");
            return;
        }
        int s;
        try {
            s = Integer.parseInt(args[0]);
        } catch (RuntimeException e) {
            quem.sendMessage("uso: hoshie parada-travar <segundos, 1 a " + MAX_SEGUNDOS + ">");
            return;
        }
        if (s < 1 || s > MAX_SEGUNDOS) {
            quem.sendMessage("uso: hoshie parada-travar <segundos, 1 a " + MAX_SEGUNDOS + ">");
            return;
        }
        Log.de(Parada.ID).a("TESTE: thread " + Thread.currentThread().getName() + " travada por " + s + " s (hoshie parada-travar)");
        long fim = System.nanoTime() + s * 1000000000L;
        for (;;) {
            long falta = fim - System.nanoTime();
            if (falta <= 0L) {
                break;
            }
            try {
                Thread.sleep(Math.max(1L, falta / 1000000L));
            } catch (InterruptedException e) {
                // segue ate o fim do prazo: e para simular um tick travado
            }
        }
        Log.de(Parada.ID).a("TESTE: thread do servidor destravada");
    }
}
