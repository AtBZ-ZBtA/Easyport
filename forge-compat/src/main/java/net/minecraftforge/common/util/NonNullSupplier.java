package net.minecraftforge.common.util;

/**
 * Shim for {@code NonNullSupplier<T>}.
 *
 * A Forge type, not a JDK one — easy to mistake for {@code java.util.function.Supplier} since
 * that is all it is, minus the nullability contract. It appears in
 * {@link LazyOptional#of(NonNullSupplier)} descriptors, so mods name it directly and it has to
 * exist even though it carries no behaviour of its own.
 */
@FunctionalInterface
public interface NonNullSupplier<T> {
    T get();
}
