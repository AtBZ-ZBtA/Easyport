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
 * Corpus analyzer for the version translation layer.
 *
 * Scans two directories of mod jars (e.g. ATM9 / Forge 1.20.1 and ATM10 / NeoForge 1.21.1),
 * identifies which mods appear in both, and triages every jar by how hard it will be to
 * translate. Mods present in both directories are ground-truth pairs: the same mod, ported
 * by its own author, which is what we derive translation rules from.
 *
 * Run with no build step:
 *   java tools/CorpusAnalyzer.java <dirA> <dirB> [outputDir]
 */
public class CorpusAnalyzer {

    // ---- metadata extraction patterns -------------------------------------------------
    // mods.toml / neoforge.mods.toml are TOML, but the fields we need are flat enough that
    // line matching beats pulling in a parser dependency for a single-file tool. Matching is
    // section-aware (see parseToml) because the same keys appear in dependency blocks.
    private static final Pattern KV_MOD_ID  = Pattern.compile("modId\\s*=\\s*\"([^\"]+)\".*");
    private static final Pattern KV_NAME    = Pattern.compile("displayName\\s*=\\s*\"([^\"]+)\".*");
    private static final Pattern KV_VERSION = Pattern.compile("version\\s*=\\s*\"([^\"]+)\".*");
    private static final Pattern JSON_ID    = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");

    /** Identity pulled from a mod descriptor: the ids it declares, plus name and version. */
    private record Meta(List<String> ids, String displayName, String version) {}

    enum Loader { FORGE, NEOFORGE, FABRIC, UNKNOWN }

    /** Rough translation difficulty, driven by what the jar actually contains. */
    enum Tier {
        TRIVIAL,    // pure content, no bytecode tricks
        MODERATE,   // substantial code, still no bytecode tricks
        HARD,       // mixins present — injection points may not survive the version delta
        NIGHTMARE   // coremods or heavy mixin use — AOT translation cannot be trusted alone
    }

    record Mod(
        String modId,
        List<String> allModIds,
        String displayName,
        String version,
        Loader loader,
        Path file,
        long sizeBytes,
        int classCount,
        int forgeApiRefs,
        int neoforgeApiRefs,
        List<String> mixinConfigs,
        int mixinClasses,
        boolean hasAccessTransformer,
        boolean hasCoremod,
        int nestedJars
    ) {
        // Difficulty is driven by mixin *class* count, not config count: one config file can
        // list eighty mixins, and it is the individual injection points that break across a
        // version gap, not the config that groups them.
        Tier tier() {
            if (hasCoremod || mixinClasses > 25) return Tier.NIGHTMARE;
            if (mixinClasses > 0)                return Tier.HARD;
            if (classCount > 150)                return Tier.MODERATE;
            return Tier.TRIVIAL;
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: java tools/CorpusAnalyzer.java <dirA> <dirB> [outputDir]");
            System.err.println("  dirA  mods folder for the source version   (e.g. ATM9,  Forge 1.20.1)");
            System.err.println("  dirB  mods folder for the target version   (e.g. ATM10, NeoForge 1.21.1)");
            System.exit(2);
        }
        Path dirA = Paths.get(args[0]);
        Path dirB = Paths.get(args[1]);
        Path out  = Paths.get(args.length > 2 ? args[2] : "corpus-report");

        for (Path d : List.of(dirA, dirB)) {
            if (!Files.isDirectory(d)) {
                System.err.println("not a directory: " + d);
                System.exit(2);
            }
        }
        Files.createDirectories(out);

        List<Mod> a = scanDirectory(dirA);
        List<Mod> b = scanDirectory(dirB);

        System.out.printf("%nScanned %d jars in %s%n", a.size(), dirA);
        System.out.printf("Scanned %d jars in %s%n%n", b.size(), dirB);

        // Match on modId, not filename. Filenames are inconsistent across versions and
        // packagers; the declared modId is the only stable identity a mod has.
        Map<String, Mod> byIdA = indexByModId(a);
        Map<String, Mod> byIdB = indexByModId(b);

        List<String> paired   = new ArrayList<>();
        List<String> onlyInA  = new ArrayList<>();
        List<String> onlyInB  = new ArrayList<>();

        for (String id : byIdA.keySet()) {
            if (byIdB.containsKey(id)) paired.add(id); else onlyInA.add(id);
        }
        for (String id : byIdB.keySet()) {
            if (!byIdA.containsKey(id)) onlyInB.add(id);
        }
        Collections.sort(paired);
        Collections.sort(onlyInA);
        Collections.sort(onlyInB);

        printSummary(paired, onlyInA, onlyInB, byIdA, byIdB);
        writeManifest(out.resolve("corpus-manifest.tsv"), a, b);
        writePairs(out.resolve("ground-truth-pairs.tsv"), paired, byIdA, byIdB);
        writeUnpaired(out.resolve("unpaired.tsv"), onlyInA, onlyInB, byIdA, byIdB);

        System.out.printf("%nWrote reports to %s%n", out.toAbsolutePath());
    }

