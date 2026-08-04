package net.minecraftforge.common.capabilities;

import net.minecraft.nbt.Tag;
import net.minecraftforge.common.util.INBTSerializable;

/**
 * Shim for {@code ICapabilitySerializable<T>}, 40 corpus jars.
 *
 * A provider that also persists. Forge used it for capabilities attached to entities and
 * chunks, which have to survive a save; NeoForge replaced the whole attachment mechanism with
 * data attachments, which serialise through their own codec.
 *
 * Reproduced as the plain interface intersection Forge declared. Nothing here calls the
 * serialisation methods -- persistence is not bridged, so a capability attached this way is
 * rebuilt empty on load. That is a real gap and it belongs to the same unfinished piece as
 * {@code AttachCapabilitiesEvent}.
 */
public interface ICapabilitySerializable<T extends Tag>
        extends ICapabilityProvider, INBTSerializable<T> {
}
