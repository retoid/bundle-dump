package com.bundledump;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Client-only mod entry point. Shift + right-click a bundle in any container screen
 * to unload its contents into the inventory the bundle sits in.
 */
@Mod(value = BundleDump.MOD_ID, dist = Dist.CLIENT)
public final class BundleDump {
    public static final String MOD_ID = "bundledump";

    public BundleDump(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.addListener(BundleUnloader::onMouseClick);
    }
}
