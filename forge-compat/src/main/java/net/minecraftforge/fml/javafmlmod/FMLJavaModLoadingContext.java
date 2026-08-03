package net.minecraftforge.fml.javafmlmod;

import net.minecraftforge.eventbus.api.IEventBus;
import net.neoforged.fml.ModLoadingContext;

/**
 * Shim for the single most-depended-on API in the corpus: 241 of 288 paired mods.
 *
 * Phase 0 concluded this one was STRUCTURAL — that NeoForge injects the event bus into the
 * mod constructor, so there is no call site to rewrite and the constructor signature would
 * have to change. That was half right. The constructor injection is real, but NeoForge also
 * kept {@code ModLoadingContext.get()} and exposes the bus through
 * {@code getActiveContainer().getEventBus()}, so the old accessor can simply be shimmed.
 *
 * That matters well beyond this class: it moves the largest single item on the work list out
 * of the "needs bytecode surgery" bucket and into "needs a delegating shim", which is the
 * cheap kind.
 */
public class FMLJavaModLoadingContext {

    private static final FMLJavaModLoadingContext INSTANCE = new FMLJavaModLoadingContext();

    public static FMLJavaModLoadingContext get() {
        return INSTANCE;
    }

    /**
     * The mod event bus for whichever mod is currently being constructed.
     *
     * Resolved per call rather than cached: the active container changes as FML constructs
     * each mod in turn, so a captured value would hand every mod the first one's bus.
     */
    public IEventBus getModEventBus() {
        return IEventBus.of(ModLoadingContext.get().getActiveContainer().getEventBus());
    }

    private FMLJavaModLoadingContext() {}
}
