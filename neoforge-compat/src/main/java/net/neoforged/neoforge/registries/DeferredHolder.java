package net.neoforged.neoforge.registries;

import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

import com.mojang.datafixers.util.Either;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderOwner;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraftforge.registries.RegistryObject;

/**
 * Shim: NeoForge's deferred registry entry, backed by Forge's {@code RegistryObject}.
 *
 * Second on the backward shim work list by call weight — {@code get()} alone is called by 211 of
 * the corpus jars, and every {@code DeferredRegister.register} returns one.
 *
 * <h2>It has to be a Holder, and the harness is what proved it</h2>
 *
 * This class originally did not implement {@code Holder}, on the argument that faking eleven
 * methods whose contracts depend on registry internals was worse than reporting the gap: the
 * corpus calls {@code value()} on 40 jars and the rest of {@code Holder} on none, so
 * {@code value()} was here and the interface was not.
 *
 * That was wrong, and wrong in a way only a launch could show. Mods do not call {@code Holder}'s
 * methods — they *pass a DeferredHolder where vanilla wants a Holder*, which no member scan sees
 * because there is no call site to count. allthecompressed does it at
 * {@code ModRegistry.blockItem}, and it failed with
 * {@code IncompatibleClassChangeError: DeferredBlock does not implement the requested interface}.
 *
 * Forge's {@code RegistryObject} can produce a real {@code Holder}, so every method delegates to
 * that rather than being invented. Nothing here is a guess about registry internals; the guess
 * was thinking the interface could be skipped.
 *
 * @param <R> the registry's element type, as NeoForge declares it
 * @param <T> the entry's own type
 */
public class DeferredHolder<R, T extends R> implements Holder<R> {

    private final RegistryObject<T> delegate;

    public DeferredHolder(RegistryObject<T> delegate) {
        this.delegate = delegate;
    }

    /**
     * The Forge object being wrapped, for shims that hand Forge code the real thing.
     *
     * Not called `unwrap`: {@code Holder.unwrap()} already means something else -- an
     * {@code Either} of key or value -- and two methods cannot differ by return type alone.
     */
    public RegistryObject<T> registryObject() {
        return delegate;
    }

    /**
     * The real holder behind the entry.
     *
     * Absent until the registry has been populated, which is why this is resolved per call rather
     * than captured. A mod reaching a holder before registration is a real error and says so,
     * instead of silently getting an empty one.
     */
    @SuppressWarnings("unchecked")
    private Holder<R> holder() {
        return (Holder<R>) delegate.getHolder().orElseThrow(() -> new IllegalStateException(
                "Registry entry " + delegate.getId() + " has no holder yet; it is read before "
              + "its registry was populated"));
    }

    @Override
    public R value() {
        return delegate.get();
    }

    /** {@code Supplier.get()}, which is what mod code overwhelmingly calls. */
    @Override
    public R get() {
        return delegate.get();
    }

    public ResourceLocation getId() {
        return delegate.getId();
    }

    @SuppressWarnings("unchecked")
    public ResourceKey<R> getKey() {
        return (ResourceKey<R>) delegate.getKey();
    }

    @Override
    public boolean isBound() {
        return delegate.isPresent();
    }

    @Override public boolean is(ResourceLocation id) { return delegate.getId().equals(id); }

    @Override public boolean is(ResourceKey<R> key) { return getKey().equals(key); }

    @Override public boolean is(Predicate<ResourceKey<R>> predicate) {
        return predicate.test(getKey());
    }

    @Override public boolean is(TagKey<R> tag) { return holder().is(tag); }

    @Override public Stream<TagKey<R>> tags() { return holder().tags(); }

    @Override public Either<ResourceKey<R>, R> unwrap() { return holder().unwrap(); }

    @Override public Optional<ResourceKey<R>> unwrapKey() { return Optional.of(getKey()); }

    @Override public Holder.Kind kind() { return Holder.Kind.REFERENCE; }

    @Override public boolean canSerializeIn(HolderOwner<R> owner) {
        return holder().canSerializeIn(owner);
    }

    public boolean isPresent() {
        return delegate.isPresent();
    }
}
