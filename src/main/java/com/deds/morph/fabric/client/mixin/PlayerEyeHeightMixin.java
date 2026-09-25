package com.deds.morph.fabric.client.mixin;

import com.deds.morph.fabric.client.MorphDummies;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Client-only camera eye-height glide (spec Part B §B.2 step 4). The collision
 * box SNAPS at transition end (server + client both call
 * {@code refreshDimensions()} then); only the first-person camera glides. During
 * the ~80-tick transition this overrides the local player's {@code getEyeHeight()}
 * (final on {@code Entity}, still bytecode-targetable) with an {@code easeInQuad}
 * interpolation between the previous and next morph eye heights — matching the
 * original's {@code pow(prog,2)} camera slide. Purely cosmetic; playtest-verified,
 * not gametested.
 */
@Mixin(Entity.class)
public abstract class PlayerEyeHeightMixin {

    @Inject(method = "getEyeHeight()F", at = @At("HEAD"), cancellable = true)
    private void deds_morph$eyeGlide(CallbackInfoReturnable<Float> cir) {
        if (!((Object) this instanceof LocalPlayer player)) {
            return;
        }
        float glide = MorphDummies.eyeHeightOverride(player);
        if (!Float.isNaN(glide)) {
            cir.setReturnValue(glide);
        }
    }

    /**
     * Crouch camera drop for a morphed player. A HUMANOID morph shrinks its
     * collision box when crouching (the getDimensions mixin), and that shrunk
     * box's eye already rides into the eye-height field via refreshDimensions —
     * so we leave it alone (avoid a double dip). A NON-humanoid morph keeps its
     * standing box, so we apply the small fixed sneak dip here. Runs at RETURN so
     * it only fires in the steady state (the glide above short-circuits during a
     * transition).
     *
     * <p>The dip predicate + amount live in ONE place —
     * {@link MorphDummies#crouchCameraDip} — shared with the per-tick box
     * reconcile, which must subtract the same dip from its eye expectation or the
     * two can never converge while sneaking in a non-humanoid morph (wave-5 review
     * finding 4/5: the reconcile compared the dipped accessor against the raw
     * dimensions eye, a constant 0.1 apart, and refreshed every tick forever).</p>
     */
    @Inject(method = "getEyeHeight()F", at = @At("RETURN"), cancellable = true)
    private void deds_morph$crouchDip(CallbackInfoReturnable<Float> cir) {
        if (!((Object) this instanceof LocalPlayer player)) {
            return;
        }
        float dip = MorphDummies.crouchCameraDip(player);
        if (dip > 0.0f) {
            cir.setReturnValue(cir.getReturnValue() - dip);
        }
    }
}
