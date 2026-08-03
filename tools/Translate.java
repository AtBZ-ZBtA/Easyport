import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Easyport transformer: rewrites a mod jar from Forge 1.20.1 to NeoForge 1.21.1.
 *
 * Deliberately does as little as possible. Under the shim-first architecture most loader-API
 * references need no rewriting at all — forge-compat ships a real net.minecraftforge.* tree,
 * so a mod's existing references resolve against it untouched. Rewriting is reserved for
 * cases where a shim is impossible, which in practice means net.minecraft.*: that package is
 * owned by the game and cannot be shadowed.
 *
 * Three passes, in this order:
 *
 *   1. SRG -> official member names. Forge 1.20.1 runs SRG at runtime, NeoForge runs official
 *      Mojang names, so this must happen before anything else — every later rule is written
 *      against official names and would not match otherwise.
 *   2. Structural and call-site rules from the rule file.
 *   3. Resources: descriptor rename, datapack directory renames.
 *
 * Anything it cannot translate is reported rather than guessed. Inventing a plausible target
 * for a removed API is the measured failure mode from handport/ — 5 false positives out of 26
 * symbols — and a jar that loads while quietly doing the wrong thing is worse than one that
 * refuses.
 *
 * Run:
 *   java -cp "<asm>;<asm-tree>;<asm-commons>" tools/Translate.java \
 *       <inputJar> <outputJar> <srg2official.tsv> <rules.tsv>
 */
public class Translate {

    /** 1.21 singularised the datapack tree. Mined from the corpus, not assumed. */
    private static final Map<String, String> DIR_RENAMES = new LinkedHashMap<>();
    static {
        DIR_RENAMES.put("recipes", "recipe");
        DIR_RENAMES.put("loot_tables", "loot_table");
        DIR_RENAMES.put("advancements", "advancement");
        DIR_RENAMES.put("structures", "structure");
        DIR_RENAMES.put("predicates", "predicate");
        DIR_RENAMES.put("item_modifiers", "item_modifier");
        DIR_RENAMES.put("functions", "function");
    }
    /** Tag subdirectories singularised alongside their parent registries. */
    private static final Map<String, String> TAG_RENAMES = new LinkedHashMap<>();
    static {
        TAG_RENAMES.put("blocks", "block");
        TAG_RENAMES.put("items", "item");
        TAG_RENAMES.put("fluids", "fluid");
        TAG_RENAMES.put("entity_types", "entity_type");
        TAG_RENAMES.put("game_events", "game_event");
    }

    record CtorRule(String owner, String ctorDesc, String factoryName, String factoryDesc) {}

    /** A constructor whose two arguments were reordered, optionally narrowing the new first one. */
    record SwapRule(String owner, String oldDesc, String newDesc, String castTop) {}

    /** A method that kept its shape but changed owner or name. The plain call-site rewrite. */
    record RenameRule(String owner, String name, String desc,
                      String newOwner, String newName, String newDesc) {}

    private final Map<String, String> srgToOfficial = new HashMap<>();
    private final List<CtorRule> ctorRules = new ArrayList<>();
    private final List<SwapRule> swapRules = new ArrayList<>();
    private final List<RenameRule> renameRules = new ArrayList<>();
    private final Set<String> removed = new LinkedHashSet<>();
    private final Map<String, String> typeRenames = new LinkedHashMap<>();

