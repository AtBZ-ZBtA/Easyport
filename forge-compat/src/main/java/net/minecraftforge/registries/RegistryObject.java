package net.minecraftforge.registries;

import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.registries.DeferredHolder;

/**
 * Shim for {@code RegistryObject<T>}, 148 corpus mods.
 *
 * Corpus mining paired {@code RegistryObject#get} with {@code DeferredHolder#get}, and the
 * hand-port confirmed it — but also showed the mapping is not 1:1. On the NeoForge side the
 * concrete type depends on the registry ({@code DeferredBlock}, {@code DeferredItem}), which
 * is why a symbol table alone scored this one wrong. Wrapping the general
 * {@link DeferredHolder} sidesteps the disambiguation entirely: one shim type covers every
 * registry, and the mod never sees which subtype it would have been.
 */
public final class RegistryObject<T> implements Supplier<T> {

    // Held wildcarded because DeferredHolder is declared <R, T extends R>, so <?, T> cannot be
    // expressed -- the compiler has no way to prove the captured R bounds T. Every construction
    // site passes a correctly-typed holder, so the cast in get() is safe by construction.
    private final DeferredHolder<?, ?> delegate;

    RegistryObject(DeferredHolder<?, ?> delegate) {
        this.delegate = delegate;
    }

    public static <T> RegistryObject<T> of(DeferredHolder<?, ?> holder) {
        return new RegistryObject<>(holder);
    }

    @Override
    @SuppressWarnings("unchecked")
    public T get() {
        return (T) delegate.get();
    }

    public ResourceLocation getId() {
        return delegate.getId();
    }

    public boolean isPresent() {
        return delegate.isBound();
    }

    public Optional<T> filter(java.util.function.Predicate<? super T> predicate) {
        return isPresent() ? Optional.of(get()).filter(predicate) : Optional.empty();
    }

    /**
     * The registry key for this entry, called by 42 corpus jars.
     *
     * Forge's returned {@code ResourceKey<T>} -- the key of the *entry*, not of the registry it
     * lives in. NeoForge's DeferredHolder exposes the same thing under the same name, so this is
     * a straight forward; the erased descriptor is {@code ResourceKey} either way.
     */
    @SuppressWarnings("unchecked")
    public <R> net.minecraft.resources.ResourceKey<R> getKey() {
        return (net.minecraft.resources.ResourceKey<R>) delegate.getKey();
    }

    /** Some mods pass the holder onward to NeoForge APIs; expose it rather than re-wrapping. */
    /**
     * The vanilla holder, 14 corpus jars.
     *
     * Forge returned {@code Optional<Holder<T>>} and mods use it to build tag entries and
     * ingredients. Empty rather than a holder that is not yet bound, which is what Forge did.
     */
    @SuppressWarnings("unchecked")
    public Optional<net.minecraft.core.Holder<T>> getHolder() {
        return delegate.isBound()
                ? Optional.of((net.minecraft.core.Holder<T>) delegate)
                : Optional.empty();
    }

    /**
     * Forge's direct factory, 13 corpus jars.
     *
     * Built a RegistryObject for an entry that may not exist yet, without going through a
     * DeferredRegister -- typically to reference another mod's content. Reproduced with a
     * DeferredHolder over the same key, which is lazily bound in exactly the same way.
     */
    public static <T> RegistryObject<T> create(ResourceLocation name, IForgeRegistry<T> registry) {
        return of(DeferredHolder.create(registry.getRegistryKey(), name));
    }

    public DeferredHolder<?, ?> unwrap() {
        return delegate;
    }
}
