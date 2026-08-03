package net.minecraftforge.eventbus.api;

/**
 * Shim for {@code net.minecraftforge.eventbus.api.IEventBus}, 214 corpus mods for
 * {@code addListener} alone.
 *
 * Declared as an interface *extending* NeoForge's, which is what makes the whole shim layer
 * work rather than merely compile. A real Forge mod's bytecode names this exact type in its
 * descriptors, so every shim handing a bus back must return it. Extending NeoForge's interface
 * means such a value is simultaneously a valid argument to NeoForge APIs — no wrapper, no
 * unwrapping at the boundary, and no identity games.
 *
 * This is the correction to an earlier false positive. A spike compiled *against* the shims
 * linked cleanly and looked like proof, but it proved nothing about descriptor compatibility:
 * it had been compiled against the very types it was testing. Real Forge mods are compiled
 * against real Forge, and only they exercise this.
 */
public interface IEventBus extends net.neoforged.bus.api.IEventBus {

    /**
     * Adapts a NeoForge bus to the Forge-facing type.
     *
     * A delegating implementation rather than a cast: NeoForge's own buses do not implement
     * this sub-interface, and cannot be made to.
     */
    static IEventBus of(net.neoforged.bus.api.IEventBus delegate) {
        return new ForgeEventBus(delegate);
    }
}
