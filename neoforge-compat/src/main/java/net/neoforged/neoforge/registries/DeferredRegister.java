package net.neoforged.neoforge.registries;

import java.util.ArrayList;
import java.util.Collection;
import java.util.function.Supplier;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.registries.RegistryObject;

/**
 * Shim: NeoForge's deferred registry, backed by Forge's.
 *
 * The single highest-weight type on the backward shim list — 250 of the corpus jars name it, and
 * its two {@code register} overloads are called by 247 each. Nothing loads without it.
 *
 * <h2>The two APIs are the same idea with different return types</h2>
 *
 * Forge's {@code register(String, Supplier)} returns a {@code RegistryObject}; NeoForge's returns
 * a {@code DeferredHolder}. So every method here forwards to Forge and wraps the result, and the
 * wrappers are kept so {@code getEntries()} returns the same objects a mod already holds rather
 * than fresh ones — a mod that compares entries by identity is rare but is right to expect it.
 *
 * <h2>What is deliberately missing</h2>
 *
 * {@code createDataComponents} and the {@code DataComponents} subclass. Data components do not
 * exist in 1.20.1 in any form, so there is nothing to defer registration *to*; a shim would
 * accept registrations and drop them, which is the silent-success failure this project refuses.
 * 36 jars call it, and they will be named in the report instead.
 */
public class DeferredRegister<T> {

    private final net.minecraftforge.registries.DeferredRegister<T> delegate;
    private final Collection<DeferredHolder<T, ? extends T>> entries = new ArrayList<>();

    protected DeferredRegister(net.minecraftforge.registries.DeferredRegister<T> delegate) {
        this.delegate = delegate;
    }

    public static <B> DeferredRegister<B> create(ResourceKey<? extends Registry<B>> key, String modid) {
        return new DeferredRegister<>(net.minecraftforge.registries.DeferredRegister.create(key, modid));
    }

    /**
     * NeoForge takes the registry itself where Forge 1.20.1 takes its key. Every vanilla registry
     * knows its own key, so the conversion is exact rather than a lookup that could miss.
     */
    public static <B> DeferredRegister<B> create(Registry<B> registry, String modid) {
        return create(registry.key(), modid);
    }

    public static DeferredRegister.Blocks createBlocks(String modid) {
        return new DeferredRegister.Blocks(
                net.minecraftforge.registries.DeferredRegister.create(Registries.BLOCK, modid));
    }

    public static DeferredRegister.Items createItems(String modid) {
        return new DeferredRegister.Items(
                net.minecraftforge.registries.DeferredRegister.create(Registries.ITEM, modid));
    }

    public <I extends T> DeferredHolder<T, I> register(String name, Supplier<? extends I> supplier) {
        DeferredHolder<T, I> holder = new DeferredHolder<>(delegate.register(name, supplier));
        entries.add(holder);
        return holder;
    }

    public void register(net.neoforged.bus.api.IEventBus bus) {
        // The shim bus wraps the real Forge one; Forge's DeferredRegister needs that real bus,
        // because it subscribes to a registry event the shim has no way to forward.
        delegate.register(((easyport.neobridge.EventBusShim) bus).unwrap());
    }

    public Collection<DeferredHolder<T, ? extends T>> getEntries() {
        return entries;
    }

    /** Forge's register, for shims that need to add an entry without wrapping it. */
    protected net.minecraftforge.registries.DeferredRegister<T> unwrap() {
        return delegate;
    }

    /** Lets the narrowed subclasses keep {@code getEntries()} accurate. */
    @SuppressWarnings("unchecked")
    protected void record(DeferredHolder<?, ?> holder) {
        entries.add((DeferredHolder<T, ? extends T>) holder);
    }

    /** NeoForge's block-specialised register, which narrows only the return types. */
    public static class Blocks extends DeferredRegister<Block> {

        protected Blocks(net.minecraftforge.registries.DeferredRegister<Block> delegate) {
            super(delegate);
        }

