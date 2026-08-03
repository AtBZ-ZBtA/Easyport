package net.minecraftforge.fml.loading;

import net.neoforged.api.distmarker.Dist;

/**
 * Shim for {@code FMLEnvironment}. NeoForge kept the type at the same path under its own
 * namespace, so this is a straight re-export.
 *
 * Fields rather than methods, matching Forge — mods read {@code FMLEnvironment.dist} directly
 * with a GETSTATIC, so exposing accessors instead would not resolve.
 *
 * Values are copied at class-initialisation time rather than resolved per read. These are
 * fixed for the lifetime of the process, and a {@code static final} copy lets the JIT fold
 * the dist check the same way the original did — mods use it on hot paths.
 */
public class FMLEnvironment {

    public static final Dist dist = net.neoforged.fml.loading.FMLEnvironment.dist;
    public static final boolean production = net.neoforged.fml.loading.FMLEnvironment.production;

    private FMLEnvironment() {}
}
