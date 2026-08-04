package net.minecraftforge.common.capabilities;

/**
 * Shim for {@code CapabilityToken<T>}.
 *
 * A pure type token — mods subclass it anonymously ({@code new CapabilityToken<IItemHandler>(){}})
 * purely so Forge could recover the erased generic parameter at runtime. It carried no
 * behaviour, so reproducing the shape is enough for the subclass to compile and link.
 *
 * NeoForge dropped the capability system this belonged to and replaced it with
 * {@code BlockCapability} / {@code ItemCapability} / {@code EntityCapability}, which take the
 * type explicitly and need no token. Nothing to delegate to, so this stands alone.
 *
 * Loading only. A mod that resolves capabilities through the old {@code CapabilityManager} will
 * link and then not find anything registered, because nothing populates that side. Bridging the
 * two capability models is Phase 3's hard shim and is not done — the roadmap has always called
 * capabilities out as a different lifecycle model rather than a rename.
 */
public abstract class CapabilityToken<T> {
}
