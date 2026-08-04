package easyport.bridge;

import net.minecraftforge.registries.IForgeRegistry;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * Static replacements for instance methods that vanished from types which had to be renamed.
 *
 * {@code RegisterEvent} is dispatched by identity, so it must be rewritten to NeoForge's class
 * rather than shimmed -- a shimmed copy would be a different type to the one the bus posts, and
 * the listener would never fire. But NeoForge's version dropped {@code getForgeRegistry()},
 * which placebo and others call on it, so the rename resolves the class and then fails on the
 * method.
 *
 * That combination -- must rename, method missing -- is what {@code METHOD_TO_STATIC} exists
 * for. The call site is rewritten to a static here, with the event passed as the first argument.
 * No stack manipulation is needed: the receiver is already sitting below the arguments where
 * INVOKESTATIC expects its first parameter.
 *
 * This is the pattern for the rest of the "rename target missing a called member" list, which
 * currently runs to several hundred entries.
 */
public final class RegistryBridge {

    /**
     * Rebuilds Forge's registry handle from NeoForge's registry key.
     *
     * Forge handed listeners a live {@code IForgeRegistry}; NeoForge hands them the key and
     * expects the registry to be looked up. The shim's {@code IForgeRegistry} is itself just a
     * key holder that resolves lazily, so the two line up exactly.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static IForgeRegistry<?> getForgeRegistry(RegisterEvent event) {
        // Raw cast on the way in. getRegistryKey() returns ResourceKey<? extends Registry<?>>,
        // whose captured wildcard cannot be unified with IForgeRegistry's own type variable --
        // the compiler has no way to know the two anonymous captures are the same type. They
        // are, and the erased descriptor is identical either way, so this is a limitation of
        // what the wildcard can express rather than a real unsoundness.
        return IForgeRegistry.of((net.minecraft.resources.ResourceKey) event.getRegistryKey());
    }

    private RegistryBridge() {}
}
