package translationlayer.shimtest;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModList;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

/**
 * Phase 0 spike: proves forge-compat shims actually link and run inside a loaded mod, under
 * NeoForge's real module layers.
 *
 * Compiling against the shims only proves the signatures line up. This mod exercises all
 * three shim shapes at mod-construction time, so a linkage failure (NoClassDefFoundError, a
 * split-package rejection, or a module-layer visibility problem) surfaces as a mod-loading
 * crash rather than passing silently.
 *
 * Deliberately loud: it prints what it resolved, because a shim that returns null without
 * throwing would otherwise look identical to success.
 */
@Mod(ShimTestMod.MODID)
public class ShimTestMod {

    public static final String MODID = "shimtest";

    public ShimTestMod(IEventBus modEventBus) {
        System.out.println("[shimtest] ==== forge-compat linkage check ====");

        // Shape 1: static field alias. Must be the *same* instance NeoForge hands out.
        IEventBus forgeBus = MinecraftForge.EVENT_BUS;
        System.out.println("[shimtest] shape 1 alias      : MinecraftForge.EVENT_BUS = " + forgeBus);
        System.out.println("[shimtest] shape 1 identity   : same as NeoForge.EVENT_BUS? "
                + (forgeBus == net.neoforged.neoforge.common.NeoForge.EVENT_BUS));

        // Shape 2: static-method delegation through a wrapped singleton.
        boolean selfLoaded = ModList.get().isLoaded(MODID);
        System.out.println("[shimtest] shape 2 delegation : ModList.isLoaded(\"" + MODID + "\") = " + selfLoaded);

        // Shape 3: instance delegation with self-return. The chain below only compiles and
        // runs if every builder method hands back the shim type rather than the delegate's.
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        ForgeConfigSpec.BooleanValue flag = builder
                .comment("shim linkage probe")
                .push("probe")
                .define("enabled", true);
        builder.pop();
        ForgeConfigSpec spec = builder.build();
        System.out.println("[shimtest] shape 3 chaining   : built spec = " + (spec != null));
        System.out.println("[shimtest] shape 3 unwrap     : delegate = " + spec.unwrap());
        System.out.println("[shimtest] shape 3 value path : " + flag.getPath());

        System.out.println("[shimtest] ==== all three shim shapes linked OK ====");
    }
}
