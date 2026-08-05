package net.neoforged.neoforge.registries;

import net.minecraft.world.item.Item;
import net.minecraftforge.registries.RegistryObject;

/** Shim: NeoForge's item-typed {@code DeferredHolder}. See {@link DeferredBlock}. */
public class DeferredItem<T extends Item> extends DeferredHolder<Item, T> {

    public DeferredItem(RegistryObject<T> delegate) {
        super(delegate);
    }
}
