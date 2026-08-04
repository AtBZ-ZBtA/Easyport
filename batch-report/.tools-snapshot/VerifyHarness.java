import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 1 verification harness: measures whether a translated mod actually works.
 *
 * "It loaded without crashing" is the weak test everyone reaches for first, and it passes for
 * mods that are badly broken — half their blocks missing, an entity type silently dropped. So
 * this measures registry content instead: run the candidate jar, run the author's own port of
 * the same mod, and compare what each registered, entry by entry.
 *
 * That comparison is the coverage metric the whole project steers by. Authoring rules is
 * cheap; knowing which rules are wrong is not, and this is what makes it cheap.
 *
 * Runs are differential. Every launch registers the harness's own content plus NeoForge's, so
 * a baseline launch with only the inspector is subtracted from each candidate launch. What
 * remains is exactly what the jar under test contributed — no hardcoded ignore lists to drift
 * out of date.
 *
 * Uses runData as the engine: full FML boot including mods-folder discovery, headless, about
 * ten seconds, and no EULA (only a dedicated server needs one).
 *
 * Run:
 *   java tools/VerifyHarness.java <runtimeDir> <supportJars,comma-separated> <candidateJar> [referenceJar] [outDir]
 */
public class VerifyHarness {

    private static final Pattern ARRAY = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\\[([^\\]]*)\\]");
    private static final Pattern ID = Pattern.compile("\"([^\"]+)\"");
    private static final String INSPECTION = "easyport-inspection.json";

    /** One launch: what it registered, which mods loaded, and why the launch failed if it did. */
    record Run(Map<String, Set<String>> registries, Set<String> loadedMods,
               boolean harnessRan, String failure) {
        int entryCount() { return registries.values().stream().mapToInt(Set::size).sum(); }
    }

    /**
     * Reads the modId a jar declares, so the harness can tell whether it actually loaded.
     *
     * Only [[mods]] blocks count; dependency blocks name *other* mods and would make every jar
     * claim to provide "neoforge". Same trap as CorpusAnalyzer.
     */
    private static Set<String> declaredModIds(Path jar) throws IOException {
        Set<String> ids = new TreeSet<>();
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
            for (String name : List.of("META-INF/neoforge.mods.toml", "META-INF/mods.toml")) {
                var entry = zip.getEntry(name);
                if (entry == null) continue;
                String toml = new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
                boolean inMods = false;
                for (String raw : toml.split("\\R")) {
                    String line = raw.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    if (line.startsWith("[")) { inMods = line.startsWith("[[mods]]"); continue; }
                    if (!inMods) continue;
                    Matcher m = Pattern.compile("modId\\s*=\\s*\"([^\"]+)\".*").matcher(line);
                    if (m.matches()) ids.add(m.group(1));
                }
                break;
            }
        }
        return ids;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: java tools/VerifyHarness.java <runtimeDir> <inspectorJar> "
                             + "<candidateJar> [referenceJar] [outDir]");
            System.exit(2);
        }
        Path runtime   = Paths.get(args[0]).toAbsolutePath();
        List<Path> support = new ArrayList<>();
        for (String s : args[1].split(",")) support.add(Paths.get(s).toAbsolutePath());
        Path candidate = Paths.get(args[2]).toAbsolutePath();
        Path reference = args.length > 3 && !args[3].equals("-") ? Paths.get(args[3]).toAbsolutePath() : null;
        Path out       = Paths.get(args.length > 4 ? args[4] : "verify-report").toAbsolutePath();
        Files.createDirectories(out);

        // The baseline depends only on the support jars, so batch runs would otherwise pay a
        // full launch per mod to recompute an identical result. Cached beside the report and
        // reused; delete the file to force a rebuild after changing forge-compat.
        // Keyed by the support set, not a single fixed name. Once dependencies are loaded
        // alongside a candidate they register content of their own, so the baseline has to
        // include them or their entries land in the candidate's delta and inflate it. Mods
        // sharing a dependency set still share a cached baseline.
        StringBuilder key = new StringBuilder();
        support.stream().map(p -> p.getFileName().toString()).sorted().forEach(key::append);
        Path baselineCache = out.resolve("baseline-"
                + Integer.toHexString(key.toString().hashCode()) + ".json");
        Run baseline;
        if (Files.exists(baselineCache)) {
            String body = Files.readString(baselineCache, StandardCharsets.UTF_8);
            baseline = new Run(parse(body), parseLoadedMods(body), true, null);
            System.out.printf("Baseline (cached): %d entries%n", baseline.entryCount());
        } else {
            System.out.println("Baseline (support jars only) ...");
            baseline = launch(runtime, support, null);
            if (baseline.harnessRan()) {
                Files.copy(runtime.resolve("run").resolve(INSPECTION), baselineCache,
                           StandardCopyOption.REPLACE_EXISTING);
            }
        }
        if (!baseline.harnessRan()) {
            System.out.println("  BASELINE FAILED: " + baseline.failure());
            System.out.println("  The harness itself cannot boot; nothing below would be meaningful.");
            System.exit(1);
        }
        if (!Files.exists(baselineCache)) System.out.printf("  baseline: %d entries%n", baseline.entryCount());

        System.out.println("\nCandidate: " + candidate.getFileName());
        Run candRun = launch(runtime, support, candidate);
        Map<String, Set<String>> candDelta = subtract(candRun.registries(), baseline.registries());
        boolean candLoaded = report("candidate", candRun, candDelta, candidate);

        Map<String, Set<String>> refDelta = null;
        if (reference != null) {
            System.out.println("\nReference: " + reference.getFileName());
            Run refRun = launch(runtime, support, reference);
            refDelta = subtract(refRun.registries(), baseline.registries());
            report("reference", refRun, refDelta, reference);
        }

        writeJson(out.resolve("candidate-delta.json"), candDelta);
        if (refDelta != null) {
            writeJson(out.resolve("reference-delta.json"), refDelta);
            coverage(candDelta, refDelta, candLoaded, out.resolve("coverage.tsv"));
            resourceCoverage(candidate, reference, out.resolve("resource-coverage.tsv"));
        }
        System.out.println("\nWrote " + out);
    }

    // ---- launching ---------------------------------------------------------------------

    /** Stages the mods folder, runs datagen, and reads back what the game registered. */
    private static Run launch(Path runtime, List<Path> support, Path candidate) throws Exception {
        Path modsDir = runtime.resolve("run/mods");
        Files.createDirectories(modsDir);
        try (DirectoryStream<Path> s = Files.newDirectoryStream(modsDir, "*.jar")) {
            for (Path p : s) Files.delete(p);
        }
        // Support jars (inspector, forge-compat) go into every run including the baseline, so
        // whatever they register subtracts out and the delta stays purely the jar under test.
        for (Path s : support) Files.copy(s, modsDir.resolve(s.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        if (candidate != null) {
            Files.copy(candidate, modsDir.resolve(candidate.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }

        Path inspection = runtime.resolve("run").resolve(INSPECTION);
        Files.deleteIfExists(inspection);

        // ProcessBuilder runs .bat directly on Windows. Going through `cmd /c` fails silently
        // when launched from a POSIX shell -- cmd opens and exits without running anything.
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        String wrapper = runtime.resolve(windows ? "gradlew.bat" : "gradlew").toString();
        List<String> cmd = List.of(wrapper, "runData", "--no-daemon", "--console=plain");

        ProcessBuilder pb = new ProcessBuilder(cmd).directory(runtime.toFile()).redirectErrorStream(true);
        Process proc = pb.start();
        String log = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        proc.waitFor();

        // A missing inspection file is the real failure signal. The gradle exit code reflects
        // the whole datagen run, which can fail for reasons unrelated to whether mods loaded.
        if (!Files.exists(inspection)) {
            // Keep the whole log: a launch failure is exactly when the detail is needed, and
            // re-running to reproduce it costs another full boot.
            // Named after the jar under test, not a single shared file. A batch run overwrote
            // that shared file on every mod, so by the time results were read the diagnostics
            // for all but the last failure were gone.
            Path logFile = runtime.resolve("run").resolve("failed-" + logTag(candidate) + ".log");
            try { Files.writeString(logFile, log, StandardCharsets.UTF_8); } catch (IOException ignored) {}
            return new Run(Map.of(), Set.of(), false, firstFailure(log) + "  [full log: " + logFile + "]");
        }
        String body = Files.readString(inspection, StandardCharsets.UTF_8);
        // Clear any failure log from an earlier run of this same jar. Without this a mod that
        // has since been fixed keeps a stale log on disk, and reading it later reports a
        // problem that no longer exists -- which is worse than having no log at all.
        Files.deleteIfExists(runtime.resolve("run").resolve("failed-" + logTag(candidate) + ".log"));
        return new Run(parse(body), parseLoadedMods(body), true, null);
    }

    /** Names the failure log after the jar under test, so a batch does not clobber its own. */
    private static String logTag(Path candidate) {
        return candidate == null ? "baseline"
             : candidate.getFileName().toString().replaceAll("\\.jar$", "");
    }

    /** Pulls the most informative line out of a failed launch, for the report. */
    private static String firstFailure(String log) {
        for (String line : log.split("\\R")) {
            if (line.contains("requires") && line.contains("or above")) return line.trim();
            if (line.contains("Caused by:")) return line.trim();
            if (line.contains("FATAL")) return line.trim();
        }
        return "no inspection file produced and no diagnostic found";
    }

    // ---- comparison --------------------------------------------------------------------

    private static Map<String, Set<String>> subtract(Map<String, Set<String>> a,
                                                     Map<String, Set<String>> b) {
        Map<String, Set<String>> out = new TreeMap<>();
        a.forEach((reg, ids) -> {
            Set<String> diff = new TreeSet<>(ids);
            diff.removeAll(b.getOrDefault(reg, Set.of()));
            if (!diff.isEmpty()) out.put(reg, diff);
        });
        return out;
    }

    /**
     * Reports whether the jar under test actually loaded, not merely whether the run finished.
     *
     * These are different questions and conflating them is the failure this harness exists to
     * prevent. An untranslated Forge jar is rejected during discovery, the run continues
     * happily without it, and the launch looks successful — while the mod contributed nothing.
     * Checking the declared modId against the loaded mod list distinguishes "loaded but
     * registered nothing" from "never loaded at all", which need completely different fixes.
     */
    private static boolean report(String label, Run run, Map<String, Set<String>> delta,
                                  Path jar) throws IOException {
        if (!run.harnessRan()) {
            System.out.printf("  %s: LAUNCH FAILED: %s%n", label, run.failure());
            return false;
        }
        Set<String> declared = declaredModIds(jar);
        boolean loaded = declared.isEmpty() || run.loadedMods().stream().anyMatch(declared::contains);

        int n = delta.values().stream().mapToInt(Set::size).sum();
        if (!loaded) {
            System.out.printf("  %s: NOT LOADED - declared %s, absent from the loaded mod list%n",
                              label, declared);
            System.out.println("           (the launch itself succeeded; the jar was rejected)");
            return false;
        }
        System.out.printf("  %s: loaded, contributed %d entries across %d registries%n",
                          label, n, delta.size());
        delta.forEach((reg, ids) -> System.out.printf("    %-42s %d%n", reg, ids.size()));
        return true;
    }

    /**
     * Scores the candidate against the reference port.
     *
     * Missing entries are the real defect: content the author's port has that the translation
     * dropped. Extra entries are reported separately rather than counted as failures — they
     * are usually the author adding features between versions, which is drift rather than a
     * translation bug.
     */
    private static void coverage(Map<String, Set<String>> cand, Map<String, Set<String>> ref,
                                 boolean candLoaded, Path out) throws IOException {
        StringBuilder sb = new StringBuilder("registry\tstatus\tentry\n");
        int matched = 0, missing = 0, extra = 0;

        Set<String> registries = new TreeSet<>();
        registries.addAll(cand.keySet());
        registries.addAll(ref.keySet());

        for (String reg : registries) {
            Set<String> c = cand.getOrDefault(reg, Set.of());
            Set<String> r = ref.getOrDefault(reg, Set.of());
            for (String id : r) {
                if (c.contains(id)) { matched++; sb.append(reg).append("\tMATCHED\t").append(id).append('\n'); }
                else { missing++; sb.append(reg).append("\tMISSING\t").append(id).append('\n'); }
            }
            for (String id : c) {
                if (!r.contains(id)) { extra++; sb.append(reg).append("\tEXTRA\t").append(id).append('\n'); }
            }
        }
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);

        int expected = matched + missing;
        System.out.println("\n" + "=".repeat(70));
        System.out.println("COVERAGE");
        System.out.println("=".repeat(70));
        if (!candLoaded) {
            System.out.println("  0% - candidate did not load");
        } else if (expected == 0) {
            System.out.println("  n/a - reference registered nothing to compare against");
        } else {
            System.out.printf("  %d/%d reference entries reproduced = %.1f%%%n",
                              matched, expected, 100.0 * matched / expected);
            System.out.printf("  %d missing, %d extra (extras are usually author drift, not defects)%n",
                              missing, extra);
        }
    }

    // ---- static resource comparison ----------------------------------------------------

    /**
     * Compares datapack and asset content without launching anything.
     *
     * Registry content needs a running game, but recipes, tags, loot tables and models are
     * just files in the jar, so paying a full boot to compare them would be waste. This also
     * catches a failure the registry check cannot see: a translated jar that loads fine and
     * registers every block, but silently lost its recipes because the directory rename was
     * not applied. Paths are compared verbatim, which is the point — a leftover `recipes/`
     * directory shows up as missing content, exactly as the game would treat it.
     */
    private static void resourceCoverage(Path candidate, Path reference, Path out) throws IOException {
        Set<String> cand = resourcePaths(candidate);
        Set<String> ref = resourcePaths(reference);

        StringBuilder sb = new StringBuilder("status\tpath\n");
        int matched = 0, missing = 0, extra = 0;
        for (String p : ref) {
            if (cand.contains(p)) { matched++; }
            else { missing++; sb.append("MISSING\t").append(p).append('\n'); }
        }
        for (String p : cand) {
            if (!ref.contains(p)) { extra++; sb.append("EXTRA\t").append(p).append('\n'); }
        }
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);

        System.out.println("\n" + "=".repeat(70));
        System.out.println("RESOURCE COVERAGE  (datapack + assets, no launch required)");
        System.out.println("=".repeat(70));
        int expected = matched + missing;
        if (expected == 0) {
            System.out.println("  n/a - reference ships no data/ or assets/ content");
        } else {
            System.out.printf("  %d/%d reference resources present = %.1f%%%n",
                              matched, expected, 100.0 * matched / expected);
            System.out.printf("  %d missing, %d extra%n", missing, extra);
        }
    }

    private static Set<String> resourcePaths(Path jar) throws IOException {
        Set<String> paths = new TreeSet<>();
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var e = entries.nextElement();
                String n = e.getName();
                if (e.isDirectory() || n.endsWith(".class")) continue;
                if (n.startsWith("data/") || n.startsWith("assets/")) paths.add(n);
            }
        }
        return paths;
    }

    // ---- inspection file ---------------------------------------------------------------

    /** Reads the loadedMods array the inspector writes. */
    private static Set<String> parseLoadedMods(String json) {
        Set<String> mods = new TreeSet<>();
        Matcher m = Pattern.compile("\"loadedMods\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(json);
        if (m.find()) {
            Matcher im = ID.matcher(m.group(1));
            while (im.find()) mods.add(im.group(1));
        }
        return mods;
    }

    private static Map<String, Set<String>> parse(String json) {
        Map<String, Set<String>> out = new TreeMap<>();
        Matcher m = ARRAY.matcher(json);
        while (m.find()) {
            // loadedMods is metadata, not a registry; the array regex cannot tell them apart.
            if (m.group(1).equals("loadedMods")) continue;
            Set<String> ids = new TreeSet<>();
            Matcher im = ID.matcher(m.group(2));
            while (im.find()) ids.add(im.group(1));
            if (!ids.isEmpty()) out.put(m.group(1), ids);
        }
        return out;
    }

    private static void writeJson(Path path, Map<String, Set<String>> data) throws IOException {
        StringBuilder sb = new StringBuilder("{\n");
        List<String> parts = new ArrayList<>();
        data.forEach((reg, ids) -> {
            List<String> quoted = new ArrayList<>();
            for (String id : ids) quoted.add("\"" + id + "\"");
            parts.add("  \"" + reg + "\": [" + String.join(", ", quoted) + "]");
        });
        sb.append(String.join(",\n", parts)).append("\n}\n");
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }
}
