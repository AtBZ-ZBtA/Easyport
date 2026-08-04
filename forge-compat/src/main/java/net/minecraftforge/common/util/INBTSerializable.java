package net.minecraftforge.common.util;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.Tag;

/**
 * Shim for {@code INBTSerializable<T>}, 61 corpus jars.
 *
 * NeoForge has this interface at the same path, so a prefix rule reaches it -- but its methods
 * gained a {@code HolderLookup.Provider} parameter in 1.20.5, when NBT serialisation started
 * needing registry access for anything holding an item stack. A Forge implementor overrides the
 * one-argument form and satisfies nothing.
 *
 * Both shapes are declared here: the Forge methods a mod implements, and defaults for the
 * NeoForge ones that forward to them. An unmodified Forge implementation therefore satisfies
 * the full interface, and a caller on either side reaches the mod's code.
 *
 * The forwarding drops the registry provider, so a mod serialising item stacks through this path
 * will fail on the components rewrite rather than here. That is the Phase 4 problem, not this
 * one.
 */
public interface INBTSerializable<T extends Tag> {

    T serializeNBT();

    void deserializeNBT(T nbt);

    default T serializeNBT(HolderLookup.Provider provider) {
        return serializeNBT();
    }

    default void deserializeNBT(HolderLookup.Provider provider, T nbt) {
        deserializeNBT(nbt);
    }
}
