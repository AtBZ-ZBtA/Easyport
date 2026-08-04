package net.minecraftforge.event;

import net.neoforged.bus.api.Event;

/**
 * Shim for {@code TickEvent}, restructured out of existence in 1.21.
 *
 * Forge had one class carrying a {@code phase} field; NeoForge split it into separate
 * {@code Pre} and {@code Post} classes under {@code neoforge.event.tick}. Neither a rename nor
 * an ordinary shim covers that: the bus dispatches by the posted object's exact class, so a
 * mod listening for {@code TickEvent.ClientTickEvent} can only ever be reached by an object of
 * that class.
 *
 * <h2>Event bridging</h2>
 *
 * So forge-compat posts one. {@link TickEventBridge} subscribes to NeoForge's tick events and
 * re-posts a Forge-shaped equivalent on the same bus, with {@code phase} set from which of
 * Pre/Post fired. Mods keep their original listeners and receive events that match.
 *
 * This generalises to every restructured event, and it is the only approach that preserves
 * behaviour. The alternative considered was renaming {@code ClientTickEvent} straight to
 * {@code ClientTickEvent$Post} — cheap, and silently wrong for any mod that listened on START,
 * which would begin firing at the wrong point in the tick with no error anywhere.
 *
 * Extends NeoForge's {@code Event} so the bus will carry it. That is the same trick as the
 * IEventBus shim: be a valid platform type while presenting the Forge-facing shape.
 */
public abstract class TickEvent extends Event {

    public enum Phase { START, END }

    public enum Type { LEVEL, PLAYER, CLIENT, SERVER, RENDER }

    public final Type type;
    public final Phase phase;

    protected TickEvent(Type type, Phase phase) {
        this.type = type;
        this.phase = phase;
    }

    public static class ClientTickEvent extends TickEvent {
        public ClientTickEvent(Phase phase) {
            super(Type.CLIENT, phase);
        }
    }

    public static class ServerTickEvent extends TickEvent {
        public ServerTickEvent(Phase phase) {
            super(Type.SERVER, phase);
        }
    }

    public static class LevelTickEvent extends TickEvent {
        public final net.minecraft.world.level.Level level;

        public LevelTickEvent(Phase phase, net.minecraft.world.level.Level level) {
            super(Type.LEVEL, phase);
            this.level = level;
        }
    }

    public static class PlayerTickEvent extends TickEvent {
        public final net.minecraft.world.entity.player.Player player;

        public PlayerTickEvent(Phase phase, net.minecraft.world.entity.player.Player player) {
            super(Type.PLAYER, phase);
            this.player = player;
        }
    }
}
