package easyport.neobridge;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * Supplies the arguments NeoForge injects into a mod constructor and Forge 1.20.1 does not.
 *
 * NeoForge 1.21.1 constructs a mod as {@code new MyMod(IEventBus, ModContainer)}; Forge 1.20.1
 * calls a no-argument constructor and expects the mod to fetch what it needs from static
 * context. 408 of the 479 corpus mods declare the injected form, so this is not an edge case —
 * it is most of the corpus, and without it those mods are found by the loader and never built.
 *
 * {@code Translate} synthesises the missing {@code ()V} and fills each parameter from here.
 * Every method is a plain static getter so the synthesised constructor is four instructions and
 * needs nothing from the verifier.
 *
 * <h2>Each of these exists in Forge 1.20.1, which is the only reason the pass is possible</h2>
 *
 * The mod event bus, the mod container and the physical side are all reachable statically in
 * 1.20.1 — they simply are not handed to the constructor. Had any of the three been genuinely
 * absent, the constructor rewrite would have had nothing to write.
 */
public final class ModCtorBridge {

    private ModCtorBridge() {}

    /**
     * The mod's own event bus.
     *
     * {@code FMLJavaModLoadingContext.get()} is valid only while a mod is being constructed,
     * which is exactly when the synthesised constructor runs — so this is read per call and
     * never cached.
     */
    public static net.neoforged.bus.api.IEventBus modEventBus() {
        return new easyport.neobridge.EventBusShim(FMLJavaModLoadingContext.get().getModEventBus());
    }

    /** The container for the mod currently being constructed. */
    public static net.neoforged.fml.ModContainer modContainer() {
        return new easyport.neobridge.ModContainerShim(ModLoadingContext.get().getActiveContainer());
    }

    /**
     * The physical side.
     *
     * Returns Forge's own {@code Dist}, not a shim: {@code Dist} is one of the types the loader
     * scans for inside annotations, so it is renamed rather than shimmed, and by the time this is
     * called the mod's references already name Forge's.
     */
    public static Dist dist() {
        return FMLEnvironment.dist;
    }
}