        /**
         * Covariant override, which is the whole point of the subclass: NeoForge narrows the
         * return type from {@code DeferredHolder} to {@code DeferredBlock} so callers need no
         * cast. 69 corpus jars call this exact descriptor, and a shim that only inherited the
         * parent's would link against none of them.
         */
        @Override
        public <I extends Block> DeferredBlock<I> register(String name, Supplier<? extends I> supplier) {
            DeferredBlock<I> held = new DeferredBlock<>(this.<I>rawRegister(name, supplier));
            record(held);
            return held;
        }

        /**
         * The convenience overloads, which NeoForge added and Forge 1.20.1 has no counterpart to.
         *
         * They are not sugar in the shim's terms: a mod calling {@code registerBlock(name, ctor,
         * properties)} names that exact descriptor, and a shim without it is a
         * {@code NoSuchMethodError} at registration. allthecompressed is the corpus example.
         *
         * The {@code Function} form hands the constructor its properties, so the supplier is
         * built here rather than by the caller.
         */
        public <I extends Block> DeferredBlock<I> registerBlock(
                String name, java.util.function.Function<BlockBehaviour.Properties, ? extends I> ctor,
                BlockBehaviour.Properties properties) {
            return register(name, () -> ctor.apply(properties));
        }

        /** NeoForge defaults the properties when they are not given. */
        public <I extends Block> DeferredBlock<I> registerBlock(
                String name, java.util.function.Function<BlockBehaviour.Properties, ? extends I> ctor) {
            return registerBlock(name, ctor, BlockBehaviour.Properties.of());
        }

        public DeferredBlock<Block> registerSimpleBlock(String name, BlockBehaviour.Properties properties) {
            return register(name, () -> new Block(properties));
        }

        public DeferredBlock<Block> registerSimpleBlock(String name) {
            return registerSimpleBlock(name, BlockBehaviour.Properties.of());
        }

        @SuppressWarnings("unchecked")
        private <I extends Block> RegistryObject<I> rawRegister(String name, Supplier<? extends I> supplier) {
            return (RegistryObject<I>) unwrap().register(name, supplier);
        }
    }

    /** NeoForge's item-specialised register. See {@link Blocks}. */
    public static class Items extends DeferredRegister<Item> {

        protected Items(net.minecraftforge.registries.DeferredRegister<Item> delegate) {
            super(delegate);
        }

        /** Covariant override, narrowing to {@code DeferredItem}. See {@link Blocks#register}. */
        @Override
        public <I extends Item> DeferredItem<I> register(String name, Supplier<? extends I> supplier) {
            DeferredItem<I> held = new DeferredItem<>(this.<I>rawRegister(name, supplier));
            record(held);
            return held;
        }

        /** The item counterparts of {@link Blocks#registerBlock}. Same reason: mods name them. */
        public <I extends Item> DeferredItem<I> registerItem(
                String name, java.util.function.Function<Item.Properties, ? extends I> ctor,
                Item.Properties properties) {
            return register(name, () -> ctor.apply(properties));
        }

        public <I extends Item> DeferredItem<I> registerItem(
                String name, java.util.function.Function<Item.Properties, ? extends I> ctor) {
            return registerItem(name, ctor, new Item.Properties());
        }

        public DeferredItem<net.minecraft.world.item.BlockItem> registerSimpleBlockItem(
                String name, Supplier<? extends Block> block, Item.Properties properties) {
            return register(name, () -> new net.minecraft.world.item.BlockItem(block.get(), properties));
        }

        public DeferredItem<net.minecraft.world.item.BlockItem> registerSimpleBlockItem(
                String name, Supplier<? extends Block> block) {
            return registerSimpleBlockItem(name, block, new Item.Properties());
        }

        @SuppressWarnings("unchecked")
        private <I extends Item> RegistryObject<I> rawRegister(String name, Supplier<? extends I> supplier) {
            return (RegistryObject<I>) unwrap().register(name, supplier);
        }
    }
}
