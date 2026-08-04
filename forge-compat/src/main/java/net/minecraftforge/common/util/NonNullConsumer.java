package net.minecraftforge.common.util;

/**
 * Shim for {@code NonNullConsumer<T>}.
 *
 * Forge declared its own functional interfaces rather than reusing {@code java.util.function}
 * so that {@code @Nonnull} could be asserted on the parameter. Behaviourally they are identical
 * to their JDK counterparts, and it would be tempting to substitute {@link java.util.function.Consumer}
 * -- which is what the first version of {@link LazyOptional} did.
 *
 * That is exactly wrong, and invisibly so. The type appears in the *descriptor* of every method
 * that takes one, so {@code LazyOptional.ifPresent(NonNullConsumer)} and
 * {@code LazyOptional.ifPresent(Consumer)} are different methods to the JVM. The shim compiled,
 * loaded, and would have thrown NoSuchMethodError at all 121 corpus jars that call it.
 *
 * Found by the shim audit in tools/RenameGaps.java rather than by a launch, which is the point
 * of that check -- nothing fails until the call actually runs.
 */
@FunctionalInterface
public interface NonNullConsumer<T> {
    void accept(T t);
}
