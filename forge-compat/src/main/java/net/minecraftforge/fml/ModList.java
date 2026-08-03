package net.minecraftforge.fml;

/**
 * Shim shape 2 of 3: static-method delegation.
 *
 * The Forge type is a singleton reached through a static accessor. The shim wraps the
 * NeoForge instance rather than aliasing it, because the two types are unrelated as far as
 * the JVM is concerned even though their surfaces match.
 *
 * Used by 146 of the 288 corpus mods, sixth on the shim work list.
 */
public class ModList {

    private static final ModList INSTANCE = new ModList();

    private final net.neoforged.fml.ModList delegate() {
        // Resolved per call rather than cached: NeoForge's own ModList.get() is null until mod
        // loading begins, so capturing it in a static initialiser would pin null forever.
        return net.neoforged.fml.ModList.get();
    }

    public static ModList get() {
        return INSTANCE;
    }

    public boolean isLoaded(String modTarget) {
        net.neoforged.fml.ModList list = delegate();
        return list != null && list.isLoaded(modTarget);
    }

    public int size() {
        net.neoforged.fml.ModList list = delegate();
        return list == null ? 0 : list.size();
    }

    private ModList() {}
}
