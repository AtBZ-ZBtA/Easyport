package net.minecraftforge.fml.config;

/**
 * Shim for {@code ModConfig}. Its {@code Type} constants appear in 68-85 corpus mods, and
 * instances reach mods through {@code ModConfigEvent.getConfig()} -- 34 jars call
 * {@code getSpec()} on the result, 25 {@code getType()}, 17 {@code getModId()}.
 *
 * The enum is reproduced rather than aliased because Java enums cannot be subtyped or
 * forwarded — a mod holding {@code ModConfig.Type.COMMON} needs a constant of *this* type,
 * so the value is converted at the boundary instead.
 *
 * The class itself wraps NeoForge's. {@code ModConfigEvent} is renamed rather than shimmed --
 * the bus dispatches on it -- so its {@code getConfig()} hands back a NeoForge {@code ModConfig}
 * where the mod's bytecode expects this one. {@code easyport.bridge.ConfigBridge} sits on that
 * call site and wraps.
 */
public class ModConfig {

    private final net.neoforged.fml.config.ModConfig delegate;

    public ModConfig(net.neoforged.fml.config.ModConfig delegate) {
        this.delegate = delegate;
    }

    public enum Type {
        COMMON,
        CLIENT,
        SERVER,
        STARTUP;

        /** Converts at the point of use, since the two enums are unrelated types to the JVM. */
        public net.neoforged.fml.config.ModConfig.Type toNeoForge() {
            return switch (this) {
                case COMMON -> net.neoforged.fml.config.ModConfig.Type.COMMON;
                case CLIENT -> net.neoforged.fml.config.ModConfig.Type.CLIENT;
                case SERVER -> net.neoforged.fml.config.ModConfig.Type.SERVER;
                case STARTUP -> net.neoforged.fml.config.ModConfig.Type.STARTUP;
            };
        }

        /** The other direction, for values arriving from NeoForge. */
        public static Type fromNeoForge(net.neoforged.fml.config.ModConfig.Type type) {
            return switch (type) {
                case COMMON -> COMMON;
                case CLIENT -> CLIENT;
                case SERVER -> SERVER;
                case STARTUP -> STARTUP;
            };
        }
    }

    public Type getType() {
        return Type.fromNeoForge(delegate.getType());
    }

    public String getModId() {
        return delegate.getModId();
    }

    public String getFileName() {
        return delegate.getFileName();
    }

    /**
     * The spec this config was built from.
     *
     * Returns null unless the spec is one of ours. Mods overwhelmingly call this to get their own
     * {@code ForgeConfigSpec} back and then read values from it, and a ForgeConfigSpec *is* an
     * IConfigSpec here -- so the common path works. A spec created by NeoForge-native code is not
     * this interface and cannot be made into one, and null is a better answer than a cast that
     * would throw.
     */
    public IConfigSpec getSpec() {
        return delegate.getSpec() instanceof IConfigSpec spec ? spec : null;
    }

    /** For bridge code that has to hand the real object onward. */
    public net.neoforged.fml.config.ModConfig unwrap() {
        return delegate;
    }
}
