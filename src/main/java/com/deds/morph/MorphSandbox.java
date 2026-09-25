package com.deds.morph;

/**
 * A same-thread guard set only around a morph {@code mobInteract} call (spec §4).
 * While {@link #active()}, {@code ServerLevelAddFreshEntityMixin} suppresses any
 * non-{@code ItemEntity}/{@code ExperienceOrb} spawn, so a mob's interaction can
 * still DROP items/XP but cannot transform (mooshroom→cow), split, spawn babies,
 * or otherwise leak a stray entity into the world from the transient dummy.
 *
 * <p>Server-thread-scoped {@link ThreadLocal} — nothing off the interaction call
 * path is affected. Shared code (no {@code net.fabricmc}).</p>
 */
public final class MorphSandbox {

    private static final ThreadLocal<Boolean> ACTIVE =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    private MorphSandbox() {
    }

    /** Enters the sandbox for the current thread (paired with {@link #end}). */
    public static void begin() {
        ACTIVE.set(Boolean.TRUE);
    }

    /** Leaves the sandbox for the current thread. */
    public static void end() {
        ACTIVE.set(Boolean.FALSE);
    }

    /** True while a sandboxed morph interaction is running on this thread. */
    public static boolean active() {
        return ACTIVE.get();
    }
}
