import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Enumerates every member of a package that the corpus actually calls, with a count of how many
 * jars call each one.
 *
 * {@link UsageScan} answers "how many mods touch this API"; this answers "which parts, and how
 * hard would they be to reproduce". Both questions come up before writing any shim, and the
 * second one is the one that has been guessed wrong: a shim built from memory of an API covers
 * the methods that came to mind, and the corpus then fails on the ones that did not.
 *
 * Written for networking, where 172 of 433 jars reference {@code SimpleChannel} and a shim built
 * on recollection would have been a coin flip. Reading the real call sites first turns the shim
 * from a guess into a transcription.
 *
 * <h2>Counting</h2>
 *
 * Jars, not call sites. One mod calling {@code sendToServer} in forty places is one mod's worth
 * of evidence, and raw occurrence counts would let a single heavy user outvote the corpus.
 *
 * Includes invokedynamic bootstrap arguments, so method references -- {@code Foo::decode} passed
 * as a decoder, which is the dominant idiom in this API -- are not missed. They are ordinary
 * constant-pool handles rather than instructions, and a scan that only walked opcodes would
 * silently under-report exactly the members most worth knowing about.
 *
 * Usage:  java -cp asm.jar tools/MemberScan.java <jar-dir> <owner-prefix> [max-jars]
 */
public class MemberScan {

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: MemberScan <jar-dir> <owner-prefix> [max-jars]");
            System.exit(2);
        }
        Path dir = Path.of(args[0]);
        String prefix = args[1];
        int maxJars = args.length > 2 ? Integer.parseInt(args[2]) : Integer.MAX_VALUE;

        // "owner.name descriptor" -> jars that reference it
        Map<String, Set<String>> members = new TreeMap<>();
        Map<String, Set<String>> types = new TreeMap<>();

        List<Path> jars;
        try (var stream = Files.walk(dir)) {
            jars = stream.filter(p -> p.toString().endsWith(".jar")).sorted().limit(maxJars).toList();
        }

        int scanned = 0;
        for (Path jar : jars) {
            String jarName = jar.getFileName().toString();
            try (ZipFile zf = new ZipFile(jar.toFile())) {
                var entries = zf.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    if (!e.getName().endsWith(".class")) continue;
                    try (InputStream in = zf.getInputStream(e)) {
                        new ClassReader(in).accept(
                                new Collector(prefix, jarName, members, types), ClassReader.SKIP_FRAMES);
                    } catch (Exception ignored) {
                        // A jar can legitimately contain classes this ASM cannot parse (a newer
                        // class version, or a deliberately mangled coremod). Skipping one class
                        // loses a data point; aborting the scan loses the whole survey.
                    }
                }
            } catch (IOException ex) {
                System.err.println("skip (unreadable): " + jarName);
                continue;
            }
            scanned++;
        }

        System.out.println("scanned " + scanned + " jars for owners under " + prefix);
        System.out.println();
        System.out.println("=== TYPES (jars referencing) ===");
        types.entrySet().stream()
             .sorted((a, b) -> b.getValue().size() - a.getValue().size())
             .forEach(en -> System.out.printf("%5d  %s%n", en.getValue().size(), en.getKey()));

        System.out.println();
        System.out.println("=== MEMBERS (jars calling) ===");
        members.entrySet().stream()
               .sorted((a, b) -> b.getValue().size() - a.getValue().size())
               .forEach(en -> System.out.printf("%5d  %s%n", en.getValue().size(), en.getKey()));
    }

    private static final class Collector extends ClassVisitor {
        private final String prefix;
        private final String jar;
        private final Map<String, Set<String>> members;
        private final Map<String, Set<String>> types;

        Collector(String prefix, String jar, Map<String, Set<String>> members,
                  Map<String, Set<String>> types) {
            super(Opcodes.ASM9);
            this.prefix = prefix;
            this.jar = jar;
            this.members = members;
            this.types = types;
        }

        private void noteType(String owner) {
            if (owner != null && owner.startsWith(prefix)) {
                types.computeIfAbsent(owner, k -> new HashSet<>()).add(jar);
            }
        }

        private void noteMember(String owner, String name, String desc, String kind) {
            if (owner == null || !owner.startsWith(prefix)) return;
            noteType(owner);
            members.computeIfAbsent(kind + " " + owner + "." + name + " " + desc,
                                    k -> new HashSet<>()).add(jar);
        }

        @Override
        public void visit(int version, int access, String name, String sig, String superName,
                          String[] interfaces) {
            noteType(superName);
            if (interfaces != null) for (String i : interfaces) noteType(i);
        }

        @Override
        public MethodVisitor visitMethod(int a, String n, String d, String s, String[] ex) {
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitMethodInsn(int op, String owner, String name, String desc,
                                            boolean itf) {
                    noteMember(owner, name, desc, "M");
                }

                @Override
                public void visitFieldInsn(int op, String owner, String name, String desc) {
                    noteMember(owner, name, desc, "F");
                }

                @Override
                public void visitTypeInsn(int op, String type) {
                    noteType(type);
                }

                @Override
                public void visitInvokeDynamicInsn(String name, String desc, Handle bsm,
                                                   Object... bsmArgs) {
                    // The lambda's target method is a bootstrap argument, not an instruction.
                    for (Object arg : bsmArgs) {
                        if (arg instanceof Handle h) noteMember(h.getOwner(), h.getName(), h.getDesc(), "M");
                    }
                }
            };
        }
    }
}
