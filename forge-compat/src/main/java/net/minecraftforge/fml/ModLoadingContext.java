package net.minecraftforge.fml;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.config.ModConfig;

/**
 * Shim for {@code ModLoadingContext}, 163 corpus mods.
 *
 * NeoForge kept the type but moved config registration onto {@link net.neoforged.fml.ModContainer},
 * which the corpus mining found independently as a class move corroborated across 101 mods.
 */
public class ModLoadingContext {

    private static final ModLoadingContext INSTANCE = new ModLoadingContext();

    public static ModLoadingContext get() {
        return INSTANCE;
    }

    public void registerConfig(ModConfig.Type type, ForgeConfigSpec spec) {
        net.neoforged.fml.ModLoadingContext.get().getActiveContainer()
                .registerConfig(type.toNeoForge(), spec.unwrap());
    }

    public void registerConfig(ModConfig.Type type, ForgeConfigSpec spec, String fileName) {
        net.neoforged.fml.ModLoadingContext.get().getActiveContainer()
                .registerConfig(type.toNeoForge(), spec.unwrap(), fileName);
    }

    /** Some mods reach the container directly; hand back NeoForge's rather than wrapping it. */
    public net.neoforged.fml.ModContainer getActiveContainer() {
        return net.neoforged.fml.ModLoadingContext.get().getActiveContainer();
    }

    public String getActiveNamespace() {
        return net.neoforged.fml.ModLoadingContext.get().getActiveNamespace();
    }

    private ModLoadingContext() {}
}
