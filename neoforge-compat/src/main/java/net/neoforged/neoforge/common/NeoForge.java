package net.neoforged.neoforge.common;

import net.neoforged.bus.api.IEventBus;

/**
 * Shim: the game event bus holder.
 *
 * The single highest-weight unresolved type in the backward direction — 271 of the corpus jars
 * name it, almost always as {@code NeoForge.EVENT_BUS.register(this)}.
 *
 * <h2>A wrapper, unlike its forward counterpart, and the difference is worth stating</h2>
 *
 * Going forward, {@code MinecraftForge.EVENT_BUS} could be a straight alias: it *is*
 * {@code NeoForge.EVENT_BUS}, verified as reference equality, because NeoForge's bus already
 * implemented the interface Forge mods name. Nothing in Forge 1.20.1 implements
 * {@code net.neoforged.bus.api.IEventBus}, so the only way to present one is to build it.
 *
 * The property that actually matters survives either way: listeners land on the bus the game
 * dispatches from. {@code EventBusShim} forwards every call to the real Forge bus and queues
 * nothing.
 *
 * The field is initialised eagerly and that is safe here, unlike {@code NeoForgeRegistries}:
 * {@code MinecraftForge.EVENT_BUS} is a constant created with the class, not something the loader
 * publishes partway through startup.
 */
public class NeoForge {

    private NeoForge() {}

    public static final IEventBus EVENT_BUS =
            new easyport.neobridge.EventBusShim(net.minecraftforge.common.MinecraftForge.EVENT_BUS);
}
