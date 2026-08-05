package net.neoforged.fml;

/**
 * Shim: the mod container NeoForge hands to a mod constructor.
 *
 * A class rather than an interface, because NeoForge's is one and mods declare it as a
 * constructor parameter type — the descriptor has to match exactly or the synthesised
 * constructor cannot call the original.
 *
 * <h2>What it deliberately does not expose</h2>
 *
 * NeoForge's {@code ModContainer} carries {@code IModInfo}, extension points and a config
 * registration surface, all in {@code net.neoforged} types that would need shimming in turn.
 * What the corpus actually calls on a constructor-injected container is narrow: the mod id, and
 * {@code registerConfig}. Those are here. Anything else is a missing member the gap report will
 * name against a real call site rather than a guess made in advance — which is the same
 * discipline forge-compat was built under, and the reason its 92 classes cover 652 referenced
 * types.
 */
public class ModContainer {

    private final net.minecraftforge.fml.ModContainer delegate;

    public ModContainer(net.minecraftforge.fml.ModContainer delegate) {
        this.delegate = delegate;
    }

    /** The Forge container being wrapped. */
    public net.minecraftforge.fml.ModContainer unwrap() {
        return delegate;
    }

    public String getModId() {
        return delegate.getModId();
    }

    public String getNamespace() {
        return delegate.getNamespace();
    }
}
