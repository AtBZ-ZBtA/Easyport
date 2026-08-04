package easyport.bridge;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * Supplies the codec arguments 1.21 added to signatures that used to take hand-written
 * readers, for {@code ARG_FILL} rules.
 *
 * 1.20.5 moved serialization wholesale onto codecs, and the commonest shape of that in the corpus
 * is a constructor that gained one: {@code ParticleType(boolean, Deserializer)} became
 * {@code ParticleType(boolean, MapCodec, StreamCodec)}. A mod compiled against the old signature
 * has nothing to pass, because in 1.20.1 there was nothing to pass.
 *
 * <h2>These are placeholders and are meant to look like it</h2>
 *
 * A real codec would have to be derived from the mod's own reader and writer, which is the same
 * unsolved problem as the loot and condition serializers. What these give back is enough for the
 * *registration* to succeed -- so the mod's particle type exists, its class loads, and everything
 * registered alongside it survives -- and not enough to move one over the network or parse one
 * from a command.
 *
 * That trade is the right way round. The alternative is a mod that fails to load at all, taking
 * with it every block and item it also registers, and the translate report names each use.
 */
public final class CodecBridge {

    private CodecBridge() {}

    /** A codec that reads nothing and writes nothing, for a type Easyport cannot reconstruct. */
    public static MapCodec<ParticleOptions> particleCodec() {
        return MapCodec.unit(() -> null);
    }

    /**
     * The codec standing in for a mod's hand-written particle deserializer.
     *
     * Takes the deserializer and ignores it. It is here rather than as a plain no-argument filler
     * because the old signature *had* that argument, so the conversion happens where the value
     * already is -- and because a rule that names both types documents which one replaced which.
     */
    public static MapCodec<ParticleOptions> particleCodecFrom(
            easyport.vanilla.ParticleOptionsDeserializer<?> deserializer) {
        return particleCodec();
    }

    public static StreamCodec<RegistryFriendlyByteBuf, ParticleOptions> particleStreamCodec() {
        return StreamCodec.unit(null);
    }
}
