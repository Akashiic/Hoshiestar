package org.bukkit.craftbukkit.util;

import com.mohistmc.youer.neoforge.NeoForgeInjectBukkit;
import net.minecraft.world.entity.MobCategory;
import org.bukkit.entity.SpawnCategory;

// Youer - no enum switches here: mods extend MobCategory (NeoForge enum extension) and Youer adds the matching
// SpawnCategory constants at runtime, so javac's $SwitchMap arrays (sized when first used) can be too short and
// throw ArrayIndexOutOfBoundsException. Modded categories have no bukkit.yml limits.
public class CraftSpawnCategory {

    private static boolean isModded(SpawnCategory spawnCategory) {
        return NeoForgeInjectBukkit.CategoryspawnMap.containsKey(spawnCategory);
    }

    public static boolean isValidForLimits(SpawnCategory spawnCategory) {
        return spawnCategory != null && spawnCategory != SpawnCategory.MISC && !isModded(spawnCategory);
    }

    public static String getConfigNameSpawnLimit(SpawnCategory spawnCategory) {
        if (spawnCategory == SpawnCategory.MONSTER) return "spawn-limits.monsters";
        if (spawnCategory == SpawnCategory.ANIMAL) return "spawn-limits.animals";
        if (spawnCategory == SpawnCategory.WATER_ANIMAL) return "spawn-limits.water-animals";
        if (spawnCategory == SpawnCategory.WATER_AMBIENT) return "spawn-limits.water-ambient";
        if (spawnCategory == SpawnCategory.WATER_UNDERGROUND_CREATURE) return "spawn-limits.water-underground-creature";
        if (spawnCategory == SpawnCategory.AMBIENT) return "spawn-limits.ambient";
        if (spawnCategory == SpawnCategory.AXOLOTL) return "spawn-limits.axolotls";
        throw new UnsupportedOperationException("Unknown Config value " + spawnCategory + " for spawn-limits");
    }

    public static String getConfigNameTicksPerSpawn(SpawnCategory spawnCategory) {
        if (spawnCategory == SpawnCategory.MONSTER) return "ticks-per.monster-spawns";
        if (spawnCategory == SpawnCategory.ANIMAL) return "ticks-per.animal-spawns";
        if (spawnCategory == SpawnCategory.WATER_ANIMAL) return "ticks-per.water-spawns";
        if (spawnCategory == SpawnCategory.WATER_AMBIENT) return "ticks-per.water-ambient-spawns";
        if (spawnCategory == SpawnCategory.WATER_UNDERGROUND_CREATURE) return "ticks-per.water-underground-creature-spawns";
        if (spawnCategory == SpawnCategory.AMBIENT) return "ticks-per.ambient-spawns";
        if (spawnCategory == SpawnCategory.AXOLOTL) return "ticks-per.axolotl-spawns";
        throw new UnsupportedOperationException("Unknown Config value " + spawnCategory + " for ticks-per");
    }

    public static long getDefaultTicksPerSpawn(SpawnCategory spawnCategory) {
        if (spawnCategory == SpawnCategory.ANIMAL) return 400; // This value come from commit 2995a08324f
        if (spawnCategory == SpawnCategory.MONSTER || spawnCategory == SpawnCategory.AXOLOTL || spawnCategory == SpawnCategory.AMBIENT
            || spawnCategory == SpawnCategory.WATER_UNDERGROUND_CREATURE || spawnCategory == SpawnCategory.WATER_AMBIENT || spawnCategory == SpawnCategory.WATER_ANIMAL) {
            return 1;
        }
        throw new UnsupportedOperationException("Unknown Config value " + spawnCategory + " for ticks-per");
    }

    public static SpawnCategory toBukkit(MobCategory enumCreatureType) {
        if (enumCreatureType == MobCategory.MONSTER) return SpawnCategory.MONSTER;
        if (enumCreatureType == MobCategory.CREATURE) return SpawnCategory.ANIMAL;
        if (enumCreatureType == MobCategory.AMBIENT) return SpawnCategory.AMBIENT;
        if (enumCreatureType == MobCategory.AXOLOTLS) return SpawnCategory.AXOLOTL;
        if (enumCreatureType == MobCategory.WATER_CREATURE) return SpawnCategory.WATER_ANIMAL;
        if (enumCreatureType == MobCategory.WATER_AMBIENT) return SpawnCategory.WATER_AMBIENT;
        if (enumCreatureType == MobCategory.UNDERGROUND_WATER_CREATURE) return SpawnCategory.WATER_UNDERGROUND_CREATURE;
        if (enumCreatureType == MobCategory.MISC) return SpawnCategory.MISC;
        SpawnCategory modded = NeoForgeInjectBukkit.spawnCategoryMap.get(enumCreatureType); // Youer
        if (modded != null) return modded;
        throw new UnsupportedOperationException("Unknown EnumCreatureType " + enumCreatureType + " for SpawnCategory");
    }

    public static MobCategory toNMS(SpawnCategory spawnCategory) {
        if (spawnCategory == SpawnCategory.MONSTER) return MobCategory.MONSTER;
        if (spawnCategory == SpawnCategory.ANIMAL) return MobCategory.CREATURE;
        if (spawnCategory == SpawnCategory.AMBIENT) return MobCategory.AMBIENT;
        if (spawnCategory == SpawnCategory.AXOLOTL) return MobCategory.AXOLOTLS;
        if (spawnCategory == SpawnCategory.WATER_ANIMAL) return MobCategory.WATER_CREATURE;
        if (spawnCategory == SpawnCategory.WATER_AMBIENT) return MobCategory.WATER_AMBIENT;
        if (spawnCategory == SpawnCategory.WATER_UNDERGROUND_CREATURE) return MobCategory.UNDERGROUND_WATER_CREATURE;
        if (spawnCategory == SpawnCategory.MISC) return MobCategory.MISC;
        MobCategory modded = NeoForgeInjectBukkit.CategoryspawnMap.get(spawnCategory); // Youer
        if (modded != null) return modded;
        throw new UnsupportedOperationException("Unknown SpawnCategory " + spawnCategory + " for EnumCreatureType");
    }

}
