package easyport.bridge;

import java.util.function.Supplier;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.neoforged.fml.common.EventBusSubscriber;

/**
 * Replacement for {@code EventBusSubscriber.Bus.bus()}.
 *
 * Forge's {@code Bus} enum could hand back the bus it named, and mods use it to register a
 * listener on whichever bus a value selects -- blockui does exactly this inside a
 * {@code DistExecutor} lambda. NeoForge kept the enum (renaming {@code FORGE} to {@code GAME})
 * and dropped the accessor, so the rename resolves the type and the call fails.
 *
 * The enum has to be renamed rather than shimmed, because {@code @EventBusSubscriber} is scanned
 * by the loader and a shimmed annotation would never be found. So the accessor moves to a static
 * instead -- {@code METHOD_TO_STATIC}, which passes the enum value as the first argument.
 */
public final class BusBridge {

    /**
     * The bus a {@code Bus} constant names.
     *
     * Returns a supplier rather than the bus itself, matching Forge -- and the laziness is
     * load-bearing for {@code MOD}, which resolves against whichever mod is being constructed at
     * the moment the supplier runs. Resolving eagerly would capture the wrong mod's bus for any
     * caller that stores the supplier.
     */
    public static Supplier<IEventBus> bus(EventBusSubscriber.Bus bus) {
        return bus == EventBusSubscriber.Bus.MOD
                ? () -> FMLJavaModLoadingContext.get().getModEventBus()
                : () -> MinecraftForge.EVENT_BUS;
    }

    /**
     * Forge s {@code Bus.FORGE}, which NeoForge renamed to {@code GAME}.
     *
     * A field read, so FIELD_TO_STATIC redirects it here rather than a rename -- there is no
     * rule kind for renaming a field, and adding one for a single constant is worse than reusing
     * the redirect that already exists.
     */
    public static EventBusSubscriber.Bus forge() {
        return EventBusSubscriber.Bus.GAME;
    }

    private BusBridge() {}
}
