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

import hoshiestar.api.Recursos;
import hoshiestar.api.Trava;
import hoshiestar.config.HoshiestarConfig;
import hoshiestar.embutidos.Correcao;
import hoshiestar.embutidos.Correcoes;
import hoshiestar.embutidos.asm.Asm;
import hoshiestar.embutidos.asm.Gancho;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Recurso "autoteste": prova o caminho inteiro das correcoes embutidas no modpack real. Serve
 * tambem de EXEMPLO de como escrever uma correcao embutida (ver servidor/README.md).
 *
 * <ol>
 * <li>Correcao inofensiva com trava REAL: no inicio de
 * {@code openblocks.common.ServerTickHandler.onServerTick} (OpenBlocks-1.12.20-GTNH) entra uma
 * chamada a {@link GanchoAutoteste#inicioServerTick}, que conta as chamadas e devolve false (o
 * metodo original roda inteiro). Usa o formato 1 com retorno antecipado, entao tambem exercita o
 * quadro de pilha acrescentado, numa classe compilada para Java 8.</li>
 * <li>Correcao com trava ERRADA de proposito em {@code bq_standard.handlers.EventHandler}
 * (BetterQuesting, classe que tambem recebe mixins do Akashic-BQRelief): tem de ser recusada, e o
 * log mostra o hash visto e se os bytes que chegaram ao transformador sao os do arquivo do jar
 * (prova de que o transformador roda antes dos mixins).</li>
 * </ol>
 *
 * Desligado por padrao ({@code autoteste.ligado: false} no hoshiestar.yml).
 */
public final class Autoteste {

    public static final String ID = "autoteste";

    static final String GANCHO = "hoshiestar/embutidos/autoteste/GanchoAutoteste";

    /** SHA-256 de openblocks/common/ServerTickHandler.class em OpenBlocks-1.12.20-GTNH.jar. */
    static final String SHA_OPENBLOCKS = "c8114b87325faafedd369aa76cbbce9b6f97ff26d346edd872d2dd2ce5c1b1f3";

    /** Trava errada de proposito (nenhuma classe tem este hash). */
    static final String SHA_ERRADO = "0000000000000000000000000000000000000000000000000000000000000000";

    static final String CLASSE_BQ = "bq_standard.handlers.EventHandler";

    private static final String DESC_TICK = "(Lcpw/mods/fml/common/gameevent/TickEvent$ServerTickEvent;)V";

    private Autoteste() {
    }

    /** Chamado por hoshiestar.Catalogo na fase de coremod. */
    public static void registrar(HoshiestarConfig cfg, Correcoes correcoes) {
        boolean ligado = cfg.autoteste_ligado;
        Recursos.registrar(ID, "autoteste das correcoes embutidas", ligado).estado(new Recursos.Estado() {
            @Override
            public String linha() {
                return linhaDeEstado();
            }
        });
        correcoes.adicionar(Correcao.nova(ID, "contador no inicio de ServerTickHandler.onServerTick",
                Trava.de("openblocks.common.ServerTickHandler", SHA_OPENBLOCKS), ligado,
                (classe, ganchos) -> Asm.ganchoNoInicio(classe, "onServerTick", DESC_TICK,
                        Gancho.de(GANCHO, "inicioServerTick"), null, true, ganchos)));
        correcoes.adicionar(Correcao.nova(ID, "trava errada de proposito (tem de ser recusada)",
                Trava.de(CLASSE_BQ, SHA_ERRADO), ligado,
                (classe, ganchos) -> Asm.ganchoNoInicio(classe, "onServerTick", DESC_TICK,
                        Gancho.de(GANCHO, "nuncaChamado"), null, false, ganchos)));
    }

    /** Linha do "hoshie status" e do fim do boot (roda com o servidor ligado). */
    static String linhaDeEstado() {
        StringBuilder sb = new StringBuilder();
        sb.append("chamadas do gancho no OpenBlocks: ").append(GanchoAutoteste.chamadas());
        sb.append("; class loader do gancho = do mod: ").append(GanchoAutoteste.carregador());
        if (GanchoAutoteste.nuncaChamadas() > 0) {
            sb.append("; ERRO: o gancho da trava errada foi chamado ").append(GanchoAutoteste.nuncaChamadas()).append(" vez(es)");
        }
        sb.append("; metodos de mixin na classe do BQ: ").append(metodosDeMixin(CLASSE_BQ));
        return sb.toString();
    }

    /**
     * Quantos metodos com nome de mixin (handler$, redirect$, wrapOperation$, modify$...) a classe
     * carregada tem. Se a correcao com trava errada viu os bytes iguais aos do jar E a classe final
     * tem metodos de mixin, o transformador do Hoshiestar rodou antes do Mixin.
     */
    static String metodosDeMixin(String classe) {
        try {
            Class<?> c = Class.forName(classe, false, Autoteste.class.getClassLoader());
            int n = 0;
            for (Method m : c.getDeclaredMethods()) {
                String nome = m.getName();
                if (nome.indexOf('$') > 0 && (nome.startsWith("handler$") || nome.startsWith("redirect$")
                        || nome.startsWith("wrapOperation$") || nome.startsWith("modify") || nome.startsWith("localvar$"))) {
                    n++;
                }
            }
            return String.valueOf(n);
        } catch (Throwable t) {
            return "classe nao carregada (" + t.getClass().getSimpleName() + ")";
        }
    }

    /** As correcoes do autoteste (para o comando). */
    public static List<Correcao> correcoes() {
        return Correcoes.global().doRecurso(ID);
    }
}
