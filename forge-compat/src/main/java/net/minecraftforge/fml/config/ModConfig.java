package net.minecraftforge.fml.config;

/**
 * Shim for {@code ModConfig}, whose {@code Type} constants appear in 68-85 corpus mods.
 *
 * The enum is reproduced rather than aliased because Java enums cannot be subtyped or
 * forwarded — a mod holding {@code ModConfig.Type.COMMON} needs a constant of *this* type,
 * so the value is converted at the boundary instead.
 */
public class ModConfig {

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
    }

    private ModConfig() {}
}
