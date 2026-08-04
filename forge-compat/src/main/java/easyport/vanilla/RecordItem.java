package easyport.vanilla;

import java.util.function.Supplier;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.Item;

/**
 * Relocated stand-in for {@code net.minecraft.world.item.RecordItem}, 13 corpus mods.
 *
 * 1.21 made music discs data-driven: the item is an ordinary {@code Item} carrying a
 * {@code JUKEBOX_PLAYABLE} component that points at a {@code JukeboxSong}, and the dedicated
 * class went away.
 *
 * Extending {@code Item} rather than being a bare class matters -- a mod's disc is registered as
 * an item and has to be one. So the disc registers, appears, and stacks; what it will not do is
 * play, because the jukebox now looks for a component this stand-in does not set. Setting one
 * would mean inventing a {@code JukeboxSong} registry entry per disc, which is a data-pack
 * question rather than a bytecode one.
 */
public class RecordItem extends Item {

    private final int analogOutput;
    private final Supplier<SoundEvent> sound;
    private final int lengthInTicks;

    public RecordItem(int analogOutput, Supplier<SoundEvent> sound, Properties properties,
                      int lengthInSeconds) {
        super(properties);
        this.analogOutput = analogOutput;
        this.sound = sound;
        this.lengthInTicks = lengthInSeconds * 20;
    }

    public RecordItem(int analogOutput, SoundEvent sound, Properties properties,
                      int lengthInSeconds) {
        this(analogOutput, () -> sound, properties, lengthInSeconds);
    }

    public int getAnalogOutput() {
        return analogOutput;
    }

    public SoundEvent getSound() {
        return sound.get();
    }

    public int getLengthInTicks() {
        return lengthInTicks;
    }

    public MutableComponent getDisplayName() {
        return Component.translatable(getDescriptionId() + ".desc");
    }

    /**
     * Forge's reverse lookup, from sound back to disc.
     *
     * Always empty. 1.20.1 kept a static map populated by every RecordItem constructed; the
     * lookup only ever made sense because vanilla's discs were in it, and under 1.21 they are not
     * RecordItems at all. Returning null is what the 1.20.1 method did for an unknown sound, so
     * callers already handle it.
     */
    public static RecordItem getBySound(SoundEvent sound) {
        return null;
    }
}
