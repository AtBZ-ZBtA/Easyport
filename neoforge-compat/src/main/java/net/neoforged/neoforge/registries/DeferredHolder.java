package net.neoforged.neoforge.registries;

import java.util.function.Supplier;

import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.RegistryObject;

/**
 * Shim: NeoForge's deferred registry entry, backed by Forge's {@code RegistryObject}.
 *
 * Second on the backward shim work list by call weight — {@code get()} alone is called by 211 of
 * the corpus jars, and every {@code DeferredRegister.register} returns one.
 *
 * <h2>Not a Holder</h2>
 *
 * NeoForge's {@code DeferredHolder} implements {@code Holder<T>}; this does not. Forge's
 * {@code RegistryObject} can produce a {@code Holder} but is not one, and faking the interface
 * would mean implementing eleven methods whose contracts depend on registry internals that differ
 * between the two versions. The corpus calls {@code value()} on 40 jars and the rest of
 * {@code Holder} on none, so {@code value()} is here and the interface is not — a mod that passes
 * a {@code DeferredHolder} where vanilla wants a {@code Holder} is a real gap, and one the report
 * will name against a call site rather than one guessed at now.
 *
 * @param <R> the registry's element type, as NeoForge declares it
 * @param <T> the entry's own type
 */
public class DeferredHolder<R, T extends R> implements Supplier<T> {

    private final RegistryObject<T> delegate;

    public DeferredHolder(RegistryObject<T> delegate) {
        this.delegate = delegate;
    }

    /** The Forge object being wrapped, for shims that hand Forge code the real thing. */
    public RegistryObject<T> unwrap() {
        return delegate;
    }

    @Override
    public T get() {
        return delegate.get();
    }

    /** {@code Holder.value()} — the same thing as {@code get()} for a registered entry. */
    public T value() {
        return delegate.get();
    }

    public ResourceLocation getId() {
        return delegate.getId();
    }

    public ResourceKey<T> getKey() {
        return delegate.getKey();
    }

    public boolean isPresent() {
        return delegate.isPresent();
    }
}
