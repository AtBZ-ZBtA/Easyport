package net.neoforged.neoforge.registries;

import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.RegistryObject;

/**
 * Shim: NeoForge's block-typed {@code DeferredHolder}.
 *
 * NeoForge added these narrow subclasses so a mod can write {@code DeferredBlock<MyBlock>} rather
 * than {@code DeferredHolder<Block, MyBlock>}. They add no members — the whole surface is
 * inherited — and they exist here only because mod code names them in field descriptors, where a
 * type that does not exist fails at class definition rather than at first use.
 */
public class DeferredBlock<T extends Block> extends DeferredHolder<Block, T> {

    public DeferredBlock(RegistryObject<T> delegate) {
        super(delegate);
    }
}
