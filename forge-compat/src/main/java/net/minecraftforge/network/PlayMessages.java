package net.minecraftforge.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;

/**
 * Shim for {@code PlayMessages}, whose {@code SpawnEntity} 3 corpus mods reference.
 *
 * Forge shipped its own entity-spawn packet because vanilla's could not carry a mod's extra spawn
 * data. 1.20.5 folded that capability into vanilla's own packet, so NeoForge deleted the class
 * outright and mods no longer need one -- {@code IEntityWithComplexSpawn} covers the same ground,
 * and forge-compat already bridges Forge's interface onto it.
 *
 * <h2>Link-only, deliberately</h2>
 *
 * This is the shape the event work settled on for anything NeoForge *removed*: supply the type so
 * the referencing class loads, and let it never be constructed, because nothing exists to
 * construct it. A mod reaches this class through {@code NetworkHooks.getEntitySpawningPacket},
 * which forge-compat already answers with vanilla's packet — so by the time these accessors could
 * be called, the real spawn has already happened by the supported route.
 *
 * The accessors return zeroes rather than throwing. A mod that reads one is on a path Easyport
 * has already redirected, and a quiet zero there is preferable to an exception thrown from inside
 * a packet handler, where it would kill the connection rather than the feature.
 */
public final class PlayMessages {

    private PlayMessages() {}

    public static final class SpawnEntity {

        private final FriendlyByteBuf additionalData = new FriendlyByteBuf(
                io.netty.buffer.Unpooled.buffer());

        public static void encode(SpawnEntity message, FriendlyByteBuf buffer) {}

        public static SpawnEntity decode(FriendlyByteBuf buffer) {
            return new SpawnEntity();
        }

        public static void handle(SpawnEntity message, Supplier<?> context) {}

        public Entity getEntity() {
            return null;
        }

        public int getTypeId() {
            return 0;
        }

        public int getEntityId() {
            return 0;
        }

        public java.util.UUID getUuid() {
            return net.minecraft.Util.NIL_UUID;
        }

        public double getPosX() {
            return 0.0D;
        }

        public double getPosY() {
            return 0.0D;
        }

        public double getPosZ() {
            return 0.0D;
        }

        public byte getPitch() {
            return 0;
        }

        public byte getYaw() {
            return 0;
        }

        public byte getHeadYaw() {
            return 0;
        }

        public int getVelX() {
            return 0;
        }

        public int getVelY() {
            return 0;
        }

        public int getVelZ() {
            return 0;
        }

        public FriendlyByteBuf getAdditionalData() {
            return additionalData;
        }
    }
}
