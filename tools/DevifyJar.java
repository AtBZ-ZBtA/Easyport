package easyport.tools;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

/**
 * Renames SRG members to official ones so a production Forge 1.20.1 jar can run in a dev launch.
 *
 * <h2>Why this is needed, and why it is not part of translation</h2>
 *
 * Forge 1.20.1 has two naming worlds. A mod shipped to players carries SRG member names; a
 * ForgeGradle dev environment runs vanilla under official names and cannot resolve SRG at all.
 * That makes the only backward harness this project has unable to load the very jars it most
 * needs to measure — the *reference* ports, the author's own 1.20.1 builds, which are the whole
 * basis of comparison.
 *
 * Verified rather than assumed: an unmodified ATM9 jar, straight off CurseForge, fails in
 * {@code runData} with {@code NoSuchFieldError: f_279569_}.
 *
 * So this does one thing and nothing else. It is not a translation and must never grow into one:
 * every rule, shim and structural pass belongs in {@code Translate}, and a second thing that
 * rewrites mod bytecode would be a second thing to keep in step. The only reason it is separate
 * is that translation and de-obfuscation are different jobs that happen to share a table.
 *
 * <h2>What it does not fix</h2>
 *
 * Mixin refmaps and access transformers still name SRG members. A reference port whose behaviour
 * depends on a mixin will not behave identically in a dev launch — but registry *content*, which
 * is what the harness measures, comes from ordinary registration code.
 *
 * Run:
 *   java -cp "&lt;asm&gt;;&lt;asm-commons&gt;" tools/DevifyJar.java &lt;in.jar&gt; &lt;out.jar&gt; mappings/srg2official.tsv
 */
public class DevifyJar {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: java -cp \"<asm>;<asm-commons>\" tools/DevifyJar.java "
                             + "<inputJar> <outputJar> <srg2official.tsv>");
            System.exit(2);
        }
        Path in = Paths.get(args[0]), out = Paths.get(args[1]);

        Map<String, String> srgToOfficial = new HashMap<>();
        for (String line : Files.readAllLines(Paths.get(args[2]), StandardCharsets.UTF_8)) {
            String[] c = line.split("\t");
            if (c.length == 2 && !c[0].equals("srg")) srgToOfficial.put(c[0], c[1]);
        }

        Remapper remapper = new Remapper() {
            @Override public String mapMethodName(String owner, String name, String desc) {
                return srgToOfficial.getOrDefault(name, name);
            }
            @Override public String mapFieldName(String owner, String name, String desc) {
                return srgToOfficial.getOrDefault(name, name);
            }
            @Override public String mapInvokeDynamicMethodName(String name, String desc) {
                return srgToOfficial.getOrDefault(name, name);
            }
        };

        int classes = 0, copied = 0;
        Files.createDirectories(out.toAbsolutePath().getParent());
        try (ZipFile zip = new ZipFile(in.toFile());
             ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(out))) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory()) continue;
                byte[] data;
                try (InputStream is = zip.getInputStream(e)) { data = is.readAllBytes(); }
                String name = e.getName();

                // Signatures cover the pre-rename bytes and cannot survive rewriting.
                if (name.startsWith("META-INF/") && (name.endsWith(".SF")
                        || name.endsWith(".RSA") || name.endsWith(".DSA"))) continue;

                if (name.endsWith(".class")) {
                    ClassReader reader = new ClassReader(data);
                    ClassWriter writer = new ClassWriter(0);
                    reader.accept(new ClassRemapper(writer, remapper), 0);
                    data = writer.toByteArray();
                    classes++;
                } else {
                    copied++;
                }
                zos.putNextEntry(new ZipEntry(name));
                zos.write(data);
                zos.closeEntry();
            }
        }
        System.out.printf("devified %s: %d classes renamed, %d resources copied -> %s%n",
                          in.getFileName(), classes, copied, out);
    }
}
