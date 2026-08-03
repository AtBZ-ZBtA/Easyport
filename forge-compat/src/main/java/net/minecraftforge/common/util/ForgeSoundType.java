package net.minecraftforge.common.util;

import java.util.function.Supplier;

import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.common.util.DeferredSoundType;

/**
 * Shim for {@code ForgeSoundType}, renamed to {@code DeferredSoundType} in NeoForge.
 *
 * Subclassing rather than delegating, because {@code SoundType} is a concrete vanilla class
 * that blocks and block properties accept directly. A wrapper would not be assignable where
 * mods pass this, so the shim has to *be* one — which works out cleanly here since NeoForge's
 * replacement keeps the same constructor shape and also extends SoundType.
 */
public class ForgeSoundType extends DeferredSoundType {

    public ForgeSoundType(float volume, float pitch,
                          Supplier<SoundEvent> breakSound,
                          Supplier<SoundEvent> stepSound,
                          Supplier<SoundEvent> placeSound,
                          Supplier<SoundEvent> hitSound,
                          Supplier<SoundEvent> fallSound) {
        super(volume, pitch, breakSound, stepSound, placeSound, hitSound, fallSound);
    }
}
