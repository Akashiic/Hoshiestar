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
package hoshiestar.arsmagica.atalho;

import am2.AMEventHandler;
import am2.api.spell.component.interfaces.ISkillTreeEntry;
import am2.spell.SkillManager;
import am2.spell.SkillTreeManager;
import hoshiestar.arsmagica.Atalhos;
import net.minecraft.world.WorldServer;

/**
 * Ganchos chamados DE DENTRO de {@code am2.AMEventHandler} depois de transformado (pontes
 * {@code hoshiestar$envolve$N} criadas pela base). Cada ponte e:
 *
 * <pre>
 * // F1, dentro de HandlePlayerSpellExtendedProperties (era: worlds = DimensionManager.getWorlds())
 * static WorldServer[] ponte0() {
 *     if (GanchoAM2.varrerMundos()) return DimensionManager.getWorlds();    // a chamada original, igual
 *     return GanchoAM2.semMundos();                                          // lista vazia: o laco faz zero voltas
 * }
 * // F2, dentro de onEntityLiving (era: !SkillTreeManager.instance.isSkillDisabled(SkillManager.instance.getSkill("DiluteTime")) &amp;&amp; ...)
 * static ISkillTreeEntry ponte1(SkillManager self, String nome) {
 *     if (GanchoAM2.consultarSkill(self, nome)) return self.getSkill(nome);
 *     return GanchoAM2.semSkill(self, nome);                                 // null
 * }
 * static boolean ponte2(SkillTreeManager self, ISkillTreeEntry e) {
 *     if (GanchoAM2.consultarDesativada(self, e)) return self.isSkillDisabled(e);
 *     return GanchoAM2.semDesativada(self, e);                               // true: o AM2 segue o desvio de "desativada"
 * }
 * </pre>
 *
 * Aqui so se le o mapa do AM2 (campo estatico publico, lido ao vivo em cada chamada) e se delega para
 * {@link Atalhos}, onde esta a decisao e onde o teste isolado a exercita. A chamada ORIGINAL fica na
 * ponte, fora de try/catch: as excecoes dela saem como sairiam sem nos. O nosso codigo fica dentro de
 * try/catch: qualquer excecao nossa vira "chame o original" e desliga o atalho (ver Atalhos.erro).
 * Os metodos "sem..." nao leem nada: so devolvem o valor do atalho (o mesmo array vazio compartilhado;
 * o AM2 so le o tamanho dele).
 */
public final class GanchoAM2 {

    private static final WorldServer[] SEM_MUNDOS = new WorldServer[0];

    static {
        try {
            Atalhos.mapas = new Atalhos.Mapas() {
                @Override
                public int aceleradas() {
                    return AMEventHandler.acceleratedEntitiesUUIDs.size();
                }

                @Override
                public int retardadas() {
                    return AMEventHandler.slowedEntitiesUUIDs.size();
                }
            };
        } catch (Throwable ignorado) {
            // so afeta o texto dos tamanhos no status
        }
    }

    private GanchoAM2() {
    }

    // ------------------------------------------------------------------ F1

    /** true = chame DimensionManager.getWorlds() (o original); false = pule a varredura. */
    public static boolean varrerMundos() {
        try {
            return Atalhos.varrerMundos(AMEventHandler.acceleratedEntitiesUUIDs);
        } catch (Throwable t) {
            Atalhos.erro(true, "gancho varrerMundos", t);
            return true;
        }
    }

    /** O valor no lugar de getWorlds() quando se pula: nenhum mundo. */
    public static WorldServer[] semMundos() {
        return SEM_MUNDOS;
    }

    // ------------------------------------------------------------------ F2

    /** true = chame SkillManager.getSkill(nome); false = pule (devolve null). */
    public static boolean consultarSkill(SkillManager self, String nome) {
        try {
            return Atalhos.consultarSkill(AMEventHandler.slowedEntitiesUUIDs);
        } catch (Throwable t) {
            Atalhos.erro(false, "gancho consultarSkill", t);
            return true;
        }
    }

    /** O valor no lugar de getSkill(nome) quando se pula. */
    public static ISkillTreeEntry semSkill(SkillManager self, String nome) {
        return null;
    }

    /** true = chame SkillTreeManager.isSkillDisabled(e); false = pule (devolve true). */
    public static boolean consultarDesativada(SkillTreeManager self, ISkillTreeEntry e) {
        try {
            return Atalhos.consultarDesativada(AMEventHandler.slowedEntitiesUUIDs);
        } catch (Throwable t) {
            Atalhos.erro(false, "gancho consultarDesativada", t);
            return true;
        }
    }

    /** O valor no lugar de isSkillDisabled(e) quando se pula: "desativada" (o AM2 segue o desvio sem olhar o mapa vazio). */
    public static boolean semDesativada(SkillTreeManager self, ISkillTreeEntry e) {
        return true;
    }
}
