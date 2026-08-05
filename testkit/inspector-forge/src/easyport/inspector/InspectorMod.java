package easyport.inspector;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * The Forge 1.20.1 half of the verification harness: dumps what every loaded mod registered.
 *
 * Deliberately the same probe as {@code testkit/inspector}, expressed in Forge's API and writing
 * the identical JSON, because the point is to compare a backward-translated mod against a
 * reference the *forward* harness measured the same way. A probe that reported differently would
 * make the two directions incomparable for reasons that have nothing to do with translation.
 *
 * "The jar loaded without crashing" is a weak signal, and it is exactly where the backward
 * direction has been stuck: a mod can construct cleanly and still register half its blocks. This
 * turns that into a count.
 *
 * Walks the registry-of-registries rather than a hardcoded list, so registries that differ between
 * 1.20.1 and 1.21.1 are picked up without this file knowing about them — which matters more here
 * than forward, since the whole question is what the older game does and does not have.
 *
 * Output: easyport-inspection.json in the game directory.
 *   { "minecraft:block": ["modid:foo", ...], ... }
 *
 * Vanilla content is excluded unless {@code -Deasyport.inspect.includeVanilla=true}.
 */
@Mod(InspectorMod.MODID)
public class InspectorMod {

    public static final String MODID = "easyport_inspector";
    private static final Logger LOGGER = LoggerFactory.getLogger(InspectorMod.class);
    private static final String OUTPUT = "easyport-inspection.json";

    /** Several lifecycle events can arrive; whichever lands first wins and the rest no-op. */
    private static final AtomicBoolean DUMPED = new AtomicBoolean(false);

    /**
     * No-argument, because Forge 1.20.1 constructs mods that way and fetches the bus from static
     * context. This is the same asymmetry the transformer's MOD_CTOR_INJECTED pass exists to
     * paper over for translated mods — here it is simply written by hand, since this probe is
     * native to 1.20.1 rather than translated into it.
     */
    public InspectorMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        // Registries are populated by common setup, but a datagen run may never reach it. Forge
        // 1.20.1's bus has no two-argument addListener, so the priority and cancellation flags
        // are spelled out.
        modBus.addListener(EventPriority.NORMAL, false, FMLCommonSetupEvent.class,
                           e -> dump("common-setup"));
        modBus.addListener(EventPriority.NORMAL, false, FMLLoadCompleteEvent.class,
                           e -> dump("load-complete"));
        modBus.addListener(EventPriority.NORMAL, false,
                           net.minecraftforge.data.event.GatherDataEvent.class,
                           e -> dump("gather-data"));
    }

    private void dump(String trigger) {
        if (!DUMPED.compareAndSet(false, true)) return;

        boolean includeVanilla = Boolean.getBoolean("easyport.inspect.includeVanilla");
        Map<String, TreeSet<String>> byRegistry = new TreeMap<>();

        for (var entry : BuiltInRegistries.REGISTRY.entrySet()) {
            String registryName = entry.getKey().location().toString();
            Registry<?> registry = entry.getValue();

            TreeSet<String> ids = new TreeSet<>();
            for (ResourceLocation id : registry.keySet()) {
                if (!includeVanilla && id.getNamespace().equals("minecraft")) continue;
                ids.add(id.toString());
            }
            if (!ids.isEmpty()) byRegistry.put(registryName, ids);
        }

        // Which mods actually loaded. Without this the harness cannot tell "the candidate loaded
        // and registered nothing" from "the candidate was rejected outright" -- both look like an
        // empty delta and they need very different fixes.
        TreeSet<String> loadedMods = new TreeSet<>();
        ModList.get().getMods().forEach(mi -> loadedMods.add(mi.getModId()));

        Path out = FMLPaths.GAMEDIR.get().resolve(OUTPUT);
        try {
            Files.writeString(out, toJson(byRegistry, trigger, loadedMods), StandardCharsets.UTF_8);
            int total = byRegistry.values().stream().mapToInt(TreeSet::size).sum();
            LOGGER.info("[easyport-inspector] wrote {} entries across {} registries to {}",
                        total, byRegistry.size(), out);
            System.out.println("[easyport-inspector] OK " + total + " entries, "
                             + byRegistry.size() + " registries, trigger=" + trigger);
        } catch (IOException e) {
            LOGGER.error("[easyport-inspector] failed to write {}", out, e);
            System.out.println("[easyport-inspector] FAILED " + e);
        }
    }

    /** Hand-rolled so the probe carries no dependency beyond what the game already provides. */
    private static String toJson(Map<String, TreeSet<String>> data, String trigger,
                                 TreeSet<String> loadedMods) {
        StringBuilder sb = new StringBuilder("{\n");
        sb.append("  \"_trigger\": \"").append(trigger).append("\",\n");

        List<String> quotedMods = new ArrayList<>();
        for (String m : loadedMods) quotedMods.add("\"" + escape(m) + "\"");
        sb.append("  \"loadedMods\": [").append(String.join(", ", quotedMods)).append("],\n");

        sb.append("  \"registries\": {\n");

        List<String> blocks = new ArrayList<>();
        for (var e : data.entrySet()) {
            StringBuilder b = new StringBuilder();
            b.append("    \"").append(escape(e.getKey())).append("\": [");
            List<String> quoted = new ArrayList<>();
            for (String id : e.getValue()) quoted.add("\"" + escape(id) + "\"");
            b.append(String.join(", ", quoted)).append(']');
            blocks.add(b.toString());
        }
        sb.append(String.join(",\n", blocks)).append("\n  }\n}\n");
        return sb.toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
