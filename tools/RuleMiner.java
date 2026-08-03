import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Mines translation rule candidates from author-ported mod pairs.
 *
 * For each ground-truth pair (the same mod, shipped for both Forge 1.20.1 and NeoForge
 * 1.21.1 by its own author), this extracts every reference the mod makes into the game and
 * loader APIs, then diffs the two sides. A symbol present on the source side and absent on
 * the target side is a candidate "lost" API; one that appears only on the target side is a
 * candidate "gained" API.
 *
 * Neither side means anything on its own. Mod versions drift heavily between the two packs
 * (ae2 goes 15.4.9 -> 19.2.17), so a single pair's diff is mostly the author's own feature
 * work. Signal comes from corroboration: a genuine API migration shows up in dozens of
 * unrelated mods, while an added feature shows up once. Everything here is therefore ranked
 * by how many independent pairs agree.
 *
 * Run:
 *   java -cp <asm.jar> tools/RuleMiner.java <pairs.tsv> <sourceModsDir> <targetModsDir> [outDir]
 */
public class RuleMiner {

    /** Only references into these namespaces can be translation rules. */
    private static final List<String> API_ROOTS = List.of(
        "net/minecraft/", "net/minecraftforge/", "net/neoforged/"
    );

    /** How many top symbols per side to correlate. Keeps the co-occurrence pass tractable. */
    private static final int CORRELATION_WIDTH = 250;

    /** A rule needs at least this many independent mods agreeing before it is worth reading. */
    private static final int MIN_CORROBORATION = 5;

    /**
     * SRG member names, e.g. m_61124_ / f_279569_.
     *
     * Forge 1.20.1 runs on SRG names while NeoForge 1.21.1 runs on official Mojang names, so
     * every vanilla member differs between the two sides for reasons that have nothing to do
     * with the API changing. Vanilla results are therefore mapping-contaminated until the
     * source side is remapped, and are reported separately from loader results, which are not
     * obfuscated and are trustworthy immediately.
     */
    private static final java.util.regex.Pattern SRG =
        java.util.regex.Pattern.compile("[mfp]_\\d+_");

    /**
     * The declaring type of a symbol, ignoring its descriptor.
     *
     * Classification has to key off the owner alone. Testing the whole symbol misfiles any
     * loader member whose descriptor happens to mention a vanilla type — which is most of
     * them, e.g. DeferredRegister#create(Lnet/minecraft/resources/ResourceKey;...).
     */
    private static String ownerOf(String symbol) {
        int hash = symbol.indexOf('#');
        if (hash >= 0) return symbol.substring(0, hash);
        int space = symbol.indexOf(' ');           // "type X" / "extends X" / "implements X"
        return space >= 0 ? symbol.substring(space + 1) : symbol;
    }

    private static boolean isVanilla(String symbol) {
        return ownerOf(symbol).startsWith("net/minecraft/");
    }

