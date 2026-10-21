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

    public Set<String> mixins = new HashSet<String>();
    @Override
    public void onLoad(String mixinPackage) {
        Youer.LOGGER.info("YouerMixinPlugin loaded");
        mixins.add("dev.architectury.mixin.neoforge.neoforge.MixinChunkMap"); // Architectury 21+ (26.2)
        mixins.add("dev.architectury.mixin.forge.neoforge.MixinChunkMap"); // Architectury 20.x (26.1.2)
        // ModernFix lowers worker thread priority through the vanilla 4-arg ForkJoinPool constructor in Util#makeExecutor;
        // Paper replaces that call (and already deprioritizes worker threads), so the injection has no target.
        mixins.add("org.embeddedt.modernfix.common.mixin.perf.thread_priorities.UtilMixin");
        // FerriteCore "fastmap" replaces StateHolder's property table; Moonrise (Paper) already does the same optimisation
        // (optimise blockstate property access) and nulls propertyValues, so both mixins of that module must be skipped.
        mixins.add("malte0811.ferritecore.mixin.fastmap.FastMapStateHolderMixin");
        mixins.add("malte0811.ferritecore.mixin.fastmap.StateDefinitionMixin");
        Mixins.addBlacklist(mixins);
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
