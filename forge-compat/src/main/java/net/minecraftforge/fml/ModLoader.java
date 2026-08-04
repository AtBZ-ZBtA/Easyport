package net.minecraftforge.fml;

/**
 * Shim for {@code ModLoader}, converted from a TYPE_RENAME after that rename made things worse.
 *
 * NeoForge has a class at the same path, so renaming to it resolved cleanly — and then failed
 * at {@code ModLoader.isDataGenRunning()}, which NeoForge does not have. That is precisely the
 * risk the rule file recorded when these 1:1 loader utilities were renamed rather than shimmed:
 * a rename fixes the class and cannot fix a signature.
 *
 * Worth noting the rename was actively harmful here. Before it, geckolib loaded — the missing
 * class sat on a path that never executed. Renaming made the class resolve, so the call was
 * reached, and the mod died on the method instead. A shim adapts where a rename cannot.
 *
 * NeoForge moved the datagen check to {@link net.neoforged.neoforge.data.loading.DatagenModLoader},
 * so that is where this forwards.
 */
public class ModLoader {

    public static boolean isDataGenRunning() {
        return net.neoforged.neoforge.data.loading.DatagenModLoader.isRunningDataGen();
    }

    public static ModLoader get() {
        return INSTANCE;
    }

    private static final ModLoader INSTANCE = new ModLoader();

    private ModLoader() {}
}
