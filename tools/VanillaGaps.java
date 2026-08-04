import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The Phase 4 work queue: every piece of *vanilla* Minecraft API the corpus uses that 1.21.1 no
 * longer has, ranked by how many jars it blocks.
 *
 * <h2>Why this is not {@link RenameGaps}</h2>
 *
 * RenameGaps asks "does this Forge type resolve to anything". For {@code net.minecraft} that
 * question is almost always yes -- the class is still there -- and it is the wrong question. The
 * 1.20.1 -> 1.21.1 migration mostly kept type names and changed what is *inside* them:
 * {@code ItemStack} still exists and no longer has {@code getTag}, {@code ArmorItem} still exists
 * and its constructor now takes a {@code Holder}. Pointed at vanilla, RenameGaps reports a clean
 * bill of health on a corpus that cannot link.
 *
 * So this tool inverts the emphasis. Missing types are a small section at the end; the body of the
 * report is members that no longer exist on types that do.
 *
 * <h2>The three findings, deliberately separated</h2>
 *
 * <ul>
 *   <li><b>SIGNATURE CHANGED</b> -- the name is still there, the descriptor is not. Nearly always
 *       a vanilla type changing shape underneath the signature: {@code Holder} wrapping,
 *       {@code Codec} -> {@code MapCodec}, {@code CompoundTag} -> a data component. The platform's
 *       real descriptors are printed alongside, because for this class of finding the fix is
 *       usually visible in the diff between the two.</li>
 *   <li><b>MEMBER GONE</b> -- the name is absent entirely. Something was deleted or renamed, and
 *       the report cannot tell which; each needs a rule or a bridge.</li>
 *   <li><b>TYPE GONE</b> -- the class itself was deleted. Needs relocate-then-rename, since a mod
 *       jar cannot supply a class under {@code net.minecraft} (see STATE gotcha, proven in
 *       testkit/vanilla-package-probe).</li>
 * </ul>
 *
 * Separating them is not cosmetic. They have different fixes and wildly different cost, and a
 * single merged list sorted by jar count interleaves one-line rules with analysis passes.
 *
 * <h2>Guards against reporting noise as work</h2>
 *
 * Everything here was learned by RenameGaps getting it wrong first:
 *
 * <ul>
 *   <li>SRG member names are mapped to official ones before comparison. Forge 1.20.1 bytecode
 *       calls {@code m_41783_}; the platform declares {@code getTag}. Skipping this made 18% of
 *       RenameGaps' findings noise.</li>
 *   <li>Inherited members count as present. A mod calling {@code Block.defaultBlockState()} is
 *       calling something real even though {@code BlockBehaviour} declares it.</li>
 *   <li>Members a rule already redirects are skipped, so completed work leaves the queue.</li>
 *   <li>Names that still look SRG after mapping are counted and reported as a health figure
 *       rather than silently swelling the gap list -- the same check that turned vanilla mining
 *       from 74.8% noise into 0.0%.</li>
 * </ul>
 *
 * Usage:
 *   java -cp asm.jar tools/VanillaGaps.java \
 *       api-report/vanilla-api-usage.txt rules/forward.rules.tsv mappings/srg2official.tsv \
 *       <platform.jar>...
 */
public class VanillaGaps {

    /** Findings below this many jars are summarised rather than listed. */
    private static final int LIST_THRESHOLD = 3;

