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

    /**
     * Container lookup, called by 66 corpus jars -- mostly to read another mod's version string
     * for a compatibility check.
     *
     * Returns NeoForge's {@code ModContainer}, which the rules already rename Forge's references
     * to, so the types line up at the call site. Empty rather than null when FML has not built
     * the list yet: a mod calling this during construction is asking a question that has no
     * answer, and Forge's Optional return is the right shape for saying so.
     */
    public java.util.Optional<net.neoforged.fml.ModContainer> getModContainerById(String modId) {
        net.neoforged.fml.ModList list = delegate();
        if (list == null) return java.util.Optional.empty();
        // NeoForge returns Optional<? extends ModContainer>; the wildcard capture will not
        // convert to Optional<ModContainer> in a conditional, and the erased descriptor is
        // Optional either way.
        return java.util.Optional.ofNullable(list.getModContainerById(modId).orElse(null));
    }

    public java.util.Optional<net.neoforged.neoforgespi.language.IModFileInfo> getModFileById(String modId) {
        net.neoforged.fml.ModList list = delegate();
        return java.util.Optional.ofNullable(list == null ? null : list.getModFileById(modId));
    }

    public java.util.List<net.neoforged.neoforgespi.language.IModInfo> getMods() {
        net.neoforged.fml.ModList list = delegate();
        return list == null ? java.util.List.of() : list.getMods();
    }

    /**
     * Annotation scan data, 17 jars.
     *
     * Used to find every class carrying a mod's own annotation -- a plugin-discovery idiom. The
     * shape survived the fork intact, so this forwards.
     */
    public java.util.List<net.neoforged.neoforgespi.language.ModFileScanData> getAllScanData() {
        net.neoforged.fml.ModList list = delegate();
        return list == null ? java.util.List.of() : list.getAllScanData();
    }

    private ModList() {}
}
