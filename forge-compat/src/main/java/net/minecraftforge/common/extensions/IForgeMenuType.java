package net.minecraftforge.common.extensions;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.network.IContainerFactory;

/**
 * Shim for {@code IForgeMenuType}, referenced by 89 corpus jars.
 *
 * Mods use exactly one thing from it: the static {@code create} that builds a {@code MenuType}
 * from a factory taking extra network data. Vanilla's own {@code MenuType} constructor cannot,
 * which is why the helper existed on both platforms.
 *
 * NeoForge renamed it to {@code IMenuTypeExtension}, so a rename would work for the *type* --
 * but the factory parameter is NeoForge's {@code IContainerFactory}, and the mod passes the
 * Forge one. Since the shimmed {@link IContainerFactory} extends NeoForge's, this can simply
 * forward: the value a mod already has is a valid argument to the NeoForge helper, with no
 * adaptation at the boundary.
 *
 * That is the same property {@code IEventBus} relies on, and the reason the shims extend
 * platform types rather than wrapping them wherever it is possible.
 */
public interface IForgeMenuType<T> {

    static <T extends AbstractContainerMenu> MenuType<T> create(IContainerFactory<T> factory) {
        return net.neoforged.neoforge.common.extensions.IMenuTypeExtension.create(factory);
    }
}
