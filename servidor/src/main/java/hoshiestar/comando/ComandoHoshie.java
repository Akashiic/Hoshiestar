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
package hoshiestar.comando;

import hoshiestar.Hoshiestar;
import hoshiestar.api.HashClasse;
import hoshiestar.api.Recursos;
import hoshiestar.api.Relogio;
import hoshiestar.api.ThreadServidor;
import hoshiestar.config.HoshiestarConfig;
import hoshiestar.embutidos.Correcao;
import hoshiestar.embutidos.Correcoes;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.craftbukkit.v1_7_R4.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Comando "hoshie" (console, RCON e operador de nivel 4). Registrado como comando Bukkit pelo
 * proprio servidor, do mesmo jeito que o Crucible registra o "crucible" (no
 * FMLServerStartingEvent do CrucibleModContainer, via {@code registerServerCommand}).
 *
 * <ul>
 * <li>{@code hoshie status}: versao, relogio, thread do servidor, cada recurso e seu estado;</li>
 * <li>{@code hoshie hash <classe>}: SHA-256 dos bytes originais da classe (para a trava de versao).</li>
 * </ul>
 *
 * Outros recursos acrescentam subcomandos com {@link #registrar(Subcomando)} (ex.: "medir",
 * "parar" do medidor). Nomes "status" e "hash" sao reservados.
 *
 * <p><b>Quem pode usar:</b> console e RCON sempre; jogador so se for operador de nivel 4 E tiver o
 * no de permissao {@value #PERMISSAO} (registrado com padrao "op": todo operador tem, a menos que um
 * plugin de permissao o negue). O no faz o Bukkit esconder o comando do tab e do /help de quem nao
 * tem, e deixa um plugin de permissao (ex.: LuckPerms) tirar o comando de um operador; ele NAO libera
 * o comando para quem nao e operador de nivel 4. Jogador sem acesso recebe a mesma resposta de um
 * comando que nao existe (nao fica sabendo que o comando existe).
 */
public final class ComandoHoshie extends Command {

    /** Um subcomando de "hoshie". */
    public interface Subcomando {
        /** Nome (minusculo, sem espacos). */
        String nome();

        /** Uma linha de ajuda, ex.: "medir [segundos] - mede a carga por regiao". */
        String ajuda();

        /** Executa. {@code args} sao os argumentos depois do nome do subcomando. Ja passou da permissao. */
        void executar(CommandSender quem, String[] args);
    }

    public static final int NIVEL_OP = 4;

    /** No de permissao Bukkit do comando (padrao "op"). */
    public static final String PERMISSAO = "hoshiestar.admin";

    private static final Map<String, Subcomando> SUBCOMANDOS = Collections.synchronizedMap(new LinkedHashMap<String, Subcomando>());

    public ComandoHoshie() {
        super("hoshie");
        setDescription("Hoshiestar: estado dos recursos e ferramentas do admin");
        setUsage("/hoshie <status|hash <classe>|...>");
        setPermission(PERMISSAO);
    }

    /**
     * Registra o no {@value #PERMISSAO} no Bukkit com padrao "op" (chamado com o servidor de pe, junto
     * com o comando). Sem isso o no funcionaria igual (o Bukkit trata no desconhecido como "op"), mas
     * plugins de permissao nao o veriam na lista. Nunca lanca excecao.
     */
    public static void registrarPermissao() {
        try {
            if (Bukkit.getPluginManager().getPermission(PERMISSAO) == null) {
                Bukkit.getPluginManager().addPermission(new Permission(PERMISSAO,
                        "Hoshiestar: comando /hoshie (alem disso exige operador de nivel 4)", PermissionDefault.OP));
            }
        } catch (Throwable t) {
            hoshiestar.api.Log.aviso("nao consegui registrar a permissao " + PERMISSAO + " (" + t + "); o comando segue so para console e operador de nivel 4 com o padrao do Bukkit");
        }
    }

    /** Acrescenta um subcomando. Nome repetido ou reservado e erro de programacao. */
    public static void registrar(Subcomando s) {
        String n = s.nome().toLowerCase(Locale.ROOT);
        if (n.equals("status") || n.equals("hash") || SUBCOMANDOS.containsKey(n)) {
            throw new IllegalArgumentException("subcomando ja existe: " + n);
        }
        SUBCOMANDOS.put(n, s);
    }

    /** Console, RCON, ou jogador operador de nivel 4 com o no {@value #PERMISSAO}. Bloco de comando e outros: nao. */
    public static boolean podeUsar(CommandSender quem) {
        if (ehConsole(quem)) {
            return true;
        }
        if (quem instanceof CraftPlayer) {
            return ((CraftPlayer) quem).getHandle().canCommandSenderUseCommand(NIVEL_OP, "hoshie")
                    && quem.hasPermission(PERMISSAO);
        }
        return false;
    }

