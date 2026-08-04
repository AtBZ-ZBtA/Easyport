package easyport.vanilla;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Relocated stand-in for {@code net.minecraft.core.particles.ParticleOptions$Deserializer}.
 *
 * 1.20.5 replaced the hand-written reader/writer pair with a {@code MapCodec} and a
 * {@code StreamCodec} on the particle type itself.
 *
 * A mod's custom particle still registers and still renders -- that comes from the particle type
 * and its provider, neither of which changed. What is lost is parsing a particle from command
 * text, which is the only thing this interface was consulted for.
 */
public interface ParticleOptionsDeserializer<T extends ParticleOptions> {

    T fromCommand(ParticleType<T> type, StringReader reader) throws CommandSyntaxException;

    T fromNetwork(ParticleType<T> type, FriendlyByteBuf buffer);
}
