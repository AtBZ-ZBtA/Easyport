package net.neoforged.fml;

import java.util.Optional;

/**
 * Shim: NeoForge's mod list.
 *
 * Fourth on the backward shim list — {@code get()} is named by 256 corpus jars and
 * {@code isLoaded} by 216, almost always to decide whether to run a compatibility path for
 * another mod. Cheap to shim because Forge 1.20.1's list is the same idea under the same name.
 *
 * <h2>Scoped to what the corpus calls, and no further</h2>
 *
 * {@code isLoaded}, {@code getModContainerById} and {@code getMods} between them cover 216, 96 and
 * 26 jars. {@code getAllScanData} and {@code getModFileById} return {@code neoforgespi} types
 * whose 1.20.1 counterparts live under {@code forgespi} with different members, so they would
 * each need a shim of their own; 21 and 25 jars call them, and they are left to be named by the
 * report against a real call site rather than approximated now. That is the same discipline
 * forge-compat was built under.
 */
public class ModList {

    private static final ModList INSTANCE = new ModList();

    public static ModList get() {
        return INSTANCE;
    }

    /**
     * Resolved per call rather than cached: Forge's own {@code ModList.get()} is null until mod
     * loading begins, so capturing it in a static initialiser would pin null forever. The forward
     * shim learned this the same way.
     */
    private net.minecraftforge.fml.ModList delegate() {
        return net.minecraftforge.fml.ModList.get();
    }

    public boolean isLoaded(String modTarget) {
        net.minecraftforge.fml.ModList list = delegate();
        return list != null && list.isLoaded(modTarget);
    }

    public int size() {
        net.minecraftforge.fml.ModList list = delegate();
        return list == null ? 0 : list.size();
    }

    /**
     * Returns the *shimmed* container, because that is the type a translated mod's variable is
     * declared as. Handing back Forge's would link and then fail on the first call.
     */
    public Optional<ModContainer> getModContainerById(String modId) {
        net.minecraftforge.fml.ModList list = delegate();
        if (list == null) return Optional.empty();
        return list.getModContainerById(modId).map(easyport.neobridge.ModContainerShim::new);
    }

    /** Mod metadata is a forgespi type on both sides and needs no wrapping. */
    public java.util.List<net.minecraftforge.forgespi.language.IModInfo> getMods() {
        net.minecraftforge.fml.ModList list = delegate();
        return list == null ? java.util.List.of() : list.getMods();
    }
}