    private final Map<String, Integer> appliedCounts = new TreeMap<>();
    private final Map<String, Integer> unresolved = new TreeMap<>();
    private int classesRewritten = 0, resourcesMoved = 0;

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("usage: java -cp \"<asm>;<asm-tree>;<asm-commons>\" tools/Translate.java "
                             + "<inputJar> <outputJar> <srg2official.tsv> <rules.tsv>");
            System.exit(2);
        }
        new Translate().run(Paths.get(args[0]), Paths.get(args[1]),
                            Paths.get(args[2]), Paths.get(args[3]));
    }

    private void run(Path in, Path out, Path mappings, Path rules) throws Exception {
        loadMappings(mappings);
        loadRules(rules);
        System.out.printf("Loaded %d SRG mappings, %d type renames, %d ctor rules, %d removed-API entries%n",
                          srgToOfficial.size(), typeRenames.size(), ctorRules.size(), removed.size());

        Files.createDirectories(out.toAbsolutePath().getParent());
        try (ZipFile zip = new ZipFile(in.toFile());
             ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(out))) {

            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory()) continue;
                byte[] data = read(zip, e);
                String name = e.getName();

                if (name.endsWith(".class")) {
                    data = transformClass(data);
                    classesRewritten++;
                } else {
                    String moved = renamePath(name);
                    if (!moved.equals(name)) { resourcesMoved++; name = moved; }
                    if (name.equals("META-INF/neoforge.mods.toml")) data = migrateDescriptor(data);
                }
                // Signatures cover the pre-translation bytes and cannot survive rewriting; a
                // stale signature file makes the jar fail verification outright.
                if (name.startsWith("META-INF/") &&
                    (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA"))) continue;

                zos.putNextEntry(new ZipEntry(name));
                zos.write(data);
                zos.closeEntry();
            }
        }
        report(out);
    }

    // ---- bytecode ----------------------------------------------------------------------

    private byte[] transformClass(byte[] data) {
        // Pass 1: SRG -> official. Must precede rule matching, which uses official names.
        ClassReader reader = new ClassReader(data);
        ClassNode node = new ClassNode();
        reader.accept(new ClassRemapper(node, new Remapper() {
            @Override public String mapMethodName(String owner, String name, String desc) {
                return srgToOfficial.getOrDefault(name, name);
            }
            @Override public String mapFieldName(String owner, String name, String desc) {
                return srgToOfficial.getOrDefault(name, name);
            }
            @Override public String mapInvokeDynamicMethodName(String name, String desc) {
                return srgToOfficial.getOrDefault(name, name);
            }
            /**
             * Type renames, applied only to the explicitly listed types.
             *
             * Deliberately not a blanket net.minecraftforge -> net.neoforged rename: under
             * shim-first, most references should keep resolving against forge-compat. Only
             * types the loader itself scans for by name must move, because a shimmed copy is
             * a different type to that scan.
             */
            @Override public String map(String internalName) {
                String renamed = typeRenames.get(internalName);
                if (renamed != null) {
                    count(appliedCounts, "TYPE_RENAME " + internalName);
                    return renamed;
                }
                return internalName;
            }
        }), 0);

        // Pass 2: structural rules.
        for (MethodNode m : node.methods) {
            if (m.instructions == null) continue;
            applyRenameRules(m.instructions);
            applyCtorRules(m.instructions);
            applySwapRules(m.instructions);
            recordRemoved(m.instructions);
        }

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /**
     * Rewrites {@code new Foo(a, b)} into {@code Foo.factory(a, b)}.
     *
     * The constructor call is three instructions apart from its allocation: NEW and DUP run
     * before the arguments are pushed, INVOKESPECIAL after. Removing the first two and
     * switching the third to INVOKESTATIC leaves the operand stack exactly as the factory
     * expects, so this is safe without any stack analysis — but only because NEW/DUP are
     * adjacent, which javac always emits for a plain constructor call. A NEW without an
     * immediately following DUP means the result is discarded or the pattern is something
     * else entirely, so it is left alone rather than guessed at.
     */
    private void applyCtorRules(InsnList insns) {
        for (AbstractInsnNode insn : insns.toArray()) {
            if (!(insn instanceof MethodInsnNode min)) continue;
            if (min.getOpcode() != Opcodes.INVOKESPECIAL || !min.name.equals("<init>")) continue;

            CtorRule rule = null;
            for (CtorRule r : ctorRules) {
                if (r.owner().equals(min.owner) && r.ctorDesc().equals(min.desc)) { rule = r; break; }
            }
            if (rule == null) continue;

            TypeInsnNode newInsn = findMatchingNew(insns, min);
            if (newInsn == null) {
                count(unresolved, "CTOR_TO_STATIC (no NEW/DUP pair) " + min.owner + min.desc);
                continue;
            }
            AbstractInsnNode dup = newInsn.getNext();
            insns.remove(newInsn);
            insns.remove(dup);

            min.setOpcode(Opcodes.INVOKESTATIC);
            min.name = rule.factoryName();
            min.desc = rule.factoryDesc();
            min.itf = false;
            count(appliedCounts, "CTOR_TO_STATIC " + rule.owner() + "#" + rule.factoryName());
        }
    }

    /**
     * Rewrites calls to methods that kept their shape but changed owner or name.
     *
     * The simplest rule kind, and the one a rename table handles well — which is exactly the
     * 13-of-13 the corpus mining got right in the hand-port scoring. Runs before the
     * structural rules so those match against already-corrected owners.
     */
    private void applyRenameRules(InsnList insns) {
        for (AbstractInsnNode insn : insns.toArray()) {
            if (!(insn instanceof MethodInsnNode min)) continue;
            for (RenameRule r : renameRules) {
                if (!r.owner().equals(min.owner) || !r.name().equals(min.name)
                        || !r.desc().equals(min.desc)) continue;
                min.owner = r.newOwner();
                min.name = r.newName();
                min.desc = r.newDesc();
                count(appliedCounts, "RENAME_METHOD " + r.owner() + "#" + r.name()
                                     + " -> " + r.newName());
                break;
            }
        }
    }

    /**
     * Reorders the two arguments of a constructor whose signature was rearranged upstream.
     *
     * Example: {@code TorchBlock(Properties, ParticleOptions)} became
     * {@code TorchBlock(SimpleParticleType, Properties)} in 1.21 — reordered *and* narrowed.
     *
     * Both arguments are already on the operand stack in source order when INVOKESPECIAL is
     * reached, so a single SWAP reorders them. Where the new signature also narrows a type, a
     * CHECKCAST must be emitted *before* the swap, while the value being narrowed is still on
     * top — after the swap it is buried and no longer reachable without more shuffling.
     *
     * Only valid for two category-1 arguments. A long or double occupies two stack slots and
     * SWAP would corrupt it, so those are refused and reported rather than mangled.
     */
    private void applySwapRules(InsnList insns) {
        for (AbstractInsnNode insn : insns.toArray()) {
            if (!(insn instanceof MethodInsnNode min)) continue;
            if (min.getOpcode() != Opcodes.INVOKESPECIAL || !min.name.equals("<init>")) continue;

            for (SwapRule r : swapRules) {
                if (!r.owner().equals(min.owner) || !r.oldDesc().equals(min.desc)) continue;

                if (hasWideArgument(r.oldDesc())) {
                    count(unresolved, "SWAP2 refused (wide argument) " + r.owner() + r.oldDesc());
                    break;
                }
                if (r.castTop() != null && !r.castTop().isEmpty()) {
                    insns.insertBefore(min, new TypeInsnNode(Opcodes.CHECKCAST, r.castTop()));
                }
                insns.insertBefore(min, new org.objectweb.asm.tree.InsnNode(Opcodes.SWAP));
                min.desc = r.newDesc();
                count(appliedCounts, "CTOR_SWAP2 " + r.owner());
                break;
            }
        }
    }

    /** True if any argument is a long or double, which occupy two stack slots. */
    private static boolean hasWideArgument(String desc) {
        for (org.objectweb.asm.Type t : org.objectweb.asm.Type.getArgumentTypes(desc)) {
            if (t.getSort() == org.objectweb.asm.Type.LONG || t.getSort() == org.objectweb.asm.Type.DOUBLE) {
                return true;
            }
        }
        return false;
    }

    /** Walks back to the NEW that allocated the object this constructor initialises. */
    private static TypeInsnNode findMatchingNew(InsnList insns, MethodInsnNode ctor) {
        int depth = 0;
        for (AbstractInsnNode p = ctor.getPrevious(); p != null; p = p.getPrevious()) {
            if (p instanceof MethodInsnNode m && m.name.equals("<init>")) depth++;
            if (p instanceof TypeInsnNode t && t.getOpcode() == Opcodes.NEW
                    && t.desc.equals(ctor.owner)) {
                if (depth == 0) {
                    AbstractInsnNode next = t.getNext();
                    return (next != null && next.getOpcode() == Opcodes.DUP) ? t : null;
                }
                depth--;
            }
        }
        return null;
    }

    /** Records calls into APIs with no replacement, so the report can name them. */
    private void recordRemoved(InsnList insns) {
        for (AbstractInsnNode insn : insns.toArray()) {
            if (!(insn instanceof MethodInsnNode min)) continue;
            String sym = min.owner + "#" + min.name + min.desc;
            if (removed.contains(sym)) count(unresolved, "REMOVED " + sym);
        }
    }

    // ---- resources ---------------------------------------------------------------------

    /** Applies the descriptor rename and the 1.21 datapack directory singularisation. */
    private static String renamePath(String path) {
        if (path.equals("META-INF/mods.toml")) return "META-INF/neoforge.mods.toml";

        String[] p = path.split("/");
        if (p.length >= 4 && p[0].equals("data")) {
            String renamed = DIR_RENAMES.get(p[2]);
            if (renamed != null) p[2] = renamed;
            if (p[2].equals("tags") && p.length >= 5) {
                String tagRenamed = TAG_RENAMES.get(p[3]);
                if (tagRenamed != null) p[3] = tagRenamed;
            }
            return String.join("/", p);
        }
        return path;
    }

    /**
     * Migrates the mod descriptor.
     *
     * Mining showed the key set is essentially identical between loaders, so this is mostly a
     * file rename — but the *dependency declarations* are not. A renamed descriptor whose
     * dependencies still say `minecraft [1.20.1,1.21)` gets past discovery only to be refused
     * during resolution, which is a more confusing failure than being rejected outright.
     *
     * Version ranges cannot be rewritten by pattern alone: `versionRange` is the same key in
     * every dependency block, so which value it should take depends on the `modId` declared
     * above it. This tracks the enclosing block instead of matching lines in isolation.
     */
    private static byte[] migrateDescriptor(byte[] data) {
        String[] lines = new String(data, StandardCharsets.UTF_8).split("\\R", -1);
        StringBuilder out = new StringBuilder();
        String depModId = null;

        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.startsWith("[[dependencies.")) {
                depModId = null;
            } else if (trimmed.startsWith("[")) {
                depModId = null;                        // left the dependency block entirely
            }

            if (depModId == null || !trimmed.startsWith("versionRange")) {
                var m = java.util.regex.Pattern.compile("modId\\s*=\\s*\"([^\"]+)\"").matcher(line);
                if (m.find()) {
                    String id = m.group(1);
                    if (id.equals("forge")) {
                        line = line.replace("\"forge\"", "\"neoforge\"");
                        depModId = "neoforge";
                    } else {
                        depModId = id;
                    }
                }
            }

            if (trimmed.startsWith("versionRange") && depModId != null) {
                String range = switch (depModId) {
                    case "neoforge" -> "[21.1,)";
                    case "minecraft" -> "[1.21.1,1.22)";
                    default -> null;                    // another mod's range; leave it alone
                };
                if (range != null) {
                    line = line.replaceAll("versionRange\\s*=\\s*\"[^\"]*\"",
                                           "versionRange=\"" + range + "\"");
                }
            }

            // NeoForge expresses dependency strength as type=, not the older mandatory= flag.
            if (trimmed.startsWith("mandatory")) {
                line = line.replaceAll("mandatory\\s*=\\s*true", "type=\"required\"")
                           .replaceAll("mandatory\\s*=\\s*false", "type=\"optional\"");
            }

            if (trimmed.startsWith("loaderVersion")) {
                line = line.replaceAll("loaderVersion\\s*=\\s*\"[^\"]*\"", "loaderVersion=\"[4,)\"");
            }

            out.append(line).append('\n');
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ---- reporting ---------------------------------------------------------------------

    /**
     * The per-jar confidence report. Unresolved entries are the point: a translated jar that
     * loads while quietly doing the wrong thing is worse than one that admits what it could
     * not handle.
     */
    private void report(Path out) throws IOException {
        System.out.printf("%nRewrote %d classes, moved %d resources%n", classesRewritten, resourcesMoved);

        System.out.println("\nApplied:");
        if (appliedCounts.isEmpty()) System.out.println("  (none)");
        appliedCounts.forEach((k, v) -> System.out.printf("  %4d  %s%n", v, k));

        System.out.println("\nUNRESOLVED - these need the vanilla bridge or a structural rule:");
        if (unresolved.isEmpty()) System.out.println("  (none)");
        unresolved.forEach((k, v) -> System.out.printf("  %4d  %s%n", v, k));

        StringBuilder sb = new StringBuilder("status\tcount\tsymbol\n");
        appliedCounts.forEach((k, v) -> sb.append("APPLIED\t").append(v).append('\t').append(k).append('\n'));
        unresolved.forEach((k, v) -> sb.append("UNRESOLVED\t").append(v).append('\t').append(k).append('\n'));
        Path reportFile = Paths.get(out.toString().replaceAll("\\.jar$", "") + ".report.tsv");
        Files.writeString(reportFile, sb.toString(), StandardCharsets.UTF_8);
        System.out.println("\nWrote " + out + "\n      " + reportFile);
    }

    private static void count(Map<String, Integer> m, String k) { m.merge(k, 1, Integer::sum); }

    // ---- inputs ------------------------------------------------------------------------

    private void loadMappings(Path p) throws IOException {
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            String[] c = line.split("\t");
            if (c.length == 2 && !c[0].equals("srg")) srgToOfficial.put(c[0], c[1]);
        }
    }

    private void loadRules(Path p) throws IOException {
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String[] c = t.split("\t");
            switch (c[0]) {
                case "CTOR_TO_STATIC" -> {
                    if (c.length >= 5) ctorRules.add(new CtorRule(c[1], c[2], c[3], c[4]));
                }
                case "RENAME_METHOD" -> {
                    if (c.length >= 7) renameRules.add(new RenameRule(c[1], c[2], c[3], c[4], c[5], c[6]));
                }
                case "CTOR_SWAP2" -> {
                    if (c.length >= 4) swapRules.add(new SwapRule(c[1], c[2], c[3],
                                                                  c.length > 4 ? c[4] : null));
                }
                case "TYPE_RENAME" -> {
                    if (c.length >= 3) typeRenames.put(c[1], c[2]);
                }
                case "REMOVED" -> {
                    if (c.length >= 2) removed.add(c[1]);
                }
                default -> System.err.println("  unknown rule kind, ignored: " + c[0]);
            }
        }
    }

    private static byte[] read(ZipFile zip, ZipEntry e) throws IOException {
        try (InputStream in = zip.getInputStream(e)) { return in.readAllBytes(); }
    }
}
