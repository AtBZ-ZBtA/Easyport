package net.minecraftforge.common.capabilities;

import net.minecraftforge.common.util.LazyOptional;

/**
 * Shim for {@code Capability<T>}, Forge's capability token.
 *
 * Forge identified a capability by a singleton token that a provider was asked for directly:
 * {@code provider.getCapability(ForgeCapabilities.ITEM_HANDLER, side)}. NeoForge inverted the
 * relationship -- capabilities are looked up from the level or the stack
 * ({@code level.getCapability(cap, pos, side)}) and providers are registered against them.
 *
 * The token survives as a plain identity object here, carrying the NeoForge capability it stands
 * for. That is enough for the two things mods do with it: pass it to {@code getCapability}, and
 * compare it against the constants on {@link ForgeCapabilities}.
 *
 * A shim rather than a rename, deliberately. NeoForge has no {@code Capability} type at all --
 * {@code BlockCapability}, {@code ItemCapability} and {@code EntityCapability} are separate
 * classes with different lookup signatures -- so there is nothing a rename could target. See
 * the note in forward.rules.tsv on why the capabilities prefix rule was withdrawn.
 */
public class Capability<T> {

    private final String name;

    public Capability(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    /**
     * Forge's helper for the common "is this the capability I handle" branch, in 54 corpus jars.
     *
     * Returns the supplied value when the requested capability is this one, and an empty
     * LazyOptional otherwise -- which is exactly the body of most {@code getCapability}
     * overrides in the corpus.
     */
    public <R> LazyOptional<R> orEmpty(Capability<R> toCheck, LazyOptional<R> value) {
        return this == toCheck ? value : LazyOptional.empty();
    }

    @SuppressWarnings("unchecked")
    public <R> Capability<R> cast() {
        return (Capability<R>) this;
    }

    @Override
    public String toString() {
        return "Capability[" + name + "]";
    }
}