    public static void main(String[] args) throws IOException {
        if (args.length < 4) {
            System.err.println("usage: VanillaGaps <vanilla-scan.txt> <rules.tsv> "
                             + "<srg2official.tsv> <platform.jar>...");
            System.exit(2);
        }

        Map<String, Integer> referencedTypes = readTypes(Path.of(args[0]));
        Rules rules = Rules.read(Path.of(args[1]));
        Map<String, String> srg = readSrg(Path.of(args[2]));

        List<Path> platformJars = new ArrayList<>();
        for (int i = 3; i < args.length; i++) platformJars.add(Path.of(args[i]));

        Index index = Index.build(platformJars);

        // ---- types ---------------------------------------------------------------------

        List<Finding> missingTypes = new ArrayList<>();
        for (var e : referencedTypes.entrySet()) {
            String type = e.getKey();
            String target = rules.rename(type);
            if (target != null) continue;               // a rule already moves it somewhere
            if (index.classes.contains(type)) continue;
            missingTypes.add(new Finding(e.getValue(), type, "", ""));
        }

        // ---- members -------------------------------------------------------------------

        List<Finding> signatureChanged = new ArrayList<>();
        List<Finding> memberGone = new ArrayList<>();
        int srgResidue = 0, checked = 0, ok = 0, skippedByRule = 0, ownerMissing = 0;

        for (MemberRef ref : readMembers(Path.of(args[0]))) {
            String owner = ref.owner();
            String renamed = rules.rename(owner);
            String effectiveOwner = renamed != null ? renamed : owner;

            // A type this report already lists as gone would otherwise contribute one finding per
            // member it declares, burying everything else under a single deleted class.
            if (!index.classes.contains(effectiveOwner)) { ownerMissing++; continue; }

            String name = official(ref.name(), srg);
            if (looksSrg(name)) { srgResidue++; continue; }

            String desc = remapDescriptor(ref.desc(), rules, index.classes);
            if (rules.redirects(owner, effectiveOwner, name, desc)) { skippedByRule++; continue; }
            if (rules.removedMember(owner, ref.name(), name, ref.desc(), desc)) {
                skippedByRule++;
                continue;
            }

            checked++;
            Set<String> members = index.membersOf(effectiveOwner);
            if (members.contains(name + " " + desc)) { ok++; continue; }

            Set<String> sameName = index.descriptorsOf(effectiveOwner, name);
            if (!sameName.isEmpty()) {
                // Printing the platform's real descriptors is most of the fix for this class of
                // finding: the change is usually one parameter, and seeing the two side by side
                // says which type moved and to what.
                String have = String.join("  |  ", trim(sameName, 3));
                signatureChanged.add(new Finding(ref.jars(),
                        effectiveOwner + "." + name,
                        "calls " + desc,
                        "  has  " + have));
            } else {
                memberGone.add(new Finding(ref.jars(),
                        effectiveOwner + "." + name,
                        "calls " + desc,
                        ref.kind().equals("F") ? "  (field)" : ""));
            }
        }

        // ---- report --------------------------------------------------------------------

        signatureChanged.sort(Finding.BY_JARS);
        memberGone.sort(Finding.BY_JARS);
        missingTypes.sort(Finding.BY_JARS);

        System.out.println("=== VANILLA DRIFT, 1.20.1 -> 1.21.1, measured on the corpus ===");
        System.out.println();
        System.out.printf("referenced vanilla types      %d%n", referencedTypes.size());
        System.out.printf("member references checked     %d%n", checked);
        System.out.printf("  still resolve               %d  (%.1f%%)%n",
                ok, checked == 0 ? 0.0 : 100.0 * ok / checked);
        System.out.printf("  SIGNATURE CHANGED           %d%n", signatureChanged.size());
        System.out.printf("  MEMBER GONE                 %d%n", memberGone.size());
        System.out.printf("TYPE GONE                     %d%n", missingTypes.size());
        System.out.println();
        System.out.printf("skipped, a rule handles them  %d%n", skippedByRule);
        System.out.printf("skipped, owner type is gone   %d%n", ownerMissing);
        System.out.printf("skipped, name still reads SRG %d  <- mapping health; want 0%n", srgResidue);
        System.out.println();
        System.out.println("Jar counts, not call sites. One mod calling something forty times is");
        System.out.println("one mod's worth of evidence.");

        section("SIGNATURE CHANGED", signatureChanged,
                "The member is still there and takes something else. This is Phase 4's central",
                "problem -- a vanilla type changed shape underneath the signature. Read the two",
                "descriptors together; the difference names the type that moved.");

        section("MEMBER GONE", memberGone,
                "No member of that name survives on the owner. Needs a rename rule, a bridge, or",
                "a REMOVED entry so the translate report names it rather than the JVM.");

        section("TYPE GONE", missingTypes,
                "The class itself was deleted. A mod jar cannot supply a replacement under",
                "net.minecraft -- module resolution refuses it -- so these are relocate-then-rename:",
                "a stand-in under easyport.vanilla plus a TYPE_RENAME.");

        // Owner-level rollup. The ranked lists say which single member to fix; this says which
        // *subsystem* to fix, which is a different and usually better-value question -- forty
        // one-jar findings on one class beat one forty-jar finding on a class nothing else uses.
        Map<String, int[]> byOwner = new TreeMap<>();
        for (Finding f : signatureChanged) tallyOwner(byOwner, f, 0);
        for (Finding f : memberGone) tallyOwner(byOwner, f, 1);
        List<Map.Entry<String, int[]>> owners = new ArrayList<>(byOwner.entrySet());
        owners.sort((a, b) -> (b.getValue()[2]) - (a.getValue()[2]));
        System.out.println();
        System.out.println("=== BY OWNING TYPE (jar-weighted) ===");
        System.out.println("weight = sum of jar counts over that type's broken members. Fix the");
        System.out.println("head of this list, not the head of the lists above.");
        System.out.println();
        System.out.printf("%8s  %5s  %5s  %s%n", "weight", "sig", "gone", "type");
        for (var e : owners) {
            if (e.getValue()[2] < 10) continue;
            System.out.printf("%8d  %5d  %5d  %s%n",
                    e.getValue()[2], e.getValue()[0], e.getValue()[1], e.getKey());
        }
    }

