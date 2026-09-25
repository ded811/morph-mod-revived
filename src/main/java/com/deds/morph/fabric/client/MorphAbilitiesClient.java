package com.deds.morph.fabric.client;

import com.deds.morph.MorphAbilities;
import com.deds.morph.MorphAbility;
import com.deds.morph.SwimParams;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Client-side per-tick applicator for the motion-based passive abilities
 * ({@code float}, {@code climb}). Player movement is CLIENT-authoritative — a
 * server {@code setDeltaMovement} on a {@code ServerPlayer} never reaches the
 * client, which is why the server-only clamp produced the wrong/inconsistent
 * chicken fall speed. Each client clamps ITS OWN local player, so the effect is
 * exact and remote players still fall/climb correctly (their own client clamps
 * them, replicated to everyone via movement packets).
 *
 * <p>Registered on {@code END_CLIENT_TICK} (after the local player has moved this
 * tick): clamping {@code deltaMovement.y} to the terminal velocity there sets the
 * start-of-next-tick velocity — reproducing exactly a vanilla chicken's per-tick
 * slow-fall (the mob damps its post-gravity {@code deltaMovement.y} at the end of
 * its own tick to the same steady value). Playtest-only (not gametested — client
 * movement is out of a headless server's reach).</p>
 */
@Environment(EnvType.CLIENT)
public final class MorphAbilitiesClient {

    private MorphAbilitiesClient() {
    }

    public static void clientTick(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return;
        }
        EnumSet<MorphAbility> active = MorphAbilities.activeAbilities(player);
        if (active.isEmpty()) {
            return;
        }

        // fly: WATER SLOWDOWN (wave 10, the other half of the ability-fly row;
        // O:morph/common/ability/AbilityFly.java:80-103). The original ran this
        // one INSIDE `if(player.worldObj.isRemote)` — it is the one piece of
        // AbilityFly that was always client-only, for the same reason float and
        // climb are here (Playbook §1). Gates, all the original's:
        //   - actually flying and not creative (:58)
        //   - in water and this morph is slowed by water (:80)
        //   - the morph does NOT also have `swim` (:86-95) — a flying swimmer
        //     is at home in water and keeps its speed
        // Constants verbatim: x/z ×0.65, y ×0.2 (:97-100).
        if (active.contains(MorphAbility.FLY)
                && player.getAbilities().flying
                && !player.getAbilities().instabuild
                && player.isInWater()
                && MorphAbilities.flySlowdownInWater(active)
                && !active.contains(MorphAbility.SWIM)) {
            Vec3 m = player.getDeltaMovement();
            player.setDeltaMovement(m.x * 0.65, m.y * 0.2, m.z * 0.65);
        }

        if (player.getAbilities().flying || player.isFallFlying()) {
            return; // creative/morph flight and elytra gliding override the
                    // fall/climb clamps (a chicken morph could not dive)
        }

        // float: clamp the fall to the morph's exact terminal velocity.
        if (active.contains(MorphAbility.FLOAT)) {
            Vec3 m = player.getDeltaMovement();
            if (m.y < MorphAbilities.FLOAT_TERMINAL) {
                player.setDeltaMovement(m.x, MorphAbilities.FLOAT_TERMINAL, m.z);
                player.resetFallDistance();
            }
        }

        // climb: while pressing into a wall, ascend (0 while sneaking) like a
        // spider — horizontalCollision is real on the client's own player.
        if (active.contains(MorphAbility.CLIMB) && player.horizontalCollision) {
            Vec3 m = player.getDeltaMovement();
            player.setDeltaMovement(m.x,
                    player.isShiftKeyDown() ? 0.0 : MorphAbilities.CLIMB_SPEED, m.z);
            player.resetFallDistance();
        }

        // swim parameters (wave-9 item 2): neutral buoyancy in water and the
        // out-of-water slowdown, both client-side for the same reason float and
        // climb are (Playbook §1 — a server setDeltaMovement on a player is
        // discarded).
        if (active.contains(MorphAbility.SWIM)) {
            swimTick(player, MorphAbilities.swimParams(player));
        }

        // strider lava-walk (best-effort, playtest-only): a strider morph does not
        // sink into lava — stop downward motion while in lava so it rides the
        // surface. True fluid-surface collision is server physics (flagged);
        // no-burn (FIRE_IMMUNITY) + land-slow are the server-authoritative parts.
        if (MorphAbilities.isStrider(player) && player.isInLava()) {
            Vec3 m = player.getDeltaMovement();
            if (m.y < 0.0) {
                player.setDeltaMovement(m.x, 0.0, m.z);
            }
        }
    }

    /**
     * The two parameterised {@code swim} behaviours our boolean-only model was
     * missing (wave-9 item 2), ported from
     * {@code O:morph/common/ability/AbilitySwim.java:109-153}:
     *
     * <ul>
     *   <li><b>Neutral buoyancy</b> ({@code canMaintainDepth}, {@code :109-130}):
     *       not sneaking, not jumping, eyes inside water ⇒ {@code motionY = 0},
     *       so a squid morph HOLDS depth instead of bobbing. While jumping,
     *       {@code motionY *= swimSpeed} instead.</li>
     *   <li><b>Land slowdown</b> ({@code :143-153}): only for a morph that cannot
     *       survive out of water, only once {@code air < 285} (~15 ticks after
     *       leaving the water), skipped for a flying creative player;
     *       {@code motionX/Z *= landSpeed} under the original's SOFT clamp.</li>
     * </ul>
     *
     * <p><b>Deliberately NOT ported: the in-water HORIZONTAL {@code swimSpeed}
     * multiply</b> ({@code :98-108}). Our swim boost is already
     * {@code LivingEntityWaterDragMixin} ORing SWIM into vanilla's
     * dolphin's-grace water-drag check (wave 6 item A, gametested by
     * {@code swimMorphDragWithoutEffect}) — 0.96 instead of 0.8 per tick, a ~5×
     * top-speed increase. Stacking the original's per-tick accelerator on top
     * would ship BOTH boosts, which the wave-9 brief explicitly forbids. The
     * parameter is still carried and still clamped to 1.22, and it IS used
     * vertically, exactly where the original uses it (the jump branch above).
     * SPEC deviation D9-4.</p>
     */
    private static void swimTick(LocalPlayer player, SwimParams params) {
        boolean jumping = player.input != null
                && player.input.keyPresses != null
                && player.input.keyPresses.jump();
        if (player.isInWater()) {
            if (!params.canMaintainDepth()) {
                return;
            }
            Vec3 m = player.getDeltaMovement();
            if (!player.isShiftKeyDown() && !jumping
                    && player.isEyeInFluid(FluidTags.WATER)) {
                player.setDeltaMovement(m.x, 0.0, m.z);
            } else if (jumping) {
                player.setDeltaMovement(m.x, m.y * params.swimSpeed(), m.z);
            }
            return;
        }
        if (!params.slowsOnLand(player.getAirSupply())
                || player.getAbilities().flying) {
            return;
        }
        Vec3 m = player.getDeltaMovement();
        player.setDeltaMovement(
                SwimParams.softScale(m.x, params.landSpeed()), m.y,
                SwimParams.softScale(m.z, params.landSpeed()));
    }
}
