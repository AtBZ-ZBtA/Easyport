package net.minecraftforge.common.util;

/** Shim for {@code NonNullFunction<T, R>}. See {@link NonNullConsumer} for why it is not Function. */
@FunctionalInterface
public interface NonNullFunction<T, R> {
    R apply(T t);
}