    private static boolean ehConsole(CommandSender quem) {
        return quem instanceof ConsoleCommandSender || quem instanceof RemoteConsoleCommandSender;
    }

    @Override
    public boolean execute(CommandSender quem, String rotulo, String[] args) {
        if (!podeUsar(quem)) {
            if (quem instanceof Player) {
                // mesma resposta de um comando inexistente (CraftServer.dispatchVanillaCommand)
                quem.sendMessage(org.spigotmc.SpigotConfig.unknownCommandMessage);
            } else {
                quem.sendMessage("hoshie: so console ou operador de nivel " + NIVEL_OP + ".");
            }
            return true;
        }
        try {
            String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
            String[] resto = new String[Math.max(0, args.length - 1)];
            if (args.length > 1) {
                System.arraycopy(args, 1, resto, 0, resto.length);
            }
            if (sub.equals("status")) {
                status(quem);
            } else if (sub.equals("hash")) {
                hash(quem, resto);
            } else {
                Subcomando s = SUBCOMANDOS.get(sub);
                if (s == null) {
                    ajuda(quem);
                } else {
                    s.executar(quem, resto);
                }
            }
        } catch (Throwable t) {
            quem.sendMessage("hoshie: erro: " + t);
            hoshiestar.api.Log.erro("erro no comando hoshie", t);
        }
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender quem, String alias, String[] args) {
        if (args.length == 1 && podeUsar(quem)) {
            List<String> l = new ArrayList<String>();
            String p = args[0].toLowerCase(Locale.ROOT);
            for (String s : nomes()) {
                if (s.startsWith(p)) {
                    l.add(s);
                }
            }
            return l;
        }
        return Collections.emptyList();
    }

    private static List<String> nomes() {
        List<String> l = new ArrayList<String>();
        l.add("status");
        l.add("hash");
        synchronized (SUBCOMANDOS) {
            l.addAll(SUBCOMANDOS.keySet());
        }
        return l;
    }

    private static void ajuda(CommandSender quem) {
        quem.sendMessage("Hoshiestar " + Hoshiestar.VERSAO + " - uso:");
        quem.sendMessage("  hoshie status - versao e estado de cada recurso");
        quem.sendMessage("  hoshie hash <classe> - SHA-256 dos bytes originais da classe (trava de versao)");
        synchronized (SUBCOMANDOS) {
            for (Subcomando s : SUBCOMANDOS.values()) {
                quem.sendMessage("  hoshie " + s.ajuda());
            }
        }
    }

    /** As linhas do "hoshie status" (tambem usadas no log do fim do boot). */
    public static List<String> linhasDeStatus() {
        List<String> l = new ArrayList<String>();
        l.add("Hoshiestar " + Hoshiestar.descricao());
        l.add("config: " + HoshiestarConfig.atual().arquivo().getPath()
                + (HoshiestarConfig.atual().avisos().isEmpty() ? "" : " (" + HoshiestarConfig.atual().avisos().size() + " aviso(s) na leitura; veja o log do boot)"));
        Thread t = ThreadServidor.thread();
        l.add("relogio: " + Relogio.ticks + " ticks; thread do servidor: " + (t == null ? "ainda nao definida" : t.getName()));
        for (Recursos.Recurso r : Recursos.todos()) {
            String e = r.linhaDeEstado();
            l.add(r.id + ": " + r.situacao() + " - " + r.descricao + (r.ligado && e != null ? " | " + e : ""));
            for (Correcao c : Correcoes.global().doRecurso(r.id)) {
                l.add("  - " + c.linha());
            }
        }
        return l;
    }

    private static void status(CommandSender quem) {
        for (String s : linhasDeStatus()) {
            quem.sendMessage(s);
        }
    }

    private static void hash(CommandSender quem, String[] args) {
        if (args.length != 1) {
            quem.sendMessage("uso: hoshie hash <classe>   (ex.: hoshie hash openblocks.common.ServerTickHandler)");
            return;
        }
        HashClasse.Resultado r = HashClasse.doArquivo(args[0]);
        // jogador ve so o nome do jar, sem o caminho das pastas do servidor
        quem.sendMessage(r.descrever(ehConsole(quem)));
        for (Correcao c : Correcoes.global().todas()) {
            if (c.classe().equals(r.classe)) {
                quem.sendMessage("  trava de '" + c.descricao + "' (" + c.recurso + "): "
                        + (r.sha256 != null && c.trava.aceita(r.sha256) ? "confere" : "NAO confere") + "; aceitos " + c.trava.aceitos());
            }
        }
    }
}
