package net.minecraftforge.common;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Shim shape 1 of 3: static field alias.
 *
 * The simplest kind of shim. The Forge symbol and its NeoForge counterpart have identical
 * types, so the field is just re-exported and translated bytecode can keep its original
 * GETSTATIC untouched apart from the owner rename.
 *
 * Used by 199 of the 288 corpus mods, third on the shim work list.
 */
public class MinecraftForge {

    /** Aliases {@link NeoForge#EVENT_BUS}; same {@link IEventBus} instance, not a copy. */
    public static final IEventBus EVENT_BUS = NeoForge.EVENT_BUS;

    private MinecraftForge() {}
}
