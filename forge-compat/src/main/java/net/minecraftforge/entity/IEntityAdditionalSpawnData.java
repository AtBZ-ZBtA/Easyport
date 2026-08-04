package net.minecraftforge.entity;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.neoforge.entity.IEntityWithComplexSpawn;

/**
 * Shim for {@code IEntityAdditionalSpawnData}, 27 corpus mods.
 *
 * NeoForge kept this interface under the name {@code IEntityWithComplexSpawn} and changed both
 * methods to take a {@code RegistryFriendlyByteBuf}. That difference is exactly why this is a
 * shim and not a {@code TYPE_RENAME}: mods *implement* this interface, so renaming would leave
 * NeoForge's two abstract methods unimplemented and every such entity would die with an
 * {@code AbstractMethodError} the first time it spawned -- which is deep into a running game,
 * long after anything would connect it to translation.
 *
 * Extending NeoForge's interface and defaulting its methods onto Forge's is the same trick
 * {@code IEventBus} and {@code IConfigSpec} use: the mod's own overrides satisfy the Forge
 * signatures, the defaults satisfy the NeoForge ones, and NeoForge's networking finds what it
 * expects. The cast is safe in the only direction it runs --
 * {@code RegistryFriendlyByteBuf extends FriendlyByteBuf}, so widening it to pass to the mod
 * loses nothing.
 */
public interface IEntityAdditionalSpawnData extends IEntityWithComplexSpawn {

    void writeSpawnData(FriendlyByteBuf buffer);

    void readSpawnData(FriendlyByteBuf additionalData);

    @Override
    default void writeSpawnData(RegistryFriendlyByteBuf buffer) {
        writeSpawnData((FriendlyByteBuf) buffer);
    }

    @Override
    default void readSpawnData(RegistryFriendlyByteBuf additionalData) {
        readSpawnData((FriendlyByteBuf) additionalData);
    }
}
