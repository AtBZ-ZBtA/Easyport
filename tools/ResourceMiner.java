import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Mines resource-layer migration rules from author-ported mod pairs.
 *
 * RuleMiner covers bytecode. This covers everything else in a jar: the mod descriptor,
 * pack.mcmeta, recipes, loot tables, tags, models. Those need migrating too, and the rules
 * are just as empirical — the shape of a 1.21.1 recipe JSON is a fact about the corpus, not
 * something worth asserting from memory.
 *
 * Same corroboration discipline as RuleMiner: mod versions drift heavily between the two
 * packs, so nothing is trusted from a single pair. Everything is ranked by how many
 * independent mods agree.
 *
 * Three things are mined:
 *   1. Resource *directory* renames  - data/x/recipes/ -> data/x/recipe/ and friends
 *   2. JSON *key* changes per category - e.g. a recipe result naming its item differently
 *   3. Descriptor and pack_format deltas - the concrete inputs to the resource migrator
 *
 * Run:
 *   java tools/ResourceMiner.java <pairs.tsv> <sourceModsDir> <targetModsDir> [outDir]
 */
public class ResourceMiner {

    private static final int MIN_CORROBORATION = 5;

    /** JSON object keys. Good enough for frequency analysis without a parser dependency. */
    private static final Pattern JSON_KEY = Pattern.compile("\"([A-Za-z0-9_]+)\"\\s*:");
    private static final Pattern PACK_FORMAT = Pattern.compile("\"pack_format\"\\s*:\\s*(\\d+)");
    private static final Pattern TOML_KEY = Pattern.compile("^\\s*([A-Za-z][A-Za-z0-9_]*)\\s*=");

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("usage: java tools/ResourceMiner.java "
                             + "<pairs.tsv> <sourceModsDir> <targetModsDir> [outDir]");
            System.exit(2);
        }
        Path pairsFile = Paths.get(args[0]);
        Path srcDir = Paths.get(args[1]);
        Path tgtDir = Paths.get(args[2]);
        Path out = Paths.get(args.length > 3 ? args[3] : "resource-report");
        Files.createDirectories(out);

        List<String[]> pairs = readPairs(pairsFile);
        System.out.printf("Mining resources from %d pairs%n%n", pairs.size());

        Map<String, Integer> srcDirs = new HashMap<>(), tgtDirs = new HashMap<>();
        Map<String, Integer> srcPackFormats = new TreeMap<>(), tgtPackFormats = new TreeMap<>();
        Map<String, Integer> srcTomlKeys = new HashMap<>(), tgtTomlKeys = new HashMap<>();
        // category -> key -> mods
        Map<String, Map<String, Integer>> srcJsonKeys = new HashMap<>(), tgtJsonKeys = new HashMap<>();

        int done = 0, failed = 0;
        for (String[] p : pairs) {
            Path src = srcDir.resolve(p[1]), tgt = tgtDir.resolve(p[2]);
            if (!Files.isRegularFile(src) || !Files.isRegularFile(tgt)) { failed++; continue; }
            try {
                scan(src, srcDirs, srcPackFormats, srcTomlKeys, srcJsonKeys);
                scan(tgt, tgtDirs, tgtPackFormats, tgtTomlKeys, tgtJsonKeys);
            } catch (Exception e) { failed++; continue; }
            if (++done % 50 == 0) System.out.printf("  ... %d/%d%n", done, pairs.size());
        }
        System.out.printf("%nMined %d pairs (%d skipped)%n", done, failed);

        reportDirectories(srcDirs, tgtDirs, out.resolve("directory-deltas.tsv"));
        reportPackFormats(srcPackFormats, tgtPackFormats);
        reportDescriptor(srcTomlKeys, tgtTomlKeys, out.resolve("descriptor-deltas.tsv"));
        reportJsonKeys(srcJsonKeys, tgtJsonKeys, out.resolve("json-key-deltas.tsv"));

        System.out.printf("%nWrote reports to %s%n", out.toAbsolutePath());
    }

    // ---- scanning ----------------------------------------------------------------------

    private static void scan(Path jar, Map<String, Integer> dirs, Map<String, Integer> packFormats,
                             Map<String, Integer> tomlKeys, Map<String, Map<String, Integer>> jsonKeys)
                             throws IOException {
        // Per-jar sets first, so a mod with 400 recipes counts once per distinct feature rather
        // than 400 times. Corroboration must be measured in mods, not files.
        Set<String> jarDirs = new HashSet<>();
        Set<String> jarToml = new HashSet<>();
        Set<String> jarFormats = new HashSet<>();
        Map<String, Set<String>> jarJson = new HashMap<>();

        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String name = e.getName();
                if (e.isDirectory() || name.endsWith(".class")) continue;

                String dir = categoryPath(name);
                if (dir != null) jarDirs.add(dir);

                if (name.equals("META-INF/mods.toml") || name.equals("META-INF/neoforge.mods.toml")) {
                    for (String line : read(zip, e).split("\\R")) {
                        Matcher m = TOML_KEY.matcher(line);
                        if (m.find()) jarToml.add(m.group(1));
                    }
                } else if (name.equals("pack.mcmeta")) {
                    Matcher m = PACK_FORMAT.matcher(read(zip, e));
                    while (m.find()) jarFormats.add(m.group(1));
                } else if (name.endsWith(".json")) {
                    String cat = category(name);
                    if (cat == null) continue;
                    Matcher m = JSON_KEY.matcher(read(zip, e));
                    Set<String> keys = jarJson.computeIfAbsent(cat, k -> new HashSet<>());
                    while (m.find()) keys.add(m.group(1));
                }
            }
        }

        for (String d : jarDirs) dirs.merge(d, 1, Integer::sum);
        for (String k : jarToml) tomlKeys.merge(k, 1, Integer::sum);
        for (String f : jarFormats) packFormats.merge(f, 1, Integer::sum);
        jarJson.forEach((cat, keys) -> {
            Map<String, Integer> agg = jsonKeys.computeIfAbsent(cat, k -> new HashMap<>());
            for (String k : keys) agg.merge(k, 1, Integer::sum);
        });
    }

    /**
     * Normalises a resource path to its structural directory, with the namespace elided.
     *
     * data/create/recipes/foo.json -> data/&lt;ns&gt;/recipes
     * Namespaces are mod-specific noise; the directory *name* is what changes between versions.
     */
    private static String categoryPath(String name) {
        String[] parts = name.split("/");
        if (parts.length < 4) return null;
        if (!parts[0].equals("data") && !parts[0].equals("assets")) return null;
        StringBuilder sb = new StringBuilder(parts[0]).append("/<ns>/").append(parts[2]);
        // Keep one more level for tags, where the subtype is exactly what got renamed in 1.21.
        if (parts[2].equals("tags") && parts.length > 4) sb.append('/').append(parts[3]);
        return sb.toString();
    }

    /**
     * Coarser bucket for JSON key analysis, canonicalised across the 1.21 directory renames.
     *
     * 1.21 singularised most datapack directories (recipes -> recipe, loot_tables ->
     * loot_table, advancements -> advancement, tags/fluids -> tags/fluid). Without folding
     * those together, every 1.21.1 key looks "added" and every 1.20.1 key "removed" — the
     * comparison silently measures the rename instead of the schema.
     *
     * Stripping a trailing 's' per segment is a heuristic, but it is applied identically to
     * both sides, so it can only ever merge buckets consistently.
     */
    private static String category(String name) {
        String p = categoryPath(name);
        if (p == null) return null;
        String cat = p.substring(p.indexOf("/<ns>/") + 6);
        StringBuilder sb = new StringBuilder();
        for (String seg : cat.split("/")) {
            if (sb.length() > 0) sb.append('/');
            sb.append(seg.endsWith("s") ? seg.substring(0, seg.length() - 1) : seg);
        }
        return sb.toString();
    }

    // ---- reporting ---------------------------------------------------------------------

    private static void reportDirectories(Map<String, Integer> src, Map<String, Integer> tgt, Path out)
            throws IOException {
        System.out.println("\n" + "=".repeat(78));
        System.out.println("RESOURCE DIRECTORY DELTAS  (renames the migrator must apply)");
        System.out.println("=".repeat(78));

        StringBuilder sb = new StringBuilder("side\tmods\tdirectory\n");
        List<String> lostDirs = new ArrayList<>(), gainedDirs = new ArrayList<>();
        src.forEach((d, n) -> { if (n >= MIN_CORROBORATION && !tgt.containsKey(d)) lostDirs.add(d); });
        tgt.forEach((d, n) -> { if (n >= MIN_CORROBORATION && !src.containsKey(d)) gainedDirs.add(d); });
        lostDirs.sort((a, b) -> src.get(b) - src.get(a));
        gainedDirs.sort((a, b) -> tgt.get(b) - tgt.get(a));

        System.out.println("\n  Present in 1.20.1 only:");
        for (String d : lostDirs) {
            System.out.printf("    %4d mods  %s%n", src.get(d), d);
            sb.append("source-only\t").append(src.get(d)).append('\t').append(d).append('\n');
        }
        System.out.println("\n  Present in 1.21.1 only:");
        for (String d : gainedDirs) {
            System.out.printf("    %4d mods  %s%n", tgt.get(d), d);
            sb.append("target-only\t").append(tgt.get(d)).append('\t').append(d).append('\n');
        }
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
    }

    private static void reportPackFormats(Map<String, Integer> src, Map<String, Integer> tgt) {
        System.out.println("\n" + "=".repeat(78));
        System.out.println("pack.mcmeta pack_format");
        System.out.println("=".repeat(78));
        System.out.println("  1.20.1: " + src);
        System.out.println("  1.21.1: " + tgt);
    }

    private static void reportDescriptor(Map<String, Integer> src, Map<String, Integer> tgt, Path out)
            throws IOException {
        System.out.println("\n" + "=".repeat(78));
        System.out.println("MOD DESCRIPTOR  mods.toml -> neoforge.mods.toml");
        System.out.println("=".repeat(78));

        StringBuilder sb = new StringBuilder("status\tsrcMods\ttgtMods\tkey\n");
        Set<String> all = new TreeSet<>();
        all.addAll(src.keySet());
        all.addAll(tgt.keySet());
        for (String k : all) {
            int s = src.getOrDefault(k, 0), t = tgt.getOrDefault(k, 0);
            if (s + t < MIN_CORROBORATION) continue;
            String status = s == 0 ? "ADDED" : t == 0 ? "REMOVED" : "kept";
            if (!status.equals("kept")) System.out.printf("  %-8s src=%-4d tgt=%-4d %s%n", status, s, t, k);
            sb.append(status).append('\t').append(s).append('\t').append(t).append('\t').append(k).append('\n');
        }
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
    }

    private static void reportJsonKeys(Map<String, Map<String, Integer>> src,
                                       Map<String, Map<String, Integer>> tgt, Path out)
                                       throws IOException {
        System.out.println("\n" + "=".repeat(78));
        System.out.println("JSON KEY DELTAS BY RESOURCE CATEGORY");
        System.out.println("=".repeat(78));

        StringBuilder sb = new StringBuilder("category\tstatus\tsrcMods\ttgtMods\tkey\n");
        Set<String> cats = new TreeSet<>();
        cats.addAll(src.keySet());
        cats.addAll(tgt.keySet());

        for (String cat : cats) {
            Map<String, Integer> s = src.getOrDefault(cat, Map.of());
            Map<String, Integer> t = tgt.getOrDefault(cat, Map.of());
            List<String> changes = new ArrayList<>();
            Set<String> keys = new TreeSet<>();
            keys.addAll(s.keySet());
            keys.addAll(t.keySet());
            // How many mods ship this category at all, so keys can be judged by share rather
            // than raw count.
            int sMods = s.values().stream().mapToInt(Integer::intValue).max().orElse(0);
            int tMods = t.values().stream().mapToInt(Integer::intValue).max().orElse(0);

            for (String k : keys) {
                int sn = s.getOrDefault(k, 0), tn = t.getOrDefault(k, 0);
                if (sn + tn < MIN_CORROBORATION) continue;

                // Most keys in a datapack file are author-chosen data (criterion names like
                // "has_iron_ingot"), not schema. Schema keys appear in a large share of mods on
                // one side and collapse on the other; data keys never reach that share. A ratio
                // test separates them where a raw count cannot.
                double sShare = sMods == 0 ? 0 : (double) sn / sMods;
                double tShare = tMods == 0 ? 0 : (double) tn / tMods;
                final double COMMON = 0.25, RARE = 0.05;

                String status = null;
                if (sShare < RARE && tShare >= COMMON) status = "ADDED";
                else if (tShare < RARE && sShare >= COMMON) status = "REMOVED";
                if (status == null) continue;
                changes.add(String.format("    %-8s src=%-4d tgt=%-4d %s", status, sn, tn, k));
                sb.append(cat).append('\t').append(status).append('\t').append(sn).append('\t')
                  .append(tn).append('\t').append(k).append('\n');
            }
            if (!changes.isEmpty()) {
                System.out.println("\n  [" + cat + "]");
                changes.forEach(System.out::println);
            }
        }
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
    }

    // ---- helpers -----------------------------------------------------------------------

    private static String read(ZipFile zip, ZipEntry e) throws IOException {
        try (InputStream in = zip.getInputStream(e)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<String[]> readPairs(Path tsv) throws IOException {
        List<String[]> pairs = new ArrayList<>();
        List<String> lines = Files.readAllLines(tsv, StandardCharsets.UTF_8);
        for (int i = 1; i < lines.size(); i++) {
            String[] cols = lines.get(i).split("\t", -1);
            if (cols.length < 10) continue;
            pairs.add(new String[] { cols[0], cols[8], cols[9] });
        }
        return pairs;
    }
}
