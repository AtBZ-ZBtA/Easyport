package net.minecraftforge.registries;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

/**
 * Shim for {@code IForgeRegistry<T>}, whose {@code getValue}/{@code getKey} appear in 110/96
 * corpus mods.
 *
 * Forge declares this as an interface, but mods overwhelmingly *consume* it rather than
 * implement it — chiefly by handing {@code ForgeRegistries.BLOCKS} to a
 * {@code DeferredRegister}. Modelling it as a final class holding the registry key keeps that
 * common path simple. A mod that genuinely implements the interface will not translate, which
 * the transformer reports rather than producing something subtly broken.
 */
public final class IForgeRegistry<T> {

    private final ResourceKey<? extends Registry<T>> key;

    IForgeRegistry(ResourceKey<? extends Registry<T>> key) {
        this.key = key;
    }

    /**
     * Public factory, for bridge code outside this package.
     *
     * The constructor stays package-private because mods never call it -- they read the
     * constants on {@link ForgeRegistries}. This exists for {@code easyport.bridge}, which has
     * to build one from a registry key recovered at runtime.
     */
    public static <T> IForgeRegistry<T> of(ResourceKey<? extends Registry<T>> key) {
        return new IForgeRegistry<>(key);
    }

    public ResourceKey<? extends Registry<T>> getRegistryKey() {
        return key;
    }

    /**
     * Resolved on every call, never cached.
     *
     * These objects are created during class initialisation of {@link ForgeRegistries}, which
     * happens long before the registries themselves are populated. Caching here would pin null.
     */
    @SuppressWarnings("unchecked")
    private Registry<T> registry() {
        return (Registry<T>) BuiltInRegistries.REGISTRY.get(key.location());
    }

    public T getValue(ResourceLocation id) {
        Registry<T> r = registry();
        return r == null ? null : r.get(id);
    }

    public ResourceLocation getKey(T value) {
        Registry<T> r = registry();
        return r == null ? null : r.getKey(value);
    }

    public boolean containsKey(ResourceLocation id) {
        Registry<T> r = registry();
        return r != null && r.containsKey(id);
    }

    public java.util.Collection<T> getValues() {
        Registry<T> r = registry();
        return r == null ? java.util.List.of() : r.stream().toList();
    }
}
