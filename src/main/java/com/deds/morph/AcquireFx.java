package com.deds.morph;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * S2C payload for the acquisition suck-in effect ({@code deds_morph:
 * acquire_fx}). The original (Morph 0.7.1) sent victim + killer ENTITY IDS
 * in a Packet131MapData to every player in the dimension and the client
 * spawned a 40-tick {@code EntityMorphAcquisition} that replaced the
 * victim. We send the victim's type/position/body-yaw by value instead —
 * the server discards the victim in the same tick (original
 * {@code setDead()}), so an entity-id lookup on the client would race the
 * removal packet.
 *
 * @param victimVariant the victim's morph variant — {@code EntityType} id +
 *                      normalized variant NBT — so the suck-in effect scatters
 *                      the correct variant model (the right sheep colour, slime
 *                      size, …), not just the default form
 * @param victimPos     the victim's position at death (effect start point)
 * @param victimBodyYaw the victim's body yaw at death (the original froze
 *                      {@code renderYawOffset} into the effect)
 * @param killer        the acquiring player; the effect homes to this
 *                      player's live position over its first 20 ticks
 */
public record AcquireFx(MorphVariant victimVariant, Vec3 victimPos,
        float victimBodyYaw, UUID killer) {

    public static final StreamCodec<RegistryFriendlyByteBuf, AcquireFx>
            STREAM_CODEC = StreamCodec.composite(
                    MorphVariant.STREAM_CODEC, AcquireFx::victimVariant,
                    Vec3.STREAM_CODEC, AcquireFx::victimPos,
                    ByteBufCodecs.FLOAT, AcquireFx::victimBodyYaw,
                    UUIDUtil.STREAM_CODEC, AcquireFx::killer,
                    AcquireFx::new);
}
