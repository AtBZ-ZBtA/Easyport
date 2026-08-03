package net.minecraftforge.fml;

import java.util.function.Supplier;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

/**
 * Shim for {@code DistExecutor}, which NeoForge removed outright.
 *
 * Forge used it to run side-specific code without the class being loaded on the wrong side —
 * hence the {@code Supplier<Runnable>} indirection, which keeps the client-only lambda's class
 * from resolving on a server. NeoForge's guidance is a plain {@code FMLEnvironment.dist} check
 * instead, and that is exactly what this reimplements.
 *
 * The indirection is preserved rather than simplified away. Collapsing
 * {@code Supplier<Runnable>} to {@code Runnable} would look tidier and would load the
 * client-only class on a dedicated server, which is the precise crash the pattern exists to
 * avoid.
 *
 * The "unsafe" and "safe" variants differed in Forge only by how carefully they isolated
 * classloading; the distinction is meaningless here, so both route to the same check.
 */
public class DistExecutor {

    public static void unsafeRunWhenOn(Dist dist, Supplier<Runnable> toRun) {
        if (FMLEnvironment.dist == dist) toRun.get().run();
    }

    public static void safeRunWhenOn(Dist dist, Supplier<Runnable> toRun) {
        unsafeRunWhenOn(dist, toRun);
    }

    public static <T> T unsafeCallWhenOn(Dist dist, Supplier<Supplier<T>> toRun) {
        return FMLEnvironment.dist == dist ? toRun.get().get() : null;
    }

    public static <T> T safeCallWhenOn(Dist dist, Supplier<Supplier<T>> toRun) {
        return unsafeCallWhenOn(dist, toRun);
    }

    public static <T> T runForDist(Supplier<Supplier<T>> clientTarget, Supplier<Supplier<T>> serverTarget) {
        return switch (FMLEnvironment.dist) {
            case CLIENT -> clientTarget.get().get();
            case DEDICATED_SERVER -> serverTarget.get().get();
        };
    }

    public static <T> T safeRunForDist(Supplier<Supplier<T>> clientTarget,
                                       Supplier<Supplier<T>> serverTarget) {
        return runForDist(clientTarget, serverTarget);
    }

    /**
     * Forge's serializable supplier variants.
     *
     * The {@code Serializable} bound is what forced these to exist rather than reusing
     * {@link Supplier}: it makes the lambda's capture explicit so Forge could reason about
     * classloading. Mods name these types in their descriptors, so they must be reproduced
     * even though the bound carries no meaning here.
     */
    @FunctionalInterface
    public interface SafeSupplier<T> extends Supplier<T>, java.io.Serializable {}

    @FunctionalInterface
    public interface SafeRunnable extends Runnable, java.io.Serializable {}

    @FunctionalInterface
    public interface SafeCallable<T> extends java.io.Serializable {
        T call();
    }

    private DistExecutor() {}
}
