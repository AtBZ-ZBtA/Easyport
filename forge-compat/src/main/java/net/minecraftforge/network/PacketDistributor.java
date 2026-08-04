package net.minecraftforge.network;

import java.util.function.BiConsumer;
import java.util.function.Supplier;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Shim for {@code PacketDistributor}, referenced by 111 corpus jars.
 *
 * Forge modelled targeting as a generic object plus a supplier of the thing being targeted:
 * {@code PacketDistributor.PLAYER.with(() -> player)}. NeoForge replaced the whole design with
 * flat static methods -- {@code sendToPlayer(player, payload)}. The constants are what mods
 * reference ({@code PLAYER} alone appears in 82 jars), so the old shape is preserved and each
 * constant simply carries the NeoForge call it maps to.
 *
 * <h2>Deliberately eager suppliers</h2>
 *
 * {@code with(Supplier)} does not call the supplier -- {@link PacketTarget} holds it and resolves
 * it at send time. Forge behaved the same way, and mods rely on it: a target built once and
 * reused is expected to re-read the supplier on each send.
 */
public class PacketDistributor<T> {

    private final BiConsumer<T, CustomPacketPayload> dispatch;

    private PacketDistributor(BiConsumer<T, CustomPacketPayload> dispatch) {
        this.dispatch = dispatch;
    }

    public static final PacketDistributor<ServerPlayer> PLAYER =
            new PacketDistributor<>((player, payload) ->
                    net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, payload));

    public static final PacketDistributor<Void> ALL =
            new PacketDistributor<>((ignored, payload) ->
                    net.neoforged.neoforge.network.PacketDistributor.sendToAllPlayers(payload));

    public static final PacketDistributor<Void> SERVER =
            new PacketDistributor<>((ignored, payload) ->
                    net.neoforged.neoforge.network.PacketDistributor.sendToServer(payload));

    public static final PacketDistributor<ResourceKey<Level>> DIMENSION =
            new PacketDistributor<>((key, payload) -> {
                ServerLevel level = level(key);
                if (level != null) {
                    net.neoforged.neoforge.network.PacketDistributor
                            .sendToPlayersInDimension(level, payload);
                }
            });

    public static final PacketDistributor<Entity> TRACKING_ENTITY =
            new PacketDistributor<>((entity, payload) ->
                    net.neoforged.neoforge.network.PacketDistributor
                            .sendToPlayersTrackingEntity(entity, payload));

    public static final PacketDistributor<Entity> TRACKING_ENTITY_AND_SELF =
            new PacketDistributor<>((entity, payload) ->
                    net.neoforged.neoforge.network.PacketDistributor
                            .sendToPlayersTrackingEntityAndSelf(entity, payload));

    public static final PacketDistributor<LevelChunk> TRACKING_CHUNK =
            new PacketDistributor<>((chunk, payload) -> {
                if (chunk.getLevel() instanceof ServerLevel sl) {
                    net.neoforged.neoforge.network.PacketDistributor
                            .sendToPlayersTrackingChunk(sl, chunk.getPos(), payload);
                }
            });

    public static final PacketDistributor<TargetPoint> NEAR =
            new PacketDistributor<>((point, payload) -> {
                ServerLevel level = level(point.dim);
                if (level != null) {
                    net.neoforged.neoforge.network.PacketDistributor.sendToPlayersNear(
                            level, point.excluded, point.x, point.y, point.z, point.r2, payload);
                }
            });

    /**
     * Resolves a dimension key against the running server.
     *
     * Forge's distributor reached the server through its own network context; NeoForge's takes a
     * {@code ServerLevel} directly, so the lookup has to happen somewhere and this is the only
     * place that has the key. Returns null off-server rather than throwing -- a mod sending to a
     * dimension from the client is misusing the API, and dropping the packet matches what Forge
     * did more closely than a crash would.
     */
    private static ServerLevel level(ResourceKey<Level> key) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server == null ? null : server.getLevel(key);
    }

    public PacketTarget with(Supplier<T> supplier) {
        return new PacketTarget(payload -> dispatch.accept(supplier.get(), payload));
    }

    /** For the targets that address everyone -- {@code ALL}, {@code SERVER}. */
    public PacketTarget noArg() {
        return new PacketTarget(payload -> dispatch.accept(null, payload));
    }

    /**
     * A resolved destination.
     *
     * Forge exposed {@code send(Packet)} on this; mods overwhelmingly go through
     * {@code SimpleChannel.send(target, message)} instead (109 jars versus a handful), so the
     * payload-taking form is what the channel calls.
     */
    public static class PacketTarget {
        private final java.util.function.Consumer<CustomPacketPayload> sender;

        PacketTarget(java.util.function.Consumer<CustomPacketPayload> sender) {
            this.sender = sender;
        }

        public void send(CustomPacketPayload payload) {
            sender.accept(payload);
        }
    }

    /** Forge's sphere-around-a-point target: centre, radius, dimension, and a player to skip. */
    public static class TargetPoint {
        public final ServerPlayer excluded;
        public final double x;
        public final double y;
        public final double z;
        public final double r2;
        public final ResourceKey<Level> dim;

        public TargetPoint(ServerPlayer excluded, double x, double y, double z, double r2,
                           ResourceKey<Level> dim) {
            this.excluded = excluded;
            this.x = x;
            this.y = y;
            this.z = z;
            this.r2 = r2;
            this.dim = dim;
        }

        /** The overload 11 corpus jars use, with no excluded player. */
        public TargetPoint(double x, double y, double z, double r2, ResourceKey<Level> dim) {
            this(null, x, y, z, r2, dim);
        }

        public static TargetPoint p(double x, double y, double z, double r2, ResourceKey<Level> dim) {
            return new TargetPoint(x, y, z, r2, dim);
        }
    }
}
