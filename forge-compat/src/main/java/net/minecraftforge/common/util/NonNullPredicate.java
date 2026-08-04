package net.minecraftforge.common.util;

/** Shim for {@code NonNullPredicate<T>}. See {@link NonNullConsumer} for why it is not Predicate. */
@FunctionalInterface
public interface NonNullPredicate<T> {
    boolean test(T t);
}