    // ---- scanning ---------------------------------------------------------------------

    private static List<Mod> scanDirectory(Path dir) throws IOException {
        List<Mod> mods = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.jar")) {
            for (Path jar : stream) {
                try {
                    Mod m = analyze(jar);
                    if (m != null) mods.add(m);
                } catch (Exception e) {
                    System.err.printf("  ! failed to read %s: %s%n", jar.getFileName(), e);
                }
            }
        }
        mods.sort(Comparator.comparing(Mod::modId));
        return mods;
    }

    private static Mod analyze(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            String forgeToml = readEntry(zip, "META-INF/mods.toml");
            String neoToml   = readEntry(zip, "META-INF/neoforge.mods.toml");
            String fabricJson = readEntry(zip, "fabric.mod.json");

            Loader loader;
            String meta;
            if (neoToml != null)        { loader = Loader.NEOFORGE; meta = neoToml; }
            else if (forgeToml != null) { loader = Loader.FORGE;    meta = forgeToml; }
            else if (fabricJson != null){ loader = Loader.FABRIC;   meta = fabricJson; }
            else                        { loader = Loader.UNKNOWN;  meta = null; }

            List<String> ids = new ArrayList<>();
            String displayName = "";
            String version = "";
            if (meta != null) {
                if (loader == Loader.FABRIC) {
                    // The top-level "id" comes first; nested ones belong to dependency blocks.
                    Matcher m = JSON_ID.matcher(meta);
                    if (m.find()) ids.add(m.group(1));
                } else {
                    Meta parsed = parseToml(meta);
                    ids.addAll(parsed.ids());
                    displayName = parsed.displayName();
                    version = parsed.version();
                }
            }
            // Most real mods leave version as a "${file.jarVersion}" placeholder for Gradle to
            // resolve into the jar manifest, so the descriptor alone gives us nothing usable.
            if (version.startsWith("${")) {
                version = manifestVersion(zip);
            }
            // A jar with no parseable metadata is still worth recording — it is usually a
            // bundled library, and libraries need translating too.
            if (ids.isEmpty()) ids.add("~unknown:" + stripJarSuffix(jar.getFileName().toString()));

            int classCount = 0, forgeRefs = 0, neoRefs = 0, nested = 0, mixinClasses = 0;
            boolean hasAt = false, hasCoremod = false;
            List<String> mixinConfigs = new ArrayList<>();

            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String name = e.getName();

                if (name.endsWith(".class")) {
                    classCount++;
                    // Mixin classes live in a package the config points at, conventionally
                    // named "mixin"/"mixins". Counting them approximates the injection-point
                    // surface without parsing every config's class list.
                    if (name.contains("/mixin/") || name.contains("/mixins/")) mixinClasses++;
                    // Constant-pool strings are stored raw, so a byte scan for the package
                    // path is a cheap and reliable proxy for "does this class touch the API".
                    byte[] data = readAllBytes(zip, e);
                    if (contains(data, "net/minecraftforge")) forgeRefs++;
                    if (contains(data, "net/neoforged"))      neoRefs++;
                } else if (name.endsWith(".mixins.json") || name.matches(".*mixins\\..*\\.json")) {
                    mixinConfigs.add(name);
                } else if (name.equals("META-INF/accesstransformer.cfg")
                        || name.endsWith("_at.cfg")) {
                    hasAt = true;
                } else if (name.startsWith("META-INF/jarjar/") && name.endsWith(".jar")) {
                    nested++;
                } else if (name.startsWith("coremods/")
                        || name.equals("META-INF/coremods.json")
                        || name.startsWith("META-INF/services/cpw.mods.modlauncher")
                        || name.startsWith("META-INF/services/net.neoforged.neoforgespi")) {
                    hasCoremod = true;
                }
            }

            return new Mod(ids.get(0), ids, displayName, version, loader, jar,
                           Files.size(jar), classCount, forgeRefs, neoRefs,
                           mixinConfigs, mixinClasses, hasAt, hasCoremod, nested);
        }
    }

    // ---- reporting --------------------------------------------------------------------

    private static void printSummary(List<String> paired, List<String> onlyInA, List<String> onlyInB,
                                     Map<String, Mod> byIdA, Map<String, Mod> byIdB) {
        System.out.println("=".repeat(72));
        System.out.println("GROUND-TRUTH PAIRS (same mod, ported by its author - rule derivation set)");
        System.out.println("=".repeat(72));
        System.out.printf("  %d mods present in both versions%n%n", paired.size());

        System.out.println("Difficulty triage of the paired set (source side):");
        Map<Tier, Integer> tiers = new EnumMap<>(Tier.class);
        for (Tier t : Tier.values()) tiers.put(t, 0);
        for (String id : paired) tiers.merge(byIdA.get(id).tier(), 1, Integer::sum);
        int total = Math.max(1, paired.size());
        for (Tier t : Tier.values()) {
            int n = tiers.get(t);
            System.out.printf("  %-10s %4d  (%4.1f%%)  %s%n",
                t, n, 100.0 * n / total, bar(n, total));
        }

        System.out.printf("%nUNPAIRED%n");
        System.out.printf("  %d only in source  - translate with no reference port available%n", onlyInA.size());
        System.out.printf("  %d only in target  - backward-direction targets%n", onlyInB.size());

        int mixinMods = 0, coremods = 0, totalMixins = 0, maxMixins = 0;
        String worst = "";
        for (String id : paired) {
            Mod m = byIdA.get(id);
            if (m.mixinClasses() > 0) { mixinMods++; totalMixins += m.mixinClasses(); }
            if (m.hasCoremod()) coremods++;
            if (m.mixinClasses() > maxMixins) { maxMixins = m.mixinClasses(); worst = m.modId(); }
        }
        System.out.printf("%nBYTECODE-MANIPULATION LOAD (paired set)%n");
        System.out.printf("  %d mods carry mixin classes%n", mixinMods);
        System.out.printf("  %d mixin classes total  <-- Phase 7 workload%n", totalMixins);
        System.out.printf("  %d median-ish per mixin mod%n", mixinMods == 0 ? 0 : totalMixins / mixinMods);
        System.out.printf("  %d in the heaviest single mod (%s)%n", maxMixins, worst);
        System.out.printf("  %d mods ship coremods or launch services%n", coremods);
    }

    private static String bar(int n, int total) {
        int width = (int) Math.round(30.0 * n / total);
        return "#".repeat(width);
    }

    private static void writeManifest(Path path, List<Mod> a, List<Mod> b) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("side\tmodId\tdisplayName\tversion\tloader\ttier\tclasses\tforgeRefs\t")
          .append("neoRefs\tmixinConfigs\tmixinClasses\thasAT\thasCoremod\tnestedJars\tsizeKB\tfile\n");
        for (Mod m : a) appendRow(sb, "source", m);
        for (Mod m : b) appendRow(sb, "target", m);
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }

    private static void appendRow(StringBuilder sb, String side, Mod m) {
        sb.append(side).append('\t')
          .append(m.modId()).append('\t')
          .append(m.displayName()).append('\t')
          .append(m.version()).append('\t')
          .append(m.loader()).append('\t')
          .append(m.tier()).append('\t')
          .append(m.classCount()).append('\t')
          .append(m.forgeApiRefs()).append('\t')
          .append(m.neoforgeApiRefs()).append('\t')
          .append(m.mixinConfigs().size()).append('\t')
          .append(m.mixinClasses()).append('\t')
          .append(m.hasAccessTransformer()).append('\t')
          .append(m.hasCoremod()).append('\t')
          .append(m.nestedJars()).append('\t')
          .append(m.sizeBytes() / 1024).append('\t')
          .append(m.file().getFileName()).append('\n');
    }

    private static void writePairs(Path path, List<String> paired,
                                   Map<String, Mod> byIdA, Map<String, Mod> byIdB) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("modId\ttier\tsourceVersion\ttargetVersion\tsourceClasses\ttargetClasses\t")
          .append("sourceMixins\ttargetMixins\tsourceFile\ttargetFile\n");
        for (String id : paired) {
            Mod s = byIdA.get(id), t = byIdB.get(id);
            sb.append(id).append('\t')
              .append(s.tier()).append('\t')
              .append(s.version()).append('\t')
              .append(t.version()).append('\t')
              .append(s.classCount()).append('\t')
              .append(t.classCount()).append('\t')
              .append(s.mixinConfigs().size()).append('\t')
              .append(t.mixinConfigs().size()).append('\t')
              .append(s.file().getFileName()).append('\t')
              .append(t.file().getFileName()).append('\n');
        }
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }

    private static void writeUnpaired(Path path, List<String> onlyInA, List<String> onlyInB,
                                      Map<String, Mod> byIdA, Map<String, Mod> byIdB) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("side\tmodId\tdisplayName\ttier\tclasses\tfile\n");
        for (String id : onlyInA) appendUnpaired(sb, "source-only", byIdA.get(id));
        for (String id : onlyInB) appendUnpaired(sb, "target-only", byIdB.get(id));
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }

    private static void appendUnpaired(StringBuilder sb, String side, Mod m) {
        sb.append(side).append('\t')
          .append(m.modId()).append('\t')
          .append(m.displayName()).append('\t')
          .append(m.tier()).append('\t')
          .append(m.classCount()).append('\t')
          .append(m.file().getFileName()).append('\n');
    }

    // ---- helpers ----------------------------------------------------------------------

    private static Map<String, Mod> indexByModId(List<Mod> mods) {
        Map<String, Mod> map = new LinkedHashMap<>();
        for (Mod m : mods) {
            // A jar declaring several mods registers under each, so a mod that was split or
            // merged between versions still matches on at least one id.
            for (String id : m.allModIds()) map.putIfAbsent(id, m);
        }
        return map;
    }

    /**
     * Pulls mod identity out of a Forge/NeoForge TOML descriptor.
     *
     * Only [[mods]] blocks count. Dependency blocks ([[dependencies.&lt;id&gt;]]) contain modId
     * keys naming *other* mods; counting those as declarations would make every jar claim to
     * provide "minecraft" and "neoforge", which corrupts corpus matching outright.
     */
    private static Meta parseToml(String toml) {
        List<String> ids = new ArrayList<>();
        String name = "", version = "";
        boolean inModsBlock = false;

        for (String raw : toml.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("[")) {
                inModsBlock = line.startsWith("[[mods]]");
                continue;
            }
            if (!inModsBlock) continue;

            Matcher m;
            if ((m = KV_MOD_ID.matcher(line)).matches()) {
                ids.add(m.group(1));
            } else if (name.isEmpty() && (m = KV_NAME.matcher(line)).matches()) {
                name = m.group(1);
            } else if (version.isEmpty() && (m = KV_VERSION.matcher(line)).matches()) {
                version = m.group(1);
            }
        }
        return new Meta(ids, name, version);
    }

    /** Falls back to the jar manifest when the descriptor version is a build placeholder. */
    private static String manifestVersion(ZipFile zip) throws IOException {
        String mf = readEntry(zip, "META-INF/MANIFEST.MF");
        if (mf == null) return "";
        for (String line : mf.split("\\R")) {
            if (line.startsWith("Implementation-Version:")) {
                return line.substring("Implementation-Version:".length()).trim();
            }
        }
        return "";
    }

    private static String readEntry(ZipFile zip, String name) throws IOException {
        ZipEntry e = zip.getEntry(name);
        if (e == null) return null;
        try (InputStream in = zip.getInputStream(e)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static byte[] readAllBytes(ZipFile zip, ZipEntry e) throws IOException {
        try (InputStream in = zip.getInputStream(e)) {
            return in.readAllBytes();
        }
    }

    private static boolean contains(byte[] haystack, String needle) {
        byte[] n = needle.getBytes(StandardCharsets.UTF_8);
        if (n.length == 0 || haystack.length < n.length) return false;
        outer:
        for (int i = 0; i <= haystack.length - n.length; i++) {
            for (int j = 0; j < n.length; j++) {
                if (haystack[i + j] != n[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    private static String stripJarSuffix(String filename) {
        return filename.endsWith(".jar") ? filename.substring(0, filename.length() - 4) : filename;
    }
}
