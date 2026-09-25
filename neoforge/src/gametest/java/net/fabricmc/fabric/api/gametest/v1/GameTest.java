package net.fabricmc.fabric.api.gametest.v1;

import net.minecraft.world.level.block.Rotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A stand-in, written for this project, for Fabric API's gametest annotation
 * of the same name, so that the shared server gametests (common/src/gametest)
 * compile and run unchanged in the NeoForge test mod. Fabric API is not on the
 * NeoForge classpath, so nothing clashes; this class exists only in the
 * NeoForge test mod and is never shipped.
 *
 * <p>Same attribute names, same defaults as the Fabric API build for this
 * Minecraft version (fabric-gametest-api-v1 4.0.21, Minecraft 26.2), because
 * the NeoForge registrar ({@code MorphNeoForgeGameTests}) turns them into the
 * same test data Fabric's annotation locator does: an 8x8x8 empty structure,
 * 20 ticks, padding 1, and so on. The 26.3 twin
 * ({@code versions/mc26.3/neoforge/src/gametest/java/...}) adds
 * {@code dimension}, as fabric-gametest-api-v1 4.0.32 does.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface GameTest {

    /** The test environment's registry id. */
    String environment() default "minecraft:default";

    /** The structure the test runs in; Fabric's 8x8x8 empty one by default. */
    String structure() default "fabric-gametest-api-v1:empty";

    /** Ticks before the test times out. */
    int maxTicks() default 20;

    /** Ticks to wait after placing the structure before the test starts. */
    int setupTicks() default 0;

    /** Whether a failure fails the whole run. */
    boolean required() default true;

    /** The structure's rotation. */
    Rotation rotation() default Rotation.NONE;

    /** Whether the test only runs when selected by name. */
    boolean manualOnly() default false;

    /** How many times the test may be attempted. */
    int maxAttempts() default 1;

    /** How many attempts must succeed. */
    int requiredSuccesses() default 1;

    /** Whether the structure is left open to the sky (else a barrier ceiling). */
    boolean skyAccess() default false;

    /** Blocks of padding around the structure. */
    int padding() default 1;
}
