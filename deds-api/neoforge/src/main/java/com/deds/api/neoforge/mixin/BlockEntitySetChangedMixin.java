package com.deds.api.neoforge.mixin;

import com.deds.api.neoforge.transfer.CapabilityRefresh;

import net.minecraft.world.level.block.entity.BlockEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets {@link CapabilityRefresh} notice when a block entity whose tanks or
 * energy Ded's API exposes changes which faces are connected, so NeoForge's
 * capability caches (which keep even a "nothing here" answer) are refreshed.
 * Cheap for every other block entity: one type-set lookup.
 */
@Mixin(BlockEntity.class)
public abstract class BlockEntitySetChangedMixin {

    @Inject(method = "setChanged()V", at = @At("TAIL"), require = 1, allow = 1)
    private void deds_api$refreshCapabilities(CallbackInfo ci) {
        CapabilityRefresh.onSetChanged((BlockEntity) (Object) this);
    }
}
