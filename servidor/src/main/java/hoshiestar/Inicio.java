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

import hoshiestar.api.Log;
import hoshiestar.api.Recursos;
import hoshiestar.config.HoshiestarConfig;
import hoshiestar.embutidos.Correcoes;
import net.minecraft.launchwrapper.LaunchClassLoader;

/**
 * Entrada do Hoshiestar na fase de coremod (muito cedo: antes de qualquer classe do Minecraft e de
 * qualquer coremod/Mixin de mod).
 *
 * <p>Chamada UMA vez por {@code io.github.crucible.bootstrap.CrucibleCoremodHook.coremodHandleLaunch}
 * (patch "// Hoshiestar"), por REFLEXAO e atraves do LaunchClassLoader. Motivo: o
 * CrucibleCoremodHook e carregado pelo class loader da aplicacao (o pacote
 * io.github.crucible.bootstrap e excluido do LaunchClassLoader); uma referencia direta faria esta
 * classe (e tudo do Hoshiestar que ela toca) ser carregada pelo class loader errado, duplicando o
 * estado (config, registro de correcoes) entre dois carregadores.
 *
 * <p><b>Regra:</b> esta classe e tudo que ela alcanca (config, Catalogo, registro de correcoes,
 * transformador) so podem usar JDK, log4j, ASM, launchwrapper, o pacote de config do Crucible e
 * classes nossas que obedecem a mesma regra. Nada do Minecraft, do Forge (fora do launchwrapper)
 * nem do Bukkit. O que roda depois que o servidor sobe fica em {@link Servidor}.
 */
public final class Inicio {

    private static final String TRANSFORMADOR = "hoshiestar.embutidos.TransformadorHoshiestar";

    private static volatile boolean transformadorRegistrado;

    private Inicio() {
    }

    /** Se o transformador das correcoes embutidas foi registrado (so quando ha alguma ligada). */
    public static boolean transformadorRegistrado() {
        return transformadorRegistrado;
    }

    /** Chamado pelo CrucibleCoremodHook. Nunca lanca excecao (o boot continua como no Crucible). */
    public static void coremod(LaunchClassLoader classLoader) {
        try {
            Log.info(Hoshiestar.descricao());
            HoshiestarConfig cfg = HoshiestarConfig.carregarPadrao();
            for (String a : cfg.avisos()) {
                Log.aviso("config: " + a);
            }
            try {
                Catalogo.registrar(cfg, Correcoes.global());
            } catch (Throwable t) {
                Log.erro("erro ao registrar os recursos (os que ja tinham entrado continuam)", t);
            }
            Correcoes.global().fechar();
            Log.info("recursos: " + resumoDosRecursos());
            if (Correcoes.global().classesComCorrecao() > 0) {
                classLoader.registerTransformer(TRANSFORMADOR);
                transformadorRegistrado = true;
                Log.info("transformador das correcoes embutidas registrado (" + Correcoes.global().classesComCorrecao()
                        + " classe(s) alvo)");
            } else {
                Log.info("nenhuma correcao embutida ligada: transformador NAO registrado (classes de mod intocadas)");
            }
        } catch (Throwable t) {
            Log.erro("erro na inicializacao do Hoshiestar; o servidor segue como Crucible puro", t);
        }
    }

    static String resumoDosRecursos() {
        StringBuilder sb = new StringBuilder();
        for (Recursos.Recurso r : Recursos.todos()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(r.id).append('=').append(r.ligado ? "ligado" : "desligado");
        }
        return sb.length() == 0 ? "nenhum" : sb.toString();
    }
}
