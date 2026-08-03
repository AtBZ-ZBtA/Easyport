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
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.neoforged.neoforge.data.event.GatherDataEvent;

/**
 * Verification harness probe: dumps what every loaded mod actually registered.
 *
 * "The jar loaded without crashing" is a weak signal. A translated mod can load cleanly and
 * still be broken — half its blocks missing, an entity type silently dropped. This records
 * the real registry contents so a translated jar can be compared against the author's own
 * port entry by entry, which is what turns coverage into a number instead of a hunch.
 *
 * Walks the registry-of-registries rather than a hardcoded list, so registries added or
 * renamed between versions are picked up without touching this file.
 *
 * Output: easyport-inspection.json in the game directory.
 *   { "minecraft:block": ["modid:foo", ...], ... }
 *
 * Vanilla content is excluded by default — it is identical on both sides and would bury the
 * mod content being measured. Set -Deasyport.inspect.includeVanilla=true to keep it.
 */
@Mod(InspectorMod.MODID)
public class InspectorMod {

    public static final String MODID = "easyport_inspector";
    private static final Logger LOGGER = LoggerFactory.getLogger(InspectorMod.class);
    private static final String OUTPUT = "easyport-inspection.json";

    /** Several lifecycle events can arrive; whichever lands first wins and the rest no-op. */
    private static final AtomicBoolean DUMPED = new AtomicBoolean(false);

    public InspectorMod(IEventBus modBus, ModContainer container) {
        // Registries are populated by common setup, but datagen runs may never reach it, and a
        // client/server run may never fire GatherDataEvent. Listening to all three means the
        // harness works under any run configuration without needing to know which it is.
        modBus.addListener(FMLCommonSetupEvent.class, e -> dump("common-setup"));
        modBus.addListener(FMLLoadCompleteEvent.class, e -> dump("load-complete"));
        modBus.addListener(GatherDataEvent.class, e -> dump("gather-data"));
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

        // Which mods actually loaded. Without this the harness cannot distinguish "the
        // candidate loaded and registered nothing" from "the candidate was rejected outright"
        // -- both look like an empty registry delta, and they need very different fixes.
        TreeSet<String> loadedMods = new TreeSet<>();
        net.neoforged.fml.ModList.get().getMods().forEach(mi -> loadedMods.add(mi.getModId()));

        Path out = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve(OUTPUT);
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
