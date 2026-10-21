package com.mohistmc.youer.util;

import net.minecraft.world.entity.Entity;
import org.bukkit.event.entity.EntityRemoveEvent.Cause;
import org.jspecify.annotations.Nullable;

/**
 * Carries the Bukkit {@link Cause} into the vanilla {@code Entity#discard()}.
 * <p>
 * CraftBukkit rewrote vanilla call sites to {@code discard(cause)}, which removes the {@code discard()V}
 * invoke mod mixins target. Call sites use {@code RemoveCauseTag.tag(entity, cause).discard()} instead:
 * {@link #tag} keeps the static receiver type, so the invoke owner matches vanilla, and the cause is
 * bound to that entity instance (see {@link SpawnReasonTag}).
 */
public final class RemoveCauseTag {

    private record Tag(Entity entity, @Nullable Cause cause) {
    }

    private static final ThreadLocal<@Nullable Tag> TAG = new ThreadLocal<>();

    private RemoveCauseTag() {
    }

    public static <T extends Entity> T tag(T entity, @Nullable Cause cause) {
        TAG.set(new Tag(entity, cause));
        return entity;
    }

    public static @Nullable Cause take(Entity entity, @Nullable Cause fallback) {
        Tag tag = TAG.get();
        if (tag == null || tag.entity() != entity) {
            return fallback;
        }
        TAG.remove();
        return tag.cause();
    }
}
