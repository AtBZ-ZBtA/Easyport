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

    /** 11 jars each. Holder lookup and value membership, both straight from vanilla. */
    public java.util.Optional<net.minecraft.core.Holder<T>> getHolder(T value) {
        Registry<T> r = registry();
        if (r == null) return java.util.Optional.empty();
        return r.getResourceKey(value).flatMap(r::getHolder).map(h -> (net.minecraft.core.Holder<T>) h);
    }

    public boolean containsValue(T value) {
        Registry<T> r = registry();
        return r != null && r.getKey(value) != null;
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

    /** Forge's registry name, which is the key's location. 12 jars. */
    public ResourceLocation getRegistryName() {
        return key.location();
    }

    /**
     * The holder for a value, or a thrown exception. 12 jars.
     *
     * Forge's "delegate" is NeoForge's {@code Holder.Reference}, reached through the vanilla
     * registry's wrapper lookup. Throws rather than returning null, matching the name.
     */
    public net.minecraft.core.Holder.Reference<T> getDelegateOrThrow(T value) {
        Registry<T> r = registry();
        if (r == null) throw new IllegalStateException("registry " + key.location() + " not available");
        return r.getResourceKey(value)
                .flatMap(r::getHolder)
                .orElseThrow(() -> new IllegalStateException("no delegate for " + value));
    }

    /**
     * Forge's per-registry tag view, 57 jars.
     *
     * NeoForge removed the layer entirely -- tags are read straight from the vanilla registry --
     * so this reconstructs the view rather than delegating. Built fresh on each call because the
     * underlying registry is resolved lazily and tag contents change on datapack reload.
     */
    public net.minecraftforge.registries.tags.ITagManager<T> tags() {
        return new net.minecraftforge.registries.tags.ITagManager<T>() {

            @Override
            public net.minecraftforge.registries.tags.ITag<T> getTag(
                    net.minecraft.tags.TagKey<T> tagKey) {
                return new net.minecraftforge.registries.tags.ITag<T>() {
                    private java.util.List<T> contents() {
                        Registry<T> r = registry();
                        if (r == null) return java.util.List.of();
                        return r.getTag(tagKey)
                                .map(named -> named.stream().map(net.minecraft.core.Holder::value).toList())
                                .orElse(java.util.List.of());
                    }
                    @Override public net.minecraft.tags.TagKey<T> getKey() { return tagKey; }
                    @Override public boolean isEmpty() { return contents().isEmpty(); }
                    @Override public int size() { return contents().size(); }
                    @Override public boolean contains(T value) { return contents().contains(value); }
                    @Override public java.util.Iterator<T> iterator() { return contents().iterator(); }
                };
            }

            @Override
            public boolean isKnownTagName(net.minecraft.tags.TagKey<T> tagKey) {
                Registry<T> r = registry();
                return r != null && r.getTag(tagKey).isPresent();
            }

            @Override
            public net.minecraft.tags.TagKey<T> createTagKey(ResourceLocation location) {
                return net.minecraft.tags.TagKey.create(key, location);
            }

            @Override
            public java.util.Iterator<net.minecraftforge.registries.tags.ITag<T>> iterator() {
                Registry<T> r = registry();
                if (r == null) return java.util.Collections.emptyIterator();
                // getTags() yields (TagKey, Named) pairs; only the key is needed here.
                return r.getTags().<net.minecraftforge.registries.tags.ITag<T>>map(
                        pair -> getTag(pair.getFirst())).iterator();
            }
        };
    }

    public java.util.Collection<T> getValues() {
        Registry<T> r = registry();
        return r == null ? java.util.List.of() : r.stream().toList();
    }
}