    private static void tallyOwner(Map<String, int[]> byOwner, Finding f, int slot) {
        int dot = f.symbol().lastIndexOf('.');
        String owner = dot < 0 ? f.symbol() : f.symbol().substring(0, dot);
        int[] cell = byOwner.computeIfAbsent(owner, k -> new int[3]);
        cell[slot]++;
        cell[2] += f.jars();
    }

    private static void section(String title, List<Finding> findings, String... blurb) {
        System.out.println();
        System.out.println("=== " + title + " (" + findings.size() + ") ===");
        for (String b : blurb) System.out.println(b);
        System.out.println();
        int suppressed = 0;
        for (Finding f : findings) {
            if (f.jars() < LIST_THRESHOLD) { suppressed++; continue; }
            System.out.printf("%5d  %s%n", f.jars(), f.symbol());
            if (!f.detail().isEmpty()) System.out.printf("       %s%n", f.detail());
            if (!f.extra().isEmpty()) System.out.printf("       %s%n", f.extra());
        }
        if (suppressed > 0) {
            System.out.printf("%n       ... and %d more used by fewer than %d jars%n",
                    suppressed, LIST_THRESHOLD);
        }
    }

    private static List<String> trim(Set<String> s, int n) {
        List<String> l = new ArrayList<>(s);
        return l.size() > n ? l.subList(0, n) : l;
    }

    private record Finding(int jars, String symbol, String detail, String extra) {
        static final java.util.Comparator<Finding> BY_JARS =
                (a, b) -> b.jars() != a.jars() ? b.jars() - a.jars()
                                               : a.symbol().compareTo(b.symbol());
    }

    // ---- platform index ----------------------------------------------------------------

    /** Classes and their members, with inheritance folded in. */
    private static final class Index {
        final Set<String> classes = new HashSet<>();
        private final Map<String, Set<String>> declared = new HashMap<>();
        private final Map<String, String> superOf = new HashMap<>();
        private final Map<String, List<String>> interfacesOf = new HashMap<>();
        private final Map<String, Set<String>> resolvedCache = new HashMap<>();
        private final Map<String, Set<String>> jdkCache = new HashMap<>();

        static Index build(List<Path> jars) throws IOException {
            Index idx = new Index();
            for (Path jar : jars) {
                try (ZipFile zf = new ZipFile(jar.toFile())) {
                    var entries = zf.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry e = entries.nextElement();
                        if (!e.getName().endsWith(".class")) continue;
                        try (var in = zf.getInputStream(e)) {
                            var reader = new org.objectweb.asm.ClassReader(in);
                            Set<String> members = new HashSet<>();
                            reader.accept(new org.objectweb.asm.ClassVisitor(
                                    org.objectweb.asm.Opcodes.ASM9) {
                                @Override public org.objectweb.asm.MethodVisitor visitMethod(
                                        int a, String n, String d, String s, String[] ex) {
                                    members.add(n + " " + d);
                                    return null;
                                }
                                @Override public org.objectweb.asm.FieldVisitor visitField(
                                        int a, String n, String d, String s, Object v) {
                                    members.add(n + " " + d);
                                    return null;
                                }
                            }, org.objectweb.asm.ClassReader.SKIP_CODE);
                            String cn = reader.getClassName();
                            idx.classes.add(cn);
                            idx.declared.put(cn, members);
                            if (reader.getSuperName() != null) idx.superOf.put(cn, reader.getSuperName());
                            idx.interfacesOf.put(cn, List.of(reader.getInterfaces()));
                        } catch (Exception ignored) {
                            // Unparseable class. Still record the name from the entry path so it
                            // does not read as a deleted type.
                            String n = e.getName();
                            idx.classes.add(n.substring(0, n.length() - 6));
                        }
                    }
                }
            }
            return idx;
        }

