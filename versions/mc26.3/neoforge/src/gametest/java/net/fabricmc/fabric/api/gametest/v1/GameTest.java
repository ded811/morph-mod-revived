package net.fabricmc.fabric.api.gametest.v1;

import net.minecraft.world.level.block.Rotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A stand-in, written for this project, for Fabric API's gametest annotation
 * of the same name: the Minecraft 26.3 twin of the canonical stand-in (see
 * that one for why it exists). Same attribute names and defaults as
 * fabric-gametest-api-v1 4.0.32, the Fabric API build for 26.3, which added
 * {@link #dimension()}: 26.3's test data runs each test in a dimension and
 * batches tests by environment AND dimension.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface GameTest {

    /** The test environment's registry id. */
    String environment() default "minecraft:default";

    /** The dimension the test runs in. */
    String dimension() default "minecraft:overworld";

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
