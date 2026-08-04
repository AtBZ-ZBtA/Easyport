import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Counts how many corpus jars reference each of a set of symbols.
 *
 * The question this answers comes up on every shim decision: is this API used by one mod or by
 * eighty? A shim for one mod can be a stub; a shim for eighty has to be right. Guessing that
 * from the single failing mod in front of me has been wrong often enough to be worth a tool.
 *
 * <h2>How it looks</h2>
 *
 * It searches raw class-file bytes for the symbol as an ASCII substring rather than parsing the
 * constant pool properly. Every class name, method name and descriptor a class references lives
 * in its constant pool as a CONSTANT_Utf8, stored as plain bytes, so a substring search finds
 * every genuine reference -- no false negatives for the thing being asked about.
 *
 * False positives are possible in principle (a string literal that happens to contain the
 * symbol) and unimportant in practice: nothing writes "net/minecraftforge/eventbus/api/
 * GenericEvent" as data. Counting jars rather than occurrences also makes the number robust to
 * a single class mentioning something repeatedly.
 *
 * Usage:  java tools/UsageScan.java <jar-dir> <symbol> [symbol...]
 * Symbols use internal form (slashes) for types, bare names for methods.
 */
public class UsageScan {

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: UsageScan <jar-dir> <symbol> [symbol...]");
            System.exit(2);
        }

        Path dir = Path.of(args[0]);
        List<String> symbols = new ArrayList<>();
        for (int i = 1; i < args.length; i++) symbols.add(args[i]);

        // symbol -> jars that reference it. Ordered so output follows the argument order.
        Map<String, List<String>> hits = new LinkedHashMap<>();
        for (String s : symbols) hits.put(s, new ArrayList<>());

        List<Path> jars;
        try (var stream = Files.walk(dir)) {
            jars = stream.filter(p -> p.toString().endsWith(".jar")).sorted().toList();
        }

        int scanned = 0;
        for (Path jar : jars) {
            Map<String, Boolean> found = new TreeMap<>();
            try (ZipFile zf = new ZipFile(jar.toFile())) {
                var entries = zf.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    if (!e.getName().endsWith(".class")) continue;
                    byte[] data;
                    try (InputStream in = zf.getInputStream(e)) {
                        data = readAll(in);
                    }
                    String text = new String(data, StandardCharsets.ISO_8859_1);
                    for (String s : symbols) {
                        if (found.containsKey(s)) continue;
                        if (text.contains(s)) found.put(s, Boolean.TRUE);
                    }
                    // Every symbol seen already -- no point reading the rest of this jar.
                    if (found.size() == symbols.size()) break;
                }
            } catch (IOException ex) {
                System.err.println("skip (unreadable): " + jar.getFileName());
                continue;
            }
            scanned++;
            for (String s : found.keySet()) hits.get(s).add(jar.getFileName().toString());
        }

        System.out.println("scanned " + scanned + " jars under " + dir);
        System.out.println();
        for (var entry : hits.entrySet()) {
            List<String> js = entry.getValue();
            System.out.printf("%-60s %4d jars%n", entry.getKey(), js.size());
            js.stream().sorted(Comparator.naturalOrder()).limit(40)
              .forEach(j -> System.out.println("      " + j));
            if (js.size() > 40) System.out.println("      ... and " + (js.size() - 40) + " more");
            System.out.println();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