        Set<String> membersOf(String cls) {
            Set<String> hit = resolvedCache.get(cls);
            if (hit != null) return hit;
            Set<String> all = new HashSet<>();
            java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
            Set<String> seen = new HashSet<>();
            queue.add(cls);
            while (!queue.isEmpty()) {
                String k = queue.poll();
                if (!seen.add(k)) continue;
                Set<String> own = declared.get(k);
                if (own == null) own = jdk(k);          // supertype outside the platform jars
                if (own != null) all.addAll(own);
                String sup = superOf.get(k);
                if (sup != null) queue.add(sup);
                List<String> ifs = interfacesOf.get(k);
                if (ifs != null) queue.addAll(ifs);
            }
            resolvedCache.put(cls, all);
            return all;
        }

        /**
         * Members of a class the platform jars do not contain -- in practice the JDK.
         *
         * Without this, {@code ListTag.add(Object)} was the single largest finding in the report
         * at 146 jars, and it is not a finding at all: {@code ListTag} extends
         * {@code java.util.AbstractList}, so {@code add} resolves exactly as it always did. Any
         * vanilla class extending a JDK type produced the same phantom, and they would have been
         * worked in rank order, top of the list first.
         *
         * Read through the running JVM rather than a jar, since java.base has no jar to open.
         * Anything genuinely unavailable records an empty member set, which restores the old
         * behaviour for that one class instead of failing the run.
         */
        private Set<String> jdk(String internalName) {
            Set<String> cached = jdkCache.get(internalName);
            if (cached != null) return cached;
            Set<String> members = new HashSet<>();
            try (var in = ClassLoader.getSystemResourceAsStream(internalName + ".class")) {
                if (in != null) {
                    var reader = new org.objectweb.asm.ClassReader(in);
                    reader.accept(new org.objectweb.asm.ClassVisitor(
                            org.objectweb.asm.Opcodes.ASM9) {
                        @Override public org.objectweb.asm.MethodVisitor visitMethod(
                                int a, String n, String d, String s, String[] ex) {
                            members.add(n + " " + d);
                            return null;
                        }
                        @Override public org.objectweb.asm.FieldVisitor visitField(
                                int a, String n, String d, String s, Object v) {
                            members.add(n + " " + d);
                            return null;
                        }
                    }, org.objectweb.asm.ClassReader.SKIP_CODE);
                    if (reader.getSuperName() != null) superOf.put(internalName, reader.getSuperName());
                    interfacesOf.put(internalName, List.of(reader.getInterfaces()));
                }
            } catch (Exception ignored) {
                // Not on the system class path. Empty set; the member reads as missing, which is
                // what this tool did for every such class before.
            }
            jdkCache.put(internalName, members);
            return members;
        }

