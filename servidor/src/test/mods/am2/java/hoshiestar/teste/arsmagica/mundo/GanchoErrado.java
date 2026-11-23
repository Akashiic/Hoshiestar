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
package hoshiestar.teste.arsmagica.mundo;

import am2.AMEventHandler;
import am2.api.spell.component.interfaces.ISkillTreeEntry;
import am2.spell.SkillManager;
import am2.spell.SkillTreeManager;
import net.minecraft.world.WorldServer;

/**
 * Ganchos ERRADOS de proposito, so para provar que o harness enxerga um erro: cada {@link #modo} e um
 * jeito de errar a decisao. O teste aplica a transformacao com estes ganchos no lugar dos de verdade e
 * exige que o hash do estado saia DIFERENTE do original (se o harness nao pegasse, ele nao provaria nada).
 */
public final class GanchoErrado {

    /** 1 = F1 pula sempre; 2 = F1 pula quando o mapa NAO esta vazio; 3 = F2 (B) pula sempre; 4 = F2 (A) pula sempre. */
    public static int modo;

    private static final WorldServer[] SEM_MUNDOS = new WorldServer[0];

    private GanchoErrado() {
    }

    public static boolean varrerMundos() {
        if (modo == 1) {
            return false;
        }
        if (modo == 2) {
            return AMEventHandler.acceleratedEntitiesUUIDs.isEmpty();
        }
        return true;
    }

    public static WorldServer[] semMundos() {
        return SEM_MUNDOS;
    }

    public static boolean consultarSkill(SkillManager self, String nome) {
        return modo != 4;
    }

    public static ISkillTreeEntry semSkill(SkillManager self, String nome) {
        return null;
    }

    public static boolean consultarDesativada(SkillTreeManager self, ISkillTreeEntry e) {
        return modo != 3;
    }

    public static boolean semDesativada(SkillTreeManager self, ISkillTreeEntry e) {
        return true;
    }
}
