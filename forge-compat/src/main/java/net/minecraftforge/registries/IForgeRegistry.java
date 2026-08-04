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

    /**
     * The rest of Forge's read surface, added from the shim audit rather than one launch at a
     * time. Counts are corpus jars: register 31, getEntries 26, iterator 25, getCodec 23,
     * getKeys 22, getResourceKey 17.
     *
     * All resolve the registry lazily through {@link #registry()} for the same reason the
     * accessors above do -- these objects are built during class initialisation, before any
     * registry is populated, so anything eager would pin null.
     */
    public void register(ResourceLocation id, T value) {
        Registry<T> r = registry();
        if (r == null) {
            throw new IllegalStateException("registry " + key.location() + " is not available yet");
        }
        Registry.register(r, id, value);
    }

    public java.util.Set<java.util.Map.Entry<ResourceKey<T>, T>> getEntries() {
        Registry<T> r = registry();
        return r == null ? java.util.Set.of() : r.entrySet();
    }

    public java.util.Iterator<T> iterator() {
        return getValues().iterator();
    }

    public java.util.Set<ResourceLocation> getKeys() {
        Registry<T> r = registry();
        return r == null ? java.util.Set.of() : r.keySet();
    }

    public java.util.Optional<ResourceKey<T>> getResourceKey(T value) {
        Registry<T> r = registry();
        return r == null ? java.util.Optional.empty() : r.getResourceKey(value);
    }

    /**
     * Forge's registry codec.
     *
     * Vanilla exposes the equivalent as {@code Registry#byNameCodec}, which is what Forge's
     * delegated to. Returns null before the registry exists rather than throwing, matching the
     * other accessors here.
     */
    public com.mojang.serialization.Codec<T> getCodec() {
        Registry<T> r = registry();
        return r == null ? null : r.byNameCodec();
    }

    public java.util.Collection<T> getValues() {
        Registry<T> r = registry();
        return r == null ? java.util.List.of() : r.stream().toList();
    }
}