        /** Every descriptor the platform declares for this member name on this type. */
        Set<String> descriptorsOf(String cls, String name) {
            Set<String> out = new java.util.LinkedHashSet<>();
            String prefix = name + " ";
            for (String m : membersOf(cls)) {
                if (m.startsWith(prefix)) out.add(m.substring(prefix.length()));
            }
            return out;
        }
    }

    // ---- rules -------------------------------------------------------------------------

    private record Rules(Map<String, String> exact, Map<String, String> prefix,
                         Set<String> redirectedMembers, Set<String> removedSymbols,
                         Map<String, String> fieldRetypes, Set<String> ctorOwners) {

        String rename(String type) {
            String hit = exact.get(type);
            if (hit != null) return hit;
            for (var e : prefix.entrySet()) {
                if (type.startsWith(e.getKey())) {
                    return e.getValue() + type.substring(e.getKey().length());
                }
            }
            return null;
        }

        boolean redirects(String owner, String renamedOwner, String name, String desc) {
            if (redirectedMembers.contains(owner + "\t" + name + "\t" + desc)) return true;
            if (renamedOwner != null
                    && redirectedMembers.contains(renamedOwner + "\t" + name + "\t" + desc)) {
                return true;
            }
            // FIELD_RETYPE rewrites every field read on the owner, so a stale descriptor on one
            // of its fields is already handled and does not belong in the queue.
            if (fieldRetypes.containsKey(owner)) return true;
            // Constructors are handled by CTOR_TO_STATIC / CTOR_SWAP2, which the member check
            // cannot model -- they change the shape of the call, not just its target.
            return name.equals("<init>") && ctorOwners.contains(owner);
        }

        /** Whether a REMOVED entry already names this call, in either mapping namespace. */
        boolean removedMember(String owner, String srgName, String officialName,
                              String srgDesc, String officialDesc) {
            return removedSymbols.contains(owner + "#" + officialName + officialDesc)
                || removedSymbols.contains(owner + "#" + srgName + srgDesc)
                || removedSymbols.contains(owner + "#" + officialName + srgDesc);
        }

        static Rules read(Path p) throws IOException {
            Map<String, String> exact = new LinkedHashMap<>();
            Map<String, String> prefix = new LinkedHashMap<>();
            Set<String> redirected = new HashSet<>();
            Set<String> removed = new HashSet<>();
            Map<String, String> retypes = new LinkedHashMap<>();
            Set<String> ctorOwners = new HashSet<>();
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                String[] c = t.split("\t");
                switch (c[0]) {
                    case "TYPE_RENAME" -> { if (c.length >= 3) exact.put(c[1], c[2]); }
                    case "TYPE_PREFIX_RENAME" -> { if (c.length >= 3) prefix.put(c[1], c[2]); }
                    case "REMOVED" -> { if (c.length >= 2) removed.add(c[1]); }
                    case "FIELD_RETYPE" -> { if (c.length >= 3) retypes.put(c[1], c[2]); }
                    case "CTOR_TO_STATIC", "CTOR_SWAP2" -> { if (c.length >= 2) ctorOwners.add(c[1]); }
                    case "RENAME_METHOD", "METHOD_TO_STATIC", "FIELD_TO_STATIC" -> {
                        if (c.length >= 4) redirected.add(c[1] + "\t" + c[2] + "\t" + c[3]);
                    }
                    default -> { }
                }
            }
            return new Rules(exact, prefix, redirected, removed, retypes, ctorOwners);
        }
    }

    // ---- scan input --------------------------------------------------------------------

    private record MemberRef(String kind, String owner, String name, String desc, int jars) {}

    private static Map<String, Integer> readTypes(Path p) throws IOException {
        Map<String, Integer> out = new LinkedHashMap<>();
        boolean in = false;
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            if (line.startsWith("=== TYPES")) { in = true; continue; }
            if (line.startsWith("=== MEMBERS")) break;
            if (!in) continue;
            String t = line.trim();
            if (t.isEmpty()) continue;
            int sp = t.indexOf(' ');
            if (sp < 0) continue;
            try {
                out.put(t.substring(sp).trim(), Integer.parseInt(t.substring(0, sp)));
            } catch (NumberFormatException ignored) {
                // header or stray line
            }
        }
        return out;
    }

    private static List<MemberRef> readMembers(Path p) throws IOException {
        List<MemberRef> out = new ArrayList<>();
        boolean in = false;
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            if (line.startsWith("=== MEMBERS")) { in = true; continue; }
            if (!in) continue;
            String t = line.trim();
            if (t.isEmpty()) continue;
            String[] parts = t.split("\\s+");
            if (parts.length < 4) continue;
            int jars;
            try {
                jars = Integer.parseInt(parts[0]);
            } catch (NumberFormatException e) {
                continue;
            }
            String ownerAndName = parts[2];
            int dot = ownerAndName.lastIndexOf('.');
            if (dot < 0) continue;
            out.add(new MemberRef(parts[1], ownerAndName.substring(0, dot),
                    ownerAndName.substring(dot + 1), parts[3], jars));
        }
        return out;
    }

    // ---- mappings ----------------------------------------------------------------------

    private static Map<String, String> readSrg(Path p) throws IOException {
        Map<String, String> out = new HashMap<>();
        if (!Files.isRegularFile(p)) return out;
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            int tab = line.indexOf('\t');
            if (tab <= 0) continue;
            out.put(line.substring(0, tab), line.substring(tab + 1).trim());
        }
        return out;
    }

    private static String official(String name, Map<String, String> srg) {
        String mapped = srg.get(name);
        return mapped != null ? mapped : name;
    }

    /**
     * An SRG name the mapping table did not cover: {@code m_41783_}, {@code f_19853_}.
     *
     * These are counted and excluded rather than reported. An unmapped SRG name is guaranteed to
     * be absent from the platform, so every one of them would show up as a confident finding on
     * a member that may well be perfectly fine -- the exact failure mode that made 74.8% of an
     * earlier vanilla survey meaningless.
     */
    private static boolean looksSrg(String name) {
        return name.matches("[mfp]_\\d+_");
    }

    private static String remapDescriptor(String desc, Rules rules, Set<String> platform) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < desc.length()) {
            char ch = desc.charAt(i);
            if (ch == 'L') {
                int end = desc.indexOf(';', i);
                if (end < 0) { sb.append(desc.substring(i)); break; }
                String type = desc.substring(i + 1, end);
                String mapped = rules.rename(type);
                sb.append('L').append(mapped != null && platform.contains(mapped) ? mapped : type)
                  .append(';');
                i = end + 1;
            } else {
                sb.append(ch);
                i++;
            }
        }
        return sb.toString();
    }
}
