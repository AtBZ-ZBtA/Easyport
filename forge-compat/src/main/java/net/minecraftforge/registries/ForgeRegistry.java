package net.minecraftforge.registries;

import java.util.Set;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

/**
 * Shim for {@code ForgeRegistry<V>}, 19 corpus mods.
 *
 * Forge's concrete registry implementation, and mods reach for it when {@link IForgeRegistry} is
 * not enough -- almost entirely for the integer id surface, which the interface never exposed:
 * {@code getID} in 13 jars and {@code getValue(int)} in 11.
 *
 * <h2>Why this is a subclass and not a separate shim</h2>
 *
 * Mods get one by downcasting: {@code (ForgeRegistry<Item>) ForgeRegistries.ITEMS}. A standalone
 * class would compile, load, and then throw {@code ClassCastException} at that cast, because the
 * object on the other side is an {@code IForgeRegistry}. So the shim hierarchy has to mirror
 * Forge's -- {@code IForgeRegistry} stops being final, this extends it, and every constant in
 * {@link ForgeRegistries} is constructed as one of these.
 *
 * That last part is the bit that is easy to miss and impossible to notice later: leaving the
 * constants as plain {@code IForgeRegistry} instances makes the downcast fail while everything
 * still compiles and the class still loads.
 *
 * Integer ids come from vanilla's own registry, which has kept them throughout -- Forge's ids
 * were vanilla's, not a parallel numbering.
 */
public class ForgeRegistry<V> extends IForgeRegistry<V> {

    ForgeRegistry(ResourceKey<? extends Registry<V>> key) {
        super(key);
    }

    /** Forge returned -1 for an unregistered value, and mods branch on that. */
    public int getID(V value) {
        Registry<V> r = registry();
        return r == null ? -1 : r.getId(value);
    }

    public int getID(ResourceLocation id) {
        Registry<V> r = registry();
        if (r == null) return -1;
        V value = r.get(id);
        return value == null ? -1 : r.getId(value);
    }

    public V getValue(int id) {
        Registry<V> r = registry();
        return r == null ? null : r.byId(id);
    }

    /**
     * Freezing state. Vanilla exposes no way to ask, and NeoForge does the freezing itself at a
     * point mods cannot observe, so this reports unlocked.
     *
     * Mods use the pair to bracket a late registration -- unfreeze, register, freeze. Under
     * NeoForge that whole idiom is unnecessary and unsupported: registration happens through the
     * registry events instead. Reporting "not locked" makes the guard fall through rather than
     * throw, which leaves the mod on the path it would have taken anyway.
     */
    public boolean isLocked() {
        return false;
    }

    public void unfreeze() {}

    public void freeze() {}

    public Set<ResourceLocation> getKeys() {
        return super.getKeys();
    }
}
