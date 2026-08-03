package net.minecraftforge.fml.config;

/**
 * Shim for {@code net.minecraftforge.fml.config.IConfigSpec}.
 *
 * Needed because Forge's {@code ModLoadingContext.registerConfig} is declared against this
 * interface, not against the concrete spec class — so a mod's bytecode names it in the call
 * descriptor even when the mod itself only ever touches {@code ForgeConfigSpec}.
 *
 * Same shape as the {@link net.minecraftforge.eventbus.api.IEventBus} shim: declared as a
 * sub-interface of NeoForge's, so anything implementing it is simultaneously valid where
 * NeoForge expects its own type. No adapter, no unwrapping at the boundary.
 */
public interface IConfigSpec extends net.neoforged.fml.config.IConfigSpec {
}
