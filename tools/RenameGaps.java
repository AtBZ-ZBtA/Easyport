import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reports every Forge type the corpus uses that neither a shim nor a rename currently resolves.
 *
 * <h2>Why offline</h2>
 *
 * The same answer was previously obtained by launching the game, reading the first
 * ClassNotFoundException, fixing it, and launching again -- roughly ten minutes per missing
 * class, discovered strictly one at a time because the JVM stops at the first one. Architectury
 * alone walked through TickEvent, TextureStitchEvent and EntityItemPickupEvent that way.
 *
 * Every input to that answer is static: which types the corpus references (from MemberScan),
 * which types the target platform has (from its jars), which types forge-compat supplies, and
 * what the rules rewrite. Computing it directly turns a serial hunt into one list, ranked by how
 * many jars each gap blocks.
 *
 * <h2>What counts as resolved</h2>
 *
 * A referenced type is fine if forge-compat ships it, or if a rule maps it to a type the
 * platform actually has. A prefix rule that produces a non-existent target is *not* resolved --
 * that is precisely the TextureStitchEvent case, where the rename looks plausible, the
 * transformer's existence check correctly refuses it, and the original name then fails at load.
 *
 * Usage:
 *   java tools/RenameGaps.java <member-scan-output> <rules.tsv> <forge-compat.jar> <platform.jar>...
 */
public class RenameGaps {

    public static void main(String[] args) throws IOException {
        if (args.length < 4) {
            System.err.println("usage: RenameGaps <scan.txt> <rules.tsv> <forge-compat.jar> <platform.jar>...");
            System.exit(2);
        }

        Map<String, Integer> referenced = readScan(Path.of(args[0]));
        Rules rules = readRules(Path.of(args[1]));

        Set<String> shimmed = classesIn(Path.of(args[2]));
        Set<String> platform = new HashSet<>();
        Set<String> abstractTypes = new HashSet<>();
        for (int i = 3; i < args.length; i++) {
            Path jar = Path.of(args[i]);
            platform.addAll(classesIn(jar));
            abstractTypes.addAll(abstractClassesIn(jar));
        }

        // Simple name -> platform classes carrying it, for suggesting rename targets.
        Map<String, List<String>> bySimpleName = new LinkedHashMap<>();
        for (String c : platform) {
            bySimpleName.computeIfAbsent(simpleName(c), k -> new ArrayList<>()).add(c);
        }

        List<String> gaps = new ArrayList<>();
        List<String> shadowed = new ArrayList<>();
        List<String> abstractTargets = new ArrayList<>();
        int resolvedShim = 0, resolvedRename = 0;

        for (var entry : referenced.entrySet()) {
            String type = entry.getKey();
            String target = rules.apply(type);

            // Rules are tested before shims because that is the order Translate uses: its
            // remapper rewrites the name and never consults forge-compat. Checking shims first
            // here would report a comfortable picture of a transformer doing something else.
            if (target != null && platform.contains(target)) {
                resolvedRename++;
                // A rename that lands on a real platform class silently wins over a shim for the
                // same type. Sometimes that is the intent; sometimes it means a broadly-scoped
                // prefix rule has quietly disabled a shim written to paper over a signature
                // difference, and the failure appears later as NoSuchMethodError.
                if (shimmed.contains(type)) {
                    shadowed.add(String.format("%5d  %s  -> %s", entry.getValue(), type, target));
                }
                // The quietest way a rename can be wrong: NeoForge split several concrete Forge
                // events into Pre/Post pairs and kept the old name as an abstract parent. The
                // rename resolves, the mod loads, the listener registers -- and nothing ever
                // posts that class, so it never fires and nothing reports it. LivingDamageEvent
                // is exactly this, reached by a prefix rule added here.
                //
                // Narrowed to abstract targets that *have* a Pre or Post nested class. Flagging
                // every abstract target instead produced 91 hits, nearly all of them ordinary
                // interfaces like IItemHandler that are entirely correct targets -- a warning
                // list that size gets skimmed and then ignored, which is worse than none.
                if (abstractTypes.contains(target)
                        && (platform.contains(target + "$Pre") || platform.contains(target + "$Post"))) {
                    abstractTargets.add(String.format("%5d  %s  -> %s  (has Pre/Post)",
                            entry.getValue(), type, target));
                }
                continue;
            }

            if (shimmed.contains(type)) { resolvedShim++; continue; }

            String note = target != null ? "rule -> " + target + " (MISSING)" : "no shim, no rule";
            gaps.add(String.format("%5d  %-72s  %s%s",
                    entry.getValue(), type, note, suggest(type, bySimpleName)));
        }

        // Sorting by the leading count keeps the ranking; the field is width-padded so a plain
        // string compare would order 100 before 99.
        gaps.sort((a, b) -> Integer.parseInt(b.substring(0, 5).trim())
                          - Integer.parseInt(a.substring(0, 5).trim()));

        System.out.println("referenced Forge types: " + referenced.size());
        System.out.println("  resolved by forge-compat: " + resolvedShim);
        System.out.println("  resolved by a rule:       " + resolvedRename);
        System.out.println("  UNRESOLVED:               " + gaps.size());
        if (!shadowed.isEmpty()) {
            System.out.println();
            System.out.println("=== SHIMS SHADOWED BY A RULE (" + shadowed.size() + ") ===");
            System.out.println("A rule redirects these to a real platform class, so forge-compat's");
            System.out.println("version is never reached. Check each is deliberate.");
            shadowed.forEach(System.out::println);
        }
        if (!abstractTargets.isEmpty()) {
            System.out.println();
            System.out.println("=== RENAMES ONTO AN ABSTRACT TYPE (" + abstractTargets.size() + ") ===");
            System.out.println("These resolve and then do nothing if the type is an event: the bus");
            System.out.println("dispatches by exact class and nothing posts an abstract one. Check");
            System.out.println("each against the Pre/Post pair that replaced it.");
            abstractTargets.forEach(System.out::println);
        }
        System.out.println();
        System.out.println("=== UNRESOLVED ===");
        gaps.forEach(System.out::println);
    }

