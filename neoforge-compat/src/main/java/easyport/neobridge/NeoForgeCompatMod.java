package easyport.neobridge;

import net.minecraftforge.fml.common.Mod;

/**
 * Makes neoforge-compat a mod Forge 1.20.1 will accept.
 *
 * Forge is stricter than NeoForge here, and the difference is worth knowing before it costs a
 * launch: NeoForge accepts a jar whose descriptor declares a mod with no {@code @Mod} class and
 * simply lists it, while Forge 1.20.1 treats it as fatal — *"constructed 0 mods: [], but had 1
 * mods specified"* — and takes the whole launch down. forge-compat.jar gets away with declaring
 * one because nothing on the NeoForge side checks.
 *
 * So this class exists only to be found. The shim layer is a library: it has no lifecycle, no
 * registrations and nothing to do during mod loading.
 */
@Mod(NeoForgeCompatMod.MOD_ID)
public class NeoForgeCompatMod {

    public static final String MOD_ID = "neoforge_compat";

    public NeoForgeCompatMod() {
        // Deliberately empty. See the class comment.
    }
}
