package com.deds.morph.neoforge;

import com.deds.api.Deds;
import com.deds.morph.Morph;
import com.deds.morph.MorphLoader;

import net.neoforged.fml.common.Mod;

/**
 * The entire NeoForge-specific (server/common) surface of this mod, the twin
 * of {@code MorphFabric}: one call into Ded's API, which bridges everything
 * Morph needs from NeoForge. The one addition is {@link MorphLoader}: the two
 * spots where NeoForge rewrote vanilla code Morph relies on (villager fear
 * distances, shearing), installed before the mod initializes so no Morph code
 * can ever see the vanilla answer on NeoForge.
 *
 * <p>Constructed after Ded's API: neoforge.mods.toml declares
 * {@code ordering = "AFTER"} on deds_api, because FML constructs unordered
 * mods in parallel.</p>
 */
@Mod(Morph.MOD_ID)
public final class MorphNeoForge {

    public MorphNeoForge() {
        MorphLoader.install(new NeoForgeMorphLoader());
        Deds.init(Morph.MOD_ID, new Morph());
    }
}
