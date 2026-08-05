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
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

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
 * <h2>Mixin coordinates are text, and getting that wrong aborts the launch</h2>
 *
 * The bytecode remapper only reaches real member references. Mixins address their targets as
 * *strings* — refmap JSON, {@code @At(target = "...")}, {@code @Accessor("f_12345_")} — and an
 * access transformer is a text file of them. Renaming only the bytecode leaves every coordinate
 * naming an SRG member the dev environment cannot resolve.
 *
 * That is not a degraded reference, it is no reference at all: Mixin throws
 * {@code Critical injection failure} during apply, which takes the whole launch down. lootr's own
 * 1.20.1 build failed exactly that way before this pass existed, and with it every mixin-carrying
 * mod in the corpus — 136 of them — was unmeasurable on the reference side.
 *
 * The same table serves, because SRG names are globally unique and appear nowhere else: a token
 * matching {@code m_\d+_} or {@code f_\d+_} is a member name wherever it is found.
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

        int classes = 0, copied = 0, texts = 0;
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
                    ClassNode node = new ClassNode();
                    reader.accept(new ClassRemapper(node, remapper), 0);
                    // Mixin annotations carry their coordinates as strings, which the remapper
                    // above never sees.
                    remapStrings(node.visibleAnnotations, srgToOfficial);
                    remapStrings(node.invisibleAnnotations, srgToOfficial);
                    for (MethodNode m : node.methods) {
                        remapStrings(m.visibleAnnotations, srgToOfficial);
                        remapStrings(m.invisibleAnnotations, srgToOfficial);
                    }
                    for (FieldNode f : node.fields) {
                        remapStrings(f.visibleAnnotations, srgToOfficial);
                        remapStrings(f.invisibleAnnotations, srgToOfficial);
                    }
                    ClassWriter writer = new ClassWriter(0);
                    node.accept(writer);
                    data = writer.toByteArray();
                    classes++;
                } else {
                    if (isRefmap(name) || isAccessTransformer(name)) {
                        data = remapText(new String(data, StandardCharsets.UTF_8), srgToOfficial)
                                .getBytes(StandardCharsets.UTF_8);
                        texts++;
                    }
                    copied++;
                }
                zos.putNextEntry(new ZipEntry(name));
                zos.write(data);
                zos.closeEntry();
            }
        }
        System.out.printf("devified %s: %d classes renamed, %d text files remapped, "
                        + "%d resources copied -> %s%n",
                          in.getFileName(), classes, texts, copied, out);
    }

    private static final java.util.regex.Pattern SRG_TOKEN =
            java.util.regex.Pattern.compile("\\b([mf]_\\d+_)\\b");

    /** Rewrites SRG member names wherever they appear in a string. */
    private static String remapText(String text, Map<String, String> table) {
        java.util.regex.Matcher m = SRG_TOKEN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String official = table.get(m.group(1));
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(
                    official != null ? official : m.group(1)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Walks annotation values, rewriting SRG names in any string they contain. */
    @SuppressWarnings("unchecked")
    private static void remapStrings(java.util.List<AnnotationNode> annotations,
                                     Map<String, String> table) {
        if (annotations == null) return;
        for (AnnotationNode a : annotations) {
            if (a == null || a.values == null) continue;
            for (int i = 0; i < a.values.size(); i++) {
                a.values.set(i, remapValue(a.values.get(i), table));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Object remapValue(Object v, Map<String, String> table) {
        if (v instanceof String s) return remapText(s, table);
        if (v instanceof AnnotationNode nested) {
            if (nested.values != null) {
                for (int i = 0; i < nested.values.size(); i++) {
                    nested.values.set(i, remapValue(nested.values.get(i), table));
                }
            }
            return nested;
        }
        if (v instanceof java.util.List<?> list) {
            java.util.List<Object> out = new java.util.ArrayList<>(list.size());
            for (Object o : list) out.add(remapValue(o, table));
            return out;
        }
        return v;
    }

    private static boolean isRefmap(String name) {
        return name.endsWith(".json") && name.contains("refmap");
    }

    private static boolean isAccessTransformer(String name) {
        return name.startsWith("META-INF/") && name.endsWith(".cfg")
            && name.contains("accesstransformer");
    }
}
