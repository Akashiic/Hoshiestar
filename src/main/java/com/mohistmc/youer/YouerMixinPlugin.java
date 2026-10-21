package com.mohistmc.youer;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * @author Mgazul
 * @date 2026/7/20 18:29
 */
public class YouerMixinPlugin implements IMixinConfigPlugin {

    /**
     * Mod mixins that cannot apply on top of Paper/Moonrise and are skipped. Each entry duplicates an optimisation
     * Paper already performs, or targets vanilla code that Paper's chunk/light/world-loading rewrite replaced.
     * Whole features are listed together because their mixins depend on each other.
     */
    private static final List<String> INCOMPATIBLE_MIXINS = List.of(
            // Architectury: ChunkMap hook re-implemented in ca.spottedleaf.moonrise.paper.PaperHooks
            "dev.architectury.mixin.neoforge.neoforge.MixinChunkMap", // Architectury 21+ (26.2)
            "dev.architectury.mixin.forge.neoforge.MixinChunkMap", // Architectury 20.x (26.1.2)
            // ModernFix thread_priorities: Paper replaces the vanilla ForkJoinPool constructor in Util#makeExecutor and
            // already deprioritizes worker threads
            "org.embeddedt.modernfix.common.mixin.perf.thread_priorities.UtilMixin",
            // ModernFix cache_strongholds: captures a ResourceKey constructor argument by type, but Paper's ServerLevel
            // constructor takes two (dimension, typeKey); only a startup-time cache is lost
            "org.embeddedt.modernfix.common.mixin.perf.cache_strongholds.ServerLevelMixin",
            "org.embeddedt.modernfix.common.mixin.perf.cache_strongholds.ChunkGeneratorMixin",
            "org.embeddedt.modernfix.common.mixin.perf.cache_strongholds.ConcentricRingsStructurePlacementMixin",
            // ModernFix release_protochunks / compact_imposterprotochunks: shadow vanilla ChunkMap/ChunkHolder/light
            // fields that Moonrise's chunk system and Starlight replace (Moonrise manages proto chunk lifetime itself)
            "org.embeddedt.modernfix.common.mixin.perf.release_protochunks.ChunkHolderMixin",
            "org.embeddedt.modernfix.common.mixin.perf.release_protochunks.ChunkMapMixin",
            "org.embeddedt.modernfix.common.mixin.perf.release_protochunks.ImposterProtoChunkMixin",
            "org.embeddedt.modernfix.common.mixin.perf.compact_imposterprotochunks.ChunkAccessMixin",
            "org.embeddedt.modernfix.common.mixin.perf.compact_imposterprotochunks.ImposterProtoChunkMixin",
            // FerriteCore fastmap: Moonrise already optimises blockstate property access and nulls propertyValues
            "malte0811.ferritecore.mixin.fastmap.FastMapStateHolderMixin",
            "malte0811.ferritecore.mixin.fastmap.StateDefinitionMixin",
            // FerriteCore threaddetec: shrinks PalettedContainer#threadingDetector, which Moonrise already removed
            "malte0811.ferritecore.mixin.threaddetec.PalettedContainerMixin"
    );

    @Override
    public void onLoad(String mixinPackage) {
        Youer.LOGGER.info("YouerMixinPlugin loaded");
        Mixins.addBlacklist(new HashSet<>(INCOMPATIBLE_MIXINS));
    }

    @Override
    public String getRefMapperConfig() {
        return "";
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // Youer start - skip compat.<modid> mixins whose mod is absent (upstream 1.21.1 480ea2a0c, #529).
        // Ask the mod discovery result rather than Class.forName: mod classes are not reachable from
        // here while mixin builds its configs, and a failed lookup must never drop a compat mixin.
        String modId = compatModId(mixinClassName);
        if (modId != null) {
            try {
                net.neoforged.fml.loading.LoadingModList loadingModList = net.neoforged.fml.loading.LoadingModList.get();
                if (loadingModList != null) {
                    return loadingModList.getModFileById(modId) != null;
                }
            } catch (Throwable ignored) {
            }
            if (modId.equals("spark")) {
                return hasClass("me.lucko.spark.neoforge.NeoForgeSparkMod");
            }
        }
        // Youer end
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {

    }

    @Override
    public List<String> getMixins() {
        return List.of();
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {

    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    private static final String COMPAT_PREFIX = "com.mohistmc.youer.mixins.compat.";

    static String compatModId(String mixinClassName) {
        if (!mixinClassName.startsWith(COMPAT_PREFIX)) {
            return null;
        }
        String modId = mixinClassName.substring(COMPAT_PREFIX.length());
        int separator = modId.indexOf('.');
        return separator < 0 ? null : modId.substring(0, separator);
    }

    public boolean hasClass(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
