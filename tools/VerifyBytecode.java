import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.SimpleVerifier;

/**
 * Runs the JVM's type checks over a translated jar, offline, without launching anything.
 *
 * <h2>Why</h2>
 *
 * The transformer's remaining failures are increasingly {@code VerifyError} rather than
 * {@code ClassNotFoundException}, and a VerifyError is the most expensive kind of failure this
 * project has. It costs a full ten-minute launch to find, it reports exactly one method, and the
 * JVM stops there -- so a jar with forty bad methods takes forty launches to survey.
 *
 * Every input to that answer is static. This does what the verifier does: walks each method with
 * a type-tracking data-flow analysis and reports every place the operand stack or a local holds
 * something the next instruction cannot accept.
 *
 * That matters most for the Holder work. Its whole risk is putting a {@code Holder} where mod
 * bytecode says {@code MobEffect}, or rebuilding an operand stack slightly wrong after a spill,
 * and both are precisely what this catches -- in seconds, over the entire jar, before anything
 * runs.
 *
 * <h2>Reading the output</h2>
 *
 * Findings are grouped by message shape rather than listed one per method, because a systematic
 * transformer bug produces the same error hundreds of times and the useful figure is how many
 * distinct shapes there are, not how many instances.
 *
 * A clean run is not proof the mod works -- verification says the types line up, not that the
 * behaviour is right. It is proof the class will load.
 *
 * Usage:
 *   java -cp "asm.jar;asm-tree.jar;asm-analysis.jar" tools/VerifyBytecode.java \
 *       <jar-to-check> <classpath-jar>...
 */
