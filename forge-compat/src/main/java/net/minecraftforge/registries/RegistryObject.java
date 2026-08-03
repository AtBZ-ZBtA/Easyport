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

    /** Some mods pass the holder onward to NeoForge APIs; expose it rather than re-wrapping. */
    public DeferredHolder<?, ?> unwrap() {
        return delegate;
    }
}
