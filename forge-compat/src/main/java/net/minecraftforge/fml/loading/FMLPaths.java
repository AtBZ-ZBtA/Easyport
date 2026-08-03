package net.minecraftforge.fml.loading;

import java.nio.file.Path;

/**
 * Shim for {@code FMLPaths}. NeoForge kept the type unchanged under its own namespace.
 *
 * Reproduced as an enum because that is what Forge declared and mods read the constants with
 * GETSTATIC — a holder class exposing equivalent fields would not resolve against their
 * descriptors.
 *
 * Enums cannot be aliased or forwarded in Java, so each constant carries its NeoForge
 * counterpart and delegates. That is the same shape as the {@code ModConfig.Type} shim, and it
 * recurs whenever Forge exposed an enum.
 */
public enum FMLPaths {

    GAMEDIR(net.neoforged.fml.loading.FMLPaths.GAMEDIR),
    MODSDIR(net.neoforged.fml.loading.FMLPaths.MODSDIR),
    CONFIGDIR(net.neoforged.fml.loading.FMLPaths.CONFIGDIR),
    FMLCONFIG(net.neoforged.fml.loading.FMLPaths.FMLCONFIG);

    private final net.neoforged.fml.loading.FMLPaths delegate;

    FMLPaths(net.neoforged.fml.loading.FMLPaths delegate) {
        this.delegate = delegate;
    }

    public Path relative() {
        return delegate.relative();
    }

    public Path get() {
        return delegate.get();
    }

    public static Path getOrCreateGameRelativePath(Path path) {
        return net.neoforged.fml.loading.FMLPaths.getOrCreateGameRelativePath(path);
    }
}
