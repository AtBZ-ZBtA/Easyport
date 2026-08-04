package net.minecraftforge.fluids.capability;

import net.minecraft.world.item.Item;

/**
 * Shim for {@code ItemFluidContainer}, the {@code Item} subclass Forge shipped for simple
 * fluid-holding items.
 *
 * NeoForge dropped it: a fluid-holding item registers an {@code ItemCapability} instead of
 * inheriting one. The capacity is kept because mods read it back, and the class extends
 * {@code Item} because a mod's bucket has to be an item whatever else changes.
 */
public class ItemFluidContainer extends Item {

    public final int capacity;

    public ItemFluidContainer(Properties properties, int capacity) {
        super(properties);
        this.capacity = capacity;
    }
}
