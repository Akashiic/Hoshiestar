package com.mohistmc.youer.util;

import net.minecraft.world.entity.Entity;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.jspecify.annotations.Nullable;

/**
 * Carries the Bukkit {@link SpawnReason} into the vanilla {@code addFreshEntity(Entity)} family.
 * <p>
 * CraftBukkit rewrote vanilla call sites to {@code addFreshEntity(entity, reason)}, which removes the
 * {@code Level#addFreshEntity(Entity)} invoke that mod mixins target (Redirect/WrapOperation/INVOKE).
 * Call sites instead use {@code level.addFreshEntity(SpawnReasonTag.tag(entity, reason))}: the vanilla
 * invoke stays in the bytecode and the implementation picks the reason up with {@link #take}. The tag
 * is bound to the entity instance, so a call a mod redirected away can never hand its reason to an
 * unrelated spawn.
 */
public final class SpawnReasonTag {

    private record Tag(Entity entity, @Nullable SpawnReason reason) {
    }

    private static final ThreadLocal<@Nullable Tag> TAG = new ThreadLocal<>();

    private SpawnReasonTag() {
    }

    public static <T extends Entity> T tag(T entity, @Nullable SpawnReason reason) {
        TAG.set(new Tag(entity, reason));
        return entity;
    }

    /** The reason tagged for this entity instance, or {@code fallback} if the caller did not tag it. */
    public static @Nullable SpawnReason take(Entity entity, @Nullable SpawnReason fallback) {
        Tag tag = TAG.get();
        if (tag == null || tag.entity() != entity) {
            return fallback; // untagged call (mods / vanilla paths); a pending tag for another entity stays put
        }
        TAG.remove();
        return tag.reason();
    }
}
