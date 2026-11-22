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
package hoshiestar;

import cpw.mods.fml.common.event.FMLServerStartingEvent;
import hoshiestar.api.Log;
import hoshiestar.api.Recursos;
import hoshiestar.api.ThreadServidor;
import hoshiestar.comando.ComandoHoshie;
import hoshiestar.embutidos.Correcao;
import hoshiestar.embutidos.Correcoes;
import hoshiestar.api.HashClasse;
import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.Launch;

import java.util.List;

/**
 * Entradas do Hoshiestar com o servidor ja de pe (fase de mods em diante). Chamadas pelo
 * {@code io.github.crucible.CrucibleModContainer} (patch "// Hoshiestar"), que e carregado pelo
 * LaunchClassLoader como os mods: referencia direta, sem reflexao.
 */
public final class Servidor {

    private Servidor() {
    }

    /** FMLServerStartingEvent: registra o comando "hoshie" (como o Crucible registra o "crucible"). */
    public static void iniciando(FMLServerStartingEvent evt) {
        try {
            evt.registerServerCommand("hoshiestar", new ComandoHoshie());
        } catch (Throwable t) {
            Log.erro("nao consegui registrar o comando hoshie", t);
        }
    }

    /**
     * FMLServerStartedEvent (logo depois do "Done"): uma linha por recurso e por correcao embutida, e a
     * posicao do transformador na cadeia do LaunchClassLoader.
     */
    public static void pronto() {
        try {
            Log.info(Hoshiestar.descricao() + " pronto; thread do servidor: "
                    + (ThreadServidor.souAThreadDoServidor() ? "confere (" + Thread.currentThread().getName() + ")"
                            : "NAO confere (" + ThreadServidor.thread() + " x " + Thread.currentThread().getName() + ")"));
            for (Recursos.Recurso r : Recursos.todos()) {
                String e = r.ligado ? r.linhaDeEstado() : null;
                Log.info("recurso " + r.id + ": " + (r.ligado ? "ligado" : "desligado") + (e != null ? " | " + e : ""));
                for (Correcao c : Correcoes.global().doRecurso(r.id)) {
                    String linha = "  correcao: " + c.linha();
                    if (c.ligada && c.estado() == Correcao.Estado.AGUARDANDO) {
                        HashClasse.Resultado arq = HashClasse.doArquivo(c.classe());
                        linha += arq.sha256 == null ? " - mod ausente (classe nao existe no classpath)"
                                : " - classe ainda nao carregada; sera conferida quando carregar";
                    }
                    if (c.estado() == Correcao.Estado.APLICADA || c.estado() == Correcao.Estado.DESLIGADA
                            || c.estado() == Correcao.Estado.AGUARDANDO) {
                        Log.info(linha);
                    } else {
                        Log.aviso(linha);
                    }
                }
            }
            if (Inicio.transformadorRegistrado()) {
                Log.info(posicaoDoTransformador());
            }
        } catch (Throwable t) {
            Log.erro("erro ao montar o resumo do boot", t);
        }
    }

    /** "transformador na posicao X de N; Mixin na posicao Y". */
    static String posicaoDoTransformador() {
        try {
            List<IClassTransformer> l = Launch.classLoader.getTransformers();
            int nosso = -1;
            int mixin = -1;
            StringBuilder antes = new StringBuilder();
            for (int i = 0; i < l.size(); i++) {
                String n = l.get(i).getClass().getName();
                if (n.equals("hoshiestar.embutidos.TransformadorHoshiestar")) {
                    nosso = i;
                } else if (nosso < 0) {
                    antes.append(antes.length() > 0 ? ", " : "").append(n);
                }
                if (mixin < 0 && n.startsWith("org.spongepowered.asm.mixin")) {
                    mixin = i;
                }
            }
            return "cadeia de transformadores: o do Hoshiestar esta na posicao " + (nosso + 1) + " de " + l.size()
                    + (mixin >= 0 ? "; Mixin na posicao " + (mixin + 1) : "; Mixin nao encontrado na cadeia")
                    + "; antes dele: " + (antes.length() == 0 ? "nenhum" : antes);
        } catch (Throwable t) {
            return "cadeia de transformadores: nao consegui ler (" + t + ")";
        }
    }
}
