package net.minecraftforge.network;

import java.util.function.Consumer;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.Entity;

/**
 * Shim for {@code NetworkHooks}, referenced by 134 corpus jars -- almost entirely for two things:
 * opening a menu with extra data, and getting an entity's spawn packet.
 *
 * Both moved rather than vanished. Menu opening became an extension method on the player;
 * entity spawning went back to vanilla.
 */
public class NetworkHooks {

    /**
     * Opens a container menu, writing extra data for the client to read on construction.
     *
     * The 72-jar case. NeoForge's version takes a {@code Consumer<RegistryFriendlyByteBuf>};
     * Forge's took {@code Consumer<FriendlyByteBuf>}. The former is a subclass of the latter, so
     * a Forge writer can be handed the richer buffer unchanged -- and it must be handed the real
     * one rather than a plain copy, because menu data in 1.20.5+ routinely contains item stacks,
     * which no longer serialise without registry access.
     */
    public static void openScreen(ServerPlayer player, MenuProvider provider,
                                  Consumer<FriendlyByteBuf> extraData) {
        player.openMenu(provider, buf -> extraData.accept(buf));
    }

    public static void openScreen(ServerPlayer player, MenuProvider provider, BlockPos pos) {
        player.openMenu(provider, pos);
    }

    public static void openScreen(ServerPlayer player, MenuProvider provider) {
        player.openMenu(provider);
    }

    /**
     * The spawn packet for a custom entity, called by 48 jars.
     *
     * Forge needed its own packet because vanilla's could not carry mod entity data. 1.20.5
     * reworked entity spawning so vanilla's packet suffices, and NeoForge dropped the hook.
     *
     * The three-argument constructor is the one that works without a {@code ServerEntity} -- the
     * tracker object that the two-argument form needs and that a mod calling this has no way to
     * obtain. It supplies zero for the spawn data field, which is what an entity with no custom
     * spawn payload would send anyway.
     */
    public static Packet<?> getEntitySpawningPacket(Entity entity) {
        return new ClientboundAddEntityPacket(entity, 0, entity.blockPosition());
    }

    private NetworkHooks() {}
}