    private static boolean isSrg(String symbol) { return SRG.matcher(symbol).find(); }

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("usage: java -cp <asm.jar> tools/RuleMiner.java "
                             + "<pairs.tsv> <sourceModsDir> <targetModsDir> [outDir] [srg2official.tsv]");
            System.exit(2);
        }
        Path pairsFile = Paths.get(args[0]);
        Path srcDir    = Paths.get(args[1]);
        Path tgtDir    = Paths.get(args[2]);
        Path out       = Paths.get(args.length > 3 ? args[3] : "rule-report");
        Files.createDirectories(out);

        // Without this table the vanilla side is pure mapping noise: Forge 1.20.1 runs SRG
        // member names and NeoForge 1.21.1 runs official ones, so every vanilla member differs
        // for reasons unrelated to the API. Build it with tools/SrgToOfficial.java.
        Map<String, String> srgToOfficial = new HashMap<>();
        if (args.length > 4) {
            for (String line : Files.readAllLines(Paths.get(args[4]), StandardCharsets.UTF_8)) {
                String[] c = line.split("\t");
                if (c.length == 2 && !c[0].equals("srg")) srgToOfficial.put(c[0], c[1]);
            }
            System.out.printf("Loaded %d SRG -> official member mappings%n", srgToOfficial.size());
        } else {
            System.out.println("WARNING: no SRG mapping supplied; vanilla results will be "
                             + "mapping-contaminated and effectively meaningless.");
        }

        List<String[]> pairs = readPairs(pairsFile);
        System.out.printf("Mining %d ground-truth pairs%n%n", pairs.size());

        // symbol -> number of distinct mods where it was lost / gained
        Map<String, Integer> lostCounts   = new HashMap<>();
        Map<String, Integer> gainedCounts = new HashMap<>();
        // Retained per pair so we can correlate lost against gained afterwards.
        List<Set<String>> lostPerPair   = new ArrayList<>();
        List<Set<String>> gainedPerPair = new ArrayList<>();
        List<String> pairNames = new ArrayList<>();

        int done = 0, failed = 0;
        for (String[] p : pairs) {
            String modId = p[0], srcFile = p[1], tgtFile = p[2];
            Path src = srcDir.resolve(srcFile), tgt = tgtDir.resolve(tgtFile);
            if (!Files.isRegularFile(src) || !Files.isRegularFile(tgt)) { failed++; continue; }

            try {
                // Only the source side is SRG-named; the target already runs official names.
                Set<String> srcSyms = extractApiRefs(src, srgToOfficial);
                Set<String> tgtSyms = extractApiRefs(tgt, Map.of());

                Set<String> lost = new HashSet<>(srcSyms);
                lost.removeAll(tgtSyms);
                Set<String> gained = new HashSet<>(tgtSyms);
                gained.removeAll(srcSyms);

                for (String s : lost)   lostCounts.merge(s, 1, Integer::sum);
                for (String s : gained) gainedCounts.merge(s, 1, Integer::sum);

                lostPerPair.add(lost);
                gainedPerPair.add(gained);
                pairNames.add(modId);
            } catch (Exception e) {
                failed++;
                continue;
            }

            if (++done % 25 == 0) System.out.printf("  ... %d/%d pairs%n", done, pairs.size());
        }
        System.out.printf("%nMined %d pairs (%d skipped)%n", done, failed);

        writeRanked(out.resolve("lost-symbols.tsv"),   lostCounts,   done, "lost");
        writeRanked(out.resolve("gained-symbols.tsv"), gainedCounts, done, "gained");

        List<String> topLost   = topN(lostCounts,   CORRELATION_WIDTH);
        List<String> topGained = topN(gainedCounts, CORRELATION_WIDTH);
        correlate(out.resolve("candidate-rules.tsv"), topLost, topGained,
                  lostPerPair, gainedPerPair, lostCounts, gainedCounts);

        summarize(lostCounts, gainedCounts, done);
        System.out.printf("%nWrote reports to %s%n", out.toAbsolutePath());
    }

    // ---- bytecode scanning -------------------------------------------------------------

    /**
     * Collects every distinct game/loader API member this jar references.
     *
     * Symbols are recorded as owner#name:descriptor for members and as bare internal names
     * for supertypes, so a rule can be expressed against exactly what a transformer rewrites.
     */
    private static Set<String> extractApiRefs(Path jar, Map<String, String> srgToOfficial)
            throws IOException {
        Set<String> symbols = new HashSet<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (!e.getName().endsWith(".class")) continue;
                try (InputStream in = zip.getInputStream(e)) {
                    new ClassReader(in.readAllBytes()).accept(
                        new RefCollector(symbols, srgToOfficial),
                        ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                } catch (Exception ignored) {
                    // Malformed or future-version classes are common in shaded jars; one bad
                    // class should not cost us the rest of the mod.
                }
            }
        }
        return symbols;
    }

    private static boolean isApi(String internalName) {
        if (internalName == null) return false;
        for (String root : API_ROOTS) if (internalName.startsWith(root)) return true;
        return false;
    }

    private static final class RefCollector extends ClassVisitor {
        private final Set<String> out;
        private final Map<String, String> srgToOfficial;

        RefCollector(Set<String> out, Map<String, String> srgToOfficial) {
            super(Opcodes.ASM9);
            this.out = out;
            this.srgToOfficial = srgToOfficial;
        }

        /**
         * Normalises an SRG member name to its official counterpart.
         *
         * Only member names need this. Forge 1.20.1 bytecode already carries official *class*
         * names, so descriptors are directly comparable between the two sides and are left
         * alone. Names with no mapping pass through unchanged — they belong to the mod itself,
         * not to Minecraft.
         */
        private String norm(String name) {
            if (srgToOfficial.isEmpty()) return name;
            return srgToOfficial.getOrDefault(name, name);
        }

        @Override
        public void visit(int v, int access, String name, String sig, String superName, String[] ifaces) {
            if (isApi(superName)) out.add("extends " + superName);
            if (ifaces != null) for (String i : ifaces) if (isApi(i)) out.add("implements " + i);
        }

        @Override
        public MethodVisitor visitMethod(int a, String n, String d, String s, String[] ex) {
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitMethodInsn(int op, String owner, String name, String desc, boolean itf) {
                    if (isApi(owner)) out.add(owner + "#" + norm(name) + desc);
                }
                @Override
                public void visitFieldInsn(int op, String owner, String name, String desc) {
                    if (isApi(owner)) out.add(owner + "#" + norm(name) + ":" + desc);
                }
                @Override
                public void visitTypeInsn(int op, String type) {
                    if (isApi(type)) out.add("type " + type);
                }
            };
        }
    }

    // ---- rule correlation --------------------------------------------------------------

    /** Member name between '#' and the descriptor, e.g. "addListener". */
    private static String memberName(String symbol) {
        int hash = symbol.indexOf('#');
        if (hash < 0) return "";
        int end = symbol.length();
        for (int i = hash + 1; i < symbol.length(); i++) {
            char c = symbol.charAt(i);
            if (c == '(' || c == ':') { end = i; break; }
        }
        return symbol.substring(hash + 1, end);
    }

    /** Descriptor with loader namespaces collapsed, so the two sides become comparable. */
    private static String normalizedDesc(String symbol) {
        int hash = symbol.indexOf('#');
        if (hash < 0) return "";
        int start = -1;
        for (int i = hash + 1; i < symbol.length(); i++) {
            char c = symbol.charAt(i);
            if (c == '(' || c == ':') { start = (c == ':') ? i + 1 : i; break; }
        }
        if (start < 0) return "";
        return symbol.substring(start)
                     .replace("net/minecraftforge/", "@/")
                     .replace("net/neoforged/neoforge/", "@/")
                     .replace("net/neoforged/", "@/");
    }

    /**
     * Pairs lost symbols with gained ones and scores how likely each pairing is a real rule.
     *
     * Plain co-occurrence is not enough. A symbol like ModConfigSpec$Builder#&lt;init&gt; appears
     * in every config-using mod, so one-directional confidence pairs it at ~0.98 with every
     * config symbol regardless of relationship. Three signals together discriminate:
     *
     *   - Jaccard overlap, which unlike confidence penalises a gained symbol that appears far
     *     more widely than the lost one it is being matched against.
     *   - Matching member name. Most of these migrations move a member between owners and keep
     *     its name (ForgeConfigSpec$Builder#comment -> ModConfigSpec$Builder#comment), so this
     *     is a strong domain-specific signal.
     *   - Matching descriptor once loader namespaces are collapsed.
     *
     * Restricted to the most frequent symbols on each side; the pass is quadratic and the long
     * tail is noise anyway.
     */
    private static void correlate(Path path, List<String> topLost, List<String> topGained,
                                  List<Set<String>> lostPerPair, List<Set<String>> gainedPerPair,
                                  Map<String, Integer> lostCounts, Map<String, Integer> gainedCounts)
                                  throws IOException {
        Map<String, Map<String, Integer>> co = new HashMap<>();

        for (int i = 0; i < lostPerPair.size(); i++) {
            Set<String> lost = lostPerPair.get(i), gained = gainedPerPair.get(i);
            for (String l : topLost) {
                if (!lost.contains(l)) continue;
                Map<String, Integer> row = co.computeIfAbsent(l, k -> new HashMap<>());
                for (String g : topGained) {
                    if (gained.contains(g)) row.merge(g, 1, Integer::sum);
                }
            }
        }

        record Rule(String lost, String gained, int support, double jaccard,
                    boolean nameMatch, boolean descMatch, double score) {}
        List<Rule> rules = new ArrayList<>();

        for (var entry : co.entrySet()) {
            String lostSym = entry.getKey();
            int lostTotal = lostCounts.getOrDefault(lostSym, 0);
            if (lostTotal < MIN_CORROBORATION) continue;

            String lName = memberName(lostSym), lDesc = normalizedDesc(lostSym);

            for (var g : entry.getValue().entrySet()) {
                String gainedSym = g.getKey();
                int support = g.getValue();
                if (support < MIN_CORROBORATION) continue;

                int gainedTotal = gainedCounts.getOrDefault(gainedSym, 0);
                int union = lostTotal + gainedTotal - support;
                double jaccard = union == 0 ? 0 : (double) support / union;

                boolean nameMatch = !lName.isEmpty() && lName.equals(memberName(gainedSym));
                boolean descMatch = !lDesc.isEmpty() && lDesc.equals(normalizedDesc(gainedSym));

                double score = jaccard * (1 + (nameMatch ? 1.0 : 0) + (descMatch ? 0.6 : 0));
                rules.add(new Rule(lostSym, gainedSym, support, jaccard, nameMatch, descMatch, score));
            }
        }
        rules.sort(Comparator.comparingDouble(Rule::score).reversed()
                             .thenComparing(Comparator.comparingInt(Rule::support).reversed()));

        StringBuilder sb = new StringBuilder("score\tjaccard\tsupport\tnameMatch\tdescMatch\tlostSymbol\tgainedSymbol\n");
        for (Rule r : rules) {
            sb.append(String.format("%.3f", r.score())).append('\t')
              .append(String.format("%.3f", r.jaccard())).append('\t')
              .append(r.support()).append('\t')
              .append(r.nameMatch()).append('\t')
              .append(r.descMatch()).append('\t')
              .append(r.lost()).append('\t')
              .append(r.gained()).append('\n');
        }
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);

        long strong = rules.stream().filter(r -> r.score() >= 1.0).count();
        System.out.printf("  %d candidate rules (%d scoring >= 1.0, corroboration >= %d mods)%n",
                          rules.size(), strong, MIN_CORROBORATION);
    }

    // ---- reporting ---------------------------------------------------------------------

    private static void writeRanked(Path path, Map<String, Integer> counts, int totalPairs,
                                    String label) throws IOException {
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort(Map.Entry.<String, Integer>comparingByValue().reversed());

        // Singletons are noise across a large corpus but are the entire signal in a small
        // controlled run, where every difference is known to be a real migration.
        int minMods = totalPairs >= 10 ? 2 : 1;
        StringBuilder sb = new StringBuilder("mods\tshareOfPairs\tsymbol\n");
        for (var e : sorted) {
            if (e.getValue() < minMods) break;
            sb.append(e.getValue()).append('\t')
              .append(String.format("%.3f", (double) e.getValue() / totalPairs)).append('\t')
              .append(e.getKey()).append('\n');
        }
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
        System.out.printf("  %s: %d distinct symbols%n", label, counts.size());
    }

    private static void summarize(Map<String, Integer> lost, Map<String, Integer> gained, int pairs) {
        long srgLost = lost.keySet().stream().filter(RuleMiner::isSrg).count();

        // The loader API is not obfuscated on either side, so these results stand on their own
        // and are the immediate work list for the compat shims.
        Map<String, Integer> loaderLost = filter(lost, s -> !isVanilla(s));
        System.out.println("\n" + "=".repeat(78));
        System.out.println("LOADER API - MOST-DEPENDED-ON LOST SYMBOLS   [TRUSTWORTHY]");
        System.out.println("  This is the forge-compat shim work list, in build order.");
        System.out.println("=".repeat(78));
        topN(loaderLost, 30).forEach(s ->
            System.out.printf("  %4d mods  %s%n", loaderLost.get(s), truncate(s, 62)));

        Map<String, Integer> loaderGained = filter(gained, s -> !isVanilla(s));
        System.out.println("\n" + "=".repeat(78));
        System.out.println("LOADER API - MOST-ADOPTED GAINED SYMBOLS     [TRUSTWORTHY]");
        System.out.println("=".repeat(78));
        topN(loaderGained, 30).forEach(s ->
            System.out.printf("  %4d mods  %s%n", loaderGained.get(s), truncate(s, 62)));

        // Residual SRG names are the honest signal of whether normalisation actually worked.
        // Anything above a fraction of a percent means the mapping table is incomplete and the
        // vanilla numbers below are measuring the mapping, not the API.
        double srgShare = 100.0 * srgLost / Math.max(1, lost.size());
        boolean normalized = srgShare < 1.0;

        Map<String, Integer> vanillaLost = filter(lost, RuleMiner::isVanilla);
        System.out.println("\n" + "=".repeat(78));
        System.out.printf("VANILLA API - MOST-DEPENDED-ON LOST SYMBOLS  [%s]%n",
                          normalized ? "TRUSTWORTHY" : "MAPPING-CONTAMINATED");
        System.out.printf("  %d of %d lost symbols still carry SRG names (%.1f%%).%n",
                          srgLost, lost.size(), srgShare);
        System.out.println("=".repeat(78));

        if (!normalized) {
            System.out.println("  Forge 1.20.1 runs SRG names; NeoForge 1.21.1 runs official Mojang names,");
            System.out.println("  so vanilla members differ for mapping reasons, not API reasons.");
            System.out.println("  -> Supply srg2official.tsv (build it with tools/SrgToOfficial.java).");
            return;
        }
        System.out.println("  This is the vanilla-bridge work list, in build order.");
        topN(vanillaLost, 25).forEach(s ->
            System.out.printf("  %4d mods  %s%n", vanillaLost.get(s), truncate(s, 62)));
    }

    private static Map<String, Integer> filter(Map<String, Integer> in,
                                               java.util.function.Predicate<String> keep) {
        Map<String, Integer> out = new HashMap<>();
        in.forEach((k, v) -> { if (keep.test(k)) out.put(k, v); });
        return out;
    }

    private static List<String> topN(Map<String, Integer> counts, int n) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(n)
                .map(Map.Entry::getKey)
                .toList();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    // ---- input -------------------------------------------------------------------------

    /** Reads modId, sourceFile and targetFile out of the analyzer's ground-truth-pairs.tsv. */
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
