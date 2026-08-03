package net.minecraftforge.common.util;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

import java.util.function.Supplier;

/**
 * Shim for {@code LazyOptional<T>}, 117 corpus mods for {@code of} and 99 for {@code cast}.
 *
 * NeoForge removed this type outright as part of the capability rewrite, so there is nothing
 * to delegate to — unlike most of forge-compat, this is a reimplementation rather than a
 * wrapper. That is fine, because the type was never coupled to Forge internals: it is a lazy,
 * invalidatable Optional and nothing more.
 *
 * The laziness is the point and is preserved exactly. Mods build these during registration,
 * long before the object being supplied can be constructed, so resolving eagerly in
 * {@code of()} would evaluate suppliers far too early — typically NPEing on a registry that
 * has not populated yet.
 *
 * Invalidation is also preserved. Forge used it to signal that a capability provider had gone
 * away, and mods hold listeners expecting that callback; dropping it would leak stale
 * references rather than fail loudly.
 */
public class LazyOptional<T> {

    private static final LazyOptional<Void> EMPTY = new LazyOptional<>(null);

    private final Supplier<T> supplier;
    private T resolved;
    private boolean resolvedOnce;
    private boolean valid = true;
    private final java.util.List<Consumer<LazyOptional<T>>> listeners = new java.util.ArrayList<>();

    private LazyOptional(Supplier<T> supplier) {
        this.supplier = supplier;
    }

    public static <T> LazyOptional<T> of(NonNullSupplier<T> supplier) {
        return supplier == null ? empty() : new LazyOptional<>(supplier::get);
    }

    @SuppressWarnings("unchecked")
    public static <T> LazyOptional<T> empty() {
        return (LazyOptional<T>) EMPTY;
    }

    private T resolve() {
        if (!valid) return null;
        if (!resolvedOnce) {
            resolvedOnce = true;
            resolved = supplier == null ? null : supplier.get();
        }
        return resolved;
    }

    public boolean isPresent() {
        return valid && supplier != null && resolve() != null;
    }

    public void ifPresent(Consumer<? super T> consumer) {
        T value = resolve();
        if (value != null) consumer.accept(value);
    }

    public <U> Optional<U> map(Function<? super T, ? extends U> mapper) {
        T value = resolve();
        return value == null ? Optional.empty() : Optional.ofNullable(mapper.apply(value));
    }

    public Optional<T> resolveOptional() {
        return Optional.ofNullable(resolve());
    }

    public T orElse(T other) {
        T value = resolve();
        return value != null ? value : other;
    }

    public T orElseGet(Supplier<? extends T> other) {
        T value = resolve();
        return value != null ? value : other.get();
    }

    public <X extends Throwable> T orElseThrow(Supplier<? extends X> exceptionSupplier) throws X {
        T value = resolve();
        if (value == null) throw exceptionSupplier.get();
        return value;
    }

    public T getValueUnsafe() {
        T value = resolve();
        if (value == null) throw new NoSuchElementException("LazyOptional is empty or invalidated");
        return value;
    }

    /**
     * Unchecked by design, matching Forge.
     *
     * Callers use this to reinterpret a capability's type after checking it themselves, so the
     * cast cannot be verified here and was never verified in Forge either.
     */
    @SuppressWarnings("unchecked")
    public <X> LazyOptional<X> cast() {
        return (LazyOptional<X>) this;
    }

    public void addListener(Consumer<LazyOptional<T>> listener) {
        if (valid) listeners.add(listener); else listener.accept(this);
    }

    public void invalidate() {
        if (!valid) return;
        valid = false;
        for (Consumer<LazyOptional<T>> listener : listeners) listener.accept(this);
        listeners.clear();
    }
}
