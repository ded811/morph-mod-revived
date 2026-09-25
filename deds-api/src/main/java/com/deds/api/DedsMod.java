package com.deds.api;

/**
 * A mod built on Ded's API. Implementations live in loader-agnostic code and interact
 * with the game exclusively through the {@link ModContext} and the rest of
 * Ded's API (plus the narrow vanilla surface allowed by
 * docs/ARCHITECTURE.md).
 *
 * <p>Each loader gets a ~3-line entrypoint class that constructs the mod and
 * hands it to {@link Deds#init}. Nothing else in the mod may reference
 * loader classes.</p>
 */
public interface DedsMod {

    /** Register content and subscribe to events. Called once at load. */
    void onInitialize(ModContext ctx);
}
