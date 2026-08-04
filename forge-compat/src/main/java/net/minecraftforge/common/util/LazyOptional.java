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

    private T value() {
        if (!valid) return null;
        if (!resolvedOnce) {
            resolvedOnce = true;
            resolved = supplier == null ? null : supplier.get();
        }
        return resolved;
    }

    public boolean isPresent() {
        return valid && supplier != null && value() != null;
    }

    public void ifPresent(NonNullConsumer<? super T> consumer) {
        T value = value();
        if (value != null) consumer.accept(value);
    }

    public <U> Optional<U> map(NonNullFunction<? super T, ? extends U> mapper) {
        T value = value();
        return value == null ? Optional.empty() : Optional.ofNullable(mapper.apply(value));
    }

    /**
     * Forge's public accessor, called by 72 corpus jars.
     *
     * The private resolver it shadows is now {@code value()}. Forge had both: a package-private
     * {@code getValue()} returning the object, and this returning an Optional. The shim
     * originally had only the former, under this name -- so every mod calling {@code resolve()}
     * would have found a method with the right name and the wrong return type.
     */
    public Optional<T> resolve() {
        return Optional.ofNullable(value());
    }

    public Optional<T> resolveOptional() {
        return resolve();
    }

    /** Forge's lazy variants, which defer the mapper until the result is itself resolved. */
    public <U> LazyOptional<U> lazyMap(NonNullFunction<? super T, ? extends U> mapper) {
        return isPresent() ? LazyOptional.of(() -> mapper.apply(value())) : LazyOptional.empty();
    }

    public Optional<T> filter(NonNullPredicate<? super T> predicate) {
        T v = value();
        return v != null && predicate.test(v) ? Optional.of(v) : Optional.empty();
    }

    public T orElse(T other) {
        T value = value();
        return value != null ? value : other;
    }

    public T orElseGet(Supplier<? extends T> other) {
        T value = value();
        return value != null ? value : other.get();
    }

    public <X extends Throwable> T orElseThrow(NonNullSupplier<? extends X> exceptionSupplier) throws X {
        T value = value();
        if (value == null) throw exceptionSupplier.get();
        return value;
    }

    public T getValueUnsafe() {
        T value = value();
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
