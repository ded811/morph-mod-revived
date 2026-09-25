package com.deds.morph.fabric;

import com.deds.api.Deds;
import com.deds.morph.Morph;

import net.fabricmc.api.ModInitializer;

/**
 * The entire Fabric-specific (server/common) surface of this mod. Keep it
 * this small in every mod — anything more belongs in Ded's API.
 */
public final class MorphFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        Deds.init(Morph.MOD_ID, new Morph());
    }
}
