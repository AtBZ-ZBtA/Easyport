import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/**
 * Prints the mod ids a jar requires, one per line.
 *
 * Exists to lift the verification harness's ceiling. 161 of the 288 paired corpus mods (56%)
 * declare inter-mod dependencies, and the harness loads only the candidate plus support jars,
 * so those mods could never load no matter how good the translation was. Every coverage figure
 * so far has come from the 44% that happens to be dependency-free — a sample that is also
 * skewed, since standalone mods are systematically simpler than mods with dependencies.
 *
 * Feeding this into the batch runner lets each mod's dependencies be translated and loaded
 * alongside it. That is also the first point at which the tool gets exercised the way a user
 * would actually use it: on a set of mods that depend on each other, not one jar in isolation.
 *
 * Only *required* dependencies are printed. Optional ones are declared by mods that expect to
 * run without them, so pulling them in would inflate the load for no gain and drag in failures
 * from mods that were never needed.
 *
 * minecraft, forge and neoforge are skipped — they are the platform, always present.
 *
 * Run:
 *   java tools/Deps.java &lt;jar&gt;
 */
public class Deps {

    private static final Set<String> PLATFORM = Set.of("minecraft", "forge", "neoforge");

    private static final Pattern MOD_ID = Pattern.compile("modId\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern MANDATORY = Pattern.compile("mandatory\\s*=\\s*(true|false)");
    private static final Pattern TYPE = Pattern.compile("type\\s*=\\s*\"([^\"]+)\"");

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("usage: java tools/Deps.java <jar>");
            System.err.println("       java tools/Deps.java <jar> <modsDir> <manifest.tsv>   (transitive)");
            System.exit(2);
        }
        StringBuilder sb = new StringBuilder();

        if (args.length >= 3) {
            // Transitive closure, emitted as "modId<TAB>jarFileName".
            //
            // Direct dependencies are not enough: loading ars_creo needs create, and create
            // needs its own. A partial graph fails at load in a way that looks like a
            // translation bug, so the whole closure is resolved up front.
            Map<String, String> idToJar = readManifest(Paths.get(args[2]));
            Path modsDir = Paths.get(args[1]);
            Set<String> seen = new LinkedHashSet<>();
            Deque<Path> queue = new ArrayDeque<>();
            queue.add(Paths.get(args[0]));

            while (!queue.isEmpty()) {
                Path jar = queue.poll();
                for (String id : requiredDependencies(jar)) {
                    if (!seen.add(id)) continue;          // also breaks dependency cycles
                    String file = idToJar.get(id);
                    if (file == null) continue;           // not in this corpus; caller reports it
                    Path depJar = modsDir.resolve(file);
                    if (!Files.isRegularFile(depJar)) continue;
                    sb.append(id).append('\t').append(file).append('\n');
                    queue.add(depJar);
                }
            }
        } else {
            // Explicit \n rather than println: on Windows println emits \r\n, and a shell
            // reading this with $(...) then carries a trailing \r into every value but the
            // last. That made lookups silently miss -- ars_nouveau resolved to nothing while
            // create, being last, worked fine.
            for (String id : requiredDependencies(Paths.get(args[0]))) sb.append(id).append('\n');
        }
        System.out.print(sb);
    }

    /** modId -> source-side jar filename, from CorpusAnalyzer's manifest. */
    private static Map<String, String> readManifest(Path tsv) throws IOException {
        Map<String, String> map = new HashMap<>();
        for (String line : Files.readAllLines(tsv, StandardCharsets.UTF_8)) {
            String[] c = line.split("\t", -1);
            if (c.length >= 16 && c[0].equals("source")) map.putIfAbsent(c[1], c[15]);
        }
        return map;
    }

    static Set<String> requiredDependencies(Path jar) throws IOException {
        Set<String> deps = new LinkedHashSet<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            String toml = null;
            for (String name : List.of("META-INF/neoforge.mods.toml", "META-INF/mods.toml")) {
                var e = zip.getEntry(name);
                if (e == null) continue;
                toml = new String(zip.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
                break;
            }
            if (toml == null) return deps;

            // Walk block by block. A dependency's modId and its required/optional flag are
            // separate lines, so both have to be read against the enclosing block rather than
            // matched independently -- the same trap as reading modId out of [[mods]].
            boolean inDependency = false;
            String currentId = null;
            boolean required = true;

            for (String raw : (toml + "\n[end]").split("\\R")) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                if (line.startsWith("[")) {
                    if (inDependency && currentId != null && required
                            && !PLATFORM.contains(currentId)) {
                        deps.add(currentId);
                    }
                    inDependency = line.startsWith("[[dependencies.");
                    currentId = null;
                    required = true;
                    continue;
                }
                if (!inDependency) continue;

                Matcher m;
                if ((m = MOD_ID.matcher(line)).find()) currentId = m.group(1);
                else if ((m = MANDATORY.matcher(line)).find()) required = Boolean.parseBoolean(m.group(1));
                else if ((m = TYPE.matcher(line)).find()) required = m.group(1).equals("required");
            }
        }
        return deps;
    }
}
