package net.minecraftforge.fml;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.config.IConfigSpec;
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

    /**
     * Declared against {@link IConfigSpec}, matching Forge, because that is the type a mod's
     * call descriptor names — a signature taking the concrete ForgeConfigSpec would not
     * resolve and would fail at load with NoSuchMethodError.
     */
    public void registerConfig(ModConfig.Type type, IConfigSpec spec) {
        net.neoforged.fml.ModLoadingContext.get().getActiveContainer()
                .registerConfig(type.toNeoForge(), unwrap(spec));
    }

    public void registerConfig(ModConfig.Type type, IConfigSpec spec, String fileName) {
        net.neoforged.fml.ModLoadingContext.get().getActiveContainer()
                .registerConfig(type.toNeoForge(), unwrap(spec), fileName);
    }

    /**
     * Hands NeoForge the underlying spec where there is one.
     *
     * Our IConfigSpec already extends NeoForge's, so passing it straight through would work —
     * but every call would then bounce through the shim's forwarding methods for no reason.
     * Unwrapping keeps the hot path direct, and anything else still passes through as-is.
     */
    private static net.neoforged.fml.config.IConfigSpec unwrap(IConfigSpec spec) {
        return (spec instanceof ForgeConfigSpec forge) ? forge.unwrap() : spec;
    }

    /**
     * Accepted and ignored.
     *
     * The only extension point in wide use was {@code DisplayTest}, which NeoForge removed —
     * server-list compatibility is declared in the descriptor now, not registered at runtime.
     * There is nothing to forward to, so this exists to make the call link.
     *
     * Silently dropping a registration is normally the wrong thing, but the alternatives are
     * worse: throwing would break mods over a cosmetic feature, and forwarding to NeoForge's
     * {@code registerExtensionPoint} would hand it a Forge type it cannot use.
     */
    public <T extends IExtensionPoint> void registerExtensionPoint(Class<T> point,
                                                                   java.util.function.Supplier<T> supplier) {
        // no-op by design; see above
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
