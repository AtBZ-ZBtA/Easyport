package net.minecraftforge.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * Shim for {@code IContainerFactory<T>}, which builds a menu from data sent with the open packet.
 *
 * <h2>Why not a rename</h2>
 *
 * NeoForge has this interface at the same name and it looked like a clean namespace move. Its
 * method takes {@code RegistryFriendlyByteBuf} where Forge's took {@code FriendlyByteBuf} --
 * the 1.20.5 change that gave packet buffers registry access, without which an item stack cannot
 * be read.
 *
 * That matters because mods *implement* this rather than call it. A rename leaves the mod's
 * {@code create(int, Inventory, FriendlyByteBuf)} overriding nothing and NeoForge's abstract
 * method unimplemented -- the class loads and throws AbstractMethodError the first time a menu
 * is opened. Flagged by the member check in RenameGaps before it ever ran.
 *
 * <h2>Declaring both</h2>
 *
 * Extends NeoForge's interface and declares Forge's method, with a default implementing
 * NeoForge's in terms of it. An unmodified Forge implementation therefore satisfies the whole
 * interface, and NeoForge calling the richer overload reaches the mod's code.
 *
 * The narrowing is safe in this direction only: {@code RegistryFriendlyByteBuf} *is* a
 * {@code FriendlyByteBuf}, so handing it to a Forge implementation loses nothing at runtime --
 * the mod simply does not know it could have read registry-backed data. Same shape as the
 * {@code INBTSerializable} shim.
 */
@FunctionalInterface
public interface IContainerFactory<T extends AbstractContainerMenu>
        extends net.neoforged.neoforge.network.IContainerFactory<T> {

    T create(int windowId, Inventory inventory, FriendlyByteBuf data);

    @Override
    default T create(int windowId, Inventory inventory, RegistryFriendlyByteBuf data) {
        return create(windowId, inventory, (FriendlyByteBuf) data);
    }
}
