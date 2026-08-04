package easyport.bridge;

import net.minecraftforge.fml.config.ModConfig;

/**
 * Wraps the NeoForge {@code ModConfig} that a renamed config event hands back.
 *
 * {@code ModConfigEvent} and its {@code Loading}/{@code Reloading} subclasses are renamed rather
 * than shimmed, because the mod bus dispatches on them. Their {@code getConfig()} therefore
 * returns NeoForge's {@code ModConfig}, while the calling mod's bytecode expects Forge's -- the
 * class resolves, the call does not.
 *
 * {@code METHOD_TO_STATIC} moves the call site here. 34 + 33 + 28 corpus jars across the three
 * event types.
 */
public final class ConfigBridge {

    public static ModConfig getConfig(net.neoforged.fml.event.config.ModConfigEvent event) {
        return new ModConfig(event.getConfig());
    }

    private ConfigBridge() {}
}
