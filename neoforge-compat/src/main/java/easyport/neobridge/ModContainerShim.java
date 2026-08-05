package easyport.neobridge;

/**
 * Builds the {@code net.neoforged.fml.ModContainer} shim around a Forge 1.20.1 container.
 *
 * Separate from the shim class itself so the shim's constructor stays the one a translated mod
 * would call, and so {@code ModCtorBridge} has a name to invoke that is obviously ours rather
 * than obviously NeoForge's.
 */
public final class ModContainerShim extends net.neoforged.fml.ModContainer {

    public ModContainerShim(net.minecraftforge.fml.ModContainer delegate) {
        super(delegate);
    }
}