    /**
     * The class's own name, keeping nesting: {@code a/b/Outer$Inner} -> {@code Outer$Inner}.
     *
     * Nesting is kept deliberately. Dropping it would match {@code TickEvent$Phase} against every
     * unrelated {@code Phase} in the platform, and the suggestions are only useful if they are
     * mostly right -- a list padded with plausible-looking wrong answers is worse than no list,
     * because each one still costs a verify cycle to disprove.
     */
    private static String simpleName(String internalName) {
        int slash = internalName.lastIndexOf('/');
        return slash < 0 ? internalName : internalName.substring(slash + 1);
    }

    /**
     * Candidate rename targets, found by matching the class's own name against the platform.
     *
     * Catches the large class of types that only moved package -- which the corpus is full of,
     * since NeoForge reorganised namespaces wholesale without renaming much. It does not catch
     * types that were also *renamed* ({@code TextureStitchEvent$Post} became
     * {@code TextureAtlasStitchedEvent}), and it cannot tell a real move from a coincidence.
     * These are leads to check, not rules to paste.
     */
    private static String suggest(String type, Map<String, List<String>> bySimpleName) {
        List<String> candidates = bySimpleName.get(simpleName(type));
        if (candidates == null || candidates.isEmpty()) return "";
        return "  ?= " + String.join(" | ", candidates.size() > 3
                ? candidates.subList(0, 3) : candidates);
    }

    /** Pulls the "<count>  <internal/name>" lines out of a MemberScan TYPES section. */
    private static Map<String, Integer> readScan(Path p) throws IOException {
        Map<String, Integer> out = new LinkedHashMap<>();
        boolean inTypes = false;
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            if (line.startsWith("=== TYPES")) { inTypes = true; continue; }
            if (line.startsWith("=== MEMBERS")) break;
            if (!inTypes) continue;
            String t = line.trim();
            if (t.isEmpty()) continue;
            int sp = t.indexOf(' ');
            if (sp < 0) continue;
            try {
                out.put(t.substring(sp).trim(), Integer.parseInt(t.substring(0, sp)));
            } catch (NumberFormatException ignored) {
                // header or stray line
            }
        }
        return out;
    }

    private record Rules(Map<String, String> exact, Map<String, String> prefix,
                         Set<String> removed) {
        /** The rewritten name, or null when no rule applies. */
        String apply(String type) {
            if (removed.contains(type)) return null;
            String hit = exact.get(type);
            if (hit != null) return hit;
            for (var e : prefix.entrySet()) {
                if (type.startsWith(e.getKey())) {
                    return e.getValue() + type.substring(e.getKey().length());
                }
            }
            return null;
        }
    }

    private static Rules readRules(Path p) throws IOException {
        Map<String, String> exact = new LinkedHashMap<>();
        Map<String, String> prefix = new LinkedHashMap<>();
        Set<String> removed = new HashSet<>();
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] c = line.split("\t");
            if (c.length < 2) continue;
            switch (c[0]) {
                case "TYPE_RENAME" -> { if (c.length >= 3) exact.put(c[1], c[2]); }
                case "TYPE_PREFIX_RENAME" -> { if (c.length >= 3) prefix.put(c[1], c[2]); }
                case "REMOVED" -> removed.add(c[1]);
                default -> { }
            }
        }
        return new Rules(exact, prefix, removed);
    }

    /**
     * Abstract classes and interfaces in a jar.
     *
     * Reads the access flags with ASM rather than by hand: they sit past the constant pool, so
     * there is no fixed offset to seek to and the pool has to be parsed either way.
     */
    private static Set<String> abstractClassesIn(Path jar) throws IOException {
        Set<String> out = new HashSet<>();
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (!e.getName().endsWith(".class")) continue;
                try (var in = zf.getInputStream(e)) {
                    var reader = new org.objectweb.asm.ClassReader(in);
                    if ((reader.getAccess() & org.objectweb.asm.Opcodes.ACC_ABSTRACT) != 0) {
                        out.add(reader.getClassName());
                    }
                } catch (Exception ignored) {
                    // Unparseable class: it cannot be a useful rename target either way.
                }
            }
        }
        return out;
    }

    private static Set<String> classesIn(Path jar) throws IOException {
        Set<String> out = new HashSet<>();
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String n = e.getName();
                if (n.endsWith(".class")) out.add(n.substring(0, n.length() - 6));
            }
        }
        return out;
    }
}
