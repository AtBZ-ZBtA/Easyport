package easyport.bridge;

import java.util.function.Supplier;

import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.TypesafeMap;

/**
 * Replacements for modlauncher constants that no longer exist.
 *
 * Not a Forge API, but the same kind of problem: a mod reads a loader-level constant the target
 * platform deleted, and the read fails during construction with NoSuchFieldError. This one takes
 * cyclopscore down before it registers anything, and ten mods depend on cyclopscore.
 */
public final class EnvBridge {

    /**
     * Stands in for {@code IEnvironment.Keys.NAMING}.
     *
     * Mods read it to ask which mapping namespace is live and use the answer as a proxy for "am I
     * in a development environment". The idiom, verbatim from cyclopscore:
     *
     * <pre>
     *   "mcp".equals(environment.getProperty(Keys.NAMING.get()).orElse("mojang"))
     * </pre>
     *
     * Built through {@code IEnvironment.buildKey} rather than fabricated, which matters twice
     * over. It produces a real {@code TypesafeMap.Key<String>}, which is what the call site casts
     * to -- returning a {@code Supplier<String>} instead compiled and loaded fine and then threw
     * ClassCastException at that checkcast. And modlauncher interns keys by name, so if a naming
     * property *is* present under that name the mod reads the true value rather than a guess.
     *
     * When it is absent the mod falls back to "mojang" and concludes it is not in a development
     * environment. That is both the correct answer for a player's game and the safer one: a mod
     * that wrongly believes it is in a dev workspace turns on extra logging and assertions.
     */
    public static Supplier<TypesafeMap.Key<String>> naming() {
        return IEnvironment.buildKey("naming", String.class);
    }

    private EnvBridge() {}
}