public class VerifyBytecode {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: VerifyBytecode <jar> <classpath-jar>...");
            System.exit(2);
        }
        Path jar = Path.of(args[0]);

        // The verifier needs to answer "is A assignable to B", which means loading A and B.
        // Giving it the jar under test plus the platform makes those answers real; without the
        // platform every vanilla type degrades to Object and the interesting errors vanish.
        List<URL> cp = new ArrayList<>();
        cp.add(jar.toUri().toURL());
        for (int i = 1; i < args.length; i++) {
            Path p = Path.of(args[i]);
            if (Files.isRegularFile(p)) cp.add(p.toUri().toURL());
        }
        ClassLoader loader = new URLClassLoader(cp.toArray(new URL[0]),
                VerifyBytecode.class.getClassLoader());

        Map<String, Integer> shapes = new LinkedHashMap<>();
        Map<String, String> firstExample = new LinkedHashMap<>();
        Map<String, Integer> absentDeps = new LinkedHashMap<>();
        int classes = 0, methods = 0, failedClasses = 0, failedMethods = 0, unreadable = 0;

        try (ZipFile zf = new ZipFile(jar.toFile())) {
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (!e.getName().endsWith(".class")) continue;
                ClassNode node = new ClassNode();
                try (var in = zf.getInputStream(e)) {
                    new ClassReader(in).accept(node, 0);
                } catch (Exception ex) {
                    unreadable++;
                    continue;
                }
                classes++;
                boolean classFailed = false;
                for (MethodNode m : node.methods) {
                    if (m.instructions == null || m.instructions.size() == 0) continue;
                    methods++;
                    try {
                        Analyzer<BasicValue> analyzer = new Analyzer<>(verifier(node, loader));
                        analyzer.analyze(node.name, m);
                    } catch (AnalyzerException | LinkageError ex) {
                        String raw = ex instanceof AnalyzerException
                                ? String.valueOf(ex.getMessage())
                                : ex.getClass().getSimpleName() + ": " + ex.getMessage();
                        String shape = classify(raw) + normalise(raw);
                        if (shape.startsWith(ABSENT_DEP)) {
                            absentDeps.merge(missingClass(raw), 1, Integer::sum);
                            continue;
                        }
                        failedMethods++;
                        classFailed = true;
                        shapes.merge(shape, 1, Integer::sum);
                        firstExample.putIfAbsent(shape, node.name + "." + m.name + m.desc);
                    }
                }
                if (classFailed) failedClasses++;
            }
        }

        System.out.printf("%s%n", jar.getFileName());
        System.out.printf("  %d classes, %d methods analysed%n", classes, methods);
        System.out.printf("  %d classes with a verification error (%d methods)%n",
                failedClasses, failedMethods);
        if (unreadable > 0) System.out.printf("  %d unreadable classes, skipped%n", unreadable);
        if (!absentDeps.isEmpty()) {
            System.out.printf("  %d other-mod classes absent from this classpath, not counted%n",
                    absentDeps.size());
        }

        if (shapes.isEmpty()) {
            System.out.println("  CLEAN - every method type-checks");
            return;
        }
        System.out.println();
        List<Map.Entry<String, Integer>> ranked = new ArrayList<>(shapes.entrySet());
        ranked.sort((a, b) -> b.getValue() - a.getValue());
        for (var en : ranked) {
            System.out.printf("%5d  %s%n", en.getValue(), en.getKey());
            System.out.printf("         first at  %s%n", firstExample.get(en.getKey()));
        }
        System.exit(1);
    }

    /** Prefix marking a finding that is about the verifier's classpath, not the bytecode. */
    private static final String ABSENT_DEP = "[absent-dependency] ";

    private static final java.util.regex.Pattern MISSING =
            java.util.regex.Pattern.compile("([A-Za-z_$][\\w$]*[./][\\w$./]*[\\w$])(?= not present|$)");

    /**
     * Sorts a failure into a finding or into noise, which the first sweep showed is most of the
     * work of reading one.
     *
     * A class the verifier cannot load produces the same exception whether the class was deleted
     * by the migration or merely absent from the classpath, and those are opposite conclusions.
     * The package decides: {@code net.minecraft} and {@code net.minecraftforge} are things
     * Easyport is responsible for supplying or renaming, so a missing one is a real gap. Anything
     * else is another mod that this run simply did not load, and reporting it buries the findings
     * -- 27 joml entries drowned the one genuine error in the first geckolib run.
     *
     * {@code IncompatibleClassChangeError} is never noise. It means the mod's class hierarchy is
     * illegal against the new platform -- extending something 1.21 made final, implementing
     * something that stopped being an interface, overriding a method that became final. Those
     * cannot be reached by a call-site rewrite at all, and the sweep found six of them.
     */
    private static String classify(String message) {
        if (message.contains("IncompatibleClassChangeError")) return "[hierarchy] ";
        boolean missingType = message.contains("not present")
                || message.contains("ClassNotFoundException")
                || message.contains("NoClassDefFoundError");
        if (!missingType) return "[types] ";
        String cls = missingClass(message);
        return cls.startsWith("net.minecraft") || cls.startsWith("net/minecraft")
                || cls.startsWith("net.neoforged") || cls.startsWith("net/neoforged")
                ? "[missing-platform-type] " : ABSENT_DEP;
    }

    private static String missingClass(String message) {
        var m = MISSING.matcher(message);
        String last = "";
        while (m.find()) last = m.group(1);
        return last;
    }

    /**
     * A verifier that resolves types through the supplied classpath.
     *
     * {@link SimpleVerifier} resolves by loading classes, and its default loader is whatever
     * loaded ASM -- which has no Minecraft on it. Pointing it at the real classpath is the
     * difference between checking assignability and assuming it.
     */
    private static SimpleVerifier verifier(ClassNode node, ClassLoader loader) {
        SimpleVerifier v = new SimpleVerifier(
                Type.getObjectType(node.name),
                node.superName == null ? null : Type.getObjectType(node.superName),
                node.interfaces.stream().map(Type::getObjectType).toList(),
                (node.access & org.objectweb.asm.Opcodes.ACC_INTERFACE) != 0);
        v.setClassLoader(loader);
        return v;
    }

    /**
     * Strips the instruction-specific detail out of a message so like errors group together.
     *
     * Without this every finding is unique -- the message carries an instruction index and the
     * exact types involved -- and a systematic bug reads as hundreds of unrelated problems
     * instead of one.
     */
    private static String normalise(String message) {
        if (message == null) return "(no message)";
        return message.replaceAll("\\b\\d+\\b", "N")
                      .replaceAll("\\s+", " ")
                      .trim();
    }
}
