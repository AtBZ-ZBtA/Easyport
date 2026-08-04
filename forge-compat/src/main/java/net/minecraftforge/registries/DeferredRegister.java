package net.minecraftforge.registries;

import java.util.function.Supplier;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraftforge.eventbus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;

/**
 * Shim for {@code DeferredRegister<T>}, 139 corpus mods for {@code register} and 112 for
 * {@code create}.
 *
 * Wraps NeoForge's own DeferredRegister rather than reimplementing deferred registration.
 *
 * The hand-port scored the corpus-mined rule for this class as WRONG, and correctly so:
 * NeoForge splits it into typed variants ({@code createBlocks}, {@code createItems}) whose
 * register methods have different signatures. That is unmappable as a symbol rename, but it
 * is trivial as a shim — the generic {@code DeferredRegister.create(ResourceKey, String)}
 * still exists and covers every registry uniformly, so the typed variants can simply be
 * ignored.
 */
public class DeferredRegister<T> {

    private final net.neoforged.neoforge.registries.DeferredRegister<T> delegate;

    private DeferredRegister(net.neoforged.neoforge.registries.DeferredRegister<T> delegate) {
        this.delegate = delegate;
    }

    public static <T> DeferredRegister<T> create(IForgeRegistry<T> registry, String modId) {
        return create(registry.getRegistryKey(), modId);
    }

    @SuppressWarnings("unchecked")
    public static <T> DeferredRegister<T> create(ResourceKey<? extends Registry<T>> key, String modId) {
        return new DeferredRegister<>(
                net.neoforged.neoforge.registries.DeferredRegister.create(
                        (ResourceKey<Registry<T>>) key, modId));
    }

    public static <T> DeferredRegister<T> create(net.minecraft.resources.ResourceLocation registryName,
                                                 String modId) {
        ResourceKey<Registry<T>> key = ResourceKey.createRegistryKey(registryName);
        return create(key, modId);
    }

    public <I extends T> RegistryObject<I> register(String name, Supplier<? extends I> supplier) {
        DeferredHolder<T, I> holder = delegate.register(name, () -> supplier.get());
        return RegistryObject.of(holder);
    }

    public void register(IEventBus bus) {
        delegate.register(bus);
    }

    /**
     * Every entry registered so far, called by 72 corpus jars.
     *
     * Forge returned {@code Collection<RegistryObject<T>>}; the descriptor mods compiled against
     * is the erased {@code Collection}, so wrapping NeoForge's holders on the way out costs
     * nothing at the call site. Rebuilt on each call rather than cached, because a mod may add
     * entries after the first read and Forge's view was live.
     */
    public java.util.Collection<RegistryObject<T>> getEntries() {
        java.util.List<RegistryObject<T>> out = new java.util.ArrayList<>();
        for (var holder : delegate.getEntries()) out.add(RegistryObject.of(holder));
        return out;
    }

    /** For shim code that has to hand the real object onward. */
    public net.neoforged.neoforge.registries.DeferredRegister<T> unwrap() {
        return delegate;
    }
}
